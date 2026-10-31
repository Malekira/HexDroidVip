/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.InterceptPlatformTextInput
import kotlinx.coroutines.awaitCancellation
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.boxlabs.hexdroid.ui.ChatScreen
import com.boxlabs.hexdroid.ui.theme.HexDroidIRCTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercise the real composer's Android InputConnection, not just Compose's text semantics. */
@OptIn(ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class Dreams106InputTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sent = mutableListOf<String>()
    private var connection: InputConnection? = null
    private var currentEditorInfo: EditorInfo? = null

    @Composable private fun RealChat() {
        val key = "demo-network::#demo"
        ChatScreen(UiState(selectedBuffer = key, buffers = mapOf(key to UiBuffer("#demo")), nicklists = mapOf(key to listOf("@DemoNick01")), showBufferList = false, showNickList = false),
            onSelectBuffer = {}, onSend = { sent.add(it) }, onDisconnect = {}, onReconnect = {}, onExit = {},
            onToggleBufferList = {}, onToggleNickList = {}, onToggleChannelsOnly = {}, onWhois = {},
            onIgnoreNick = { _, _ -> }, onUnignoreNick = { _, _ -> }, onIgnoreNotifications = { _, _ -> },
            onUnignoreNotifications = { _, _ -> }, onRefreshNicklist = {}, onRefreshChannelModes = {},
            onOpenList = {}, onOpenSettings = {}, onOpenNetworks = {}, onOpenTransfers = {}, onSysInfo = {}, onAbout = {}, onUpdateSettings = {})
    }

    private fun startWithMessages() {
        compose.setContent { HexDroidIRCTheme {
            // Drive the actual field's Android InputConnection as its sole IME. Calling
            // View.onCreateInputConnection manually while the OS keyboard is active creates
            // competing connections and can finish composition between test operations.
            InterceptPlatformTextInput(interceptor = { request, _ ->
                val info = EditorInfo()
                val active = request.createInputConnection(info)
                connection = active
                currentEditorInfo = info
                try { awaitCancellation() } finally { if (connection === active) connection = null }
            }) { RealChat() }
        } }
        val input = compose.onNodeWithTag("chat-input")
        val sendLabel = compose.activity.getString(R.string.chat_send_message)
        for (number in 1..3) {
            input.performTextReplacement("Texto de prueba $number")
            compose.onNodeWithContentDescription(sendLabel).performClick()
        }
        input.performClick()
    }

    private fun ime(action: (InputConnection) -> Unit) {
        compose.waitUntil(timeoutMillis = 5_000) { connection != null }
        compose.runOnIdle { action(connection!!) }
        compose.waitForIdle()
    }

    @Test fun dreams107ImeDeleteAndHardwareBackspaceNeverRecallMessages() {
        startWithMessages()
        val input = compose.onNodeWithTag("chat-input")
        ime { assertTrue(it.deleteSurroundingText(1, 0)) }
        input.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        input.performKeyInput { pressKey(Key.Backspace) }
        input.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithTag("input-history-backspace").assertContentDescriptionEquals(compose.activity.getString(R.string.dreams_recall_ten)).performClick()
        input.assertTextEquals("Texto de prueba 3")
        ime { it.deleteSurroundingText(1, 0) }
        input.assertTextEquals("Texto de prueba ")
        ime { it.deleteSurroundingTextInCodePoints(1, 0) }
        input.assertTextEquals("Texto de prueba")
        input.performKeyInput { pressKey(Key.Backspace) }
        input.assertTextEquals("Texto de prueb")
        compose.runOnIdle { assertEquals(3, sent.size) }
    }

    @Test fun dreams107ComposingWordsAreNotAutoCompletedOrDuplicated() {
        startWithMessages()
        val input = compose.onNodeWithTag("chat-input")
        ime { it.beginBatchEdit(); assertTrue(it.setComposingText("dem", 1)); it.endBatchEdit() }
        input.assertTextEquals("dem")
        ime { assertTrue(it.setComposingText("demora", 1)) }
        input.assertTextEquals("demora")
        ime { assertTrue(it.commitText("demora", 1)) }
        input.assertTextEquals("demora")
        ime { assertTrue(it.commitText(" ", 1)) }
        input.assertTextEquals("demora ")
        ime { it.beginBatchEdit(); assertTrue(it.setComposingText("maña", 1)); it.endBatchEdit() }
        input.assertTextEquals("demora maña")
        ime { assertTrue(it.setComposingText("mañana", 1)) }
        input.assertTextEquals("demora mañana")
        ime { assertTrue(it.commitText("mañana", 1)) }
        input.assertTextEquals("demora mañana")
        compose.runOnIdle {
            assertNotNull(currentEditorInfo)
            assertEquals(0, currentEditorInfo!!.inputType and android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        }
        compose.runOnIdle { assertEquals(3, sent.size) }
    }

    @Test fun dreams107UnicodeAndSelectedTextAreDeletedNormallyAfterButtonRecall() {
        startWithMessages()
        val input = compose.onNodeWithTag("chat-input")
        compose.onNodeWithTag("input-history-backspace").performClick()
        input.performTextReplacement("Editado 🙂")
        ime { it.deleteSurroundingTextInCodePoints(1, 0) }
        input.assertTextEquals("Editado ")
        ime { it.commitText("x", 1) }
        input.assertTextEquals("Editado x")
        ime { it.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)); it.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)) }
        input.assertTextEquals("Editado ")
        compose.runOnIdle { assertEquals(3, sent.size) }
    }
}
