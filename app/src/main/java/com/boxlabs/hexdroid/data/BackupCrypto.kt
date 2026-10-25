/*
 * HexDroidIRC - An IRC Client for Android
 * Copyright (C) 2026 boxlabs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.boxlabs.hexdroid.data

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase encryption for the passwords in a backup file: a key from Argon2id over the
 * passphrase, then AES-256-GCM. The KDF parameters travel with the data, so later builds can
 * raise them and still open older backups.
 */
object BackupCrypto {

    /** Encrypted data and everything needed to open it except the passphrase. */
    data class Sealed(
        val iterations: Int,
        val memoryKb: Int,
        val parallelism: Int,
        val salt: String,
        val iv: String,
        val data: String,
    )

    /** The passphrase does not open the data, or the data was altered. */
    class WrongPassphrase : Exception("Wrong passphrase")

    const val ITERATIONS = 3
    const val MEMORY_KB = 32 * 1024
    const val PARALLELISM = 1
    const val MIN_PASSPHRASE_LENGTH = 8

    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Upper bounds accepted when opening, so a crafted file can't demand gigabytes. */
    private const val MAX_ITERATIONS = 20
    private const val MAX_MEMORY_KB = 256 * 1024
    private const val MAX_PARALLELISM = 8

    private val random = SecureRandom()

    fun seal(plain: ByteArray, passphrase: CharArray): Sealed {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val key = deriveKey(passphrase, salt, ITERATIONS, MEMORY_KB, PARALLELISM)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            val data = cipher.doFinal(plain)
            val b64 = Base64.getEncoder()
            return Sealed(ITERATIONS, MEMORY_KB, PARALLELISM, b64.encodeToString(salt), b64.encodeToString(iv), b64.encodeToString(data))
        } finally {
            key.fill(0)
        }
    }

    /** @throws WrongPassphrase When [passphrase] is wrong or the data was changed. */
    fun open(sealed: Sealed, passphrase: CharArray): ByteArray {
        require(sealed.iterations in 1..MAX_ITERATIONS) { "Unsupported KDF iterations" }
        require(sealed.memoryKb in 8..MAX_MEMORY_KB) { "Unsupported KDF memory" }
        require(sealed.parallelism in 1..MAX_PARALLELISM) { "Unsupported KDF parallelism" }
        val b64 = Base64.getDecoder()
        val salt = b64.decode(sealed.salt)
        val iv = b64.decode(sealed.iv)
        val data = b64.decode(sealed.data)
        val key = deriveKey(passphrase, salt, sealed.iterations, sealed.memoryKb, sealed.parallelism)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            return try {
                cipher.doFinal(data)
            } catch (_: AEADBadTagException) {
                throw WrongPassphrase()
            }
        } finally {
            key.fill(0)
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int, memoryKb: Int, parallelism: Int): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withIterations(iterations)
            .withMemoryAsKB(memoryKb)
            .withParallelism(parallelism)
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(params)
        val out = ByteArray(KEY_BYTES)
        val pw = String(passphrase).toByteArray(Charsets.UTF_8)
        try {
            gen.generateBytes(pw, out)
        } finally {
            pw.fill(0)
        }
        return out
    }
}
