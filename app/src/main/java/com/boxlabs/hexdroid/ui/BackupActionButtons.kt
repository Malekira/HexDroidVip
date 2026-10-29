/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.R

@Composable
internal fun BackupActionButtons(export: () -> Unit, restore: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = export,
            modifier = Modifier.fillMaxWidth().testTag("backup-export").focusHighlight(RoundedCornerShape(50))) {
            Text(stringResource(R.string.settings_export_backup))
        }
        OutlinedButton(onClick = restore,
            modifier = Modifier.fillMaxWidth().testTag("backup-restore").focusHighlight(RoundedCornerShape(50))) {
            Text(stringResource(R.string.settings_restore_backup))
        }
    }
}
