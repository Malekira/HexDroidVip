/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.*
import java.security.MessageDigest
import java.util.zip.*

internal fun historyDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Sanitisation/truncation must never merge different valid IRC targets into one file. */
internal fun uniqueHistoryFileBase(original: String, sanitized: String): String {
    if (original == sanitized && sanitized.toByteArray(Charsets.UTF_8).size <= 200) return sanitized
    val prefix = StringBuilder()
    var bytes = 0
    val points = sanitized.codePoints().iterator()
    while (points.hasNext()) {
        val chars = String(Character.toChars(points.nextInt()))
        val size = chars.toByteArray(Charsets.UTF_8).size
        if (bytes + size > 160) break
        prefix.append(chars); bytes += size
    }
    return "$prefix--${historyDigest(original).take(16)}"
}

/** Excludes authentication exchanges; redacts accidental credential assignments in conversation text. */
internal fun safeHistoryText(buffer: String, text: String): String? {
    if (bareMember(buffer).lowercase() in setOf("nickserv", "authserv", "chanserv", "operserv", "q", "x")) return null
    val clean = stripIrcFormatting(text)
    if (Regex("(?i)^\\s*(?:/|(?:PRIVMSG\\s+\\S+\\s+:))?(?:identify|authenticate|pass|oper|sasl|login|auth)\\b").containsMatchIn(clean)) return null
    return clean.replace(Regex("(?i)\\b(password|passwd|contraseña|token|secret|api[_-]?key|authorization)\\s*[:=]\\s*[^\\s]+"), "$1=[REDACTADO]")
        .replace(Regex("(?i)\\bBearer\\s+[^\\s]+"), "Bearer [REDACTADO]")
        .replace(Regex("(?i)([a-z][a-z0-9+.-]*://)[^\\s/@]+:[^\\s/@]+@"), "$1[REDACTADO]@")
}

data class HistoryConversation(val path: String, val bytes: Long)
data class HistoryPage(val lines: List<String>, val next: Long?)

