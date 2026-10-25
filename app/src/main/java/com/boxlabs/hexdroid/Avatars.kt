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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** Lettered avatars for notification people, conversation shortcuts and bubbles. */
object Avatars {

    // Small, since every message in a conversation notification carries its sender's avatar.
    private const val SIZE = 64
    private const val ADAPTIVE_SIZE = 108

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
}
