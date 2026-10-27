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

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.R
import com.boxlabs.hexdroid.WhoisWindow
import kotlin.math.roundToInt

/** An ordinary Compose child: no Dialog, OS overlay, navigation or IRC buffer. */
@Composable
fun WhoisOverlay(windows: List<WhoisWindow>, networks: Map<String, String>, onMinimize: (Long, Boolean) -> Unit, onClose: (Long) -> Unit, onMove: (Long, Float, Float) -> Unit) {
    if (windows.isEmpty()) return
    val active = windows.lastOrNull { !it.minimized }
    val clipboard = LocalClipboardManager.current
    val minimizeLabel = stringResource(R.string.p2_minimize)
    val closeLabel = stringResource(R.string.p2_close)
    val restoreLabel = stringResource(R.string.p2_restore)
    BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(8.dp)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        active?.let { window ->
            key(window.id) {
                val x = window.offsetX
                val y = window.offsetY
                val latestWindow by rememberUpdatedState(window)
                var size by remember { mutableStateOf(IntSize.Zero) }
                val panelWidth = minOf(420.dp, maxWidth)
                val panelHeight = maxHeight * 0.78f
                Surface(
                    modifier = Modifier.width(panelWidth).heightIn(max = panelHeight)
                        .offset { IntOffset(x.coerceIn(0f, (widthPx - size.width).coerceAtLeast(0f)).roundToInt(), y.coerceIn(0f, (heightPx - size.height - 56.dp.toPx()).coerceAtLeast(0f)).roundToInt()) }
                        .onSizeChanged { size = it },
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 8.dp, shadowElevation = 12.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().pointerInput(window.id, widthPx, heightPx, size) {
                                var dragX = latestWindow.offsetX
                                var dragY = latestWindow.offsetY
                                detectDragGestures(
                                    onDragStart = { dragX = latestWindow.offsetX; dragY = latestWindow.offsetY },
                                    onDrag = { change, delta ->
                                        change.consume()
                                        dragX = (dragX + delta.x).coerceIn(0f, (widthPx - size.width).coerceAtLeast(0f))
                                        dragY = (dragY + delta.y).coerceIn(0f, (heightPx - size.height - 56.dp.toPx()).coerceAtLeast(0f))
                                        onMove(window.id, dragX, dragY)
                                    },
                                )
                            }.padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("WHOIS — ${window.nick}", style = MaterialTheme.typography.titleMedium)
                                Text(networks[window.network] ?: window.network, style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { onMinimize(window.id, true) }, modifier = Modifier.semantics { contentDescription = minimizeLabel }) { Text("−") }
                            TextButton(onClick = { onClose(window.id) }, modifier = Modifier.semantics { contentDescription = closeLabel }) { Text("×") }
                        }
                        HorizontalDivider()
                        val fields = listOf(
                            "nick" to R.string.p2_nick, "user" to R.string.p2_user, "host" to R.string.p2_host,
                            "realname" to R.string.p2_realname, "channels" to R.string.p2_channels,
                            "server" to R.string.p2_server, "account" to R.string.p2_account,
                            "away" to R.string.p2_away, "idle" to R.string.p2_idle,
                            "signon" to R.string.p2_signon, "connected" to R.string.p2_connected,
                        )
                        val copyText = buildString {
                            appendLine("WHOIS — ${window.nick} (${networks[window.network] ?: window.network})")
                            window.fields.forEach { (name, value) -> appendLine("$name: $value") }
                            append(window.lines.joinToString("\n"))
                        }
                        SelectionContainer(Modifier.weight(1f, fill = false)) {
                            Column(Modifier.testTag("whois-content").verticalScroll(rememberScrollState()).padding(12.dp)) {
                                fields.forEach { (name, label) ->
                                    window.fields[name]?.let { value ->
                                        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                        Text(value, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
                                    }
                                }
                                Text(stringResource(R.string.p2_details), style = MaterialTheme.typography.labelMedium)
                                Text(window.lines.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(copyText)) }) { Text(stringResource(R.string.p2_copy)) }
                            TextButton(onClick = { onClose(window.id) }) { Text(stringResource(R.string.p2_close)) }
                        }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            windows.filter { it.id != active?.id }.forEach { window ->
                Surface(shape = MaterialTheme.shapes.small, tonalElevation = 8.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onMinimize(window.id, false) }, modifier = Modifier.semantics { contentDescription = "$restoreLabel ${window.nick}" }) { Text("WHOIS ${window.nick}") }
                        TextButton(onClick = { onClose(window.id) }, modifier = Modifier.semantics { contentDescription = closeLabel }) { Text("×") }
                    }
                }
            }
        }
    }
}
