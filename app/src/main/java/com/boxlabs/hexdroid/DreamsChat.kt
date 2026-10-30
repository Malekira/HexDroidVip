/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import java.net.URI

/** Only the shorthand changes; explicit JOIN and server channel prefixes stay intact. */
internal fun normalizeJoinAlias(command: String, channelTypes: String = "#&!+"): String {
    val parts = command.trim().split(Regex("\\s+"), limit = 3)
    if (!parts.first().equals("j", true)) return command
    if (parts.size < 2) return "join"
    val channels = parts[1].split(',').joinToString(",") {
        if (it.firstOrNull()?.let { prefix -> prefix in channelTypes } == true || it == "0") it else "#$it"
    }
    return "join $channels" + (parts.getOrNull(2)?.let { " $it" } ?: "")
}

internal fun validAvatarUrl(value: String): Boolean = value.isBlank() || runCatching {
    val uri = URI(value)
    value.length <= 512 && value.none { it.isWhitespace() || it.isISOControl() } &&
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawFragment == null
}.getOrDefault(false)

internal fun updatedUserModes(current: String, changes: String, snapshot: Boolean = false): String {
    val result = if (snapshot) mutableSetOf<Char>() else current.toSet().toMutableSet()
    var adding = true
    for (ch in changes.substringBefore(' ')) when (ch) {
        '+' -> adding = true
        '-' -> adding = false
        else -> if (ch.isLetter()) { if (adding) result.add(ch) else result.remove(ch) }
    }
    return result.sorted().joinToString("")
}

internal fun privateModeCommand(connected: Boolean, ownNick: String, mode: Char, enabled: Boolean,
                                supported: String, callerId: Boolean, current: String): String {
    check(connected) { "Red desconectada" }
    require(isReplyNick(ownNick)) { "Nick propio no disponible" }
    require(mode == 'g' || mode == 'R') { "Modo no válido" }
    require(mode in supported || (mode == 'g' && callerId) || (!enabled && mode in current)) {
        "La red no anunció soporte para +$mode"
    }
    return "MODE $ownNick ${if (enabled) '+' else '-'}$mode"
}

data class PrivateAttemptAlert(val id: Long, val network: String, val nick: String, val text: String)

/** Session-only composer recall; credentials and slash commands are deliberately not retained. */
internal fun mayRecallInput(text: String) = text.isNotBlank() &&
    !stripIrcFormatting(text).trimStart().startsWith('/') &&
    !Regex("(?i)(?:password|passwd|token|secret|contraseña)\\s*[:=]").containsMatchIn(text)
