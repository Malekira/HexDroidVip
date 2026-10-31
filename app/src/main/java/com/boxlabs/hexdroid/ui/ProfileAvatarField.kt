/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.R
import com.boxlabs.hexdroid.validAvatarUrl

@Composable
internal fun ProfileAvatarField(url: String, onChange: (String) -> Unit) {
    val valid = validAvatarUrl(url.trim())
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(url, onChange, label = { Text(stringResource(R.string.dreams_avatar_url)) },
            singleLine = true, isError = !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag("network-profile-avatar"))
        if (!valid) Text(stringResource(R.string.dreams_avatar_invalid), color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.dreams_avatar_profile_help), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.dreams_avatar_privacy), style = MaterialTheme.typography.bodySmall)
    }
}
