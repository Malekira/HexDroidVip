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

import java.util.Locale

internal fun parseDccSend(textRaw: String): DccOffer? {
// CTCP wrapper: \u0001DCC SEND|TSEND <filename> <ip> <port> <size> [token]\u0001
if (!textRaw.startsWith("\u0001DCC ", ignoreCase = false) || !textRaw.endsWith("\u0001")) return null

val inner = textRaw.removePrefix("\u0001").removeSuffix("\u0001").trim()
if (!inner.startsWith("DCC ", ignoreCase = true)) return null

val afterDcc = inner.drop(3).trimStart() // remove "DCC"
val verbRaw = afterDcc.substringBefore(' ')
val verb = verbRaw.uppercase(Locale.ROOT)
if (verb != "SEND" && verb != "TSEND" && verb != "SSEND") return null
val rest = afterDcc.substring(verbRaw.length).trimStart()
if (rest.isBlank()) return null

// Filename
val (filename, afterName) = if (rest.startsWith('"')) {
    val endQuote = rest.indexOf('"', startIndex = 1)
    if (endQuote <= 0) return null
    rest.substring(1, endQuote) to rest.substring(endQuote + 1).trim()
} else {
    val firstSpace = rest.indexOf(' ')
    if (firstSpace <= 0) return null
    rest.substring(0, firstSpace) to rest.substring(firstSpace + 1).trim()
}

val parts = afterName.split(Regex("\\s+")).filter { it.isNotBlank() }
if (parts.size < 3) return null

val ipField = parts[0]
val port = parts[1].toIntOrNull()?.takeIf { it in 0..65535 } ?: return null
val size = parts[2].toLongOrNull()?.takeIf { it >= 0L } ?: 0L

// SSEND: TLS-wrapped transfer.
val secure = verb == "SSEND"

// Turbo DCC:
// - If TSEND, receiver SHOULD NOT send ACKs.
// - Some clients append 'T' to the reverse-DCC token to signal turbo.
var turbo = verb == "TSEND"
var token: Long? = null
parts.getOrNull(3)?.let { tokRaw ->
    val t = tokRaw.trim()
    val hasT = t.endsWith("T", ignoreCase = true)
    val numeric = if (hasT) t.dropLast(1) else t
    turbo = turbo || hasT
    token = numeric.toLongOrNull()
}

val ip = parseDccAddress(ipField) ?: return null
// Reject malformed passive offers: a port=0 SEND is only meaningful with a token
// (reverse DCC handshake). Without one we'd dispatch it to receive() which immediately
// throws on the port>0 require(), surfacing a confusing error to the user. Drop them
// silently. the sender's client is misbehaving and the user can't do anything useful
// with a port=0/no-token offer anyway.
if (port == 0 && token == null) return null
return DccOffer(from = "?", filename = filename, ip = ip, port = port, size = size, token = token, turbo = turbo, secure = secure)
}

internal fun parseDccResume(textRaw: String): DccResume? {
val p = parseDccResumeLike(textRaw, "RESUME") ?: return null
return DccResume(filename = p.filename, port = p.port, position = p.position, token = p.token)
}

internal fun parseDccAccept(textRaw: String): DccAccept? {
val p = parseDccResumeLike(textRaw, "ACCEPT") ?: return null
return DccAccept(filename = p.filename, port = p.port, position = p.position, token = p.token)
}

private data class DccResumeLikePayload(val filename: String, val port: Int, val position: Long, val token: Long?)

/**
* Parse DCC RESUME or DCC ACCEPT (same format: `<filename> <port> <position> [token]`, filename
* optionally quoted; passive resume has port 0 and a token). Null unless the line is a
* well-formed [expectedVerb].
*/
private fun parseDccResumeLike(textRaw: String, expectedVerb: String): DccResumeLikePayload? {
if (!textRaw.startsWith("\u0001DCC ", ignoreCase = false) || !textRaw.endsWith("\u0001")) return null

val inner = textRaw.removePrefix("\u0001").removeSuffix("\u0001").trim()
if (!inner.startsWith("DCC ", ignoreCase = true)) return null

val afterDcc = inner.drop(3).trimStart() // remove "DCC"
val verbRaw = afterDcc.substringBefore(' ')
if (!verbRaw.equals(expectedVerb, ignoreCase = true)) return null

val rest = afterDcc.substring(verbRaw.length).trimStart()
if (rest.isBlank()) return null

// Filename (quoted or unquoted) — same logic as parseDccSend.
val (filename, afterName) = if (rest.startsWith('"')) {
    val endQuote = rest.indexOf('"', startIndex = 1)
    if (endQuote <= 0) return null
    rest.substring(1, endQuote) to rest.substring(endQuote + 1).trim()
} else {
    val firstSpace = rest.indexOf(' ')
    if (firstSpace <= 0) return null
    rest.substring(0, firstSpace) to rest.substring(firstSpace + 1).trim()
}

val parts = afterName.split(Regex("\\s+")).filter { it.isNotBlank() }
if (parts.size < 2) return null

val port = parts[0].toIntOrNull() ?: return null
val position = parts[1].toLongOrNull() ?: return null
if (port < 0 || position < 0L) return null

val token: Long? = parts.getOrNull(2)?.toLongOrNull()
// Passive RESUME/ACCEPT (port==0) MUST carry a token. Active form (port>0) may or
// may not carry one — some clients echo the token even on active resume to be
// helpful. Either is fine.
if (port == 0 && token == null) return null

return DccResumeLikePayload(filename = filename, port = port, position = position, token = token)
}

