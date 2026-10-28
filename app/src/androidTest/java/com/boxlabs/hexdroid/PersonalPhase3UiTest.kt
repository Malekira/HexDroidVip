/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.toArgb
import com.boxlabs.hexdroid.ui.theme.HexDroidIRCTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.boxlabs.hexdroid.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PersonalPhase3UiTest {
    @get:Rule val compose = createComposeRule()
    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun phase3HeaderAndPopupRetainContrastWithOppositeBackgrounds() {
        val theme = PersonalTheme(darkColors = mapOf("TOP" to 0xff000000.toInt(), "MENU" to 0xffffffff.toInt(), "UI_TEXT" to 0xffffffff.toInt()))
        var headerColor: Int? = null
        var menuColor: Int? = null
        compose.setContent {
            HexDroidIRCTheme(personalTheme = theme) {
                PersonalTopAppBar(title = { headerColor = LocalContentColor.current.toArgb(); Text("Cabecera demo") }, actions = {
                    PersonalDropdownMenu(true, {}) {
                        menuColor = LocalContentColor.current.toArgb()
                        DropdownMenuItem(text = { Text("Opción demo") }, onClick = {})
                    }
                })
            }
        }
        compose.onNodeWithText("Opción demo").assertExists()
        compose.runOnIdle {
            assertEquals(0xffffffff.toInt(), headerColor)
            assertEquals(0xff000000.toInt(), menuColor)
        }
    }
    @Test fun phase3ThemeEditorPreviewsSavesAndDuplicates() {
        var settings by mutableStateOf(UiSettings())
        compose.setContent { MaterialTheme { PersonalThemeSettings(settings) { update -> settings = settings.update() } } }
        compose.onNodeWithText("Crear tema").performClick()
        compose.onNodeWithText("Nombre").performTextReplacement("Tema demo UI")
        compose.onNodeWithText("#RRGGBB").performTextReplacement("123456")
        compose.onNodeWithText("Rojo: 18").assertExists()
        compose.onNodeWithText("Guardar y aplicar").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("Tema demo UI", settings.personalThemes.single().name)
            assertEquals(0xff123456.toInt(), settings.personalThemes.single().color(ThemeRole.CHAT, true))
            assertEquals(settings.personalThemes.single().id, settings.selectedThemeId)
        }
        compose.onNodeWithText("Duplicar").performClick()
        compose.onNodeWithText("Guardar y aplicar").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, settings.personalThemes.size); assertNotEquals(settings.personalThemes[0].id, settings.personalThemes[1].id) }
    }
    @Test fun phase3NickDropdownAndTabChooseDifferentMatchesWithoutLosingText() {
        val nicks = listOf("@DemoNick01", "+DemoNick02")
        var text by mutableStateOf("Hola dem después")
        var cursor by mutableIntStateOf(8)
        var cycle by mutableStateOf<NickCompletion?>(null)
        compose.setContent {
            MaterialTheme {
                Column {
                    Text(text)
                    NickChoiceList(completionMatches(nicks, "dem"), cycle?.let { it.matches[it.index] }) { nick ->
                        val token = cycle?.let { NickToken(it.start, it.cursor, it.cursor, "", it.hadAt) } ?: nickToken(text, cursor)!!
                        val result = completeNickToken(text, token, nick); text = result.first; cursor = result.second
                    }
                    TextButton(onClick = { val result = advanceNickCompletion(text, cursor, nicks, cycle)!!; cycle = result; text = result.text; cursor = result.cursor }) { Text("Tab") }
                }
            }
        }
        compose.onNodeWithText("DemoNick01", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("DemoNick02", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Tab").performClick()
        compose.onNodeWithText("Hola DemoNick01 después").assertExists()
        compose.onNodeWithText("Tab").performClick()
        compose.onNodeWithText("Hola DemoNick02 después").assertExists()
        compose.onNodeWithText("DemoNick01", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Hola DemoNick01 después").assertExists()
    }
    @Test fun phase3PrivateDecisionUiIncludesOptionalReasonAndIgnore() {
        val request = PrivateRequest("request-demo", "network-demo", "DemoNick01", 2, false)
        var action = ""; var reason = ""
        compose.setContent { MaterialTheme { PrivateRequestsScreen(UiState(privateRequests = listOf(request)), {}, { _, a, r -> action = a; reason = r }, { _, _ -> }) } }
        compose.onNodeWithText("Rechazar").performClick()
        compose.onNodeWithText("Motivo opcional").performTextInput("Motivo de prueba")
        compose.onAllNodesWithText("Rechazar").onLast().performClick()
        compose.runOnIdle { assertEquals("REJECTED", action); assertEquals("Motivo de prueba", reason) }
        compose.onNodeWithText("Ignorar").performClick()
        compose.runOnIdle { assertEquals("IGNORED", action) }
        compose.onNodeWithText("Aceptar").performClick()
        compose.runOnIdle { assertEquals("ACCEPTED", action) }
    }
}
