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

import java.text.SimpleDateFormat
import java.util.Locale

internal fun IrcClient.formatNumeric(msg: IrcMessage): String? {
    val code = msg.command
    if (code.length != 3 || !code.all { it.isDigit() }) return null

    // Don't double-emit for numerics that already have dedicated events.
    // SASL numerics 903-908 are emitted as IrcEvent.Status/Error by IrcSession's
    // CAP/SASL state machine; they must be suppressed here or the user sees the
    // success/failure message twice (once as a clean Status, once as raw ServerText).
    // 900 (RPL_LOGGEDIN), 901 (RPL_LOGGEDOUT), and 902 (ERR_NICKLOCKED) are NOT
    // handled by IrcSession, so they fall through to the generic ServerText path.
    if (code in setOf(
            "001","005","315","321","322","323","324","332","333","352","353","366","367","368","381",
            "433","442","471","472","473","474","475","476","477",
            // draft/metadata-2 numerics: handled by dedicated handlers (761/766 update the
            // nick display silently; 770/771/772 emit a friendly status line; 774 retries).
            // Must return null here too, or the generic formatter's fallback echoes them.
            "761","766","770","771","772","774",
            "903","904","905","906","907","908"
        )) return null

    fun p(i: Int) = msg.params.getOrNull(i)
    // Strip IRC formatting for most numerics, but preserve it for MOTD lines (372/375/376)
    // so mIRC colours and bold/italic show up when the user has them enabled in settings.
    // The rendering layer (IrcLinkifiedText) decides whether to show or strip colours
    // based on the mircColorsEnabled setting.
    val motdCodes = setOf("372", "375", "376")
    val t = msg.trailing?.let { if (code in motdCodes) it else stripIrcFormatting(it) }

    return when (code) {
        // MOTD - pass raw text so colours/formatting are preserved for the renderer
        "375" -> t ?: tr(R.string.core_motd_start)
        "372" -> t ?: p(1) ?: ""
        "376" -> t ?: tr(R.string.core_motd_end)
        "422" -> t ?: tr(R.string.core_no_motd)

        // ISUPPORT
        "005" -> {
            val tokens = msg.params.drop(1).filter { it.isNotBlank() }
            if (tokens.isEmpty()) null else "ISUPPORT: " + tokens.joinToString(" ")
        }

        // Host hidden
        "396" -> {
            // Typical: :server 396 <nick> <hiddenHost> :is now your hidden host
            val hidden = p(1)
            when {
                hidden != null -> tr(R.string.core_hidden_host, hidden)
                t != null -> t
                else -> null
            }
        }

        // LUSERS
        "251" -> t ?: tr(R.string.core_lusers_251, p(1) ?: "?", p(2) ?: "?", p(3) ?: "?")
        "252" -> {
            val n = p(1) ?: return t
            // Server trailing wins when present; our own wording is quantity-aware.
            if (t != null) "$n $t"
            else trPlural(R.plurals.core_lusers_operators, n.toIntOrNull() ?: 0, n)
        }
        "254" -> {
            val n = p(1) ?: return t
            // Server trailing wins when present; our own wording is quantity-aware.
            if (t != null) "$n $t"
            else trPlural(R.plurals.core_lusers_channels, n.toIntOrNull() ?: 0, n)
        }
        // Count is a middle param, wording is the trailing; both halves needed (cf. 252/254).
        "253" -> {
            val n = p(1) ?: return t
            // Server trailing wins when present; our own wording is quantity-aware.
            if (t != null) "$n $t"
            else trPlural(R.plurals.core_lusers_unknown_conns, n.toIntOrNull() ?: 0, n)
        }
        "255" -> t ?: tr(R.string.core_lusers_255, p(1) ?: "?", p(2) ?: "?")
        "265" -> t ?: tr(R.string.core_lusers_265, p(1) ?: "?", p(2) ?: "?")
        "266" -> t ?: tr(R.string.core_lusers_266, p(1) ?: "?", p(2) ?: "?")

        // Channel info
        "328" -> t?.let { tr(R.string.core_channel_url, it) }
        "329" -> {
            val ts = (p(2) ?: t)?.toLongOrNull()?.times(1000L)
            val date = ts?.let {
                val sdf = SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", Locale.getDefault())
                sdf.format(java.util.Date(it))
            } ?: tr(R.string.core_unknown)
            tr(R.string.core_channel_created, date)
        }

        // WHOIS / away / logged-in
        "301" -> {
            val nick = p(1) ?: return null
            val awayMsg = t?.takeIf { it != "*" } ?: tr(R.string.core_away_default)
            tr(R.string.core_whois_away, nick, awayMsg)
        }
        "307" -> {
            val nick = p(1) ?: return null
            t?.let { tr(R.string.core_whois_logged_in_as, nick, it) }
        }
        "311" -> {
            val nick = p(1) ?: return null
            val user = p(2) ?: "?"
            val host = p(3) ?: "?"
            val real = t ?: ""
            "$nick is $user@$host ${if (real.isBlank()) "" else "($real)"}"
        }
        "312" -> {
            val nick = p(1) ?: return null
            val server = p(2) ?: "?"
            val info = t ?: ""
            "$nick using $server ${if (info.isBlank()) "" else "($info)"}"
        }
        "313" -> {
            val nick = p(1) ?: return null
            t ?: tr(R.string.core_whois_oper, nick)
        }
        "317" -> {
            val nick = p(1) ?: return null
            val idle = p(2)?.toLongOrNull()
            val signon = p(3)?.toLongOrNull()?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }
            val idleStr = whoisDuration(idle)?.let { tr(R.string.personal_whois_idle, it) } ?: ""
            val signonStr = signon?.let { tr(R.string.core_whois_signon, java.util.Date(it * 1000L)) } ?: ""
            val connectedStr = signon?.let { since ->
                whoisDuration((System.currentTimeMillis() / 1000L - since).takeIf { it >= 0 })
                    ?.let { tr(R.string.personal_whois_connected, it) }
            } ?: ""
            listOf(idleStr, signonStr, connectedStr).filter { it.isNotBlank() }.joinToString(", ").let {
                if (it.isBlank()) null else "$nick: $it"
            }
        }
        "318" -> {
            val nick = p(1) ?: return null
            t ?: tr(R.string.core_whois_end)
        }
        "319" -> {
            val nick = p(1) ?: return null
            val chans = t ?: ""
            tr(R.string.core_whois_channels, nick, chans)
        }
        "320" -> {
            val nick = p(1) ?: return null
            t ?: tr(R.string.core_whois_special, nick)
        }
        "330" -> {
            // RPL_WHOISACCOUNT: <client> <nick> <account> :is logged in as
            // (Trailing differs across IRCds — InspIRCd/UnrealIRCd say "is logged in as",
            // ircd-seven says "is signed in as". Either way, account is in params[2].)
            val nick = p(1) ?: return null
            val account = p(2) ?: return null
            // Server trailing wins; otherwise use a whole sentence so verb-final
            // languages can order the nick and account themselves.
            if (t != null && t.isNotBlank()) "$nick $t $account"
            else tr(R.string.core_whois_logged_in_as, nick, account)
        }
        "335" -> {
            val nick = p(1) ?: return null
            t ?: tr(R.string.core_whois_bot, nick)
        }
        "338" -> {
            // RPL_WHOISACTUALLY: <client> <nick> <ip> :Actual IP/host (varies by IRCd).
            // Most IRCds put the resolved IP in params[2] and a description in trailing.
            val nick = p(1) ?: return null
            val info = (msg.params.drop(2) + listOfNotNull(t))
                .filter { it.isNotBlank() }
                .joinToString(" ")
            if (info.isBlank()) tr(R.string.core_whois_actual_host, nick) else "$nick $info"
        }
        "378" -> {
            // RPL_WHOISHOST: <client> <nick> :is connecting from <user>@<host> <ip>
            val nick = p(1) ?: return null
            t ?: return null
            "$nick $t"
        }
        "276", "310", "337", "339", "344", "350" -> {
            // Whois lines this client has no special layout for: certificate fingerprint,
            // helpop, free text, marks, country, gateway. Rendered like the rest of the
            // whois reply rather than through the numeric-prefixed fallback.
            val nick = p(1) ?: return null
            val rest = (msg.params.drop(2).map { stripIrcFormatting(it) } + listOfNotNull(t))
                .filter { it.isNotBlank() }.joinToString(" ")
            if (rest.isBlank()) return null
            "$nick $rest"
        }
        "616" -> {
            // RPL_WHOISCERTFP: <client> <nick> [<fp>] :has client certificate fingerprint <fp>
            val nick = p(1) ?: return null
            val rest = (msg.params.drop(2).map { stripIrcFormatting(it) } + listOfNotNull(t))
                .filter { it.isNotBlank() }.joinToString(" ")
            if (rest.isBlank()) return null
            "$nick $rest"
        }
        "379" -> {
            // RPL_WHOISMODES: <client> <nick> :is using modes +<modes>
            val nick = p(1) ?: return null
            t ?: return null
            "$nick $t"
        }
        "760" -> {
            // RPL_WHOISKEYVALUE (draft/metadata-2): <me> <nick> <key> <visibility> :<value>
            // Shown as a WHOIS line, e.g. "alice display-name: Alice". Value is the last
            // parameter so read it via allParams; skip control chars defensively since
            // metadata is attacker-controlled free text.
            val ap = msg.allParams
            val nick = ap.getOrNull(1) ?: return null
            val key = ap.getOrNull(2) ?: return null
            val value = ap.getOrNull(4)?.let { raw -> buildString { for (c in raw) if (c.code >= 0x20 && c.code != 0x7f) append(c) }.trim() }
            if (value.isNullOrEmpty()) tr(R.string.core_whois_metadata_set, nick, key) else "$nick $key: $value"
        }
        "671" -> {
            // RPL_WHOISSECURE: <client> <nick> :is using a secure connection [cipher info]
            val nick = p(1) ?: return null
            if (t != null && t.isNotBlank()) "$nick $t"
            else tr(R.string.core_whois_secure, nick)
        }

        // Common errors
        "401" -> t ?: tr(R.string.core_err_no_such_nick_channel)
        "402" -> t ?: tr(R.string.core_err_no_such_server)
        "403" -> p(1)?.takeIf { it.isNotBlank() }?.let { tr(R.string.core_err_no_such_channel_named, it) } ?: (t ?: tr(R.string.core_err_no_such_channel))
        "404" -> t ?: tr(R.string.core_err_cannot_send_to_channel)
        "406" -> t ?: tr(R.string.core_err_no_such_nickname)
        "421" -> p(1)?.takeIf { it.isNotBlank() }?.let { tr(R.string.core_err_unknown_command_named, it) } ?: (t ?: tr(R.string.core_err_unknown_command))
        "433" -> t ?: tr(R.string.core_err_nick_in_use)
        "442" -> t ?: tr(R.string.core_not_on_channel)
        "461" -> t ?: tr(R.string.core_err_not_enough_params)
        "462" -> t ?: tr(R.string.core_err_may_not_reregister)
        "464" -> t ?: tr(R.string.core_password_incorrect)
        "465" -> t ?: tr(R.string.core_err_banned_from_server)

        // RPL_ADMIN
        "256" -> t ?: tr(R.string.core_admin_info)
        "257" -> t?.let { tr(R.string.core_admin_location, it) }
        "258" -> t?.let { tr(R.string.core_admin_info_line, it) }
        "259" -> t?.let { tr(R.string.core_admin_contact, it) }
        "260" -> t?.let { tr(R.string.core_admin_extended, it) }

        // Generic fallback
        else -> {
            val bodyParts = msg.params.drop(1).map { stripIrcFormatting(it) } + listOfNotNull(t)
            val body = bodyParts.filter { it.isNotBlank() }.joinToString(" ")
            if (body.isNotBlank()) "[$code] $body" else null
        }
    }
}

