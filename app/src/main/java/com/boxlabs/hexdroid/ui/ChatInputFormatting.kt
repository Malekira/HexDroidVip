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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import com.boxlabs.hexdroid.isAsciiDigit

/** Inline slot for the robot badge on a bot's messages. */
/**
 * An icon button that is exactly [size], for placing in a bar shorter than 48dp.
 *
 * Material's IconButton enforces a 48dp minimum touch target and reports that size to its
 * parent, so in a shorter bar it is clipped and the focus ring loses its top and bottom.
 */
@Composable
internal fun BarIconButton(
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    initialFocus: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .then(if (initialFocus) Modifier.tvInitialFocus() else Modifier)
            .focusHighlight(CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Wrap the selection of [v] in the toggle [code] (bold, italic, underline), or unwrap it when it's
 * already wrapped. With nothing selected, insert [code] at the cursor so it applies to what's typed
 * next. The selection stays on the same text.
 */
internal fun toggleFormat(v: TextFieldValue, code: Char): TextFieldValue {
    val t = v.text
    val s = v.selection.min
    val e = v.selection.max
    if (s == e) return TextFieldValue(t.substring(0, s) + code + t.substring(s), TextRange(s + 1))
    if (s > 0 && e < t.length && t[s - 1] == code && t[e] == code) {
        return TextFieldValue(t.substring(0, s - 1) + t.substring(s, e) + t.substring(e + 1), TextRange(s - 1, e - 1))
    }
    val inner = t.substring(s, e)
    if (inner.length >= 2 && inner.first() == code && inner.last() == code) {
        return TextFieldValue(t.substring(0, s) + inner.substring(1, inner.length - 1) + t.substring(e), TextRange(s, e - 2))
    }
    return TextFieldValue(t.substring(0, s) + code + inner + code + t.substring(e), TextRange(s + 1, e + 1))
}

/**
 * Colour the selection of [v] with mIRC colour [fg] and/or background [bg]; with nothing
 * selected, colour what's typed next. A digit or comma straight after a colour code would be read
 * as part of it, so an empty bold pair separates them where needed.
 */
internal fun colourFormat(v: TextFieldValue, fg: Int?, bg: Int?): TextFieldValue {
    val code = buildString {
        append('\u0003')
        append((fg ?: 99).toString().padStart(2, '0'))
        if (bg != null) append(',').append(bg.toString().padStart(2, '0'))
    }
    fun guard(next: Char?) = if (next != null && (next.isAsciiDigit() || next == ',')) "\u0002\u0002" else ""
    val t = v.text
    val s = v.selection.min
    val e = v.selection.max
    if (s == e) {
        val open = code + guard(t.getOrNull(s))
        return TextFieldValue(t.substring(0, s) + open + t.substring(s), TextRange(s + open.length))
    }
    val inner = t.substring(s, e)
    val open = code + guard(inner.firstOrNull())
    val close = "\u0003" + guard(t.getOrNull(e))
    return TextFieldValue(
        t.substring(0, s) + open + inner + close + t.substring(e),
        TextRange(s + open.length, s + open.length + inner.length),
    )
}

private val FORMAT_CODES = Regex("\u0003(?:\\d{1,2}(?:,\\d{1,2})?)?|\u0004(?:[0-9a-fA-F]{6})?|[\u0002\u000F\u0011\u0016\u001D\u001E\u001F]")

/** Remove formatting codes from the selection of [v], or from the whole input when nothing is selected. */
internal fun clearFormat(v: TextFieldValue): TextFieldValue {
    val t = v.text
    val s = v.selection.min
    val e = v.selection.max
    if (s == e) {
        val nt = FORMAT_CODES.replace(t, "")
        return TextFieldValue(nt, TextRange(nt.length))
    }
    val inner = FORMAT_CODES.replace(t.substring(s, e), "")
    return TextFieldValue(t.substring(0, s) + inner + t.substring(e), TextRange(s, s + inner.length))
}

/** Shows mIRC formatting in the input as it will look, with the control codes hidden. */
internal class MircInputTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.none { it < ' ' && it != '\n' && it != '\t' }) return TransformedText(text, OffsetMapping.Identity)
        val out = AnnotatedString.Builder(raw.length)
        val toOut = IntArray(raw.length + 1)
        val toRaw = ArrayList<Int>(raw.length + 1)
        var bold = false
        var italic = false
        var underline = false
        var fg: Int? = null
        var bg: Int? = null
        var i = 0
        while (i < raw.length) {
            toOut[i] = out.length
            val c = raw[i]
            if (c == '\u0003') {
                var j = i + 1
                val fgDigits = StringBuilder()
                while (j < raw.length && fgDigits.length < 2 && raw[j].isAsciiDigit()) fgDigits.append(raw[j++])
                val bgDigits = StringBuilder()
                if (fgDigits.isNotEmpty() && j + 1 < raw.length && raw[j] == ',' && raw[j + 1].isAsciiDigit()) {
                    j++
                    while (j < raw.length && bgDigits.length < 2 && raw[j].isAsciiDigit()) bgDigits.append(raw[j++])
                }
                if (fgDigits.isEmpty()) { fg = null; bg = null }
                else {
                    fg = fgDigits.toString().toInt()
                    if (bgDigits.isNotEmpty()) bg = bgDigits.toString().toInt()
                }
                for (x in i until j) toOut[x] = out.length
                i = j
                continue
            }
            when (c) {
                '\u0002' -> bold = !bold
                '\u001D' -> italic = !italic
                '\u001F' -> underline = !underline
                '\u000F' -> { bold = false; italic = false; underline = false; fg = null; bg = null }
                else -> if (c < ' ' && c != '\n' && c != '\t') Unit else {
                    toRaw.add(i)
                    out.withStyle(
                        SpanStyle(
                            fontWeight = if (bold) FontWeight.Bold else null,
                            fontStyle = if (italic) FontStyle.Italic else null,
                            textDecoration = if (underline) TextDecoration.Underline else null,
                            color = fg?.let { mircColor(it) } ?: Color.Unspecified,
                            background = bg?.let { mircColor(it) } ?: Color.Unspecified,
                        )
                    ) { append(c) }
                }
            }
            i++
        }
        toOut[raw.length] = out.length
        toRaw.add(raw.length)
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = toOut[offset.coerceIn(0, raw.length)]
            override fun transformedToOriginal(offset: Int) = toRaw[offset.coerceIn(0, toRaw.size - 1)]
        }
        return TransformedText(out.toAnnotatedString(), mapping)
    }
}
