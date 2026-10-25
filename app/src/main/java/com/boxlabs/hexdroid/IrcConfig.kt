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

import com.boxlabs.hexdroid.connection.ConnectionConstants
import com.boxlabs.hexdroid.data.AutoJoinChannel

enum class SaslMechanism { PLAIN, EXTERNAL, SCRAM_SHA_256 }

sealed class SaslConfig {
    data object Disabled : SaslConfig()
    data class Enabled(
        val mechanism: SaslMechanism,
        val authcid: String?,
        val password: String?
    ) : SaslConfig()
}

data class CapPrefs(
    val messageTags: Boolean = true,
    val serverTime: Boolean = true,
    val echoMessage: Boolean = true,
    val labeledResponse: Boolean = true,
    val batch: Boolean = true,
    // Both the graduated cap and its draft alias are requested so we work with
    // older (draft/chathistory) and modern (chathistory) servers simultaneously.
    val draftChathistory: Boolean = true,
    val draftEventPlayback: Boolean = true,
    val utf8Only: Boolean = true,
    val accountNotify: Boolean = true,
    val awayNotify: Boolean = true,
    val chghost: Boolean = true,
    val extendedJoin: Boolean = true,
    val inviteNotify: Boolean = true,
    val multiPrefix: Boolean = true,
    val setname: Boolean = true,
    val userhostInNames: Boolean = false,
    val draftRelaymsg: Boolean = false,
    val draftReadMarker: Boolean = true,
    /** IRCv3 MONITOR: track online/offline status of specific nicks. */
    val monitor: Boolean = true,
    /** IRCv3 account-tag: include services account in PRIVMSG/NOTICE tags. */
    val accountTag: Boolean = true,
    /** draft/typing (+typing tag): show when other users are typing. */
    val typingIndicator: Boolean = true,
    /** soju.im/no-implicit-names: suppress automatic NAMES list on JOIN (bouncer only). */
    val sojuNoImplicitNames: Boolean = true,
    /**
     * IRCv3 standard-replies (FAIL/WARN/NOTE): structured error replies from modern IRCd.
     */
    val standardReplies: Boolean = true,
    /**
     * IRCv3 pre-away: allows sending AWAY before numeric 001.
     */
    val preAway: Boolean = true,
    /**
     * IRCv3 message-ids (msgid tag): unique ID per message, used for deduplication
     * when echo-message and history replay are both active.
     */
    val messageIds: Boolean = true,
    /**
     * soju.im/read: soju's proprietary read-marker (parallel to draft/read-marker).
     * Requesting both ensures read position syncs on soju-based bouncers.
     */
    val sojuRead: Boolean = true,
    /**
     * WHOX: send WHO #chan %tuhsnfar,42 on join to obtain full ident/host/account for all
     * members. Only sent when the server advertises WHOX in ISUPPORT (005).
     */
    val whox: Boolean = true,

    /**
     * draft/channel-rename: handle RENAME commands so channel renames update the buffer
     * key and display name without a full re-join cycle.
     */
    val channelRename: Boolean = true,

    /**
     * draft/extended-monitor: richer MONONLINE replies that include account name and
     * real name alongside the nick!user@host prefix. Ergo 2.13+.
     */
    val extendedMonitor: Boolean = true,

    /**
     * draft/message-reactions: emoji reactions sent via TAGMSG +draft/react.
     * When enabled, incoming reactions are surfaced as status lines in the buffer.
     * Outgoing reactions: long-press a message in the chat UI to pick a preset emoji,
     * or use the /react and /unreact slash commands for arbitrary emoji.
     */
    val messageReactions: Boolean = true,

    /**
     * draft/no-implicit-names: suppress automatic NAMES list on JOIN (generic form,
     * graduated from the draft). Parallel to soju.im/no-implicit-names.
     */
    val noImplicitNames: Boolean = false,

    /**
     * draft/multiline (also requested as `multiline`): receive a message delivered as a multiline
     * BATCH as one ChatMessage or Notice. `+draft/multiline-concat` on a line joins it without a
     * newline. Sending builds the same batches (see [buildMultilineWireLines]).
     */
    val multiline: Boolean = true,
    /** draft/message-redaction: negotiate the cap and offer "Delete message" for own messages. */
    val messageRedaction: Boolean = true,
    /** draft/account-registration: negotiate the cap and enable /register and /verify. */
    val accountRegistration: Boolean = true,
    /** draft/extended-isupport: fetch the ISUPPORT list before registration completes. */
    val extendedIsupport: Boolean = true,
    /** draft/metadata-2: user/channel metadata (display-name, avatar). Requires [batch]. */
    val metadata2: Boolean = true,
    /**
     * soju.im/FILEHOST uploads. the endpoint arrives via ISUPPORT, but grouped
     * with the per-network capability toggles so the attach button can be disabled per network.
     */
    val filehostUploads: Boolean = true,

    /**
     * draft/webpush (soju.im/webpush): lets the server deliver messages of interest as
     * Web Push notifications while no TCP connection is open. The push endpoint comes
     * from a UnifiedPush distributor on the device and is handed to the server with
     * WEBPUSH REGISTER.
     */
    val webPush: Boolean = true
)

