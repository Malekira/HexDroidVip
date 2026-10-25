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

/**
 * Who spoke recently in each channel, so joins, parts, quits and nick changes from people who have
 * been quiet can be left out. A join that was left out is held, with any nick changes after it,
 * and handed back if the person speaks within the window, to be shown at their own times.
 *
 * Times are the events' own (server time where there is one), so history replays are judged by
 * when things happened. Keys are a buffer key and a casefolded nick. Not thread-safe: called from
 * the main thread.
 */
class SmartFilter {
    /** An event line that was left out, kept in case the person speaks soon after. */
    data class HeldEvent(val bufferKey: String, val text: String, val timeMs: Long, val isHistory: Boolean)

    private val lastSpoke = HashMap<String, Long>()
    private val held = HashMap<String, MutableList<HeldEvent>>()

    private fun key(bufferKey: String, nickFold: String) = "$bufferKey\u0000$nickFold"

    /** True when [nickFold] spoke in [bufferKey] within [windowMs] before [atMs]. */
    fun isActive(bufferKey: String, nickFold: String, atMs: Long, windowMs: Long): Boolean {
        val spoke = lastSpoke[key(bufferKey, nickFold)] ?: return false
        return atMs - spoke <= windowMs
    }

    /**
     * Records that [nickFold] spoke in [bufferKey] at [atMs]. Returns the lines held for them, oldest
     * first, when their join was within [windowMs]; otherwise an empty list.
     */
    fun spoke(bufferKey: String, nickFold: String, atMs: Long, windowMs: Long): List<HeldEvent> {
        noteActivity(bufferKey, nickFold, atMs)
        val lines = held.remove(key(bufferKey, nickFold)) ?: return emptyList()
        val joinedAt = lines.firstOrNull()?.timeMs ?: return emptyList()
        return if (atMs - joinedAt in 0..windowMs) lines else emptyList()
    }

    /** Holds a join that was left out, replacing anything held for [nickFold] in that buffer. */
    fun holdJoin(nickFold: String, join: HeldEvent) {
        held[key(join.bufferKey, nickFold)] = mutableListOf(join)
        prune(join.timeMs)
    }

    /**
     * Adds a later line to what is held for [nickFold], such as a nick change after their join.
     * Returns false when no join is held for them there.
     */
    fun holdFollowUp(nickFold: String, event: HeldEvent): Boolean {
        val lines = held[key(event.bufferKey, nickFold)] ?: return false
        if (lines.size < MAX_HELD_PER_NICK) lines.add(event)
        return true
    }

    /** They left [bufferKey]: anything still held for them there is no longer worth showing. */
    fun left(bufferKey: String, nickFold: String) {
        held.remove(key(bufferKey, nickFold))
    }

    /** Carries activity and held lines over a nick change, in every buffer under [bufferPrefix]. */
    fun renamed(bufferPrefix: String, oldFold: String, newFold: String) {
        if (oldFold == newFold) return
        val suffix = "\u0000$oldFold"
        for (k in lastSpoke.keys.filter { it.startsWith(bufferPrefix) && it.endsWith(suffix) }) {
            val buffer = k.removeSuffix(suffix)
            lastSpoke.remove(k)?.let { lastSpoke[key(buffer, newFold)] = it }
        }
        for (k in held.keys.filter { it.startsWith(bufferPrefix) && it.endsWith(suffix) }) {
            val buffer = k.removeSuffix(suffix)
            held.remove(k)?.let { held[key(buffer, newFold)] = it }
        }
    }

    /** Forgets everything about [bufferKey], when it is closed. */
    fun forgetBuffer(bufferKey: String) {
        val prefix = "$bufferKey\u0000"
        lastSpoke.keys.removeAll { it.startsWith(prefix) }
        held.keys.removeAll { it.startsWith(prefix) }
    }

    /** Forgets everything under [bufferPrefix], when a network is removed. */
    fun forgetPrefix(bufferPrefix: String) {
        lastSpoke.keys.removeAll { it.startsWith(bufferPrefix) }
        held.keys.removeAll { it.startsWith(bufferPrefix) }
    }

    /** Records [nickFold] speaking in [bufferKey] at [atMs]. */
    fun noteActivity(bufferKey: String, nickFold: String, atMs: Long) {
        val k = key(bufferKey, nickFold)
        val previous = lastSpoke[k]
        if (previous == null || atMs > previous) lastSpoke[k] = atMs
        prune(atMs)
    }

    /** Drops what no window can reach any more, and caps the size. */
    private fun prune(nowMs: Long) {
        if (lastSpoke.size + held.size < PRUNE_AT) return
        val cutoff = nowMs - MAX_WINDOW_MS
        lastSpoke.values.removeAll { it < cutoff }
        held.values.removeAll { (it.firstOrNull()?.timeMs ?: 0L) < cutoff }
        if (lastSpoke.size > MAX_ENTRIES) {
            val keep = lastSpoke.entries.sortedByDescending { it.value }.take(MAX_ENTRIES).map { it.key }.toHashSet()
            lastSpoke.keys.retainAll(keep)
        }
        if (held.size > MAX_ENTRIES) held.clear()
    }

    internal val size: Int get() = lastSpoke.size + held.size

    companion object {
        /** The longest window offered. */
        const val MAX_WINDOW_MS = 60 * 60_000L
        private const val MAX_ENTRIES = 5_000
        private const val PRUNE_AT = 6_000
        private const val MAX_HELD_PER_NICK = 20

        /** The windows offered, in minutes. */
        val WINDOW_CHOICES = listOf(5, 10, 30, 60)
    }
}
