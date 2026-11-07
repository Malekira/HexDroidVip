/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.boxlabs.hexdroid.data.SettingsRepository
import com.boxlabs.hexdroid.ui.ChatScreen
import com.boxlabs.hexdroid.ui.theme.HexDroidIRCTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class Dreams110SidebarTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val first = "tree-demo-a"
    private val second = "tree-demo-b"
    private var state by mutableStateOf(UiState())
    private val selections = mutableListOf<String>()
    private val commands = mutableListOf<String>()

    private fun fixture(tabs: Boolean) {
        val base = SettingsRepository(context).defaultNetworks().first().copy(host = "irc.example.invalid", autoConnect = false)
        state = UiState(networks = listOf(base.copy(id = first, name = "Red de prueba A"), base.copy(id = second, name = "Red de prueba B")),
            activeNetworkId = first, selectedBuffer = "$first::#demo", showBufferList = true, showNickList = false,
            settings = UiSettings(networkTabs = tabs, networkTabsAtBottom = false),
            connections = mapOf(first to NetConnState(connected = true, chanTypes = "#&"), second to NetConnState(connected = true, chanTypes = "#&")),
            buffers = linkedMapOf("$first::*server*" to UiBuffer("*server*"),
                "$first::#demo" to UiBuffer("#demo", unread = 2), "$first::DemoNick01" to UiBuffer("DemoNick01", unread = 3, highlights = 1),
                "$first::$WHOIS_BUFFER" to UiBuffer(WHOIS_BUFFER), "$first::$NOTICES_BUFFER" to UiBuffer(NOTICES_BUFFER),
                "$second::*server*" to UiBuffer("*server*"), "$second::&other" to UiBuffer("&other"), "$second::DemoNick02" to UiBuffer("DemoNick02")))
    }

    @Composable private fun Chat(phone: Boolean) {
        val configuration = Configuration(context.resources.configuration).apply {
            screenWidthDp = if (phone) 400 else 900
            orientation = if (phone) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
        }
        CompositionLocalProvider(LocalConfiguration provides configuration) { HexDroidIRCTheme {
            ChatScreen(state, onSelectBuffer = { selections += it; state = state.copy(selectedBuffer = it) }, onSend = { commands += it },
                onDisconnect = {}, onReconnect = {}, onExit = {}, onToggleBufferList = { state = state.copy(showBufferList = !state.showBufferList) },
                onToggleNickList = {}, onToggleChannelsOnly = {}, onWhois = {}, onIgnoreNick = { _, _ -> }, onUnignoreNick = { _, _ -> },
                onIgnoreNotifications = { _, _ -> }, onUnignoreNotifications = { _, _ -> }, onRefreshNicklist = {}, onRefreshChannelModes = {},
                onOpenList = {}, onOpenSettings = {}, onOpenNetworks = {}, onOpenTransfers = {}, onSysInfo = {}, onAbout = {}, onUpdateSettings = {},
                onToggleNetworkExpanded = { id -> state = state.copy(collapsedNetworkIds = if (id in state.collapsedNetworkIds) state.collapsedNetworkIds - id else state.collapsedNetworkIds + id) })
        } }
    }

    private fun openDrawer() { compose.onNodeWithText("☰").performClick() }
    private fun group(kind: String, network: String = first) = compose.onNodeWithTag("sidebar-group-$network-$kind")
    private fun row(name: String, network: String = first) = compose.onNodeWithTag("sidebar-buffer-$network::$name")
    private fun collapsed(kind: String, network: String = first) {
        group(kind, network).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.dreams_sidebar_collapsed)))
    }

    @Test fun dreams110PhoneNetworkTabsShowTwoIndependentTreesAndKeepSpecialBuffersSeparate() {
        fixture(tabs = true)
        compose.setContent { Chat(phone = true) }
        openDrawer()
        group("rooms").assertIsDisplayed(); group("privates").assertIsDisplayed(); group("other").assertIsDisplayed()
        group("rooms").performClick(); collapsed("rooms")
        row("#demo").assertDoesNotExist(); row("DemoNick01").assertIsDisplayed(); row(WHOIS_BUFFER).assertIsDisplayed()
        group("privates").performClick(); collapsed("privates")
        row("DemoNick01").assertDoesNotExist(); row(NOTICES_BUFFER).assertIsDisplayed()
        compose.runOnIdle { assertTrue(selections.isEmpty()); assertTrue(commands.isEmpty()); assertEquals(2, state.buffers.getValue("$first::#demo").unread) }
        group("rooms").performClick()
        row("#demo").performClick()
        compose.runOnIdle { assertEquals(listOf("$first::#demo"), selections) }
        openDrawer()
        collapsed("privates"); row("DemoNick01").assertDoesNotExist(); row("#demo").assertIsDisplayed()
        group("privates").performClick()
        compose.onNode(hasText("✕") and hasAnyAncestor(hasTestTag("sidebar-buffer-$first::DemoNick01")), useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(listOf("/closekey $first::DemoNick01"), commands) }
    }

    @Test fun dreams110PhoneTabsRememberEachNetworksTreesAndRefreshUnreadBadgesWhileCollapsed() {
        fixture(tabs = true)
        compose.setContent { Chat(phone = true) }
        openDrawer()
        group("rooms").performClick()
        compose.onNodeWithTag("sidebar-network-tab-$second").performClick()
        row("&other", second).assertIsDisplayed(); row("DemoNick02", second).assertIsDisplayed()
        group("privates", second).performClick(); row("DemoNick02", second).assertDoesNotExist()
        compose.onNodeWithTag("sidebar-network-tab-$first").performClick()
        collapsed("rooms"); row("#demo").assertDoesNotExist(); row("DemoNick01").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(buffers = state.buffers + ("$first::#demo" to state.buffers.getValue("$first::#demo").copy(unread = 7))) }
        compose.onNodeWithTag("sidebar-group-unread-$first-rooms", useUnmergedTree = true).assertTextEquals("7")
        compose.onNodeWithTag("sidebar-group-unread-$first-privates", useUnmergedTree = true).assertTextEquals("3")
        compose.runOnIdle { assertTrue(selections.isEmpty()); assertTrue(commands.isEmpty()) }
    }

    @Test fun dreams110TreeModeKeepsIndependentGroupsThroughNetworkCollapseAndTheRoomsOnlyFilter() {
        fixture(tabs = false)
        compose.setContent { Chat(phone = false) }
        group("rooms").performClick(); row("#demo").assertDoesNotExist(); row("DemoNick01").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(collapsedNetworkIds = setOf(first)) }
        group("rooms").assertDoesNotExist(); group("privates").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(collapsedNetworkIds = emptySet()) }
        collapsed("rooms"); row("DemoNick01").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(channelsOnly = true) }
        group("privates").assertDoesNotExist(); group("other").assertDoesNotExist(); collapsed("rooms")
        group("rooms").performClick(); row("#demo").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(channelsOnly = false, settings = state.settings.copy(networkTabs = true)) }
        row("#demo").assertIsDisplayed(); row("DemoNick01").assertIsDisplayed()
    }

    @Test fun dreams110GroupExpansionSurvivesSavedStateRestorationWithoutChangingConversations() {
        fixture(tabs = true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Chat(phone = false) }
        group("privates").performClick(); row("DemoNick01").assertDoesNotExist()
        val buffers = state.buffers
        restoration.emulateSavedInstanceStateRestore()
        collapsed("privates"); row("DemoNick01").assertDoesNotExist(); row("#demo").assertIsDisplayed()
        group("privates").performClick(); row("DemoNick01").assertIsDisplayed()
        compose.runOnIdle { assertEquals(buffers, state.buffers); assertTrue(selections.isEmpty()); assertTrue(commands.isEmpty()) }
    }
}
