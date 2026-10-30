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
import com.boxlabs.hexdroid.IrcViewModel.Companion.ENTRY_NOTICE_WINDOW_MS
import com.boxlabs.hexdroid.data.IgnoreType
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Messages, notices, CTCP replies and DCC offers. */
internal fun IrcViewModel.handleMessageEvent(netId: String, ev: IrcEvent) {
    when (ev) {
        is IrcEvent.DccOfferEvent -> {
            if (isIgnoredSender(netId, ev.offer.from, IgnoreType.DCC)) return

            val offer0 = ev.offer.copy(netId = netId)

            // If this is a passive/reverse DCC reply for one of our outgoing sends, consume it.
            val baseName = offer0.filename.substringAfterLast('/').substringAfterLast('\\')
            val token = offer0.token
            if (token != null) {
                val pending = pendingPassiveDccSends[token]
                if (pending != null
                    && pending.target.equals(offer0.from, ignoreCase = true)
                    && pending.filename == baseName
                    && offer0.port > 0
                    && (offer0.size == 0L || pending.size == 0L || offer0.size == pending.size)
                ) {
                    pendingPassiveDccSends.remove(token)
                    pending.reply.complete(offer0)
                    return
                }
            } else {
                // Fallback: some clients (or bouncers) reply without a token; match by target+filename(+size).
                val match = pendingPassiveDccSends.entries.firstOrNull { (_, p) ->
                    p.target.equals(offer0.from, ignoreCase = true)
                        && p.filename == baseName
                        && offer0.port > 0
                        && (offer0.size == 0L || p.size == 0L || offer0.size == p.size)
                }
                if (match != null) {
                    pendingPassiveDccSends.remove(match.key)
                    match.value.reply.complete(offer0)
                    return
                }
            }

            val st = _state.value
            _state.value = st.copy(dccOffers = st.dccOffers + offer0)

            // Auto-accept: the user has explicitly trusted this nick on this network for
            // unattended receiving.
            if (st.settings.dccEnabled
                && isDccAutoAccepted(netId, offer0.from)
                && getPartialFor(offer0) == null
                && !dccBlockedByLanPermission(offer0)
            ) {
                append(bufKey(netId, "*server*"), from = null,
                    text = "*** " + appContext.getString(
                        R.string.vm_dcc_auto_accepted, offer0.from, offer0.filename))
                acceptDcc(offer0)
                return
            }

            append(bufKey(netId, "*server*"), from = null, text = "*** " + appContext.getString(R.string.vm_dcc_incoming, offer0.from, offer0.filename))
            if (st.settings.notificationsEnabled) {
                notifier.notifyDccIncomingFile(netId, offer0.from, baseName)
            }
        }

        is IrcEvent.DccResumeRequest -> {
            // Peer wants to resume one of our outgoing sends. Find the matching live
            // send by (peer, basename, port-or-token) and, if the position is in range,
            // reply with DCC ACCEPT so the send path can seek the file to that offset.
            val r = ev.resume
            val from = ev.from
            val baseName = r.filename.substringAfterLast('/').substringAfterLast('\\')
            if (isIgnoredSender(netId, from, IgnoreType.DCC)) return

            // Two ways to match: passive (token-based) or active (port-based).
            val match = liveOutgoingSends.values.firstOrNull { live ->
                if (!live.target.equals(from, ignoreCase = true)) return@firstOrNull false
                val nameMatches = live.filename.substringAfterLast('/').substringAfterLast('\\') == baseName
                if (!nameMatches) return@firstOrNull false
                if (r.token != null && live.token != null) {
                    live.token == r.token
                } else {
                    live.port == r.port && r.port > 0
                }
            }
            if (match == null) {
                append(bufKey(netId, "*server*"), from = null,
                    text = "*** " + appContext.getString(R.string.vm_dcc_resume_nomatch, from, baseName),
                    doNotify = false)
                return
            }
            if (r.position < 0L || r.position >= match.size) {
                append(bufKey(netId, "*server*"), from = null,
                    text = "*** " + appContext.getString(R.string.vm_dcc_resume_oor, from, r.position, match.size),
                    doNotify = false)
                return
            }

            // Send DCC ACCEPT echoing the same triple/quadruple, then surface the offset
            // to whoever is waiting on the LiveOutgoingSend's resumeRequest deferred.
            // handleEvent isn't suspend, so the CTCP send hops to a coroutine; the
            // deferred-completion below stays on the event thread so the awaiting
            // sender sees the offset without an extra dispatch.
            val rt = runtimes[netId] ?: return
            val c = rt.client
            val name = quoteDccFilenameIfNeeded(match.filename)
            val tokenField = if (r.token != null) " ${r.token}" else if (match.token != null) " ${match.token}" else ""
            val payload = "DCC ACCEPT $name ${r.port} ${r.position}$tokenField"
            viewModelScope.launch { runCatching { c.ctcp(from, payload) } }
            append(bufKey(netId, "*server*"), from = null,
                text = "*** " + appContext.getString(R.string.vm_dcc_resume_honour, from, r.position),
                doNotify = false)
            // Don't blow up if multiple RESUMEs arrive (unusual but not illegal).
            if (!match.resumeRequest.isCompleted) match.resumeRequest.complete(r.position)
        }

        is IrcEvent.DccAcceptResponse -> {
            // The peer ACCEPTed a RESUME we sent. Hand the accept to the waiting
            // negotiateResumeOrZero() coroutine so it can proceed with the receive.
            val a = ev.accept
            val baseName = a.filename.substringAfterLast('/').substringAfterLast('\\')
            if (isIgnoredSender(netId, ev.from, IgnoreType.DCC)) return
            // The pending map is keyed by (peer, basename, size). We don't have `size`
            // in the ACCEPT payload, so we have to look up by (peer, basename) and
            // tolerate at most one match per peer/basename at a time. Realistically
            // a user never has two distinct in-flight RESUME requests for the same
            // basename from the same peer.
            val prefix = "${ev.from.lowercase()}|$baseName|"
            val key = pendingResumeRequests.keys.firstOrNull { it.startsWith(prefix) }
            if (key != null) {
                pendingResumeRequests[key]?.complete(a)
            }
        }

        is IrcEvent.DccChatOfferEvent -> {
            if (isIgnoredSender(netId, ev.offer.from, IgnoreType.DCC)) return

            val offer0 = ev.offer.copy(netId = netId)
            val st = _state.value
            // De-dupe by peer + endpoint.
            val exists = st.dccChatOffers.any {
                it.netId == netId && it.from.equals(offer0.from, ignoreCase = true) && it.ip == offer0.ip && it.port == offer0.port
            }
            if (!exists) {
                _state.value = st.copy(dccChatOffers = st.dccChatOffers + offer0)
                // Create the DCC chat buffer immediately so the user can see and act on the
                // offer without having to navigate to the Transfers screen.
                val chatKey = findDccChatKey(netId, offer0.from)
                ensureBuffer(chatKey)
                append(
                    bufKey(netId, "*server*"),
                    from = null,
                    text = "*** " + appContext.getString(R.string.vm_dcc_chat_incoming, offer0.from),
                    doNotify = false
                )
                // Show the offer inline inside the dedicated buffer with a clear prompt.
                append(
                    chatKey,
                    from = null,
                    text = "*** " + appContext.getString(R.string.vm_dcc_chat_offer, offer0.from, offer0.ip, dccChatPortLabel(offer0.port, offer0.secure)),
                    doNotify = false
                )
                if (st.settings.notificationsEnabled) {
                    // Pass the DCC chat buffer key so the notification deep-links directly
                    // into the buffer (where Accept/Reject inline actions live), rather than
                    // requiring the user to navigate to the generic Transfers screen first.
                    notifier.notifyDccIncomingChat(netId, offer0.from, dccBuffer = splitKey(chatKey).second)
                }
            }
        }

        is IrcEvent.NotOnChannel -> {
            val chan = normalizeIncomingBufferName(netId, ev.channel)
            val pendingKey = popPendingCloseForChannel(netId, chan)
            if (pendingKey != null) {
                // We tried to part/close a channel we're not in; drop the buffer anyway.
                removeBuffer(pendingKey)
                append(bufKey(netId, "*server*"), from = null, text = "*** " + appContext.getString(R.string.vm_closed_buffer, chan), doNotify = false)
            }
        }
        is IrcEvent.ChatMessage -> {
            // Bot Mode: a `bot`-tagged message is proof the sender is a bot; remember it
            // so the member list and nick sheet can badge them even before a WHO.
            if (ev.fromBot) markBotInState(netId, ev.from)
            // The echo of a line shown when it was sent, matched to its send by the client.
            val ownAnswer = ev.ownSendId?.let { takeOwnSend(netId, it) }
            if (ownAnswer?.finished == true) {
                // One batch is logged as the server has it; several are logged as typed.
                val line = ownAnswer.line
                logOwnLine(line, if (line.batches == 1) ev.text else line.text, ev.timeMs)
            }
            val my = _state.value.connections[netId]?.myNick ?: runtimes[netId]?.myNick ?: _state.value.myNick
            val fromMe = ev.from.equals(my, ignoreCase = true)
            if (!fromMe && ev.isPrivate && isIgnoredSender(netId, ev.from, IgnoreType.PRIVATE)) return
            // +AGE transport tap: AGE MSG/DEAL/IDENT/INVITE/REKEY lines are protocol, not chat.
            // Hand them to the bridge (which raises age_msg/age_deal into scripts) and, if it
            // consumed the line, suppress it from the buffer. Cheap prefix guard first.
            val ageConv = if (isChannelTarget(ev.target)) ev.target else ev.from
            // Self-heal after a restart: the +AGE pref persists but the bridge is recreated
            // empty. An inbound AGE line for a target we have +AGE on, but no live session for, must (re)start key agreement.
            if (ev.text.startsWith("AGE ") &&
                ageEnabledPrefs.getBoolean(ageKey(netId, ageConv), false) &&
                ageBridgeFor(netId)?.isActive(ageConv) != true) {
                if (isChannelTarget(ev.target)) ageBridgeFor(netId)?.enableChat(ageConv)
                else ageBridgeFor(netId)?.enablePm(ageConv)
            }
            if (ev.text.startsWith("AGE ")) {
                onAgeWireLine(netId, ageConv, ev.from, ev.text)
                // Never render raw +AGE protocol in the buffer, even if the bridge was momentarily
                // unavailable when the line arrived (it retries, so key agreement recovers).
                if (isAgeProtocolVerb(ev.text)) return
            }
            // Drop PRIVMSGs with a literally empty body (a stripped CTCP wrapper, `PRIVMSG
            // #chan :`), which would render as a bare "<nick> " line. Text that only looks
            // blank because of a wrong encoding is kept, so the problem stays visible.
            if (ev.text.isEmpty()) return
            if (!fromMe && !ev.isPrivate && isIgnoredSender(netId, ev.from, IgnoreType.CHANNEL)) return
            val st = _state.value
            val suppressUnread = ev.isHistory && !st.settings.ircHistoryCountsAsUnread
            val allowNotify = if (ev.isHistory) st.settings.ircHistoryTriggersNotifications else true
            val targetKey = resolveIncomingBufferKey(netId, ev.target)
            val newPrivate = ev.isPrivate && !fromMe && targetKey !in st.buffers && !ev.isHistory &&
                (ev.timeMs == null || ev.timeMs >= System.currentTimeMillis() - BufferLog.REPLAY_SUSPICION_MS)

            if (ownAnswer != null) {
                attachEchoMsgId(targetKey, ev.text, ev.isAction, ev.msgId, my)
                return
            }

            val prepareBeforeScript = !hasPrivateRequestPolicy() || !ev.isPrivate || fromMe
            if (prepareBeforeScript) prepareIncomingMessageBuffer(targetKey, ev.from, fromMe)
            // Script TEXT hook: lets scripts react to incoming lines (e.g. translate.hex echoes
            // a translation underneath) and optionally transform or suppress them. Runs only for
            // live messages, since history replays on reconnect must not re-fire it. No-op until the
            // script engine is started and only if a TEXT handler is registered.
            val dupByMsgId = !ev.msgId.isNullOrBlank() &&
                (_state.value.buffers[targetKey]?.log?.seenIds?.contains(ev.msgId) == true)
            val shownText: String = if (!ev.isHistory && !dupByMsgId) {
                val r = runCatching {
                    scriptEngine.onText(
                        com.boxlabs.hexdroid.script.TextEvent(
                            network = netId, buffer = ev.target, from = ev.from, text = ev.text,
                            isAction = ev.isAction, isPrivate = ev.isPrivate, isMine = fromMe,
                        ),
                    )
                }.getOrNull()
                if (r?.halted == true) return
                r?.text ?: ev.text
            } else ev.text
            if (gatePrivateMessage(netId, ev, shownText, targetKey)) return
            if (newPrivate && !dupByMsgId && isReplyNick(ev.from)) {
                showNativePrivateAttempt(netId, ev.from, "* <${ev.from}> Inició una conversación privada. El mensaje se recibió en su privado.")
            }
            if (!prepareBeforeScript) prepareIncomingMessageBuffer(targetKey, ev.from, fromMe)
            val highlight = if (fromMe) false else isHighlight(netId, shownText, ev.isPrivate, targetKey)
            if (!ev.isPrivate && isChannelOnNet(netId, splitKey(targetKey).second)) {
                noteChannelSpeaker(netId, targetKey, ev.from, ev.timeMs)
            }
            append(
                targetKey,
                from = ev.from,
                // +draft/channel-context: a PM sent from a channel's context carries the
                // channel name; annotate the line so the recipient sees what it relates
                // to (hexdroid keeps PMs in their own buffer rather than rerouting them).
                text = if (ev.isPrivate && ev.channelContext != null) "[${ev.channelContext}] $shownText" else shownText,
                isAction = ev.isAction,
                isHighlight = highlight,
                isPrivate = ev.isPrivate,
                isLocal = fromMe || suppressUnread,
                timeMs = ev.timeMs,
                doNotify = allowNotify,
                msgId = ev.msgId,
                replyToMsgId = ev.replyToMsgId,
                isHistory = ev.isHistory,
                isChathistoryContext = ev.isChathistoryContext,
                encryption = ev.encryption,
                fromOper = ev.fromOper,
                fromBot = ev.fromBot,
                multiline = ev.multiline,
            )
        }
        is IrcEvent.Notice -> {
            // Drop empty-bodied notices, as for PRIVMSG above; encoding-mangled but non-empty
            // text is kept.
            if (ev.text.isEmpty()) return
            val st = _state.value
            val suppressUnread = ev.isHistory && !st.settings.ircHistoryCountsAsUnread
            if (!ev.isServer && isIgnoredSender(netId, ev.from, IgnoreType.NOTICES)) return
            if (!ev.isHistory && ev.from.equals("AuthServ", ignoreCase = true)) authServNetworks += netId
            if (!ev.isHistory) {
                scriptEvent(
                    "NOTICE", netId, if (ev.isPrivate) "" else ev.target, ev.from, ev.text,
                    isMyNick(netId, ev.from), isPrivate = ev.isPrivate,
                    fields = mapOf("isserver" to ev.isServer.toString()),
                )
                if (ev.isServer) scriptEvent("SNOTICE", netId, "", ev.from, ev.text)
            }
            val normTarget0 = normalizeIncomingBufferName(netId, ev.target)
            val normTarget = stripStatusMsgPrefix(netId, normTarget0)
            val isChanTarget = isChannelOnNet(netId, normTarget)
            val targetIsServerBuffer = normTarget == "*server*"
            // A stored notice being played back (a bouncer's buffer, chathistory), as opposed
            // to one sent just now. It keeps its own time, and the join heuristics skip it.
            val replayedNotice = ev.isHistory ||
                (ev.timeMs != null && ev.timeMs < System.currentTimeMillis() - BufferLog.REPLAY_SUSPICION_MS)

            // Notice routing:
            //   1. Server notices (hostname prefix) go to *server*.
            //   2. A notice to a channel we have a buffer for goes there.
            //   3. A notice naming a channel we have a buffer for goes there (service-bot
            //     welcomes).
            //   4. Otherwise, a live one arriving within ~5 s of joining a single channel goes
            //     to that channel.
            //   5. Everything else goes to the selected buffer on this network, or *server*.
            // Rules 3 and 4 never create a buffer.
            fun firstMentionedKnownChannelKey(): String? {
                val chantypes = runtimes[netId]?.support?.chantypes ?: "#&"
                val text = ev.text
                var i = 0
                while (i < text.length) {
                    val c = text[i]
                    if (c in chantypes) {
                        // Walk forward over channel-name characters. RFC 2812 forbids
                        // space, control chars, comma, BEL, NUL inside channel names;
                        // we stop at any non-name char.
                        var j = i + 1
                        while (j < text.length) {
                            val ch = text[j]
                            if (ch == ' ' || ch == ',' || ch == '\u0007' || ch == '\u0000' ||
                                ch == '\r' || ch == '\n' || ch == ':') break
                            j++
                        }
                        // Trim trailing punctuation that's grammatically part of the
                        // sentence, not the channel name: ".", ",", "!", "?", ")", "]",
                        // ":", ";", and quotes. Don't strip "-" or "_" (legitimate in
                        // channel names like #foo-bar / #foo_bar).
                        var end = j
                        while (end > i + 1) {
                            val tail = text[end - 1]
                            if (tail in ".,!?)]:;\"'") end-- else break
                        }
                        if (end > i + 1) {
                            val candidate = text.substring(i, end)
                            val key = bufKey(netId, candidate)
                            val foldCandidate = casefoldText(netId, candidate)
                            val match = st.buffers.keys.firstOrNull { k ->
                                val (nid, bn) = splitKey(k)
                                nid == netId && casefoldText(netId, bn) == foldCandidate
                            }
                            if (match != null) return match
                            // Also accept an exact bufKey match (covers freshly-ensured
                            // buffers not yet in the casefold sweep).
                            if (st.buffers.containsKey(key)) return key
                        }
                        i = j
                    } else {
                        i++
                    }
                }
                return null
            }

            fun recentlyJoinedChannelKey(): String? {
                val now = System.currentTimeMillis()
                val cutoff = now - 5_000L
                // Find channels we joined in the last 5 s on this network. Multiple
                // matches: bail (ambiguous) — better to fall through to the selected
                // buffer than guess wrong.
                val matches = recentJoinAtMs.entries
                    .filter { (key, ts) ->
                        ts >= cutoff && key.startsWith("$netId::")
                    }
                    .map { it.key }
                return if (matches.size == 1) matches.single() else null
            }

            // The buffer holding the message this notice replies to. Services answer a
            // command with a notice carrying that command's msgid, which places the
            // answer beside the question with no guessing.
            fun replyParentBufferKey(): String? {
                val parent = ev.replyToMsgId ?: return null
                return st.buffers.entries.firstOrNull { (k, b) ->
                    k.startsWith("$netId::") && b.log.seenIds.contains(parent)
                }?.key
            }

            // An open conversation with the sender. Services reply to our nick rather
            // than into the conversation, so there is no target to route on.
            fun senderPrivateBufferKey(): String? {
                val sender = ev.from.takeIf { it.isNotBlank() } ?: return null
                val fold = casefoldText(netId, sender)
                return st.buffers.keys.firstOrNull { k ->
                    val (nid, bn) = splitKey(k)
                    nid == netId && !isPseudoBuffer(bn) && !isChannelOnNet(netId, bn) &&
                        casefoldText(netId, bn) == fold
                }
            }

            val originalKey = when {
                ev.isServer -> bufKey(netId, "*server*")
                targetIsServerBuffer -> bufKey(netId, "*server*")
                isChanTarget -> resolveBufferKey(netId, normTarget)
                else -> {
                    replyParentBufferKey()
                        ?: firstMentionedKnownChannelKey()
                        ?: senderPrivateBufferKey()
                        ?: (if (replayedNotice) null else recentlyJoinedChannelKey())
                        ?: run {
                            val sel = st.selectedBuffer
                            val (selNet, _) = splitKey(sel)
                            if (sel.isNotBlank() && selNet == netId) sel
                            else bufKey(netId, "*server*")
                        }
                }
            }

            val destKeys = replyDestinations(
                mask = st.settings.noticeDestinations,
                network = netId, window = NOTICES_BUFFER,
                selectedKey = st.selectedBuffer,
                privateNick = ev.from.takeUnless { ev.isServer },
                originalKey = originalKey, serverNotice = ev.isServer,
                resolve = { resolveBufferKey(netId, it) },
            )
            // Notices render as `* <nick> text`, except bouncer pseudo-users (nicks starting
            // with '*', or BouncerServ), which render as `<nick> text` to avoid `* <*status>`.
            val fromNick = ev.from
            val isBouncerPseudo = ev.isServer && (
                fromNick.startsWith("*") ||
                fromNick.equals("BouncerServ", ignoreCase = true)
            )
            val rendered = if (isBouncerPseudo) {
                "<$fromNick> ${ev.text}"
            } else {
                "* <${ev.from}> ${ev.text}"
            }
            // A live notice arriving just after we joined is that channel's entry notice. It
            // keeps its own time like any other notice, but doesn't mark the channel unread.
            if (isChanTarget && !ev.isServer) noteChannelSpeaker(netId, originalKey, ev.from, ev.timeMs)
            for (destKey in destKeys) {
                ensureBuffer(destKey)
                val entryNotice = !replayedNotice &&
                    recentJoinAtMs[destKey]?.let { System.currentTimeMillis() - it <= ENTRY_NOTICE_WINDOW_MS } == true
                append(
                    destKey,
                    from = null,
                    text = rendered,
                    isLocal = suppressUnread || entryNotice,
                    timeMs = ev.timeMs,
                    isHistory = ev.isHistory,
                    doNotify = false,
                    msgId = ev.msgId,
                    replyToMsgId = ev.replyToMsgId,
                    encryption = ev.encryption,
                )
            }
        }

        is IrcEvent.CtcpReply -> {
            if (!isIgnoredSender(netId, ev.from, IgnoreType.CTCP)) scriptEvent(
                "CTCPREPLY", netId, "", ev.from,
                text = listOf(ev.command, ev.args).filter { it.isNotEmpty() }.joinToString(" "),
            )
            // Display CTCP replies in the current buffer or server buffer
            val st = _state.value
            val sel = st.selectedBuffer
            val (selNet, _) = splitKey(sel)
            val destKey = if (sel.isNotBlank() && selNet == netId) sel else bufKey(netId, "*server*")
            ensureBuffer(destKey)

            val text = when (ev.command.uppercase()) {
                "PING" -> {
                    // Calculate round-trip time if args is a timestamp we sent
                    // Our timestamps are 13-digit millisecond values from System.currentTimeMillis()
                    val sent = ev.args.trim().toLongOrNull()
                    val now = System.currentTimeMillis()
                    if (sent != null && sent > 1000000000000L && sent < now + 60000) {
                        // Looks like a valid recent timestamp
                        val rtt = now - sent
                        "*** " + appContext.getString(R.string.vm_ev_ctcp_ping_ms, ev.from, rtt)
                    } else {
                        // Not our timestamp format, just show raw
                        "*** " + appContext.getString(R.string.vm_ev_ctcp_ping_raw, ev.from, ev.args)
                    }
                }
                else -> "*** " + appContext.getString(R.string.vm_ev_ctcp_reply, ev.command, ev.from, ev.args)
            }
            append(destKey, from = null, text = text, isLocal = true, timeMs = ev.timeMs, doNotify = false)
        }
        else -> Unit
    }
}

private fun IrcViewModel.prepareIncomingMessageBuffer(targetKey: String, sender: String, fromMe: Boolean) {
    ensureBuffer(targetKey)
    // Clear this nick's typing indicator when they send a message (implicit "done").
    if (!fromMe) {
        _state.update { st ->
            val buf = st.buffers[targetKey]
            if (buf != null && sender in buf.typingNicks) {
                st.copy(buffers = st.buffers + (targetKey to buf.copy(typingNicks = buf.typingNicks - sender)))
            } else st
        }
    }

}
