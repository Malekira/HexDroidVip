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

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale

/**
 * Case-fold [s] under the ISUPPORT CASEMAPPING [caseMapping], shared by IrcClient.casefold() and
 * the ViewModel. rfc1459 and strict-rfc1459 fold A-Z and the []\\{}| pairs (plain rfc1459 also ^
 * and ~); ascii folds A-Z only; anything else uses full Unicode lowercasing plus the rfc1459 pairs.
 */
internal fun ircCasefold(s: String, caseMapping: String): String {
    val cm = caseMapping.lowercase(Locale.ROOT)
    val sb = StringBuilder(s.length)
    for (ch0 in s) {
        var ch = ch0
        if (ch in 'A'..'Z') ch = (ch.code + 32).toChar()
        when (cm) {
            "rfc1459", "strict-rfc1459" -> {
                ch = when (ch) {
                    '[', '{' -> '{'
                    ']', '}' -> '}'
                    '\\', '|' -> '|'
                    else -> ch
                }
                if (cm == "rfc1459" && (ch == '^' || ch == '~')) ch = '~'
            }
            "ascii" -> { /* ASCII A-Z already handled above */ }
            else -> {
                ch = ch.lowercaseChar()
                ch = when (ch) {
                    '[', '{' -> '{'
                    ']', '}' -> '}'
                    '\\', '|' -> '|'
                    '^', '~' -> '~'
                    else -> ch
                }
            }
        }
        sb.append(ch)
    }
    return sb.toString()
}

/** Parsed `draft/multiline=max-bytes=4096,max-lines=24` cap value. */
internal data class MultilineLimits(val maxBytes: Int, val maxLines: Int)

/**
 * Parse the multiline cap value. A missing, zero or absurd value means "the server
 * imposes no limit", in which case we still apply a sane one of our own so a hostile
 * or buggy advertisement can't make us build a single batch out of a 50 MB paste.
 */
internal fun parseMultilineLimits(raw: String?): MultilineLimits {
    var bytes = 0
    var lines = 0
    for (tok in (raw ?: "").split(',')) {
        val k = tok.substringBefore('=').trim().lowercase(Locale.ROOT)
        val v = tok.substringAfter('=', "").trim().toIntOrNull() ?: continue
        when (k) {
            "max-bytes" -> bytes = v
            "max-lines" -> lines = v
        }
    }
    return MultilineLimits(
        maxBytes = if (bytes in 1..1_048_576) bytes else 4096,
        maxLines = if (lines in 1..1000) lines else 24,
    )
}

/** Nicknames that reach a network service, lowercased. */
private val SERVICE_NICKS = setOf(
    "nickserv", "chanserv", "authserv", "hostserv", "operserv", "botserv", "memoserv",
    "ns", "cs", "hs", "os", "bs", "ms", "x", "q", "l", "w",
)

/** Service subcommands whose arguments carry a password. */
private val SERVICE_SECRET_CMDS = setOf(
    "IDENTIFY", "REGISTER", "SETPASS", "GHOST", "RECOVER", "RELEASE",
    "LOGIN", "AUTH", "PASS", "SASLPASS", "CONFIRM", "RESETPASS",
)

/**
 * Strip credentials from a raw protocol line before it is shown or copied.
 */