/** One change in a channel MODE line: [adding], the mode letter, and its argument when it takes one. */
data class ChannelModeChange(val adding: Boolean, val mode: Char, val arg: String?)

data class TlsClientCert(
    val pkcs12: ByteArray = ByteArray(0),
    val password: String? = null,
    /** An Android Keystore alias (see [NetworkCertificates]), used instead of [pkcs12]. */
    val keystoreAlias: String? = null,
)

data class IrcConfig(
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val allowInvalidCerts: Boolean,
    val nick: String,
    val altNick: String?,
    val username: String,
    val realname: String,
    val serverPassword: String? = null,
    val sasl: SaslConfig = SaslConfig.Disabled,
    val clientCert: TlsClientCert? = null,
    val capPrefs: CapPrefs = CapPrefs(),
    val autoJoin: List<AutoJoinChannel> = emptyList(),
    val historyLimit: Int = 50,
    val connectTimeoutMs: Int = ConnectionConstants.SOCKET_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Int = ConnectionConstants.SOCKET_READ_TIMEOUT_MS,
    val tcpNoDelay: Boolean = false,  // Nagle coalescing is fine for IRC; disabling it causes extra radio wake-ups
    val keepAlive: Boolean = ConnectionConstants.TCP_KEEPALIVE,
    /**
     * Character encoding for this connection.
     * - "auto" = try UTF-8, auto-detect non-UTF-8 encodings
     * - Or explicit: "UTF-8", "windows-1251", "ISO-8859-1", etc.
     */
    val encoding: String = "auto",
    /** True when connecting through a bouncer (ZNC, soju, etc). */
    val isBouncer: Boolean = false,
    /**
     * Optional away message to set at connection time (pre-away).
     * When non-null and the pre-away CAP is negotiated, AWAY is sent before 001
     * so the server marks the user as away from session start.
     */
    val initialAwayMessage: String? = null,
    /** Answer CTCP queries. ACTION and DCC are unaffected: neither sends a reply. */
    val ctcpRepliesEnabled: Boolean = true,
    /**
     * TOFU certificate fingerprint (SHA-256, lowercase hex, colon-separated). Only used with
     * [allowInvalidCerts]: the first connect reports it via [IrcEvent.TlsFingerprintLearned]; later
     * connects enforce only the pin and refuse on mismatch ([IrcEvent.TlsFingerprintChanged]).
     * Without invalid certs, normal chain and hostname checks apply and any pin is ignored.
     */
    val tlsTofuFingerprint: String? = null,
    /**
     * Additional accepted TOFU fingerprints. Used for round-robin DNS hosts where the
     * connection lands on a different server (and thus a different cert) each time.
     * The verifier accepts any peer whose fingerprint is either [tlsTofuFingerprint] OR a
     * member of this set. Empty for the common single-server case. Same [allowInvalidCerts]
     * gating as [tlsTofuFingerprint] - dormant when invalid-certs is off.
     */
    val tlsTofuFingerprints: Set<String> = emptySet(),
    /**
     * Certificate identities the user has accepted for this profile after a hostname mismatch,
     * lowercased. The connection proceeds when the peer certificate's identity set is a subset
     * of this one. Only consulted when [allowInvalidCerts] is off.
     */
    val tlsAcceptedIdentities: Set<String> = emptySet(),
    /**
     * Bouncer protocol this profile targets, which sets the SASL authcid and USER syntax in
     * [effectiveAuthIdentity]: NONE direct; SOJU `user/network@clientid`; ZNC
     * `user@clientid/network`; GENERIC `user/network` with no client id. The order matters: the
     * wrong one routes to the wrong upstream or fails authentication.
     */
    val bouncerKind: BouncerKind = BouncerKind.NONE,
    /**
     * For bouncers using a network selector in the username: the upstream network name.
     * Composed into the auth identity per [bouncerKind]'s rules. Use [effectiveAuthIdentity]
     * at every send site rather than concatenating inline.
     */
    val bouncerNetworkName: String? = null,
    /**
     * For bouncers supporting per-client identification (ZNC clientbuffer, soju per-client
     * history): a short identifier for THIS device/client (e.g. "phone", "desktop").
     * Composed into the auth identity per [bouncerKind]'s rules. Leave null when not using
     * per-client buffers, or for bouncers that don't support the concept.
     */
    val bouncerClientId: String? = null,
    /**
     * SOCKS proxy to tunnel this connection through. Default [ProxyConfig] has
     * type NONE (direct connection)
     */
    val proxy: com.boxlabs.hexdroid.connection.ProxyConfig = com.boxlabs.hexdroid.connection.ProxyConfig(),
    /**
     * Network to bind this connection's socket (and, on the direct path, DNS) to, or null for
     * default routing. Pinning makes a Wi-Fi/cellular handoff drop the socket promptly, and lets
     * the ViewModel match its onLost callback to this connection.
     */
    val pinnedNetwork: android.net.Network? = null
) {
    /**
     * The authentication identity for this connection, with the bouncer network name and client id
     * added in [bouncerKind]'s syntax. Returned unchanged for NONE, when both fields are blank, or
     * when [base] already contains '/' (hand-assembled). A bare '@' is not treated as
     * hand-assembled, since usernames can be email addresses.
     *
     * SOJU libera/phone -> user/libera@phone; ZNC libera/phone -> user@phone/libera; GENERIC ->
     * user/libera.
     */
    fun effectiveAuthIdentity(base: String): String {
        if (bouncerKind == BouncerKind.NONE) return base

        val net = bouncerNetworkName?.takeIf { it.isNotBlank() }
        val cid = bouncerClientId?.takeIf { it.isNotBlank() }
        if (net == null && cid == null) return base

        // Legacy hand-rolled identity (`/` is the unambiguous bouncer-network separator).
        // Don't double up.
        if (base.contains('/')) return base

        return when (bouncerKind) {
            BouncerKind.NONE -> base
            BouncerKind.SOJU -> when {
                net != null && cid != null -> "$base/$net@$cid"
                net != null -> "$base/$net"
                cid != null -> "$base@$cid"
                else -> base
            }
            BouncerKind.ZNC -> when {
                net != null && cid != null -> "$base@$cid/$net"
                net != null -> "$base/$net"
                cid != null -> "$base@$cid"
                else -> base
            }
            // Generic bouncers (kiwibnc, pounce single-network, anything not explicitly typed):
            // accept the soju-style `user/network` form. Ignore clientId since the convention
            // varies and we can't safely guess the order.
            BouncerKind.GENERIC -> if (net != null) "$base/$net" else base
        }
    }

    /**
     * The PASS value for this connection. For bouncer profiles with a network name or client id it
     * is `<authcid>:<password>`, with the authcid from [effectiveAuthIdentity]; direct connections
     * pass the password unchanged. Left alone when it already looks hand-assembled (a '/' before
     * the first colon). Null when [password] is blank, so no PASS line is sent.
     */
    fun effectivePassLine(password: String?): String? {
        val pw = password?.takeIf { it.isNotBlank() } ?: return null
        if (bouncerKind == BouncerKind.NONE) return pw

        val net = bouncerNetworkName?.takeIf { it.isNotBlank() }
        val cid = bouncerClientId?.takeIf { it.isNotBlank() }
        if (net == null && cid == null) return pw  // nothing to prepend

        // Hand-assembled detection: require a `/` before the first `:`. The `/` is the
        // unambiguous bouncer-network separator (no real IRC username may contain it),
        // so its presence is a strong signal the user typed the full identity themselves.
        // `@` is not used because it's common in passwords.
        val firstColon = pw.indexOf(':')
        if (firstColon > 0 && pw.substring(0, firstColon).contains('/')) return pw

        // Otherwise compose: <effective-authcid>:<password>.
        // Reuses effectiveAuthIdentity so SOJU vs ZNC ordering and clientId handling
        // stay in one place.
        return "${effectiveAuthIdentity(username)}:$pw"
    }
}

