/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.util.zip.*

class ConversationBackupTest {
    private val json = "{\"app\":\"HexDroid\",\"version\":6,\"networks\":[],\"settings\":{}}"
    private fun directory() = Files.createTempDirectory("conversation-backup-test").toFile()
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (path, bytes) ->
            z.putNextEntry(ZipEntry(path)); z.write(bytes); z.closeEntry()
        } }
    }.toByteArray()

    @Test fun combinedBackupStreamsEveryLineWithoutMovingOrTruncatingOriginals() {
        val root = directory()
        try {
            val source = File(root, "logs")
            val path = "network-demo/#demo.txt"
            val file = HistoryFiles.resolve(source, path).apply { parentFile!!.mkdirs() }
            val text = (1..6_123).joinToString("\n", postfix = "\n") { "2026-10-30 12:00:00\t<DemoNick01> línea $it ñ 🙂" }
            file.writeText(text, Charsets.UTF_8)
            val before = file.readBytes()
            val out = ByteArrayOutputStream()
            ConversationBackup.write(out, json) { HistoryFiles.export(source, setOf(path), it) }
            assertArrayEquals(before, file.readBytes())
            ConversationBackup.read(out.toByteArray().inputStream(), root).use { backup ->
                assertEquals(json, backup.json)
                val target = File(root, "restored")
                assertEquals(listOf(path), backup.history!!.inputStream().use { HistoryFiles.stage(target, it, target) })
                assertArrayEquals(before, HistoryFiles.resolve(target, path).readBytes())
            }
            assertArrayEquals(before, file.readBytes())
            assertFalse(root.listFiles()!!.any { it.name.startsWith("conversation-backup-") })
        } finally { root.deleteRecursively() }
    }

    @Test fun legacySettingsOnlyJsonDoesNotAddHistoryAndCleansUpStaging() {
        val root = directory()
        try {
            ConversationBackup.read(json.byteInputStream(), root).use {
                assertEquals(json, it.json); assertNull(it.history)
            }
            assertEquals(0, root.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }

    @Test fun includingAnEmptyHistoryProducesARestorableSettingsBackup() {
        val root = directory()
        try {
            val out = ByteArrayOutputStream()
            ConversationBackup.write(out, json) { HistoryFiles.export(File(root, "empty"), emptySet(), it) }
            ConversationBackup.read(out.toByteArray().inputStream(), root).use {
                assertEquals(json, it.json); assertNull(it.history)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidHistoriesAndForeignEntriesFailBeforeAnyRestoreAndLeaveOriginalsIntact() {
        val root = directory()
        val original = File(root, "original.txt").apply { writeText("Conversación intacta 🙂") }
        val malformed = zip("network-demo/#demo.txt" to byteArrayOf(0xc3.toByte(), 0x28))
        val traversal = zip("../outside.txt" to "bad".toByteArray())
        try {
            listOf(
                zip("history.zip" to zip()), // missing settings
                zip("settings.json" to json.toByteArray(), "unexpected.txt" to byteArrayOf(1)),
                zip("settings.json" to json.toByteArray(), "history.zip" to "not a ZIP".toByteArray()),
                zip("settings.json" to json.toByteArray(), "history.zip" to malformed),
                zip("settings.json" to json.toByteArray(), "history.zip" to traversal),
            ).forEach { bytes ->
                try {
                    ConversationBackup.read(bytes.inputStream(), root).use { fail("Invalid backup accepted") }
                } catch (_: IllegalArgumentException) { } catch (_: IOException) { }
                assertEquals("Conversación intacta 🙂", original.readText())
                assertFalse(root.listFiles()!!.any { it.name.startsWith("conversation-backup-") })
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun failedExportNeverRemovesOrChangesSourceHistory() {
        val root = directory()
        try {
            val path = "network-demo/#demo.txt"
            val file = HistoryFiles.resolve(root, path).apply { parentFile!!.mkdirs(); writeText("Contenido completo 🙂\n") }
            val before = file.readBytes()
            val failing = object : OutputStream() {
                override fun write(value: Int) { throw IOException("Simulated destination failure") }
            }
            try {
                ConversationBackup.write(failing, json) { HistoryFiles.export(root, setOf(path), it) }
                fail("Export should fail")
            } catch (_: IOException) { }
            assertArrayEquals(before, file.readBytes())
        } finally { root.deleteRecursively() }
    }
}
