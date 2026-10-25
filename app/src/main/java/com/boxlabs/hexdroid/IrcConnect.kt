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
import com.boxlabs.hexdroid.connection.ConnectionConstants
import com.boxlabs.hexdroid.connection.SocksProxy
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Locale
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * SSLContext cache keyed by trust and key material, so reconnects to the same profile reuse
 * the JSSE session cache and get TLS session resumption. Entries are never evicted; the set
 * of distinct keys is small.
 */
private val sslContextCache = java.util.concurrent.ConcurrentHashMap<SslContextKey, SSLContext>()

/**
 * Key for [sslContextCache]: trust mode, client certificate (Keystore alias, or a digest of
 * the PKCS#12 and its password), and TOFU pin, since each changes the context's trust or
 * key material.
 */
private data class SslContextKey(
    val allowInvalidCerts: Boolean,
    val keystoreAlias: String?,
    val pkcs12Sha256: String?,
    val passwordSha256: String?,
    val tlsTofuFingerprint: String?,
)

/** Lowercase hex SHA-256 of [bytes]. */
private fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }

internal fun IrcClient.openSocket(): Socket {
    // Sane socket defaults for mobile networks.
    // - connect timeout avoids hanging forever on bad networks
    // - read timeout helps detect half-open connections (ping loop also guards this)
    fun baseSocket(): Socket = Socket().apply {
        tcpNoDelay = config.tcpNoDelay
        keepAlive = config.keepAlive
        soTimeout = config.readTimeoutMs
    }

    // When proxying, the destination is NOT resolved on-device — the proxy resolves it
    // remotely (mandatory for Tor `.onion`, and a DNS-leak guard for clearnet). So the
    // whole Happy-Eyeballs local-resolution loop below is bypassed; SocksProxy returns a
    // single already-connected raw socket that the TLS branch wraps just like a direct one.
    val proxyEnabled = config.proxy.enabled

    /**
     * Open the underlying (pre-TLS) TCP socket to the destination, either directly via
     * Happy-Eyeballs or through the configured SOCKS proxy. The TLS and plaintext paths
     * both build on this so the proxy logic lives in exactly one place.
     */
    fun openRawSocket(): Socket {
        if (proxyEnabled) {
            // The handshake leaves soTimeout at readTimeoutMs; socket options are applied
            // inside SocksProxy.connect to match baseSocket().
            return SocksProxy.connect(
                cfg = config.proxy,
                destHost = config.host,
                destPort = config.port,
                connectTimeoutMs = config.connectTimeoutMs,
                soTimeoutMs = config.readTimeoutMs,
                tcpNoDelay = config.tcpNoDelay,
                keepAlive = config.keepAlive,
                pinnedNetwork = config.pinnedNetwork,
            )
        }
        // FAIL CLOSED: if a proxy was selected but is misconfigured (e.g. blank host or
        // out-of-range port), do NOT silently fall back to a direct connection — that
        // would leak the real connection outside the tunnel the user explicitly asked
        // for. Refuse instead, with a message pointing at the fix.
        if (config.proxy.type != com.boxlabs.hexdroid.connection.ProxyType.NONE) {
            throw java.io.IOException(
                "Proxy is enabled for this network but misconfigured (host/port invalid). " +
                "Refusing to connect directly. Check the proxy settings."
            )
        }
        // Direct connection: resolve every A and AAAA address and try each in turn, taking the
        // first that connects. When pinned to a network, resolve on that network too.
        val resolved: Array<InetAddress> = try {
            resolveAllWithTimeout(config.host, config.pinnedNetwork, config.connectTimeoutMs)
        } catch (uhe: java.net.UnknownHostException) {
            throw java.net.UnknownHostException("Unable to resolve ${config.host}: ${uhe.message ?: "no DNS record"}")
        }
        require(resolved.isNotEmpty()) { "No addresses resolved for ${config.host}" }
        var lastError: Throwable? = null
        for (addr in resolved) {
            val s = baseSocket()
            try {
                // Pin to the chosen interface BEFORE connect (bindSocket requires an
                // unconnected socket). runCatching: if the network vanished between
                // selection and now, fall through to default routing. the connect will
                // then fail on a dead network and auto-reconnect handles it.
                config.pinnedNetwork?.let { net -> runCatching { net.bindSocket(s) } }
                s.connect(InetSocketAddress(addr, config.port), config.connectTimeoutMs)
                return s
            } catch (t: Throwable) {
                runCatching { s.close() }
                lastError = t
            }
        }
        throw lastError ?: java.net.ConnectException("All resolved addresses failed for ${config.host}")
    }

    return if (!config.useTls) {
        // Plaintext IRC: just the raw socket (direct Happy-Eyeballs, or via the proxy).
        openRawSocket()
    } else {
        // Shared SSLContext cache: reconnects to the same profile reuse the same context
        // and therefore the same JSSE session cache, letting the platform perform TLS
        // session resumption (abbreviated handshake). Cuts a round-trip on reconnects.
        //
        // SSLContext.init() may only be called ONCE per context instance, so the cache key
        // must encode every aspect of the context's behaviour, see [SslContextKey].
        val clientCert = config.clientCert
        val cacheKey = SslContextKey(
            allowInvalidCerts = config.allowInvalidCerts,
            keystoreAlias = clientCert?.keystoreAlias,
            pkcs12Sha256 = clientCert?.takeIf { it.keystoreAlias == null }?.let { sha256Hex(it.pkcs12) },
            passwordSha256 = clientCert?.password?.let { sha256Hex(it.toByteArray(Charsets.UTF_8)) },
            tlsTofuFingerprint = config.tlsTofuFingerprint,
        )
        // Pin mode follows allowInvalidCerts: off, the standard CA and hostname checks apply
        // and any stored pin is ignored; on, a permissive trust manager is used and the pin is
        // checked (or learned) after the handshake.
        val pinMode = config.allowInvalidCerts
        // A context built without the network's certificate (Keystore unavailable) is used for
        // this connection only, so the next connect tries the Keystore again.
        var cacheable = true
        val sslContext = sslContextCache[cacheKey] ?: run {
            val ctx = SSLContext.getInstance("TLS")
            val tm = if (pinMode) arrayOf<TrustManager>(InsecureTrustManager()) else null
            val km: Array<KeyManager>? = config.clientCert?.let { cert ->
                try {
                    val alias = cert.keystoreAlias
                    if (alias != null) {
                        // A per-network certificate is made the first time it's needed. This
                        // runs on the connect thread, since Keystore generation is slow. If the
                        // Keystore can't make or read it, connect without one rather than not at all.
                        try {
                            if (NetworkCertificates.ensure(alias)) netCertCreated = true
                            arrayOf<KeyManager>(KeystoreKeyManager(alias))
                        } catch (t: Throwable) {
                            android.util.Log.w("IrcCore", "network certificate unavailable", t)
                            netCertUnavailable = true
                            cacheable = false
                            null
                        }
                    } else {
                        val ks = KeyStore.getInstance("PKCS12")
                        val pwdChars = cert.password?.toCharArray()
                        ByteArrayInputStream(cert.pkcs12).use { ks.load(it, pwdChars) }
                        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                        kmf.init(ks, pwdChars)
                        kmf.keyManagers
                    }
                } catch (t: Throwable) {
                    throw IllegalStateException(
                        "Client certificate could not be loaded: " + (t.message ?: t::class.java.simpleName),
                        t
                    )
                }
            }
            ctx.init(km, tm, SecureRandom())
            if (cacheable) sslContextCache.putIfAbsent(cacheKey, ctx) ?: ctx else ctx
        }

        // Obtain the underlying TCP socket (direct Happy-Eyeballs, or through the SOCKS
        // proxy). When proxying, resolution + connect happen at the proxy; otherwise we
        // try each resolved address until one connects. The TLS handshake below is then
        // attempted exactly once on that socket — a TLS failure is a configuration issue
        // (cert/protocol mismatch) where retrying a different IP wouldn't help and would
        // only obscure the real cause.
        val rawSocket = openRawSocket()

        val ss = sslContext.socketFactory.createSocket(rawSocket, config.host, config.port, true) as SSLSocket
        val allowed = ss.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }
        if (allowed.isNotEmpty()) ss.enabledProtocols = allowed.toTypedArray()

        // Hostname verification (RFC 6125) runs post-handshake rather than via
        // setEndpointIdentificationAlgorithm, so a mismatch can be reported with the names the
        // certificate actually carries and accepted per profile. It fails closed either way.

        // Bound the TLS handshake so a stalled negotiation fails and reconnects instead of
        // hanging; the read timeout is restored afterwards.
        ss.soTimeout = ConnectionConstants.TLS_HANDSHAKE_TIMEOUT_MS
        try {
            ss.startHandshake()
        } catch (e: Exception) {
            runCatching { ss.close() }
            runCatching { rawSocket.close() }
            // Flush this context's client session cache now so the immediate retry performs a clean FULL handshake.
            // Only runs on failure, so successful reconnects keep the resumption fast-path.
            runCatching {
                val sessions = sslContext.clientSessionContext
                val ids = sessions.ids
                while (ids.hasMoreElements()) {
                    sessions.getSession(ids.nextElement())?.invalidate()
                }
            }
            throw e
        }
        ss.soTimeout = config.readTimeoutMs  // restore post-handshake timeout

        // Skipped in pin mode, where the stored fingerprint is the identity proof.
        if (!pinMode) {
            val leaf = runCatching { ss.session.peerCertificates.firstOrNull() }
                .getOrNull() as? java.security.cert.X509Certificate
            if (leaf == null) {
                runCatching { ss.close() }
                runCatching { rawSocket.close() }
                throw java.io.IOException("TLS: peer presented no X.509 certificate")
            }
            if (!hostnameMatchesCert(config.host, leaf)) {
                val ids = certIdentities(leaf)
                val accepted = config.tlsAcceptedIdentities.map { it.lowercase(Locale.ROOT) }.toSet()
                val ok = ids.isNotEmpty() && accepted.containsAll(ids.map { it.lowercase(Locale.ROOT) })
                if (!ok) {
                    runCatching { ss.close() }
                    runCatching { rawSocket.close() }
                    throw TlsHostnameMismatchException(expected = config.host, identities = ids)
                }
            }
        }

        // TOFU pinning after the handshake, only with invalid certificates allowed: hash the
        // leaf certificate; with no pin stored, learn it (emitted as TlsFingerprintLearned);
        // otherwise compare it with the primary and extra pins and throw
        // [TlsFingerprintMismatchException] on mismatch. With a pin stored, any failure to
        // check is fatal; without one, it is logged and skipped.
        val storedRaw = config.tlsTofuFingerprint?.takeIf { it.isNotBlank() }
        if (!config.allowInvalidCerts) {
            // CA-validated connection. TOFU is dormant: any stored fingerprint stays in
            // the profile (so flipping invalid-certs back on later picks up where it left
            // off), but is neither verified against nor learned from this connection.
            // The handshake's standard trust path is the authority here.
        } else if (storedRaw != null) {
            // Pin enforcement path: any failure here closes the socket and propagates.
            val actualFp = try {
                val peerCerts = ss.session.peerCertificates
                val leaf = peerCerts.firstOrNull() as? java.security.cert.X509Certificate
                    ?: throw java.io.IOException("TLS pin enforcement: peer presented no X.509 certificate")
                computeFingerprintSha256(leaf)
            } catch (t: Throwable) {
                runCatching { ss.close() }
                runCatching { rawSocket.close() }
                if (t is java.io.IOException) throw t
                throw java.io.IOException("TLS pin enforcement failed: ${t.message ?: t::class.java.simpleName}", t)
            }
            val storedNorm = normaliseFingerprint(storedRaw)
            // Round-robin DNS: accept the connection if the fingerprint matches the primary pin
            // or any of the extra accepted pins, since each server behind the name has its own
            // certificate.
            val acceptedNormSet = config.tlsTofuFingerprints
                .asSequence()
                .map { normaliseFingerprint(it) }
                .toSet() + storedNorm
            if (!acceptedNormSet.any { it.equals(actualFp, ignoreCase = true) }) {
                runCatching { ss.close() }
                runCatching { rawSocket.close() }
                throw TlsFingerprintMismatchException(
                    stored = storedNorm,
                    actual = actualFp,
                )
            }
        } else {
            // Learning path: best-effort. Only reached when allowInvalidCerts is on AND
            // no pin is stored yet - exactly the "first connect to a self-signed bouncer"
            // case TOFU is designed for.
            runCatching {
                val peerCerts = ss.session.peerCertificates
                val leaf = peerCerts.firstOrNull() as? java.security.cert.X509Certificate
                if (leaf != null) {
                    learnedFingerprint = computeFingerprintSha256(leaf)
                }
            }
        }

        // Capture basic session info for UI (cipher/protocol/cert subject).
        // The trust-mode label states what authenticated the peer.
        lastTlsInfo = runCatching {
            val sess = ss.session
            val proto = sess.protocol ?: "?"
            val cipher = sess.cipherSuite ?: "?"
            val peer = runCatching { sess.peerPrincipal?.name }.getOrNull()
            //   pinned          - TOFU fingerprint matched; chain was bypassed.
            //   unverified      - invalid-certs on, nothing pinned yet.
            //   verified        - CA chain and RFC 6125 hostname both passed.
            //   name accepted   - CA chain passed, hostname accepted by the user.
            val pinned = config.allowInvalidCerts && !config.tlsTofuFingerprint.isNullOrBlank()
            val nameMatched = pinned || config.allowInvalidCerts ||
                runCatching {
                    (ss.session.peerCertificates.firstOrNull() as? java.security.cert.X509Certificate)
                        ?.let { hostnameMatchesCert(config.host, it) } ?: false
                }.getOrDefault(false)
            val verified = when {
                pinned -> "(pinned)"
                config.allowInvalidCerts -> "(unverified)"
                nameMatched -> "(verified)"
                else -> "(name accepted)"
            }
            val peerShort = peer?.substringAfter("CN=")?.substringBefore(',')?.takeIf { it.isNotBlank() }
                ?: peer
                ?: "peer"
            "$proto $cipher $verified • $peerShort"
        }.getOrNull()

        // Apply socket options on the wrapped SSLSocket as well.
        ss.tcpNoDelay = config.tcpNoDelay
        ss.keepAlive = config.keepAlive
        ss.soTimeout = config.readTimeoutMs

        ss
    }
}