internal fun redactRawLine(line: String): String {
    // Tags are never secret and can contain spaces in values, so split them off first.
    val tagPrefix: String
    val rest: String
    if (line.startsWith("@")) {
        val sp = line.indexOf(' ')
        if (sp < 0) return line
        tagPrefix = line.substring(0, sp + 1)
        rest = line.substring(sp + 1)
    } else {
        tagPrefix = ""
        rest = line
    }
    val body = if (rest.startsWith(":")) rest.substringAfter(' ', "") else rest
    val leader = if (rest.startsWith(":")) rest.substring(0, rest.length - body.length) else ""
    val rawVerb = body.substringBefore(' ').uppercase(Locale.ROOT)
    val rawArgs = body.substringAfter(' ', "")

    // MSG, SQUERY and the NICKSERV-as-verb aliases are rewritten to the PRIVMSG form.
    val aliasService = rawVerb.lowercase(Locale.ROOT).takeIf { it in SERVICE_NICKS }
    val verb = when {
        aliasService != null -> "PRIVMSG"
        rawVerb == "MSG" || rawVerb == "SQUERY" -> "PRIVMSG"
        else -> rawVerb
    }
    val args = when {
        aliasService != null -> "$rawVerb $rawArgs"
        else -> rawArgs
    }

    fun hide(keep: String) = tagPrefix + leader + rawVerb + (if (keep.isEmpty()) "" else " $keep") + " <hidden>"

    return when {
        // PASS <password>, and the bouncer form PASS <user>/<network>:<password>.
        verb == "PASS" -> hide("")
        // AUTHENTICATE <base64>. "+" and "*" are protocol markers and the first line is
        // the mechanism name, all of which are worth seeing; only the payload is secret.
        verb == "AUTHENTICATE" && args.isNotBlank() && args != "+" && args != "*" &&
            !(args.length <= 20 && args.all { it.isDigit() || it in 'A'..'Z' || it == '-' }) -> hide("")
        // OPER <name> <password>.
        verb == "OPER" -> hide(args.substringBefore(' '))
        // Services logins sent as a normal message: PRIVMSG NickServ :IDENTIFY <pw>.
        verb == "PRIVMSG" || verb == "NOTICE" -> {
            val target = args.substringBefore(' ')
            val text = args.substringAfter(' ', "").removePrefix(":")
            val svc = target.substringBefore('@').lowercase(Locale.ROOT)
            val cmd = text.substringBefore(' ').uppercase(Locale.ROOT)
            val sub = text.substringAfter(' ', "").substringBefore(' ').uppercase(Locale.ROOT)
            val secretCmd = cmd in SERVICE_SECRET_CMDS || (cmd == "SET" && sub in setOf("PASSWORD", "PASS"))
            when {
                svc !in SERVICE_NICKS || !secretCmd -> line
                aliasService != null -> "$tagPrefix$leader$rawVerb $cmd <hidden>"
                else -> "$tagPrefix$leader$rawVerb $target :$cmd <hidden>"
            }
        }
        // draft/account-registration.
        verb == "REGISTER" || verb == "VERIFY" -> hide(args.substringBefore(' '))
        // soju.im/webpush. The endpoint is a bearer capability: anyone holding it can
        // push to this device, and the p256dh/auth keys let them encrypt a payload it
        // will decrypt and show as a notification. Keep only the subcommand.
        verb == "WEBPUSH" -> hide(args.substringBefore(' '))
        // The server echoes the endpoint back as context on FAIL/WARN/NOTE for WEBPUSH,
        // so the same secret arrives inbound. Keep the shape that identifies the problem
        // (WEBPUSH <code> <subcommand>) and drop everything after it.
        (verb == "FAIL" || verb == "WARN" || verb == "NOTE") &&
            args.substringBefore(' ').equals("WEBPUSH", true) ->
            hide(args.split(' ').take(3).joinToString(" "))
        else -> line
    }
}

/** Vendor client-only tag for read receipts in private messages. Value: the msgid read up to. */
internal const val READ_RECEIPT_TAG = "hexdroid.org/read"

/** Split [text] into pieces of at most [maxBytes] UTF-8 bytes, never mid-codepoint. */
internal fun splitByUtf8Bytes(text: String, maxBytes: Int): List<String> {
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return listOf(text)
    val out = mutableListOf<String>()
    var off = 0
    while (off < bytes.size) {
        var len = minOf(maxBytes, bytes.size - off)
        // Back off to a codepoint boundary: continuation bytes are 10xxxxxx.
        while (len > 1 && off + len < bytes.size && (bytes[off + len].toInt() and 0xC0) == 0x80) len--
        out += String(bytes, off, len, Charsets.UTF_8)
        off += len
    }
    return out
}

/**
 * Build the lines that send [lines] to [target] as multiline BATCHes. An input line too long for
 * one PRIVMSG is split with `+draft/multiline-concat`. A message larger than one batch's
 * max-lines/max-bytes becomes consecutive batches; [openTags] (label, reply tag) go on the first
 * only.
 */
/**
 * The (label, text) of each multiline batch in [wire], as [buildMultilineWireLines] writes them:
 * the label from the BATCH open's tags, and the text joined as the receiving side joins it, with
 * a line feed before each line not tagged as a continuation.
 */