/**
 * Bouncer protocol family. Determines how [IrcConfig.effectiveAuthIdentity] composes the
 * username/network/clientId fields into the SASL authcid and USER command.
 */
enum class BouncerKind { NONE, SOJU, ZNC, GENERIC }

/**
 * Machine-readable cause of a disconnect, so reasons can be translated.
 * [IrcEvent.Disconnected].
 */
enum class DisconnectCode {
    /** No diagnosis available. Consumers fall back to their text heuristics. */
    UNKNOWN,

    /** The user, or the app on the user's behalf, closed the session. */
    USER_QUIT,

    /** Server closed the stream with no diagnosis. */
    EOF,

    /** Server sent ERROR :... immediately before dropping the link. */
    SERVER_ERROR,

    /** Never reached a usable session: the connect attempt itself failed. */
    CONNECT_FAILED,

    /** Host did not resolve, or nothing was listening on the configured port. */
    HOST_UNREACHABLE,

    /** TLS failure a retry will hit again: bad cert, no shared protocol, pin failure. */
    TLS_UNRECOVERABLE,

    /** Presented certificate no longer matches the stored TOFU fingerprint. */
    TLS_FINGERPRINT_CHANGED,

    /** Certificate is valid but was not issued for the host we connected to. */
    TLS_HOSTNAME_MISMATCH,

