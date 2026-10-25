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

import com.boxlabs.hexdroid.IrcViewModel.NetRuntime
import com.boxlabs.hexdroid.data.IgnoreRules
import com.boxlabs.hexdroid.data.IgnoreType
import com.boxlabs.hexdroid.data.NetworkProfile

internal fun IrcViewModel.isRecentEvent(timeMs: Long): Boolean {
    val now = System.currentTimeMillis()
    // 30s window should cover clock skew + batching without letting real playback mutate state.
    return timeMs >= (now - 30_000L) && timeMs <= (now + 30_000L)
}

// History events only affect live state if their timestamp is within 30s of now.
// Some bouncers mis-tag live messages as history, but those carry a recent @time.
internal fun IrcViewModel.shouldAffectLiveState(isHistory: Boolean, timeMs: Long?): Boolean =
    if (!isHistory) true else (timeMs != null && isRecentEvent(timeMs))

/**
 * Mirror a single nick's away transition into NetConnState.awayNicks (keyed by
 * lowercased original nick, matching how the member list looks them up). Kept in
 * step with nickAwayState, which stays casefolded and also holds the away message.
 */
internal fun IrcViewModel.markAwayInState(netId: String, nick: String, away: Boolean) {
    val key = nick.lowercase()
    setNetConn(netId) { st ->
        if (away) {
            if (key in st.awayNicks) st else st.copy(awayNicks = st.awayNicks + key)
        } else {
            if (key !in st.awayNicks) st else st.copy(awayNicks = st.awayNicks - key)
        }
    }
}

/** Drop all away flags for a network (used when the away map is bulk-cleared). */
internal fun IrcViewModel.clearAwayNicksInState(netId: String) {
    setNetConn(netId) { st -> if (st.awayNicks.isEmpty()) st else st.copy(awayNicks = emptySet()) }
}

/**
 * Forget the draft/metadata-2 values held for [nick] on [netId].
 *
 * Metadata is keyed by nick, but a nick is only ever on loan: once its holder quits, the
 * next person to take it inherits whatever the last one had set. Called when a nick is
 * given up rather than carried to a new one.
 */
internal fun IrcViewModel.dropMetadataForNick(netId: String, nick: String) {
    val key = nick.lowercase()
    setNetConn(netId) { st ->
        if (key !in st.displayNames && key !in st.avatarUrls && key !in st.nickColors &&
            key !in st.statuses && key !in st.extraMetadata
        ) return@setNetConn st
        st.copy(
            displayNames = st.displayNames - key,
            avatarUrls = st.avatarUrls - key,
            nickColors = st.nickColors - key,
            statuses = st.statuses - key,
            extraMetadata = st.extraMetadata - key,
        )
    }
}

/**
 * Carry [from]'s metadata to [to], for the same person changing nick.
 *
 * Pure, because the nick-change handler assembles a whole [UiState] and assigns it at the
 * end. A write of its own in the middle of that would be discarded by the assignment.
 */
internal fun IrcViewModel.withMetadataMovedForNick(st: NetConnState, from: String, to: String): NetConnState {
    val a = from.lowercase()
    val b = to.lowercase()
    if (a == b) return st
    if (a !in st.displayNames && a !in st.avatarUrls && a !in st.nickColors &&
        a !in st.statuses && a !in st.extraMetadata
    ) return st
    fun <V> move(m: Map<String, V>): Map<String, V> =
        m[a]?.let { (m - a) + (b to it) } ?: (m - a)
    return st.copy(
        displayNames = move(st.displayNames),
        avatarUrls = move(st.avatarUrls),
        nickColors = move(st.nickColors),
        statuses = move(st.statuses),
        extraMetadata = move(st.extraMetadata),
    )
}

/** Record a nick as a bot (from WHO/WHOX flags or a `bot` message tag). */
internal fun IrcViewModel.markBotInState(netId: String, nick: String) {
    val key = nick.lowercase()
    setNetConn(netId) { st ->
        if (key in st.botNicks) st else st.copy(botNicks = st.botNicks + key)
    }
}

