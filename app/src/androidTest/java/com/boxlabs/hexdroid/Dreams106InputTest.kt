/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.boxlabs.hexdroid.ui.ChatScreen
import com.boxlabs.hexdroid.ui.theme.HexDroidIRCTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercise the real composer's Android InputConnection, not just Compose's text semantics. */
@OptIn(ExperimentalTestApi::class)
class Dreams106InputTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sent = mutableListOf<String>()

    @Composable private fun RealChat() {
        val key = "demo-network::#demo"
        ChatScreen(UiState(selectedBuffer = key, buffers = mapOf(key to UiBuffer("#demo")), showBufferList = false, showNickList = false),
            onSelectBuffer = {}, onSend = { sent.add(it) }, onDisconnect = {}, onReconnect = {}, onExit = {},
            onToggleBufferList = {}, onToggleNickList = {}, onToggleChannelsOnly = {}, onWhois = {},
            onIgnoreNick = { _, _ -> }, onUnignoreNick = { _, _ -> }, onIgnoreNotifications = { _, _ -> },
            onUnignoreNotifications = { _, _ -> }, onRefreshNicklist = {}, onRefreshChannelModes = {},
            onOpenList = {}, onOpenSettings = {}, onOpenNetworks = {}, onOpenTransfers = {}, onSysInfo = {}, onAbout = {}, onUpdateSettings = {})
    }

    private fun startWithMessages() {
        compose.setContent { HexDroidIRCTheme { RealChat() } }
        val input = compose.onNodeWithTag("chat-input")
        val sendLabel = compose.activity.getString(R.string.chat_send_message)
        for (number in 1..3) {
            input.performTextReplacement("Texto de prueba $number")
            compose.onNodeWithContentDescription(sendLabel).performClick()
        }
        input.performClick()
    }

    private fun editor(view: View): View? {
        if (view.onCheckIsTextEditor()) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) editor(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun ime(action: (InputConnection) -> Unit) {
        compose.waitForIdle()
        compose.runOnIdle {
            val owner = editor(compose.activity.window.decorView)
            assertNotNull("The real chat must expose an Android text editor", owner)
            val connection = owner!!.onCreateInputConnection(EditorInfo())
            assertNotNull("Android IME connection must be available", connection)
            action(connection!!)
        }
        compose.waitForIdle()
    }

    @Test fun dreams106ImeDeleteProtocolsRecallInOrderAndNeverSend() {
        startWithMessages()
        val input = compose.onNodeWithTag("chat-input")
        ime { assertTrue(it.deleteSurroundingText(1, 0)) }
        input.assertTextEquals("Texto de prueba 3")
        ime { assertTrue(it.deleteSurroundingTextInCodePoints(1, 0)) }
        input.assertTextEquals("Texto de prueba 2")
        ime {
            it.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            it.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
        input.assertTextEquals("Texto de prueba 1")
        compose.runOnIdle { assertEquals(3, sent.size) }
    }

    @Test fun dreams106FieldTouchAndTypingReturnImeToOrdinaryUnicodeDeletion() {
        startWithMessages()
        val input = compose.onNodeWithTag("chat-input")
        ime { it.deleteSurroundingText(1, 0) }
        input.assertTextEquals("Texto de prueba 3")
        input.performClick()
        input.performTextReplacement("Editado 🙂")
        ime { it.deleteSurroundingTextInCodePoints(1, 0) }
        input.assertTextEquals("Editado ")
        ime { it.commitText("x", 1) }
        input.assertTextEquals("Editado x")
        ime { it.deleteSurroundingText(1, 0) }
        input.assertTextEquals("Editado ")
        compose.runOnIdle { assertEquals(3, sent.size) }
    }

    @Test fun dreams106HardwareBackspaceUsesSameHistoryAndNormalDeleteAfterEditing() {
        startWithMessages()
        val input = compose.onNodeWithTag("chat-input")
        input.performKeyInput { pressKey(Key.Backspace) }
        input.assertTextEquals("Texto de prueba 3")
        input.performKeyInput { pressKey(Key.Backspace) }
        input.assertTextEquals("Texto de prueba 2")
        input.performTextReplacement("Borrar")
        input.performKeyInput { pressKey(Key.Backspace) }
        input.assertTextEquals("Borra")
        compose.runOnIdle { assertEquals(3, sent.size) }
    }
}
