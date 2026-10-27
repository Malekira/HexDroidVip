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

/** Stable persisted bits; NOTICE may additionally retain the original service/channel routing. */
object ReplyDestination {
    const val STATUS = 1
    const val WINDOW = 2
    const val PRIVATE = 4
    const val ACTIVE = 8
    const val ORIGINAL = 16
    const val FLOATING = 32
    const val WHOIS_DEFAULT = FLOATING
    const val NOTICE_DEFAULT = ORIGINAL
    const val WHOIS_MASK = STATUS or WINDOW or ACTIVE or FLOATING
    const val NOTICE_MASK = STATUS or WINDOW or PRIVATE or ACTIVE or ORIGINAL

    fun sanitize(value: Int, allowed: Int, fallback: Int): Int =
        (value and allowed).takeIf { it != 0 } ?: fallback
}

const val WHOIS_BUFFER = "*whois*"
const val NOTICES_BUFFER = "*notices*"

/** Conservative IRC nick syntax: hostnames, bouncer pseudo-users and targets are never PVs. */
internal fun isReplyNick(nick: String?): Boolean {
    if (nick.isNullOrEmpty()) return false
    fun initial(c: Char) = c.isLetter() || c in "[]\\`_^{|}"
    return initial(nick.first()) && nick.drop(1).all { initial(it) || it.isDigit() || it == '-' }
}

/** Resolve names with the network's CASEMAPPING before deduplication; never cross networks. */
internal fun replyDestinations(
    mask: Int,
    network: String,
    window: String,
    selectedKey: String,
    privateNick: String?,
    originalKey: String? = null,
    serverNotice: Boolean = false,
    resolve: (String) -> String = { "$network::$it" },
): Set<String> = buildSet {
    val status = resolve("*server*")
    if (mask and ReplyDestination.STATUS != 0) add(status)
    if (mask and ReplyDestination.WINDOW != 0) add(resolve(window))
    if (mask and ReplyDestination.PRIVATE != 0) {
        add(if (!serverNotice && isReplyNick(privateNick)) resolve(privateNick!!) else status)
    }
    if (mask and ReplyDestination.ACTIVE != 0) {
        add(selectedKey.takeIf { it.startsWith("$network::") && it.length > network.length + 2 } ?: status)
    }
    if (mask and ReplyDestination.ORIGINAL != 0) {
        add(originalKey?.takeIf { it.startsWith("$network::") } ?: status)
    }
    if (serverNotice || (isEmpty() && mask and ReplyDestination.FLOATING == 0)) add(status)
}

/** Server-provided seconds only; invalid/missing values remain absent. */
internal fun whoisDuration(seconds: Long?): String? {
    if (seconds == null || seconds < 0) return null
    val days = seconds / 86400
    val hours = seconds % 86400 / 3600
    val minutes = seconds % 3600 / 60
    val rest = seconds % 60
    return buildList {
        if (days > 0) add("${days}d")
        if (hours > 0 || days > 0) add("${hours}h")
        if (minutes > 0 || hours > 0 || days > 0) add("${minutes}m")
        add("${rest}s")
    }.joinToString(" ")
}

/** Only simple single-nick queries can opt into the widely supported remote-idle form. */
internal fun whoisCommand(command: String, requestIdle: Boolean): String {
    val parts = command.trim().split(Regex("\\s+"))
    return if (requestIdle && parts.size == 2 && parts[0].equals("whois", true) && isReplyNick(parts[1]))
        "whois ${parts[1]} ${parts[1]}" else command
}

internal data class WhoisRequest(val id: Long, val nick: String, val buffer: String, val startedMs: Long)

/** FIFO per folded nick: simultaneous queries keep their origins and close only on their own end. */
internal class WhoisRequests(private val now: () -> Long = System::currentTimeMillis) {
    private companion object { val sequence = java.util.concurrent.atomic.AtomicLong() }
    private val pending = LinkedHashMap<Long, WhoisRequest>()

    @Synchronized fun remember(fold: String, buffer: String) {
        expire()
        if (pending.size >= 50) pending.remove(pending.keys.first())
        val request = WhoisRequest(sequence.incrementAndGet(), fold, buffer, now())
        pending[request.id] = request
    }

    @Synchronized fun receive(fold: String, code: String): WhoisRequest? {
        expire()
        val request = pending.values.firstOrNull { it.nick == fold } ?: return null
        if (code == "318") pending.remove(request.id)
        return request
    }

    @Synchronized fun clear() { pending.clear() }

    private fun expire() { pending.entries.removeAll { now() - it.value.startedMs >= 60_000L } }
}

internal data class WhoisBlock(val destinations: Set<String>, val nick: String, val lines: MutableList<String> = mutableListOf(), val floating: Boolean = false, val fields: MutableMap<String, String> = linkedMapOf())

/** UI-thread collector; the caller flushes incomplete blocks on timeout/disconnection. */
internal class WhoisBlocks {
    private val pending = mutableMapOf<Pair<String, Long>, WhoisBlock>()
    fun contains(network: String, id: Long) = (network to id) in pending
    fun add(network: String, id: Long, nick: String, destinations: Set<String>, text: String?, floating: Boolean = false, fields: Map<String, String> = emptyMap()) {
        val block = pending.getOrPut(network to id) { WhoisBlock(destinations, nick, floating = floating) }
        if (text != null) { block.lines += text; block.fields.putAll(fields) }
    }
    fun finish(network: String, id: Long): WhoisBlock? = pending.remove(network to id)
}