/**
 * Classify [t] as a [DisconnectCode] from the raw exception text, which is always English,
 * rather than from the translated friendly message.
 */
internal fun errorCode(t: Throwable): DisconnectCode {
    val chain = generateSequence<Throwable>(t) { it.cause.takeIf { c -> c !== it } }
        .take(8)
        .toList()
    val raw = chain.mapNotNull { it.message }.joinToString(" | ")
    val anyIs: (Class<out Throwable>) -> Boolean = { cls -> chain.any { cls.isInstance(it) } }
    fun has(vararg needles: String) = needles.any { raw.contains(it, ignoreCase = true) }

    return when {
        anyIs(java.net.UnknownHostException::class.java) ||
            has("UnknownHost", "No address associated", "nodename nor servname",
                "Name or service not known", "resolution timed out") ->
            DisconnectCode.HOST_UNREACHABLE
        has("Connection refused") -> DisconnectCode.HOST_UNREACHABLE
        anyIs(SSLHandshakeException::class.java) ||
            has("CERTIFICATE_VERIFY_FAILED", "CertPathValidatorException",
                "PROTOCOL_VERSION", "NO_PROTOCOLS_AVAILABLE", "HANDSHAKE_FAILURE",
                "TLS pin enforcement") ->
            DisconnectCode.TLS_UNRECOVERABLE
        has("Connection reset", "ECONNRESET", "Broken pipe") ->
            DisconnectCode.CONNECTION_RESET
        anyIs(java.net.SocketTimeoutException::class.java) ||
            has("Connection timed out", "connect timed out", "read timed out") ->
            DisconnectCode.READ_TIMEOUT
        else -> DisconnectCode.CONNECTION_ERROR
    }
}

