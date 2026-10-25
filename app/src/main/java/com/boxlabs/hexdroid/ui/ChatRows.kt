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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boxlabs.hexdroid.R
import com.boxlabs.hexdroid.stripIrcFormatting

internal sealed class SidebarItem(val stableKey: String) {
    data class Header(val netId: String, val title: String, val expanded: Boolean = true) : SidebarItem("h:$netId")
    data class Buffer(
        val key: String,
        val label: String,
        val indent: Dp,
        val isNetworkHeader: Boolean = false,
        val netId: String? = null,
        val expanded: Boolean = true,
    ) : SidebarItem("b:$key")
    data class DividerItem(val netId: String) : SidebarItem("d:$netId")
}

/**
 * A tab-completion in progress, so a second Tab cycles to the next match rather than
 * completing the word the first one produced.
 *
 * [text] and [cursor] are what the last completion left in the field: the cycle is only
 * continued when the field still holds exactly that, so any edit in between starts over.
 */
internal data class NickCycle(
    val start: Int,
    val hadAt: Boolean,
    val matches: List<String>,
    val index: Int,
    val text: String,
    val cursor: Int,
)

/** Drag handle for sidebar network rows. Long-press to start drag.
 *  onDrag receives the CUMULATIVE y offset from the drag start position. */
@Composable
internal fun SidebarDragHandle(
    onStart: () -> Unit,
    onDrag: (totalOffsetY: Float) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit = onEnd,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
) {
    // The gesture block is set up once and then outlives every recomposition, so it
    // reads the callbacks through state rather than capturing the first ones it saw,
    // which would leave a drag acting on the network order as it was at first draw.
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    val cancel by rememberUpdatedState(onCancel)
    Box(
        modifier = Modifier
            .size(24.dp)
            .dpadReorder(onMoveUp = onMoveUp, onMoveDown = onMoveDown)
            .pointerInput(Unit) {
                var accumulated = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { accumulated = 0f; start() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accumulated += dragAmount.y
                        drag(accumulated)
                    },
                    onDragEnd = { end() },
                    onDragCancel = { cancel() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.DragHandle,
            contentDescription = stringResource(R.string.chat_drag_reorder),
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

/**
 * Quoted preview above a message with a reply tag. The parent is found through [msgStrToDisplayIdx]
 * and [msgIdToText]. [canScroll] is true when the parent is loaded, making the quote tappable;
 * otherwise it is shown as a non-tappable placeholder.
 */
@Composable
internal fun ReplyQuote(
    replyToMsgId: String,
    msgIdToText: Map<String, Pair<String?, String>>,
    canScroll: Boolean,
    onTap: () -> Unit,
    onFetch: (() -> Unit)? = null,
) {
    val entry = msgIdToText[replyToMsgId]  // O(1)
    val label = when {
        entry == null          -> "↩ " + stringResource(R.string.chat_reply_not_loaded)
        entry.first != null    -> "↩ ${entry.first}: ${stripIrcFormatting(entry.second).take(80)}"
        else                   -> "↩ ${stripIrcFormatting(entry.second).take(80)}"
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .focusHighlight(RoundedCornerShape(4.dp))
            // Jumps to the original, or fetches it when outside the loaded window.
            .clickable(
                enabled = canScroll || (entry == null && onFetch != null),
                onClick = if (canScroll) onTap else (onFetch ?: {}),
            )
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
/**
 * Top-of-scrollback row offering the next page of stored history.
 *
 * Shown only for buffers whose server has chathistory and hasn't reported itself out of
 * older messages.
 */
@Composable
internal fun LoadOlderHistoryRow(
    loading: Boolean,
    onLoad: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.chat_history_loading),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            TextButton(
                onClick = onLoad,
                modifier = Modifier.focusHighlight(RoundedCornerShape(16.dp)),
            ) {
                Text(
                    text = stringResource(R.string.chat_history_load_older),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * Horizontal divider shown above the first unread message in a buffer.
 * Driven by the server's draft/read-marker or soju.im/read timestamp.
 * Also integrated into the app.
 */
@Composable
internal fun UnreadSeparator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
            thickness = 1.dp
        )
        Text(
            text = stringResource(R.string.chat_unread_divider),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
            thickness = 1.dp
        )
    }
}
