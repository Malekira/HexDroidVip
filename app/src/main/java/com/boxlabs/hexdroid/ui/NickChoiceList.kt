/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun NickChoiceList(matches: List<String>, selected: String?, choose: (String) -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(selected, matches) { val i = matches.indexOf(selected); if (i >= 0) list.animateScrollToItem(i) }
    Surface(tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth().testTag("nick-choices")) {
        Column {
            Text("Elegí un nick · Tab recorre las coincidencias", Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
            LazyColumn(state = list, modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                items(matches, key = { it }) { nick ->
                    Surface(color = if (nick == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                        Text(nick, Modifier.fillMaxWidth().clickable { choose(nick) }.padding(12.dp))
                    }
                }
            }
        }
    }
}
