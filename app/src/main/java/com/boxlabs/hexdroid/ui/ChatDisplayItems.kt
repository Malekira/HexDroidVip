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

package com.boxlabs.hexdroid.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.boxlabs.hexdroid.UiMessage
import com.boxlabs.hexdroid.isAsciiDigit
import com.boxlabs.hexdroid.stripIrcFormatting

/**
 * This exists to stop colour-heavy *text* (relay bots that paint the network
 * tag and nick, lively coloured chat) from being misclassified as ASCII/ANSI
 * art and shrunk to an unreadable size.
 */
private fun looksLikeProse(plain: String): Boolean {
    var words = 0
    var run = 0
    var wordChars = 0
    var nonSpace = 0
    for (ch in plain) {
        if (ch.isLetter()) {
            run++
            wordChars++
        } else {
            if (run >= 2) words++
            run = 0
            // Numbers are ordinary in prose ("14°C", "67%", "24-09").
            if (ch.isDigit()) wordChars++
        }
        if (!ch.isWhitespace()) nonSpace++
    }
    if (run >= 2) words++
    val wordRatio = if (nonSpace > 0) wordChars.toFloat() / nonSpace else 0f
    return words >= 3 && wordRatio >= 0.55f
}

/**
 * [s] without emoji: pictographs, flags, skin tones, keycaps, ZWJ joins and any symbol given emoji
 * presentation (followed by U+FE0F). Emoji are neither art structure nor fixed-width, so they are
 * left out of the art heuristics entirely.
 */
private fun stripEmoji(s: String): String {
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val n = Character.charCount(cp)
        val next = if (i + n < s.length) s.codePointAt(i + n) else -1
        val emoji = cp in 0x1F000..0x1FAFF || cp in 0xE0020..0xE007F ||
            cp == 0x200D || cp == 0xFE0F || cp == 0x20E3 ||
            next == 0xFE0F || next == 0x20E3
        if (!emoji) out.appendCodePoint(cp)
        i += n
    }
    return out.toString()
}

/**
 * Returns true when a chat message line looks like it belongs to a bot-generated
 * ASCII/ANSI art block rather than normal coloured conversation.
 */
