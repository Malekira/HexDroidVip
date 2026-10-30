/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import java.io.*
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Settings remain JSON; the optional ZIP carries the existing, interoperable history ZIP. */
internal object ConversationBackup {
    private const val SETTINGS = "settings.json"
    private const val HISTORY = "history.zip"

    fun write(out: OutputStream, json: String, exportHistory: (OutputStream) -> Unit) {
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(SETTINGS))
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(HISTORY))
            // HistoryFiles.export owns its stream, but must not close the outer ZIP entry.
            exportHistory(object : FilterOutputStream(zip) {
                override fun write(bytes: ByteArray, offset: Int, length: Int) = zip.write(bytes, offset, length)
                override fun close() = flush()
            })
            zip.closeEntry()
        }
    }

    class Contents(val json: String, val history: File?, private val staging: File) : Closeable {
        override fun close() { staging.deleteRecursively() }
    }

    /** Stage and validate everything before settings or existing conversations are changed. */
    fun read(input: InputStream, temporaryParent: File): Contents {
        val staging = File(temporaryParent, "conversation-backup-${UUID.randomUUID()}")
        check(staging.mkdirs()) { "Cannot create backup staging directory" }
        try {
            return input.buffered().use { stream ->
                stream.mark(4)
                val signature = List(4) { stream.read() }
                stream.reset()
                if (signature != listOf(0x50, 0x4b, 3, 4)) {
                    return@use Contents(stream.reader(Charsets.UTF_8).readText(), null, staging)
                }
                val seen = mutableSetOf<String>()
                ZipInputStream(stream).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(!entry.isDirectory && entry.name in setOf(SETTINGS, HISTORY) && seen.add(entry.name)) {
                            "Invalid or duplicate backup entry"
                        }
                        File(staging, entry.name).outputStream().use { zip.copyTo(it) }
                        zip.closeEntry()
                    }
                }
                require(SETTINGS in seen) { "Backup has no settings.json" }
                val history = File(staging, HISTORY).takeIf { it.exists() }
                val hasConversations = history?.inputStream()?.use {
                    HistoryFiles.stage(File(staging, "validate"), it, File(staging, "validate"), allowEmpty = true).isNotEmpty()
                } ?: false
                // Validation copies are temporary; the original private history is never touched.
                File(staging, "validate").deleteRecursively()
                Contents(File(staging, SETTINGS).readText(Charsets.UTF_8), history?.takeIf { hasConversations }, staging)
            }
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }
}