internal fun multilineBatchTexts(wire: List<String>): List<Pair<String?, String>> {
    val out = mutableListOf<Pair<String?, String>>()
    var label: String? = null
    var text: StringBuilder? = null
    var parts = 0
    for (line in wire) {
        val tags = if (line.startsWith("@")) line.substring(1).substringBefore(' ') else ""
        val rest = if (line.startsWith("@")) line.substringAfter(' ', "") else line
        when {
            rest.startsWith("BATCH +") -> {
                label = tags.split(';').firstOrNull { it.startsWith("label=") }?.substringAfter('=')
                text = StringBuilder()
                parts = 0
            }
            rest.startsWith("BATCH -") -> {
                text?.let { out += label to it.toString() }
                label = null
                text = null
            }
            text != null -> {
                val payload = rest.substringAfter(" :", "")
                val concat = tags.split(';').any { it.endsWith("multiline-concat") }
                if (parts > 0 && !concat) text.append('\n')
                text.append(payload)
                parts++
            }
        }
    }
    return out
}

internal fun buildMultilineWireLines(
    target: String,
    lines: List<String>,
    limits: MultilineLimits,
    perLineBytes: Int,
    batchType: String,
    concatTag: String,
    nextBatchId: () -> String,
    openTagsFor: () -> List<String>,
): List<String> {
    if (lines.isEmpty()) return emptyList()

    // Expand input lines into (payload, isContinuationOfPreviousLine) pairs.
    val inner = mutableListOf<Pair<String, Boolean>>()
    for (raw in lines) {
        val line = raw.replace("\r", "").replace("\n", "")
        if (line.isEmpty()) { inner += "" to false; continue }
        var first = true
        for (piece in splitByUtf8Bytes(line, perLineBytes)) {
            inner += piece to !first
            first = false
        }
    }

    // Spec: a message may not consist entirely of blank lines, and no line feed is
    // appended after the last line, so leading/trailing blanks carry nothing.
    while (inner.isNotEmpty() && inner.first().first.isEmpty()) inner.removeAt(0)
    while (inner.isNotEmpty() && inner.last().first.isEmpty()) inner.removeAt(inner.size - 1)
    if (inner.isEmpty()) return emptyList()

    val out = mutableListOf<String>()
    var i = 0
    while (i < inner.size) {
        var bytes = 0
        var end = i
        while (end < inner.size && end - i < limits.maxLines) {
            // A line after the first is joined by a line feed unless it continues the previous one.
            val joiner = if (end > i && !inner[end].second) 1 else 0
            val b = inner[end].first.toByteArray(Charsets.UTF_8).size + joiner
            if (end > i && bytes + b > limits.maxBytes) break
            bytes += b
            end++
        }
        // Never cut inside a split line: the first message of a batch cannot carry the
        // concat tag, so the remainder would arrive as a separate message.
        if (end < inner.size && inner[end].second) {
            var back = end
            while (back > i && inner[back].second) back--
            if (back > i) end = back
        }
        if (end == i) end = i + 1  // never stall on a single over-budget line
        // Never emit a batch that is entirely blank lines.
        while (end < inner.size && (i until end).all { inner[it].first.isEmpty() }) end++

        val batchId = nextBatchId()
        val tags = openTagsFor()
        val tag = if (tags.isEmpty()) "" else tags.joinToString(";", prefix = "@", postfix = " ")
        out += "${tag}BATCH +$batchId $batchType $target"
        for (j in i until end) {
            val (payload, concat) = inner[j]
            // j > i: an unavoidable mid-run cut (a single line longer than max-lines
            // pieces) drops the tag rather than emitting an invalid leading concat.
            val concatPart = if (concat && j > i) ";$concatTag" else ""
            out += "@batch=$batchId$concatPart PRIVMSG $target :$payload"
        }
        out += "BATCH -$batchId"
        i = end
    }
    return out
}

/**
 * Parse the attributes of `BOUNCER NETWORK <id> <attrs>`, given as `key=value` tokens. A lone `*`
 * means the network was removed.
 */
internal fun parseBouncerNetworkAttrs(
    networkId: String,
    attrTokens: List<String>
): IrcEvent.BouncerNetwork {
    if (attrTokens.size == 1 && attrTokens[0] == "*") {
        return IrcEvent.BouncerNetwork(networkId, removed = true)
    }
    val attrs = LinkedHashMap<String, String>()
    val cleared = mutableSetOf<String>()
    for (tok in attrTokens) {
        if (tok.isEmpty()) continue
        val eq = tok.indexOf('=')
        // A bare key is how message tags spell an empty value, so it clears too.
        val key = (if (eq < 0) tok else tok.substring(0, eq)).lowercase(Locale.ROOT)
        if (key.isEmpty() || key in attrs || key in cleared) continue
        val rawValue = if (eq < 0) "" else tok.substring(eq + 1)
        if (rawValue.isEmpty()) cleared += key else attrs[key] = unescapeIrcTagValue(rawValue)
    }
    return IrcEvent.BouncerNetwork(networkId, attrs, clearedKeys = cleared)
}

