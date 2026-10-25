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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.BouncerKind
import com.boxlabs.hexdroid.BouncerNetworkDraft
import com.boxlabs.hexdroid.BouncerUpstreamInfo
import com.boxlabs.hexdroid.R
import com.boxlabs.hexdroid.data.NetworkProfile
import com.boxlabs.hexdroid.importsUpstream
import com.boxlabs.hexdroid.toDraft

/** What the bouncer networks section can ask for. Each takes the bouncer profile's id first. */
class BouncerNetworkActions(
    val refresh: (parentNetId: String) -> Unit = {},
    val import: (parentNetId: String, name: String, upstreamId: String) -> Unit = { _, _, _ -> },
    val importAll: (parentNetId: String) -> Unit = {},
    val add: (parentNetId: String, draft: BouncerNetworkDraft, importAfter: Boolean) -> Unit = { _, _, _ -> },
    val edit: (parentNetId: String, upstreamId: String, draft: BouncerNetworkDraft) -> Unit = { _, _, _ -> },
    val delete: (parentNetId: String, upstreamId: String, deleteProfiles: Boolean) -> Unit = { _, _, _ -> },
)

/**
 * The upstreams a live soju or ZNC profile's bouncer reports, each with its address, state and
 * any error, and Import or "Imported". soju's networks can also be added, edited and deleted
 * here. An empty list shows a hint to refresh.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BouncerNetworksSection(
    parent: NetworkProfile,
    upstreams: Map<String, BouncerUpstreamInfo>,
    existingProfiles: List<NetworkProfile>,
    actions: BouncerNetworkActions,
) {
    // Stable order: name (blank counts as none), case-folded, then id.
    val sortedUpstreams = upstreams.values.sortedWith(
        compareBy(
            { (it.name?.takeIf { n -> n.isNotBlank() } ?: it.id).lowercase(java.util.Locale.ROOT) },
            { it.id }
        )
    )
    val canManage = parent.bouncerKind == BouncerKind.SOJU
    val imported = sortedUpstreams.associate { u -> u.id to existingProfiles.any { it.importsUpstream(parent, u) } }
    val anyToImport = sortedUpstreams.any { !it.name.isNullOrBlank() && imported[it.id] != true }

    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.bouncer_networks_section_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.bouncer_networks_section_subtitle, parent.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { actions.refresh(parent.id) }, modifier = Modifier.focusHighlight()) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.bouncer_networks_refresh)
                    )
                }
            }

            if (canManage || anyToImport) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canManage) {
                        OutlinedButton(
                            onClick = { adding = true },
                            modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.bouncer_networks_add))
                        }
                    }
                    if (anyToImport) {
                        OutlinedButton(
                            onClick = { actions.importAll(parent.id) },
                            modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                        ) { Text(stringResource(R.string.bouncer_networks_import_all)) }
                    }
                }
            }

            if (sortedUpstreams.isEmpty()) {
                Text(
                    stringResource(R.string.bouncer_networks_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                sortedUpstreams.forEach { upstream ->
                    // A nameless upstream can't be imported: the name is what the clone binds to.
                    val upstreamName = upstream.name?.takeIf { it.isNotBlank() }
                    BouncerUpstreamRow(
                        upstream = upstream,
                        alreadyImported = imported[upstream.id] == true,
                        canManage = canManage,
                        onImport = { if (upstreamName != null) actions.import(parent.id, upstreamName, upstream.id) },
                        onEdit = { editingId = upstream.id },
                        onDelete = { deletingId = upstream.id },
                    )
                }
            }
        }
    }

    if (adding) {
        BouncerNetworkDialog(
            upstream = null,
            onConfirm = { draft, importAfter ->
                adding = false
                actions.add(parent.id, draft, importAfter)
            },
            onDismiss = { adding = false },
        )
    }
    editingId?.let { id ->
        val upstream = upstreams[id]
        if (upstream == null) {
            editingId = null
        } else {
            BouncerNetworkDialog(
                upstream = upstream,
                onConfirm = { draft, _ ->
                    editingId = null
                    actions.edit(parent.id, id, draft)
                },
                onDismiss = { editingId = null },
            )
        }
    }
    deletingId?.let { id ->
        val upstream = upstreams[id]
        if (upstream == null) {
            deletingId = null
        } else {
            DeleteBouncerNetworkDialog(
                name = upstream.name?.takeIf { it.isNotBlank() } ?: upstream.id,
                hasProfile = imported[id] == true,
                onConfirm = { deleteProfiles ->
                    deletingId = null
                    actions.delete(parent.id, id, deleteProfiles)
                },
                onDismiss = { deletingId = null },
            )
        }
    }
}

@Composable
private fun BouncerUpstreamRow(
    upstream: BouncerUpstreamInfo,
    alreadyImported: Boolean,
    canManage: Boolean,
    onImport: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val name = upstream.name?.takeIf { it.isNotBlank() }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                name ?: stringResource(R.string.bouncer_networks_unnamed),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BouncerStatePill(state = upstream.state)
                val address = upstreamAddress(upstream)
                if (address != null) {
                    Text(
                        address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            upstream.error?.takeIf { it.isNotBlank() }?.let {
                Text(
                    stringResource(R.string.bouncer_networks_error, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (alreadyImported) {
            Text(
                stringResource(R.string.bouncer_networks_already_imported),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            OutlinedButton(onClick = onImport, enabled = name != null, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                Text(stringResource(R.string.bouncer_networks_import))
            }
        }
        if (canManage) {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.focusHighlight()) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.bouncer_networks_actions, name ?: upstream.id),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit)) },
                        onClick = { menu = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

/** "host:port · TLS", or as much of it as the bouncer reported. */
@Composable
private fun upstreamAddress(upstream: BouncerUpstreamInfo): String? {
    val host = upstream.host?.takeIf { it.isNotBlank() } ?: return null
    val hostPort = upstream.port?.let { "$host:$it" } ?: host
    return when (upstream.tls) {
        true -> "$hostPort · " + stringResource(R.string.bouncer_networks_tls)
        false -> "$hostPort · " + stringResource(R.string.bouncer_networks_plaintext)
        null -> hostPort
    }
}