private fun looksLikeArt(text: String): Boolean {
    // Examine stripped content for structural shape, plus ANSI SGR and mIRC colour
    // codes as additional signals. The block-size gate (≥2 consecutive lines from
    // non-action senders) does the primary false-positive filtering.
    val plain = stripEmoji(stripIrcFormatting(text))
    if (plain.length < 3) return false

    // Single pass: detect ANSI SGR and count mIRC colour codes. ANSI SGR (ESC[…m) is almost never
    // in normal IRC text, so one is a strong signal; other ANSI escapes (ESC[3~, ESC[A) are not
    // art, so only the SGR final byte 'm' counts. mIRC colour codes are common in chat, so it takes
    // four or more on a line.
    var mircCount = 0
    var i = 0
    while (i < text.length) {
        when (text[i]) {
            '' -> {
                if (i + 1 < text.length && text[i + 1] == '[') {
                    var j = i + 2
                    while (j < text.length && (text[j].isAsciiDigit() || text[j] == ';' || text[j] == ':')) j++
                    if (j < text.length && text[j] == 'm') return true  // ANSI SGR colour
                    i = j + 1
                } else i++
            }
            '' -> { mircCount++; i++ }
            else -> i++
        }
    }

    // Prose gate: colour-heavy but word-shaped lines (relay bots painting the network
    // tag and nick, lively coloured chat) must never be classified as art. Only the
    // unambiguous signals - ANSI SGR above and the Unicode box-drawing first char
    // below - bypass this; every heuristic that can plausibly match real sentences
    // is gated on the line NOT reading as prose.
    val isProse = looksLikeProse(plain)

    if (!isProse && mircCount >= 4) return true

    // Signal 1: two or more leading spaces and a structural line. A box-drawing or block character
    // (U+2500-U+2BFF) first qualifies outright; ASCII structure needs both ends non-alphanumeric,
    // so "|  text  |" counts but "  * bullet text" doesn't.
    if (plain.length >= 3 && plain[0] == ' ' && plain[1] == ' ') {
        val trimmed = plain.trimStart()
        if (trimmed.isNotEmpty()) {
            val first = trimmed[0]
            if (!first.isLetterOrDigit()) {
                // Box-drawing, block elements, geometric shapes, misc symbols (U+2500–U+2BFF)
                if (first.code in 0x2500..0x2BFF) return true
                // ASCII structural: both ends non-alphanumeric → symmetrically framed line
                val lastCh = trimmed.trimEnd().lastOrNull()
                if (!isProse && lastCh != null && !lastCh.isLetterOrDigit()) return true
            }
        }
    }

    // Signal 2: structural-symbol density, with tiered thresholds for short dense lines and longer
    // mixed ones:
    //   Short  (>= 4 non-space):  density >= 80% (border lines like "_____")
    //   Medium (>= 8 non-space):  density >= 45% ("H   _|\_/|_   H")
    //   Long   (>= 16 non-space): density >= 30%
    // Normal chat scores under 20%.
    var artCount = 0
    var alphaCount = 0
    for (ch in plain) {
        if (ch == ' ') continue
        if (ch.isLetterOrDigit()) alphaCount++ else artCount++
    }
    val nonSpace = artCount + alphaCount
    val density = if (nonSpace > 0) artCount.toFloat() / nonSpace else 0f
    if (!isProse) {
        if (nonSpace >= 4  && density >= 0.80f) return true   // short dense:  "_____", "|_ _|"
        if (nonSpace >= 8  && density >= 0.45f) return true   // medium mixed: "H _|\/|_ H"
        if (nonSpace >= 16 && density >= 0.30f) return true   // long:         original threshold
    }

    // Signal 3: single-space indent + border character patterns.
    // Expanded border set to include common art characters (*#@~^<>{}) beyond the
    // original "|/\_-=+[]".
    if (!isProse && plain.length >= 4 && plain[0] == ' ' && plain[1] != ' ') {
        val c1 = plain[1]; val c2 = plain[2]
        val border = "|/\\_-=+[]{}~^<>*#@"
        // 3a: border char followed immediately by space or another border char.
        if (c1 in border && (c2 == ' ' || c2 in border)) return true
        // 3b: framed label — first AND last non-space chars are both border chars.
        val lastNs = plain.trimEnd().lastOrNull()
        if (lastNs != null && c1 in border && lastNs in border) return true
    }

    return false
}

/**
 * A chat list item before sizing: [Single] is one message, [Art] a run of consecutive art lines
 * rendered as one gap-free block.
 */
private sealed class RawItem {
    data class Single(val msg: UiMessage) : RawItem()
    data class Art(val msgs: List<UiMessage>) : RawItem()  // chronological
}

internal sealed class DisplayItem {
    abstract val key: Any
    data class Single(val msg: UiMessage) : DisplayItem() {
        // Type-tagged string keys can't collide across types: "S:<id>" for a single message,
        // "A:<firstId>:<size>" for an art block, whose key changes as the block grows or shrinks.
        override val key: Any = "S:${msg.id}"
    }
    data class Art(
        val msgs: List<UiMessage>,  // chronological: oldest first
        val fontSizeSp: Float,
        /** Too wide for the pane even at the minimum size, so the block scrolls sideways. */
        val overflows: Boolean = false,
    ) : DisplayItem() {
        override val key: Any = "A:${msgs.first().id}:${msgs.size}"
    }
}

/**
 * Builds the [DisplayItem] list for the chat LazyColumn, merging consecutive art lines into one
 * [DisplayItem.Art]. Phase 1 detects blocks with string operations only, keyed on the message
 * list. Phase 2 measures each block once and scales it to the pane width, so resizing costs no
 * measurement.
 */