/**
 * CHANMODES types A, B and C. Before the server has sent CHANMODES, the RFC 2811 defaults: b, e,
 * I and q; k; l.
 */
internal fun IrcClient.chanModeTypes(): Triple<Set<Char>, Set<Char>, Set<Char>> {
    val parts = chanModes?.split(",")
    fun type(i: Int, default: Set<Char>) = parts?.getOrNull(i)?.toSet() ?: default
    return Triple(type(0, setOf('b', 'e', 'I', 'q')), type(1, setOf('k')), type(2, setOf('l')))
}

/**
 * Every change in a MODE line with its argument, consuming arguments by CHANMODES type as
 * [parseChannelUserModes] does: prefix modes and types A and B always, C only when adding.
 */
internal fun IrcClient.parseModeChanges(modeStr: String, args: List<String>): List<ChannelModeChange> {
    val (typeA, typeB, typeC) = chanModeTypes()
    val out = mutableListOf<ChannelModeChange>()
    var adding = true
    var argIdx = 0
    for (c in modeStr) {
        when (c) {
            '+' -> adding = true
            '-' -> adding = false
            else -> {
                val takesArg = c in prefixModeToSymbol || c in typeA || c in typeB || (c in typeC && adding)
                val arg = if (takesArg) args.getOrNull(argIdx).also { argIdx++ } else null
                out += ChannelModeChange(adding, c, arg)
            }
        }
    }
    return out
}