internal fun IrcViewModel.canonicalIgnoreNick(raw: String): String? {
    val t = raw.trim()
    if (t.isBlank()) return null
    val token = t.split(Regex("\\s+"), limit = 2).firstOrNull()?.trim() ?: return null
    // If it looks like a mask (nick!user@host or *!*@host), keep it as-is (trimmed).
    if (token.contains('!') || token.contains('@') || token.contains('*') || token.contains('?')) {
        return token.takeIf { it.isNotBlank() }
    }
    // Plain nick: strip mode prefix and trailing punctuation.
    val base = token.trimEnd(':', ',').trimStart('~','&','@','%','+')
    if (base.isBlank() || base == "." || base == "..") return null
    val cleaned = base.replace(Regex("[\u0000-\u001F\u007F]"), "").trim()
    return cleaned.takeIf { it.isNotBlank() }
}

/**
 * True when an ignore entry on [netId] matches [nick] and drops [type]. [userHost] is the
 * sender's `user@host` where the event carries it; otherwise the last one seen is used.
 */
internal fun IrcViewModel.isIgnoredSender(netId: String, nick: String?, type: IgnoreType, userHost: String? = null): Boolean {
    val n = nick?.trim().takeIf { !it.isNullOrBlank() } ?: return false
    val net = _state.value.networks.firstOrNull { it.id == netId } ?: return false
    if (net.ignoredNicks.isEmpty()) return false
    val uh = userHost?.takeIf { it.contains('@') && !it.startsWith("*") } ?: runtimes[netId]?.client?.userHostOf(n)
    return IgnoreRules.ignores(net.ignoredNicks, net.ignoreLevels, n, uh, type)
}

/**
 * True when [nick]'s messages on [netId] should NOT raise a highlight or PM
 * notification, per NetworkProfile.highlightIgnoreMasks. Unlike isIgnoredSender (which
 * drops the message entirely), this only suppresses the *alert* — the message still
 * lands in its buffer. Matches the bare nick (status prefixes stripped) against each
 * mask as a regex (`/.../`), an IRC glob (`*`/`?`), or a plain case-insensitive nick.
 */
internal fun IrcViewModel.isNotifyIgnoredSender(netId: String, nick: String?): Boolean {
    val masks = _state.value.networks.firstOrNull { it.id == netId }?.highlightIgnoreMasks.orEmpty()
    return com.boxlabs.hexdroid.data.NotifyMasks.mutesNick(masks, nick)
}

internal fun IrcViewModel.updateNetworkInState(updated: com.boxlabs.hexdroid.data.NetworkProfile) {
    val st = _state.value
    val next = st.networks.map { if (it.id == updated.id) updated else it }
    _state.value = st.copy(networks = next)
}

/**
 * True when [nick] is on this network's DCC auto-accept list.
 */
internal fun IrcViewModel.isDccAutoAccepted(netId: String, nick: String?): Boolean {
    val n = nick?.trim().orEmpty()
    if (n.isBlank()) return false
    return _state.value.networks.firstOrNull { it.id == netId }
        ?.dccAutoAcceptNicks.orEmpty()
        .any { it.equals(n, ignoreCase = true) }
}

/** Adds [channel] to the reconnect rejoin list, keeping a key already known for it when [key] is null. */
internal fun IrcViewModel.rememberManualJoin(netId: String, rt: NetRuntime, channel: String, key: String?) {
    val known = forgetManualJoin(netId, rt, channel)
    rt.manuallyJoinedChannels[channel] = key ?: known
}

/** Removes [channel] from the reconnect rejoin list in any case. Returns the key it had. */
internal fun IrcViewModel.forgetManualJoin(netId: String, rt: NetRuntime, channel: String): String? {
    val fold = casefoldText(netId, channel)
    var key: String? = null
    val it = rt.manuallyJoinedChannels.entries.iterator()
    while (it.hasNext()) {
        val e = it.next()
        if (casefoldText(netId, e.key) == fold) {
            key = key ?: e.value
            it.remove()
        }
    }
    return key
}

/** The key set for [channel] in the network's auto-join list, if any. */
internal fun IrcViewModel.autoJoinKey(netId: String, channel: String): String? {
    val fold = casefoldText(netId, channel)
    return _state.value.networks.firstOrNull { it.id == netId }?.autoJoin
        ?.firstOrNull { casefoldText(netId, it.channel.split(",")[0].trim()) == fold }
        ?.key?.takeIf { it.isNotBlank() }
}

// Nicklist helpers (multi-status + CASEMAPPING aware)

