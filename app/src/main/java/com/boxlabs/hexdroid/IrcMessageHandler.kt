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

import kotlinx.coroutines.channels.ProducerScope
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Handles one non-numeric line (PRIVMSG, NOTICE, JOIN and the rest), emitting into [out].
 * Numeric replies go through the numeric handler table instead.
 */
internal suspend fun IrcClient.handleMessageCommand(
    out: ProducerScope<IrcEvent>,
    msg: IrcMessage,
    irc: IrcSession,
    serverTimeMs: Long?,
    playbackHistory: Boolean,
    nowMs: Long,
) {
    with(out) {
        when (msg.command.uppercase(Locale.ROOT)) {
            "NICK" -> {
                val old = msg.prefixNick()
                val newNick = (msg.trailing ?: msg.params.firstOrNull())
                if (old != null && newNick != null) {
                    send(IrcEvent.NickChanged(old, newNick, timeMs = serverTimeMs, isHistory = playbackHistory, historyChannel = playbackChannelFor(msg.tags)))
                }
                if (old != null && newNick != null && nickEquals(old, currentNick)) {
                    currentNick = newNick
                }
            }

            "PRIVMSG" -> {
                val from = msg.prefixNick() ?: "?"
                val rawTarget = msg.params.getOrNull(0) ?: return
                val target = normalizeMsgTarget(rawTarget)
                // The text is the last parameter, sent without a colon when it is one word.
                val textRaw = msg.trailing ?: msg.params.getOrNull(1) ?: ""

                // IRCv3 multiline: if this line is part of an open multiline batch
                // for our session, accumulate its body into the batch state instead
                // of emitting normally. The flush happens on BATCH -<id> close, which
                // emits a single ChatMessage with the joined text. Per spec, any
                // PRIVMSG line in the batch MUST target the same recipient as the
                // batch open; we trust the server here rather than re-validating.
                val multilineBatchIdPm = msg.tags["batch"]
                if (multilineBatchIdPm != null) {
                    val mlState = openMultilineBatches[multilineBatchIdPm]
                    if (mlState != null) {
                        // Lock in the inner-line command (PRIVMSG vs NOTICE) AND
                        // capture the inner-line sender on the first inner line.
                        // Per spec the BATCH open MAY have a prefix, but the
                        // authoritative sender is on the inner lines. Subsequent
                        // lines must match the same sender (mixing is forbidden);
                        // we don't try to handle that case.
                        if (mlState.parts.isEmpty()) {
                            openMultilineBatches[multilineBatchIdPm] =
                                mlState.copy(
                                    command = "PRIVMSG",
                                    innerSenderPrefix = msg.prefix,
                                )
                        }
                        openMultilineBatches[multilineBatchIdPm]?.let {
                            appendMultilinePart(it, textRaw, hasConcatTag(msg.tags))
                        }
                        return  // Don't emit individually - flushed on BATCH -<id>.
                    }
                }

                // znc.in/playback: *playback module sends TIMESTAMP <buffer> <epoch>
                // so we know when we were last seen and can request only missed messages.
                // Only consume TIMESTAMP messages here; other messages from *playback
                // (e.g. "Module not loaded") fall through to the generic pseudo-user
                // routing below and surface in the server buffer.
                if (config.isBouncer && from.equals("*playback", ignoreCase = true)) {
                    val parts = textRaw.trim().split(" ")
                    if (parts.size >= 2 && parts[0].equals("TIMESTAMP", ignoreCase = true)) {
                        val bufName = parts[1]
                        val epochSecs = parts.getOrNull(2)?.toLongOrNull()
                        if (epochSecs != null) zncLastSeen[casefold(bufName)] = epochSecs
                        return  // Internal plumbing only — never shown.
                    }
                    // Non-TIMESTAMP: fall through.
                }

                // Our own message coming back: match it to its send.
                val ownSend = if (nickEquals(from, currentNick) && !playbackHistory) {
                    confirmEcho(notice = false, rawTarget = rawTarget, text = textRaw, label = replyLabel(msg.tags))
                } else null

                // echo-message for messages we sent to a bouncer pseudo-user. `*playback`
                // echoes (PLAY commands) are dropped; any other pseudo-user (`*status`,
                // `*controlpanel`, BouncerServ) is shown in *server* as `<self> ...`, next
                // to its reply, instead of opening a query buffer.
                if (config.isBouncer
                    && !isChannelName(target)
                    && nickEquals(from, currentNick)
                    && isBouncerPseudoUser(target)
                ) {
                    // Shown here rather than as an echo, so the send is finished here too.
                    if (ownSend != null && !ownSend.suppress) {
                        send(IrcEvent.SendResolved(listOf(ownSend.id), failed = false))
                    }
                    if (target.equals("*playback", ignoreCase = true)) return
                    send(IrcEvent.Notice(
                        from = from,
                        target = "*server*",
                        text = "→ $target: $textRaw",
                        isPrivate = false,
                        isServer = true,
                        timeMs = serverTimeMs,
                        isHistory = (playbackHistory || isHeuristicHistory("*server*", serverTimeMs, nowMs))
                    ))
                    return
                }

                // Bouncer pseudo-users (ZNC modules, whose nicks start with '*', and soju's
                // BouncerServ) go to the server buffer rather than opening a query.
                if (config.isBouncer && !isChannelName(target) && isBouncerPseudoUser(from)) {
                    // ZNC discover-and-clone: opportunistically scrape any `*status` reply
                    // for ListNetworks rows.
                    if (config.bouncerKind == BouncerKind.ZNC && from.equals("*status", ignoreCase = true)) {
                        parseZncListNetworksLine(textRaw)?.let { send(it) }
                    }
                    // Show the message but route it to the server buffer.
                    send(IrcEvent.Notice(
                        from = from,
                        target = "*server*",
                        text = textRaw,
                        isPrivate = false,
                        isServer = true,
                        timeMs = serverTimeMs,
                        isHistory = (playbackHistory || isHeuristicHistory("*server*", serverTimeMs, nowMs))
                    ))
                    return
                }

                val isChannel = isChannelName(target)
                val isPrivate = !isChannel

                // Echo of a /msg we already printed into the origin buffer.
                if (isPrivate && ownSend?.suppress == true) return

                // If this PRIVMSG comes from a server prefix (no '!'), route it to *server*.
                // Some networks replay our OWN historical PMs (via CHATHISTORY) with a bare
                // nick prefix and no !user@host - identical in shape to a real server prefix.
                // Check nickEquals() first so that case routes to the PM buffer instead of
                // being misclassified as a server message (our own nick is never a server name).
                val isServerPrefix = (msg.prefix != null && !msg.prefix.contains('!') && !msg.prefix.contains('@')
                    && !nickEquals(from, currentNick))

                // For private messages, use the *other party* as the buffer name.
                val buf = if (isPrivate) {
                    when {
                        isServerPrefix -> "*server*"
                        nickEquals(from, currentNick) -> target
                        else -> from
                    }
                } else {
                    target
                }

                // Handle CTCP requests
                val trimmedText = textRaw.trim()
                if (trimmedText.startsWith("\u0001") && !nickEquals(from, currentNick)) {
                    // Strip leading \x01 and optional trailing \x01
                    val ctcpContent = trimmedText.removePrefix("\u0001").removeSuffix("\u0001").trim()
                    if (ctcpContent.isNotEmpty()) {
                        val spaceIdx = ctcpContent.indexOf(' ')
                        val ctcpCmd = (if (spaceIdx > 0) ctcpContent.substring(0, spaceIdx) else ctcpContent).uppercase()
                        val ctcpArgs = if (spaceIdx > 0) ctcpContent.substring(spaceIdx + 1) else ""
                        // A replayed request is never answered, and a replayed DCC offer is stale.
                        val replayed = playbackHistory || isHeuristicHistory(buf, serverTimeMs, nowMs)
                        if (ctcpCmd != "ACTION") {
                            send(IrcEvent.CtcpRequest(from, target, ctcpCmd, ctcpArgs, isHistory = replayed))
                            if (replayed) return
                        }

                        // Sanitise the sender nick used in outgoing NOTICE targets: strip
                        // CR/LF/NUL so a malicious server prefix cannot inject IRC commands.
                        val safeSender = from.replace(Regex("[\r\n\u0000]"), "")

                        // Rate-limit replies to at most one per CTCP_RATE_LIMIT_MS per nick
                        // so a flood of CTCP requests cannot get us K-lined.
                        // ACTION and DCC are never rate-limited (they don't generate replies).
                        val now = System.currentTimeMillis()
                        pruneCtcpReplyTimes(now)
                        val senderKey = safeSender.lowercase()
                        val lastReply = ctcpLastReplyMs[senderKey] ?: 0L
                        val answerable = ctcpCmd != "ACTION" && ctcpCmd != "DCC"
                        if (answerable && runCatching { ctcpIgnored?.invoke(from, userHostOf(from)) }.getOrNull() == true) return
                        val rateLimited = answerable && (now - lastReply) < CTCP_RATE_LIMIT_MS
                        if (rateLimited) {
                            send(IrcEvent.Status(tr(R.string.core_ctcp_ratelimited, ctcpCmd, safeSender)))
                            return
                        }
                        // The request is still reported, so the user sees who asked.
                        if (answerable && !config.ctcpRepliesEnabled) {
                            send(IrcEvent.Status(tr(R.string.core_ctcp_reply_disabled, ctcpCmd, safeSender)))
                            return
                        }

                        when (ctcpCmd) {
                            "VERSION" -> {
                                // Build the reply at call time so it always reflects the
                                // installed app version - never stale from a saved config.
                                ctcpLastReplyMs[senderKey] = now
                                val reply = "VERSION HexDroid v${BuildConfig.VERSION_NAME} - https://hexdroid.org"
                                sendCtcpReply(safeSender, reply)
                                send(IrcEvent.CtcpExchange(fromNick = safeSender, request = ctcpContent, reply = reply))
                                return
                            }
                            "PING" -> {
                                // Strip CR/LF/NUL from the echoed payload to prevent IRC
                                // command injection via a crafted CTCP PING argument.
                                val safeArgs = ctcpArgs.replace(Regex("[\r\n\u0000\u0001]"), "").take(200)
                                ctcpLastReplyMs[senderKey] = now
                                val reply = "PING $safeArgs"
                                sendCtcpReply(safeSender, reply)
                                send(IrcEvent.CtcpExchange(fromNick = safeSender, request = ctcpContent, reply = reply))
                                return
                            }
                            "TIME" -> {
                                val timeStr = java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", java.util.Locale.US).format(java.util.Date())
                                ctcpLastReplyMs[senderKey] = now
                                val reply = "TIME $timeStr"
                                sendCtcpReply(safeSender, reply)
                                send(IrcEvent.CtcpExchange(fromNick = safeSender, request = ctcpContent, reply = reply))
                                return
                            }
                            "FINGER", "USERINFO" -> {
                                val safeRealname = config.realname.take(100)
                                ctcpLastReplyMs[senderKey] = now
                                val reply = "$ctcpCmd $safeRealname"
                                sendCtcpReply(safeSender, reply)
                                send(IrcEvent.CtcpExchange(fromNick = safeSender, request = ctcpContent, reply = reply))
                                return
                            }
                            "CLIENTINFO" -> {
                                ctcpLastReplyMs[senderKey] = now
                                val reply = "CLIENTINFO ACTION PING VERSION TIME FINGER USERINFO CLIENTINFO SOURCE DCC"
                                sendCtcpReply(safeSender, reply)
                                send(IrcEvent.CtcpExchange(fromNick = safeSender, request = ctcpContent, reply = reply))
                                return
                            }
                            "SOURCE" -> {
                                ctcpLastReplyMs[senderKey] = now
                                val reply = "SOURCE https://hexdroid.org"
                                sendCtcpReply(safeSender, reply)
                                send(IrcEvent.CtcpExchange(fromNick = safeSender, request = ctcpContent, reply = reply))
                                return
                            }
                            "ACTION" -> {
                                // ACTION is handled below as a message
                            }
                            "DCC" -> {
                                // DCC is handled below
                            }
                            else -> {
                                // Unknown CTCP — log but don't reply (no reply = no flood risk).
                                // Shown with a null reply so the request is still visible without
                                // implying we answered it.
                                send(IrcEvent.CtcpExchange(fromNick = safeSender, request = ctcpContent, reply = null))
                                return
                            }
                        }
                    }
                }

                // CTCP DCC: consume offers so the raw CTCP line doesn't show in chat.
                val dccSend = parseDccSend(textRaw)
                if (dccSend != null) {
                    if (!nickEquals(from, currentNick)) {
                        send(IrcEvent.DccOfferEvent(dccSend.copy(from = from)))
                    }
                    return
                }

                val dccChat = parseDccChat(textRaw)
                if (dccChat != null) {
                    if (!nickEquals(from, currentNick)) {
                        send(IrcEvent.DccChatOfferEvent(dccChat.copy(from = from)))
                    }
                    return
                }

                // DCC RESUME (peer wants to resume one of our outgoing sends).
                val dccResume = parseDccResume(textRaw)
                if (dccResume != null) {
                    if (!nickEquals(from, currentNick)) {
                        send(IrcEvent.DccResumeRequest(from = from, resume = dccResume))
                    }
                    return
                }

                // DCC ACCEPT (the sender confirmed our RESUME request).
                val dccAccept = parseDccAccept(textRaw)
                if (dccAccept != null) {
                    if (!nickEquals(from, currentNick)) {
                        send(IrcEvent.DccAcceptResponse(from = from, accept = dccAccept))
                    }
                    return
                }

                val isAction = textRaw.startsWith("\u0001ACTION ") && textRaw.endsWith("\u0001")
                val rawText = if (isAction) {
                    textRaw.removePrefix("\u0001ACTION ").removeSuffix("\u0001")
                } else {
                    textRaw
                }

                // Decrypt after CTCP unwrapping, so an encrypted /me arrives here without
                // its ACTION framing. A failed decrypt keeps the wire text visible for
                // diagnosis.
                // Playback repeats ciphertexts already seen; append() deduplicates those.
                val replayedLine = playbackHistory || isHeuristicHistory(buf, serverTimeMs, nowMs) ||
                    (serverTimeMs != null && serverTimeMs < nowMs - BufferLog.REPLAY_SUSPICION_MS)
                val codecResult = e2eCodec?.decryptIncoming(buf, rawText, currentNick, replay = replayedLine)
                val text = codecResult?.text ?: rawText
                val encryption: com.boxlabs.hexdroid.crypto.E2eScheme? = codecResult?.let { r ->
                    when (r.outcome) {
                        com.boxlabs.hexdroid.crypto.E2eCodec.Outcome.PASSTHROUGH -> null
                        com.boxlabs.hexdroid.crypto.E2eCodec.Outcome.DECRYPTED -> r.scheme
                        com.boxlabs.hexdroid.crypto.E2eCodec.Outcome.FAILED -> null
                    }
                }

                // Suppress messages whose body is literally empty (e.g. an empty
                // PRIVMSG trailing, or a stray "\u0001ACTION \u0001" with no payload
                // whose ACTION unwrap leaves "" behind). Otherwise the UI renders a
                // bare "<nick> " line. We do NOT filter messages that merely look
                // blank because of encoding misdecoding - that text is real content
                // and the user needs to see it to know to fix their encoding setting.
                if (text.isEmpty()) return

                // Keep raw formatting codes. UI chooses to strip or render them.
                //  FOR DEBUGGING ONLY android.util.Log.w("AGMDUP", "EMIT core=${System.identityHashCode(this)} from=$from ts=$serverTimeMs text=${text.take(24)}")
                send(
                    IrcEvent.ChatMessage(
                        from = from,
                        target = buf,
                        text = text,
                        isPrivate = isPrivate,
                        isAction = isAction,
                        timeMs = serverTimeMs,
                        isHistory = (playbackHistory || isHeuristicHistory(buf, serverTimeMs, nowMs)),
                        isChathistoryContext = msg.tags.containsKey("draft/chathistory-context"),
                        msgId = msg.tags["msgid"],
                        // IRCv3 +draft/reply / +reply tag: msgid of message being replied to.
                        replyToMsgId = msg.tags["+draft/reply"] ?: msg.tags["+reply"],
                        // IRCv3 account-tag: services account of the sender.
                        senderAccount = msg.tags["account"]?.takeIf { it.isNotBlank() && it != "*" },
                        encryption = encryption,
                        // draft/oper-tag: server-attached tag marking the sender as an oper.
                        // Accept the draft name and the eventual ratified bare name.
                        fromOper = msg.tags.containsKey("draft/oper") || msg.tags.containsKey("oper"),
                        fromBot = msg.tags.containsKey("bot"),
                        // +draft/channel-context: for PMs, which channel the message relates to.
                        channelContext = (msg.tags["+draft/channel-context"] ?: msg.tags["+channel-context"])
                            ?.takeIf { isPrivate && isChannelName(it) },
                        ownSendId = ownSend?.id,
                    )
                )
            }

            "NOTICE" -> {
                val from = msg.prefixNick() ?: (msg.prefix ?: "?")
                val rawTarget = msg.params.getOrNull(0) ?: "*server*"
                val target = normalizeMsgTarget(rawTarget)
                val text = msg.trailing ?: msg.params.getOrNull(1) ?: ""

                // IRCv3 multiline: same buffering path as PRIVMSG above. Multiline
                // batches can carry NOTICE rather than PRIVMSG (per spec - lines
                // must be all the same kind), so the flush at BATCH -<id> picks
                // the right event type from mlState.command.
                val multilineBatchIdN = msg.tags["batch"]
                if (multilineBatchIdN != null) {
                    val mlState = openMultilineBatches[multilineBatchIdN]
                    if (mlState != null) {
                        if (mlState.parts.isEmpty()) {
                            openMultilineBatches[multilineBatchIdN] =
                                mlState.copy(
                                    command = "NOTICE",
                                    innerSenderPrefix = msg.prefix,
                                )
                        }
                        openMultilineBatches[multilineBatchIdN]?.let {
                            appendMultilinePart(it, text, hasConcatTag(msg.tags))
                        }
                        return
                    }
                }

                // Echo of a notice we sent and already displayed
                val ownNotice = if (nickEquals(from, currentNick) && !playbackHistory) {
                    confirmEcho(notice = true, rawTarget = rawTarget, text = text, label = replyLabel(msg.tags))
                } else null
                if (!isChannelName(target) && ownNotice?.suppress == true) return

                // Check for CTCP reply (wrapped in \x01)
                // Only process if it's from someone else, not our own echoed reply
                if (text.startsWith("\u0001") && text.endsWith("\u0001") && !nickEquals(from, currentNick)) {
                    val ctcpContent = text.trim('\u0001')
                    val spaceIdx = ctcpContent.indexOf(' ')
                    val ctcpCmd = if (spaceIdx > 0) ctcpContent.substring(0, spaceIdx) else ctcpContent
                    val ctcpArgs = if (spaceIdx > 0) ctcpContent.substring(spaceIdx + 1) else ""
                    send(
                        IrcEvent.CtcpReply(
                            from = from,
                            command = ctcpCmd,
                            args = ctcpArgs,
                            timeMs = serverTimeMs
                        )
                    )
                    return
                }

                val isChannel = isChannelName(target)
                val isServerPrefix = (msg.prefix != null && !msg.prefix.contains('!') && !msg.prefix.contains('@')
                    && !nickEquals(from, currentNick))

                // Bouncer pseudo-user NOTICEs (ZNC replies to /msg *status and /znc by
                // NOTICE) render in the server log rather than a query buffer; see
                // [isBouncerPseudoUser].
                if (config.isBouncer && !isChannel && isBouncerPseudoUser(from)) {
                    // ZNC discover-and-clone: also scrape NOTICE bodies. *status itself
                    // almost always sends PRIVMSG (the primary hook is in that handler),
                    // but a handful of modules and configurations route their replies
                    // through NOTICE — keep the duplicate hook so neither delivery path
                    // silently misses upstream rows.
                    if (config.bouncerKind == BouncerKind.ZNC && from.equals("*status", ignoreCase = true)) {
                        parseZncListNetworksLine(text)?.let { send(it) }
                    }
                    send(IrcEvent.Notice(
                        from = from,
                        target = "*server*",
                        text = text,
                        isPrivate = false,
                        isServer = true,
                        timeMs = serverTimeMs,
                        isHistory = (playbackHistory || isHeuristicHistory("*server*", serverTimeMs, nowMs)),
                        msgId = msg.tags["msgid"],
                        replyToMsgId = msg.tags["+draft/reply"] ?: msg.tags["+reply"]
                    ))
                    return
                }

                // Keep the IRC target intact and let the UI decide routing.
                // Use a stable buffer name for history heuristics.
                val histBuf = if (isChannel) target else "*server*"
                // E2E decrypt hook for NOTICE. Same pattern as PRIVMSG above: the
                // decrypted text replaces the wire text so downstream routing
                // (notice-to-channel rules, etc.) sees the plaintext content.
                // For non-channel NOTICE targets the key lookup uses the SENDER's
                // nick (since per-target keys for queries are keyed by remote
                // nick), so a NickServ NOTICE is never accidentally decrypted
                // against a #channel's key.
                val rawNoticeText = text
                val noticeLookupTarget = if (isChannel) target else from
                val noticeReplayed = playbackHistory || isHeuristicHistory(histBuf, serverTimeMs, nowMs) ||
                    (serverTimeMs != null && serverTimeMs < nowMs - BufferLog.REPLAY_SUSPICION_MS)
                val noticeCodecResult = e2eCodec?.decryptIncoming(noticeLookupTarget, rawNoticeText, currentNick, replay = noticeReplayed)
                val noticeText = noticeCodecResult?.text ?: rawNoticeText
                val noticeEncryption: com.boxlabs.hexdroid.crypto.E2eScheme? = noticeCodecResult?.let { r ->
                    when (r.outcome) {
                        com.boxlabs.hexdroid.crypto.E2eCodec.Outcome.PASSTHROUGH -> null
                        com.boxlabs.hexdroid.crypto.E2eCodec.Outcome.DECRYPTED -> r.scheme
                        com.boxlabs.hexdroid.crypto.E2eCodec.Outcome.FAILED -> null
                    }
                }
                // Drop notices with an empty body, matching the PRIVMSG check above.
                if (noticeText.isEmpty()) return
                send(
                    IrcEvent.Notice(
                        from = from,
                        target = target,
                        text = noticeText,
                        isPrivate = !isChannel && !isServerPrefix,
                        isServer = isServerPrefix,
                        timeMs = serverTimeMs,
                        isHistory = (playbackHistory || isHeuristicHistory(histBuf, serverTimeMs, nowMs)),
                        msgId = msg.tags["msgid"],
                        replyToMsgId = msg.tags["+draft/reply"] ?: msg.tags["+reply"],
                        encryption = noticeEncryption,
                        fromOper = msg.tags.containsKey("draft/oper") || msg.tags.containsKey("oper"),
                        fromBot = msg.tags.containsKey("bot"),
                    )
                )
            }

            "JOIN" -> {
                val nick = msg.prefixNick() ?: return
                // JOIN can be "JOIN :#chan" or, with extended-join, "JOIN #chan account :realname".
                // Prefer the first param when it looks like a channel; otherwise fall back to trailing.
                val chanRaw = msg.params.firstOrNull()?.takeIf { isChannelName(it) }
                    ?: msg.trailing?.takeIf { isChannelName(it) }
                    ?: return

                // Suppress JOIN events that are part of a netjoin batch - emit one collapsed line instead.
                val batchId = msg.tags["batch"]
                if (batchId != null && openNetsplitBatches[batchId] == "netjoin") {
                    netsplitBuffer.getOrPut(batchId) { mutableListOf() }.add(msg)
                    return
                }

                // JOIN may include a comma-separated list (JOIN #a,#b). Emit one event per channel.
                val chans = chanRaw
                    .split(',')
                    .map { it.trim() }
                    .filter { isChannelName(it) }
                    .ifEmpty { listOf(chanRaw) }

                val userHost = msg.prefix?.substringAfter('!', missingDelimiterValue = "")
                    ?.takeIf { it.isNotBlank() }

                // IRCv3 extended-join: params[1] = services account ("*" = not logged in),
                // trailing = realname (gecos). Standard JOIN has no params[1].
                val extAccount = if (irc.hasCap("extended-join")) {
                    msg.params.getOrNull(1)?.takeIf { it.isNotBlank() && it != "*" }
                } else null
                val extRealname = if (irc.hasCap("extended-join")) msg.trailing else null
                if (irc.hasCap("extended-join")) noteUser(nick, account = extAccount ?: "*")

                for (chan in chans) {
                    val chanHist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
                    if (nickEquals(nick, currentNick) && !chanHist && serverTimeMs != null) {
                        selfJoinServerMs[casefold(chan)] = serverTimeMs
                    }
                    val joinKey = if (nickEquals(nick, currentNick) && !chanHist) joinKeys.remove(casefold(chan)) else null
                    send(
                        IrcEvent.Joined(
                            channel = chan,
                            nick = nick,
                            userHost = userHost,
                            timeMs = serverTimeMs,
                            isHistory = chanHist,
                            account = extAccount,
                            realname = extRealname,
                            key = joinKey,
                        )
                    )
                    if (nickEquals(nick, currentNick) && !chanHist) {
                        val fold = casefold(chan)
                        joinedChannelCases[fold] = chan
                    }

                    // soju.im/no-implicit-names and draft/no-implicit-names
                    // the server won't send an automatic 353/366 NAMES list on JOIN when this cap is negotiated
                    if (nickEquals(nick, currentNick)
                        && !chanHist
                        && (hasCap("soju.im/no-implicit-names") || hasCap("draft/no-implicit-names"))
                        && namesRequested.add(casefold(chan))
                    ) {
                        sendBackground("NAMES $chan")
                    }

                    // IRCv3 chathistory: request recent messages when we (re)join.
                    // Supports both the graduated "chathistory" and legacy "draft/chathistory" cap.
                    //
                    // Skip when chanHist is true: a JOIN arriving as part of buffer playback
                    // represents our PRIOR session, not the current one. Firing CHATHISTORY
                    // LATEST against it re-requests history we're already in the middle of
                    // receiving — same rationale as the znc.in/playback guard below.
                    if (nickEquals(nick, currentNick)
                        && config.capPrefs.draftChathistory
                        && hasChathistoryCap()
                        && !chanHist
                        && historyRequested.add(casefold(chan))
                    ) {
                        val lim = clampHistoryLimit(config.historyLimit.coerceIn(0, 500))
                        if (lim > 0) {
                            send(IrcEvent.JoinHistoryWanted(chan, lim))
                            // Long enough to cover the wait for the buffer's saved log.
                            historyExpectUntil[casefold(chan)] = nowMs + 20_000L
                        }
                    }

                    // znc.in/playback: request only messages we missed since last seen.
                    // Sends: PRIVMSG *playback :PLAY <buffer> <lastSeen> <now>
                    //
                    // Skip when chanHist is true: a JOIN arriving as part of the bouncer's
                    // own buffer playback represents our PRIOR session, not the current one.
                    // Firing PLAY against it re-requests history we're already in the middle
                    // of receiving and produces duplicate lines.
                    if (nickEquals(nick, currentNick)
                        && config.isBouncer
                        && irc.hasCap("znc.in/playback")
                        && !chanHist
                    ) {
                        val lastSeen = zncLastSeen[casefold(chan)] ?: 0L
                        val nowSecs = nowMs / 1000L
                        sendBackground("PRIVMSG *playback :PLAY $chan $lastSeen $nowSecs")
                        historyExpectUntil[casefold(chan)] = nowMs + 15_000L
                    }

                    // The member WHO that fills in hosts, accounts and away state is sent by the
                    // ViewModel once the member list is in, so it can skip large channels.
                }
            }

            "PART" -> {
                val nick = msg.prefixNick() ?: return

                // Most servers send:  PART <channel>[,<channel>...] [:reason]
                // But some bouncers/bridges send malformed variants
                // where the channel list lands in the trailing field (with no params).
                // Accept both so we still update nicklists.

                val trailing0 = msg.trailing?.trim()
                val chanRaw = when {
                    msg.params.isNotEmpty() -> msg.params[0]
                    // Only treat trailing as channel list if it's a single token and looks like a channel.
                    trailing0 != null && !trailing0.contains(' ') && (trailing0.startsWith('#') || trailing0.startsWith('&')) -> trailing0
                    else -> return
                }

                // PART may include a comma-separated list (PART #a,#b :reason). Emit one event per channel.
                val chans = chanRaw
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .ifEmpty { listOf(chanRaw) }

                val userHost = msg.prefix?.substringAfter('!', missingDelimiterValue = "")
                    ?.takeIf { it.isNotBlank() }
                // Reason can be the IRC trailing parameter, or a second param without ':'
                // e.g. "PART #chan goodbye".
                val reason = when {
                    msg.params.size >= 2 && msg.trailing == null -> msg.params[1]
                    // If we had to read the channel from trailing (malformed form), don't reuse it as reason.
                    msg.params.isEmpty() -> null
                    else -> msg.trailing
                }

                for (chan in chans) {
                    val chanHist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
                    send(
                        IrcEvent.Parted(
                            channel = chan,
                            nick = nick,
                            userHost = userHost,
                            reason = reason,
                            timeMs = serverTimeMs,
                            isHistory = chanHist
                        )
                    )
                    if (nickEquals(nick, currentNick) && !chanHist) {
                        joinedChannelCases.remove(casefold(chan))
                        // A rejoin fetches what was missed while away.
                        historyRequested.remove(casefold(chan))
                    }
                }
            }

            "KICK" -> {
                val kicker = msg.prefixNick() ?: return
                val kickerHost = msg.prefix?.substringAfter('!', missingDelimiterValue = "")
                    ?.takeIf { it.isNotBlank() }
                val chan = msg.params.getOrNull(0) ?: return
                // KICK is "<channel> <user> [<comment>]". The comment is normally the
                // trailing parameter, but a server may send the user itself as
                // the trailing parameter when there's no comment (KICK #chan :user).
                val victim = msg.params.getOrNull(1) ?: msg.trailing ?: return
                val reason = msg.params.getOrNull(2) ?: if (msg.params.size >= 2) msg.trailing else null
                val chanHist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
                send(
                    IrcEvent.Kicked(
                        channel = chan,
                        victim = victim,
                        byNick = kicker,
                        byHost = kickerHost,
                        reason = reason,
                        timeMs = serverTimeMs,
                        isHistory = chanHist
                    )
                )
                if (nickEquals(victim, currentNick) && !chanHist) {
                    joinedChannelCases.remove(casefold(chan))
                    historyRequested.remove(casefold(chan))
                }
            }

            "QUIT" -> {
                val nick = msg.prefixNick() ?: return
                // Suppress QUIT events that are part of a netsplit batch - emit one collapsed line instead.
                val batchId = msg.tags["batch"]
                if (batchId != null && openNetsplitBatches[batchId] == "netsplit") {
                    netsplitBuffer.getOrPut(batchId) { mutableListOf() }.add(msg)
                    return
                }
                val userHost = msg.prefix?.substringAfter('!', missingDelimiterValue = "")
                    ?.takeIf { it.isNotBlank() }
                val reason = msg.trailing ?: msg.params.getOrNull(0)
                send(IrcEvent.Quit(nick = nick, userHost = userHost, reason = reason, timeMs = serverTimeMs, isHistory = playbackHistory, historyChannel = playbackChannelFor(msg.tags)))
            }

            "WALLOPS", "GLOBOPS", "LOCOPS", "OPERWALL", "SNOTICE" -> {
                val sender = msg.prefixNick() ?: (msg.prefix ?: "server")
                val txt = (msg.trailing ?: msg.params.drop(0).joinToString(" ")).let { stripIrcFormatting(it) }
                if (txt.isNotBlank()) {
                    send(IrcEvent.ServerText(tr(R.string.core_ctcp_generic_from, msg.command.uppercase(Locale.ROOT), sender, txt), code = msg.command.uppercase(Locale.ROOT)))
                }
            }

            "TOPIC" -> {
                val chan = msg.params.firstOrNull() ?: return
                val topic = msg.trailing ?: msg.params.getOrNull(1)
                val setter = msg.prefixNick()
                send(IrcEvent.Topic(chan, topic, setter = setter, timeMs = serverTimeMs, isHistory = (playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs))))
            }

            "MODE" -> {
                val rawTarget = msg.params.getOrNull(0) ?: return
                val target = normalizeMsgTarget(rawTarget)
                if (!isChannelName(target)) {
                    // User MODE change (target is a nick, not a channel).
                    // Detect +o/+O on our own nick - covers auto-oper via services,
                    // not just explicit /OPER (which triggers 381 RPL_YOUREOPER).
                    if (nickEquals(target, currentNick)) {
                        // Strict RFC2812 IRCd's send the final MODE parameter
                        // as a trailing parameter, e.g.  :nick!u@h MODE #chan -v :target
                        // the target nick then lives in msg.trailing, not msg.params. Fold trailing
                        // back in as the last positional parameter so mode arguments stay aligned.
                        val modeStr = msg.params.getOrNull(1) ?: msg.trailing ?: ""
                        send(IrcEvent.OwnUserModes(modeStr))
                        var adding = true
                        for (ch in modeStr) {
                            when (ch) {
                                '+' -> adding = true
                                '-' -> adding = false
                                'o', 'O' -> {
                                    if (adding) {
                                        send(IrcEvent.YoureOper(tr(R.string.core_youre_oper)))
                                    } else {
                                        send(IrcEvent.YoureDeOpered)
                                    }
                                }
                            }
                        }
                    }
                    return
                }

                val modeParams = msg.params + listOfNotNull(msg.trailing)
                val modeStr = modeParams.getOrNull(1) ?: return
                val args = modeParams.drop(2)

                val isHistoryMode = playbackHistory || isHeuristicHistory(target, serverTimeMs, nowMs)

                // Update nick prefixes for rank modes (op/voice/etc).
                parseChannelUserModes(target, modeStr, args).forEach { (nick, prefix, adding) ->
                    send(IrcEvent.ChannelUserMode(target, nick, prefix, adding, timeMs = serverTimeMs, isHistory = isHistoryMode, byNick = msg.prefixNick()))
                }
                send(IrcEvent.ChannelModeChanges(
                    target, msg.prefixNick(), (listOf(modeStr) + args).joinToString(" "),
                    parseModeChanges(modeStr, args), isHistory = isHistoryMode,
                ))

                // Surface the simple-mode delta so the ViewModel can keep the channel's
                // stored mode string current (drives the Channel Tools toggles).
                if (!isHistoryMode) {
                    send(IrcEvent.ChannelModeChanged(target, modeStr))
                }

                // Also surface the mode change as a readable line in the channel buffer.
                val setter = msg.prefixNick() ?: (msg.prefix ?: "server")
                val extra = if (args.isEmpty()) "" else " " + args.joinToString(" ")
                send(IrcEvent.ChannelModeLine(target, "*** " + tr(R.string.core_sets_mode, setter, modeStr, extra), timeMs = serverTimeMs, isHistory = isHistoryMode))
            }

            // IRCv3 CHGHOST: ident or hostname changed (requires chghost CAP)
            // CHGHOST is "<user> <host>"; the host is the last param, so accept it from
            // the trailing parameter too (CHGHOST user :host) instead of dropping the event.
            "CHGHOST" -> {
                val nick = msg.prefixNick() ?: return
                val newUser = msg.params.getOrNull(0) ?: return
                val newHost = msg.params.getOrNull(1) ?: msg.trailing ?: return
                noteUser(nick, newUser, newHost)
                send(IrcEvent.Chghost(nick, newUser, newHost, timeMs = serverTimeMs, isHistory = playbackHistory))
            }

            // IRCv3 ACCOUNT: services account changed (requires account-notify CAP)
            // account name is params[0]; "*" means logged out
            // account-notify carries the account as a single param; accept it from the
            // trailing parameter too (ACCOUNT :name) so it isn't misread as a logout ("*").
            "ACCOUNT" -> {
                val nick = msg.prefixNick() ?: return
                val account = msg.params.getOrNull(0) ?: msg.trailing ?: "*"
                send(IrcEvent.AccountChanged(nick, account, timeMs = serverTimeMs, isHistory = playbackHistory))
            }

            // IRCv3 SETNAME: realname changed (requires setname CAP)
            "SETNAME" -> {
                val nick = msg.prefixNick() ?: return
                val newRealname = msg.trailing ?: msg.params.getOrNull(0) ?: return
                send(IrcEvent.Setname(nick, newRealname, timeMs = serverTimeMs, isHistory = playbackHistory))
            }

            // INVITE: received an invite to a channel
            "INVITE" -> {
                // :inviter INVITE targetNick #channel
                val from = msg.prefixNick() ?: return
                val targetNick = msg.params.getOrNull(0) ?: return
                val channel = msg.trailing ?: msg.params.getOrNull(1) ?: return
                if (nickEquals(targetNick, currentNick)) {
                    // This invite is for us.
                    send(IrcEvent.InviteReceived(from, channel, timeMs = serverTimeMs))
                } else if (irc.hasCap("invite-notify")) {
                    // IRCv3 invite-notify: server broadcasts invites to others in shared channels.
                    // Surface as a status line in the channel buffer (and server buffer as fallback).
                    val bufTarget = if (isChannelName(channel)) channel else "*server*"
                    send(IrcEvent.ServerText(
                        "*** " + tr(R.string.core_invited_to, from, targetNick, channel),
                        code = "INVITE",
                        bufferName = bufTarget
                    ))
                }
            }

            // ERROR: fatal server message, always followed by connection close
            "ERROR" -> {
                val message = msg.trailing ?: msg.params.joinToString(" ")
                send(IrcEvent.ServerError(message))
                // Emit Disconnected immediately so the reconnect loop doesn't wait for EOF.
                sendDisconnectedOnce(
                    tr(R.string.core_disconnect_server_error, message),
                    DisconnectCode.SERVER_ERROR,
                )
            }

            // AWAY: another user's away status changed (requires away-notify CAP).
            // No trailing = returned from away; trailing = new away message.
            "AWAY" -> {
                val nick = msg.prefixNick() ?: return
                if (nickEquals(nick, currentNick)) return  // skip our own reflected echo
                send(IrcEvent.AwayChanged(nick, msg.trailing ?: msg.params.getOrNull(0), timeMs = serverTimeMs))
            }

            // draft/relaymsg: relay bot forwarded a message on behalf of another user.
            // Format: ":relaybot!u@h RELAYMSG #channel relayednick :message"
            // params[0] = channel/target, params[1] = relayed nick, trailing = message text.
            // Surface as a regular chat message attributed to the relayed nick so the UI
            // renders it identically to a direct PRIVMSG from that nick.
            "RELAYMSG" -> {
                if (!config.capPrefs.draftRelaymsg) return
                val target    = msg.params.getOrNull(0) ?: return
                val relayNick = msg.params.getOrNull(1) ?: return
                val text      = msg.trailing ?: msg.params.getOrNull(2) ?: return
                val isAction  = text.startsWith("\u0001ACTION ") && text.endsWith("\u0001")
                val body      = if (isAction) text.removePrefix("\u0001ACTION ").removeSuffix("\u0001") else text
                send(IrcEvent.ChatMessage(
                    from          = relayNick,
                    target        = target,
                    text          = body,
                    isPrivate     = !isChannelName(target),
                    isAction      = isAction,
                    timeMs        = serverTimeMs,
                    isHistory     = false,
                    msgId         = msg.tags["msgid"],
                    replyToMsgId  = msg.tags["+draft/reply"] ?: msg.tags["+reply"],
                    senderAccount = null   // relay bots don't expose the relayed user's account
                ))
            }

            // TAGMSG: message-tags-only (no body text).
            // Used for typing indicators (draft/typing), reactions, and other tag-only events.
            "TAGMSG" -> {
                val fromNick = msg.prefixNick() ?: return
                val rawTarget = msg.params.getOrNull(0) ?: return
                val rawConvo = normalizeMsgTarget(rawTarget)
                // As with PRIVMSG, a PM names whoever received it, so an incoming one is
                // addressed to us. Report the conversation instead, or a reaction or a
                // typing notice on a PM lands in our own nick's buffer.
                val target = when {
                    isChannelName(rawConvo) -> rawConvo
                    nickEquals(fromNick, currentNick) -> rawConvo
                    else -> fromNick
                }
                // draft/typing: +typing tag indicates composing status.
                // Values: "active" (typing), "paused" (stopped briefly), "done" (cleared/sent).
                // typing is a client-only tag
                // Libera permits it via CLIENTTAGDENY=*,-typing using message-tags.
                val typingState = msg.tags["+typing"] ?: msg.tags["typing"]
                if (typingState != null &&
                    (irc.hasCap("draft/typing") || irc.hasCap("typing") || irc.hasCap("message-tags"))) {
                    send(IrcEvent.TypingStatus(
                        target = target,
                        nick = fromNick,
                        state = typingState,
                        timeMs = serverTimeMs
                    ))
                }
                // Read receipt: only meaningful in a private message to us.
                msg.tags["+$READ_RECEIPT_TAG"]?.takeIf { it.isNotBlank() }?.let { readId ->
                    if (!isChannelName(rawConvo) && !nickEquals(fromNick, currentNick)) {
                        send(IrcEvent.ReadReceipt(nick = fromNick, msgId = readId))
                    }
                }
                // draft/message-reactions: +draft/react tag carries the emoji.
                // Format: TAGMSG <target> with tags +draft/react=<emoji> +draft/reply=<msgid-of-original>
                // Removal uses +draft/unreact (react client-tag spec, Feb 2026) or the
                // older +draft/react-removed (message-reactions); accept both.
                val reactEmoji = msg.tags["+draft/react"]
                val reactRemoved = msg.tags["+draft/unreact"] ?: msg.tags["+draft/react-removed"]
                if ((reactEmoji != null || reactRemoved != null) &&
                    (irc.hasCap("draft/message-reactions") || irc.hasCap("message-tags"))) {
                    val emoji = reactEmoji ?: reactRemoved!!
                    val adding = reactEmoji != null
                    // The target message's msgid is in "+draft/reply" (or "+reply" for the
                    // graduated tag name), NOT in "msgid" which is the TAGMSG's own ID.
                    val replyMsgId = msg.tags["+draft/reply"] ?: msg.tags["+reply"]
                    send(IrcEvent.MessageReaction(
                        fromNick = fromNick,
                        target = target,
                        reaction = emoji,
                        msgId = replyMsgId,
                        adding = adding,
                        timeMs = serverTimeMs,
                        isHistory = isReplayedLine(msg.tags),
                    ))
                }
            }

            // IRCv3 MARKREAD (draft/read-marker) and READ (soju.im/read):
            // server confirms updated read pointer for a buffer.
            // Format: MARKREAD <target> [timestamp=<ISO8601>]
            //         READ <target> timestamp=<ISO8601>   (soju.im/read)
            // draft/metadata-2 server notification:
            //   METADATA <Target> <Key> <Visibility> <Value>
            "METADATA" -> {
                val ap = msg.allParams
                val mdTarget = ap.getOrNull(0) ?: return
                val mdKey = ap.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return
                send(IrcEvent.MetadataChanged(
                    target = mdTarget,
                    key = mdKey.lowercase(Locale.ROOT),
                    visibility = ap.getOrNull(2),
                    value = ap.getOrNull(3),
                ))
            }

            // draft/account-registration server responses:
            //   REGISTER SUCCESS <account> <message>
            //   REGISTER VERIFICATION_REQUIRED <account> <message>
            //   VERIFY SUCCESS <account> <message>
            // Failures arrive as FAIL REGISTER/VERIFY <code> via standard-replies.
            "REGISTER", "VERIFY" -> {
                val stage = msg.params.getOrNull(0) ?: return
                val account = msg.params.getOrNull(1)?.takeIf { it.isNotBlank() && it != "*" }
                val message = msg.allParams.getOrNull(2)?.takeIf { it.isNotBlank() }
                val line = when (stage.uppercase(Locale.ROOT)) {
                    "SUCCESS" -> {
                        pendingVerifyAccount = null
                        val who = account ?: currentNick
                        val done = if (msg.command == "VERIFY") tr(R.string.core_account_verified, who)
                                   else tr(R.string.core_account_registered, who)
                        "*** " + done + (message?.let { " ($it)" } ?: "")
                    }
                    "VERIFICATION_REQUIRED" -> {
                        pendingVerifyAccount = account
                        "*** " + tr(R.string.core_verification_required, account ?: currentNick, message ?: tr(R.string.core_check_your_email))
                    }
                    else -> "*** ${msg.command} $stage ${account ?: ""} ${message ?: ""}".trimEnd()
                }
                send(IrcEvent.ServerText(line, code = msg.command))
                send(IrcEvent.AccountRegUpdate(
                    command = msg.command,
                    stage = stage.uppercase(Locale.ROOT),
                    account = account,
                    message = message,
                ))
            }

            // IRCv3 draft/message-redaction: a message was deleted.
            // Format: :nick!u@h REDACT <target> <msgid> [:reason]
            "REDACT" -> {
                val ap = msg.allParams
                val target = ap.getOrNull(0) ?: return
                val redactId = ap.getOrNull(1) ?: return
                val reason = ap.getOrNull(2)?.takeIf { it.isNotBlank() }
                send(IrcEvent.MessageRedacted(
                    fromNick = msg.prefixNick() ?: (msg.prefix ?: "?"),
                    target = target,
                    msgId = redactId,
                    reason = reason,
                    timeMs = serverTimeMs,
                    isHistory = isReplayedLine(msg.tags),
                ))
            }

            // Server confirmation of a subscription change.
            // Format: WEBPUSH REGISTER <endpoint> | WEBPUSH UNREGISTER <endpoint>
            "WEBPUSH" -> {
                val ap = msg.allParams
                val sub = ap.getOrNull(0)?.uppercase(Locale.ROOT) ?: return
                val wpEndpoint = ap.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return
                when (sub) {
                    "REGISTER" -> send(IrcEvent.WebPushRegistered(wpEndpoint))
                    "UNREGISTER" -> send(IrcEvent.WebPushUnregistered(wpEndpoint))
                }
            }

            // CHATHISTORY TARGETS reply: one line per buffer the server stores
            // history for, delivered inside a draft/chathistory-targets batch.
            // Format: CHATHISTORY TARGETS <target> <timestamp>
            "CHATHISTORY" -> {
                val ap = msg.allParams
                if (!ap.getOrNull(0).equals("TARGETS", ignoreCase = true)) return
                val histTarget = ap.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return
                val histTs = ap.getOrNull(2)
                    ?.removePrefix("timestamp=")
                    ?.takeIf { it.isNotBlank() } ?: return
                send(IrcEvent.HistoryTarget(target = histTarget, timestamp = histTs))
            }

            "MARKREAD", "READ" -> {
                val target = msg.params.getOrNull(0) ?: return
                val tsParam = (msg.params.drop(1) + listOfNotNull(msg.trailing))
                    .firstOrNull { it.startsWith("timestamp=") }
                    ?.removePrefix("timestamp=")
                if (tsParam != null) {
                    send(IrcEvent.ReadMarker(target = target, timestamp = tsParam))
                }
            }

            // soju/pounce BOUNCER sub-protocol: upstream network info
            "BOUNCER" -> {
                val subCmd = msg.params.getOrNull(0)?.uppercase(Locale.ROOT) ?: return
                when (subCmd) {
                    "NETWORK" -> {
                        // BOUNCER NETWORK <id> <attrs> | BOUNCER NETWORK <id> *
                        // Per the soju.im/bouncer-networks spec, <attrs> is a single field of
                        // semicolon-separated key=value pairs (message-tag escaping rules apply
                        // to each value) NOT space-separated tokens.
                        val networkId = msg.params.getOrNull(1) ?: return
                        val attrTokens = (msg.params.drop(2) + listOfNotNull(msg.trailing))
                            .flatMap { it.split(';') }
                        send(parseBouncerNetworkAttrs(networkId, attrTokens))
                    }
                    // BOUNCER ADDNETWORK / DELNETWORK / CHANGENETWORK <netid>: the command worked.
                    "ADDNETWORK", "DELNETWORK", "CHANGENETWORK" -> {
                        val networkId = msg.allParams.getOrNull(1)?.takeIf { it.isNotBlank() }
                        val text = "BOUNCER $subCmd${networkId?.let { " $it" } ?: ""}"
                        send(IrcEvent.ServerText(text, code = "BOUNCER"))
                        send(IrcEvent.BouncerCommandResult(subCmd, networkId, failed = false))
                    }
                    "ERROR" -> {
                        val detail = msg.params.drop(1).joinToString(" ") + (msg.trailing?.let { " :$it" } ?: "")
                        send(IrcEvent.Error(tr(R.string.core_bouncer_error, detail)))
                    }
                }
            }

            // draft/channel-rename: server renamed a channel we're in.
            // Format: RENAME <old> <new> [:<reason>]
            // The client must update its buffer key and membership records.
            "RENAME" -> {
                if (!config.capPrefs.channelRename) return
                val oldName = msg.params.getOrNull(0) ?: return
                val newName = msg.params.getOrNull(1) ?: return
                send(IrcEvent.ChannelRenamed(oldName = oldName, newName = newName, timeMs = serverTimeMs))
                // Also emit a status line so the rename appears in the buffer history.
                val reason = msg.trailing ?: msg.params.getOrNull(2)
                val text = if (reason.isNullOrBlank()) tr(R.string.core_channel_renamed, oldName, newName)
                          else tr(R.string.core_channel_renamed_reason, oldName, newName, reason)
                send(IrcEvent.ServerText(text, code = "RENAME"))
            }

            // standard-replies: FAIL/WARN/NOTE <command> <code> [<context>...]
            // :<description>.
            "FAIL", "WARN", "NOTE" -> {
                val srCmd = msg.params.getOrNull(0) ?: "?"
                val srCode = msg.params.getOrNull(1) ?: "?"
                // Context tokens are params[2..n-1] when trailing carries the description;
                // when there is no trailing, the last param IS the description (no context).
                val contextTokens = if (msg.trailing != null) msg.params.drop(2) else emptyList()
                val srDesc = msg.trailing ?: msg.params.lastOrNull() ?: "?"
                if (msg.command == "FAIL" && handleSojuFail(out, msg, srCmd, srCode, contextTokens, srDesc)) return
                // soju.im/webpush echoes the push endpoint back as a context token.
                if (srCmd.equals("WEBPUSH", true)) {
                    if (msg.command == "FAIL") {
                        send(IrcEvent.WebPushFailed(
                            subcommand = contextTokens.firstOrNull() ?: "?",
                            code = srCode,
                            message = srDesc,
                        ))
                    } else {
                        send(IrcEvent.ServerText(
                            "${msg.command} WEBPUSH $srCode: $srDesc", code = msg.command))
                    }
                    return
                }
                val srContextStr = if (contextTokens.isNotEmpty()) " [${contextTokens.joinToString(" ")}]" else ""
                val srText = "${msg.command} $srCmd $srCode$srContextStr: $srDesc"
                // draft/account-registration failures: append an actionable hint so the
                // user knows what to do next instead of just seeing the raw code.
                val srHint = if (msg.command == "FAIL" && (srCmd == "REGISTER" || srCmd == "VERIFY")) {
                    when (srCode) {
                        "ACCOUNT_EXISTS" -> " " + tr(R.string.core_reg_hint_account_exists)
                        "WEAK_PASSWORD", "UNACCEPTABLE_PASSWORD" -> " " + tr(R.string.core_reg_hint_weak_password)
                        "INVALID_EMAIL", "UNACCEPTABLE_EMAIL" -> " " + tr(R.string.core_reg_hint_invalid_email)
                        "INVALID_CODE" -> " " + tr(R.string.core_reg_hint_invalid_code)
                        "COMPLETE_CONNECTION_REQUIRED" -> " " + tr(R.string.core_reg_hint_not_connected)
                        "TEMPORARILY_UNAVAILABLE" -> " " + tr(R.string.core_reg_hint_temp_unavailable)
                        else -> ""
                    }
                } else ""
                if (msg.command == "FAIL") send(IrcEvent.Error(srText + srHint))
                else send(IrcEvent.ServerText(srText, code = msg.command))
                // A rejected multiline BATCH never landed. The FAIL carries no batch id, so
                // it answers the batch with its label, or else the oldest one outstanding.
                if (msg.command == "FAIL" && srCmd.equals("BATCH", true) &&
                    srCode.uppercase(Locale.ROOT).startsWith("MULTILINE_")
                ) {
                    val failed = takeAnsweredSend(msg.tags) { it.multiline }
                    send(IrcEvent.MultilineSendFailed(
                        failed?.target ?: lastMultilineTarget,
                        srCode,
                        srDesc,
                        sendIds = listOfNotNull(failed?.id),
                    ))
                }
                // A rejected CHATHISTORY gets no reply batch, so close the request here
                // rather than waiting for its watchdog. The target is the second context
                // item, after the subcommand, for every code that names one; INVALID_PARAMS
                // carries none.
                if (msg.command == "FAIL" && srCmd.equals("CHATHISTORY", true)) {
                    val code = srCode.uppercase(Locale.ROOT)
                    // FAIL <command> <code> <the_given_command> [<the_given_target>] :<desc>
                    val target = if (code == "INVALID_PARAMS") null else msg.params.getOrNull(3)
                    // The server does not take the reference type we used, so record it and
                    // let the next request fall through to the other one.
                    if (code == "INVALID_MSGREFTYPE") {
                        lastHistoryRefType?.let { bad -> refusedRefTypes = refusedRefTypes + bad }
                    }
                    send(IrcEvent.HistoryRequestFailed(target, code, srDesc, label = msg.tags["label"]))
                }
                // Feed account-registration failures to the guided dialog too, so it
                // can show the error inline instead of only in the server buffer.
                if (msg.command == "FAIL" && (srCmd == "REGISTER" || srCmd == "VERIFY")) {
                    send(IrcEvent.AccountRegUpdate(
                        command = srCmd,
                        stage = "FAIL:$srCode",
                        account = null,
                        message = (srDesc + srHint).trim(),
                    ))
                }
            }

        }
    }
}
