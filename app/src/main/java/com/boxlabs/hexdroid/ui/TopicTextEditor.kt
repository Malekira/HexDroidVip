/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val initial = remember { initialTopicPalette(value.text) }
    var foreground by remember { mutableStateOf(initial.first) }
    var shade by remember { mutableStateOf(initial.second) }
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
                TopicFormatButton("topic-select-all", stringResource(R.string.dreams_select_all),
                    { onChange(value.copy(selection = TextRange(0, value.text.length), composition = null)) }) {
                    Icon(Icons.Filled.SelectAll, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                TopicFormatButton("topic-bold", stringResource(R.string.format_bold), { onChange(toggleFormat(value, '\u0002')) }) {
                    Text(stringResource(R.string.dreams_format_bold_short), fontWeight = FontWeight.Bold)
                }
                TopicFormatButton("topic-italic", stringResource(R.string.format_italic), { onChange(toggleFormat(value, '\u001d')) }) {
                    Text(stringResource(R.string.dreams_format_italic_short), fontStyle = FontStyle.Italic)
                }
                TopicFormatButton("topic-underline", stringResource(R.string.format_underline), { onChange(toggleFormat(value, '\u001f')) }) {
                    Text(stringResource(R.string.dreams_format_underline_short), textDecoration = TextDecoration.Underline)
                }
                TopicFormatButton("topic-strike", stringResource(R.string.dreams_format_strike), { onChange(toggleFormat(value, '\u001e')) }) {
                    Text(stringResource(R.string.dreams_format_strike_short), textDecoration = TextDecoration.LineThrough)
                }
                TopicFormatButton("topic-clear-format", stringResource(R.string.format_clear),
                    { onChange(clearFormat(value)); foreground = null; shade = null }) {
                    Icon(Icons.Filled.FormatClear, contentDescription = null, modifier = Modifier.size(20.dp))
                }
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
                            onChange(topicColourFormat(value, foreground, shade))
                        }, contentAlignment = Alignment.Center) { }
                }
            }
            TextButton(onClick = { extended = !extended }, modifier = Modifier.testTag("topic-more-colors")) {
                Text(stringResource(if (extended) R.string.dreams_topic_basic_colors else R.string.dreams_topic_more_colors))
            }
        }
    }
}

@Composable
private fun TopicFormatButton(tag: String, description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(Modifier.size(36.dp).testTag(tag).border(1.dp, MaterialTheme.colorScheme.outline, shape)
        .semantics(mergeDescendants = true) { contentDescription = description }
        .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) { content() }
}
