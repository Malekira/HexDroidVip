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
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/** Avatars for notification people, conversation shortcuts and bubbles. */
object Avatars {

    // Small, since every message in a conversation notification carries its sender's avatar.
    private const val SIZE = 64
    private const val ADAPTIVE_SIZE = 108
    private const val ADAPTIVE_PHOTO_SIZE = 144

    /** Share of an adaptive icon the launcher shows; the rest is margin for its mask. */
    private const val ADAPTIVE_VISIBLE = 72f / 108f

    private const val MAX_PHOTOS = 32

    /** Metadata avatar URLs that notifications may fetch, per network id, keyed by casefolded nick. */
    @Volatile
    private var allowedUrls: Map<String, Map<String, String>> = emptyMap()

    /** Cropped photos keyed by kind and URL, each with the source bitmap it was made from. */
    private val photos = object : LinkedHashMap<String, Pair<Bitmap, Bitmap>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Bitmap, Bitmap>>) =
            size > MAX_PHOTOS
    }

    /** Replace the avatar URLs notifications may use. */
    fun setAllowedUrls(urls: Map<String, Map<String, String>>) {
        allowedUrls = urls
    }

    /** The metadata avatar URL notifications may use for [nick] on [networkId], if any. */
    fun urlFor(networkId: String, nick: String): String? =
        allowedUrls[networkId]?.get(nick.trim().lowercase())

    /** The first letter or digit of [name], ignoring channel and status prefixes. */
    fun initialOf(name: String): String {
        val c = name.firstOrNull { it.isLetterOrDigit() } ?: name.firstOrNull { !it.isWhitespace() } ?: '?'
        return c.uppercaseChar().toString()
    }

    /** A stable hue for [name], so the same nick always gets the same colour. */
    fun hueOf(name: String): Float {
        var h = 0
        for (ch in name.lowercase(java.util.Locale.ROOT)) h = h * 31 + ch.code
        return ((h % 360) + 360) % 360f
    }

    /**
     * A circle in [name]'s colour with its initial. With [adaptive], a full square sized for an
     * adaptive icon, with the letter inside the safe zone.
     */
    fun letterAvatar(name: String, adaptive: Boolean = false): Bitmap {
        val size = if (adaptive) ADAPTIVE_SIZE else SIZE
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.HSVToColor(floatArrayOf(hueOf(name), 0.55f, 0.72f))
        }
        if (adaptive) {
            canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), fill)
        } else {
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, fill)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            // The adaptive safe zone is the middle 66/108 of the icon.
            textSize = if (adaptive) size * 0.30f else size * 0.50f
        }
        val y = size / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(initialOf(name), size / 2f, y, text)
        return bmp
    }

    /** [photo] from [url], centre-cropped into a circle the size of a lettered avatar. */
    fun circlePhoto(url: String, photo: Bitmap): Bitmap = memo("c:$url", photo) {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(coverMatrix(photo, RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat())))
            }
        }
        Canvas(bmp).drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, paint)
        bmp
    }

    /**
     * [photo] from [url] as an adaptive icon: centre-cropped to fill the part the launcher shows,
     * on its average colour.
     */
    fun adaptivePhoto(url: String, photo: Bitmap): Bitmap = memo("a:$url", photo) {
        val size = ADAPTIVE_PHOTO_SIZE
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val average = Bitmap.createScaledBitmap(photo, 1, 1, true).getPixel(0, 0)
        canvas.drawColor(average or 0xFF000000.toInt())
        val inset = size * (1f - ADAPTIVE_VISIBLE) / 2f
        val dst = RectF(inset, inset, size - inset, size - inset)
        canvas.drawBitmap(photo, coverSource(photo), dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        bmp
    }

    private fun memo(key: String, source: Bitmap, make: () -> Bitmap): Bitmap {
        synchronized(photos) { photos[key]?.takeIf { it.first === source }?.let { return it.second } }
        val made = make()
        synchronized(photos) { photos[key] = source to made }
        return made
    }

    /** The centred square of [photo]. */
    private fun coverSource(photo: Bitmap): Rect {
        val edge = minOf(photo.width, photo.height)
        val left = (photo.width - edge) / 2
        val top = (photo.height - edge) / 2
        return Rect(left, top, left + edge, top + edge)
    }

    /** Scales and centres [photo] so it covers [dst]. */
    private fun coverMatrix(photo: Bitmap, dst: RectF): Matrix {
        val src = coverSource(photo)
        val scale = dst.width() / src.width()
        return Matrix().apply {
            setTranslate(-src.left.toFloat(), -src.top.toFloat())
            postScale(scale, scale)
            postTranslate(dst.left, dst.top)
        }
    }
}
