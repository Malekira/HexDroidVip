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

package com.boxlabs.hexdroid

import androidx.lifecycle.viewModelScope
import com.boxlabs.hexdroid.IrcViewModel.Companion.MAX_PENDING_REACTIONS
import com.boxlabs.hexdroid.IrcViewModel.PendingReaction
import com.boxlabs.hexdroid.IrcViewModel.ReactionResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/** Set [key]'s history flags in one update. */
internal fun IrcViewModel.setHistoryFlags(key: String, loading: Boolean, exhausted: Boolean) {
    _state.update { st ->
        val buf = st.buffers[key] ?: return@update st
        if (buf.historyLoading == loading && buf.historyExhausted == exhausted) return@update st
        st.copy(
            buffers = st.buffers + (key to buf.copy(
                historyLoading = loading,
                historyExhausted = exhausted,
            ))
        )
    }
}

/**
 * Merge a completed backfill into its buffer. Ordering and deduplication are the log's
 * job. A page that deduplicates away is not exhaustion: the anchor pointed into a range
 * the disk logs already covered, and the next request anchors above it.
 */
internal fun IrcViewModel.onBackfillFinished(result: BackfillResult) {
    val key = result.bufferKey
    _state.update { st ->
        val buf = st.buffers[key] ?: return@update st
        val baseCap = st.settings.maxScrollbackLines.coerceIn(100, 5000)
        // Recognised on arrival: identity recorded, not shown.
        // Remember where this page started so the next request pages on from it, whether
        // or not any of it was new to the buffer. Only for a request that was walking
        // backwards: fetching a specific stretch says nothing about where paging is.
        if (result.kind == BackfillKind.OLDER) {
            (result.messages + result.suppressed)
                .filterNot { it.id in result.contextIds }
                .minByOrNull { it.timeMs }
                ?.let { historyAnchors[key] = it }
        }

        val base = buf.log.remembering(result.suppressed)
        val merged = base.merge(
            incoming = result.messages,
            baseCap = baseCap,
            maxExtra = ChatHistoryController.MAX_BACKFILL_EXTRA,
            growCapacity = true,
            isOwn = ownLinePredicate(splitKey(key).first),
        )
        // Nothing more to gain once the buffer has grown as far as it may.
        val capped = merged.log.extraCapacity >= ChatHistoryController.MAX_BACKFILL_EXTRA
        st.copy(
            buffers = st.buffers + (key to buf.copy(
                log = merged.log,
                historyLoading = false,
                historyExhausted =
                    if (result.kind == BackfillKind.OLDER) result.exhausted || capped
                    else buf.historyExhausted,
            ))
        )
    }
    flushTrailingDivider(key)
    settlePendingReactions(key)
}

/** Record [r] on the message it names in [bufKey], if that message is loaded. */
internal fun IrcViewModel.applyReaction(bufKey: String, r: PendingReaction): ReactionResult {
    val parentId = r.parentId ?: return ReactionResult.MISSING
    var result = ReactionResult.MISSING
    _state.update { st ->
        result = ReactionResult.MISSING
        val buf = st.buffers[bufKey] ?: return@update st
        val idx = buf.messages.indexOfLast { it.msgId == parentId }
        if (idx < 0) return@update st
        val target = buf.messages[idx]
        val who = target.reactions[r.reaction].orEmpty()
        val nextWho = if (r.adding) who + r.fromNick else who - r.fromNick
        val nextReactions =
            if (nextWho.isEmpty()) target.reactions - r.reaction
            else target.reactions + (r.reaction to nextWho)
        if (nextReactions == target.reactions) {
            result = ReactionResult.UNCHANGED
            return@update st
        }
        result = ReactionResult.ATTACHED
        st.copy(buffers = st.buffers + (bufKey to buf.copy(
            log = buf.log.replaceAt(idx, target.copy(reactions = nextReactions))
        )))
    }
    return result
}

/**
 * Hold [r] until its message is fetched, starting the fetch when none is running.
 * Returns false when the message cannot be fetched here.
 */
internal fun IrcViewModel.queueReaction(bufKey: String, r: PendingReaction): Boolean {
    if (r.parentId == null) return false
    if (!supportsChatHistory(bufKey)) return false
    val queue = pendingReactions.getOrPut(bufKey) { ArrayDeque() }
    if (queue.size >= MAX_PENDING_REACTIONS) return false
    queue.addLast(r)
    // A replayed reaction's message may still be on its way in the same replay, so its
    // fetch waits for the replay to end.
    if (!r.isHistory) kickReactionFetch(bufKey)
    return true
}

