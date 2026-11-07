/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.boxlabs.hexdroid.data.SettingsRepository
import com.boxlabs.hexdroid.connection.ProxyType
import com.boxlabs.hexdroid.ui.*
import com.boxlabs.hexdroid.ui.theme.HexDroidIRCTheme
import kotlinx.collections.immutable.persistentListOf
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class Dreams109UiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Composable private fun Chat(state: UiState) {
        ChatScreen(state, onSelectBuffer = {}, onSend = {}, onDisconnect = {}, onReconnect = {}, onExit = {},
            onToggleBufferList = {}, onToggleNickList = {}, onToggleChannelsOnly = {}, onWhois = {},
            onIgnoreNick = { _, _ -> }, onUnignoreNick = { _, _ -> }, onIgnoreNotifications = { _, _ -> },
            onUnignoreNotifications = { _, _ -> }, onRefreshNicklist = {}, onRefreshChannelModes = {},
            onOpenList = {}, onOpenSettings = {}, onOpenNetworks = {}, onOpenTransfers = {}, onSysInfo = {}, onAbout = {}, onUpdateSettings = {})
    }

    @Test fun dreams109HistoryControlsExposeFolderSelectionAndPersistTheChosenGroupingInTheirCallback() {
        var settings by mutableStateOf(UiSettings(settingsOnePage = true))
        compose.setContent { HexDroidIRCTheme {
            SettingsScreen(UiState(settings = settings), onBack = {}, onUpdate = { settings = settings.it() }, onRunTour = {}, onOpenNetworks = {}, onOpenIgnoreList = {})
        } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("history-choose-folder"))
        compose.onNodeWithTag("history-choose-folder").performScrollTo().assertHasClickAction()
        compose.onNodeWithTag("history-combined").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Dividir los archivos por día").performScrollTo().performClick()
        compose.onNodeWithTag("history-daily").assertIsOn()
        compose.runOnIdle { assertTrue(settings.logDailyFiles); assertTrue(settings.logCombineConversations) }
        compose.onNodeWithTag("history-export-organized").performScrollTo().assertHasClickAction()
        compose.onNodeWithTag("history-per-conversation").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertFalse(settings.logCombineConversations); assertTrue(settings.logDailyFiles) }
    }

    @Test fun dreams109RoomAndPrivateSectionsKeepWhoisOutsidePrivateConversations() {
        val id = "grouped-demo"
        val profile = SettingsRepository(context).defaultNetworks().first().copy(id = id, name = "Red de prueba", host = "irc.example.invalid")
        val room = "$id::#demo"; val pv = "$id::DemoNick01"
        val configuration = Configuration(context.resources.configuration).apply { screenWidthDp = 900 }
        val state = UiState(networks = listOf(profile), activeNetworkId = id, selectedBuffer = room,
            settings = UiSettings(networkTabsAtBottom = false),
            buffers = mapOf("$id::*server*" to UiBuffer("*server*"), room to UiBuffer("#demo"), pv to UiBuffer("DemoNick01"), "$id::$WHOIS_BUFFER" to UiBuffer(WHOIS_BUFFER)),
            showBufferList = true, showNickList = false)
        compose.setContent { CompositionLocalProvider(LocalConfiguration provides configuration) { HexDroidIRCTheme { Chat(state) } } }
        val rooms = compose.onNodeWithTag("sidebar-group-$id-rooms").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val privates = compose.onNodeWithTag("sidebar-group-$id-privates").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val others = compose.onNodeWithTag("sidebar-group-$id-other").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val roomRow = compose.onNodeWithText("#demo").fetchSemanticsNode().boundsInRoot
        val pvRow = compose.onNodeWithText("DemoNick01").fetchSemanticsNode().boundsInRoot
        assertTrue(rooms.bottom <= roomRow.top); assertTrue(roomRow.bottom <= privates.top)
        assertTrue(privates.bottom <= pvRow.top); assertTrue(pvRow.bottom <= others.top)
    }

    @Test fun dreams109ChatZonaPhotosAppearInPrivatesAndOptionallyMessagesWithMetadataPriorityAndAnOffSwitch() {
        val id = "avatar-demo"
        val key = "$id::DemoNick01"
        val profile = SettingsRepository(context).defaultNetworks().first().copy(id = id, host = "irc.chatzona.org", proxyType = ProxyType.NONE)
        val msg = UiMessage(1, System.currentTimeMillis(), "DemoNick01", "Mensaje de prueba")
        var state by mutableStateOf(UiState(networks = listOf(profile), activeNetworkId = id, selectedBuffer = key,
            buffers = mapOf(key to UiBuffer("DemoNick01", log = BufferLog(messages = persistentListOf(msg)))),
            connections = mapOf(id to NetConnState(myNick = "OwnDemoNick")), showBufferList = false, showNickList = false,
            settings = UiSettings(chatZonaAvatarsEnabled = true, chatZonaAvatarsInMessages = true, imagePreviewsEnabled = false)))
        val urls = CopyOnWriteArrayList<String>()
        val image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap()
        val loader: suspend (String, Long) -> androidx.compose.ui.graphics.ImageBitmap? = { url, _ -> urls += url; image }
        compose.setContent { CompositionLocalProvider(LocalAvatarLoader provides loader) { HexDroidIRCTheme { Chat(state) } } }
        compose.onAllNodesWithTag("avatar-DemoNick01").assertCountEquals(2)
        compose.waitUntil(5_000) { urls.any { it == "https://chat-avatars.chateagratis.net/avatar/Chat/DemoNick01" } }
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(chatZonaAvatarsInMessages = false)) }
        compose.onAllNodesWithTag("avatar-DemoNick01").assertCountEquals(1)
        val custom = "https://images.example.invalid/custom.jpg"
        compose.runOnIdle { state = state.copy(connections = mapOf(id to NetConnState(myNick = "OwnDemoNick", avatarUrls = mapOf("demonick01" to custom)))) }
        compose.waitUntil(5_000) { urls.contains(custom) }
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(chatZonaAvatarsEnabled = false, imagePreviewsEnabled = true)) }
        compose.onAllNodesWithTag("avatar-DemoNick01").assertCountEquals(0)
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(chatZonaAvatarsEnabled = true), networks = listOf(profile.copy(host = "irc.other.invalid"))) }
        compose.onAllNodesWithTag("avatar-DemoNick01").assertCountEquals(0)
        compose.runOnIdle { state = state.copy(networks = listOf(profile.copy(proxyType = ProxyType.SOCKS5))) }
        compose.onAllNodesWithTag("avatar-DemoNick01").assertCountEquals(0)
    }
}