/**
 * Encode bouncer network attributes for `BOUNCER ADDNETWORK` / `CHANGENETWORK`: message-tag
 * syntax, one `key=value` per entry joined by `;`. An empty value is written as a bare `key=`,
 * which clears it.
 */
internal fun encodeBouncerNetworkAttrs(attrs: Map<String, String>): String =
    attrs.entries.joinToString(";") { (k, v) -> "$k=${escapeIrcTagValue(v)}" }

/**
 * Parse one row of ZNC's `*status ListNetworks` table into a [IrcEvent.BouncerNetwork], or null if
 * it isn't a network row. A row starts and ends with `|`, has at least four cells and "Yes" or "No"
 * in the second. The network name serves as id and name; the server cell becomes the host, and
 * Yes/No become connected/disconnected.
 */
internal fun parseZncListNetworksLine(line: String): IrcEvent.BouncerNetwork? {
    val trimmed = line.trim()
    if (!trimmed.startsWith("|") || !trimmed.endsWith("|")) return null

    // Strip the outer `|` chars so split doesn't produce empty leading/trailing cells.
    val inner = trimmed.substring(1, trimmed.length - 1)
    val cells = inner.split('|').map { it.trim() }
    if (cells.size < 4) return null

    val name = cells[0]
    val onirc = cells[1]
    val server = cells[2]
    // cells[3] = "IRC User" (nick!ident@host) currently unused, see kdoc.

    if (name.isEmpty() || name.equals("Network", ignoreCase = true)) return null
    if (!onirc.equals("Yes", ignoreCase = true) && !onirc.equals("No", ignoreCase = true)) return null

    // ZNC's IRC Server cell looks like `server:+6697`, where `+` marks TLS. An unfamiliar
    // shape is kept whole as the host rather than dropping the row.
    val attrs = LinkedHashMap<String, String>()
    attrs["name"] = name
    if (server.isNotEmpty()) {
        val colon = server.lastIndexOf(':')
        val portPart = if (colon > 0) server.substring(colon + 1) else ""
        val port = portPart.removePrefix("+").toIntOrNull()
        if (port != null) {
            attrs["host"] = server.substring(0, colon)
            attrs["port"] = port.toString()
            attrs["tls"] = if (portPart.startsWith("+")) "1" else "0"
        } else {
            attrs["host"] = server
        }
    }
    attrs["state"] = if (onirc.equals("Yes", ignoreCase = true)) "connected" else "disconnected"

    // ZNC has no opaque netid; the name doubles as the stable id.
    return IrcEvent.BouncerNetwork(networkId = name, attrs = attrs)
}

/**
 * Resolve [host] to all its addresses with a hard time limit, since the platform resolvers have
 * none. On timeout the resolver thread is abandoned and an UnknownHostException follows the normal
 * connect-failure path. IP literals return immediately.
 */
internal fun resolveAllWithTimeout(
    host: String,
    network: android.net.Network?,
    timeoutMs: Int,
): Array<InetAddress> {
    val task = java.util.concurrent.FutureTask<Array<InetAddress>> {
        network?.getAllByName(host) ?: InetAddress.getAllByName(host)
    }
    Thread(task, "irc-dns-resolve").apply { isDaemon = true; start() }
    return try {
        task.get(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (te: java.util.concurrent.TimeoutException) {
        task.cancel(true)
        throw java.net.UnknownHostException(
            "DNS resolution timed out after ${timeoutMs / 1000}s (resolver unresponsive) for $host"
        )
    } catch (ee: java.util.concurrent.ExecutionException) {
        throw ee.cause ?: ee
    }
}

/**
 * Decode the \xHH escapes RPL_ISUPPORT values use for space, '=' and backslash. A malformed
 * escape is kept as written.
 */
internal fun unescapeIsupportValue(value: String): String {
    if (!value.contains("\\x")) return value
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '\\' && i + 3 < value.length && value[i + 1] == 'x') {
            val hi = Character.digit(value[i + 2], 16)
            val lo = Character.digit(value[i + 3], 16)
            if (hi >= 0 && lo >= 0) {
                out.append((hi * 16 + lo).toChar())
                i += 4
                continue
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}
