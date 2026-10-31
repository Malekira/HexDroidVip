/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

internal const val COMPOSER_RECALL_LIMIT = 10

internal data class RecallDraft(val text: String, val start: Int = text.length, val end: Int = start)

/** Per-conversation composer navigation, independent of permanent conversation logs. */
internal class ComposerRecall {
    private var position = -1
    private var original: RecallDraft? = null

    fun previous(history: List<String>, current: RecallDraft): RecallDraft? {
        val entries = history.takeLast(COMPOSER_RECALL_LIMIT)
        if (entries.isEmpty()) return null
        if (position == -1) original = current
        position = (position + 1).coerceAtMost(entries.lastIndex)
        val text = entries[entries.lastIndex - position]
        return RecallDraft(text)
    }

    /** A field tap, cursor move or edit starts a new tour from the button. */
    fun edited() {
        position = -1
    }

    fun reset() {
        edited()
        original = null
    }

    fun restoreDraft(): RecallDraft? {
        val saved = original
        reset()
        return saved
    }

}
