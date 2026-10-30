/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class ConversationBackupAndroidTest {
    @Test fun combinedHistoryBackupKeepsDiskFilesAndMergesWithoutLosingOtherConversationsOrDuplicates() {
        val ctx = Phase3TestContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val archive = HistoryArchive(ctx)
        val restoredContext = Phase3TestContext(InstrumentationRegistry.getInstrumentation().targetContext)
        var restored = HistoryArchive(restoredContext)
        val path = "network-demo/#demo.txt"
        val privatePath = "network-demo/DemoNick02.txt"
        val line = "2026-10-30 12:00:00\t<DemoNick01> Acentos ñ 🙂"
        val other = "2026-10-30 12:01:00\t<DemoNick02> Conversación existente"
        try {
            archive.append(path, line)
            archive.append(path, line) // Two actual identical messages must both survive.
            val before = HistoryFiles.resolve(archive.root, path).readBytes()
            val out = ByteArrayOutputStream()
            ConversationBackup.write(out, "{\"app\":\"HexDroid\",\"version\":6}", archive::exportAll)
            assertArrayEquals(before, HistoryFiles.resolve(archive.root, path).readBytes())
            restored.append(path, line)
            restored.append(privatePath, other)
            ConversationBackup.read(out.toByteArray().inputStream(), restoredContext.root).use { contents ->
                assertEquals(1, contents.history!!.inputStream().use { restored.restore(it) })
                assertEquals(0, contents.history.inputStream().use { restored.restore(it) })
            }
            assertEquals(listOf(line, line), restored.page(path, "", 0).lines)
            assertEquals(listOf(other), restored.page(privatePath, "", 0).lines)
            restored.close()
            restored = HistoryArchive(restoredContext)
            assertEquals(listOf(line, line), restored.page(path, "", 0).lines)
            assertFalse(restored.append(path, line, replay = true))
            assertArrayEquals(before, HistoryFiles.resolve(archive.root, path).readBytes())
        } finally {
            archive.close(); restored.close()
            ctx.root.deleteRecursively(); restoredContext.root.deleteRecursively()
        }
    }

    @Test fun backingUpSettingsOnlyOrAnEmptyHistoryNeverTouchesExistingConversations() {
        val ctx = Phase3TestContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val archive = HistoryArchive(ctx)
        try {
            val path = "network-demo/#demo.txt"
            val line = "2026-10-30 12:00:00\t<DemoNick01> Historial conservado 🙂"
            archive.append(path, line)
            val before = HistoryFiles.resolve(archive.root, path).readBytes()
            ConversationBackup.read("{\"version\":6}".byteInputStream(), ctx.root).use { assertNull(it.history) }
            val out = ByteArrayOutputStream()
            ConversationBackup.write(out, "{\"version\":6}") { HistoryFiles.export(File(ctx.root, "empty"), emptySet(), it) }
            ConversationBackup.read(out.toByteArray().inputStream(), ctx.root).use { assertNull(it.history) }
            assertArrayEquals(before, HistoryFiles.resolve(archive.root, path).readBytes())
            assertEquals(listOf(line), archive.page(path, "", 0).lines)
        } finally { archive.close(); ctx.root.deleteRecursively() }
    }
}