/**
 * Attach whatever [bufKey]'s pending reactions can now reach, and fetch for the first
 * that is still missing. Waits while the buffer's saved log or held replay is pending,
 * or while a fetch is already running.
 */
internal fun IrcViewModel.kickReactionFetch(bufKey: String) {
    val queue = pendingReactions[bufKey] ?: return
    if (reactionFetchFor.containsKey(bufKey)) return
    if (scrollbackLoads.containsKey(bufKey) || !deferredReplays[bufKey].isNullOrEmpty()) return
    queue.removeAll { applyReaction(bufKey, it) != ReactionResult.MISSING }
    val next = queue.firstOrNull()?.parentId
    if (next == null) {
        pendingReactions.remove(bufKey)
        return
    }
    reactionFetchFor[bufKey] = next
    if (loadMessageContext(bufKey, next, ChatHistoryController.REACTION_CONTEXT_SIZE)) return
    reactionFetchFor.remove(bufKey)
    // Another fetch is running and will kick this one when it ends. With none running,
    // nothing can be fetched, so the reactions are reported as they are.
    val busy = chatHistory.isBackfilling(bufKey) || _state.value.buffers[bufKey]?.historyLoading == true
    if (!busy) failPendingReactions(bufKey)
}

/** Report every reaction waiting on [bufKey] in words. */
internal fun IrcViewModel.failPendingReactions(bufKey: String) {
    val queue = pendingReactions.remove(bufKey) ?: return
    reactionFetchFor.remove(bufKey)
    for (r in queue) reactionFallbackLine(bufKey, r)
}

/**
 * After any fetch into [bufKey]: attach what can be attached, report the reactions whose
 * message the finished fetch was for and did not bring, and fetch for the next one.
 */
internal fun IrcViewModel.settlePendingReactions(bufKey: String) {
    val queue = pendingReactions[bufKey] ?: return
    val fetched = reactionFetchFor.remove(bufKey)
    val left = ArrayDeque<PendingReaction>()
    for (r in queue) {
        when {
            applyReaction(bufKey, r) != ReactionResult.MISSING -> Unit
            fetched != null && r.parentId == fetched -> reactionFallbackLine(bufKey, r)
            else -> left.addLast(r)
        }
    }
    if (left.isEmpty()) {
        pendingReactions.remove(bufKey)
        return
    }
    pendingReactions[bufKey] = left
    kickReactionFetch(bufKey)
}

/** Say [r] in words, for a message that is not available. */
internal fun IrcViewModel.reactionFallbackLine(bufKey: String, r: PendingReaction) {
    // A removal from a message nobody can see tells the reader nothing.
    if (!r.adding) return
    val line = appContext.getString(R.string.reaction_added, r.fromNick, r.reaction)
    append(bufKey, from = null,
        text = "* $line",
        timeMs = r.timeMs, doNotify = false, isLocal = true,
        isHistory = r.isHistory)
}

/** Drop reactions waiting on [bufKey]. */
internal fun IrcViewModel.dropPendingReactions(bufKey: String) {
    pendingReactions.remove(bufKey)
    reactionFetchFor.remove(bufKey)
}

/**
 * Draw the history divider as the last line of a replay that nothing followed, since the
 * usual trigger is the first live message after a catch-up.
 */
internal fun IrcViewModel.flushTrailingDivider(bufferKey: String) {
    val pending = chatHistory.claimTrailingDivider(bufferKey) ?: return
    _state.update { st ->
        val buf = st.buffers[bufferKey] ?: return@update st
        if (buf.messages.isEmpty()) return@update st
        // After the last replayed line: a catch-up reply arrives once the join, topic
        // and member list are already printed.
        val lastReplay = buf.messages.indexOfLast { it.fromHistory }
        if (lastReplay < 0) return@update st
        st.copy(
            buffers = st.buffers + (bufferKey to buf.copy(
                log = buf.log.insertDividerAt(lastReplay + 1, historyDivider(pending))
            ))
        )
    }
}

/** Build the "chat history" separator line for a boundary at [newestReplayMs]. */
internal fun IrcViewModel.historyDivider(newestReplayMs: Long): UiMessage {
    val newestStr = runCatching {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(newestReplayMs))
    }.getOrElse { java.util.Date(newestReplayMs).toString() }
    return UiMessage(
        id = nextUiMsgId.getAndIncrement(),
        timeMs = newestReplayMs + 1L,
        from = null,
        text = appContext.getString(R.string.vm_history_divider, newestStr),
    )
}

/**
 * Request what [key] missed while disconnected (CHATHISTORY AFTER), anchored on [after] when
 * continuing, otherwise on the newest message held from before this join or connection. Its
 * messages go at the bottom like live traffic. Registered with the controller so its batch
 * can't close a backfill.
 */