/**
 * As [errorCode], but for a failure during the connect attempt: anything not recognised
 * as a specific unrecoverable cause is CONNECT_FAILED rather than CONNECTION_ERROR, so
 * the VM can tell "never got a session" from "session died".
 */
internal fun connectFailureCode(t: Throwable): DisconnectCode {
    val code = errorCode(t)
    return if (code == DisconnectCode.HOST_UNREACHABLE || code == DisconnectCode.TLS_UNRECOVERABLE) {
        code
    } else {
        DisconnectCode.CONNECT_FAILED
    }
}

/**
 * Turn raw exception text, especially OpenSSL/BoringSSL messages, into something a user can act
 * on. Walks the cause chain, since Android often wraps the real error.
 */
internal fun IrcClient.friendlyErrorMessage(t: Throwable): String {
    // Walk the cause chain (bounded to 8 hops to avoid pathological cycles) so the
    // message-substring checks can match regardless of which wrapper level carries
    // the diagnostic string.
    val chain = generateSequence<Throwable>(t) { it.cause.takeIf { c -> c !== it } }
        .take(8)
        .toList()
    // Dedup the cause chain BEFORE joining.
    val raw = chain.mapNotNull { it.message?.trim()?.takeIf { s -> s.isNotEmpty() } }
        .distinct()
        .joinToString(" | ")
        .ifBlank { t::class.java.simpleName }
    val anyIs: (Class<out Throwable>) -> Boolean = { cls -> chain.any { cls.isInstance(it) } }

    // Proxy (SOCKS) failures: surface the proxy's own diagnostic, which SocksProxy already
    // phrases for humans ("connection refused by destination", "proxy requires username…").
    // Prefix so the user knows the failure was at the proxy hop, not the IRC server.
    if (anyIs(com.boxlabs.hexdroid.connection.ProxyException::class.java)) {
        val msg = chain.firstNotNullOfOrNull {
            (it as? com.boxlabs.hexdroid.connection.ProxyException)?.message
        } ?: raw
        return tr(R.string.core_err_proxy, msg)
    }

    // SSL handshake failures (certificate problems, protocol mismatch).
    // Hostname mismatch is not here: it raises TlsHostnameMismatchException post-handshake,
    // never an SSLHandshakeException.
    if (anyIs(SSLHandshakeException::class.java)) {
        return when {
            raw.contains("CERTIFICATE_VERIFY_FAILED", ignoreCase = true) ||
            raw.contains("CertPathValidatorException", ignoreCase = true) ->
                tr(R.string.core_err_tls_cert_verify)
            raw.contains("PROTOCOL_VERSION", ignoreCase = true) ||
            raw.contains("NO_PROTOCOLS_AVAILABLE", ignoreCase = true) ->
                tr(R.string.core_err_tls_protocol)
            raw.contains("HANDSHAKE_FAILURE", ignoreCase = true) ->
                tr(R.string.core_err_tls_rejected)
            else ->
                tr(R.string.core_err_tls_handshake_other, raw.take(120))
        }
    }

    // General SSL exceptions (mid-session errors)
    if (anyIs(SSLException::class.java)) {
        return when {
            // BoringSSL "Success" (TCP closed without close_notify) and "Internal OpenSSL
            // error" (a stale session after the app was killed) aren't actionable; the
            // reconnect handles them.
            raw.contains(", Success", ignoreCase = false) ||
            raw.contains("I/O error during system call, Success", ignoreCase = true) ||
            raw.contains("Internal error in SSL library", ignoreCase = true) ||
            raw.contains("SSL_ERROR_INTERNAL", ignoreCase = true) ||
            raw.contains("Internal OpenSSL error", ignoreCase = true) ||
            raw.contains("or protocol error", ignoreCase = true) ->
                tr(R.string.core_err_tls_interrupted)
            raw.contains("Connection reset", ignoreCase = true) ||
            raw.contains("ECONNRESET", ignoreCase = true) ->
                tr(R.string.core_err_connection_reset)
            raw.contains("PROTOCOL_ERROR", ignoreCase = true) ||
            raw.contains("protocol_error", ignoreCase = true) ->
                tr(R.string.core_err_tls_proto_error)
            raw.contains("Read error", ignoreCase = true) ||
            raw.contains("SSL_ERROR_SYSCALL", ignoreCase = true) ->
                tr(R.string.core_err_tls_read)
            raw.contains("write", ignoreCase = true) ->
                tr(R.string.core_err_tls_write)
            raw.contains("closed", ignoreCase = true) ||
            raw.contains("shutdown", ignoreCase = true) ->
                tr(R.string.core_err_tls_closed)
            else ->
                tr(R.string.core_err_tls_other, raw.take(120))
        }
    }

    // Non-SSL socket/IO errors
    return when {
        raw.contains("Connection refused", ignoreCase = true) ->
            tr(R.string.core_err_connection_refused)
        raw.contains("Network is unreachable", ignoreCase = true) ->
            tr(R.string.core_err_network_unreachable)
        raw.contains("Connection timed out", ignoreCase = true) ||
        raw.contains("connect timed out", ignoreCase = true) ->
            tr(R.string.core_err_connection_timed_out)
        raw.contains("resolution timed out", ignoreCase = true) ->
            tr(R.string.core_err_dns_timeout)
        raw.contains("UnknownHost", ignoreCase = true) ||
        raw.contains("No address associated", ignoreCase = true) ->
            tr(R.string.core_err_dns_failed)
        raw.contains("Broken pipe", ignoreCase = true) ->
            tr(R.string.core_err_broken_pipe)
        raw.contains("Connection reset", ignoreCase = true) ->
            tr(R.string.core_err_connection_reset)
        raw.contains("Socket closed", ignoreCase = true) ->
            tr(R.string.core_err_connection_closed)
        else ->
            raw.take(160)
    }
}

