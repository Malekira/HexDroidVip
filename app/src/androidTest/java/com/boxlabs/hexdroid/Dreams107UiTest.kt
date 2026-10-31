/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.boxlabs.hexdroid.data.NetworkProfile
import com.boxlabs.hexdroid.data.SettingsRepository
import com.boxlabs.hexdroid.ui.*
import com.boxlabs.hexdroid.ui.theme.HexDroidIRCTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class Dreams107UiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun dreams107ExistingChannelToolsFormatTopicAndSendTheActualIrcCodes() {
        val key = "demo-network::#demo"
        val text = "Hola ñ 🙂"
        val sent = mutableListOf<String>()
        val state = UiState(selectedBuffer = key, buffers = mapOf(key to UiBuffer("#demo", topic = "\u000303$text\u000f", modeString = "+nt")),
            nicklists = mapOf(key to listOf("@OwnDemoNick")),
            connections = mapOf("demo-network" to NetConnState(connected = true, myNick = "OwnDemoNick")),
            showBufferList = false, showNickList = false)
        compose.setContent { HexDroidIRCTheme {
            ChatScreen(state, onSelectBuffer = {}, onSend = { sent += it }, onDisconnect = {}, onReconnect = {}, onExit = {},
                onToggleBufferList = {}, onToggleNickList = {}, onToggleChannelsOnly = {}, onWhois = {},
                onIgnoreNick = { _, _ -> }, onUnignoreNick = { _, _ -> }, onIgnoreNotifications = { _, _ -> },
                onUnignoreNotifications = { _, _ -> }, onRefreshNicklist = {}, onRefreshChannelModes = {},
                onOpenList = {}, onOpenSettings = {}, onOpenNetworks = {}, onOpenTransfers = {}, onSysInfo = {}, onAbout = {}, onUpdateSettings = {})
        } }
        compose.onNodeWithTag("channel-topic-edit").assertDoesNotExist()
        compose.onNodeWithTag("channel-tools-button").performClick()
        compose.onNodeWithTag("topic-format-tools").performScrollTo().performClick()
        compose.onNodeWithTag("topic-select-all").performScrollTo().performClick()
        compose.onNodeWithTag("topic-bold").performScrollTo().performClick()
        compose.onNodeWithTag("topic-italic").performScrollTo().performClick()
        compose.onNodeWithTag("topic-underline").performScrollTo().performClick()
        compose.onNodeWithTag("topic-color-4").performScrollTo().performClick()
        compose.onNodeWithTag("topic-background").performScrollTo().performClick()
        compose.onNodeWithTag("topic-color-2").performScrollTo().performClick()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("topic-preview", useUnmergedTree = true).performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(text, layouts.single().layoutInput.text.text)
        assertTrue(layouts.single().layoutInput.text.spanStyles.any { it.item.color == mircColor(4) && it.item.background == mircColor(2) })
        compose.onNodeWithTag("topic-tools-apply").performScrollTo().performClick()
        compose.runOnIdle {
            val command = sent.single()
            assertEquals("/topic #demo :$text", stripIrcFormatting(command))
            assertTrue(command.contains('\u0002')); assertTrue(command.contains('\u001d')); assertTrue(command.contains('\u001f'))
            assertTrue(command.contains("\u000304,02"))
        }
    }

    @Test fun dreams107ScriptsHaveTheirOwnSettingsCategoryAndOpenTheRealManagerCallback() {
        var opened = false
        compose.setContent { HexDroidIRCTheme {
            SettingsScreen(UiState(settings = UiSettings(settingsOnePage = false)), onBack = {}, onUpdate = {}, onRunTour = {},
                onOpenNetworks = {}, onOpenIgnoreList = {}, onOpenScripts = { opened = true })
        } }
        compose.onNodeWithText(context.getString(R.string.settings_scripts)).performClick()
        compose.onNodeWithTag("scripts-manage").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test fun dreams107PhotoIsEditedAndSavedWithTheRealNetworkIdentityForm() {
        val net = SettingsRepository(context).defaultNetworks().first().copy(id = "photo-form-demo", autoConnect = false)
        var saved: NetworkProfile? = null
        compose.setContent { HexDroidIRCTheme {
            NetworkEditScreen(UiState(settings = UiSettings(settingsOnePage = true), editingNetwork = net),
                onCancel = {}, onSave = { profile, _, _ -> saved = profile })
        } }
        compose.onNodeWithTag("network-profile-avatar").performScrollTo().performTextReplacement("http://images.example.invalid/photo.jpg")
        compose.onNodeWithTag("network-save").assertIsNotEnabled()
        compose.onNodeWithTag("network-profile-avatar").performTextReplacement("https://images.example.invalid/photo.jpg")
        compose.onNodeWithTag("network-save").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("https://images.example.invalid/photo.jpg", saved!!.avatarUrl)
            assertEquals(net.nick, saved!!.nick); assertEquals(net.username, saved!!.username); assertEquals(net.realname, saved!!.realname)
        }
    }
}
