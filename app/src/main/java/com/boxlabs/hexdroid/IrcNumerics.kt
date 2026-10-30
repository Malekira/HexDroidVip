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

import com.boxlabs.hexdroid.connection.ConnectionConstants
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Handles one numeric reply: (message, server time, replayed as history, now). */
internal typealias NumericHandler = suspend (IrcMessage, Long?, Boolean, Long) -> Unit

/**
 * The numeric reply handlers for one connection, keyed by numeric. They emit into [out] and write
 * protocol lines with [writeLine]; 001 also ends [registrationWatchdogJob]. The read loop shows a
 * numeric without a handler as formatted text.
 */
internal fun IrcClient.buildNumericHandlers(
    out: ProducerScope<IrcEvent>,
    writeLine: suspend (String) -> Unit,
    registrationWatchdogJob: Job,
): Map<String, NumericHandler> = with(out) {
    /**
     * The item and end-of-list handlers for a channel list mode (ban, invex, except, quiet), whose
     * items all read <me> <#chan> <mask> [setBy] [setAt (epoch seconds)].
     */
    fun listModeHandlers(
        itemNumeric: String,
        endNumeric: String,
        makeItem: (chan: String, mask: String, setBy: String?, setAtMs: Long?, timeMs: Long?, isHistory: Boolean) -> IrcEvent,
        makeEnd: (chan: String, code: String, timeMs: Long?, isHistory: Boolean) -> IrcEvent
    ): List<Pair<String, NumericHandler>> = listOf(
        itemNumeric to handler@{ msg, serverTimeMs, playbackHistory, nowMs ->
            val p = msg.params + listOfNotNull(msg.trailing)
            val chan = p.getOrNull(1) ?: return@handler
            val mask = p.getOrNull(2) ?: return@handler
            val setBy = p.getOrNull(3)
            val setAtMs = p.getOrNull(4)?.toLongOrNull()?.let { it * 1000L }
            val hist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
            send(makeItem(chan, mask, setBy, setAtMs, serverTimeMs, hist))
        },
        endNumeric to handler@{ msg, serverTimeMs, playbackHistory, nowMs ->
            val chan = msg.params.getOrNull(1) ?: return@handler
            val hist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
            send(makeEnd(chan, msg.command, serverTimeMs, hist))
        }
    )

    // Reclaim the configured nick after reconnecting on a fallback. When the primary nick
    // was taken at registration, most often our own ghost session lingering after a
    // mobile/Wi-Fi reconnect; try once, then retry QUIETLY in the background until it
    // frees, the user takes manual control, or we hit the give-up window.
    suspend fun runNickReclaim() {
        // Set once the server tells us a nick change can never succeed on this connection
        // (account-locked nick). Cleared only by a reconnect, which rebuilds the client.
        if (nickChangeRefused) return
        val target = config.nick
        val fallback = currentNick   // the nick the server registered us with
        // Let SASL/services nick-reclaim and a fast ghost ping-timeout settle first.
        delay(ConnectionConstants.NICK_RECLAIM_INITIAL_DELAY_MS)
        if (userClosing || !nickRegainEnabled || !nickEquals(currentNick, fallback)) return
            if (nickEquals(currentNick, target)) {
                send(IrcEvent.ServerText("*** " + tr(R.string.core_regained_nick, target), code = "NICK"))
                return
            }

            // First attempt
            send(IrcEvent.ServerText("*** " + tr(R.string.core_regaining_nick, target), code = "NICK"))
            runCatching { writeLine("NICK $target") }
            delay(ConnectionConstants.NICK_RECLAIM_RESPONSE_GRACE_MS)
            if (nickChangeRefused) return
            if (nickEquals(currentNick, target)) {
                send(IrcEvent.ServerText("*** " + tr(R.string.core_regained_nick, target), code = "NICK"))
                return
            }
            if (userClosing || !nickEquals(currentNick, fallback)) return

                // Didn't free up immediately, almost always our own lingering session, which won't
                // release the nick until the server times it out (~180s).
                send(IrcEvent.ServerText(
                    "*** " + tr(R.string.core_nick_still_in_use, target), code = "NICK"))

                val deadline = System.currentTimeMillis() + ConnectionConstants.NICK_RECLAIM_TOTAL_WINDOW_MS
                while (System.currentTimeMillis() < deadline) {
                    delay(ConnectionConstants.NICK_RECLAIM_RETRY_INTERVAL_MS)
                    if (userClosing || nickChangeRefused || !nickRegainEnabled) return
                        if (nickEquals(currentNick, target)) {
                            send(IrcEvent.ServerText("*** " + tr(R.string.core_regained_nick, target), code = "NICK"))
                            return
                        }
                        // Don't fight a deliberate /nick or a server-forced rename.
                        if (!nickEquals(currentNick, fallback)) return
                            runCatching { writeLine("NICK $target") }
                            delay(ConnectionConstants.NICK_RECLAIM_RESPONSE_GRACE_MS)
                            if (nickEquals(currentNick, target)) {
                                send(IrcEvent.ServerText("*** " + tr(R.string.core_regained_nick, target), code = "NICK"))
                                return
                            }
                }
                send(IrcEvent.ServerText(
                    "*** " + tr(R.string.core_nick_gave_up, target),
                    code = "NICK"))
    }

    val numericHandlers: Map<String, NumericHandler> = mapOf(
        "001" to handler@{ msg, _, _, _ ->
            // Welcome: <me> ...
            val me = msg.params.getOrNull(0) ?: config.nick
            currentNick = me
            registered = true
            registrationWatchdogJob.cancel()  // 001 received — connection is live
            send(IrcEvent.Registered(me))
            if (netCertUnavailable) send(IrcEvent.ServerText(tr(R.string.core_netcert_unavailable)))
            if (netCertCreated) send(IrcEvent.ServerText(tr(R.string.core_netcert_created)))
            // draft/metadata-2: subscribe now if it wasn't already done during registration
            // (only servers advertising `before-connect` accept METADATA that early).
            // takeMetadataSubLine() self-guards, so this is a no-op when it already went out.
            sessionRef?.markRegistered()
            if (sessionRef?.sojuMetadata != true || bouncerNetId != null) {
                sessionRef?.takeMetadataSubLine()?.let { writeLine(it) }
            }
            // Stored away message: already sent during registration under draft/pre-away, else now.
            if (sessionRef?.preAwaySent != true) {
                config.initialAwayMessage?.takeIf { it.isNotBlank() }?.let {
                    writeLine("AWAY :${clampLen(it, "AWAYLEN")}")
                }
            }
            // If the server handed us a fallback nick (the configured nick was taken,
            // often our own ghost session lingering after a reconnect), try to reclaim it.
            if (nickRegainEnabled && !nickEquals(me, config.nick)) {
                launch { runNickReclaim() }
            }
        },

        "004" to handler@{ msg, _, _, _ ->
            send(IrcEvent.SupportedUserModes(msg.params.getOrNull(3).orEmpty()))
            writeLine("MODE $currentNick")
        },
        "221" to handler@{ msg, _, _, _ ->
            if (nickEquals(msg.params.firstOrNull().orEmpty(), currentNick)) {
                send(IrcEvent.OwnUserModes(msg.params.getOrNull(1) ?: msg.trailing.orEmpty(), snapshot = true))
            }
        },
        "005" to handler@{ msg, _, _, _ ->
            // ISUPPORT: drive channel detection, prefix rank mapping, and casemapping.
            // Example: PREFIX=(qaohv)~&@%+ CHANTYPES=#& CASEMAPPING=rfc1459 STATUSMSG=@+
            val tokens = msg.params.drop(1)
            var chant = chantypes
            var cm = caseMapping
            var pm = prefixModes
            var ps = prefixSymbols
            var sm: String? = statusMsg
            var chm: String? = chanModes
            var ll: Int? = serverLinelen

            for (tok in tokens) {
                if (tok.isBlank()) continue
                val parts = tok.split("=", limit = 2)
                val k = parts[0].trim().uppercase(Locale.ROOT)
                val v = parts.getOrNull(1)?.trim()?.let(::unescapeIsupportValue)
                // RPL_ISUPPORT negation ("-KEY"): the server withdraws a previously
                // advertised key (used by extended-isupport updates, legal in plain 005
                // too). Reset the tracked keys to their defaults. CHANTYPES, CASEMAPPING
                // and PREFIX deliberately keep their current values - reverting those to
                // RFC defaults mid-session would re-key existing buffers and nicklists.
                if (k.startsWith("-")) {
                    when (k.drop(1)) {
                        "STATUSMSG" -> sm = null
                        "CHANMODES" -> chm = null
                        "LINELEN" -> ll = null
                        "WHOX" -> whoxSupported = false
                        "CLIENTTAGDENY" -> clientTagDeny = null
                        "ELIST" -> elistTokens = null
                        "MONITOR" -> monitorLimit = -1
                        "SOJU.IM/FILEHOST", "DRAFT/FILEHOST", "FILEHOST" -> filehostUrl = null
                        "DRAFT/ICON", "ICON" -> networkIconUrl = null
                        "BOT" -> botModeChar = null
                        "EXTBAN" -> { extbanPrefix = null; extbanTypes = null }
                        "ACCOUNTEXTBAN" -> accountExtban = null
                        "CHATHISTORY" -> chatHistoryLimit = 0
                        "UTF8ONLY" -> utf8OnlyServer = false
                        "MSGREFTYPES" -> msgRefTypes = emptyList()
                        "VAPID" -> vapidPublicKey = null
                        "TOPICLEN", "KICKLEN", "AWAYLEN", "QUITLEN", "NICKLEN", "MAXNICKLEN",
                        "CHANNELLEN", "NAMELEN" -> lengthLimits.remove(k.drop(1))
                        "KNOCK" -> knockSupported = false
                        "CHANLIMIT" -> chanLimits.clear()
                        "BOUNCER_NETID" -> bouncerNetId = null
                        "CALLERID" -> callerIdMode = null
                        "SOJU.IM/SAFERATE" -> safeRate = false
                    }
                    continue
                }
                when (k) {
                    "CHANTYPES" -> if (!v.isNullOrBlank()) chant = v
                    "CASEMAPPING" -> if (!v.isNullOrBlank()) cm = v
                    "STATUSMSG" -> if (!v.isNullOrBlank()) sm = v
                    "CHANMODES" -> if (!v.isNullOrBlank()) chm = v
                    "PREFIX" -> if (!v.isNullOrBlank()) {
                        val m0 = Regex("^\\(([^)]+)\\)(.+)$").find(v)
                        if (m0 != null) {
                            pm = m0.groupValues[1]
                            ps = m0.groupValues[2]
                        }
                    }
                    // parse LINELEN so the ViewModel can use the server's actual limit
                    "LINELEN" -> v?.toIntOrNull()?.takeIf { it in 512..65535 }?.let { ll = it }
                    // WHOX is a flag token (no value): server supports extended WHO %fields,querytype
                    "WHOX" -> whoxSupported = true
                    // BOT=<char>: the user-mode letter that marks a bot; also the flag shown
                    // in WHO/WHOX replies for bots (Bot Mode spec).
                    "BOT" -> botModeChar = v?.trim()?.firstOrNull()
                    // EXTBAN=<prefix>,<letters>: extended ban syntax. Prefix may be empty.
                    "EXTBAN" -> {
                        val parts = v?.split(",", limit = 2)
                        extbanPrefix = parts?.getOrNull(0) ?: ""
                        extbanTypes = parts?.getOrNull(1)?.takeIf { it.isNotBlank() }
                    }
                    // ACCOUNTEXTBAN=<name>,<letter> (draft/account-extban): prefer the short letter.
                    "ACCOUNTEXTBAN" -> accountExtban =
                        v?.split(",")?.mapNotNull { it.trim().takeIf { t -> t.isNotEmpty() } }?.lastOrNull()
                    // CHATHISTORY=<n>: max messages returned per CHATHISTORY request.
                    "CHATHISTORY" -> chatHistoryLimit = v?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    // UTF8ONLY: outgoing text must be UTF-8 whatever encoding the profile sets.
                    "UTF8ONLY" -> utf8OnlyServer = true
                    // VAPID=<key>: application-server public key for draft/webpush notifications.
                    // A transition from "no key" to a key is the cue to subscribe.
                    "VAPID" -> {
                        val newKey = v?.trim()?.takeIf { it.isNotEmpty() }
                        val changed = newKey != null && newKey != vapidPublicKey
                        vapidPublicKey = newKey
                        if (changed) vapidBecameReady = true
                    }
                    // MSGREFTYPES=<csv>: which reference types CHATHISTORY selectors may use.
                    // Order matters: the spec lists these "in order of decreasing preference".
                    "MSGREFTYPES" -> msgRefTypes =
                        v?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
                            ?: emptyList()
                    // Server-advertised length limits; stored for input validation and for
                    // clamping free-text command payloads before they're sent.
                    "TOPICLEN", "KICKLEN", "AWAYLEN", "QUITLEN", "NICKLEN", "MAXNICKLEN",
                    "CHANNELLEN", "NAMELEN" ->
                        v?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { lengthLimits[k] = it }
                    // KNOCK: server supports /knock on invite-only channels (valueless token).
                    "KNOCK" -> knockSupported = true
                    // CHANLIMIT=<pfx>:<n>[,...]: max channels per type prefix.
                    "CHANLIMIT" -> {
                        chanLimits.clear()
                        v?.split(',')?.forEach { grp ->
                            val idx = grp.lastIndexOf(':')
                            if (idx > 0) {
                                val n = grp.substring(idx + 1).toIntOrNull()?.takeIf { it > 0 }
                                if (n != null) grp.substring(0, idx).forEach { pfx -> chanLimits[pfx] = n }
                            }
                        }
                    }
                    "CLIENTTAGDENY" -> clientTagDeny = v?.takeIf { it.isNotBlank() }
                    // BOUNCER_NETID=<id>: soju bound this connection to that upstream network.
                    "BOUNCER_NETID" -> bouncerNetId = v?.takeIf { it.isNotBlank() }
                    // soju.im/SAFERATE (valueless): the server will not flood-kill us.
                    "CALLERID" -> callerIdMode = v ?: "g"
                    "SOJU.IM/SAFERATE" -> safeRate = true
                    // soju.im/FILEHOST / draft/FILEHOST: HTTP file-upload endpoint URL.
                    // Keys arrive uppercased by the tokenizer above; the value keeps its case.
                    "SOJU.IM/FILEHOST", "DRAFT/FILEHOST", "FILEHOST" -> filehostUrl = v?.takeIf { it.isNotBlank() }
                    // ICON / draft/ICON ISUPPORT token: ICON=<url> (draft/ICON in the wild, e.g. UnrealIRCd)
                    // advertises a server icon; may contain a literal {size} template the client
                    // substitutes with a pixel size. Keys arrive uppercased by the tokenizer.
                    "DRAFT/ICON", "ICON" -> networkIconUrl = v?.takeIf { it.isNotBlank() }
                    // ELIST=<chars>: server-side LIST search extensions (U = user-count filtering, etc).
                    "ELIST" -> if (!v.isNullOrBlank()) elistTokens = v.uppercase(Locale.ROOT)
                    // MONITOR=<n>: the maximum watch list size per client; empty means no limit. Used
                    // by /monitor to report when the limit is reached.
                    "MONITOR" -> {
                        monitorLimit = if (v.isNullOrBlank()) Int.MAX_VALUE
                                       else v.toIntOrNull()?.takeIf { it >= 0 } ?: Int.MAX_VALUE
                    }
                }
            }

            chantypes = chant
            caseMapping = cm
            statusMsg = sm
            chanModes = chm
            prefixModes = pm
            prefixSymbols = ps
            serverLinelen = ll

            val mp = mutableMapOf<Char, Char>()
            val n = minOf(pm.length, ps.length)
            for (i in 0 until n) mp[pm[i]] = ps[i]
            if (mp.isNotEmpty()) prefixModeToSymbol = mp

            send(IrcEvent.ISupport(
                chantypes, caseMapping, prefixModes, prefixSymbols, statusMsg, chanModes, ll, elistTokens,
                filehostUrl, networkIconUrl, extbanPrefix, extbanTypes, accountExtban,
                bouncerNetId = bouncerNetId,
                safeRate = safeRate,
                callerIdMode = callerIdMode,
            ))
            // soju lists per-target metadata only once the connection is bound, which
            // BOUNCER_NETID announces after RPL_WELCOME.
            if (registered && bouncerNetId != null) sessionRef?.takeMetadataSubLine()?.let { writeLine(it) }
            if (vapidBecameReady) {
                vapidBecameReady = false
                vapidPublicKey?.let { send(IrcEvent.WebPushVapidReady(it)) }
            }
        },

        // LIST output
        // draft/metadata-2 numerics. Params are shifted by one because params[0] is
        // always our own nick (or "*" before registration completes).
        //   761 RPL_KEYVALUE <Target> <Key> <Visibility> :<Value>
        // The value is the last parameter, so read it through allParams. 760
        // RPL_WHOISKEYVALUE is handled separately via formatNumeric + WHOIS buffer
        // routing, because it is display-only (WHOIS) and not a metadata-store update.
        "761" to handler@{ msg, _, _, _ ->
            val ap = msg.allParams
            val t = ap.getOrNull(1) ?: return@handler
            val k = ap.getOrNull(2) ?: return@handler
            send(IrcEvent.MetadataChanged(t, k.lowercase(Locale.ROOT), ap.getOrNull(3), ap.getOrNull(4)))
        },
        // 766 RPL_KEYNOTSET <Target> <Key> :key not set - the key is absent, so clear it
        // locally. (Also the reply to CLEAR, one per cleared key.)
        "766" to handler@{ msg, _, _, _ ->
            val t = msg.params.getOrNull(1) ?: return@handler
            val k = msg.params.getOrNull(2) ?: return@handler
            send(IrcEvent.MetadataChanged(t, k.lowercase(Locale.ROOT), null, null))
        },
        // 770/771/772: subscription acknowledgements. Keys are individual parameters in
        // metadata-2 (they were a space-separated trailing in the old metadata-notify),
        // so allParams covers both shapes.
        "770" to handler@{ msg, _, _, _ ->
            // RPL_METADATASUBOK: our SUB was accepted. No user-facing message (some servers
            // reply with one 770 per key); /metadata subs (772) still reports. soju sends the
            // current values before it, so it also marks the end of the initial sync.
            for (key in msg.allParams.drop(1)) {
                if (key.isNotBlank()) send(IrcEvent.MetadataSubscribed(key.lowercase(Locale.ROOT)))
            }
        },
        "771" to handler@{ msg, _, _, _ ->
            val keys = msg.allParams.drop(1).filter { it.isNotBlank() }
            if (keys.isNotEmpty()) send(IrcEvent.Status(tr(R.string.core_metadata_unsub, keys.joinToString(" "))))
        },
        "772" to handler@{ msg, _, _, _ ->
            val keys = msg.allParams.drop(1).filter { it.isNotBlank() }
            send(IrcEvent.ServerText("*** " + tr(R.string.core_metadata_subs, keys.joinToString(" ").ifBlank { tr(R.string.core_metadata_subs_none) }), code = "772"))
        },
        // 774 RPL_METADATASYNCLATER <Target> [<RetryAfter>]: the server deferred the sync.
        // Re-request after the advertised delay (default 10s, clamped so a hostile or
        // buggy value can't park a coroutine for hours).
        "774" to handler@{ msg, _, _, _ ->
            val t = msg.params.getOrNull(1) ?: return@handler
            val retryAfter = msg.params.getOrNull(2)?.toLongOrNull()?.coerceIn(1L, 300L) ?: 10L
            launch {
                delay(retryAfter * 1000L)
                runCatching { sendRaw("METADATA $t SYNC") }
            }
        },

        "321" to handler@{ _, _, _, _ -> send(IrcEvent.ChannelListStart) },
        "322" to handler@{ msg, _, _, _ ->
            // RPL_LIST: <me> <#chan> <visible> :topic
            val chan = msg.params.getOrNull(1) ?: return@handler
            val users = msg.params.getOrNull(2)?.toIntOrNull() ?: 0
            val topic = (msg.trailing ?: msg.params.getOrNull(3) ?: "").let { stripIrcFormatting(it) }
            send(IrcEvent.ChannelListItem(chan, users, topic))
        },
        "323" to handler@{ _, _, _, _ -> send(IrcEvent.ChannelListEnd) },
        // RPL_TRYAGAIN: the server rejected a command (usually LIST) due to rate limiting
        // or SECURELIST (list disabled for the first N seconds after connect). Format:
        // <me> <command> :<message>. End any in-progress list so the UI stops spinning, and
        // surface an actionable message. Emitted as a distinct event so the ViewModel can
        // tell the LIST screen to show a retry affordance rather than an empty result.
        "263" to handler@{ msg, _, _, _ ->
            val command = msg.params.getOrNull(1) ?: ""
            val detail = (msg.trailing ?: msg.params.getOrNull(2))?.takeIf { it.isNotBlank() }
            send(IrcEvent.ChannelListEnd)
            send(IrcEvent.TryAgain(command = command.uppercase(Locale.ROOT), message = detail))
        },

        // Topic numerics
        "332" to handler@{ msg, serverTimeMs, playbackHistory, nowMs ->
            // RPL_TOPIC: <me> <#chan> :topic
            val chan = msg.params.getOrNull(1) ?: return@handler
            val topic = msg.trailing ?: msg.params.getOrNull(2)
            val hist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
            send(IrcEvent.TopicReply(chan, topic, timeMs = serverTimeMs, isHistory = hist))
        },
        "333" to handler@{ msg, serverTimeMs, playbackHistory, nowMs ->
            // RPL_TOPICWHOTIME: <me> <#chan> <setter> <time>
            val chan = msg.params.getOrNull(1) ?: return@handler
            val setter = msg.params.getOrNull(2) ?: return@handler
            // InspIRCd 4 sends the set-time as a trailing parameter; fall back to it.
            val secs = (msg.params.getOrNull(3) ?: msg.trailing)?.toLongOrNull()
            val setAtMs = secs?.let { it * 1000L }
            val hist = playbackHistory || isHeuristicHistory(chan, serverTimeMs, nowMs)
            send(IrcEvent.TopicWhoTime(chan, setter, setAtMs, timeMs = serverTimeMs, isHistory = hist))
        },

        "324" to handler@{ msg, _, _, _ ->
            // RPL_CHANNELMODEIS: <me> <#chan> <modes> [mode params...]
            val chan = msg.params.getOrNull(1) ?: return@handler
            val modes = (msg.params.drop(2) + listOfNotNull(msg.trailing)).joinToString(" ").trim()
            if (modes.isNotBlank()) {
                send(IrcEvent.ChannelModeIs(
                    chan, modes, code = msg.command,
                    silent = isSilentModeReply(chan),
                ))
            }
        },
        "900" to handler@{ msg, _, _, _ ->
            // RPL_LOGGEDIN: <nick> <mask> <account> :You are now logged in as <account>.
            // Track our own account so the UI can reflect being identified.
            val account = msg.params.getOrNull(2)?.takeIf { it.isNotBlank() && it != "*" }
            if (account != null) send(IrcEvent.AccountChanged(currentNick, account))
        },
        "901" to handler@{ msg, _, _, _ ->
            // RPL_LOGGEDOUT.
            send(IrcEvent.AccountChanged(currentNick, "*"))
        },
        "381" to handler@{ msg, _, _, _ ->
            // RPL_YOUREOPER
            val text = msg.trailing ?: msg.params.drop(1).joinToString(" ").trim().ifBlank { tr(R.string.core_youre_oper) }
            send(IrcEvent.YoureOper(text))
        },

        // Names list
        "353" to handler@{ msg, _, _, _ ->
            // RPL_NAMREPLY: <me> <symbol> <#chan> :[prefix]nick ...
            // With userhost-in-names CAP, entries are [prefix]nick!user@host - strip the
            // user@host so channel tracking, nick colouring, and case-folding work correctly.
            val chan = msg.params.getOrNull(2) ?: return@handler
            val names = (msg.trailing ?: msg.params.getOrNull(3) ?: "").split(Regex("\\s+")).filter { it.isNotBlank() }
                .map { raw ->
                    // Find where prefixes end and the nick!user@host begins.
                    // Use server-negotiated prefixSymbols (updated from 005 PREFIX) so non-standard
                    // rank symbols (e.g. '!' or '~' on custom IRCd configs) are stripped correctly.
                    val validPrefixes = prefixSymbols
                    val prefixEnd = raw.indexOfFirst { it !in validPrefixes }
                    if (prefixEnd < 0) return@map raw  // only prefixes? keep as-is
                    val prefixes = raw.substring(0, prefixEnd)
                    val rest = raw.substring(prefixEnd)
                    // Strip !user@host if present (userhost-in-names CAP)
                    val nick = rest.substringBefore('!')
                    prefixes + nick
                }
            if (names.isNotEmpty()) send(IrcEvent.Names(chan, names))
        },
        "366" to handler@{ msg, _, _, _ ->
            // RPL_ENDOFNAMES: <me> <#chan> :End of /NAMES list.
            val chan = msg.params.getOrNull(1) ?: return@handler
            send(IrcEvent.NamesEnd(chan))
        },

        // WHOX reply (354): response to WHO #chan %tuhsnfar,42
        // Params depend on the %fields requested. With %tuhsnfar,42:
        //   <me> 42 <ident> <host> <server> <nick> <flags> <account> :<realname>
        //   The query type (42) is in params[1]; we skip this numeric if it doesn't match ours.
        "354" to handler@{ msg, _, _, _ ->
            // Query type 42 marks the client's own member refresh; any other answers a WHO the user typed.
            if (msg.params.getOrNull(1) != "42") {
                val buffer = currentWho()?.buffer ?: return@handler
                val fields = msg.allParams.drop(1).joinToString(" ") { stripIrcFormatting(it) }
                send(IrcEvent.ServerText(fields, code = "354", bufferName = buffer))
                return@handler
            }
            val ident   = msg.params.getOrNull(2) ?: return@handler
            val host    = msg.params.getOrNull(3) ?: return@handler
            val nick    = msg.params.getOrNull(5) ?: return@handler
            // flags field (params[6]): 'H'=Here (present), 'G'=Gone (away). May have extra flags like '*'=oper.
            val flags   = msg.params.getOrNull(6)
            val isAway  = flags?.firstOrNull { it == 'H' || it == 'G' }?.let { it == 'G' }
            val account = msg.params.getOrNull(7)?.takeIf { it != "0" }  // "0" = not logged in
            // Bot Mode: the flags field also carries the BOT mode letter for bots.
            val isBot = botModeChar?.let { bc -> flags?.contains(bc) == true } ?: false
            noteUser(nick, ident, host, account ?: "*")
            send(IrcEvent.WhoxReply(nick = nick, ident = ident, host = host, account = account, isAway = isAway, isBot = isBot))
        },

        // RPL_WHOREPLY (352): the plain WHO reply, for servers without WHOX.
        //   <me> <channel> <username> <host> <server> <nick> <flags> :<hopcount> <realname>
        // Per the modern spec's WHO section. Flags carry 'H' (here) or 'G' (gone/away), and may
        // be followed by '*' for an operator and channel membership prefixes. No account field,
        // so account stays null and only away and bot status come from this.
        "352" to handler@{ msg, _, _, _ ->
            val ident = msg.params.getOrNull(2) ?: return@handler
            val host  = msg.params.getOrNull(3) ?: return@handler
            val nick  = msg.params.getOrNull(5) ?: return@handler
            val flags = msg.params.getOrNull(6)
            val isAway = flags?.firstOrNull { it == 'H' || it == 'G' }?.let { it == 'G' }
            val isBot = botModeChar?.let { bc -> flags?.contains(bc) == true } ?: false
            noteUser(nick, ident, host)
            send(IrcEvent.WhoxReply(nick = nick, ident = ident, host = host, account = null, isAway = isAway, isBot = isBot))
            val chan = msg.params.getOrNull(1) ?: "*"
            val buffer = currentWho(chan.takeIf { isChannelName(it) })?.buffer ?: return@handler
            val server = msg.params.getOrNull(4)?.let { " ($it)" } ?: ""
            val realname = msg.trailing?.let { stripIrcFormatting(it.substringAfter(' ', "")) }.orEmpty()
            send(IrcEvent.ServerText(
                "$chan $nick ${flags.orEmpty()} $ident@$host$server $realname".trimEnd(),
                code = "352",
                bufferName = buffer,
            ))
        },

        // RPL_ENDOFWHO (315): <me> <mask> :End of WHO list. Printed only for a WHO the user typed.
        "315" to handler@{ msg, _, _, _ ->
            val mask = msg.params.getOrNull(1) ?: "*"
            val buffer = finishWho(mask)?.buffer ?: return@handler
            val text = msg.trailing?.let { stripIrcFormatting(it) }
            send(IrcEvent.ServerText(if (text != null) "$mask: $text" else mask, code = "315", bufferName = buffer))
        },

        // ERR_NOTONCHANNEL
        "442" to handler@{ msg, _, _, _ ->
            // <me> <#chan> :You're not on that channel
            val chan = msg.params.getOrNull(1) ?: return@handler
            val reason = msg.trailing?.let { stripIrcFormatting(it) } ?: tr(R.string.core_not_on_channel)
            send(IrcEvent.NotOnChannel(chan, reason, code = msg.command))
        },

        // Ban / invex / except / quiet list modes - all use identical param layouts:
        // <me> <#chan> <mask> [setBy] [setAt(epoch)]. Delegated to listModeHandlers().
        *listModeHandlers("367", "368",
            makeItem = { c, m, by, at, ts, hist -> IrcEvent.BanListItem(c, m, by, at, timeMs = ts, isHistory = hist) },
            makeEnd  = { c, code, ts, hist -> IrcEvent.BanListEnd(c, code = code, timeMs = ts, isHistory = hist) }
        ).toTypedArray(),
        *listModeHandlers("346", "347",
            makeItem = { c, m, by, at, ts, hist -> IrcEvent.InvexListItem(c, m, by, at, timeMs = ts, isHistory = hist) },
            makeEnd  = { c, code, ts, hist -> IrcEvent.InvexListEnd(c, code = code, timeMs = ts, isHistory = hist) }
        ).toTypedArray(),
        *listModeHandlers("348", "349",
            makeItem = { c, m, by, at, ts, hist -> IrcEvent.ExceptListItem(c, m, by, at, timeMs = ts, isHistory = hist) },
            makeEnd  = { c, code, ts, hist -> IrcEvent.ExceptListEnd(c, code = code, timeMs = ts, isHistory = hist) }
        ).toTypedArray(),
        *listModeHandlers("728", "729",
            makeItem = { c, m, by, at, ts, hist -> IrcEvent.QuietListItem(c, m, by, at, timeMs = ts, isHistory = hist) },
            makeEnd  = { c, code, ts, hist -> IrcEvent.QuietListEnd(c, code = code, timeMs = ts, isHistory = hist) }
        ).toTypedArray(),

        // Join failures (ircu/unreal/inspircd/nefarious all use these)
        // ERR_TOOMANYCHANNELS: joined too many channels. Enrich with the CHANLIMIT figure
        // when we know it, mirroring the friendly 471-477 join-error handlers.
        "405" to handler@{ msg, _, _, _ ->
            val chan = msg.params.getOrNull(1) ?: return@handler
            val limit = channelLimitFor(chan)
            val reason = if (limit > 0) tr(R.string.core_channel_limit_reached, limit)
                         else (msg.trailing ?: tr(R.string.core_too_many_channels))
            send(IrcEvent.JoinError(chan, reason, code = msg.command))
        },
        "471" to handler@{ msg, _, _, _ ->
            val chan = msg.params.getOrNull(1) ?: return@handler
            val reason = msg.trailing ?: tr(R.string.core_cannot_join_full)
            send(IrcEvent.JoinError(chan, reason, code = msg.command))
        },
        "472" to handler@{ msg, _, _, _ ->
            val chan = msg.params.getOrNull(1) ?: return@handler
            val reason = msg.trailing ?: tr(R.string.core_cannot_join)
            send(IrcEvent.JoinError(chan, reason, code = msg.command))
        },
        "473" to handler@{ msg, _, _, _ ->
            val chan = msg.params.getOrNull(1) ?: return@handler
            val reason = msg.trailing ?: tr(R.string.core_cannot_join_invite)
            send(IrcEvent.JoinError(chan, reason, code = msg.command))
        },
        "474" to handler@{ msg, _, _, _ ->
            val chan = msg.params.getOrNull(1) ?: return@handler
            val reason = msg.trailing ?: tr(R.string.core_cannot_join_banned)
            send(IrcEvent.JoinError(chan, reason, code = msg.command))
        },
        "475" to handler@{ msg, _, _, _ ->
            val chan = msg.params.getOrNull(1) ?: return@handler
            val reason = msg.trailing ?: tr(R.string.core_cannot_join_key)
            send(IrcEvent.JoinError(chan, reason, code = msg.command))
        },
        "476" to handler@{ msg, _, _, _ ->
            val chan = msg.params.getOrNull(1) ?: return@handler
            val reason = msg.trailing ?: tr(R.string.core_cannot_join)
            send(IrcEvent.JoinError(chan, reason, code = msg.command))
        },
        "477" to handler@{ msg, _, _, _ ->
            val chan = msg.params.getOrNull(1) ?: return@handler
            val reason = msg.trailing ?: tr(R.string.core_cannot_join)
            send(IrcEvent.JoinError(chan, reason, code = msg.command))
        },

        // MONITOR numerics: 730 online (target[!user@host] [account], comma-separated), 731 offline,
        // 732 list page, 733 end of list, 734 list full (with the targets that could not be added).
        "730" to handler@{ msg, serverTime, _, _ ->
            val raw = (msg.trailing ?: msg.params.drop(1).joinToString(","))
            for (entry in raw.split(",").map { it.trim() }.filter { it.isNotBlank() }) {
                // extended-monitor: "nick!user@host" or "nick!user@host account"
                val spaceIdx = entry.indexOf(' ')
                val hostPart = if (spaceIdx > 0) entry.substring(0, spaceIdx) else entry
                val accountPart = if (spaceIdx > 0) entry.substring(spaceIdx + 1).trim().takeIf { it.isNotBlank() && it != "*" } else null
                val bangIdx = hostPart.indexOf('!')
                val nick = if (bangIdx > 0) hostPart.substring(0, bangIdx) else hostPart
                val ident = if (bangIdx > 0) hostPart.substring(bangIdx + 1).substringBefore('@').takeIf { it.isNotBlank() } else null
                val host = if (bangIdx > 0) hostPart.substringAfter('@', "").takeIf { it.isNotBlank() } else null
                send(IrcEvent.MonitorStatus(nick, online = true, timeMs = serverTime, ident = ident, host = host, account = accountPart))
            }
        },
        "731" to handler@{ msg, serverTime, _, _ ->
            val nicks = (msg.trailing ?: msg.params.drop(1).joinToString(","))
                .split(",").map { it.trim().substringBefore("!") }.filter { it.isNotBlank() }
            for (nick in nicks) send(IrcEvent.MonitorStatus(nick, online = false, timeMs = serverTime))
        },
        "732" to handler@{ msg, _, _, _ ->
            // Accumulate entries across 732 lines; the terminating 733 prints them as one line rather
            // than one line per page.
            val nicks = (msg.trailing ?: msg.params.drop(1).joinToString(","))
                .split(",").map { it.trim().substringBefore("!") }.filter { it.isNotBlank() }
            if (nicks.isNotEmpty()) monitorListBuffer.addAll(nicks)
        },
        "733" to handler@{ _, _, _, _ ->
            // RPL_ENDOFMONLIST: flush the accumulated 732 entries.
            val collected = monitorListBuffer.toList()
            monitorListBuffer.clear()
            if (collected.isEmpty()) {
                send(IrcEvent.ServerText(tr(R.string.core_monitor_empty)))
            } else {
                send(IrcEvent.ServerText(tr(R.string.core_monitor_list, collected.size, collected.joinToString(", "))))
            }
        },
        "734" to handler@{ msg, _, _, _ ->
            // ERR_MONLISTFULL: <client> <limit> <targets> :Monitor list is full.
            // params[0] is our nick; params[1] is the numeric limit; params[2] is the
            // CSV of nicks the server refused to add. Surface the limit AND the nicks so
            // the user can see what got dropped, not just that "the list" is full.
            val limit = msg.params.getOrNull(1) ?: "?"
            val rejected = msg.params.getOrNull(2)?.takeIf { it.isNotBlank() }
            val msgText = if (rejected != null) {
                tr(R.string.core_monitor_list_full_rejected, limit, rejected)
            } else {
                tr(R.string.core_monitor_list_full, limit)
            }
            send(IrcEvent.ServerText(msgText))
        },
    )
    numericHandlers
}
