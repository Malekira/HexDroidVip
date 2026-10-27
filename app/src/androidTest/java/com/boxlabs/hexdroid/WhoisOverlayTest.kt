/*
* HexDroidIRC - An IRC Client for Android
* Copyright (C) 2026 boxlabs
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.boxlabs.hexdroid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.boxlabs.hexdroid.ui.WhoisOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WhoisOverlayTest {
    @get:Rule val compose = createComposeRule()
    @Test fun roomRemainsWhilePanelMovesMinimizesRestoresAndCloses() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        var windows by mutableStateOf(listOf(WhoisWindow(1, "red", "Alice", mapOf("nick" to "Alice"), (1..80).map { "WHOIS line $it" })))
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    Text("#dreams remains open")
                    WhoisOverlay(windows, mapOf("red" to "Network"),
                        onMinimize = { id, min -> windows = windows.map { if (it.id == id) it.copy(minimized = min) else it } },
                        onClose = { id -> windows = windows.filterNot { it.id == id } },
                        onMove = { id, x, y -> windows = windows.map { if (it.id == id) it.copy(offsetX = x, offsetY = y) else it } },
                    )
                }
            }
        }
        compose.onNodeWithText("WHOIS — Alice").assertIsDisplayed()
        compose.onNodeWithText("WHOIS — Alice").performTouchInput { swipe(center, center + Offset(0f, 80f), 500) }
        compose.onNodeWithTag("whois-content").performTouchInput { swipeUp() }
        val scrollRange = compose.onNodeWithTag("whois-content").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(scrollRange.value() > 0f)
        compose.onNodeWithText(ctx.getString(R.string.p2_copy)).performClick()
        compose.runOnIdle {
            val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertTrue(clipboard.primaryClip!!.getItemAt(0).coerceToText(ctx).contains("WHOIS line 80"))
        }
        var movedY = 0f
        compose.runOnIdle { movedY = windows.single().offsetY; assertTrue(movedY > 0f) }
        compose.onNodeWithContentDescription(ctx.getString(R.string.p2_minimize)).performClick()
        compose.onNodeWithText("#dreams remains open").assertIsDisplayed()
        compose.onNodeWithText("WHOIS — Alice").assertDoesNotExist()
        compose.onNodeWithContentDescription(ctx.getString(R.string.p2_restore) + " Alice").performClick()
        compose.onNodeWithText("WHOIS — Alice").assertIsDisplayed()
        compose.runOnIdle { assertEquals(movedY, windows.single().offsetY, 0.1f) }
        compose.onNodeWithContentDescription(ctx.getString(R.string.p2_close)).performClick()
        compose.onNodeWithText("WHOIS — Alice").assertDoesNotExist()
        compose.onNodeWithText("#dreams remains open").assertIsDisplayed()
    }
}
