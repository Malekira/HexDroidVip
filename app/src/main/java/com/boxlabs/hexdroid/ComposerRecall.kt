/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

internal const val COMPOSER_RECALL_LIMIT = 10

internal data class RecallDraft(val text: String, val start: Int = text.length, val end: Int = start)
internal data class RecallDeleteResult(val consumed: Boolean, val draft: RecallDraft? = null)

/** Per-conversation composer navigation, independent of permanent conversation logs. */
internal class ComposerRecall {
    private var position = -1
    private var original: RecallDraft? = null
    private var recalled: String? = null
    private var keyboardBrowsing = false
    private var lastNormalDelete: Long? = null

    fun previous(history: List<String>, current: RecallDraft): RecallDraft? {
        val entries = history.takeLast(COMPOSER_RECALL_LIMIT)
        if (entries.isEmpty()) return null
        if (position == -1) original = current
        position = (position + 1).coerceAtMost(entries.lastIndex)
        val text = entries[entries.lastIndex - position]
        recalled = text
        keyboardBrowsing = true
        return RecallDraft(text)
    }

    /** A field tap, cursor move or edit gives the keyboard its ordinary delete behaviour. */
    fun edited() {
        position = -1
        recalled = null
        keyboardBrowsing = false
    }

    fun reset() {
        edited()
        original = null
        lastNormalDelete = null
    }

    fun restoreDraft(): RecallDraft? {
        val saved = original
        reset()
        return saved
    }

    fun keyboardDelete(history: List<String>, current: RecallDraft, nowMs: Long, repeat: Boolean = false): RecallDeleteResult {
        if (repeat) return RecallDeleteResult(keyboardBrowsing)
        val caretAtEnd = current.start == current.end && current.end == current.text.length
        if (keyboardBrowsing && (current.text != recalled || !caretAtEnd)) edited()
        if (!keyboardBrowsing && current.text.isNotEmpty()) {
            lastNormalDelete = nowMs
            return RecallDeleteResult(false)
        }
        // Holding Delete to empty a draft must not immediately bring old text back.
        if (!keyboardBrowsing && lastNormalDelete?.let { nowMs - it < 400L } == true) {
            lastNormalDelete = nowMs
            return RecallDeleteResult(false)
        }
        val next = previous(history, current)
        return RecallDeleteResult(next != null, next)
    }
}
