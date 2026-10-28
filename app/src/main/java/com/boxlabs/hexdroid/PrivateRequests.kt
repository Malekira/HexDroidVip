/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stored pending bodies are encrypted at rest, even when permanent plaintext logging is disabled. */
internal class PendingCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "hexdroid_personal_pending_v1"
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun encrypt(text: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    fun decrypt(text: String): String {
        val b = Base64.decode(text, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
        return c.doFinal(b.copyOfRange(12, b.size)).toString(Charsets.UTF_8)
    }
}

data class PrivateRequest(val id: String, val network: String, val nick: String, val count: Int, val serverBlocked: Boolean)
internal data class PendingPrivateMessage(val text: String, val timeMs: Long, val action: Boolean, val msgId: String?, val encryption: com.boxlabs.hexdroid.crypto.E2eScheme?)
internal fun privateRequestId(network: String, foldedNick: String) = historyDigest(network + "\u0000" + foldedNick)
internal fun rejectionText(configured: String, reason: String): String {
    val text = configured.trim()
    val required = if (text.contains("rechaz", true)) text else "Su privado fue rechazado. $text"
    return required + reason.trim().takeIf { it.isNotEmpty() }?.let { " Motivo: $it" }.orEmpty()
}
internal fun safeAutomaticText(text: String): String = stripIrcFormatting(text).replace(Regex("[\\r\\n\\u0000]"), " ").trim()

internal class PrivateRequestStore(ctx: Context) {
    private val cipher = PendingCipher()
    private val helper = object : SQLiteOpenHelper(ctx, "personal-private-requests.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE requests (id TEXT PRIMARY KEY, network TEXT NOT NULL, nick TEXT NOT NULL, state TEXT NOT NULL, blocked INTEGER NOT NULL DEFAULT 0, waiting INTEGER NOT NULL DEFAULT 0, authorized INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, request TEXT NOT NULL, identity TEXT NOT NULL, body TEXT NOT NULL, time INTEGER NOT NULL, action INTEGER NOT NULL, msgid TEXT, encryption TEXT, UNIQUE(request,identity))")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    fun close() { helper.close() }
    @Synchronized fun authorized(id: String): Boolean = helper.readableDatabase.rawQuery("SELECT 1 FROM requests WHERE id=? AND authorized=1", arrayOf(id)).use { it.moveToFirst() }
    @Synchronized fun enqueue(id: String, network: String, nick: String, message: PendingPrivateMessage?, blocked: Boolean): Boolean {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO requests(id,network,nick,state) VALUES (?,?,?,'PENDING')", arrayOf<Any?>(id, network, nick))
            db.execSQL("UPDATE requests SET state='PENDING',nick=?,blocked=? WHERE id=?", arrayOf<Any?>(nick, if (blocked) 1 else 0, id))
            var added = message == null
            if (message != null) {
                val identity = historyDigest(message.msgId ?: "${message.timeMs}\u0000${message.action}\u0000${message.text}")
                val before = db.rawQuery("SELECT 1 FROM messages WHERE request=? AND identity=?", arrayOf(id, identity)).use { it.moveToFirst() }
                if (!before) {
                    db.execSQL("INSERT INTO messages(request,identity,body,time,action,msgid,encryption) VALUES (?,?,?,?,?,?,?)", arrayOf<Any?>(id, identity, cipher.encrypt(message.text), message.timeMs, if (message.action) 1 else 0, message.msgId, message.encryption?.name))
                    added = true
                }
            }
            db.setTransactionSuccessful()
            return added
        } finally { db.endTransaction() }
    }
    @Synchronized fun claimWait(id: String): Boolean {
        val db = helper.writableDatabase
        db.execSQL("UPDATE requests SET waiting=1 WHERE id=? AND waiting=0", arrayOf(id))
        return db.rawQuery("SELECT changes()", null).use { it.moveToFirst(); it.getInt(0) == 1 }
    }
    @Synchronized fun pending(): List<PrivateRequest> = helper.readableDatabase.rawQuery("SELECT r.id,r.network,r.nick,count(m.id),r.blocked FROM requests r LEFT JOIN messages m ON m.request=r.id WHERE r.state='PENDING' GROUP BY r.id ORDER BY min(m.time),r.id", null).use { c ->
        buildList { while (c.moveToNext()) add(PrivateRequest(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4) != 0)) }
    }
    @Synchronized fun messages(id: String): List<PendingPrivateMessage> = helper.readableDatabase.rawQuery("SELECT body,time,action,msgid,encryption FROM messages WHERE request=? ORDER BY time,id", arrayOf(id)).use { c ->
        buildList { while (c.moveToNext()) add(PendingPrivateMessage(cipher.decrypt(c.getString(0)), c.getLong(1), c.getInt(2) != 0, c.getString(3), c.getString(4)?.let { com.boxlabs.hexdroid.crypto.E2eScheme.valueOf(it) })) }
    }
    @Synchronized fun finish(id: String, state: String, remember: Boolean) {
        require(state in setOf("ACCEPTED", "REJECTED", "IGNORED"))
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE requests SET state=?,authorized=? WHERE id=?", arrayOf<Any?>(state, if (remember) 1 else 0, id))
            db.execSQL("DELETE FROM messages WHERE request=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun revokeAll() { helper.writableDatabase.execSQL("UPDATE requests SET authorized=0") }
}
