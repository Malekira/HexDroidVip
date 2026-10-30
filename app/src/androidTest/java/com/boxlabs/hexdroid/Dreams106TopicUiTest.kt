/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.boxlabs.hexdroid.data.ThemeMode
import com.boxlabs.hexdroid.ui.ChatScreen
import com.boxlabs.hexdroid.ui.mircColor
import com.boxlabs.hexdroid.ui.theme.HexDroidIRCTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class Dreams106TopicUiTest {
    @get:Rule val compose = createComposeRule()
    private val key = "demo-network::#demo"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val sent = mutableListOf<String>()
    private fun state(topic: String? = "Topic anterior") = UiState(selectedBuffer = key,
        buffers = mapOf(key to UiBuffer("#demo", topic = topic, modeString = "+nt")),
        nicklists = mapOf(key to listOf("@OwnDemoNick", "+DemoNick01")),
        connections = mapOf("demo-network" to NetConnState(connected = true, myNick = "OwnDemoNick")),
        showBufferList = false, showNickList = false)

    @Composable private fun Chat(state: UiState) {
        ChatScreen(state, onSelectBuffer = {}, onSend = { sent += it }, onDisconnect = {}, onReconnect = {}, onExit = {},
            onToggleBufferList = {}, onToggleNickList = {}, onToggleChannelsOnly = {}, onWhois = {},
            onIgnoreNick = { _, _ -> }, onUnignoreNick = { _, _ -> }, onIgnoreNotifications = { _, _ -> },
            onUnignoreNotifications = { _, _ -> }, onRefreshNicklist = {}, onRefreshChannelModes = {},
            onOpenList = {}, onOpenSettings = {}, onOpenNetworks = {}, onOpenTransfers = {}, onSysInfo = {}, onAbout = {}, onUpdateSettings = {})
    }

    private fun layout(tag: String): androidx.compose.ui.text.TextLayoutResult {
        val result = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }

    @Test fun dreams106DarkTopicAndControlsHaveContrastEvenWithALightChatAndKeepIrcColors() {
        val background = 0xff161b22.toInt()
        val theme = PersonalTheme(darkColors = mapOf("CHAT" to 0xffedfff7.toInt(), "TOP" to background,
            "UI_TEXT" to 0xff000000.toInt(), "BUTTON" to 0xff161b22.toInt()))
        val text = "Tema de prueba ñ 🙂 https://example.invalid/ \u000304Rojo\u000f " + "Texto extenso del topic. ".repeat(20)
        compose.setContent { HexDroidIRCTheme(themeMode = ThemeMode.DARK, personalTheme = theme) { Chat(state(text)) } }
        val styled = layout("channel-topic-text")
        assertTrue(themeContrast(styled.layoutInput.style.color.toArgb(), background) >= 4.5)
        assertTrue(styled.layoutInput.text.spanStyles.any { it.item.color == mircColor(4) })
        val image = compose.onNodeWithTag("channel-topic-bar").captureToImage().toPixelMap()
        assertEquals(background, image[0, image.height / 2].toArgb())
        compose.onNodeWithTag("channel-topic-edit").assertIsEnabled()
        compose.onNodeWithTag("channel-topic-expand").assertExists().performClick()
        assertTrue(layout("channel-topic-text").lineCount > 2)
    }

    @Test fun dreams106VisibleMenuEditsAndClearsATopicWithoutPretendingTheServerConfirmedIt() {
        var current by mutableStateOf(state())
        compose.setContent { HexDroidIRCTheme { Chat(current) } }
        compose.onNodeWithTag("general-menu-button").performClick()
        compose.onNodeWithText(context.getString(R.string.topic_edit_title)).performClick()
        compose.onNodeWithTag("topic-editor-input").assertTextContains("Topic anterior").performTextReplacement("Nuevo  topic ñ 🙂")
        compose.onNodeWithTag("topic-editor-save").performClick()
        compose.runOnIdle { assertEquals(listOf("/topic #demo :Nuevo  topic ñ 🙂"), sent) }
        compose.onNodeWithTag("channel-topic-text", useUnmergedTree = true).assertTextEquals("Topic anterior")
        compose.runOnIdle { current = state("Nuevo  topic ñ 🙂") }
        compose.onNodeWithTag("channel-topic-text", useUnmergedTree = true).assertTextEquals("Nuevo  topic ñ 🙂")
        compose.onNodeWithTag("channel-topic-edit").performClick()
        compose.onNodeWithTag("topic-editor-input").performTextReplacement("Inválido\nQUIT")
        compose.onNodeWithTag("topic-editor-save").assertIsNotEnabled()
        compose.onNodeWithTag("topic-editor-input").performTextReplacement("")
        compose.onNodeWithTag("topic-editor-save").performClick()
        compose.runOnIdle { assertEquals("/topic #demo :", sent.last()); current = state("") }
        compose.onNodeWithTag("channel-topic-bar").assertDoesNotExist()
        compose.onNodeWithTag("general-menu-button").performClick()
        compose.onNodeWithText(context.getString(R.string.topic_edit_title)).performClick()
        compose.onNodeWithTag("topic-editor").assertIsDisplayed()
    }

    @Test fun dreams106TopicUiRespectsLivePermissionsAndDisconnection() {
        var current by mutableStateOf(state().copy(nicklists = mapOf(key to listOf("OwnDemoNick", "@DemoNick01"))))
        compose.setContent { HexDroidIRCTheme { Chat(current) } }
        compose.onNodeWithTag("channel-topic-edit").assertIsNotEnabled()
        compose.runOnIdle { current = current.copy(buffers = mapOf(key to UiBuffer("#demo", topic = "Topic", modeString = "+ns"))) }
        compose.onNodeWithTag("channel-topic-edit").assertIsEnabled()
        compose.runOnIdle { current = current.copy(connections = mapOf("demo-network" to NetConnState(connected = false, myNick = "OwnDemoNick"))) }
        compose.onNodeWithTag("channel-topic-edit").assertIsNotEnabled()
        compose.runOnIdle { current = state().copy(nicklists = mapOf(key to listOf("@DemoNick01"))) }
        compose.onNodeWithTag("channel-topic-edit").assertIsNotEnabled()
    }
}
