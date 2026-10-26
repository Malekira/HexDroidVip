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
import java.util.Locale

// soju extensions: bouncer network management (soju.im/bouncer-networks), message search
// (soju.im/search) and the FAIL replies that answer them.

/** A soju.im/search request awaiting its reply batch. [labeled] when it went out with [id] as its label. */
internal class PendingSearch(val id: String, val labeled: Boolean)

/** An open soju.im/search batch: the request it answers, if known, and its messages so far. */
internal class SearchBatch(val request: PendingSearch?) {
    val hits = ArrayList<SearchHit>()
}

/**
 * Most search requests awaited at once, above the 25 one log search sends: dropping one that is
 * still answered would hand its reply to the next request.
 */
private const val MAX_PENDING_SEARCHES = 64

/** soju caps a search at 100 messages; this bounds a server that sends more. */
private const val MAX_SEARCH_HITS = 500

/** The BOUNCER subcommands whose replies and failures become [IrcEvent.BouncerCommandResult]. */
private val BOUNCER_EDIT_SUBCOMMANDS = setOf("ADDNETWORK", "CHANGENETWORK", "DELNETWORK")

/** True when the server is soju, going by the capabilities it offered. */
val IrcClient.isSoju: Boolean get() = sessionRef?.sojuMetadata == true

/** Ask the bouncer to add an upstream network with [attrs] (name, host, port, tls, nickname, ...). */
suspend fun IrcClient.bouncerAddNetwork(attrs: Map<String, String>) {
    if (attrs.isEmpty()) return
    sendRaw("BOUNCER ADDNETWORK ${encodeBouncerNetworkAttrs(attrs)}")
}

/** Change the attributes of upstream network [networkId]; an empty value clears one. */
suspend fun IrcClient.bouncerChangeNetwork(networkId: String, attrs: Map<String, String>) {
    if (attrs.isEmpty() || networkId.isBlank()) return
    sendRaw("BOUNCER CHANGENETWORK $networkId ${encodeBouncerNetworkAttrs(attrs)}")
}

/** Remove upstream network [networkId] from the bouncer. */
suspend fun IrcClient.bouncerDeleteNetwork(networkId: String) {
    if (networkId.isBlank()) return
    sendRaw("BOUNCER DELNETWORK $networkId")
}

/** A fresh id for [searchMessages], so a caller can be waiting for it before the request goes out. */
fun IrcClient.newSearchId(): String = nextLabel()

/**
 * Search the bouncer's stored history of [target] (soju.im/search), for messages containing
 * [text] and, when given, sent by [from]. Returns [id], which [IrcEvent.SearchResults] will
 * carry, or null when the server offers no search.
 */
suspend fun IrcClient.searchMessages(
    target: String,
    text: String?,
    from: String? = null,
    limit: Int = 100,
    id: String = newSearchId(),
): String? {
    if (!hasCap("soju.im/search") || target.isBlank()) return null
    val attrs = LinkedHashMap<String, String>()
    attrs["in"] = target
    text?.trim()?.takeIf { it.isNotEmpty() }?.let { attrs["text"] = it }
    from?.trim()?.takeIf { it.isNotEmpty() }?.let { attrs["from"] = it }
    attrs["limit"] = limit.coerceIn(1, 100).toString()
    val labeled = hasCap("labeled-response")
    val request = PendingSearch(id, labeled)
    synchronized(pendingSearches) {
        if (pendingSearches.size >= MAX_PENDING_SEARCHES) pendingSearches.removeFirst()
        pendingSearches.addLast(request)
    }
    val tag = if (labeled) "@label=${request.id} " else ""
    sendBackground("${tag}SEARCH ${encodeBouncerNetworkAttrs(attrs)}")
    return request.id
}

/**
 * The search request a reply answers: the one with its [label], or without a label the oldest
 * outstanding. A label of someone else's matches nothing.
 */
private fun IrcClient.takePendingSearch(label: String?): PendingSearch? = synchronized(pendingSearches) {
    val idx = if (label != null) {
        pendingSearches.indexOfFirst { it.labeled && it.id == label }
    } else if (pendingSearches.isNotEmpty()) 0 else -1
    if (idx >= 0) pendingSearches.removeAt(idx) else null
}

