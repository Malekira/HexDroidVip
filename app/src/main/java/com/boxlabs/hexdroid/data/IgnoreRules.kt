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

package com.boxlabs.hexdroid.data

import java.util.Locale

/** What an ignore entry drops. */
enum class IgnoreType {
    /** Messages and actions in channels. */
    CHANNEL,
    /** Private messages and actions. */
    PRIVATE,
    NOTICES,
    /** CTCP requests: no automatic reply is sent and scripts do not see them. */
    CTCP,
    INVITES,
    /** DCC file and chat offers. */
    DCC,
    /** Joins, parts, quits and nick changes. */
    EVENTS,
}

/**
 * Ignore entries and their types. An entry is a nick, a glob on the nick (`bob*`), or a
 * `nick!user@host` glob (`*!*@example.com`). An entry stored without types drops everything
 * except channel events, as entries always did.
 */
object IgnoreRules {

    val DEFAULT_TYPES: Set<IgnoreType> = setOf(
        IgnoreType.CHANNEL, IgnoreType.PRIVATE, IgnoreType.NOTICES,
        IgnoreType.CTCP, IgnoreType.INVITES, IgnoreType.DCC,
    )

    val ALL_TYPES: Set<IgnoreType> = IgnoreType.values().toSet()

    /** The key an entry's types are stored under. */
    fun key(mask: String): String = mask.trim().lowercase(Locale.ROOT)

    fun typesFor(levels: Map<String, Set<IgnoreType>>, mask: String): Set<IgnoreType> =
        levels[key(mask)] ?: DEFAULT_TYPES

    /** True when [mask] is a `nick!user@host` style entry rather than a nick. */
    fun isHostMask(mask: String): Boolean = mask.contains('!') || mask.contains('@')

    /**
     * True when [pattern] matches [nick], whose `user@host` is [userHost] when known. A host
     * mask with no known host is matched against `nick!*@*`, so only a mask that leaves the
     * user and host open can match it.
     */
    fun matches(pattern: String, nick: String, userHost: String?): Boolean {
        val p = pattern.trim()
        val base = nick.trim().trimStart('~', '&', '@', '%', '+')
        if (p.isEmpty() || base.isEmpty()) return false
        return when {
            isHostMask(p) -> NotifyMasks.matchIrcGlob(p, "$base!${userHost?.takeIf { it.contains('@') } ?: "*@*"}")
            p.contains('*') || p.contains('?') -> NotifyMasks.matchIrcGlob(p, base)
            else -> p.equals(base, ignoreCase = true)
        }
    }

    /** True when any of [masks] matches the sender and drops [type]. */
    fun ignores(
        masks: List<String>,
        levels: Map<String, Set<IgnoreType>>,
        nick: String?,
        userHost: String?,
        type: IgnoreType,
    ): Boolean {
        if (nick.isNullOrBlank() || masks.isEmpty()) return false
        return masks.any { m -> type in typesFor(levels, m) && matches(m, nick, userHost) }
    }

    /**
     * Types named by [words] (as typed after `/ignore <mask>`), or null when a word is not a
     * type. No words means the default set.
     */
    fun parseTypes(words: List<String>): Set<IgnoreType>? {
        if (words.isEmpty()) return DEFAULT_TYPES
        val out = mutableSetOf<IgnoreType>()
        for (w in words) {
            when (w.lowercase(Locale.ROOT).trimStart('+')) {
                "all" -> out += ALL_TYPES
                "public", "channel", "channels", "chan" -> out += IgnoreType.CHANNEL
                "msgs", "msg", "private", "pm", "pms", "query" -> out += IgnoreType.PRIVATE
                "notice", "notices" -> out += IgnoreType.NOTICES
                "ctcp", "ctcps" -> out += IgnoreType.CTCP
                "invite", "invites" -> out += IgnoreType.INVITES
                "dcc" -> out += IgnoreType.DCC
                "events", "joins", "parts", "quits", "nicks" -> out += IgnoreType.EVENTS
                else -> return null
            }
        }
        return out
    }

    /** The short words for [types], in a fixed order, for listing. */
    fun describe(types: Set<IgnoreType>): String = when (types) {
        ALL_TYPES -> "ALL"
        else -> IgnoreType.values().filter { it in types }.joinToString(" ") { t ->
            when (t) {
                IgnoreType.CHANNEL -> "PUBLIC"
                IgnoreType.PRIVATE -> "MSGS"
                IgnoreType.NOTICES -> "NOTICES"
                IgnoreType.CTCP -> "CTCPS"
                IgnoreType.INVITES -> "INVITES"
                IgnoreType.DCC -> "DCC"
                IgnoreType.EVENTS -> "EVENTS"
            }
        }
    }

    fun encodeLevels(levels: Map<String, Set<IgnoreType>>): org.json.JSONObject {
        val o = org.json.JSONObject()
        for ((k, v) in levels) o.put(k, org.json.JSONArray(IgnoreType.values().filter { it in v }.map { it.name }))
        return o
    }

    fun decodeLevels(o: org.json.JSONObject?): Map<String, Set<IgnoreType>> {
        if (o == null) return emptyMap()
        val out = HashMap<String, Set<IgnoreType>>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val arr = o.optJSONArray(k) ?: continue
            val set = (0 until arr.length()).mapNotNull { i ->
                runCatching { IgnoreType.valueOf(arr.optString(i)) }.getOrNull()
            }.toSet()
            out[key(k)] = set
        }
        return out
    }
}
