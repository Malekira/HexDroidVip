/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.boxlabs.hexdroid.data.Draft
import com.boxlabs.hexdroid.data.SettingsRepository
import com.boxlabs.hexdroid.ui.ChatScreen
import com.boxlabs.hexdroid.ui.theme.HexDroidIRCTheme
import org.junit.Rule
import org.junit.Test

class ChatNavigationRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun horizontalSwipesAndTabsKeepEachRoomsDraftAndVerticalDragsStayInRoom() {
        val profile = SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext)
            .defaultNetworks().first().copy(id = "navigation-demo", name = "Red de prueba")
        val first = "${profile.id}::#one"
        val second = "${profile.id}::#two"
        val drafts = mutableMapOf<String, Draft>()
        var state by mutableStateOf(UiState(networks = listOf(profile), selectedBuffer = first,
            buffers = mapOf(first to UiBuffer(first), second to UiBuffer(second)),
            showBufferList = false, showNickList = false))
        compose.setContent {
            HexDroidIRCTheme {
                ChatScreen(state, onSelectBuffer = { state = state.copy(selectedBuffer = it) }, onSend = {},
                    onDisconnect = {}, onReconnect = {}, onExit = {}, onToggleBufferList = {}, onToggleNickList = {},
                    onToggleChannelsOnly = {}, onWhois = {}, onIgnoreNick = { _, _ -> }, onUnignoreNick = { _, _ -> },
                    onIgnoreNotifications = { _, _ -> }, onUnignoreNotifications = { _, _ -> },
                    onRefreshNicklist = {}, onRefreshChannelModes = {}, onOpenList = {}, onOpenSettings = {},
                    onOpenNetworks = {}, onOpenTransfers = {}, onSysInfo = {}, onAbout = {}, onUpdateSettings = {},
                    draftFor = { drafts[it] ?: Draft.EMPTY },
                    onDraftChanged = { key, text, cursor -> drafts[key] = Draft(text, cursor) })
            }
        }
        val input = compose.onNodeWithTag("chat-input")
        val messages = compose.onNodeWithTag("chat-messages")
        val firstTab = compose.onNodeWithTag("conversation-tab:$first")
        val secondTab = compose.onNodeWithTag("conversation-tab:$second")
        firstTab.assertIsSelected()
        input.performTextInput("Borrador de la primera sala")
        messages.performTouchInput { swipeLeft() }
        secondTab.assertIsSelected()
        input.assertTextEquals("")
        input.performTextInput("Borrador de la segunda sala")
        messages.performTouchInput { swipeRight() }
        firstTab.assertIsSelected()
        input.assertTextEquals("Borrador de la primera sala")
        secondTab.performClick()
        input.assertTextEquals("Borrador de la segunda sala")
        messages.performTouchInput { swipeUp() }
        secondTab.assertIsSelected()
        input.performTouchInput { swipeRight() }
        secondTab.assertIsSelected()
        messages.performTouchInput { swipeLeft() }
        secondTab.assertIsSelected()
    }
}
