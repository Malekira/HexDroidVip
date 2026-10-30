/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid.ui

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/** Samsung Keyboard and Gboard may send deletion commands instead of key events. */
internal class RecallInputConnection(target: InputConnection, private val onBackspace: (Boolean) -> Boolean) :
    InputConnectionWrapper(target, false) {
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        if (beforeLength > 0 && afterLength == 0 && onBackspace(false)) true
        else super.deleteSurroundingText(beforeLength, afterLength)

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        if (beforeLength > 0 && afterLength == 0 && onBackspace(false)) true
        else super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)

    override fun sendKeyEvent(event: KeyEvent): Boolean =
        if (event.keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN && onBackspace(event.repeatCount > 0)) true
        else super.sendKeyEvent(event)
}

/** Stable interceptor: typing must not recreate the IME session on every character. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun BackspaceRecallInput(onBackspace: (Boolean) -> Boolean, content: @Composable () -> Unit) {
    val latestBackspace by rememberUpdatedState(onBackspace)
    val interceptor = remember {
        PlatformTextInputInterceptor { request, next ->
            next.startInputMethod(object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
                    RecallInputConnection(request.createInputConnection(outAttributes)) { repeat -> latestBackspace(repeat) }
            })
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}