    /** Registration did not complete within REGISTRATION_TIMEOUT_MS. */
    REGISTRATION_TIMEOUT,

    /** No inbound traffic for PING_TIMEOUT_MS: the link is dead. */
    PING_TIMEOUT,

    /** Socket read timed out after SOCKET_READ_TIMEOUT_MS (Doze/NAT killed it). */
    READ_TIMEOUT,

    /** Peer reset the connection, or the pipe broke mid-stream. */
    CONNECTION_RESET,

    /** Any other mid-stream socket or TLS failure. */
    CONNECTION_ERROR;

    /**
     * Dead-socket class that feeds flap detection. Deliberately excludes
     * [CONNECTION_ERROR]: a generic mid-stream failure is not evidence of an unstable
     * link the way a timeout or reset is, and counting it would pause auto-reconnect
     * on networks that are merely noisy.
     */
    val isDeadSocket: Boolean
        get() = this == PING_TIMEOUT || this == READ_TIMEOUT || this == CONNECTION_RESET

    /** True when we never had a working session, so flap detection must not count it. */
    val isConnectAttemptFailure: Boolean
        get() = this == CONNECT_FAILED || this == HOST_UNREACHABLE || this == TLS_UNRECOVERABLE

    /**
     * True for disconnects the UI renders with error styling rather than a plain status line.
     * [READ_TIMEOUT] stays a plain status line.
     */
    val stylesAsError: Boolean
        get() = isConnectAttemptFailure || this == CONNECTION_ERROR || this == CONNECTION_RESET
}