@Composable
internal fun rememberDisplayItems(
    reversedMessages: List<UiMessage>,
    availableWidthPx: Float,
    style: androidx.compose.ui.text.TextStyle,
    minFontSp: Float = 4f,
    artDetectionEnabled: Boolean = true,
    /** First message ids of art blocks to show as ordinary lines instead. */
    shownAsText: Set<Long> = emptySet(),
): List<DisplayItem> {
    val textMeasurer = rememberTextMeasurer()
    val naturalSizeSp = style.fontSize.value.takeIf { !it.isNaN() && it > 0f } ?: 14f

    // ── Phase 1: block detection, string ops only ───────────────────────
    // Keyed on the reversedMessages list by reference, so it invalidates exactly when the content
    // changes; a merge can reorder messages while the size and end ids stay the same. Phase 1 costs
    // microseconds at the scrollback cap, and Phase 2's measurements are cached separately below.
    val rawItems: List<RawItem> = remember(reversedMessages, artDetectionEnabled) {
        if (reversedMessages.isEmpty()) return@remember emptyList()
        // Detection disabled: every message renders as a normal chat line. Mapping
        // straight to Singles keeps the DisplayItem pipeline (keys, unread separator
        // indexing, reply-quote scroll) identical to the enabled path.
        if (!artDetectionEnabled) {
            return@remember reversedMessages.map { RawItem.Single(it) }
        }
        val result  = mutableListOf<RawItem>()
        val artRun  = mutableListOf<UiMessage>()  // accumulated in reversed (newest-first) order

        fun flushArtRun() {
            if (artRun.size >= 2) result.add(RawItem.Art(artRun.asReversed().toList()))
            else artRun.forEach { result.add(RawItem.Single(it)) }
            artRun.clear()
        }

        // Maximum time gap between consecutive art lines before the block is split.
        // Art bots paste lines in rapid succession; a gap of ≥60 s almost certainly
        // means two separate art pastes that should not be rendered as one block.
        val ART_TIME_GAP_MS = 60_000L

        for (msg in reversedMessages) {
            // /me action messages are excluded: their text is rendered with a "* nick"
            // prefix in normal chat, so absorbing them into an art block would strip that
            // prefix and display the raw action text without context.
            // Server messages (from == null) are also excluded.
            if (msg.from != null && !msg.isAction && looksLikeArt(msg.text)) {
                // If there is a large time gap between this message and the previous art
                // line, flush the current run and start a new block. reversedMessages is
                // newest-first, so artRun.last() is the message directly preceding this
                // one in time (it is newer than msg).
                if (artRun.isNotEmpty() && artRun.last().timeMs - msg.timeMs > ART_TIME_GAP_MS) {
                    flushArtRun()
                }
                artRun.add(msg)
            } else {
                flushArtRun()
                result.add(RawItem.Single(msg))
            }
        }
        flushArtRun()
        result
    }

    // ── Phase 2: font sizing ─────────────────────────────────────────────
    // Each block is measured once, at the natural size: text width scales linearly with font
    // size, so fitting a new pane width is arithmetic and resizing never re-measures. The cache
    // is keyed on the style, and each block on its ids and text.
    val naturalWidthCache = remember(style) { HashMap<Long, Float>() }

    fun naturalWidest(msgs: List<UiMessage>): Float {
        var key = (msgs.first().id shl 17) or msgs.size.toLong()
        for (m in msgs) key = key * 31 + m.text.hashCode()
        naturalWidthCache[key]?.let { return it }
        if (naturalWidthCache.size >= 500) naturalWidthCache.clear()
        val natural = style.copy(fontSize = naturalSizeSp.sp)
        val widest = msgs.maxOfOrNull { m ->
            val line = stripIrcFormatting(m.text)
            if (line.isEmpty()) 0f else textMeasurer.measure(
                text = line,
                style = natural,
                constraints = Constraints(maxWidth = Int.MAX_VALUE),
                maxLines = 1,
                softWrap = false,
            ).size.width.toFloat()
        } ?: 0f
        naturalWidthCache[key] = widest
        return widest
    }

    /** Font size for an art block, and whether it still overflows the pane at that size. */
    fun fontSizeForBlock(msgs: List<UiMessage>): Pair<Float, Boolean> {
        val widest = naturalWidest(msgs)
        if (widest <= 0f || availableWidthPx <= 0f) return naturalSizeSp to false
        // A little slack, so rounding at the new size can't push the last column past the edge.
        val usable = availableWidthPx * 0.99f - 1f
        if (widest <= usable) return naturalSizeSp to false
        // Rounded down to a quarter point, so a resize only relays out a block when its size steps.
        val fitted = kotlin.math.floor(naturalSizeSp * usable / widest * 4f) / 4f
        val sp = fitted.coerceAtLeast(minFontSp)
        return sp to (widest * sp / naturalSizeSp > availableWidthPx)
    }

    return rawItems.flatMap { raw ->
        when (raw) {
            is RawItem.Single -> listOf(DisplayItem.Single(raw.msg))
            // The list runs newest first, the block oldest first.
            is RawItem.Art -> if (raw.msgs.first().id in shownAsText) {
                raw.msgs.asReversed().map { DisplayItem.Single(it) }
            } else {
                fontSizeForBlock(raw.msgs).let { (sp, overflows) ->
                    listOf(DisplayItem.Art(raw.msgs, sp, overflows))
                }
            }
        }
    }
}

