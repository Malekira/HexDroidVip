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
            val unicodeLog = writer.logFileInternal("DemoNet", "#" + "🙂".repeat(120))
            assertTrue(unicodeLog.name.toByteArray(Charsets.UTF_8).size < 255)
            unicodeLog.parentFile!!.mkdirs()
            unicodeLog.writeText(a, Charsets.UTF_8)
            assertEquals(a, unicodeLog.readText(Charsets.UTF_8))
            assertTrue(unicodeLog.delete())
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
                val multiPath = "network-demo/#multiline-demo.txt"
                val multi = "2026-10-27 20:17:00\t" + HISTORY_ESCAPED + "<DemoNick01> " + encodeHistoryBody("token=[REDACTADO]\nSegunda línea 🙂")
                reopened.append(multiPath, multi)
                val multiBackup = ByteArrayOutputStream(); reopened.export(setOf(multiPath), multiBackup)
                reopened.delete(setOf(multiPath))
                assertEquals(1, reopened.restore(ByteArrayInputStream(multiBackup.toByteArray())))
                assertEquals(listOf(multi), reopened.page(multiPath, "", 0).lines)
                assertEquals(0, reopened.restore(ByteArrayInputStream(multiBackup.toByteArray())))
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

    @Test fun dreamsPermanentArchiveKeepsMoreThanFiveThousandLinesAndExportsAllOfThem() {
        val ctx = Phase3TestContext(context)
        val archive = HistoryArchive(ctx)
        val path = "network-demo/#history-demo.txt"
        try {
            repeat(5_005) { index ->
                assertTrue(archive.append(path, "2026-10-29 12:00:00\t<DemoNick01> Mensaje $index á 🙂", "history-demo-$index"))
            }
            val out = ByteArrayOutputStream()
            archive.exportText(path, out)
            val lines = out.toString("UTF-8").lineSequence().filter { it.isNotEmpty() }.toList()
            assertEquals(5_005, lines.size)
            assertTrue(lines.first().endsWith("Mensaje 0 á 🙂"))
            assertTrue(lines.last().endsWith("Mensaje 5004 á 🙂"))
        } finally { archive.close(); ctx.root.deleteRecursively() }
    }

    @Test fun dreamsRetiresIncludedPrivateAddonWithoutLosingPendingOrPersonalScripts() {
        val originalSource = InstrumentationRegistry.getInstrumentation().context.assets
            .open("retired-private-requests.hex").bufferedReader().use { it.readText() }
        for (edited in listOf(false, true)) {
            val ctx = Phase3TestContext(context)
            val prefs = ctx.getSharedPreferences("retirement-test-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE)
            val store = com.boxlabs.hexdroid.script.ScriptStore(ctx, "hex")
            val source = originalSource + if (edited) "\n; Ajuste personal de prueba\n" else ""
            val greeting = "alias buenosdias {\n echo -a SALUDO_PERSONAL_CONSERVADO\n}"
            store.write(PRIVATE_REQUEST_SCRIPT, source)
            store.write("saludo-personal.hex", greeting)
            if (edited) prefs.edit().putString("shipped_hash:$PRIVATE_REQUEST_SCRIPT", historyDigest(originalSource)).commit()
            val echoes = mutableListOf<String>()
            val host = java.lang.reflect.Proxy.newProxyInstance(com.boxlabs.hexdroid.script.ScriptHost::class.java.classLoader,
                arrayOf(com.boxlabs.hexdroid.script.ScriptHost::class.java)) { _, method, args ->
                when (method.name) {
                    "echo" -> { echoes += args[3] as String; Unit }
                    "activeNetwork" -> "network-demo"
                    "activeBuffer" -> "#demo"
                    "logDebug" -> Unit
                    else -> null
                }
            } as com.boxlabs.hexdroid.script.ScriptHost
            val engine = com.boxlabs.hexdroid.script.ScriptEngine(host, com.boxlabs.hexdroid.script.HexScriptBackend())
            val manager = com.boxlabs.hexdroid.script.ScriptManager(ctx, engine, prefs)
            val pending = PrivateRequestStore(ctx)
            val id = privateRequestId("network-demo", "demonick01")
            try {
                pending.enqueue(id, "network-demo", "DemoNick01", PendingPrivateMessage("Mensaje conservado 🙂", 1234, false, "demo-retired-1", null), false)
                manager.seedBundled(); manager.reloadAll()
                assertNull(manager.read(PRIVATE_REQUEST_SCRIPT))
                assertFalse(manager.list().any { it.name == PRIVATE_REQUEST_SCRIPT })
                assertFalse(engine.hasPrivateRequestHandler())
                assertEquals(source, File(ctx.filesDir, "retired-scripts").listFiles()!!.single().readText())
                assertEquals(greeting, manager.read("saludo-personal.hex"))
                assertTrue(engine.runCommand("buenosdias", "", "network-demo", "#demo"))
                assertEquals(listOf("SALUDO_PERSONAL_CONSERVADO"), echoes)
                assertEquals("Mensaje conservado 🙂", pending.messages(id).single().text)
                assertTrue(pending.pending().any { it.id == id })
                manager.seedBundled(); manager.restoreBundled(); manager.reloadAll()
                assertFalse(manager.list().any { it.name == PRIVATE_REQUEST_SCRIPT })
                assertFalse(engine.hasPrivateRequestHandler())
            } finally { engine.shutdown(); pending.close(); prefs.edit().clear().commit(); ctx.root.deleteRecursively() }
        }
    }
}
