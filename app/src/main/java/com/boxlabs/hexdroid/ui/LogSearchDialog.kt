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

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.boxlabs.hexdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boxlabs.hexdroid.IrcViewModel
import com.boxlabs.hexdroid.LogSearch
import com.boxlabs.hexdroid.LogSearchHit
import com.boxlabs.hexdroid.LogSearchScope
import com.boxlabs.hexdroid.R

/**
 * Full-screen search over the saved logs, and the history of any soju bouncer that keeps it.
 * Each hit shows its line with the matches marked, the lines around it on request, and a way to
 * open the conversation at that line.
 */
@Composable
fun LogSearchDialog(
    viewModel: IrcViewModel,
    /** The buffer the search was opened from, for the narrower scopes. */
    bufferKey: String?,
    bufferName: String?,
    loggingEnabled: Boolean,
    onDismiss: () -> Unit,
) {
    val search by viewModel.logSearch.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(search.query) }
    val hasBuffer = bufferKey != null && !bufferName.isNullOrBlank()
    var scope by rememberSaveable { mutableStateOf(if (hasBuffer) LogSearchScope.BUFFER else LogSearchScope.ALL) }
    val terms = remember(search.query) { LogSearch.parse(search.query).terms }

    DisposableEffect(Unit) { onDispose { viewModel.clearLogSearch() } }

    fun run() = viewModel.searchLogs(text, scope, bufferKey)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                    IconButton(onClick = onDismiss, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.close))
                    }
                    Text(stringResource(R.string.log_search_title), style = MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.log_search_hint)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { run() }),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (hasBuffer) {
                            FilterChip(
                                selected = scope == LogSearchScope.BUFFER,
                                onClick = { scope = LogSearchScope.BUFFER; if (text.isNotBlank()) run() },
                                label = { Text(bufferName) },
                                modifier = Modifier.focusHighlight(RoundedCornerShape(8.dp)),
                            )
                            FilterChip(
                                selected = scope == LogSearchScope.NETWORK,
                                onClick = { scope = LogSearchScope.NETWORK; if (text.isNotBlank()) run() },
                                label = { Text(stringResource(R.string.log_search_scope_network)) },
                                modifier = Modifier.focusHighlight(RoundedCornerShape(8.dp)),
                            )
                        }
                        FilterChip(
                            selected = scope == LogSearchScope.ALL,
                            onClick = { scope = LogSearchScope.ALL; if (text.isNotBlank()) run() },
                            label = { Text(stringResource(R.string.log_search_scope_all)) },
                            modifier = Modifier.focusHighlight(RoundedCornerShape(8.dp)),
                        )
                    }
                    Text(
                        stringResource(R.string.log_search_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        search.running -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.log_search_searching), modifier = Modifier.padding(start = 8.dp))
                        }
                        search.searched && search.hits.isEmpty() && search.serverRunning -> Unit
                        search.searched && search.hits.isEmpty() -> Column {
                            Text(stringResource(R.string.log_search_none))
                            if (!loggingEnabled && search.serverSearched == 0) {
                                Text(
                                    stringResource(R.string.log_search_logging_off),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        search.searched -> Text(
                            if (search.truncated) stringResource(R.string.log_search_more, search.hits.size)
                            else stringResource(R.string.log_search_results, search.hits.size),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    when {
                        search.serverRunning -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text(
                                stringResource(R.string.log_search_server_searching),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        search.serverSearched > 0 -> Text(
                            stringResource(R.string.log_search_server_count, search.serverSearched),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // The same file name can turn up in app storage and in a chosen folder.
                    itemsIndexed(search.hits, key = { i, h -> "$i/${h.networkDir}/${h.buffer}/${h.lineNo}" }) { _, hit ->
                        LogHitCard(
                            hit = hit,
                            terms = terms,
                            showWhere = scope != LogSearchScope.BUFFER,
                            onOpen = { if (viewModel.openLogSearchHit(hit)) onDismiss() },
                            canOpen = hit.netId != null || viewModel.networkForLogDir(hit.networkDir) != null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogHitCard(
    hit: LogSearchHit,
    terms: List<String>,
    showWhere: Boolean,
    canOpen: Boolean,
    onOpen: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val mark = MaterialTheme.colorScheme.tertiaryContainer
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .focusHighlight(RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (showWhere) "${hit.buffer} · ${hit.networkDir}" else hit.buffer,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (hit.fromServer) {
                    Text(
                        stringResource(R.string.log_search_server_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                Text(hit.stamp, style = MaterialTheme.typography.labelSmall, color = dim)
            }
            if (expanded) {
                for (l in hit.before) ContextLine(l, dim)
            }
            Text(highlighted(hit.body, terms, mark), style = MaterialTheme.typography.bodyMedium)
            if (expanded) {
                for (l in hit.after) ContextLine(l, dim)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    TextButton(
                        onClick = onOpen,
                        enabled = canOpen,
                        modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
                    ) { Text(stringResource(R.string.log_search_go)) }
                }
            }
        }
    }
}

@Composable
private fun ContextLine(line: String, color: Color) {
    val stamp = line.substringBefore('\t', "").takeLast(8)
    Text(
        (if (stamp.isNotEmpty()) "$stamp  " else "") + LogSearch.bodyOf(line),
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

/** [text] with each occurrence of [terms] marked, ignoring case. */
private fun highlighted(text: String, terms: List<String>, mark: Color): AnnotatedString {
    if (terms.isEmpty()) return AnnotatedString(text)
    val ranges = mutableListOf<IntRange>()
    for (t in terms) {
        if (t.isEmpty()) continue
        var from = 0
        while (true) {
            val i = text.indexOf(t, from, ignoreCase = true)
            if (i < 0) break
            ranges += i until i + t.length
            from = i + t.length
        }
    }
    if (ranges.isEmpty()) return AnnotatedString(text)
    val merged = ranges.sortedBy { it.first }.fold(mutableListOf<IntRange>()) { acc, r ->
        val last = acc.lastOrNull()
        if (last != null && r.first <= last.last + 1) acc[acc.size - 1] = last.first..maxOf(last.last, r.last) else acc += r
        acc
    }
    return buildAnnotatedString {
        var pos = 0
        for (r in merged) {
            if (r.first > pos) append(text.substring(pos, r.first))
            pushStyle(SpanStyle(background = mark, fontWeight = FontWeight.Bold))
            append(text.substring(r.first, (r.last + 1).coerceAtMost(text.length)))
            pop()
            pos = r.last + 1
        }
        if (pos < text.length) append(text.substring(pos))
    }
}
