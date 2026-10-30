/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.data.NetworkProfile
import com.boxlabs.hexdroid.ui.*
import com.boxlabs.hexdroid.ui.theme.HexDroidIRCTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class Dreams104UiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dreams104TouchHistoryRecoversEditsAndRestoresDraftWithoutSending() {
        val sent = mutableListOf<String>()
        val key = "demo-network::#demo"
        val state = UiState(selectedBuffer = key, buffers = mapOf(key to UiBuffer("#demo")), showBufferList = false, showNickList = false)
        compose.setContent { HexDroidIRCTheme {
            ChatScreen(state, onSelectBuffer = {}, onSend = { sent.add(it) }, onDisconnect = {}, onReconnect = {}, onExit = {},
                onToggleBufferList = {}, onToggleNickList = {}, onToggleChannelsOnly = {}, onWhois = {},
                onIgnoreNick = { _, _ -> }, onUnignoreNick = { _, _ -> }, onIgnoreNotifications = { _, _ -> },
                onUnignoreNotifications = { _, _ -> }, onRefreshNicklist = {}, onRefreshChannelModes = {},
                onOpenList = {}, onOpenSettings = {}, onOpenNetworks = {}, onOpenTransfers = {}, onSysInfo = {}, onAbout = {}, onUpdateSettings = {})
        } }
        val input = compose.onNodeWithTag("chat-input")
        compose.onNodeWithTag("input-history-previous").assertIsNotEnabled()
        input.performTextInput("Primer mensaje 🙂")
        compose.onNodeWithContentDescription(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_send_message)).performClick()
        input.performTextInput("borrador sin enviar")
        compose.onNodeWithTag("input-history-previous").performClick()
        input.assertTextEquals("Primer mensaje 🙂")
        input.performTextReplacement("Primer mensaje editado")
        compose.onNodeWithTag("input-history-next").performClick()
        input.assertTextEquals("borrador sin enviar")
        compose.runOnIdle { assertEquals(listOf("Primer mensaje 🙂"), sent) }
    }

    @Test fun dreams104NativeSettingsKeepNetworkModesAndAvatarSeparate() {
        val net = com.boxlabs.hexdroid.data.SettingsRepository(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)
            .defaultNetworks().first().copy(id = "demo-network", name = "Red de prueba", host = "irc.example.invalid", autoConnect = false)
        var settings by mutableStateOf(UiSettings())
        var desired: Triple<String, Char, Boolean>? = null
        var saved: Pair<String, String?>? = null
        compose.setContent { MaterialTheme { Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
            DreamsChatSettings(UiState(settings = settings, networks = listOf(net),
                connections = mapOf(net.id to NetConnState(connected = true, supportedUserModes = "igR", userModesKnown = true))),
                onUpdate = { settings = settings.it() }, onMode = { network, mode, value -> desired = Triple(network, mode, value) },
                onAvatar = { network, value -> saved = network to value })
            VisibleMentionSoundSetting(settings) { settings = settings.it() }
        } } }
        compose.onNodeWithTag("private-mode-g").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(Triple(net.id, 'g', true), desired) }
        compose.onNodeWithTag("private-mode-g").assertIsOff() // no fake server confirmation
        compose.onNodeWithTag("own-avatar-url").performScrollTo().performTextReplacement("http://images.example.invalid/photo.jpg")
        compose.onNodeWithTag("own-avatar-save").assertIsNotEnabled()
        compose.onNodeWithTag("own-avatar-url").performTextReplacement("https://images.example.invalid/photo.jpg")
        compose.onNodeWithTag("own-avatar-save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(net.id to "https://images.example.invalid/photo.jpg", saved) }
        compose.onNodeWithTag("private-floating").performScrollTo().performClick()
        compose.onNodeWithTag("private-active-notice").performScrollTo().assertIsOn()
        compose.onNodeWithTag("visible-mention-sound").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(settings.privateAttemptFloating); assertTrue(settings.privateAttemptActiveNotice); assertTrue(settings.soundMentionsWhileVisible) }
    }

    @Test fun dreams104AvatarViewerShowsFailureRetriesAndDisplaysWholePhoto() {
        val photo = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) for (x in 0 until 200) photo.setPixel(x, y, if (x < 100) android.graphics.Color.RED else android.graphics.Color.BLUE)
        var requests = 0
        var closed by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            if (!closed) AvatarViewer("https://images.example.invalid/photo.jpg", "DemoNick01", { closed = true }) { _, retry ->
                requests++
                if (!retry) null else photo.asImageBitmap()
            }
        } }
        compose.onNodeWithTag("avatar-retry").performClick()
        val image = compose.onNodeWithTag("avatar-full-image").assertIsDisplayed().captureToImage().toPixelMap()
        assertEquals(android.graphics.Color.RED, image[2, image.height / 2].toArgb())
        assertEquals(android.graphics.Color.BLUE, image[image.width - 3, image.height / 2].toArgb())
        compose.runOnIdle { assertEquals(2, requests) }
        compose.onNodeWithText(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.close)).performClick()
        compose.onNodeWithTag("avatar-viewer").assertDoesNotExist()
    }

    @Test fun dreams104FloatingPrivateNoticeClosesWithoutReplacingRoom() {
        var alert by mutableStateOf<PrivateAttemptAlert?>(PrivateAttemptAlert(1, "demo-network", "DemoNick01", "Intento real de privado (+g)"))
        compose.setContent { MaterialTheme { Box { androidx.compose.material3.Text("Sala abierta")
            PrivateAttemptOverlay(alert, "Red de prueba") { alert = null }
        } } }
        compose.onNodeWithText("Sala abierta").assertExists()
        compose.onNodeWithTag("private-attempt-floating").assertIsDisplayed()
        compose.onNodeWithText(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.close)).performClick()
        compose.onNodeWithTag("private-attempt-floating").assertDoesNotExist()
        compose.onNodeWithText("Sala abierta").assertIsDisplayed()
    }
}
