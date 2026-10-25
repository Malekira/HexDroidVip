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

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import com.boxlabs.hexdroid.R

/** Launch a document picker for [input], or say so when the device has none. */
fun <I> ActivityResultLauncher<I>.launchPicker(ctx: Context, input: I) {
    try {
        launch(input)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, R.string.no_file_picker, Toast.LENGTH_SHORT).show()
    }
}

/** Start [intent], or say so when no app on the device handles it. */
fun Context.startActivityOrToast(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.no_app_for_action, Toast.LENGTH_SHORT).show()
    }
}
