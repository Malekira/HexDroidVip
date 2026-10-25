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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.R
import com.boxlabs.hexdroid.data.BackupCrypto

/** Asks whether the backup should carry the passwords, and for the passphrase that guards them. */
@Composable
fun ExportBackupDialog(onExport: (passphrase: String?) -> Unit, onDismiss: () -> Unit) {
    var include by remember { mutableStateOf(false) }
    var pass by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val tooShort = pass.length < BackupCrypto.MIN_PASSPHRASE_LENGTH
    val mismatch = repeat != pass
    val ok = !include || (!tooShort && !mismatch)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.backup_include_passwords))
                        Text(
                            stringResource(R.string.backup_include_passwords_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = include,
                        onCheckedChange = { include = it },
                        modifier = Modifier.focusHighlight(RoundedCornerShape(16.dp)),
                    )
                }
                if (include) {
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text(stringResource(R.string.backup_passphrase)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = pass.isNotEmpty() && tooShort,
                        supportingText = { Text(stringResource(R.string.backup_passphrase_short, BackupCrypto.MIN_PASSPHRASE_LENGTH)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = repeat,
                        onValueChange = { repeat = it },
                        label = { Text(stringResource(R.string.backup_passphrase_confirm)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = repeat.isNotEmpty() && mismatch,
                        supportingText = if (repeat.isNotEmpty() && mismatch) {
                            { Text(stringResource(R.string.backup_passphrase_mismatch)) }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onExport(if (include) pass else null) },
                enabled = ok,
                modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
            ) { Text(stringResource(R.string.backup_export_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Asks for the passphrase of a backup's passwords, or to restore without them. */
@Composable
fun RestorePassphraseDialog(
    wrong: Boolean,
    onRestore: (String) -> Unit,
    onRestoreWithout: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_passphrase_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.restore_passphrase_body))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text(stringResource(R.string.backup_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = wrong,
                    supportingText = if (wrong) {
                        { Text(stringResource(R.string.restore_passphrase_wrong)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onRestoreWithout, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                    Text(stringResource(R.string.restore_without_passwords))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRestore(pass) },
                enabled = pass.isNotEmpty(),
                modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
            ) { Text(stringResource(R.string.restore_with_passwords)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
