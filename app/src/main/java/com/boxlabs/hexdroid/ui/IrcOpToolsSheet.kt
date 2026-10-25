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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.R

/** The IRC operator tools for [netName]. Commands go out through [onSend]; [onDismiss] closes the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IrcOpToolsSheet(netName: String, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    // Insets are handled by the content, not the sheet: see the note on the channel
    // tools sheet in ChatScreen.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        val scrollState = rememberScrollState()
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(16.dp)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.AdminPanelSettings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.chat_ircop_tools), style = MaterialTheme.typography.titleLarge)
            }
            Text(netName, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()

            var opTarget by remember { mutableStateOf("") }
            var opReason by remember { mutableStateOf("") }
            var opDuration by remember { mutableStateOf("") }
            var opServer by remember { mutableStateOf("") }
            var opMessage by remember { mutableStateOf("") }

            // Target / Reason fields
            Text(stringResource(R.string.chat_target_label), fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = opTarget, onValueChange = { opTarget = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.chat_nick_mask_label)) }
            )
            OutlinedTextField(
                value = opReason, onValueChange = { opReason = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.chat_reason)) }
            )
            // Duration for the timed *-line / shun commands. Format is server-specific
            // (e.g. "1d", "2h", "30" minutes); "0" usually means permanent.
            OutlinedTextField(
                value = opDuration, onValueChange = { opDuration = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.chat_duration_label)) },
                supportingText = { Text(stringResource(R.string.chat_duration_hint)) }
            )

            // Kill / K-line / Z-line
            Text(stringResource(R.string.chat_punishments), fontWeight = FontWeight.Bold)
            val noReasonStr = stringResource(R.string.chat_no_reason)
            // The timed line/shun commands take <mask> <duration> [reason]; require both a
            // target and a duration so we never send a bare "<mask> <reason>" that the
            // server would misparse.
            val canLine = opTarget.isNotBlank() && opDuration.isNotBlank()
            // FlowRow so the buttons wrap onto extra lines on narrow screens instead of
            // overflowing off the right edge. Both rows of punishments are merged into one
            // wrapping group.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val t = opTarget.trim()
                        if (t.isNotBlank()) {
                            val r = opReason.trim().ifBlank { noReasonStr }
                            onSend("/kill $t $r")
                            onDismiss()
                        }
                    },
                    enabled = opTarget.isNotBlank(), modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                ) { Text(stringResource(R.string.ircop_kill)) }
                OutlinedButton(
                    onClick = {
                        val t = opTarget.trim(); val d = opDuration.trim()
                        if (t.isNotBlank() && d.isNotBlank()) {
                            val r = opReason.trim().ifBlank { noReasonStr }
                            onSend("/kline $t $d $r")
                            onDismiss()
                        }
                    },
                    enabled = canLine, modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                ) { Text(stringResource(R.string.ircop_kline)) }
                OutlinedButton(
                    onClick = {
                        val t = opTarget.trim(); val d = opDuration.trim()
                        if (t.isNotBlank() && d.isNotBlank()) {
                            val r = opReason.trim().ifBlank { noReasonStr }
                            onSend("/zline $t $d $r")
                            onDismiss()
                        }
                    },
                    enabled = canLine, modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                ) { Text(stringResource(R.string.ircop_zline)) }
                OutlinedButton(
                    onClick = {
                        val t = opTarget.trim(); val d = opDuration.trim()
                        if (t.isNotBlank() && d.isNotBlank()) {
                            val r = opReason.trim().ifBlank { noReasonStr }
                            onSend("/gline $t $d $r")
                            onDismiss()
                        }
                    },
                    enabled = canLine, modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                ) { Text(stringResource(R.string.ircop_gline)) }
                OutlinedButton(
                    onClick = {
                        val t = opTarget.trim(); val d = opDuration.trim()
                        if (t.isNotBlank() && d.isNotBlank()) {
                            val r = opReason.trim().ifBlank { noReasonStr }
                            onSend("/shun $t $d $r")
                        }
                    },
                    enabled = canLine, modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                ) { Text(stringResource(R.string.ircop_shun)) }
                OutlinedButton(
                    onClick = {
                        val t = opTarget.trim(); val d = opDuration.trim()
                        if (t.isNotBlank() && d.isNotBlank()) {
                            val r = opReason.trim().ifBlank { noReasonStr }
                            onSend("/dline $t $d $r")
                        }
                    },
                    enabled = canLine, modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                ) { Text(stringResource(R.string.ircop_dline)) }
            }

            HorizontalDivider()

            // Force join/part
            Text(stringResource(R.string.chat_force_joinpart), fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = opServer, onValueChange = { opServer = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.chat_channel_label)) }
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val t = opTarget.trim(); val ch = opServer.trim()
                        if (t.isNotBlank() && ch.isNotBlank()) onSend("/sajoin $t $ch")
                    },
                    enabled = opTarget.isNotBlank() && opServer.isNotBlank(), modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                ) { Text(stringResource(R.string.ircop_sajoin)) }
                OutlinedButton(
                    onClick = {
                        val t = opTarget.trim(); val ch = opServer.trim()
                        if (t.isNotBlank() && ch.isNotBlank()) onSend("/sapart $t $ch")
                    },
                    enabled = opTarget.isNotBlank() && opServer.isNotBlank(), modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                ) { Text(stringResource(R.string.ircop_sapart)) }
            }

            HorizontalDivider()

            // Broadcast messages
            Text(stringResource(R.string.chat_broadcast), fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = opMessage, onValueChange = { opMessage = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.chat_message_label)) }
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { if (opMessage.isNotBlank()) onSend("/wallops ${opMessage.trim()}") },
                    enabled = opMessage.isNotBlank(), modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                ) { Text(stringResource(R.string.ircop_wallops)) }
                OutlinedButton(
                    onClick = { if (opMessage.isNotBlank()) onSend("/globops ${opMessage.trim()}") },
                    enabled = opMessage.isNotBlank(), modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                ) { Text(stringResource(R.string.ircop_globops)) }
                OutlinedButton(
                    onClick = { if (opMessage.isNotBlank()) onSend("/locops ${opMessage.trim()}") },
                    enabled = opMessage.isNotBlank(), modifier = Modifier.focusHighlight(RoundedCornerShape(50))
                ) { Text(stringResource(R.string.ircop_locops)) }
            }

            HorizontalDivider()

            // Server queries
            Text(stringResource(R.string.chat_server_queries), fontWeight = FontWeight.Bold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { onSend("/motd"); onDismiss() }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.ircop_motd)) }
                OutlinedButton(onClick = { onSend("/admin"); onDismiss() }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.ircop_admin)) }
                OutlinedButton(onClick = { onSend("/stats u"); onDismiss() }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.ircop_uptime)) }
                OutlinedButton(onClick = { onSend("/stats l"); onDismiss() }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Text(stringResource(R.string.ircop_links)) }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
