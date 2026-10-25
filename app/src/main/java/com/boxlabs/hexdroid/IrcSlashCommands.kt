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

import com.boxlabs.hexdroid.IrcClient.BanMaskType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Instant
import java.util.Locale

internal suspend fun IrcClient.runSlashCommand(cmdLine: String, currentBuffer: String) {
    val parts = cmdLine.trim().split(Regex("\\s+"))
    if (parts.isEmpty()) return
    val cmd = parts[0].lowercase()

    when (cmd) {
        "join" -> parts.getOrNull(1)?.let { chan ->
            val key = parts.getOrNull(2)
            sendRaw(if (key.isNullOrBlank()) "JOIN $chan" else "JOIN $chan $key")
        }
        "part" -> {
            val arg1 = parts.getOrNull(1)
            val hasChan = arg1 != null && isChannelName(arg1)
            val chan = when {
                hasChan -> arg1
                currentBuffer != "*server*" -> currentBuffer
                else -> arg1 ?: return
            }
            val reason = (if (hasChan) parts.drop(2) else parts.drop(1)).joinToString(" ").trim()
            sendRaw(if (reason.isBlank()) "PART $chan" else "PART $chan :$reason")
        }
        "cycle" -> {
            val arg1 = parts.getOrNull(1)
            val hasChan = arg1 != null && isChannelName(arg1)
            val chan = when {
                hasChan -> arg1
                currentBuffer != "*server*" -> currentBuffer
                else -> arg1 ?: return
            }
            val key = if (hasChan) parts.getOrNull(2) else parts.getOrNull(1)
            sendRaw("PART $chan :Rejoining")
            // Small delay so servers process PART before JOIN
            delay(300)
            sendRaw(if (key.isNullOrBlank()) "JOIN $chan" else "JOIN $chan $key")
        }
        "msg" -> {
            val target = parts.getOrNull(1)
            val msg = parts.drop(2).joinToString(" ")
            if (target.isNullOrBlank() || msg.isBlank()) {
                commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer,
                    text = tr(R.string.core_usage_msg), isPrivate = true))
                return
            }
            val origin = currentBuffer
            // Printed where the user typed, so the server's echo-message copy is dropped
            // rather than opening a query buffer.
            privmsg(target, msg, suppressEcho = true)
            commandEvents.send(IrcEvent.OutgoingEcho(originBuffer = origin, target = target, text = msg))
        }
        // /query <nick> [message] - open a PM buffer with a user (buffer switching handled in ViewModel)
        // /query with no message just opens the buffer; with a message it sends it too.
        "query" -> {
            val target = parts.getOrNull(1)
            if (target.isNullOrBlank()) {
                commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer,
                    text = tr(R.string.core_usage_query), isPrivate = true))
                return
            }
            val msg = parts.drop(2).joinToString(" ").trim()
            if (msg.isNotBlank()) privmsg(target, msg)
            // Signal the ViewModel to open/focus the query buffer via a fake incoming event.
            commandEvents.trySend(IrcEvent.OpenQueryBuffer(target))
        }
        // Services shorthands: /ns, /cs, /as, /hs, /ms, /bs, /x3
        "ns" -> {
            val rest = parts.drop(1).joinToString(" ").trim()
            if (rest.isNotBlank()) privmsg("NickServ", rest)
        }
        "cs" -> {
            val rest = parts.drop(1).joinToString(" ").trim()
            if (rest.isNotBlank()) privmsg("ChanServ", rest)
        }
        "as" -> {
            val rest = parts.drop(1).joinToString(" ").trim()
            if (rest.isNotBlank()) privmsg("AuthServ", rest)
        }
        "x3" -> {
            val rest = parts.drop(1).joinToString(" ").trim()
            if (rest.isNotBlank()) privmsg("X3", rest)
        }
        "hs" -> {
            val rest = parts.drop(1).joinToString(" ").trim()
            if (rest.isNotBlank()) privmsg("HostServ", rest)
        }
        "ms" -> {
            val rest = parts.drop(1).joinToString(" ").trim()
            if (rest.isNotBlank()) privmsg("MemoServ", rest)
        }
        "bs" -> {
            val rest = parts.drop(1).joinToString(" ").trim()
            if (rest.isNotBlank()) privmsg("BotServ", rest)
        }
        // ZNC bouncer control: /znc <command> → PRIVMSG *status :<command>
        // ZNC's own clients have used this shorthand for years; supporting it keeps muscle
        // memory from HexChat / Quassel etc. working. Modules can be addressed with their
        // own pseudo-user (/msg *clientbuffer ..., /msg *playback ...) the long way.
        "znc" -> {
            val rest = parts.drop(1).joinToString(" ").trim()
            if (rest.isNotBlank()) privmsg("*status", rest)
        }
        // soju bouncer control: /bouncerserv <command> → PRIVMSG BouncerServ :<command>
        // BouncerServ is soju's equivalent of *status. Note BouncerServ is a normal nick
        // (not a *-prefixed pseudo-user) so it routes through the regular PRIVMSG path;
        // the routing-to-*server* logic in the PRIVMSG handler still folds replies into
        // the server buffer for cleanliness.
        "bouncerserv", "bnc" -> {
            val rest = parts.drop(1).joinToString(" ").trim()
            if (rest.isNotBlank()) privmsg("BouncerServ", rest)
        }
        "me" -> {
            val msg = parts.drop(1).joinToString(" ")
            val target = if (currentBuffer == "*server*") return else currentBuffer
            sendRaw("PRIVMSG $target :\u0001ACTION $msg\u0001")
        }
        "amsg" -> {
            val msg = parts.drop(1).joinToString(" ").trim()
            if (msg.isBlank()) {
                // Give feedback in current buffer
                // Option A: raw status
                // send(IrcEvent.Status("Usage: /amsg <message>"))

                // Option B: send a fake notice to current buffer
                commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer, text = tr(R.string.core_no_text_to_send), isPrivate = true))
                return
            }
            for (chan in joinedChannelCases.values) {
                privmsg(chan, msg)
                // Echo locally only if the server won't reflect it back via echo-message.
                if (!hasCap("echo-message")) {
                    commandEvents.send(
                        IrcEvent.ChatMessage(
                            from = currentNick,
                            target = chan,
                            text = msg,
                            isPrivate = false,
                            timeMs = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
        "ame" -> {
            val msg = parts.drop(1).joinToString(" ").trim()
            if (msg.isBlank()) {
                commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer, text = tr(R.string.core_no_text_to_send), isPrivate = true))
                return
            }
            for (chan in joinedChannelCases.values) {
                ctcp(chan, "ACTION $msg")
                // Echo locally only if the server won't reflect it back via echo-message.
                if (!hasCap("echo-message")) {
                    commandEvents.send(
                        IrcEvent.ChatMessage(
                            from = currentNick,
                            target = chan,
                            text = msg,
                            isPrivate = false,
                            isAction = true,
                            timeMs = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
        "list" -> {
            // Forward any arguments so ELIST filters work directly, e.g. "/list >50" to
            // list channels with more than 50 users on servers that advertise ELIST=...U.
            val args = parts.drop(1).joinToString(" ").trim()
            sendRaw(if (args.isBlank()) "LIST" else "LIST $args")
        }
        "motd" -> {
            val arg = parts.drop(1).joinToString(" ")
            sendRaw(if (arg.isBlank()) "MOTD" else "MOTD $arg")
        }
        "knock" -> {
            // KNOCK <channel> [reason]: ask for an invite to an invite-only channel.
            val chan = parts.getOrNull(1)
            if (chan.isNullOrBlank()) {
                commandEvents.trySend(IrcEvent.ServerText(tr(R.string.core_usage_knock)))
            } else if (!knockSupported) {
                commandEvents.trySend(IrcEvent.ServerText(tr(R.string.core_no_knock)))
            } else {
                val reason = parts.drop(2).joinToString(" ").trim()
                sendRaw(if (reason.isBlank()) "KNOCK $chan" else "KNOCK $chan :$reason")
            }
        }
        "whois" -> {
            val arg = parts.drop(1).joinToString(" ").trim()
            // WHOIS [<server>] <nick>[,<nick>...]: the nicks are the last argument.
            val nicks = parts.drop(1).lastOrNull { it.isNotBlank() }?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            if (arg.isBlank() || nicks.isNullOrEmpty()) return
            for (n in nicks) rememberWhoisBuffer(casefold(n), currentBuffer)
            sendRaw("WHOIS $arg")
        }
        "who" -> {
            val arg = parts.drop(1).joinToString(" ")
            val who = whoFor(parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "*", currentBuffer)
            sendLine(if (arg.isBlank()) "WHO" else "WHO $arg", who = who)
        }
        "nick" -> parts.getOrNull(1)?.let { sendRaw("NICK $it") }
        "topic" -> {
            // /topic                         query the current channel's topic
            // /topic <new topic>             set the current channel's topic
            // /topic <#channel>              query that channel's topic
            // /topic <#channel> <new topic>  set that channel's topic
            val firstArg = parts.getOrNull(1)
            val target = when {
                firstArg != null && isChannelName(firstArg) -> firstArg
                currentBuffer != "*server*" -> currentBuffer
                firstArg != null -> firstArg  // last-resort: send as-is, server will 403
                else -> return
            }
            // Re-derive whether we consumed parts[1] as the channel: if the chosen
            // target equals firstArg AND firstArg looked like a channel, drop the
            // first arg from the topic text. Otherwise the whole tail is the topic.
            val firstWasChannel = firstArg != null && isChannelName(firstArg) && target == firstArg
            val newTopic = (if (firstWasChannel) parts.drop(2) else parts.drop(1))
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
            sendRaw(if (newTopic == null) "TOPIC $target" else "TOPIC $target :${clampLen(newTopic, "TOPICLEN")}")
        }
        "mode" -> {
            val arg = parts.drop(1).joinToString(" ")
            if (arg.isNotBlank()) sendRaw("MODE $arg")
        }
        "kick" -> {
            // /kick <nick> [reason]      — kick from current channel
            // /kick <#chan> <nick> [reason] — kick from a different channel
            val parsed = parseChanTargetCommand(parts, cmd, "<nick>", needsTarget = true, currentBuffer = currentBuffer) ?: return
            val reason = parsed.tail.joinToString(" ").trim()
            sendRaw(if (reason.isBlank()) "KICK ${parsed.chan} ${parsed.target}"
                    else "KICK ${parsed.chan} ${parsed.target} :${clampLen(reason, "KICKLEN")}")
        }
        "ban" -> {
            // /ban <nick-or-mask> [type]      — ban in current channel
            // /ban <#chan> <nick-or-mask> [type] — ban in a different channel
            // [type] is one of: nick (default), user, host, domain, account
            // If <nick-or-mask> contains !, @, or starts with $, it's treated as a raw mask.
            val parsed = parseChanTargetCommand(parts, cmd, "<nick|mask> [type]", needsTarget = true, currentBuffer = currentBuffer) ?: return
            val type = parseMaskType(parsed.tail.firstOrNull()) ?: BanMaskType.HOST
            applyBanOrQueue(parsed.chan, parsed.target!!, type, quiet = false, alsoKick = false, kickReason = "")
        }
        "unban" -> {
            // /unban <nick-or-mask>      — remove ban in current channel
            // /unban <#chan> <nick-or-mask> — remove ban in a different channel
            // Plain nicks are expanded to nick!*@*; raw masks pass through. To remove a
            // host/account ban, paste the exact mask shown by /banlist.
            val parsed = parseChanTargetCommand(parts, cmd, "<nick|mask>", needsTarget = true, currentBuffer = currentBuffer) ?: return
            val mask = if (looksLikeRawMask(parsed.target!!)) parsed.target else "${parsed.target}!*@*"
            sendRaw("MODE ${parsed.chan} -b $mask")
        }
        "kb", "kickban" -> {
            // /kb <nick-or-mask> [type] [reason...]
            // /kb <#chan> <nick-or-mask> [type] [reason...]
            // type, if present, is the same keyword as /ban. When the target is a raw
            // mask, the kick step is skipped (no single nick to kick).
            val parsed = parseChanTargetCommand(parts, cmd, "<nick|mask> [type] [reason...]", needsTarget = true, currentBuffer = currentBuffer) ?: return
            // Distinguish "/kb nick host with extra reason words" from "/kb nick reason words"
            // by checking whether the next word is a recognised type keyword. If it is,
            // it's the type; otherwise it's the start of the reason.
            val maybeType = parseMaskType(parsed.tail.firstOrNull())
            val (type, reasonStartIdx) = if (maybeType != null) maybeType to 1 else BanMaskType.HOST to 0
            val reason = parsed.tail.drop(reasonStartIdx).joinToString(" ").trim()
            applyBanOrQueue(parsed.chan, parsed.target!!, type, quiet = false, alsoKick = true, kickReason = reason)
        }
        "mute", "quiet" -> {
            // /mute <nick-or-mask> [type]      — set +q (quiet) in current channel
            // /mute <#chan> <nick-or-mask> [type] — same in a different channel
            // Same syntax as /ban. Falls back to +b on ircds without quiet support.
            val parsed = parseChanTargetCommand(parts, cmd, "<nick|mask> [type]", needsTarget = true, currentBuffer = currentBuffer) ?: return
            val type = parseMaskType(parsed.tail.firstOrNull()) ?: BanMaskType.HOST
            applyBanOrQueue(parsed.chan, parsed.target!!, type, quiet = true, alsoKick = false, kickReason = "")
        }
        "unmute", "unquiet" -> {
            val parsed = parseChanTargetCommand(parts, cmd, "<nick|mask>", needsTarget = true, currentBuffer = currentBuffer) ?: return
            val mask = if (looksLikeRawMask(parsed.target!!)) parsed.target else "${parsed.target}!*@*"
            val modeChar = if (supportsQuietMode()) 'q' else 'b'
            sendRaw("MODE ${parsed.chan} -$modeChar $mask")
        }
        "sajoin" -> {
            // Services/admin forced join: SAJOIN <nick> <#channel>
            val a1 = parts.getOrNull(1) ?: return
            val a2 = parts.getOrNull(2) ?: return
            val (nick, chan) = if (isChannelName(a1) && !isChannelName(a2)) (a2 to a1) else (a1 to a2)
            sendRaw("SAJOIN $nick $chan")
        }
        "sapart" -> {
            // Services/admin forced part: SAPART <nick> <#channel> [:reason]
            val a1 = parts.getOrNull(1) ?: return
            val a2 = parts.getOrNull(2) ?: return
            val (nick, chan) = if (isChannelName(a1) && !isChannelName(a2)) (a2 to a1) else (a1 to a2)
            val reason = parts.drop(3).joinToString(" ").trim()
            sendRaw(if (reason.isBlank()) "SAPART $nick $chan" else "SAPART $nick $chan :$reason")
        }
        "gline", "zline", "kline", "dline", "eline", "qline", "shun" -> {
            // Server ban/line commands take <mask> <duration> [reason]; prefix ':' on the
            // reason so spaces are preserved. The first two tokens (mask, duration) stay
            // bare so the server parses the duration correctly.
            // Examples:
            //   /gline *!*@bad.host 1d no spam pls
            //   /zline 203.0.113.0/24 2h scanning
            val raw = cmd.uppercase(Locale.ROOT)
            if (parts.size >= 4) {
                val head = parts.subList(1, 3).joinToString(" ")
                val reason = parts.drop(3).joinToString(" ").trim()
                sendRaw(if (reason.isBlank()) "$raw $head" else "$raw $head :$reason")
            } else {
                val rest = parts.drop(1).joinToString(" ").trim()
                sendRaw(if (rest.isBlank()) raw else "$raw $rest")
            }
        }
        "kill" -> {
            // KILL takes <nick> <comment>
            val nick = parts.getOrNull(1)
            if (nick.isNullOrBlank()) {
                commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer,
                    text = tr(R.string.core_usage_kill), isPrivate = true))
                return
            }
            val reason = parts.drop(2).joinToString(" ").trim()
            sendRaw(if (reason.isBlank()) "KILL $nick" else "KILL $nick :$reason")
        }
        "wallops", "globops", "locops", "operwall" -> {
            val msg = parts.drop(1).joinToString(" ").trim()
            if (msg.isNotBlank()) sendRaw("${cmd.uppercase(Locale.ROOT)} :$msg")
        }

        "ctcp" -> {
            val target = parts.getOrNull(1) ?: return
            // Only the CTCP command is case-insensitive; its arguments are sent as typed.
            val rawPayload = parts.drop(2).joinToString(" ").trim()
            if (rawPayload.isBlank()) return
            val payload = rawPayload.substringBefore(' ').uppercase() +
                rawPayload.substringAfter(' ', "").let { if (it.isEmpty()) "" else " $it" }

            // For PING, add timestamp if not provided
            val actualPayload = if (payload == "PING") {
                "PING ${System.currentTimeMillis()}"
            } else {
                payload
            }
            ctcp(target, actualPayload)
            commandEvents.send(IrcEvent.Status(tr(R.string.core_ctcp_sent, payload, target)))
        }
        "finger" -> {
            val target = parts.getOrNull(1) ?: return
            ctcp(target, "FINGER")
            commandEvents.send(IrcEvent.Status(tr(R.string.core_ctcp_finger_sent, target)))
        }
        "userinfo" -> {
            val target = parts.getOrNull(1) ?: return
            ctcp(target, "USERINFO")
            commandEvents.send(IrcEvent.Status(tr(R.string.core_ctcp_userinfo_sent, target)))
        }
        "clientinfo" -> {
            val target = parts.getOrNull(1) ?: return
            ctcp(target, "CLIENTINFO")
            commandEvents.send(IrcEvent.Status(tr(R.string.core_ctcp_clientinfo_sent, target)))
        }
        "away" -> {
            // Always sets away; /back is how to return.
            val msg = parts.drop(1).joinToString(" ").trim()
                .ifBlank { tr(R.string.core_away_default) }
            sendRaw("AWAY :${clampLen(msg, "AWAYLEN")}")
            // Reported so it can be restored next connection.
            commandEvents.trySend(IrcEvent.SelfAwayChanged(msg))
        }
        "back" -> {
            sendRaw("AWAY")
            commandEvents.trySend(IrcEvent.SelfAwayChanged(null))
        }
        "setname" -> {
            // IRCv3 SETNAME: change your own realname (requires setname CAP).
            val newRealname = parts.drop(1).joinToString(" ").trim()
            if (newRealname.isBlank()) {
                commandEvents.trySend(IrcEvent.ServerText(tr(R.string.core_usage_setname)))
            } else if (!hasCap("setname")) {
                commandEvents.trySend(IrcEvent.ServerText(tr(R.string.core_no_setname)))
            } else {
                sendRaw("SETNAME :$newRealname")
            }
        }
        "quit" -> {
            val reason = parts.drop(1).joinToString(" ").trim()
            sendRaw(if (reason.isBlank()) "QUIT" else "QUIT :${clampLen(reason, "QUITLEN")}")
            delay(500)
            // Give time for QUIT to send before disconnect
            disconnect(reason.ifBlank { "Quitting" })
        }
        "notice" -> {
            val target = parts.getOrNull(1)
            val msg = parts.drop(2).joinToString(" ")
            if (target.isNullOrBlank() || msg.isBlank()) {
                commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer,
                    text = tr(R.string.core_usage_notice), isPrivate = true))
                return
            }
            val origin = currentBuffer
            sendNotice(target, msg, suppressEcho = true)
            commandEvents.send(IrcEvent.OutgoingEcho(originBuffer = origin, target = target, text = msg))
        }
        "invite" -> {
            val nick = parts.getOrNull(1)
            if (nick.isNullOrBlank()) {
                commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer,
                    text = tr(R.string.core_usage_invite), isPrivate = true))
                return
            }
            val chan = parts.getOrNull(2)
                ?: if (currentBuffer != "*server*" && isChannelName(currentBuffer)) currentBuffer
                   else {
                    commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer,
                        text = tr(R.string.core_invite_needs_channel),
                        isPrivate = true))
                    return
                   }
            sendRaw("INVITE $nick $chan")
        }
        "op", "deop", "voice", "devoice" -> {
            val nick = parts.getOrNull(1)
            if (nick.isNullOrBlank()) {
                commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer,
                    text = tr(R.string.core_usage_mode_target, cmd), isPrivate = true))
                return
            }
            val chan = parts.getOrNull(2)
                ?: if (currentBuffer != "*server*" && isChannelName(currentBuffer)) currentBuffer
                   else {
                    commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer,
                        text = tr(R.string.core_needs_channel_second_arg, cmd),
                        isPrivate = true))
                    return
                   }
            val mode = when (cmd) {
                "op" -> "+o"; "deop" -> "-o"; "voice" -> "+v"; "devoice" -> "-v"
                else -> return  // unreachable; satisfies the when-expression exhaustiveness check
            }
            sendRaw("MODE $chan $mode $nick")
        }
        "ctcpping", "ping" -> {
            val target = parts.getOrNull(1) ?: return
            ctcp(target, "PING ${System.currentTimeMillis()}")
            commandEvents.send(IrcEvent.Status(tr(R.string.core_ctcp_ping_sent, target)))
        }
        "time" -> {
            val arg = parts.drop(1).joinToString(" ")
            sendRaw(if (arg.isBlank()) "TIME" else "TIME $arg")
        }
        "version" -> {
            val arg = parts.drop(1).joinToString(" ").trim()
            if (arg.isBlank()) {
                // No argument: query server version
                sendRaw("VERSION")
            } else {
                // Argument provided: send CTCP VERSION to target
                ctcp(arg, "VERSION")
            }
        }
        "admin" -> {
            val arg = parts.drop(1).joinToString(" ")
            sendRaw(if (arg.isBlank()) "ADMIN" else "ADMIN $arg")
        }
        "info" -> {
            val arg = parts.drop(1).joinToString(" ")
            sendRaw(if (arg.isBlank()) "INFO" else "INFO $arg")
        }
        "oper" -> {
            val args = parts.drop(1).joinToString(" ")
            if (args.isNotBlank()) sendRaw("OPER $args")
        }
        // Account registration and message redaction:
        //   /register <password> | <email> <password> | <account> <email> <password>
        //   /verify [account] <code>
        //   /redact [<target>] <msgid> [reason]
        // Omitted account and email slots are sent as "*".
        "redact" -> {
            suspend fun note(text: String) = commandEvents.send(IrcEvent.Notice(
                from = "*", target = currentBuffer, text = text, isPrivate = true,
            ))
            if (!hasCap("draft/message-redaction") && !hasCap("message-redaction")) {
                note(tr(R.string.core_no_redact))
                return
            }
            val a = parts.drop(1)
            if (a.isEmpty()) { note(tr(R.string.core_usage_redact)); return }
            // A leading argument counts as an explicit target only when it is a
            // CHANNEL name and something follows it. Guessing "nick vs msgid" for a
            // bare first word is unsafe: msgids are opaque and can look like anything,
            // so a heuristic would silently redact against the wrong target. For a PM,
            // run the command from that query buffer.
            val hasTarget = a.size >= 2 && isChannelName(a[0])
            val t = if (hasTarget) a[0] else currentBuffer
            val rest = if (hasTarget) a.drop(1) else a
            val msgId = rest.firstOrNull() ?: run { note(tr(R.string.core_usage_redact)); return }
            val reason = rest.drop(1).joinToString(" ").takeIf { it.isNotBlank() }
            if (t.isBlank() || t == "*server*") { note(tr(R.string.core_redact_wrong_buffer)); return }
            sendRedact(t, msgId, reason)
        }

        // draft/metadata-2: user and channel key-value metadata.
        //   /metadata                              - list your own metadata
        //   /metadata <key> <value>                - set one of your keys
        //   /metadata <key>                        - clear one of your keys
        //   /metadata <target> get|list|sync|clear - operate on a nick or channel
        //   /metadata sub|unsub <key...>           - manage key subscriptions
        "metadata" -> {
            suspend fun note(text: String) = commandEvents.send(IrcEvent.Notice(
                from = "*", target = currentBuffer, text = text, isPrivate = true,
            ))
            if (!hasCap("draft/metadata-2")) {
                note(tr(R.string.core_no_metadata))
                return
            }
            val a = parts.drop(1)
            val sub = a.getOrNull(0)?.lowercase(Locale.ROOT)
            when {
                a.isEmpty() -> sendRaw("METADATA * LIST")
                sub == "sub" || sub == "unsub" -> {
                    val keys = a.drop(1).joinToString(" ")
                    if (keys.isBlank()) note(tr(R.string.core_usage_metadata_sub, sub))
                    else sendRaw("METADATA * ${sub.uppercase(Locale.ROOT)} $keys")
                }
                sub == "subs" -> sendRaw("METADATA * SUBS")
                // Second word is a subcommand: the first word is the target.
                a.size >= 2 && a[1].lowercase(Locale.ROOT) in setOf("get", "list", "sync", "clear") -> {
                    val t = a[0]
                    val s2 = a[1].uppercase(Locale.ROOT)
                    val rest = a.drop(2).joinToString(" ")
                    sendRaw("METADATA $t $s2" + if (rest.isBlank()) "" else " $rest")
                }
                // Otherwise: set or clear one of our own keys.
                a.size == 1 -> sendRaw("METADATA * SET ${a[0]}")
                else -> sendRaw("METADATA * SET ${a[0]} :${a.drop(1).joinToString(" ")}")
            }
        }
        "register" -> {
            suspend fun note(text: String) = commandEvents.send(IrcEvent.Notice(
                from = "*", target = currentBuffer, text = text, isPrivate = true,
            ))
            if (!hasCap("draft/account-registration")) {
                note(tr(R.string.core_no_registration))
                return
            }
            val flags = (capValue("draft/account-registration") ?: "")
                .split(',').map { it.trim().lowercase(Locale.ROOT) }
            val emailRequired = flags.contains("email-required")
            val customName = flags.contains("custom-account-name")
            // Command syntax stays in English (it is what the user types); only the
            // "Usage:" label and the trailing note are translated.
            val syntax = buildString {
                append("/register ")
                if (customName) append("[account] ")
                append(if (emailRequired) "<email> " else "[email] ")
                append("<password>")
            }
            val usage = tr(R.string.core_usage_syntax, syntax) +
                if (emailRequired) tr(R.string.core_register_email_required) else ""
            val args = parts.drop(1)
            if (args.isEmpty()) { note(usage); return }
            val account: String
            val email: String
            val password: String
            when {
                args.size == 1 -> { account = "*"; email = "*"; password = args[0] }
                args.size == 2 -> { account = "*"; email = args[0]; password = args[1] }
                else -> { account = args[0]; email = args[1]; password = args.drop(2).joinToString(" ") }
            }
            if (emailRequired && email == "*") { note(usage); return }
            if (account != "*" && !customName) {
                note(tr(R.string.core_register_nick_is_account, usage))
                return
            }
            sendRaw("REGISTER $account $email :$password")
        }
        "verify" -> {
            suspend fun note(text: String) = commandEvents.send(IrcEvent.Notice(
                from = "*", target = currentBuffer, text = text, isPrivate = true,
            ))
            if (!hasCap("draft/account-registration")) {
                note(tr(R.string.core_no_verify))
                return
            }
            val args = parts.drop(1)
            if (args.isEmpty()) { note(tr(R.string.core_usage_verify)); return }
            val account: String
            val code: String
            if (args.size >= 2) { account = args[0]; code = args.drop(1).joinToString(" ") }
            else {
                // Prefer the account the server named in VERIFICATION_REQUIRED so a
                // bare /verify <code> just works; fall back to the current nick.
                account = pendingVerifyAccount ?: currentNick
                code = args[0]
            }
            sendRaw("VERIFY $account :$code")
        }
        "raw", "quote" -> {
            // /raw and /quote are aliases — both send the rest of the line verbatim to
            // the server. /quote is the more traditional IRC name (mIRC, irssi, weechat
            // all use it); /raw is the descriptive variant some clients prefer. Both
            // are supported so muscle memory from any other client works here.
            val line = parts.drop(1).joinToString(" ")
            if (line.isNotBlank()) {
                val words = line.trim().split(' ').filter { it.isNotEmpty() }
                val who = if (words.first().equals("WHO", ignoreCase = true)) {
                    whoFor(words.getOrNull(1) ?: "*", currentBuffer)
                } else null
                sendLine(line, who = who)
            }
        }
        // IRCv3 MONITOR: watch list management
        // /monitor + nick[,nick...]   - add to watch list  (or /monitor +nick)
        // /monitor - nick[,nick...]   - remove from watch list
        // /monitor C                  - clear watch list
        // /monitor L                  - list current watch list
        // /monitor S                  - request status of all watched nicks
        "monitor" -> {
            // Surface unsupported MONITOR up-front instead of letting the user discover
            // it via a raw "421 Unknown command" line. monitorLimit stays at -1 until we
            // see a MONITOR token in 005; if the server didn't advertise it after
            // registration completed (RPL_WELCOME received) we treat it as unsupported.
            // This is the common case on legacy IRCds (e.g. ircu without the
            // watch-monitor patch).
            if (registered && monitorLimit == -1) {
                commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer,
                    text = tr(R.string.core_no_monitor), isPrivate = false))
                return
            }
            val arg = parts.drop(1).joinToString(" ").trim()
            if (arg.isBlank()) {
                val limitMsg = when {
                    monitorLimit == -1 -> ""
                    monitorLimit == Int.MAX_VALUE -> tr(R.string.core_monitor_no_limit)
                    else -> tr(R.string.core_monitor_limit, monitorLimit)
                }
                commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer,
                    text = tr(R.string.core_usage_monitor, limitMsg), isPrivate = false))
            } else {
                sendRaw("MONITOR $arg")
            }
        }
        // IRCv3 draft/read-marker: mark a buffer as read up to now (or a specific timestamp).
        // /markread [#channel|nick] [timestamp]
        "markread" -> {
            val target = parts.getOrNull(1)
                ?: (if (currentBuffer != "*server*") currentBuffer else null)
                ?: return
            val ts = parts.getOrNull(2)
                ?: historyTimestamp(java.time.Instant.now())
            if (hasReadMarkerCap()) {
                // soju.im/read uses the "READ" command; draft/read-marker uses "MARKREAD".
                // Both carry the same "timestamp=<ISO8601>" argument.
                val readCmd = if (hasCap("soju.im/read") && !hasCap("draft/read-marker")) "READ" else "MARKREAD"
                sendRaw("$readCmd $target timestamp=$ts")
            } else {
                commandEvents.send(IrcEvent.Notice(from = "*", target = currentBuffer,
                    text = tr(R.string.core_no_read_markers), isPrivate = false))
            }
        }
        "dns" -> {
            val arg = parts.getOrNull(1)?.trim() ?: return
            if (arg.isBlank()) return

            // /dns resolves on-device. With a proxy active that would leak the queried
            // name to the local resolver, outside the tunnel the rest of the session
            // Refuse rather than leak.
            if (config.proxy.enabled) {
                commandEvents.send(IrcEvent.ServerText(
                    tr(R.string.core_dns_proxy_disabled),
                    bufferName = currentBuffer))
                return
            }

            coroutineScope {
                launch {
                    try {
                        commandEvents.send(IrcEvent.ServerText(tr(R.string.core_looking_up, arg), bufferName = currentBuffer))
                        val resolved = resolveDns(arg)
                        if (resolved.isNotEmpty()) {
                            commandEvents.send(IrcEvent.ServerText(tr(R.string.core_resolved_to), bufferName = currentBuffer))
                            resolved.forEach { line ->
                                commandEvents.send(IrcEvent.ServerText("    $line", bufferName = currentBuffer))
                            }
                        } else {
                            commandEvents.send(IrcEvent.ServerText(tr(R.string.core_no_resolution, arg), bufferName = currentBuffer))
                        }
                    } catch (e: Exception) {
                        commandEvents.send(IrcEvent.ServerText(tr(R.string.core_dns_failed, e.message ?: tr(R.string.core_unknown_error)), bufferName = currentBuffer))
                    }
                }
            }
        }
        else -> {
            // Pass through unknown commands
            val rawCmd = parts[0].uppercase(Locale.ROOT)
            val rest = cmdLine.trim().drop(parts[0].length).trimStart()
            sendRaw(if (rest.isBlank()) rawCmd else "$rawCmd $rest")
        }
    }
}

