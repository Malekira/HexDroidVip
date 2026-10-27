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
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bounded image fetcher for server-supplied images (network icons, metadata avatars). Callers must
 * first check HTTPS, the image-previews opt-in and an unproxied profile, since this bypasses any
 * proxy. Byte and pixel caps, short timeouts, no redirects; failures mean no image.
 */
object RemoteBitmaps {

    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) =
            size > MAX_CACHED
    }

    private val inFlight = HashMap<String, Deferred<Bitmap?>>()

    /** When each URL's last background load failed. */
    private val failedAt = HashMap<String, Long>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val MAX_CACHED = 64
    private const val MAX_BYTES = 262_144

    /** Longest decoded edge. The byte cap alone does not bound memory: a flat image decodes huge. */
    private const val MAX_EDGE_PX = 256

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val RETRY_AFTER_MS = 10 * 60_000L

    /** Cached bitmap for [url], or null when it has not been fetched yet. */
    fun cached(url: String): Bitmap? = synchronized(cache) { cache[url] }

    /**
     * Fetch and decode [url]. Returns null on any failure, oversize response or undecodable
     * payload. A cached result short-circuits and concurrent calls for one URL share a request.
     */
    suspend fun fetch(url: String): Bitmap? {
        cached(url)?.let { return it }
        val request = synchronized(inFlight) {
            inFlight[url] ?: scope.async { download(url) }.also { job ->
                inFlight[url] = job
                job.invokeOnCompletion { synchronized(inFlight) { inFlight.remove(url) } }
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

    private fun download(url: String): Bitmap? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = false
        try {
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
            decodeSampled(buf.toByteArray())?.also { bmp ->
                synchronized(cache) { cache[url] = bmp }
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** Decode [bytes] with the longest edge no larger than [MAX_EDGE_PX]. */
    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight))
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** The power of two that brings [edgePx] within [MAX_EDGE_PX]. */
    private fun sampleSizeFor(edgePx: Int): Int {
        var sample = 1
        while (edgePx / sample > MAX_EDGE_PX) sample *= 2
        return sample
    }
}
