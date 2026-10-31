/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.R

/** Shared real IRC editor: changes only this topic, never the composer's saved writing style. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TopicTextEditor(value: TextFieldValue, onChange: (TextFieldValue) -> Unit, label: String) {
    var tools by remember { mutableStateOf(false) }
    var background by remember { mutableStateOf(false) }
    var extended by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf<Int?>(null) }
    var shade by remember { mutableStateOf<Int?>(null) }
    val transformation = remember { MircInputTransformation() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value, onChange, label = { Text(label) }, maxLines = 4,
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag("topic-editor-input"))
        TextButton(onClick = { tools = !tools }, modifier = Modifier.testTag("topic-format-tools")) {
            Text(stringResource(R.string.dreams_topic_format_tools))
        }
        if (tools) {
            Text(stringResource(R.string.dreams_topic_selection_hint), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onChange(value.copy(selection = TextRange(0, value.text.length), composition = null)) },
                    modifier = Modifier.testTag("topic-select-all")) { Text(stringResource(R.string.dreams_select_all)) }
                TextButton(onClick = { onChange(toggleFormat(value, '\u0002')) }, modifier = Modifier.testTag("topic-bold")) { Text(stringResource(R.string.format_bold)) }
                TextButton(onClick = { onChange(toggleFormat(value, '\u001d')) }, modifier = Modifier.testTag("topic-italic")) { Text(stringResource(R.string.format_italic)) }
                TextButton(onClick = { onChange(toggleFormat(value, '\u001f')) }, modifier = Modifier.testTag("topic-underline")) { Text(stringResource(R.string.format_underline)) }
                TextButton(onClick = { onChange(clearFormat(value)); foreground = null; shade = null },
                    modifier = Modifier.testTag("topic-clear-format")) { Text(stringResource(R.string.format_clear)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !background, onClick = { background = false }, label = { Text(stringResource(R.string.dreams_topic_text_color)) }, modifier = Modifier.testTag("topic-foreground"))
                FilterChip(selected = background, onClick = { background = true }, label = { Text(stringResource(R.string.dreams_topic_background_color)) }, modifier = Modifier.testTag("topic-background"))
            }
            FlowRow(maxItemsInEachRow = 8, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (code in 0..(if (extended) 98 else 15)) {
                    val color = mircColor(code) ?: continue
                    val description = stringResource(R.string.dreams_topic_color_code, code)
                    Box(Modifier.size(36.dp).testTag("topic-color-$code")
                        .semantics { contentDescription = description }
                        .background(color, CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable {
                            if (background) shade = code else foreground = code
                            onChange(colourFormat(value, foreground, shade))
                        }, contentAlignment = Alignment.Center) { }
                }
            }
            TextButton(onClick = { extended = !extended }, modifier = Modifier.testTag("topic-more-colors")) {
                Text(stringResource(if (extended) R.string.dreams_topic_basic_colors else R.string.dreams_topic_more_colors))
            }
        }
        Text(stringResource(R.string.dreams_topic_preview), style = MaterialTheme.typography.labelMedium)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            IrcLinkifiedText(value.text, mircColorsEnabled = true, linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary),
                onAnnotationClick = { _, _ -> }, style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.padding(8.dp).testTag("topic-preview"))
        }
    }
}
