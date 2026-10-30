/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.boxlabs.hexdroid.ui.ExportBackupDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConversationBackupUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun exportAsksAboutHistoryEveryTimeAndPassesTheSelectedOption() {
        var result: Pair<String?, Boolean>? = null
        compose.setContent { MaterialTheme {
            ExportBackupDialog(onExport = { pass, history -> result = pass to history }, onDismiss = {})
        } }
        compose.onNodeWithText(context.getString(R.string.backup_include_history)).assertIsDisplayed()
        compose.onNodeWithTag("backup-include-history").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText(context.getString(R.string.backup_history_privacy)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.backup_export_action)).performClick()
        compose.runOnIdle { assertEquals(null to true, result) }
    }

    @Test fun settingsOnlyAndCancelDoNotImplicitlyExportHistory() {
        var result: Pair<String?, Boolean>? = null
        var dismissed = false
        compose.setContent { MaterialTheme {
            ExportBackupDialog(onExport = { pass, history -> result = pass to history }, onDismiss = { dismissed = true })
        } }
        compose.onNodeWithText(context.getString(R.string.backup_export_action)).performClick()
        compose.runOnIdle { assertEquals(null to false, result); result = null }
        compose.onNodeWithTag("backup-include-history").performClick()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertTrue(dismissed); assertNull(result) }
    }
}
