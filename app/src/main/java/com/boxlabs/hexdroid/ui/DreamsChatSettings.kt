/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.*
import com.boxlabs.hexdroid.R

@Composable
fun VisibleMentionSoundSetting(settings: UiSettings, onUpdate: (UiSettings.() -> UiSettings) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(stringResource(R.string.dreams_visible_sound), modifier = Modifier.weight(1f))
        Switch(settings.soundMentionsWhileVisible,
            onCheckedChange = { value -> onUpdate { copy(soundMentionsWhileVisible = value) } },
            modifier = Modifier.testTag("visible-mention-sound"))
    }
    Text(stringResource(R.string.dreams_visible_sound_hint), style = MaterialTheme.typography.bodySmall)
}

@Composable
fun DreamsChatSettings(state: UiState, onUpdate: (UiSettings.() -> UiSettings) -> Unit,
                       onMode: (String, Char, Boolean) -> Unit, onAvatar: (String, String?) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(state.activeNetworkId ?: state.networks.firstOrNull()?.id.orEmpty()) }
    val net = state.networks.firstOrNull { it.id == selected } ?: state.networks.firstOrNull()
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.dreams_private_controls), style = MaterialTheme.typography.titleMedium)
        if (net != null) {
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().testTag("private-network")) { Text(net.name) }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    state.networks.forEach { candidate -> DropdownMenuItem(text = { Text(candidate.name) }, onClick = {
                        selected = candidate.id; expanded = false
                    }) }
                }
            }
            val conn = state.connections[net.id] ?: NetConnState()
            for (mode in listOf('g', 'R')) {
                val supported = mode in conn.supportedUserModes || mode in conn.userModes || (mode == 'g' && net.id in state.callerIdNetworks)
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(if (mode == 'g') R.string.dreams_mode_g else R.string.dreams_mode_r))
                        Text(stringResource(when {
                            !conn.connected -> R.string.dreams_mode_offline
                            !supported -> R.string.dreams_mode_unsupported
                            !conn.userModesKnown -> R.string.dreams_mode_unknown
                            else -> R.string.dreams_mode_confirmed
                        }), style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(mode in conn.userModes, { onMode(net.id, mode, it) },
                        enabled = supported && conn.connected && conn.userModesKnown,
                        modifier = Modifier.testTag("private-mode-$mode"))
                }
            }
            Text(stringResource(R.string.dreams_mode_hint), style = MaterialTheme.typography.bodySmall)
            state.privateModeMessages[net.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            Text(stringResource(R.string.dreams_own_avatar), style = MaterialTheme.typography.titleMedium)
            val saved = state.profileAvatarUrls[net.id] ?: conn.ownMetadata["avatar"].orEmpty()
            var url by rememberSaveable(net.id, saved) { mutableStateOf(saved) }
            val valid = validAvatarUrl(url.trim())
            OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.dreams_avatar_url)) },
                singleLine = true, isError = !valid, modifier = Modifier.fillMaxWidth().testTag("own-avatar-url"))
            if (!valid) Text(stringResource(R.string.dreams_avatar_invalid), color = MaterialTheme.colorScheme.error)
            Text(stringResource(if (conn.hasUserMetadata) R.string.dreams_avatar_shared else R.string.dreams_avatar_local),
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = { onAvatar(net.id, url.trim().ifEmpty { null }) }, enabled = valid && url.trim() != saved,
                modifier = Modifier.testTag("own-avatar-save")) { Text(stringResource(R.string.save)) }
            Text(stringResource(R.string.dreams_avatar_privacy), style = MaterialTheme.typography.bodySmall)
        } else Text(stringResource(R.string.dreams_select_network))
        HorizontalDivider()
        Text(stringResource(R.string.dreams_private_alerts), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(stringResource(R.string.dreams_private_notice), Modifier.weight(1f))
            Switch(state.settings.privateAttemptActiveNotice,
                { value -> onUpdate { copy(privateAttemptActiveNotice = value) } }, modifier = Modifier.testTag("private-active-notice"))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(stringResource(R.string.dreams_private_floating), Modifier.weight(1f))
            Switch(state.settings.privateAttemptFloating,
                { value -> onUpdate { copy(privateAttemptFloating = value) } }, modifier = Modifier.testTag("private-floating"))
        }
        Text(stringResource(R.string.dreams_private_alert_hint), style = MaterialTheme.typography.bodySmall)
    }
}

/** A non-modal child of the app; no OS overlay permission or private buffer is needed. */
@Composable
fun PrivateAttemptOverlay(alert: PrivateAttemptAlert?, networkName: String, onDismiss: (Long) -> Unit) {
    if (alert == null) return
    Box(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
        ElevatedCard(Modifier.widthIn(max = 420.dp).fillMaxWidth().testTag("private-attempt-floating")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(networkName, style = MaterialTheme.typography.labelMedium)
                Text(alert.text, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { onDismiss(alert.id) }, modifier = Modifier.align(androidx.compose.ui.Alignment.End)) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}