/**
 * Coloured pill for an upstream's connection state: green up, amber working, grey down,
 * neutral unknown. "disconnected" is not shown as an error, since a bouncer detached from an
 * upstream on purpose is normal.
 */
@Composable
private fun BouncerStatePill(state: String?) {
    val (label, bg, fg) = when (state?.lowercase()) {
        "connected" -> Triple(
            stringResource(R.string.bouncer_networks_state_connected),
            Color(0xFF1B5E20),
            Color.White
        )
        "connecting" -> Triple(
            stringResource(R.string.bouncer_networks_state_connecting),
            Color(0xFFF9A825),
            Color.Black
        )
        "disconnected" -> Triple(
            stringResource(R.string.bouncer_networks_state_disconnected),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        else -> Triple(
            stringResource(R.string.bouncer_networks_state_unknown),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

/**
 * Add a soju upstream network ([upstream] null) or edit one. Blank fields are left to soju's
 * defaults; when editing, a blank password keeps the current one.
 */
@Composable
private fun BouncerNetworkDialog(
    upstream: BouncerUpstreamInfo?,
    onConfirm: (BouncerNetworkDraft, importAfter: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = remember(upstream?.id) { upstream?.toDraft() ?: BouncerNetworkDraft() }
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var host by rememberSaveable { mutableStateOf(initial.host) }
    var port by rememberSaveable { mutableStateOf(initial.port) }
    var tls by rememberSaveable { mutableStateOf(initial.tls) }
    var nickname by rememberSaveable { mutableStateOf(initial.nickname) }
    var username by rememberSaveable { mutableStateOf(initial.username) }
    var realname by rememberSaveable { mutableStateOf(initial.realname) }
    var pass by rememberSaveable { mutableStateOf("") }
    var importAfter by rememberSaveable { mutableStateOf(true) }
    var tried by remember { mutableStateOf(false) }

    val hostError = host.isBlank()
    val portError = port.isNotBlank() && port.trim().toIntOrNull()?.let { it in 1..65535 } != true
    val adding = upstream == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (adding) stringResource(R.string.bouncer_net_dialog_add_title)
                else stringResource(R.string.bouncer_net_dialog_edit_title, upstream?.name ?: upstream?.id.orEmpty())
            )
        },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.bouncer_net_optional),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.network_host)) },
                    isError = tried && hostError,
                    supportingText = if (tried && hostError) {
                        { Text(stringResource(R.string.bouncer_net_host_required)) }
                    } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.network_port)) },
                    isError = portError,
                    supportingText = {
                        Text(stringResource(if (portError) R.string.bouncer_net_port_invalid else R.string.bouncer_net_field_port_hint))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = tls, onValueChange = { tls = it }, role = Role.Switch)
                        .focusHighlight(RoundedCornerShape(8.dp))
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.network_use_tls_label), modifier = Modifier.weight(1f))
                    Switch(checked = tls, onCheckedChange = null)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.network_name)) },
                    supportingText = { Text(stringResource(R.string.bouncer_net_field_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text(stringResource(R.string.network_nick)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.network_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = realname,
                    onValueChange = { realname = it },
                    label = { Text(stringResource(R.string.network_realname)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text(stringResource(R.string.network_server_password_label)) },
                    supportingText = if (!adding) {
                        { Text(stringResource(R.string.bouncer_net_field_pass_keep)) }
                    } else null,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (adding) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = importAfter, onValueChange = { importAfter = it }, role = Role.Checkbox)
                            .focusHighlight(RoundedCornerShape(8.dp))
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = importAfter, onCheckedChange = null)
                        Text(stringResource(R.string.bouncer_net_import_after), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    tried = true
                    if (!hostError && !portError) {
                        onConfirm(
                            BouncerNetworkDraft(
                                name = name, host = host, port = port, tls = tls,
                                nickname = nickname, username = username, realname = realname, pass = pass,
                            ),
                            importAfter,
                        )
                    }
                },
                modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
            ) { Text(stringResource(if (adding) R.string.bouncer_net_add else R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Confirm removing upstream [name] from the bouncer, optionally with the profile imported for it. */
@Composable
private fun DeleteBouncerNetworkDialog(
    name: String,
    hasProfile: Boolean,
    onConfirm: (deleteProfiles: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var alsoProfile by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bouncer_net_delete_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.bouncer_net_delete_body))
                if (hasProfile) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = alsoProfile, onValueChange = { alsoProfile = it }, role = Role.Checkbox)
                            .focusHighlight(RoundedCornerShape(8.dp))
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = alsoProfile, onCheckedChange = null)
                        Text(stringResource(R.string.bouncer_net_delete_profile), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(hasProfile && alsoProfile) },
                modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
            ) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