/**
 * Stable casefold for tracking in-flight /NAMES requests.
 *
 * We intentionally do NOT use casefoldText(netId, ...) here because CASEMAPPING (005) can arrive mid-request.
 * If folding rules change between the 353 and 366 numerics, the request key won't match, and the nicklist will
 * never get an initial snapshot (you'll only see users who join after you).
 */
internal fun IrcViewModel.namesKeyFold(channel: String): String {
    val sb = StringBuilder(channel.length)
    for (ch0 in channel) {
        var ch = ch0
        if (ch in 'A'..'Z') ch = (ch.code + 32).toChar()
        ch = when (ch) {
            '[', '{' -> '{'
            ']', '}' -> '}'
            '\\', '|' -> '|'
            '^', '~' -> '~'
            else -> ch
        }
        sb.append(ch)
    }
    return sb.toString()
}

/**
 * Casefold [s] using the CASEMAPPING advertised by the given network's ISUPPORT 005.
 *
 * Delegates to [ircCasefold], the same function IrcCore folds with when it routes
 * incoming messages, so a buffer key computed here can never disagree with one
 * computed there. Defaults to rfc1459 before 005 arrives, as most ircds do.
 */
internal fun IrcViewModel.casefoldText(netId: String, s: String): String =
    ircCasefold(s, runtimes[netId]?.support?.caseMapping ?: "rfc1459")

internal fun IrcViewModel.prefixModes(netId: String): String = runtimes[netId]?.support?.prefixModes ?: "qaohv"

internal fun IrcViewModel.prefixSymbols(netId: String): String = runtimes[netId]?.support?.prefixSymbols ?: "~&@%+"

internal fun IrcViewModel.parseNickWithPrefixes(netId: String, display: String): Pair<String, Set<Char>> {
    val ps = prefixSymbols(netId)
    val pm = prefixModes(netId)
    var i = 0
    val modes = linkedSetOf<Char>()
    while (i < display.length && ps.indexOf(display[i]) >= 0) {
        val idx = ps.indexOf(display[i])
        if (idx in 0 until pm.length) modes.add(pm[idx])
        i++
    }
    val base = display.substring(i)
    return base to modes
}

internal fun IrcViewModel.modeForPrefixSymbol(netId: String, sym: Char?): Char? {
    if (sym == null) return null
    val idx = prefixSymbols(netId).indexOf(sym)
    if (idx < 0) return null
    val pm = prefixModes(netId)
    return pm.getOrNull(idx)
}

internal fun IrcViewModel.highestPrefixSymbol(netId: String, modes: Set<Char>): Char? {
    if (modes.isEmpty()) return null
    val pm = prefixModes(netId)
    val ps = prefixSymbols(netId)
    var bestIdx = Int.MAX_VALUE
    for (m in modes) {
        val idx = pm.indexOf(m)
        if (idx >= 0 && idx < bestIdx) bestIdx = idx
    }
    return if (bestIdx != Int.MAX_VALUE) ps.getOrNull(bestIdx) else null
}

internal fun IrcViewModel.nickRank(netId: String, display: String): Int {
    val (_, modes) = parseNickWithPrefixes(netId, display)
    val sym = highestPrefixSymbol(netId, modes)
    val idx = if (sym != null) prefixSymbols(netId).indexOf(sym) else -1
    return if (idx >= 0) idx else prefixSymbols(netId).length
}

internal fun IrcViewModel.rebuildNicklist(netId: String, chanKey: String): List<String> {
    val baseMap = chanNickCase[chanKey].orEmpty()
    val modeMap = chanNickStatus[chanKey].orEmpty()
    val ps = prefixSymbols(netId)
    val prefixChars = ps.toCharArray()

    val out = baseMap.entries.map { (fold, base) ->
        val modes = modeMap[fold].orEmpty()
        val sym = highestPrefixSymbol(netId, modes)
        (sym?.toString() ?: "") + base
    }

    return out.distinct().sortedWith(Comparator { a, b ->
        val ra = nickRank(netId, a)
        val rb = nickRank(netId, b)
        if (ra != rb) ra - rb
        else {
            val ba = a.trimStart(*prefixChars)
            val bb = b.trimStart(*prefixChars)
            casefoldText(netId, ba).compareTo(casefoldText(netId, bb))
        }
    })
}

