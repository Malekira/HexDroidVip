/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import com.boxlabs.hexdroid.ui.*
import org.junit.Assert.*
import org.junit.Test

class Dreams108FormatTest {
    private fun rendered(raw: String) = buildAnnotatedString { appendIrcStyledLinkified(raw, SpanStyle(color = Color.Cyan), true) }

    @Test fun topicBackdropMakesExplicitBlackAndBadColorPairsReadableWithoutChangingTheirLettersOrLinks() {
        val text = rendered("\u000301Negro \u000301,01negro sobre negro\u000f #demo 🙂")
        val fg = Color.White
        val bg = Color(0xff161b22)
        val result = readableTopicSpans(text, fg, bg)
        assertEquals(text.text, result.text)
        assertEquals(text.getStringAnnotations(0, text.length), result.getStringAnnotations(0, result.length))
        assertEquals(text.spanStyles, result.spanStyles.take(text.spanStyles.size))
        for (index in result.indices) {
            var actualFg = fg
            var actualBg = bg
            for (span in result.spanStyles) if (index in span.start until span.end) {
                if (span.item.color != Color.Unspecified) actualFg = span.item.color
                if (span.item.background != Color.Unspecified) actualBg = span.item.background
            }
            assertTrue("Unreadable topic character $index", themeContrast(actualFg.toArgb(), actualBg.toArgb()) >= 4.5)
        }
        assertTrue(result.spanStyles.drop(text.spanStyles.size).all { it.item.color == Color.Unspecified })
        assertEquals(Color.Black, text.spanStyles.first { it.item.color != Color.Unspecified }.item.color)
    }

    @Test fun readableTopicsInBothThemesKeepTheirOriginalBackgroundsAndFormatting() {
        for ((fg, bg) in listOf(Color.White to Color.Black, Color.Black to Color.White)) {
            val text = rendered("Sin color \u0002negrita\u0002 \u000304,01rojo")
            assertEquals(text, readableTopicSpans(text, fg, bg))
        }
    }

    @Test fun strikethroughAloneCombinesWithUnderlineAndResetInBothTheEditorAndReceivedText() {
        val raw = "\u001eTachado\u001f ambos\u000f normal"
        val editor = MircInputTransformation().filter(AnnotatedString(raw))
        val received = rendered(raw)
        assertEquals("Tachado ambos normal", received.text)
        assertEquals(received.text, editor.text.text)
        for (text in listOf(editor.text, received)) {
            fun decorationAt(index: Int) = text.spanStyles.filter { index in it.start until it.end }.mapNotNull { it.item.textDecoration }.lastOrNull()
            assertEquals(TextDecoration.LineThrough, decorationAt(0))
            assertTrue(decorationAt(9)!!.contains(TextDecoration.LineThrough))
            assertTrue(decorationAt(9)!!.contains(TextDecoration.Underline))
            assertNull(decorationAt(15))
        }
        for (i in 0..raw.length) assertTrue(editor.offsetMapping.originalToTransformed(i) in 0..received.length)
    }

    @Test fun strikeSelectionPreservesUnicodeSurroundingsAndClearRemovesOnlyFormatting() {
        val plain = "Antes ñ 🙂 después"
        val struck = toggleFormat(TextFieldValue(plain, TextRange(6, 10)), '\u001e')
        assertEquals(plain, stripIrcFormatting(struck.text))
        val colored = topicColourFormat(struck, 4, 2)
        assertTrue(colored.text.contains('\u001e'))
        assertEquals(plain, clearFormat(colored.copy(selection = TextRange.Zero)).text)
    }

    @Test fun strikeAndColorCodesAreSentAsRealTopicDataAndRemainIntactInTheServerReply() {
        val harness = IrcTestHarness("echo-message")
        try {
            val text = "\u000304\u001eTachado\u001e normal ñ 🙂"
            harness.run { harness.client.handleSlashCommand(topicEditCommand("#demo", text).removePrefix("/"), "#demo") }
            harness.server.expect { it == "TOPIC #demo :$text" }
            assertTrue(harness.events.none { it is IrcEvent.Topic })
            harness.server.send(":DemoNick01!ident@host.invalid TOPIC #demo :$text")
            assertEquals(text, harness.await<IrcEvent.Topic> { it.channel == "#demo" }.topic)
        } finally { harness.close() }
    }

    @Test fun realNameChangesStillReachTheProtocolEventLayer() {
        val harness = IrcTestHarness("echo-message setname")
        try {
            harness.server.send(":DemoNick01!ident@host.invalid SETNAME :Nombre de prueba")
            val event = harness.await<IrcEvent.Setname> { it.nick == "DemoNick01" }
            assertEquals("Nombre de prueba", event.newRealname)
        } finally { harness.close() }
    }
}