/** Streaming utilities: history size never sets a scrollback or retention limit. */
internal object HistoryFiles {
    fun resolve(root: File, path: String): File {
        require(path.split('/').size == 2 && path.endsWith(".txt") && '\\' !in path && '\u0000' !in path) { "Ruta de historial no válida" }
        val f = File(root, path).canonicalFile
        require(f.path.startsWith(root.canonicalPath + File.separator) && f.parentFile != root.canonicalFile) { "Ruta fuera del historial" }
        return f
    }
    fun list(root: File): List<HistoryConversation> = root.walkTopDown().filter { it.isFile && it.extension == "txt" && it.relativeTo(root).invariantSeparatorsPath.split('/').size == 2 }
        .map { HistoryConversation(it.relativeTo(root).invariantSeparatorsPath, it.length()) }.sortedBy { it.path }.toList()
    fun page(root: File, path: String, query: String, offset: Long, size: Int = 100): HistoryPage {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val result = mutableListOf<String>()
        var count = 0L
        var next: Long? = null
        resolve(root, path).bufferedReader(Charsets.UTF_8).useLines { lines ->
            val it = lines.iterator()
            while (it.hasNext()) {
                val line = it.next(); val pos = count++
                if (pos < offset || !terms.all { line.contains(it, true) }) continue
                if (result.size == size) { next = pos; break }
                result += line
            }
        }
        return HistoryPage(result, next)
    }
    fun export(root: File, paths: Set<String>, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { z ->
            paths.sorted().forEach { path ->
                val f = resolve(root, path)
                z.putNextEntry(ZipEntry(path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
    }
    /** Validate the entire archive before touching stored conversations. Disk space is the real limit. */
    fun stage(root: File, input: InputStream, staging: File): List<String> {
        val paths = mutableListOf<String>()
        ZipInputStream(input.buffered()).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                if (entry.isDirectory) { z.closeEntry(); continue }
                resolve(root, entry.name) // rejects traversal and foreign backup types
                require(entry.name !in paths) { "Archivo duplicado en la copia" }
                val out = resolve(staging, entry.name); out.parentFile!!.mkdirs()
                out.outputStream().use { z.copyTo(it) }; z.closeEntry()
                // Reject binary/corrupt content, including malformed UTF-8, before merging anything.
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                InputStreamReader(out.inputStream(), decoder).buffered().useLines { lines -> lines.forEach { require('\u0000' !in it) { "El historial no es texto" } } }
                paths += entry.name
            }
        }
        require(paths.isNotEmpty()) { "La copia no contiene conversaciones compatibles" }
        return paths
    }
}

/** Persistent dedup index is on disk, independently of finite UI scrollback. No message content in DB. */
internal class HistoryArchive(ctx: Context) {
    val root = File(ctx.filesDir, "logs").apply { mkdirs() }
    private val db = object : SQLiteOpenHelper(ctx, "personal-history-index.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE seen (path TEXT NOT NULL, identity TEXT NOT NULL, PRIMARY KEY(path,identity))")
            db.execSQL("CREATE TABLE seeded (path TEXT PRIMARY KEY, bytes INTEGER NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    private fun exists(path: String, identity: String): Boolean = db.readableDatabase.rawQuery("SELECT 1 FROM seen WHERE path=? AND identity=?", arrayOf(path, identity)).use { it.moveToFirst() }
    private fun mark(path: String, identity: String) { db.writableDatabase.execSQL("INSERT OR IGNORE INTO seen VALUES (?,?)", arrayOf(path, identity)) }
    private fun clear(path: String) {
        db.writableDatabase.execSQL("DELETE FROM seen WHERE path=?", arrayOf(path))
        db.writableDatabase.execSQL("DELETE FROM seeded WHERE path=?", arrayOf(path))
    }
    private fun seed(path: String, file: File) {
        if (!file.exists()) clear(path)
        val seeded = db.readableDatabase.rawQuery("SELECT bytes FROM seeded WHERE path=?", arrayOf(path)).use { it.moveToFirst() && it.getLong(0) == file.length() }
        if (seeded) return
        db.writableDatabase.beginTransaction()
        try {
            if (file.exists()) file.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { mark(path, "line:" + historyDigest(it)) } }
            db.writableDatabase.execSQL("INSERT OR REPLACE INTO seeded VALUES (?,?)", arrayOf<Any>(path, file.length()))
            db.writableDatabase.setTransactionSuccessful()
        } finally { db.writableDatabase.endTransaction() }
    }
    @Synchronized fun append(path: String, line: String, msgId: String? = null, replay: Boolean = false): Boolean {
        val file = HistoryFiles.resolve(root, path)
        seed(path, file)
        val lineId = "line:" + historyDigest(line)
        val id = msgId?.takeIf { it.isNotBlank() }?.let { "msg:" + historyDigest(it) } ?: lineId
        if ((msgId != null && exists(path, id)) || (replay && exists(path, lineId))) return false
        file.parentFile!!.mkdirs()
        file.appendText(line + "\n", Charsets.UTF_8)
        mark(path, id); mark(path, lineId)
        db.writableDatabase.execSQL("UPDATE seeded SET bytes=? WHERE path=?", arrayOf<Any>(file.length(), path))
        return true
    }
    fun close() { db.close() }
    @Synchronized fun migrateLegacy(source: File, path: String) {
        val destination = HistoryFiles.resolve(root, path)
        if (source.exists() && !destination.exists() && source.canonicalFile != destination.canonicalFile) {
            destination.parentFile!!.mkdirs()
            java.nio.file.Files.move(source.toPath(), destination.toPath())
        }
    }
    @Synchronized fun list(): List<HistoryConversation> = HistoryFiles.list(root)
    @Synchronized fun page(path: String, query: String, offset: Long): HistoryPage = HistoryFiles.page(root, path, query, offset)
    @Synchronized fun export(paths: Set<String>, out: OutputStream) = HistoryFiles.export(root, paths, out)
    @Synchronized fun exportText(path: String, out: OutputStream) { out.use { target -> HistoryFiles.resolve(root, path).inputStream().use { it.copyTo(target) } } }
    @Synchronized fun delete(paths: Set<String>) { paths.forEach { p -> require(HistoryFiles.resolve(root, p).delete() || !HistoryFiles.resolve(root, p).exists()); clear(p) } }
    @Synchronized fun restore(input: InputStream): Int {
        val staging = File(root.parentFile, "history-import-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val paths = HistoryFiles.stage(root, input, staging)
            var added = 0
            val index = db.writableDatabase
            // Count occurrences on disk rather than collapsing a conversation into a set:
            // two legitimate identical messages must survive a backup/restore.
            index.execSQL("CREATE TEMP TABLE IF NOT EXISTS restore_counts (identity TEXT PRIMARY KEY, existing INTEGER NOT NULL DEFAULT 0, incoming INTEGER NOT NULL DEFAULT 0)")
            paths.forEach { path ->
                val target = HistoryFiles.resolve(root, path)
                index.beginTransaction()
                try {
                    index.execSQL("DELETE FROM restore_counts")
                    if (target.exists()) target.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { line ->
                        val key = historyDigest(line)
                        index.execSQL("INSERT OR IGNORE INTO restore_counts(identity) VALUES (?)", arrayOf(key))
                        index.execSQL("UPDATE restore_counts SET existing=existing+1 WHERE identity=?", arrayOf(key))
                    } }
                    HistoryFiles.resolve(staging, path).bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { line ->
                        val tab = line.indexOf('\t')
                        val rawBody = if (tab >= 0) line.substring(tab + 1) else line
                        val body = rawBody.removePrefix(HISTORY_ESCAPED)
                        val text = LogSearch.textOf(body)
                        val safe = safeHistoryText(path.substringAfter('/').removeSuffix(".txt"), text)
                        if (safe != null) {
                            val sanitized = if (safe == text) line else (if (tab >= 0) line.substring(0, tab + 1) else "") + body.removeSuffix(text) + safe
                            val key = historyDigest(sanitized)
                            index.execSQL("INSERT OR IGNORE INTO restore_counts(identity) VALUES (?)", arrayOf(key))
                            index.execSQL("UPDATE restore_counts SET incoming=incoming+1 WHERE identity=?", arrayOf(key))
                            val missing = index.rawQuery("SELECT incoming>existing FROM restore_counts WHERE identity=?", arrayOf(key)).use { it.moveToFirst(); it.getInt(0) != 0 }
                            if (missing && append(path, sanitized)) added++
                        }
                    } }
                    index.setTransactionSuccessful()
                } finally { index.endTransaction() }
            }
            index.execSQL("DROP TABLE restore_counts")
            return added
        } finally { staging.deleteRecursively() }
    }
}