internal fun IrcViewModel.upsertNickInChannel(netId: String, chanKey: String, baseNick: String, modes: Set<Char>? = null) {
    val fold = casefoldText(netId, baseNick)
    val baseMap = chanNickCase.getOrPut(chanKey) { mutableMapOf() }
    baseMap[fold] = baseNick
    val modeMap = chanNickStatus.getOrPut(chanKey) { mutableMapOf() }
    if (modes != null) {
        modeMap[fold] = modes.toMutableSet()
    } else {
        modeMap.getOrPut(fold) { mutableSetOf() }
    }
}

internal fun IrcViewModel.removeNickFromChannel(netId: String, chanKey: String, nick: String) {
    val fold = casefoldText(netId, nick)
    chanNickCase[chanKey]?.remove(fold)
    chanNickStatus[chanKey]?.remove(fold)
    if (chanNickCase[chanKey]?.isEmpty() == true) chanNickCase.remove(chanKey)
    if (chanNickStatus[chanKey]?.isEmpty() == true) chanNickStatus.remove(chanKey)
    // +AGE: tell the bridge, which reports it to scripts and rekeys an encrypted chat channel.
    // Unconditional, as for joins: a scripted game channel never sets the chat +AGE pref.
    ageBridges[netId]?.onMemberLeft(chanKey.removePrefix("$netId::"), nick)
}

internal fun IrcViewModel.setNicklistState(netId: String, chanKey: String) {
    val st = _state.value
    val rebuilt = rebuildNicklist(netId, chanKey)
    _state.value = syncActiveNetworkSummary(st.copy(nicklists = st.nicklists + (chanKey to rebuilt)))
}

internal fun IrcViewModel.applyNamesDelta(netId: String, chanKey: String, names: List<String>) {
    for (raw in names) {
        val (base, modes) = parseNickWithPrefixes(netId, raw)
        if (base.isBlank()) continue
        upsertNickInChannel(netId, chanKey, baseNick = base, modes = modes)
    }
    setNicklistState(netId, chanKey)
}

internal fun IrcViewModel.applyNamesSnapshot(netId: String, chanKey: String, names: List<String>) {
    chanNickCase[chanKey] = mutableMapOf()
    chanNickStatus[chanKey] = mutableMapOf()
    applyNamesDelta(netId, chanKey, names)
}

internal fun IrcViewModel.updateUserMode(netId: String, chanKey: String, nick: String, prefixSym: Char?, adding: Boolean) {
    val fold = casefoldText(netId, nick)
    val baseMap = chanNickCase.getOrPut(chanKey) { mutableMapOf() }
    val modeMap = chanNickStatus.getOrPut(chanKey) { mutableMapOf() }
    baseMap.putIfAbsent(fold, nick)
    val set = modeMap.getOrPut(fold) { mutableSetOf() }
    val mode = modeForPrefixSymbol(netId, prefixSym) ?: return
    if (adding) set.add(mode) else set.remove(mode)
    setNicklistState(netId, chanKey)
}

internal fun IrcViewModel.moveNickAcrossChannels(netId: String, oldNick: String, newNick: String) {
    val oldFold = casefoldText(netId, oldNick)
    val newFold = casefoldText(netId, newNick)
    val keys = chanNickCase.keys.filter { it.startsWith("$netId::") }
    for (k in keys) {
        val baseMap = chanNickCase[k] ?: continue
        val base = baseMap.remove(oldFold) ?: continue
        val modes = chanNickStatus[k]?.remove(oldFold)
        baseMap[newFold] = newNick
        if (modes != null) {
            val mm = chanNickStatus.getOrPut(k) { mutableMapOf() }
            mm[newFold] = modes
        }
    }
}



    // Connection notifications

/**
 * Clean a draft/metadata-2 value for display: strip control characters (including formatting
 * codes) and line breaks, and cap the length. Null when nothing is left.
 */
internal fun IrcViewModel.sanitizeMetadataValue(raw: String?, maxLen: Int): String? {
    if (raw.isNullOrEmpty()) return null
    val cleaned = buildString {
        for (ch in raw) {
            if (ch.code >= 0x20 && ch.code != 0x7f) append(ch)
        }
    }.trim()
    if (cleaned.isEmpty()) return null
    return if (cleaned.length > maxLen) cleaned.take(maxLen) else cleaned
}