@SuppressLint("TrustAllX509TrustManager")
private class InsecureTrustManager : X509TrustManager {
    override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
}

/**
 * Sentinel exception thrown from [openSocket] when a TOFU-pinned server presents a
 * certificate whose SHA-256 fingerprint does NOT match the stored one. Carries both the
 * expected (stored) and presented (actual) fingerprints so the [events] flow can surface
 * them to the user via [IrcEvent.TlsFingerprintChanged]. This is a distinct type (not a
 * plain IOException) so the flow can recognise it without string-matching the message.
 */
internal class TlsFingerprintMismatchException(
    val stored: String,
    val actual: String,
) : java.io.IOException(
    "TLS certificate fingerprint mismatch — expected $stored, got $actual"
)

internal class TlsHostnameMismatchException(
    val expected: String,
    val identities: List<String>,
) : java.io.IOException(
    "TLS certificate is not valid for $expected (cert names: ${identities.joinToString(", ").ifEmpty { "none" }})"
)

/**
 * Compute the SHA-256 fingerprint of an X.509 certificate's DER encoding, formatted as
 * lowercase hex pairs separated by colons (e.g. "a1:b2:c3:…"). This is the canonical
 * representation used by OpenSSL, Firefox, and most IRC clients that expose TOFU, so it
 * round-trips cleanly if a user ever wants to copy-paste a fingerprint between tools.
 */
