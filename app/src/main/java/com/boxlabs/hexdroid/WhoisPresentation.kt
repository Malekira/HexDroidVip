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

/** Late numerics after an incomplete timeout update the same window instead of duplicating its id. */
internal fun mergeWhoisWindow(windows: List<WhoisWindow>, incoming: WhoisWindow): List<WhoisWindow> {
    val old = windows.find { it.id == incoming.id && it.network == incoming.network } ?: return windows + incoming
    val merged = old.copy(fields = old.fields + incoming.fields, lines = old.lines + incoming.lines)
    return windows.map { if (it.id == old.id && it.network == old.network) merged else it }
}