internal fun IrcClient.parseChannelUserModes(
    channel: String,
    modeStr: String,
    args: List<String>
): List<Triple<String, Char?, Boolean>> {
    // Returns (nick, prefix char, adding) for each prefix change, consuming every mode's
    // argument by its CHANMODES type (A and B always, C only when adding, D never; prefix modes
    // always) so arguments stay aligned. Unknown modes count as type D.
    val (typeA, typeB, typeC) = chanModeTypes()
    // typeD: any mode not in A, B, C, or prefix — no parameter needed.

    val results = mutableListOf<Triple<String, Char?, Boolean>>()
    var adding = true
    var argIdx = 0

    for (c in modeStr) {
        when (c) {
            '+' -> adding = true
            '-' -> adding = false
            else -> {
                val prefix = prefixModeToSymbol[c]
                if (prefix != null) {
                    // Prefix mode (op, voice, etc.) — consumes a nick argument.
                    val nick = args.getOrNull(argIdx) ?: continue
                    argIdx++
                    results.add(Triple(nick, prefix, adding))
                } else {
                    // Non-prefix mode — consume its argument (if any) so argIdx
                    // stays aligned for subsequent prefix modes in the same line.
                    val takesArg = when {
                        c in typeA -> true          // list modes always take a param
                        c in typeB -> true          // key mode always takes a param
                        c in typeC -> adding        // limit mode: param only when adding
                        else       -> false         // flag mode: no param
                    }
                    if (takesArg && argIdx < args.size) argIdx++
                    // Do not emit a ChannelUserMode event for non-prefix modes.
                }
            }
        }
    }
    return results
}