internal fun IrcViewModel.requestHistoryCatchup(
    netId: String,
    key: String,
    target: String,
    round: Int = 0,
    claimSlot: Boolean = true,
    after: UiMessage? = null,
    limit: Int = ChatHistoryController.PAGE_SIZE,
    maxSlots: Int = ChatHistoryController.MAX_CATCHUP_TARGETS,
) {
    val rt = runtimes[netId] ?: return
    if (!rt.client.supportsChatHistory()) return
    if (claimSlot && !chatHistory.claimCatchup(netId, key, maxSlots)) return

    val buf = _state.value.buffers[key]
    // A server that takes no timestamp selector can only be anchored on a message it
    // sent, since only those carry a msgid.
    val needsMsgId = !rt.client.acceptsHistoryTimestamps()
    val usable = { m: UiMessage -> m.from != null && (!needsMsgId || m.msgId != null) }
    val heldBefore = joinStartId[key] ?: historySessionStartId[netId] ?: Long.MAX_VALUE

    val held = after?.takeIf(usable)
        ?: buf?.messages
            ?.filter { usable(it) && (it.fromLog || it.id < heldBefore) }
            ?.maxByOrNull { it.timeMs }
    // After leaving a channel, what was missed starts when we left, which is later than
    // the last message held when the channel was quiet.
    val leftAt = if (after == null && !needsMsgId) leftChannelAtMs.remove(key) else null
    val fromMs = when {
        leftAt != null && (held == null || leftAt > held.timeMs) -> leftAt
        else -> held?.timeMs
    }
    val fromMsgId = if (held != null && fromMs == held.timeMs) held.msgId else null
    // The far end: the oldest message this session saw arrive live, which bounds the
    // gap on both sides. Absent until traffic arrives, which is usually a moment after
    // connecting, and absent for a buffer whose catch-up beat the first message to it.
    val to = sessionFirstLive[key]
        ?.takeIf { usable(it) && fromMs != null && it.timeMs > fromMs }

    catchupAnchorMs[key] = fromMs ?: 0L
    if (held != null && fromMs == held.timeMs) catchupCursor[key] = held
    val fromTs = fromMs?.let { runCatching { ChatHistoryController.anchorTimestamp(it) }.getOrNull() }
    val toTs = to?.let { runCatching { ChatHistoryController.anchorTimestamp(it.timeMs) }.getOrNull() }

    viewModelScope.launch {
        val request = runCatching {
            when {
                // Nothing to anchor on, so ask for the most recent page instead.
                fromMs == null -> rt.client.requestChatHistoryLatest(target, limit)
                // Bounded at both ends, which is what the spec recommends for a gap.
                to != null -> rt.client.requestChatHistoryBetween(
                    target = target,
                    fromTimestamp = fromTs,
                    fromMsgId = fromMsgId,
                    toTimestamp = toTs,
                    toMsgId = to.msgId,
                    limit = limit,
                )
                else -> rt.client.requestChatHistoryAfter(target, fromTs, fromMsgId, limit)
            }
        }.getOrNull()
        if (request != null) {
            chatHistory.expectCatchup(key, target, request.label, request.limit, round)
        }
    }
}

/**
 * Decide whether a catch-up page closed its gap: a short page, or one marked as the end, did; a
 * full page means more, so request the next. The run is capped, and anything still missing is
 * marked in the buffer.
 */
internal fun IrcViewModel.onCatchupPage(page: CatchupPage) {
    // A LATEST page had no gap to fill: older messages are reached by scrolling up.
    val anchored = (catchupAnchorMs[page.bufferKey] ?: 0L) > 0L
    if (page.complete || page.lines < page.requested || !anchored) {
        clearHistoryGap(page.bufferKey)
        return
    }
    // A full page that ended where the last one started means the requests are going in
    // circles, which a server that answers BETWEEN from the far end does.
    val next = page.newest
    val advanced = next != null && next.timeMs > (catchupAnchorMs[page.bufferKey] ?: 0L)
    if (next != null && advanced) catchupCursor[page.bufferKey] = next
    if (!advanced || page.round + 1 >= ChatHistoryController.MAX_CATCHUP_ROUNDS) {
        markHistoryGap(page.bufferKey)
        return
    }
    val (netId, _) = splitKey(page.bufferKey)
    requestHistoryCatchup(
        netId, page.bufferKey, page.target, page.round + 1,
        claimSlot = false, after = next, limit = page.requested,
    )
}

