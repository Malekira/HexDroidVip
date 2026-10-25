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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boxlabs.hexdroid.R

private const val DEBUG_RAW_LINES = 100

/**
 * The debug report, previewed before it is copied. Raw lines are left out unless asked for,
 * since they can hold other people's messages.
 */
@Composable
fun DebugInfoDialog(build: (rawLines: Int) -> String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var includeRaw by remember { mutableStateOf(false) }
    val report = remember(includeRaw) { runCatching { build(if (includeRaw) DEBUG_RAW_LINES else 0) }.getOrElse { it.toString() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.debug_info_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.debug_info_raw))
                        Text(
                            stringResource(R.string.debug_info_raw_desc, DEBUG_RAW_LINES),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = includeRaw,
                        onCheckedChange = { includeRaw = it },
                        modifier = Modifier.focusHighlight(RoundedCornerShape(16.dp)),
                    )
                }
                Surface(
                    tonalElevation = 2.dp,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                ) {
                    SelectionContainer {
                        Text(
                            report,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(8.dp),
                        )
                    }
                }
                Text(
                    stringResource(R.string.debug_info_check),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    copyToClipboard(ctx, report)
                    onDismiss()
                },
                modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
            ) { Text(stringResource(R.string.debug_info_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

private fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    runCatching { cm.setPrimaryClip(ClipData.newPlainText("HexDroid debug info", text)) }
    // Android 13 and later show their own confirmation.
    if (android.os.Build.VERSION.SDK_INT < 33) {
        Toast.makeText(ctx, ctx.getString(R.string.debug_info_copied), Toast.LENGTH_SHORT).show()
    }
}