private suspend fun IrcClient.resolveDns(query: String): List<String> = withContext(Dispatchers.IO) {
    val results = mutableListOf<String>()
    var hasIpv4 = false
    var hasIpv6 = false

    try {
        // Forward lookup: hostname > IP(s)
        // Android's resolver won't return IPv6 if the device lacks IPv6 connectivity
        val addresses = InetAddress.getAllByName(query)
        for (addr in addresses) {
            val ip = addr.hostAddress ?: continue
            when (addr) {
                is java.net.Inet6Address -> {
                    hasIpv6 = true
                    results.add("IPv6: $ip")
                }
                is java.net.Inet4Address -> {
                    hasIpv4 = true
                    results.add("IPv4: $ip")
                }
                else -> results.add("IP: $ip")
            }

            // Try reverse lookup (PTR) for each IP
            try {
                val reverse = InetAddress.getByAddress(addr.address).canonicalHostName
                if (reverse != ip && reverse != query) {
                    results.add("  PTR: $reverse")
                }
            } catch (_: Exception) {
                // Reverse failed, skip
            }
        }

        // If we only got IPv4, note that IPv6 may exist but wasn't returned
        if (hasIpv4 && !hasIpv6 && results.isNotEmpty()) {
            results.add(tr(R.string.core_dns_ipv6_note))
        }

        // If it's an IP address and no forward results, try reverse directly
        if (results.isEmpty() && isValidIp(query)) {
            try {
                val addr = InetAddress.getByName(query)
                val ptr = addr.canonicalHostName
                if (ptr != query) {
                    results.add("PTR: $ptr")
                }
            } catch (_: Exception) {}
        }
    } catch (_: UnknownHostException) {
        // Host not found
    }

    results
}

// Validate IPs
private fun isValidIp(input: String): Boolean {
    return input.matches(Regex("^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")) ||
           input.matches(Regex("^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$")) // rough IPv6 check
}