private fun computeFingerprintSha256(cert: java.security.cert.X509Certificate): String {
    val der = cert.encoded
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val digest = md.digest(der)
    val sb = StringBuilder(digest.size * 3)
    for ((i, b) in digest.withIndex()) {
        if (i > 0) sb.append(':')
        sb.append(String.format("%02x", b.toInt() and 0xFF))
    }
    return sb.toString()
}

/**
 * RFC 6125 check of [host] against the certificate's SAN DNS and IP entries. A wildcard matches
 * only as the whole left-most label (`*.example.com` matches `irc.example.com`, not
 * `a.b.example.com`). IPs are compared as strings. The subject CN is not used.
 */
private fun hostnameMatchesCert(host: String, cert: java.security.cert.X509Certificate): Boolean {
    val hostLower = host.lowercase(Locale.ROOT).trimEnd('.')
    val sans = runCatching { cert.subjectAlternativeNames }.getOrNull() ?: return false
    for (entry in sans) {
        val type = entry.getOrNull(0) as? Int ?: continue
        val value = (entry.getOrNull(1) as? String)?.lowercase(Locale.ROOT) ?: continue
        when (type) {
            2 /* dNSName */ -> if (dnsNameMatches(hostLower, value)) return true
            7 /* iPAddress */ -> if (ipAddressMatches(hostLower, value)) return true
        }
    }
    return false
}

