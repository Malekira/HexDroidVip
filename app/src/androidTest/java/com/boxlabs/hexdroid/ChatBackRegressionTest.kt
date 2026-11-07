package com.boxlabs.hexdroid

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.boxlabs.hexdroid.ui.ChatScreen
import com.boxlabs.hexdroid.ui.theme.HexDroidIRCTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatBackRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun backOnlyNavigatesWhenComposerIsEmptyIncludingWhitespaceDrafts() {
        var navigations = 0
        lateinit var dispatcher: OnBackPressedDispatcher
        val key = "network-demo::#demo"
        val state = UiState(selectedBuffer = key, buffers = mapOf(key to UiBuffer(key)),
            showBufferList = false, showNickList = false)
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            BackHandler { navigations++ }
            HexDroidIRCTheme {
                ChatScreen(state, onSelectBuffer = {}, onSend = {}, onDisconnect = {}, onReconnect = {}, onExit = {},
                    onToggleBufferList = {}, onToggleNickList = {}, onToggleChannelsOnly = {}, onWhois = {},
                    onIgnoreNick = { _, _ -> }, onUnignoreNick = { _, _ -> }, onIgnoreNotifications = { _, _ -> },
                    onUnignoreNotifications = { _, _ -> }, onRefreshNicklist = {}, onRefreshChannelModes = {},
                    onOpenList = {}, onOpenSettings = {}, onOpenNetworks = {}, onOpenTransfers = {}, onSysInfo = {},
                    onAbout = {}, onUpdateSettings = {})
            }
        }
        val input = compose.onNodeWithTag("chat-input")
        for (draft in listOf("Borrador sin enviar", "   ")) {
            input.performTextReplacement(draft)
            compose.runOnIdle { dispatcher.onBackPressed() }
            input.assertTextEquals(draft)
            compose.runOnIdle { assertEquals(0, navigations) }
        }
        input.performTextClearance()
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(1, navigations) }
    }
}
