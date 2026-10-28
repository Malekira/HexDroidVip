/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.boxlabs.hexdroid.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.*

internal class Phase3TestContext(base: Context) : ContextWrapper(base) {
    val root = File(base.cacheDir, "phase3-${java.util.UUID.randomUUID()}").apply { mkdirs() }
    override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
    override fun getDatabasePath(name: String): File = File(root, name)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
}

class PersonalPhase3StorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun phase3PreferencesSurviveRepositoryReconstruction() = runBlocking {
        val repo = SettingsRepository(context)
        val old = repo.settingsFlow.first()
        val theme = PersonalTheme(name = "Tema demo", darkColors = mapOf("CHAT" to 0xff102030.toInt()), lightColors = mapOf("CHAT" to 0xfffefefe.toInt()))
        val style = WritingStyle(4, 8, true, true, true, true)
        try {
            repo.updateSettings { it.withPersonal { copy(personalThemes = listOf(theme), selectedThemeId = theme.id, writingStyle = style,
                privateWaitText = "Esperá un momento", privateAcceptText = "Bienvenido al privado", privateRejectText = "Su privado fue rechazado", privateAutoMessages = false, privateRememberAccepted = true) } }
            val restored = SettingsRepository(context).settingsFlow.first()
            assertEquals(listOf(theme), restored.personalThemes); assertEquals(theme.id, restored.selectedThemeId)
            assertEquals(style, restored.writingStyle); assertFalse(restored.privateAutoMessages)
            assertEquals("Esperá un momento", restored.privateWaitText); assertTrue(restored.privateRememberAccepted)
            assertTrue(File(context.filesDir, "datastore/hexdroid_prefs.preferences_pb").isFile)
        } finally { repo.updateSettings { old } }
    }
    @Test fun phase3HistoryPersistsDeduplicatesRestoresAndDeletes() {
        val ctx = Phase3TestContext(context)
        val path = "network-demo/#sala-demo.txt"
        val a = "2026-10-27 20:15:32\t<DemoNick01> Hola á 🙂"
        val b = "2026-10-27 20:16:04\t<DemoNick02> Respuesta completa"
        val archive = HistoryArchive(ctx)
        try {
            val writer = LogWriter(ctx)
            assertNotEquals(writer.logFileInternal("DemoNet", "#demo/a"), writer.logFileInternal("DemoNet", "#demo_a"))
            assertTrue(writer.logFileInternal("DemoNet", "#" + "🙂".repeat(120)).name.toByteArray(Charsets.UTF_8).size < 255)
            assertTrue(archive.append(path, a, "msg-demo-1"))
            assertFalse(archive.append(path, a, "msg-demo-1", true))
            archive.close()
            val reopened = HistoryArchive(ctx)
            try {
                assertFalse(reopened.append(path, a, "msg-demo-1", true))
                assertTrue(reopened.append(path, b, "msg-demo-2"))
                val zip = ByteArrayOutputStream(); reopened.export(setOf(path), zip)
                assertEquals(0, reopened.restore(ByteArrayInputStream(zip.toByteArray())))
                reopened.delete(setOf(path)); assertTrue(reopened.list().isEmpty())
                assertEquals(2, reopened.restore(ByteArrayInputStream(zip.toByteArray())))
                assertEquals(listOf(a, b), reopened.page(path, "", 0).lines)
                assertEquals(listOf(a), reopened.page(path, "DemoNick01 🙂", 0).lines)
                val legacy = File(ctx.filesDir, "logs/LegacyDemo/#old-demo.txt").apply { parentFile!!.mkdirs(); writeText(a + "\n", Charsets.UTF_8) }
                reopened.migrateLegacy(legacy, "network-demo/#old-demo.txt")
                assertFalse(legacy.exists())
                assertEquals(listOf(a), reopened.page("network-demo/#old-demo.txt", "", 0).lines)
                assertFalse(reopened.append("network-demo/#old-demo.txt", a, replay = true))
            } finally { reopened.close() }
        } finally { archive.close(); ctx.root.deleteRecursively() }
    }
    @Test fun phase3IdenticalLiveLinesArePreservedWhileReplayIsDeduplicated() {
        val ctx = Phase3TestContext(context); val archive = HistoryArchive(ctx)
        try {
            val line = "2026-10-27 20:15:32\t<DemoNick01> repetido"
            assertTrue(archive.append("network-demo/#demo.txt", line))
            assertTrue(archive.append("network-demo/#demo.txt", line))
            assertFalse(archive.append("network-demo/#demo.txt", line, replay = true))
            assertEquals(2, archive.page("network-demo/#demo.txt", "", 0).lines.size)
            val backup = ByteArrayOutputStream(); archive.export(setOf("network-demo/#demo.txt"), backup)
            archive.delete(setOf("network-demo/#demo.txt"))
            assertEquals(2, archive.restore(ByteArrayInputStream(backup.toByteArray())))
            assertEquals(0, archive.restore(ByteArrayInputStream(backup.toByteArray())))
            assertEquals(listOf(line, line), archive.page("network-demo/#demo.txt", "", 0).lines)
        } finally { archive.close(); ctx.root.deleteRecursively() }
    }
    @Test fun phase3PendingPrivateMessagesSurviveAndDecisionsNeverTouchIgnores() {
        val ctx = Phase3TestContext(context); val store = PrivateRequestStore(ctx)
        val before = SettingsRepository(context)
        val original = runBlocking { before.settingsFlow.first() }
        val id = privateRequestId("network-demo", "demonick01")
        val message = PendingPrivateMessage("Contenido privado á 🙂", 1793132132000L, false, "pending-demo-1", null)
        try {
            assertTrue(store.enqueue(id, "network-demo", "DemoNick01", message, false))
            assertFalse(store.enqueue(id, "network-demo", "DemoNick01", message, false))
            assertTrue(store.claimWait(id)); assertFalse(store.claimWait(id))
            store.close()
            val reopened = PrivateRequestStore(ctx)
            try {
                assertEquals(1, reopened.pending().single().count)
                assertEquals(message, reopened.messages(id).single())
                assertFalse(File(ctx.root, "personal-private-requests.db").readBytes().toString(Charsets.UTF_8).contains(message.text))
                reopened.finish(id, "ACCEPTED", true); assertTrue(reopened.authorized(id)); assertTrue(reopened.pending().isEmpty())
                reopened.revokeAll(); assertFalse(reopened.authorized(id))
                listOf("REJECTED", "IGNORED").forEach { state ->
                    reopened.enqueue(id, "network-demo", "DemoNick01", message, false)
                    reopened.finish(id, state, false)
                    assertFalse(reopened.authorized(id)); assertTrue(reopened.pending().isEmpty())
                    assertTrue(reopened.messages(id).isEmpty())
                }
                assertEquals(original, runBlocking { before.settingsFlow.first() })
            } finally { reopened.close() }
        } finally { store.close(); ctx.root.deleteRecursively() }
    }
}