private fun ipAddressMatches(host: String, value: String): Boolean {
    if (host == value) return true
    // Only literals are compared: getByName on a hostname would resolve it, outside any proxy.
    if (!isIpLiteral(host) || !isIpLiteral(value)) return false
    return runCatching {
        java.net.InetAddress.getByName(host) == java.net.InetAddress.getByName(value)
    }.getOrDefault(false)
}

/** True for a dotted IPv4 or an IPv6 literal; never resolves. */
private fun isIpLiteral(s: String): Boolean =
    parseDccAddress(s) != null && (s.contains(':') || s.count { it == '.' } == 3)

/**
 * Every identity an X.509 certificate claims: SAN dNSNames, SAN iPAddresses, and the subject
 * CN. The CN is reported but never matched on ([hostnameMatchesCert] follows RFC 6125 §6.4.4).
 */
private fun certIdentities(cert: java.security.cert.X509Certificate): List<String> {
    val out = LinkedHashSet<String>()
    runCatching { cert.subjectAlternativeNames }.getOrNull()?.forEach { entry ->
        val type = entry.getOrNull(0) as? Int
        val value = entry.getOrNull(1) as? String
        if ((type == 2 || type == 7) && !value.isNullOrBlank()) out.add(value.lowercase(Locale.ROOT))
    }
    runCatching {
        val dn = cert.subjectX500Principal.getName(javax.security.auth.x500.X500Principal.RFC2253)
        Regex("(?:^|,)CN=([^,]+)").find(dn)?.groupValues?.get(1)
    }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let { out.add(it.lowercase(Locale.ROOT)) }
    return out.toList()
}