/** Fetch [channel]'s history once its saved log has been read, so the anchor is known. */
internal fun IrcViewModel.requestJoinCatchup(netId: String, key: String, channel: String, limit: Int) {
    if (scrollbackLoads.containsKey(key)) {
        pendingJoinCatchups[key] = limit
        return
    }
    requestHistoryCatchup(netId, key, channel, limit = limit, maxSlots = Int.MAX_VALUE)
}

/** Send the join catch-up [key] was waiting to send, if any. */
internal fun IrcViewModel.runPendingJoinCatchup(key: String) {
    val limit = pendingJoinCatchups.remove(key) ?: return
    val (netId, channel) = splitKey(key)
    requestHistoryCatchup(netId, key, channel, limit = limit, maxSlots = Int.MAX_VALUE)
}

/** Say in the buffer that messages between what it holds and the live conversation are missing. */
internal fun IrcViewModel.markHistoryGap(key: String) {
    _state.update { st ->
        val buf = st.buffers[key] ?: return@update st
        if (buf.historyGapOpen) return@update st
        // The gap follows the last message the catch-up reached.
        val cursor = catchupCursor[key]
        val cursorAt = cursor?.let { c -> buf.messages.indexOfLast { it.id == c.id || (c.msgId != null && it.msgId == c.msgId) } } ?: -1
        val at = if (cursorAt >= 0) cursorAt + 1 else buf.messages.size
        val marker = UiMessage(
            id = nextUiMsgId.getAndIncrement(),
            timeMs = buf.messages.getOrNull(at - 1)?.timeMs ?: System.currentTimeMillis(),
            from = null,
            text = appContext.getString(R.string.vm_history_gap),
        )
        gapMarkers[key] = marker.id
        st.copy(
            buffers = st.buffers + (key to buf.copy(
                log = buf.log.insertDividerAt(at, marker),
                historyGapOpen = true,
            ))
        )
    }
}

/** Take the gap marker back out, for a catch-up that reached the live conversation. */
internal fun IrcViewModel.clearHistoryGap(key: String) {
    val markerId = gapMarkers.remove(key)
    _state.update { st ->
        val buf = st.buffers[key] ?: return@update st
        if (!buf.historyGapOpen) return@update st
        val log = if (markerId == null) buf.log
        else buf.log.retaining(buf.messages.map { it.id }.filterNot { it == markerId }.toSet())
        st.copy(buffers = st.buffers + (key to buf.copy(log = log, historyGapOpen = false)))
    }
}

/**
 * Pick up a catch-up that stopped with messages still missing. Called when the buffer is
 * opened, so the gap fills as the user reaches the conversations they care about rather
 * than all at once on connect.
 */
internal fun IrcViewModel.resumeHistoryGap(key: String) {
    val buf = _state.value.buffers[key] ?: return
    if (!buf.historyGapOpen) return
    val (netId, bufferName) = splitKey(key)
    if (isPseudoBuffer(bufferName) || isDccChatBufferName(bufferName)) return
    requestHistoryCatchup(netId, key, bufferName, round = 0, claimSlot = false, after = catchupCursor[key])
}

/**
 * Drop this network's history bookkeeping on registration. "Exhausted" described the
 * previous connection, so buffers get another chance rather than hiding the control.
 */
internal fun IrcViewModel.clearHistoryStateFor(netId: String) {
    // Fetches in flight die with the connection, so what they were for is reported now.
    pendingReactions.keys.filter { it.startsWith("$netId::") }.forEach { failPendingReactions(it) }
    chatHistory.forgetNetwork(netId)
    historySessionStartId[netId] = nextUiMsgId.get()
    val prefix = "$netId::"
    historyAnchors.keys.filter { it.startsWith(prefix) }.toList().forEach { historyAnchors.remove(it) }
    sessionFirstLive.keys.filter { it.startsWith(prefix) }.toList().forEach { sessionFirstLive.remove(it) }
    catchupAnchorMs.keys.filter { it.startsWith(prefix) }.toList().forEach { catchupAnchorMs.remove(it) }
    catchupCursor.keys.filter { it.startsWith(prefix) }.toList().forEach { catchupCursor.remove(it) }
    pendingJoinCatchups.keys.filter { it.startsWith(prefix) }.toList().forEach { pendingJoinCatchups.remove(it) }
    _state.update { st ->
        var changed = false
        var bufs = st.buffers
        for ((k, b) in st.buffers) {
            if (!k.startsWith(prefix)) continue
            if (!b.historyLoading && !b.historyExhausted) continue
            bufs = bufs + (k to b.copy(historyLoading = false, historyExhausted = false))
            changed = true
        }
        if (changed) st.copy(buffers = bufs) else st
    }
}
