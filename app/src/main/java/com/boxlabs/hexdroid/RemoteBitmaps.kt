/*
 * HexDroidIRC - An IRC Client for Android
 * Copyright (C) 2026 boxlabs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.boxlabs.hexdroid

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bounded image fetcher for server-supplied images (network icons, metadata avatars). Callers must
 * first check HTTPS, the image-previews opt-in and an unproxied profile, since this bypasses any
 * proxy. Byte and pixel caps, four concurrent downloads, short timeouts and at most three HTTPS redirects.
 */
object RemoteBitmaps {

    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) =
            size > MAX_CACHED
    }

    private val inFlight = HashMap<String, Deferred<Bitmap?>>()
    private val downloads = Semaphore(4)
    private val largeCache = object : LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > 4
    }

    /** When each URL's last background load failed. */
    private val failedAt = HashMap<String, Long>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val MAX_CACHED = 64
    private const val MAX_BYTES = 2 * 1024 * 1024

    /** Longest decoded edge. The byte cap alone does not bound memory: a flat image decodes huge. */
    private const val MAX_EDGE_PX = 256

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val RETRY_AFTER_MS = 30_000L

    /** Cached bitmap for [url], or null when it has not been fetched yet. */
    fun cached(url: String, fullSize: Boolean = false): Bitmap? {
        val selected = if (fullSize) largeCache else cache
        return synchronized(selected) { selected[url] }
    }

    /**
     * Fetch and decode [url]. Returns null on any failure, oversize response or undecodable
     * payload. A cached result short-circuits and concurrent calls for one URL share a request.
     */
    suspend fun fetch(url: String, fullSize: Boolean = false, retry: Boolean = false): Bitmap? {
        if (url.isBlank() || !validAvatarUrl(url)) return null
        cached(url, fullSize)?.let { return it }
        val key = (if (fullSize) "large:" else "small:") + url
        synchronized(failedAt) {
            if (retry) failedAt.remove(key)
            else if (failedAt[key]?.let { System.currentTimeMillis() - it < RETRY_AFTER_MS } == true) return null
        }
        val request = synchronized(inFlight) {
            inFlight[key] ?: scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                val image = downloads.withPermit { download(url, fullSize) }
                synchronized(failedAt) {
                    if (image == null) {
                        if (failedAt.size > 128) failedAt.clear()
                        failedAt[key] = System.currentTimeMillis()
                    } else failedAt.remove(key)
                }
                image
            }.also { job ->
                inFlight[key] = job
                job.invokeOnCompletion { synchronized(inFlight) { inFlight.remove(key) } }
            }
        }
        return request.await()
    }

    /**
     * Fetch [url] in the background and pass the image to [onLoaded] when it arrives. A URL that
     * failed is not tried again for [RETRY_AFTER_MS].
     */
    fun load(url: String, onLoaded: (Bitmap) -> Unit) {
        synchronized(failedAt) {
            val at = failedAt[url]
            if (at != null && System.currentTimeMillis() - at < RETRY_AFTER_MS) return
        }
        scope.launch {
            val bmp = fetch(url)
            if (bmp == null) {
                synchronized(failedAt) {
                    if (failedAt.size >= MAX_CACHED) failedAt.clear()
                    failedAt[url] = System.currentTimeMillis()
                }
            } else {
                runCatching { onLoaded(bmp) }
            }
        }
    }

    private fun download(url: String, fullSize: Boolean, redirects: Int = 0): Bitmap? = runCatching {
        if (!validAvatarUrl(url) || url.isBlank()) return@runCatching null
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = false
        try {
            if (conn.responseCode in setOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location") ?: return@runCatching null
                val target = URL(URL(url), location).toString()
                if (redirects >= 3 || !validAvatarUrl(target)) return@runCatching null
                conn.disconnect()
                return@runCatching download(target, fullSize, redirects + 1)?.also {
                    val selected = if (fullSize) largeCache else cache
                    synchronized(selected) { selected[url] = it }
                    if (fullSize) cached(target)?.let { small -> synchronized(cache) { cache[url] = small } }
                }
            }
            if (conn.responseCode !in 200..299) return@runCatching null
            if (conn.contentLengthLong > MAX_BYTES) return@runCatching null
            val buf = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                // readNBytes needs API 33; minSdk is 26.
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > MAX_BYTES) return@runCatching null
                }
            }
            decodeSampled(buf.toByteArray(), if (fullSize) 1024 else MAX_EDGE_PX)?.also { bmp ->
                val selected = if (fullSize) largeCache else cache
                synchronized(selected) { selected[url] = bmp }
                if (fullSize) {
                    val ratio = minOf(1f, MAX_EDGE_PX.toFloat() / maxOf(bmp.width, bmp.height))
                    val small = Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * ratio).toInt()), maxOf(1, (bmp.height * ratio).toInt()), true)
                    synchronized(cache) { cache[url] = small }
                }
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** Decode [bytes] with the longest edge no larger than [MAX_EDGE_PX]. */
    private fun decodeSampled(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight), maxEdge)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** The power of two that brings [edgePx] within [MAX_EDGE_PX]. */
    private fun sampleSizeFor(edgePx: Int, maxEdge: Int): Int {
        var sample = 1
        while (edgePx / sample > maxEdge) sample *= 2
        return sample
    }
}