private fun dnsNameMatches(host: String, pattern: String): Boolean {
    if (host == pattern) return true
    // Leftmost-only wildcard: "*.example.com" matches "irc.example.com" but not
    // "a.b.example.com" and not "example.com" itself.
    if (pattern.startsWith("*.")) {
        val suffix = pattern.substring(1) // ".example.com"
        if (!host.endsWith(suffix)) return false
        val prefix = host.dropLast(suffix.length)
        // Prefix must be exactly one label: non-empty and no dots.
        return prefix.isNotEmpty() && !prefix.contains('.')
    }
    return false
}

/**
 * Normalise a TOFU fingerprint string for comparison. The stored value SHOULD already
 * be lowercase colon-separated hex (we emit it that way in [computeFingerprintSha256])
 * but accept a few common variations defensively: uppercase hex, missing colons, or
 * stray whitespace. This way a user who pastes a fingerprint from OpenSSL or a browser
 * into the profile JSON by hand doesn't get locked out by formatting alone.
 */
private fun normaliseFingerprint(raw: String): String {
    val cleaned = raw.trim().lowercase().filter { it.isLetterOrDigit() }
    // Re-insert colons between byte pairs.
    return buildString(cleaned.length + cleaned.length / 2) {
        for (i in cleaned.indices) {
            if (i > 0 && i % 2 == 0) append(':')
            append(cleaned[i])
        }
    }
}
