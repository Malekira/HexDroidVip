/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.R
import com.boxlabs.hexdroid.UiSettings
import com.boxlabs.hexdroid.readableThemeText

@Composable
internal fun MentionColorSettings(s: UiSettings, update: (UiSettings.() -> UiSettings) -> Unit) {
    var hex by remember(s.mentionHighlightColor) { mutableStateOf("#%06X".format(s.mentionHighlightColor and 0xFFFFFF)) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(hex, { value ->
            hex = value
            if (value.matches(Regex("#[0-9a-fA-F]{6}")))
                update { copy(mentionHighlightColor = (0xFF000000L or value.drop(1).toLong(16)).toInt()) }
        }, singleLine = true, label = { Text(stringResource(R.string.p2_color)) },
            isError = !hex.matches(Regex("#[0-9a-fA-F]{6}")), modifier = Modifier.fillMaxWidth().testTag("mention-color-hex"))
        Surface(color = Color(s.mentionHighlightColor),
            contentColor = Color(readableThemeText(0xff000000.toInt(), s.mentionHighlightColor)),
            modifier = Modifier.fillMaxWidth().testTag("mention-color-preview")) {
            Text(stringResource(R.string.mention_color_preview), Modifier.padding(12.dp))
        }
        ThemeColorPicker(s.mentionHighlightColor) { color -> update { copy(mentionHighlightColor = color) } }
    }
}
