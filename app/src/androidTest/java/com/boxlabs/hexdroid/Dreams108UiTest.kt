/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.boxlabs.hexdroid.data.ThemeMode
import com.boxlabs.hexdroid.ui.*
import com.boxlabs.hexdroid.ui.theme.HexDroidIRCTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class Dreams108UiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val sent = mutableListOf<String>()
    private val key = "demo-network::#demo"
    @Composable private fun Chat(topic: String = "Tema de prueba") {
        ChatScreen(UiState(selectedBuffer = key, buffers = mapOf(key to UiBuffer("#demo", topic = topic)),
            nicklists = mapOf(key to listOf("@DemoNick01")), showBufferList = false, showNickList = false),
            onSelectBuffer = {}, onSend = { sent += it }, onDisconnect = {}, onReconnect = {}, onExit = {},
            onToggleBufferList = {}, onToggleNickList = {}, onToggleChannelsOnly = {}, onWhois = {},
            onIgnoreNick = { _, _ -> }, onUnignoreNick = { _, _ -> }, onIgnoreNotifications = { _, _ -> },
            onUnignoreNotifications = { _, _ -> }, onRefreshNicklist = {}, onRefreshChannelModes = {},
            onOpenList = {}, onOpenSettings = {}, onOpenNetworks = {}, onOpenTransfers = {}, onSysInfo = {}, onAbout = {}, onUpdateSettings = {})
    }

    @Test fun dreams108BlackIrcTopicRemainsBlackButHasAReadableLocalBackdropInDarkMode() {
        compose.setContent { HexDroidIRCTheme(themeMode = ThemeMode.DARK) { Chat("\u000301Texto negro\u000f normal") } }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("channel-topic-text", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val text = layouts.single().layoutInput.text
        assertEquals("Texto negro normal", text.text)
        var foreground = layouts.single().layoutInput.style.color
        var background = Color(ThemeRole.TOP.dark)
        for (span in text.spanStyles) if (span.start == 0 && span.end > 0) {
            if (span.item.color != Color.Unspecified) foreground = span.item.color
            if (span.item.background != Color.Unspecified) background = span.item.background
        }
        assertEquals(Color.Black, foreground)
        assertTrue(themeContrast(foreground.toArgb(), background.toArgb()) >= 4.5)
    }

    @Test fun dreams108RecallMatchesTheTabButtonMirroredInItsExistingPositionAndRestoresTheDraftOnLongPress() {
        compose.setContent { HexDroidIRCTheme { Chat() } }
        val input = compose.onNodeWithTag("chat-input")
        for (i in 1..2) {
            input.performTextReplacement("Mensaje $i")
            compose.onNodeWithContentDescription(context.getString(R.string.chat_send_message)).performClick()
        }
        input.performTextReplacement("Borrador")
        val tab = compose.onNodeWithTag("nick-tab-button")
        val recall = compose.onNodeWithTag("input-history-backspace")
        for (button in listOf(tab, recall)) button.assertWidthIsEqualTo(36.dp).assertHeightIsEqualTo(36.dp).assertHasClickAction()
        assertTrue(tab.fetchSemanticsNode().boundsInRoot.right <= input.fetchSemanticsNode().boundsInRoot.left)
        assertTrue(recall.fetchSemanticsNode().boundsInRoot.left >= input.fetchSemanticsNode().boundsInRoot.right)
        val right = tab.captureToImage().toPixelMap()
        val left = recall.captureToImage().toPixelMap()
        assertEquals(right.width, left.width); assertEquals(right.height, left.height)
        var matching = 0
        for (y in 0 until right.height) for (x in 0 until right.width) {
            if (right[x, y].toArgb() == left[left.width - 1 - x, y].toArgb()) matching++
        }
        assertTrue("Matching controls must be horizontal reflections", matching.toDouble() / (right.width * right.height) > 0.95)
        recall.performClick(); input.assertTextEquals("Mensaje 2")
        recall.performClick(); input.assertTextEquals("Mensaje 1")
        recall.performTouchInput { longClick() }
        input.assertTextEquals("Borrador")
        compose.runOnIdle { assertEquals(listOf("Mensaje 1", "Mensaje 2"), sent) }
    }
}
