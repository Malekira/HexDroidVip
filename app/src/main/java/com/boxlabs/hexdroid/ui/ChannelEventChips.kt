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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.R
import com.boxlabs.hexdroid.data.EventPrefs

/** A channel event that can be left out of chat. */
internal class ChannelEventOption(
    val labelRes: Int,
    val isHidden: (EventPrefs) -> Boolean,
    val setHidden: (EventPrefs, Boolean) -> EventPrefs,
)

internal val CHANNEL_EVENT_OPTIONS = listOf(
    ChannelEventOption(R.string.event_joins, { it.hideJoins }, { p, v -> p.copy(hideJoins = v) }),
    ChannelEventOption(R.string.event_parts, { it.hideParts }, { p, v -> p.copy(hideParts = v) }),
    ChannelEventOption(R.string.event_quits, { it.hideQuits }, { p, v -> p.copy(hideQuits = v) }),
    ChannelEventOption(R.string.event_nick_changes, { it.hideNickChanges }, { p, v -> p.copy(hideNickChanges = v) }),
    ChannelEventOption(R.string.event_topic_changes, { it.hideTopicChanges }, { p, v -> p.copy(hideTopicChanges = v) }),
    ChannelEventOption(R.string.event_kicks, { it.hideKicks }, { p, v -> p.copy(hideKicks = v) }),
    ChannelEventOption(R.string.event_mode_changes, { it.hideModeChanges }, { p, v -> p.copy(hideModeChanges = v) }),
)

/**
 * Which channel events appear in chat: a summary with one button to hide or show them all, a chip
 * per event (selected while it is shown), and the smart filter switch. [onChange] receives a
 * transform so it can be applied to the newest value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelEventChips(
    prefs: EventPrefs,
    onChange: ((EventPrefs) -> EventPrefs) -> Unit,
    title: String = stringResource(R.string.setting_channel_events),
) {
    val total = CHANNEL_EVENT_OPTIONS.size
    val shown = CHANNEL_EVENT_OPTIONS.count { !it.isHidden(prefs) }
    val summary = when (shown) {
        total -> stringResource(R.string.setting_events_all_shown)
        0 -> stringResource(R.string.setting_events_all_hidden)
        else -> stringResource(R.string.setting_events_some_shown, shown, total)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(summary, style = MaterialTheme.typography.bodySmall)
            }
            // Hides all while any is shown.
            val hideAll = shown > 0
            OutlinedButton(
                onClick = { onChange { p -> CHANNEL_EVENT_OPTIONS.fold(p) { acc, o -> o.setHidden(acc, hideAll) } } },
                modifier = Modifier.focusHighlight(RoundedCornerShape(50)),
            ) {
                Text(stringResource(if (hideAll) R.string.setting_events_hide_all else R.string.setting_events_show_all))
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (option in CHANNEL_EVENT_OPTIONS) {
                val visible = !option.isHidden(prefs)
                FilterChip(
                    selected = visible,
                    onClick = { onChange { p -> option.setHidden(p, !option.isHidden(p)) } },
                    label = { Text(stringResource(option.labelRes)) },
                    leadingIcon = if (visible) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else null,
                    modifier = Modifier.focusHighlight(RoundedCornerShape(8.dp)),
                )
            }
        }
        Text(
            stringResource(R.string.setting_channel_events_own),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.setting_smart_filter))
                Text(
                    stringResource(R.string.setting_smart_filter_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = prefs.smartFilter,
                onCheckedChange = { on -> onChange { p -> p.copy(smartFilter = on) } },
                modifier = Modifier.focusHighlight(RoundedCornerShape(16.dp)),
            )
        }
    }
}