/**
 * Computes the single font size (in sp) that makes the widest MOTD line fit within
 * [availableWidthPx] at the given [style]. Every MOTD line must be rendered at this
 * same size so that monospace ASCII art columns stay aligned.
 *
 * Returns [style]'s natural size when all lines already fit, or [minFontSp] as a floor.
 */
@Composable
internal fun rememberMotdFontSizeSp(
    motdLines: List<String>,
    style: androidx.compose.ui.text.TextStyle,
    availableWidthPx: Float,
    minFontSp: Float = 6f,
): Float {
    val textMeasurer = rememberTextMeasurer()
    val naturalSizeSp = style.fontSize.value.takeIf { !it.isNaN() && it > 0f } ?: 14f

    // Strip IRC formatting from every line for measurement (formatting chars have no width).
    val plainLines = remember(motdLines) { motdLines.map { stripIrcFormatting(it) } }

    return remember(plainLines, availableWidthPx, naturalSizeSp) {
        if (availableWidthPx <= 0f || plainLines.isEmpty()) return@remember naturalSizeSp

        // Find the widest line at the natural font size.
        val widestAtNatural = plainLines.maxOf { line ->
            textMeasurer.measure(
                text = line,
                style = style.copy(fontSize = naturalSizeSp.sp),
                constraints = Constraints(maxWidth = Int.MAX_VALUE),
                maxLines = 1,
                softWrap = false,
            ).size.width
        }
        if (widestAtNatural <= availableWidthPx) return@remember naturalSizeSp

        // Binary-search a single shared size that fits even the widest line.
        var lo = minFontSp
        var hi = naturalSizeSp
        repeat(8) {
            val mid = (lo + hi) / 2f
            val widest = plainLines.maxOf { line ->
                textMeasurer.measure(
                    text = line,
                    style = style.copy(fontSize = mid.sp),
                    constraints = Constraints(maxWidth = Int.MAX_VALUE),
                    maxLines = 1,
                    softWrap = false,
                ).size.width
            }
            if (widest <= availableWidthPx) lo = mid else hi = mid
        }
        lo
    }
}

/**
 * Renders a single MOTD line at [fontSizeSp]. The caller is responsible for computing
 * a shared font size across all MOTD lines (via [rememberMotdFontSizeSp]) so that
 * monospace ASCII art columns remain aligned across lines of different lengths.
 */
@Composable
internal fun MotdLine(
    text: String,
    fontSizeSp: Float,
    style: androidx.compose.ui.text.TextStyle,
    mircColorsEnabled: Boolean,
    ansiColorsEnabled: Boolean = false,
    linkStyle: SpanStyle,
    onAnnotationClick: (String, String) -> Unit,
) {
    IrcLinkifiedText(
        text = text,
        mircColorsEnabled = mircColorsEnabled,
        ansiColorsEnabled = ansiColorsEnabled,
        linkStyle = linkStyle,
        onAnnotationClick = onAnnotationClick,
        style = style.copy(fontSize = fontSizeSp.sp),
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

/**
 * The character a key event types, or null when the event is a shortcut, a
 * modifier or a control key rather than text. Space counts as null because it
 * activates whatever control has focus.
 */
internal fun typedCharacter(ev: KeyEvent): Char? {
    if (ev.type != KeyEventType.KeyDown) return null
    if (ev.isCtrlPressed || ev.isAltPressed || ev.isMetaPressed) return null
    val code = ev.utf16CodePoint
    if (code == 0 || code == ' '.code || Character.isISOControl(code)) return null
    return code.toChar()
}
