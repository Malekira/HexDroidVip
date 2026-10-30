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

package com.boxlabs.hexdroid.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.boxlabs.hexdroid.RemoteBitmaps

/**
 * [RemoteBitmaps] for Compose. Callers must first check HTTPS, the image-previews opt-in and an
 * unproxied profile, since fetching bypasses any proxy.
 */
object RemoteImage {

    /** Cached image for [url], or null when it has not been fetched yet. */
    fun cached(url: String): ImageBitmap? = RemoteBitmaps.cached(url)?.asImageBitmap()

    /** Fetch and decode [url]; null on any failure. */
    suspend fun fetch(url: String): ImageBitmap? = RemoteBitmaps.fetch(url)?.asImageBitmap()

    suspend fun fetchFull(url: String, retry: Boolean): ImageBitmap? = RemoteBitmaps.fetch(url, fullSize = true, retry = retry)?.asImageBitmap()
}
