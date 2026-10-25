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
package com.boxlabs.hexdroid

import android.annotation.SuppressLint
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import java.io.IOException
import java.math.BigInteger
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * TLS for Secure DCC (SSEND and SCHAT). The side that accepted the TCP connection is the TLS
 * server and the side that sends requests is the client. Any peer certificate is accepted;
 * the server certificate's SHA-256 fingerprint is shown for comparison.
 */
object DccTls {
    private const val KEY_ALIAS = "hexdroid_dcc_tls"
    private const val HANDSHAKE_TIMEOUT_MS = 20_000
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** A completed handshake: protocol and the TLS server's certificate fingerprint. */
    data class Session(
        val protocol: String,
        val serverFingerprint: String?,
        val weAreServer: Boolean,
    )

    private class Identity(val key: PrivateKey, val cert: X509Certificate)

    @Volatile private var identity: Identity? = null
    @Volatile private var serverCtx: SSLContext? = null
    @Volatile private var clientCtx: SSLContext? = null

    /**
     * Handshake over [sock] and return the TLS socket; [server] is true on the accepting side.
     * Closes [sock] and throws [IOException] on failure. Blocking.
     */
    fun wrap(sock: Socket, server: Boolean): SSLSocket {
        val ssl = try {
            val ctx = if (server) serverContext() else clientContext()
            val host = sock.inetAddress?.hostAddress ?: ""
            ctx.socketFactory.createSocket(sock, host, sock.port, true) as SSLSocket
        } catch (t: Throwable) {
            runCatching { sock.close() }
            throw IOException("TLS setup failed: ${t.message ?: t::class.java.simpleName}", t)
        }
        try {
            ssl.useClientMode = !server
            val previousTimeout = sock.soTimeout
            ssl.soTimeout = HANDSHAKE_TIMEOUT_MS
            ssl.startHandshake()
            ssl.soTimeout = previousTimeout
        } catch (t: Throwable) {
            runCatching { ssl.close() }
            runCatching { sock.close() }
            val reason = if (t is SocketTimeoutException) "timed out" else (t.message ?: t::class.java.simpleName)
            throw IOException("TLS handshake failed: $reason", t)
        }
        return ssl
    }

    /** Protocol and server certificate fingerprint of a socket returned by [wrap]. */
    fun describe(ssl: SSLSocket, server: Boolean): Session {
        val session = ssl.session
        val cert = if (server) {
            session.localCertificates?.firstOrNull()
        } else {
            runCatching { session.peerCertificates.firstOrNull() }.getOrNull()
        }
        return Session(
            protocol = session.protocol ?: "TLS",
            serverFingerprint = (cert as? X509Certificate)?.let(::fingerprint),
            weAreServer = server,
        )
    }

    /** SHA-256 of [cert] as lowercase colon-separated hex. */
    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun serverContext(): SSLContext = serverCtx ?: synchronized(this) {
        serverCtx ?: SSLContext.getInstance("TLS").apply {
            init(arrayOf(IdentityKeyManager(identity())), arrayOf(AnyCertTrustManager), SecureRandom())
        }.also { serverCtx = it }
    }

    private fun clientContext(): SSLContext = clientCtx ?: synchronized(this) {
        clientCtx ?: SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(AnyCertTrustManager), SecureRandom())
        }.also { clientCtx = it }
    }

    /** The server identity: a Keystore key when usable, otherwise an in-memory one. */
    private fun identity(): Identity = synchronized(this) {
        identity ?: (runCatching { keystoreIdentity() }.getOrNull() ?: ephemeralIdentity())
            .also { identity = it }
    }

    private fun keystoreIdentity(): Identity? {
        NetworkCertificates.ensure(KEY_ALIAS)
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = ks.getKey(KEY_ALIAS, null) as? PrivateKey ?: return null
        val cert = ks.getCertificate(KEY_ALIAS) as? X509Certificate ?: return null
        // The TLS stack signs digests it computed itself.
        Signature.getInstance("NONEwithECDSA").run {
            initSign(key)
            update(ByteArray(32))
            sign()
        }
        return Identity(key, cert)
    }

    private fun ephemeralIdentity(): Identity {
        val kp = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val now = System.currentTimeMillis()
        val name = X500Name("CN=IRC client")
        val sigAlg = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
        val tbs = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(BigInteger(63, SecureRandom()).add(BigInteger.ONE)))
            setSignature(sigAlg)
            setIssuer(name)
            setSubject(name)
            setStartDate(Time(Date(now - DAY_MS)))
            setEndDate(Time(Date(now + 365L * DAY_MS)))
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(kp.public.encoded))
        }.generateTBSCertificate()
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(kp.private)
            update(tbs.getEncoded(ASN1Encoding.DER))
            sign()
        }
        val der = DERSequence(arrayOf<ASN1Encodable>(tbs, sigAlg, DERBitString(sig)))
            .getEncoded(ASN1Encoding.DER)
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(der.inputStream()) as X509Certificate
        return Identity(kp.private, cert)
    }

    /** Presents [id] as the TLS server certificate; never offers a client certificate. */
    private class IdentityKeyManager(private val id: Identity) : X509ExtendedKeyManager() {
        private fun pick(keyType: String?): String? =
            if (keyType == null || keyType.startsWith("EC", ignoreCase = true)) KEY_ALIAS else null

        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
            pick(keyType)

        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
            pick(keyType)

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
            pick(keyType)?.let { arrayOf(it) }

        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

        override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(id.cert)

        override fun getPrivateKey(alias: String?): PrivateKey = id.key
    }

    /** Accepts any non-empty chain; DCC peers are self-signed. */
    @SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
    private object AnyCertTrustManager : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = requireChain(chain)

        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = requireChain(chain)

        private fun requireChain(chain: Array<X509Certificate>?) {
            if (chain.isNullOrEmpty()) throw CertificateException("Peer sent no certificate")
        }
    }
}
