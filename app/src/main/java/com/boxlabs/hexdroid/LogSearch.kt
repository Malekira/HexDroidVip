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

/** One matching log line, with the lines around it. */
data class LogSearchHit(
    /** The log's network folder name. */
    val networkDir: String,
    /** The buffer name the log file belongs to, as near as its file name allows. */
    val buffer: String,
    /** 1-based line number in the file. */
    val lineNo: Int,
    val line: String,
    val before: List<String>,
    val after: List<String>,
    /** Found in the bouncer's history rather than a log file, on network [netId]. */
    val fromServer: Boolean = false,
    val netId: String? = null,
    val msgId: String? = null,
) {
    /** The timestamp text at the start of the line, or empty. Sorts in time order. */
    val stamp: String get() = line.substringBefore('\t', "").takeIf { it.length in 10..25 }.orEmpty()

    /** The line without its timestamp. */
    val body: String get() = LogSearch.bodyOf(line)
}

/**
 * Searching log files: query parsing and line matching, kept free of storage so it can be
 * tested on its own.
 *
 * A query is words that must all appear in the message (any order, any case). `from:nick` keeps
 * only lines from that nick. A phrase in double quotes must appear as written.
 */
object LogSearch {

    /** A parsed query. [fromTyped] is [from] as typed, before lowercasing. */
    class Query(val terms: List<String>, val from: String?, val fromTyped: String? = from) {
        val isEmpty: Boolean get() = terms.isEmpty() && from == null
    }

    fun parse(raw: String): Query {
        val terms = mutableListOf<String>()
        var from: String? = null
        var fromTyped: String? = null
        var i = 0
        val s = raw.trim()
        while (i < s.length) {
            if (s[i].isWhitespace()) { i++; continue }
            if (s[i] == '"') {
                val end = s.indexOf('"', i + 1)
                val phrase = if (end < 0) s.substring(i + 1) else s.substring(i + 1, end)
                if (phrase.isNotBlank()) terms += phrase.lowercase(Locale.ROOT)
                i = if (end < 0) s.length else end + 1
                continue
            }
            var end = i
            while (end < s.length && !s[end].isWhitespace()) end++
            val word = s.substring(i, end)
            if (word.startsWith("from:", ignoreCase = true) && word.length > 5) {
                fromTyped = word.substring(5).trimStart('<', '@', '+', '%', '&', '~').trimEnd('>', ':', ',')
                    .takeIf { it.isNotEmpty() }
                from = fromTyped?.lowercase(Locale.ROOT)
            } else {
                terms += word.lowercase(Locale.ROOT)
            }
            i = end
        }
        return Query(terms, from, fromTyped)
    }

    /** The message part of a log line: everything after the timestamp's tab. */
    fun bodyOf(line: String): String {
        val tab = line.indexOf('\t')
        return if (tab in 0..25) line.substring(tab + 1) else line
    }

    /** The sender of a chat or action line, or null for a status line. */
    fun senderOf(body: String): String? {
        if (body.startsWith("<")) {
            val end = body.indexOf("> ")
            if (end > 1) return body.substring(1, end)
        }
        if (body.startsWith("*") && body.length > 2 && body[1] != ' ' && body[1] != '*') {
            val close = body.indexOf('*', 1)
            if (close > 1) return body.substring(1, close).takeIf { n -> n.none { it.isWhitespace() } }
        }
        return null
    }

    /** The message text of a chat or action line, without the sender. */
    fun textOf(body: String): String {
        if (body.startsWith("<")) {
            val end = body.indexOf("> ")
            if (end > 1) return body.substring(end + 2)
        }
        if (body.startsWith("*") && body.length > 2 && body[1] != ' ' && body[1] != '*') {
            val close = body.indexOf('*', 1)
            if (close > 1 && close + 1 < body.length) return body.substring(close + 1).trimStart()
        }
        return body
    }

    fun matches(query: Query, line: String): Boolean {
        if (query.isEmpty) return false
        val body = bodyOf(line)
        if (query.from != null) {
            val sender = senderOf(body)?.trimStart('@', '+', '%', '&', '~')?.lowercase(Locale.ROOT) ?: return false
            if (sender != query.from) return false
        }
        if (query.terms.isEmpty()) return true
        val hay = body.lowercase(Locale.ROOT)
        return query.terms.all { hay.contains(it) }
    }

    /**
     * Hits in [lines], each with up to [context] lines either side. At most [keep] hits are
     * returned, the newest (last) ones. [cancelled] is checked as lines are read.
     */
    fun searchLines(
        lines: Sequence<String>,
        query: Query,
        networkDir: String,
        buffer: String,
        context: Int = 2,
        keep: Int = 500,
        cancelled: () -> Boolean = { false },
    ): List<LogSearchHit> {
        val out = ArrayDeque<LogSearchHit>()
        val recent = ArrayDeque<String>(context + 1)
        // Hits still collecting lines after them, with how many they still want.
        val open = ArrayList<Pair<LogSearchHit, MutableList<String>>>()
        var lineNo = 0
        for (line in lines) {
            lineNo++
            if ((lineNo and 0x3FF) == 0 && cancelled()) break
            if (open.isNotEmpty()) {
                val it = open.iterator()
                while (it.hasNext()) {
                    val (_, after) = it.next()
                    after += line
                    if (after.size >= context) it.remove()
                }
            }
            if (matches(query, line)) {
                val after = ArrayList<String>(context)
                val hit = LogSearchHit(networkDir, buffer, lineNo, line, recent.toList(), after)
                out.addLast(hit)
                if (out.size > keep) out.removeFirst()
                if (context > 0) open += hit to after
            }
            if (context > 0) {
                recent.addLast(line)
                if (recent.size > context) recent.removeFirst()
            }
        }
        return out.toList()
    }

    /** The buffer a log file name belongs to: the reverse of the log writer's naming. */
    fun bufferForFileName(fileName: String): String {
        val base = fileName.removeSuffix(".txt")
        return when {
            base == "server" -> "*server*"
            base.equals("server_", ignoreCase = true) -> base.dropLast(1)
            else -> base
        }
    }

    /** Hits from several files, newest first, at most [limit]. */
    fun newestFirst(hits: List<LogSearchHit>, limit: Int): List<LogSearchHit> =
        hits.sortedWith(compareByDescending<LogSearchHit> { it.stamp }.thenByDescending { it.lineNo }).take(limit)
}
