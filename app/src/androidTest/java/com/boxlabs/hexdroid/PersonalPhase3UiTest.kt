/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentListOf
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

    @Composable private fun RealChat(state: UiState) {
        ChatScreen(state, onSelectBuffer = {}, onSend = {}, onDisconnect = {}, onReconnect = {}, onExit = {},
            onToggleBufferList = {}, onToggleNickList = {}, onToggleChannelsOnly = {}, onWhois = {},
            onIgnoreNick = { _, _ -> }, onUnignoreNick = { _, _ -> }, onIgnoreNotifications = { _, _ -> },
            onUnignoreNotifications = { _, _ -> }, onRefreshNicklist = {}, onRefreshChannelModes = {},
            onOpenList = {}, onOpenSettings = {}, onOpenNetworks = {}, onOpenTransfers = {}, onSysInfo = {},
            onAbout = {}, onUpdateSettings = {})
    }

    @Test fun phase3RealComposerNeverCompletesASingleNickWithoutAnExplicitChoice() {
        val key = "network-demo::#demo"
        val state = UiState(selectedBuffer = key, buffers = mapOf(key to UiBuffer("#demo")),
            nicklists = mapOf(key to listOf("@DemoNick01")), showBufferList = false, showNickList = false)
        compose.setContent { HexDroidIRCTheme { RealChat(state) } }
        val input = compose.onNodeWithTag("chat-input")
        input.performTextInput("dem")
        compose.onNodeWithTag("nick-choices").assertExists()
        // Let the real former 300 ms coroutine deadline elapse with the UI thread running.
        android.os.SystemClock.sleep(650)
        input.assertTextEquals("dem")
        input.performTextInput("ora")
        input.assertTextEquals("demora")
        input.performTextReplacement("dem")
        compose.onNodeWithText("DemoNick01", useUnmergedTree = true).performClick()
        input.assertTextEquals("DemoNick01: ")
        input.performTextReplacement("Hola dem después")
        input.performSemanticsAction(SemanticsActions.SetSelection) { it(8, 8, false) }
        compose.onNodeWithTag("nick-tab-button").performClick()
        input.assertTextEquals("Hola DemoNick01 después")
    }

    @Test fun phase3VisualColorPickerUpdatesHexPreviewAndBothSavedPalettes() {
        var settings by mutableStateOf(UiSettings())
        compose.setContent { MaterialTheme { PersonalThemeSettings(settings) { update -> settings = settings.update() } } }
        compose.onNodeWithText("Crear tema").performClick()
        compose.onNodeWithText("Nombre").performTextReplacement("Tema de prueba visual")
        compose.onNodeWithText("#RRGGBB").performTextReplacement("FF0000")
        val wheel = compose.onNodeWithTag("theme-color-wheel")
        wheel.performScrollTo().performTouchInput {
            click(Offset(width * .375f, height * .71650635f))
        }
        compose.onNodeWithTag("theme-brightness").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(.5f) }
        val chosen = parseThemeHex(compose.onNodeWithText("#RRGGBB").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)!!
        assertTrue(((chosen ushr 8) and 255) in 126..130)
        assertTrue(((chosen ushr 16) and 255) in 61..67)
        assertTrue((chosen and 255) in 61..67)
        wheel.assertWidthIsEqualTo(160.dp)
        val preview = compose.onNodeWithTag("theme-preview", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(preview.positionInRoot.y < wheel.fetchSemanticsNode().positionInRoot.y)
        compose.onNodeWithText("Claro").performScrollTo().performClick()
        compose.onNodeWithText("#RRGGBB").performTextReplacement("123456")
        compose.onNodeWithText("Guardar y aplicar").performScrollTo().performClick()
        compose.runOnIdle {
            val theme = settings.personalThemes.single()
            assertEquals(chosen, theme.color(ThemeRole.CHAT, true))
            assertEquals(0xff123456.toInt(), theme.color(ThemeRole.CHAT, false))
        }
        compose.onNodeWithText("Crear tema").performClick()
        compose.onNodeWithText("#RRGGBB").performTextReplacement("ABCDEF")
        compose.onNodeWithText("Cancelar").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, settings.personalThemes.size) }
    }

    @Test fun phase3CallerIdNoticeIsVisiblyHighlightedInTheRealChat() {
        val key = "network-demo::#demo"
        val message = UiMessage(1, System.currentTimeMillis(), null, "* <DemoNick01> Solicitud de PV bloqueada por +g", isHighlightedNotice = true)
        val state = UiState(selectedBuffer = key, buffers = mapOf(key to UiBuffer("#demo", log = BufferLog(messages = persistentListOf(message)))),
            showBufferList = false, showNickList = false)
        var background = 0
        compose.setContent { HexDroidIRCTheme { background = MaterialTheme.colorScheme.secondaryContainer.toArgb(); RealChat(state) } }
        val notice = compose.onNodeWithTag("highlighted-private-notice").assertIsDisplayed()
        val pixels = notice.captureToImage().toPixelMap()
        assertEquals(background, pixels[1, 1].toArgb())
        compose.onNodeWithText(message.text, substring = true).assertIsDisplayed()
    }
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

    @Test fun dreamsMembershipArrowsAndGreyBodyRenderInTheRealChat() {
        val key = "network-demo::#demo"
        val messages = MembershipEvent.entries.mapIndexed { i, event ->
            UiMessage(i + 1L, System.currentTimeMillis(), null,
                membershipEventLine(event, "DemoNick01", "demo@192.0.2.1", "#demo"))
        }
        val state = UiState(selectedBuffer = key, buffers = mapOf(key to UiBuffer("#demo", log = BufferLog(messages = persistentListOf(*messages.toTypedArray())))),
            showBufferList = false, showNickList = false)
        compose.setContent { HexDroidIRCTheme { RealChat(state) } }
        for (event in MembershipEvent.entries) {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText("${event.arrow} ${event.name} DemoNick01 (demo@192.0.2.1) #demo", substring = true, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val styled = layouts.single().layoutInput.text
            assertTrue(styled.spanStyles.any { it.item.color == mircColor(event.color) })
            assertTrue(styled.spanStyles.any { it.item.color == mircColor(14) })
        }
    }

    @Test fun dreamsMentionColorWheelAndHexStayInSyncWithoutNamedExamples() {
        var settings by mutableStateOf(UiSettings())
        compose.setContent { MaterialTheme { androidx.compose.foundation.layout.Column {
            MentionColorSettings(settings) { update -> settings = settings.update() }
        } } }
        compose.onNodeWithTag("mention-color-hex").performTextReplacement("#FF0000")
        compose.onNodeWithTag("theme-color-wheel").performTouchInput {
            click(Offset(width * .375f, height * .71650635f))
        }
        compose.onNodeWithTag("theme-brightness").performSemanticsAction(SemanticsActions.SetProgress) { it(.5f) }
        compose.runOnIdle { assertTrue(((settings.mentionHighlightColor ushr 8) and 255) in 126..130) }
        val text = compose.onNodeWithTag("mention-color-hex").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals(settings.mentionHighlightColor, parseThemeHex(text))
        compose.onNodeWithTag("mention-color-preview").assertExists()
        compose.onNodeWithTag("mention-color-hex").performTextReplacement("#123456")
        compose.runOnIdle { assertEquals(0xff123456.toInt(), settings.mentionHighlightColor) }
    }

    @Test fun dreamsTabIsLeftOfComposerAndRoomToolsAreBeforeTheGeneralMenu() {
        val key = "network-demo::#demo"
        val state = UiState(selectedBuffer = key, buffers = mapOf(key to UiBuffer("#demo")),
            myNick = "OwnDemoNick", nicklists = mapOf(key to listOf("@OwnDemoNick", "+DemoNick01")),
            showBufferList = false, showNickList = false)
        compose.setContent { HexDroidIRCTheme { RealChat(state) } }
        val tab = compose.onNodeWithTag("nick-tab-button").fetchSemanticsNode()
        val input = compose.onNodeWithTag("chat-input").fetchSemanticsNode()
        assertTrue(tab.positionInRoot.x < input.positionInRoot.x)
        val tools = compose.onNodeWithTag("channel-tools-button").fetchSemanticsNode()
        val menu = compose.onNodeWithTag("general-menu-button").fetchSemanticsNode()
        assertTrue(tools.positionInRoot.x < menu.positionInRoot.x)
        assertTrue(kotlin.math.abs(tools.positionInRoot.y - menu.positionInRoot.y) < 30f)
        compose.onNodeWithTag("chat-input").performTextInput("Hola dem después")
        compose.onNodeWithTag("chat-input").performSemanticsAction(SemanticsActions.SetSelection) { it(8, 8, false) }
        compose.onNodeWithTag("nick-tab-button").performClick()
        compose.onNodeWithTag("chat-input").assertTextEquals("Hola DemoNick01 después")
        compose.onNodeWithTag("channel-tools-button").performClick()
        val title = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(com.boxlabs.hexdroid.R.string.chat_channel_tools)
        compose.onNodeWithText(title).assertExists()
    }
}