/** A soju.im/search batch [id] opened, labeled [label] directly or through its parent. */
internal fun IrcClient.openSearchBatch(id: String, label: String?) {
    if (searchBatches.size >= IrcClient.MAX_TRACKED_BATCHES) searchBatches.remove(searchBatches.keys.first())
    searchBatches[id] = SearchBatch(takePendingSearch(label))
}

/**
 * Takes a line belonging to an open search batch, keeping its PRIVMSG or NOTICE as a hit. These
 * are stored history, so they never reach the buffers. Returns false for any other line.
 */
internal fun IrcClient.collectSearchLine(msg: IrcMessage): Boolean {
    if (msg.command == "BATCH") return false
    val batch = searchBatches[msg.tags["batch"] ?: return false] ?: return false
    val cmd = msg.command.uppercase(Locale.ROOT)
    if ((cmd != "PRIVMSG" && cmd != "NOTICE") || batch.hits.size >= MAX_SEARCH_HITS) return true
    val target = msg.params.getOrNull(0) ?: return true
    var text = msg.trailing ?: msg.params.getOrNull(1) ?: return true
    var action = false
    if (text.startsWith('\u0001')) {
        val inner = text.trim('\u0001')
        if (cmd != "PRIVMSG" || (inner != "ACTION" && !inner.startsWith("ACTION "))) return true
        text = inner.removePrefix("ACTION").removePrefix(" ")
        action = true
    }
    batch.hits += SearchHit(
        from = msg.prefixNick() ?: "",
        target = target,
        text = text,
        timeMs = parseServerTimeMs(msg.tags),
        msgId = msg.tags["msgid"],
        isNotice = cmd == "NOTICE",
        isAction = action,
    )
    return true
}

/** A search batch closed: report its messages to the request it answers. */
internal suspend fun IrcClient.closeSearchBatch(out: ProducerScope<IrcEvent>, id: String) {
    val batch = searchBatches.remove(id) ?: return
    val request = batch.request ?: return
    out.send(IrcEvent.SearchResults(request.id, batch.hits.toList()))
}

/**
 * FAIL replies to soju commands with a structured answer: BOUNCER network edits, SEARCH, and
 * `FAIL * ACCOUNT_REQUIRED` for a connection that gave no credentials. Returns true when [msg]
 * was one of them.
 */
internal suspend fun IrcClient.handleSojuFail(
    out: ProducerScope<IrcEvent>,
    msg: IrcMessage,
    command: String,
    code: String,
    context: List<String>,
    description: String,
): Boolean {
    val c = code.uppercase(Locale.ROOT)
    when {
        command.equals("BOUNCER", ignoreCase = true) -> {
            val sub = context.firstOrNull()?.uppercase(Locale.ROOT)
            if (sub == null || sub !in BOUNCER_EDIT_SUBCOMMANDS) return false
            out.send(IrcEvent.ServerText("FAIL BOUNCER $c ${context.joinToString(" ")}: $description", code = "FAIL"))
            out.send(IrcEvent.BouncerCommandResult(
                subcommand = sub,
                networkId = if (c == "INVALID_NETID") context.getOrNull(1) else null,
                failed = true,
                code = c,
                // Spec form "<sub> <netid> <attr>"; soju omits the netid. The attribute is last either way.
                attribute = if (c.endsWith("_ATTRIBUTE")) context.drop(1).lastOrNull() else null,
                description = description,
            ))
            return true
        }
        command.equals("SEARCH", ignoreCase = true) -> {
            out.send(IrcEvent.ServerText("FAIL SEARCH $c: $description", code = "FAIL"))
            val request = takePendingSearch(replyLabel(msg.tags)) ?: return true
            out.send(IrcEvent.SearchResults(request.id, emptyList(), failed = true, code = c, description = description))
            return true
        }
        command == "*" && c == "ACCOUNT_REQUIRED" -> {
            out.send(IrcEvent.AuthFailed(reason = description, source = "ACCOUNT_REQUIRED"))
            return true
        }
    }
    return false
}