internal fun parseDccChat(textRaw: String): DccChatOffer? {
// CTCP wrapper: \u0001DCC CHAT|SCHAT <proto> <ip> <port>\u0001
if (!textRaw.startsWith("\u0001DCC ") || !textRaw.endsWith("\u0001")) return null

val inner = textRaw.removePrefix("\u0001").removeSuffix("\u0001").trim()
if (!inner.startsWith("DCC ", ignoreCase = true)) return null

val afterDcc = inner.drop(3).trimStart() // remove "DCC"
val verbRaw = afterDcc.substringBefore(' ')
val verb = verbRaw.uppercase(Locale.ROOT)
if (verb != "CHAT" && verb != "SCHAT") return null

// SCHAT: TLS-wrapped chat session.
val secure = verb == "SCHAT"

val rest = afterDcc.substring(verbRaw.length).trimStart()
val parts = rest.split(Regex("\\s+")).filter { it.isNotBlank() }
if (parts.size < 3) return null

val proto = parts[0]
val ipField = parts[1]
val port = parts[2].toIntOrNull() ?: return null
// Passive (port 0) chat is not supported.
if (port !in 1..65535) return null
val ip = parseDccAddress(ipField) ?: return null

return DccChatOffer(from = "?", protocol = proto, ip = ip, port = port, secure = secure)
}

private fun ipFromLong(v: Long): String {
val b1 = (v shr 24) and 255
val b2 = (v shr 16) and 255
val b3 = (v shr 8) and 255
val b4 = v and 255
return "$b1.$b2.$b3.$b4"
}

/**
* Parse a DCC offer's address field: a 32-bit integer, a dotted IPv4 quad or an IPv6 literal.
* Hostnames are rejected, so a crafted offer can't trigger a DNS lookup that would leak outside
* a SOCKS/Tor tunnel.
*/
internal fun parseDccAddress(ipField: String): String? {
val f = ipField.trim()
// IPv6 literal. May arrive bracketed (some clients wrap it in [ ]); strip those.
if (f.contains(':')) {
    val literal = f.removePrefix("[").removeSuffix("]")
    return if (isIpv6Literal(literal)) literal else null
}
if (f.contains('.')) {
    // Dotted quad: exactly four octets of one to three ASCII digits, each 0..255.
    val octets = f.split('.')
    if (octets.size != 4) return null
    if (octets.any { it.length !in 1..3 || !it.all { c -> c.isAsciiDigit() } || it.toInt() > 255 }) return null
    return f
}
// Classic 32-bit integer form (IPv4 only).
if (f.isEmpty() || !f.all { it.isAsciiDigit() }) return null
val v = f.toLongOrNull() ?: return null
if (v !in 0L..0xFFFFFFFFL) return null
return ipFromLong(v)
}

/**
* Syntactic IPv6 literal check with no name resolution. Accepts "::" compression and a trailing
* dotted IPv4 part; rejects scope ids. [validateRemoteIp] still screens the address afterwards.
*/
private fun isIpv6Literal(s: String): Boolean {
if (s.isEmpty() || s.length > 45 || s.contains('%')) return false

// Detach an optional embedded IPv4 tail and validate it; replace with two synthetic
// hex groups so the remainder can be counted as plain 16-bit groups below.
var work = s
val lastColon = s.lastIndexOf(':')
if (lastColon >= 0 && s.substring(lastColon + 1).contains('.')) {
    val v4 = s.substring(lastColon + 1)
    val octets = v4.split('.')
    if (octets.size != 4) return false
    for (p in octets) {
        if (p.isEmpty() || p.length > 3 || p.any { !it.isAsciiDigit() }) return false
        if ((p.toIntOrNull() ?: -1) !in 0..255) return false
    }
    work = s.substring(0, lastColon) + ":0:0"
}

// At most one "::".
val dc = work.indexOf("::")
if (dc != work.lastIndexOf("::")) return false

fun countGroups(part: String): Int? {
    if (part.isEmpty()) return 0
    val groups = part.split(':')
    for (g in groups) {
        if (g.isEmpty() || g.length > 4 || g.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
    }
    return groups.size
}

return if (dc >= 0) {
    val l = countGroups(work.substring(0, dc)) ?: return false
    val r = countGroups(work.substring(dc + 2)) ?: return false
    // "::" stands in for >= 1 zero group, so the explicit groups must total < 8.
    l + r <= 7
} else {
    countGroups(work) == 8
}
}
