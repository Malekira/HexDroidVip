/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.boxlabs.hexdroid.ui.*
import org.junit.Assert.*
import org.junit.Test

class Dreams107FormatTest {
    @Test fun topicStylesPreserveSelectedUnicodeAndCanBeRemovedWithoutDeletingText() {
        val plain = "Antes Hola ñ 🙂 después"
        var value = TextFieldValue(plain, TextRange(6, 15))
        for (code in listOf('\u0002', '\u001d', '\u001f')) value = toggleFormat(value, code)
        value = colourFormat(value, 4, 2)
        assertEquals(plain, stripIrcFormatting(value.text))
        assertTrue(value.text.contains("\u000304,02"))
        val command = topicEditCommand("#demo", value.text)
        assertEquals("/topic #demo :$plain", stripIrcFormatting(command))
        val cleared = clearFormat(value.copy(selection = TextRange.Zero))
        assertEquals(plain, cleared.text)
    }

    @Test fun numericTextDoesNotExtendTheIrcColorSequence() {
        val plain = "123, texto"
        val value = colourFormat(TextFieldValue(plain, TextRange(0, plain.length)), 4, 2)
        assertTrue(value.text.startsWith("\u000304,02\u0002\u0002"))
        assertEquals(plain, stripIrcFormatting(value.text))
        val transformed = MircInputTransformation().filter(androidx.compose.ui.text.AnnotatedString(value.text))
        assertEquals(plain, transformed.text.text)
        for (i in 0..value.text.length) assertTrue(transformed.offsetMapping.originalToTransformed(i) in 0..plain.length)
    }

    @Test fun formattingAtTheCursorKeepsTheSurroundingTextAndPosition() {
        val value = toggleFormat(TextFieldValue("Antes después", TextRange(6)), '\u0002')
        assertEquals("Antes \u0002después", value.text)
        assertEquals(7, value.selection.start)
        assertEquals("Antes después", clearFormat(value).text)
    }

    @Test fun partKeepsVioletArrowFullMaskAndReasonWithoutRepeatingTheRoom() {
        val value = membershipEventLine(MembershipEvent.PART, "DemoNick01", "ident@2001:db8::1", "#demo", "Hasta luego")
        assertTrue(value.startsWith("\u00036←\u000314"))
        assertEquals("← PART DemoNick01 (ident@2001:db8::1) [Hasta luego]", stripIrcFormatting(value))
        assertFalse(value.contains("#demo"))
    }
}
