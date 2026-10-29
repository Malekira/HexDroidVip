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

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.boxlabs.hexdroid.IrcViewModel.ChannelEventKind
import com.boxlabs.hexdroid.IrcViewModel.Companion.AUTO_REJOIN_DELAY_MS
import com.boxlabs.hexdroid.IrcViewModel.Companion.AUTO_REJOIN_SUPPRESS_MS
import com.boxlabs.hexdroid.IrcViewModel.Companion.CHANNEL_LIST_FLUSH_INTERVAL_MS
import com.boxlabs.hexdroid.IrcViewModel.Companion.WHO_ON_JOIN_MAX_MEMBERS
import com.boxlabs.hexdroid.IrcViewModel.NamesRequest
import com.boxlabs.hexdroid.data.ChannelListEntry
import com.boxlabs.hexdroid.data.IgnoreType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Channel membership, topics, modes and the channel list. */
internal fun IrcViewModel.handleChannelEvent(netId: String, ev: IrcEvent) {
    when (ev) {
        is IrcEvent.ChannelModeLine -> {
            val st = _state.value
            val chanKey = resolveBufferKey(netId, ev.channel)
            if (showsEvent(chanKey, ChannelEventKind.MODE)) {
                val suppressUnread = ev.isHistory && !st.settings.ircHistoryCountsAsUnread
                ensureBuffer(chanKey)
                append(chanKey, from = null, text = ev.line, isLocal = suppressUnread, timeMs = ev.timeMs, doNotify = false, isHistory = ev.isHistory)
            }
        }

        is IrcEvent.Names -> {
            // Treat NAMES as a bounded snapshot (353...366). Even if we didn't explicitly request it,
            // servers send NAMES after JOIN and some bouncers can replay it. We accumulate until
            // NamesEnd then replace the channel's userlist.
                val rt = runtimes[netId]
                if (rt == null) {
                    // Network is no longer active; ignore.
                } else {
            val keyFold = namesKeyFold(ev.channel)
                    val existing = rt.namesRequests[keyFold]
                    if (existing != null) {
                        existing.names.addAll(ev.names)
                    } else {
                        val chanKey = resolveBufferKey(netId, ev.channel)
                        ensureBuffer(chanKey)
                        val nr = NamesRequest(replyBufferKey = chanKey, printToBuffer = false)
                        nr.names.addAll(ev.names)
                        rt.namesRequests[keyFold] = nr
                    }
                }
        }

        is IrcEvent.NamesEnd -> {
            val rt = runtimes[netId]
            val keyFold = namesKeyFold(ev.channel)
            val req = rt?.namesRequests?.remove(keyFold)
            if (req != null) {
                val st1 = _state.value
                val meFold = casefoldText(netId, st1.connections[netId]?.myNick ?: st1.myNick)
                val includesMe = req.names.any {
                    casefoldText(netId, parseNickWithPrefixes(netId, it).first) == meFold
                }
                val names = if (includesMe) {
                    val chanKey = resolveBufferKey(netId, ev.channel)
                    ensureBuffer(chanKey)
                    applyNamesSnapshot(netId, chanKey, req.names.toList())
                    rebuildNicklist(netId, chanKey)
                } else {
                    val ps = prefixSymbols(netId)
                    req.names.sortedWith(
                        compareBy<String> { n -> ps.indexOf(n.firstOrNull() ?: ' ').let { if (it < 0) ps.length else it } }
                            .thenBy { casefoldText(netId, parseNickWithPrefixes(netId, it).first) }
                    )
                }
                if (req.printToBuffer) {
                    appendNamesList(req.replyBufferKey, ev.channel, names)
                }
                // The join's member list is in: fetch hosts, accounts and away state now for a
                // channel of modest size, or when a large one's member list is first shown.
                if (includesMe && rt.whoAfterJoin.remove(keyFold) && rt.client.whoxAvailable()) {
                    if (names.size <= WHO_ON_JOIN_MAX_MEMBERS) {
                        viewModelScope.launch { runCatching { rt.client.refreshChannelWho(ev.channel) } }
                    } else {
                        rt.whoDeferred.add(keyFold)
                    }
                }
            }
        }

        is IrcEvent.Joined -> {
            if (!ev.isHistory) scriptEvent(
                "JOIN", netId, ev.channel, ev.nick, isMe = isMyNick(netId, ev.nick),
                fields = mapOf("account" to (ev.account ?: "")),
            )
            val st0 = _state.value
            val suppressUnread = ev.isHistory && !st0.settings.ircHistoryCountsAsUnread
            // Recorded even when joins are hidden: a join is what precedes a replay.
            if (!ev.isHistory) ev.timeMs?.let { newestLiveServerTimeMs.merge(netId, it, ::maxOf) }

            val chanKey = resolveBufferKey(netId, ev.channel)
            ensureBuffer(chanKey)

            val myNickJ = st0.connections[netId]?.myNick ?: st0.myNick
            val isSelfJoin = casefoldText(netId, ev.nick) == casefoldText(netId, myNickJ)
            if (isSelfJoin && !ev.isHistory) {
                // Catch-up for this join starts from what was held before it.
                joinStartId[chanKey] = nextUiMsgId.get()
                sessionFirstLive.remove(chanKey)
                catchupCursor.remove(chanKey)
            }

            // With draft/event-playback, CHATHISTORY replays our own JOIN straight back
            // at us, so "Now talking on #chan" printed once live and again from history.
            // Someone else's join under the smart filter is held rather than shown, unless
            // they spoke here recently.
            val joinShown = if (isSelfJoin) showsEvent(chanKey, ChannelEventKind.JOIN)
                else showsOthersEvent(netId, chanKey, ChannelEventKind.JOIN, ev.nick, ev.timeMs)
            val holdJoin = !joinShown && !isSelfJoin && showsEvent(chanKey, ChannelEventKind.JOIN) && smartFilterOn(chanKey) &&
                !isIgnoredSender(netId, ev.nick, IgnoreType.EVENTS, ev.userHost)
            if ((joinShown || holdJoin) && !(ev.isHistory && isSelfJoin)) {
                val msg = membershipEventLine(
                    MembershipEvent.JOIN, ev.nick, ev.userHost, ev.channel,
                    hideHostnames = st0.settings.hideHostnames,
                    colors = st0.settings.colorChannelEvents,
                )
                if (holdJoin) {
                    smartFilter.holdJoin(
                        casefoldText(netId, ev.nick),
                        SmartFilter.HeldEvent(chanKey, msg, ev.timeMs ?: System.currentTimeMillis(), ev.isHistory),
                    )
                } else {
                    append(
                        chanKey,
                        from = null,
                        text = msg,
                        isLocal = suppressUnread,
                        timeMs = ev.timeMs,
                        isHistory = ev.isHistory,
                        doNotify = false
                    )
                }
            }


            val myNickNow = st0.connections[netId]?.myNick ?: st0.myNick
            val isMeNow = casefoldText(netId, ev.nick) == casefoldText(netId, myNickNow)

            // +AGE: tell the bridge a peer joined, so it knows they missed any IDENT we already
            // sent on this channel and re-announces for them (and ONLY for them, so two clients
            // that were both already present don't each answer an IDENT the other already has).
            // Deliberately not gated on the chat +AGE pref: a scripted game channel keys itself
            // through age.join without that pref ever being set. The bridge no-ops for channels
            // we have never announced on, so this is cheap for every other JOIN.
            if (!isMeNow) ageBridgeFor(netId)?.onMemberJoined(ev.channel, ev.nick)

            // Track our own join times for the notice-routing recently-joined-channel
            // fallback. Stamps the per-buffer key when WE join (not when other users
            // join), and only for live joins (history replays don't represent a "we
            // just walked into this channel" moment that bots would greet on).
            if (isMeNow && !ev.isHistory) {
                // Resume +AGE key agreement after a (re)join: the pref persists across restarts but
                // the bridge is recreated empty, so re-announce our identity to the channel here.
                if (ageEnabledPrefs.getBoolean(ageKey(netId, ev.channel), false)) ageBridgeFor(netId)?.enableChat(ev.channel)
                val now = System.currentTimeMillis()
                recentJoinAtMs[chanKey] = now
                // Cap map size: we only ever read entries within a 5 s window in the
                // notice handler, so there's no point holding older ones around. A
                // user who join-spams 32+ channels back-to-back would otherwise grow
                // this map unbounded.
                if (recentJoinAtMs.size > 32) {
                    val cutoff = now - 5_000L
                    recentJoinAtMs.entries.removeAll { it.value < cutoff }
                }
                // Arm the history divider for this join: if replayed history and then the first
                // live message arrive within the window, the divider is inserted. Later
                // playback or a manual request won't trigger it.
                chatHistory.armDivider(chanKey, ChatHistoryController.DIVIDER_WINDOW_MS)
            }

            if (isMeNow || shouldAffectLiveState(ev.isHistory, ev.timeMs)) {
                // Re-read state after append/ensureBuffer so we don't overwrite newly appended messages.
                val st1 = _state.value

                upsertNickInChannel(netId, chanKey, baseNick = ev.nick)
                val updated = rebuildNicklist(netId, chanKey)

                val myNick = st1.connections[netId]?.myNick ?: st1.myNick
                val isMe = casefoldText(netId, ev.nick) == casefoldText(netId, myNick)

                // On self-join, the server sends the member list (NAMES) unprompted, and IrcCore
                // asks for it under no-implicit-names. Live joins only: a CHATHISTORY replay of
                // our own JOIN is not a new join.
                if (isMe && !ev.isHistory) {
                    val rt = runtimes[netId]
                    rt?.whoAfterJoin?.add(namesKeyFold(ev.channel))
                    // A rejoin under no-implicit-names gets no list from IrcCore, which asks
                    // once per session, so ask here. Not printed to the buffer.
                    val keyFold = namesKeyFold(ev.channel)
                    val noImplicitNames = rt?.client?.let {
                        it.hasCap("soju.im/no-implicit-names") || it.hasCap("draft/no-implicit-names")
                    } == true
                    if (rt != null && noImplicitNames && !rt.namesRequests.containsKey(keyFold)) {
                        rt.namesRequests[keyFold] = NamesRequest(replyBufferKey = chanKey, printToBuffer = false)
                        viewModelScope.launch { runCatching { rt.client.sendBackground("NAMES ${ev.channel}") } }
                    }

                    // Ask for the channel's modes so the title bar can show them
                    rt?.let { r -> viewModelScope.launch { runCatching { r.client.querySilentModes(ev.channel) } } }

                    // Track for reconnect rejoin if not already covered by autoJoin.
                    // Skip history/playback - we only care about live self-joins.
                    if (!ev.isHistory && rt != null) {
                        val profile = st1.networks.firstOrNull { it.id == netId }
                        val isAutoJoin = profile?.autoJoin?.any {
                            casefoldText(netId, it.channel.split(",")[0].trim()) == casefoldText(netId, ev.channel)
                        } == true
                        if (!isAutoJoin) rememberManualJoin(netId, rt, ev.channel, ev.key)
                    }
                }
                // Decide whether this self-JOIN should pull the user onto the channel.
                // It should for an explicit user join (typed /join or the join button),
                // recorded in pendingUserJoinSwitch. It should NOT for the automatic rejoin
                // burst after a reconnect (client-sent rejoins and bouncer-replayed JOINs),
                // which is what suppressAutoJoinSwitchUntilMs guards. An explicit user join
                // always wins, even inside the suppression window.
                val rtForSwitch = runtimes[netId]
                // Only a live self-JOIN may switch the view. A replayed JOIN (e.g. in a history
                // reply) is a record, not a request.
                val isLiveSelfJoin = isMe && !ev.isHistory
                // Consume the intent only on an actual self-join so a JOIN by someone else
                // never clears it.
                val userRequestedJoin =
                    isLiveSelfJoin &&
                        rtForSwitch?.pendingUserJoinSwitch?.remove(casefoldText(netId, ev.channel)) == true
                val autoSwitchSuppressed =
                    rtForSwitch != null &&
                        System.currentTimeMillis() < rtForSwitch.suppressAutoJoinSwitchUntilMs
                val shouldSwitch =
                    isLiveSelfJoin &&
                        st1.activeNetworkId == netId &&
                        (st1.screen == AppScreen.CHAT || st1.screen == AppScreen.NETWORKS) &&
                        (userRequestedJoin || !autoSwitchSuppressed)

                if (shouldSwitch) {
                    val leaving = st1.selectedBuffer
                    if (leaving.isNotBlank() && leaving != chanKey) stampReadMarker(leaving)
                }

                // Re-read after stampReadMarker so its write isn't clobbered.
                val st2 = _state.value
                val next = st2.copy(
                    nicklists = st2.nicklists + (chanKey to updated),
                    selectedBuffer = if (shouldSwitch) chanKey else st2.selectedBuffer,
                    screen = if (shouldSwitch) AppScreen.CHAT else st2.screen
                )
                _state.value = syncActiveNetworkSummary(next)
            }
        }


        is IrcEvent.Parted -> {
            if (!ev.isHistory) scriptEvent("PART", netId, ev.channel, ev.nick, ev.reason.orEmpty(), isMyNick(netId, ev.nick))
            val st0 = _state.value
            val suppressUnread = ev.isHistory && !st0.settings.ircHistoryCountsAsUnread

            // If this PART is the result of closing the buffer, don't recreate the buffer on the echo.
            val myNickNow = st0.connections[netId]?.myNick ?: st0.myNick
            val isMe = casefoldText(netId, ev.nick) == casefoldText(netId, myNickNow)
            // Our own earlier departures, replayed: nothing to show or act on.
            if (ev.isHistory && isOwnNickForReplay(netId, ev.nick, myNickNow)) return
            if (isMe) {
                val leftKey = resolveBufferKey(netId, ev.channel)
                chatHistory.releaseCatchup(leftKey)
                leftChannelAtMs[leftKey] = ev.timeMs ?: System.currentTimeMillis()
                val pendingKey = popPendingCloseForChannel(netId, ev.channel)
                // User explicitly left - remove from reconnect rejoin list.
                runtimes[netId]?.let { forgetManualJoin(netId, it, ev.channel) }
                if (pendingKey != null) {
                    append(
                        bufKey(netId, "*server*"),
                        from = null,
                        text = "*** " + appContext.getString(R.string.vm_left_channel, ev.channel),
                        isLocal = suppressUnread,
                        timeMs = ev.timeMs,
                        isHistory = ev.isHistory,
                        doNotify = false
                    )
                    return
                }
            }

            val chanKey = resolveBufferKey(netId, ev.channel)

            val partShown = if (isMe) showsEvent(chanKey, ChannelEventKind.PART)
                else showsOthersEvent(netId, chanKey, ChannelEventKind.PART, ev.nick, ev.timeMs)
            if (!isMe) smartFilter.left(chanKey, casefoldText(netId, ev.nick))
            if (partShown) {
                val msg = membershipEventLine(
                    MembershipEvent.PART, ev.nick, ev.userHost, ev.channel, ev.reason,
                    hideHostnames = st0.settings.hideHostnames,
                    colors = st0.settings.colorChannelEvents,
                )
                append(
                    chanKey,
                    from = null,
                    text = msg,
                    isLocal = suppressUnread,
                    timeMs = ev.timeMs,
                    isHistory = ev.isHistory,
                    doNotify = false
                )
            }

            // Nicklist removal, for live PARTs only. A replayed PART is from before the
            // member list the server sent on join: someone listed now came back since.
            val affectLivePart = shouldAffectLiveState(ev.isHistory, ev.timeMs)
            if (affectLivePart) {
                // Re-read state after append so we don't overwrite the message we just appended.
                val st1 = _state.value
                removeNickFromChannel(netId, chanKey, ev.nick)
                if (!isMe) forgetAwayIfUnseen(netId, ev.nick)
                val updated = rebuildNicklist(netId, chanKey)
                // Clear any pending typing indicator for the parted nick,
                // and cancel the expiry coroutine so it doesn't linger for 30 s.
                val bufAfterPart = st1.buffers[chanKey]
                val clearedBuf = if (bufAfterPart != null && ev.nick in bufAfterPart.typingNicks)
                    bufAfterPart.copy(typingNicks = bufAfterPart.typingNicks - ev.nick) else bufAfterPart
                val newBufs = if (clearedBuf != null) st1.buffers + (chanKey to clearedBuf) else st1.buffers
                receivedTypingExpiryJobs.remove("$chanKey/${ev.nick}")?.cancel()
                _state.value = syncActiveNetworkSummary(st1.copy(nicklists = st1.nicklists + (chanKey to updated), buffers = newBufs))
            }
        }

        is IrcEvent.Kicked -> {
            if (!ev.isHistory) scriptEvent(
                "KICK", netId, ev.channel, ev.byNick, ev.reason.orEmpty(), isMyNick(netId, ev.victim),
                fields = mapOf("victim" to ev.victim),
            )
            val st0 = _state.value
            val suppressUnread = ev.isHistory && !st0.settings.ircHistoryCountsAsUnread

            val chanKey = resolveBufferKey(netId, ev.channel)
            ensureBuffer(chanKey)

            run {
                val myNick = st0.connections[netId]?.myNick ?: st0.myNick
                val kickedMe = ev.victim.equals(myNick, ignoreCase = true)
                if (!kickedMe) smartFilter.left(chanKey, casefoldText(netId, ev.victim))
                if (!kickedMe && !showsEvent(chanKey, ChannelEventKind.KICK)) return@run
                val by = ev.byNick ?: "?"
                val reason = ev.reason?.takeIf { it.isNotBlank() }
                val msg = if (kickedMe) {
                    "* " + appContext.getString(R.string.vm_ev_you_kicked, ev.channel, by) + (reason?.let { " [$it]" } ?: "")
                } else {
                    "* " + appContext.getString(R.string.vm_ev_kicked, ev.victim, by) + (reason?.let { " [$it]" } ?: "")
                }

                append(
                    chanKey,
                    from = null,
                    text = colorEvent(msg, 4),  // red
                    isLocal = suppressUnread,
                    timeMs = ev.timeMs,
                    isHistory = ev.isHistory,
                    doNotify = false
                )
            }

            if (shouldAffectLiveState(ev.isHistory, ev.timeMs)) {
                // Re-read state after append so we don't overwrite the message we just appended.
                val st1 = _state.value

                val myNick = st1.connections[netId]?.myNick ?: st1.myNick
                val victimIsMe = casefoldText(netId, ev.victim) == casefoldText(netId, myNick)
                val kickedKey = if (victimIsMe) {
                    runtimes[netId]?.let { forgetManualJoin(netId, it, ev.channel) }
                        ?: autoJoinKey(netId, ev.channel)
                } else null

                removeNickFromChannel(netId, chanKey, ev.victim)
                if (!victimIsMe) forgetAwayIfUnseen(netId, ev.victim)
                if (victimIsMe) {
                    chatHistory.releaseCatchup(chanKey)
                    leftChannelAtMs[chanKey] = ev.timeMs ?: System.currentTimeMillis()
                    chanNickCase[chanKey] = mutableMapOf()
                    chanNickStatus[chanKey] = mutableMapOf()
                }

                val finalList = if (victimIsMe) emptyList() else rebuildNicklist(netId, chanKey)
                _state.value = syncActiveNetworkSummary(st1.copy(nicklists = st1.nicklists + (chanKey to finalList)))

                // Auto-rejoin on kick. Off by default. Throttled to one rejoin per channel per
                // AUTO_REJOIN_SUPPRESS_MS so we don't loop against +i/+k/+b modes or against an
                // op who is actively kick-banning. The small AUTO_REJOIN_DELAY_MS makes it feel
                // less adversarial than an instant rejoin.
                if (victimIsMe && st1.settings.rejoinOnKick) {
                    val rejoinKey = "$netId::${ev.channel.lowercase()}"
                    val now = System.currentTimeMillis()
                    // Opportunistic eviction: if the map has grown past a small bound, drop entries
                    // older than 2× the suppress window so it can't grow without limit on a busy
                    // channel-hopping user.
                    if (recentKickRejoins.size > 32) {
                        val cutoff = now - (AUTO_REJOIN_SUPPRESS_MS * 2)
                        recentKickRejoins.entries.removeAll { it.value < cutoff }
                    }
                    val last = recentKickRejoins[rejoinKey] ?: 0L
                    if (now - last > AUTO_REJOIN_SUPPRESS_MS) {
                        recentKickRejoins[rejoinKey] = now
                        viewModelScope.launch {
                            delay(AUTO_REJOIN_DELAY_MS)
                            val join = if (kickedKey.isNullOrBlank()) "JOIN ${ev.channel}" else "JOIN ${ev.channel} $kickedKey"
                            runtimes[netId]?.client?.sendRaw(join)
                        }
                    } else {
                        append(chanKey, from = null,
                            text = "*** " + appContext.getString(R.string.vm_autorejoin_suppressed, AUTO_REJOIN_SUPPRESS_MS / 1000),
                            doNotify = false)
                    }
                }
            }
        }

        is IrcEvent.Quit -> {
            if (!ev.isHistory) scriptEvent("QUIT", netId, "", ev.nick, ev.reason.orEmpty(), isMyNick(netId, ev.nick))
            val st0 = _state.value
            val suppressUnread = ev.isHistory && !st0.settings.ircHistoryCountsAsUnread
            val affectLive = shouldAffectLiveState(ev.isHistory, ev.timeMs)

            // Compute which channels the quitting nick is currently in, REGARDLESS of
            // affectLive. The flag only gates state *mutation*; we still need an accurate
            // target list to display the QUIT line only in channels the user actually
            // shared with us.
            val foldedNick = casefoldText(netId, ev.nick)
            val affected = st0.nicklists
                .asSequence()
                .filter { (k, _) -> k.startsWith("$netId::") }
                .filter { (_, list) ->
                    list.any { display ->
                        casefoldText(netId, parseNickWithPrefixes(netId, display).first) == foldedNick
                    }
                }
                .map { it.key }
                .toList()

            // Targets: only channels where the quitting user shared a nicklist with us; with no
            // such evidence the line is dropped. A replayed QUIT is for someone no longer in
            // any nicklist, so it goes to the channel named by its CHATHISTORY batch.
            val targets = when {
                ev.isHistory && ev.historyChannel != null -> listOf(resolveBufferKey(netId, ev.historyChannel))
                affected.isNotEmpty() -> affected
                ev.historyChannel != null -> listOf(resolveBufferKey(netId, ev.historyChannel))
                else -> emptyList()
            }
            // Our own earlier connections quitting, replayed, say nothing about this one.
            val ownReplay = ev.isHistory &&
                isOwnNickForReplay(netId, ev.nick, st0.connections[netId]?.myNick ?: st0.myNick)
            val isMeQuit = casefoldText(netId, ev.nick) == casefoldText(netId, st0.connections[netId]?.myNick ?: st0.myNick)
            val shownTargets = targets.filter { k ->
                if (isMeQuit) showsEvent(k, ChannelEventKind.QUIT)
                else showsOthersEvent(netId, k, ChannelEventKind.QUIT, ev.nick, ev.timeMs)
            }
            if (!isMeQuit) for (k in targets) smartFilter.left(k, foldedNick)
            if (shownTargets.isNotEmpty() && !ownReplay) {
                val coloured = membershipEventLine(
                    MembershipEvent.QUIT, ev.nick, ev.userHost, reason = ev.reason,
                    hideHostnames = st0.settings.hideHostnames,
                    colors = st0.settings.colorChannelEvents,
                )
                for (k in shownTargets) {
                    append(
                        k,
                        from = null,
                        text = coloured,
                        isLocal = suppressUnread,
                        timeMs = ev.timeMs,
                        isHistory = ev.isHistory,
                        doNotify = false
                    )
                }
            }


            // Nicklist removal, for live QUITs only (a recent one counts as live, which
            // covers clock skew). A replayed QUIT is from before the member lists the
            // server sent on join: someone listed now came back since.
            if (affectLive) {
                // The nick is back in the pool, so whatever draft/metadata-2 values it
                // carried describe someone who is no longer here. Left behind, they are
                // shown as the next holder's display name, avatar and colour.
                dropMetadataForNick(netId, ev.nick)
                val keys = _state.value.nicklists.keys.filter { it.startsWith("$netId::") }
                for (k in keys) removeNickFromChannel(netId, k, ev.nick)
                val rebuilt = keys.associateWith { rebuildNicklist(netId, it) }
                // Also remove from away state map - only on truly live events; a stale replay
                // shouldn't reset somebody's current away status.
                nickAwayState[netId]?.remove(casefoldText(netId, ev.nick))
                markAwayInState(netId, ev.nick, false)
                // Clear any pending typing indicator for the quitting nick across all buffers on this network.
                _state.value.buffers.forEach { (k, buf) ->
                    if (k.startsWith("$netId::") && ev.nick in buf.typingNicks) {
                        receivedTypingExpiryJobs.remove("$k/${ev.nick}")?.cancel()
                    }
                }
                _state.update { cur ->
                    val newBufs = cur.buffers.mapValues { (k, buf) ->
                        if (k.startsWith("$netId::") && ev.nick in buf.typingNicks)
                            buf.copy(typingNicks = buf.typingNicks - ev.nick)
                        else buf
                    }
                    syncActiveNetworkSummary(cur.copy(nicklists = cur.nicklists + rebuilt, buffers = newBufs))
                }
            }
        }

        is IrcEvent.TopicReply -> {
            val st0 = _state.value
            val suppressUnread = ev.isHistory && !st0.settings.ircHistoryCountsAsUnread

            val chanKey = resolveBufferKey(netId, ev.channel)
            ensureBuffer(chanKey)

            // Unconditional: a replayed 332 carries the topic the server holds now, and
            // skipping it left the bar stale after a bouncer attach.
            setTopic(chanKey, ev.topic)

            if (!st0.settings.hideTopicOnEntry) {
                // mIRC-style join/topic info line, stamped locally. A replayed server-time
                // tag would sort it into the scrollback and collide with the logged copy.
                val topicText = ev.topic ?: ""
                val msg = "* " + appContext.getString(R.string.vm_ev_topic_is, ev.channel, topicText)
                append(
                    chanKey,
                    from = null,
                    text = msg,
                    isLocal = suppressUnread,
                    doNotify = false
                )
            }
        }
        is IrcEvent.TopicWhoTime -> {
            val st0 = _state.value
            val suppressUnread = ev.isHistory && !st0.settings.ircHistoryCountsAsUnread

            val chanKey = resolveBufferKey(netId, ev.channel)
            ensureBuffer(chanKey)

            if (!st0.settings.hideTopicOnEntry) {
                val whenStr = ev.setAtMs?.let {
                    try {
                        java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", java.util.Locale.US).format(java.util.Date(it))
                    } catch (_: Throwable) {
                        java.util.Date(it).toString()
                    }
                } ?: appContext.getString(R.string.vm_ev_unknown_time)

                val msg = "* " + appContext.getString(R.string.vm_ev_topic_set_by, ev.channel, ev.setter, whenStr)
                append(
                    chanKey,
                    from = null,
                    text = msg,
                    isLocal = suppressUnread,
                    doNotify = false
                )
            }
        }
        is IrcEvent.Topic -> {
            if (!ev.isHistory) scriptEvent("TOPIC", netId, ev.channel, ev.setter, ev.topic.orEmpty(), isMyNick(netId, ev.setter))
            val chanKey = resolveBufferKey(netId, ev.channel)
            ensureBuffer(chanKey)
            // Always update the topic bar; isHistory only gates the chat line below, since a
            // live TOPIC can carry a server time well in the past.
            setTopic(chanKey, ev.topic)
            if (ev.setter != null && !isMyNick(netId, ev.setter)) noteChannelSpeaker(netId, chanKey, ev.setter, ev.timeMs)
            if (!ev.isHistory && showsEvent(chanKey, ChannelEventKind.TOPIC)) {
                // Append a status line so the change is visible in the buffer.
                val topicText = ev.topic?.takeIf { it.isNotBlank() } ?: "(topic cleared)"
                val line = if (ev.setter != null)
                    "* " + appContext.getString(R.string.vm_ev_topic_changed_by, ev.setter, topicText)
                else
                    "* " + appContext.getString(R.string.vm_ev_topic_changed, topicText)
                append(chanKey, from = null, text = line, doNotify = false, timeMs = ev.timeMs, isHistory = ev.isHistory)
            }
        }
        is IrcEvent.ChannelUserMode -> {
            if (!ev.isHistory) {
                val chanKey = resolveBufferKey(netId, ev.channel)
                updateUserMode(netId, chanKey, ev.nick, ev.prefix, ev.adding)
                // Scripts see the change as a mode letter ("+v"), mapped from the prefix symbol.
                val conn = _state.value.connections[netId]
                val symbols = conn?.prefixSymbols ?: "~&@%+"
                val modes = conn?.prefixModes ?: "qaohv"
                val letter = ev.prefix?.let { p -> modes.getOrNull(symbols.indexOf(p)) }
                if (letter != null) {
                    val fields = mapOf(
                        "mode" to (if (ev.adding) "+" else "-") + letter,
                        "victim" to ev.nick,
                        "prefix" to ev.prefix.toString(),
                        // mIRC's names for the affected nick
                        "opnick" to ev.nick, "vnick" to ev.nick, "hnick" to ev.nick,
                    )
                    val isMe = isMyNick(netId, ev.nick)
                    scriptEvent("MODE", netId, ev.channel, ev.byNick, isMe = isMe, fields = fields)
                    // mIRC's per-status events
                    val named = when (letter) {
                        'o' -> if (ev.adding) "OP" else "DEOP"
                        'v' -> if (ev.adding) "VOICE" else "DEVOICE"
                        'h' -> if (ev.adding) "HELP" else "DEHELP"
                        else -> null
                    }
                    if (named != null) scriptEvent(named, netId, ev.channel, ev.byNick, isMe = isMe, fields = fields)
                }
            }
        }
        is IrcEvent.ChannelListStart -> {
            _channelListBuffer.clear()
            _channelListLastFlushMs = 0L
            _state.value = _state.value.copy(listInProgress = true, channelDirectory = emptyList(), listTryAgainMessage = null)
        }
        is IrcEvent.ChannelListItem -> {
            _channelListBuffer.add(ChannelListEntry(ev.channel, ev.users, ev.topic))
            // Time-throttled flush (not every-N-items): keeps the socket draining fast on
            // huge networks so the server's slow-reader LIST cutoff isn't tripped. The
            // first item flushes immediately (lastFlush == 0), then at most every
            // CHANNEL_LIST_FLUSH_INTERVAL_MS. ListEnd always does a final flush.
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - _channelListLastFlushMs >= CHANNEL_LIST_FLUSH_INTERVAL_MS) {
                _channelListLastFlushMs = now
                val snapshot = _channelListBuffer.toList()
                _state.update { it.copy(channelDirectory = snapshot) }
            }
        }
        is IrcEvent.ChannelListEnd -> {
            val snapshot = _channelListBuffer.toList()
            _channelListBuffer.clear()
            _state.update { it.copy(channelDirectory = snapshot, listInProgress = false) }
        }
        is IrcEvent.TryAgain -> {
            // Only the LIST command drives the channel directory UI; other TRYAGAIN
            // targets (rare) just get a server-buffer line via the generic path.
            if (ev.command == "LIST") {
                val msg = ev.message ?: appContext.getString(R.string.vm_ev_list_ratelimited)
                _state.update { it.copy(listInProgress = false, listTryAgainMessage = msg) }
            }
            append(bufKey(netId, "*server*"), from = null,
                text = "*** " + appContext.getString(R.string.vm_command_error, ev.command, ev.message ?: appContext.getString(R.string.vm_try_again)),
                doNotify = false, isLocal = true)
        }
        else -> Unit
    }
}
