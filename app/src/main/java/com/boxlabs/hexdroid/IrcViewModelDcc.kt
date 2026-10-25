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

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.viewModelScope
import com.boxlabs.hexdroid.IrcViewModel.DccChatSession
import com.boxlabs.hexdroid.IrcViewModel.LiveOutgoingSend
import com.boxlabs.hexdroid.IrcViewModel.PendingPassiveDccSend
import com.boxlabs.hexdroid.IrcViewModel.PreparedDccSend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.Socket
import kotlin.random.Random

internal fun IrcViewModel.doAcceptDccCommon(offer: DccOffer, resumeFrom: PartialTransfer?) {
    val st = _state.value

    if (dccBlockedByLanPermission(offer)) {
        append(bufKey(offer.netId.ifBlank { st.activeNetworkId ?: "" }, "*server*"),
            from = null,
            text = "*** " + appContext.getString(R.string.vm_dcc_lan_perm, offer.from),
            isHighlight = true)
        return
    }

    _state.value = st.copy(dccOffers = st.dccOffers.filterNot { it == offer })

    val resumeOffset = resumeFrom?.receivedBytes ?: 0L
    val incoming = DccTransferState.Incoming(
        offer = offer,
        received = resumeOffset,
        resumeOffset = resumeOffset,
        savedPath = resumeFrom?.savedPath,
    )
    _state.value = _state.value.copy(dccTransfers = _state.value.dccTransfers + incoming)

    // route the transfer through the network where the offer was received.
    val netId = offer.netId.takeIf { it.isNotBlank() } ?: _state.value.activeNetworkId ?: return
    val rt = runtimes[netId] ?: return
    val c = rt.client
    // A passive offer means WE must listen for the sender to connect back. A CONNECT-only
    // proxy (Tor) can't accept inbound connections, and listening on a local port while
    // proxied would expose our real IP/port to the sender outside the tunnel — defeating
    // the point. Refuse rather than leak. (Active offers, where we dial out, are fine and
    // get tunnelled below.) Use the cheap flag check here; the full proxy config (with the
    // encrypted password) is loaded off the main thread inside the launch below.
    if (isProxiedNetwork(netId) && offer.isPassive) {
        _state.value = _state.value.copy(dccTransfers = _state.value.dccTransfers.filterNot {
            it is DccTransferState.Incoming && it.offer == offer
        })
        append(bufKey(netId, "*server*"), from = null,
            text = "*** " + appContext.getString(R.string.vm_dcc_passive_proxy, offer.from),
            isHighlight = true)
        return
    }
    // Active offer through a proxy: we dial out via the tunnel, which is fine for a
    // routable peer. But a private/loopback/link-local target can't be the peer's real
    // address once we're tunnelling. connecting to it would make the proxy probe its OWN
    // local network on our behalf. Refuse rather than turn the proxy into a scanner.
    if (isProxiedNetwork(netId) && !offer.isPassive && isLocalHost(offer.ip)) {
        _state.value = _state.value.copy(dccTransfers = _state.value.dccTransfers.filterNot {
            it is DccTransferState.Incoming && it.offer == offer
        })
        append(bufKey(netId, "*server*"), from = null,
            text = "*** " + appContext.getString(R.string.vm_dcc_refuse_private, offer.from, offer.ip),
            isHighlight = true)
        return
    }
    val minP = st.settings.dccIncomingPortMin
    val maxP = st.settings.dccIncomingPortMax
    val customFolder = st.settings.dccDownloadFolderUri
    val baseName = offer.filename.substringAfterLast('/').substringAfterLast('\\')

    viewModelScope.launch {
        // Register the Job so cancelIncomingDcc() can find it. The map entry is
        // removed on completion regardless of how the coroutine ended (normal,
        // error, or cancellation) so we don't leak entries for finished transfers.
        incomingReceiveJobs[offer] = checkNotNull(coroutineContext[kotlinx.coroutines.Job]) {
            "No Job in coroutine context"
        }
        try {
            // Load the proxy (incl. encrypted password) off the main thread (this launch
            // runs on Main by default). Only the active (outbound) receive path uses it;
            // the passive branch can't be reached while proxied (guarded above).
            val proxy = withContext(Dispatchers.IO) { proxyForNetwork(netId) }
            // Resume from the bytes actually stored, which can be more than the last
            // progress report. A missing partial means a fresh download.
            val partial = resumeFrom?.let { p ->
                when (val onDisk = withContext(Dispatchers.IO) { dcc.storedSize(p.savedPath) }) {
                    null -> null
                    -1L -> p
                    else -> p.copy(receivedBytes = onDisk).takeIf { onDisk in 1 until p.size }
                }
            }
            if (resumeFrom != null && partial == null) dccPartials.remove(offer.from, baseName, offer.size)
            // If resuming, run the CTCP RESUME / ACCEPT handshake first. If the sender
            // doesn't ACCEPT within the timeout (it may not support RESUME), we silently
            // fall back to a fresh download — better UX than failing the transfer.
            val effectiveOffset: Long = if (partial != null) {
                negotiateResumeOrZero(netId, c, offer, partial)
            } else 0L
            // The sender continues from the agreed offset, so the file must end there.
            if (partial != null && effectiveOffset in 1 until partial.receivedBytes &&
                !withContext(Dispatchers.IO) { dcc.truncateStored(partial.savedPath, effectiveOffset) }
            ) {
                throw IOException("DCC RESUME: could not trim the partial file to $effectiveOffset bytes")
            }
            updateIncoming(offer) {
                it.copy(
                    received = effectiveOffset,
                    resumeOffset = effectiveOffset,
                    savedPath = if (effectiveOffset > 0L) partial?.savedPath else null,
                )
            }

            val savedPath = if (offer.isPassive) {
                // Passive/reverse DCC: we open a port and tell the sender to connect.
                dcc.receivePassive(
                    offer = offer,
                    portMin = minP,
                    portMax = maxP,
                    customFolderUri = customFolder,
                    resumeOffset = effectiveOffset,
                    resumeSavedPath = if (effectiveOffset > 0L) partial?.savedPath else null,
                    onSavedPath = { path ->
                        // Stamp the path on the Incoming state as soon as the file is opened
                        // so a mid-transfer error can still leave a useful partial pointer.
                        updateIncoming(offer) { it.copy(savedPath = path) }
                    },
                    onListening = { addrField, port, size, token ->
                        val name = quoteDccFilenameIfNeeded(offer.filename)
                        val tokenStr = if (offer.turbo) "${token}T" else token.toString()
                        val verb = if (offer.secure) "SSEND" else "SEND"
                        val payload = "DCC $verb $name $addrField $port $size $tokenStr"
                        c.ctcp(offer.from, payload)
                        val resumeNote = if (effectiveOffset > 0L) " (resuming from ${effectiveOffset} bytes)" else ""
                        append(bufKey(netId, "*server*"), from = null, text = "*** " + appContext.getString(R.string.vm_dcc_accepted_passive, offer.filename, port, resumeNote), doNotify = false)
                    }
                ) { got, _ ->
                    updateIncoming(offer) { it.copy(received = got) }
                }
            } else {
                dcc.receive(
                    offer = offer,
                    customFolderUri = customFolder,
                    resumeOffset = effectiveOffset,
                    resumeSavedPath = if (effectiveOffset > 0L) partial?.savedPath else null,
                    proxy = proxy,
                    onSavedPath = { path -> updateIncoming(offer) { it.copy(savedPath = path) } },
                ) { got, _ ->
                    updateIncoming(offer) { it.copy(received = got) }
                }
            }
            updateIncoming(offer) { it.copy(done = true, savedPath = savedPath, endTimeMs = System.currentTimeMillis()) }
            scriptEvent(
                "FILERCVD", offer.netId, "", offer.from, text = offer.filename,
                fields = mapOf("filename" to offer.filename, "filesize" to offer.size.toString(), "path" to savedPath),
            )
            // Clear the partial record on success (don't delete the file — it IS the completed download).
            dccPartials.remove(offer.from, baseName, offer.size)
            val displayPath = if (savedPath.startsWith("content://")) "Downloads" else savedPath.substringAfterLast('/')
            notifier.notifyFileDone(netId, offer.filename, displayPath)
        } catch (t: Throwable) {
            // A user cancel manifests in two ways depending on timing: as a
            // CancellationException at a suspension point, OR as an IOException
            // bubbling up from the socket close that DccManager's invokeOnCompletion
            // triggered. Either way, !isActive is true once cancel() has been called,
            // which is the reliable signal.
            val cancelled = !isActive || t is kotlinx.coroutines.CancellationException
            val msg = if (cancelled) "Cancelled" else (t.message ?: "error")
            val cur = _state.value.dccTransfers.firstOrNull {
                it is DccTransferState.Incoming && it.offer == offer
            } as? DccTransferState.Incoming
            updateIncoming(offer) {
                if (it.done || it.error != null) it
                else it.copy(error = msg, endTimeMs = System.currentTimeMillis())
            }
            // Record the partial so a future offer can be RESUMEd, sized by what is on disk.
            val partialPath = cur?.savedPath
            val onDisk = if (partialPath.isNullOrBlank()) null
                else withContext(NonCancellable + Dispatchers.IO) { dcc.storedSize(partialPath) }
            val partialBytes = if (onDisk == -1L) cur?.received ?: 0L else onDisk ?: 0L
            if (!partialPath.isNullOrBlank() && offer.size > 0L && partialBytes in 1 until offer.size) {
                dccPartials.put(
                    PartialTransfer(
                        from = offer.from,
                        filename = baseName,
                        size = offer.size,
                        savedPath = partialPath,
                        receivedBytes = partialBytes,
                        secure = offer.secure,
                        turbo = offer.turbo,
                    )
                )
            }
            if (t is kotlinx.coroutines.CancellationException) throw t
        } finally {
            incomingReceiveJobs.remove(offer)
        }
    }
}

/**
 * Send DCC RESUME and wait for the matching DCC ACCEPT. Returns the agreed offset, or 0 when
 * the peer doesn't answer in time, falling back to a fresh transfer (many clients don't support
 * RESUME).
 */
internal suspend fun IrcViewModel.negotiateResumeOrZero(
    netId: String,
    c: IrcClient,
    offer: DccOffer,
    partial: PartialTransfer,
    timeoutMs: Long = 10_000L,
): Long {
    val baseName = offer.filename.substringAfterLast('/').substringAfterLast('\\')
    val key = "${offer.from.lowercase()}|$baseName|${offer.size}"
    val def = CompletableDeferred<DccAccept>()
    pendingResumeRequests[key] = def

    val name = quoteDccFilenameIfNeeded(offer.filename)
    // Active offer: <port> is the sender's port from the offer.
    // Passive offer: <port> is 0 and <token> is required (echoes the offer's token).
    val portField = if (offer.isPassive) 0 else offer.port
    val tokenField = if (offer.isPassive) " ${offer.token}" else ""
    val resumePayload = "DCC RESUME $name $portField ${partial.receivedBytes}$tokenField"
    c.ctcp(offer.from, resumePayload)
    append(bufKey(netId, "*server*"), from = null,
        text = "*** " + appContext.getString(R.string.vm_dcc_resume_requested, offer.filename, partial.receivedBytes),
        doNotify = false)

    return try {
        val accept = withTimeout(timeoutMs) { def.await() }
        // Defensive: if the peer ACCEPTed a different position than we asked for, honour
        // their position (clamped to the partial's actual size). Some clients align to
        // block boundaries.
        val agreed = accept.position.coerceIn(0L, partial.receivedBytes)
        if (agreed != partial.receivedBytes) {
            append(bufKey(netId, "*server*"), from = null,
                text = "*** " + appContext.getString(R.string.vm_dcc_resume_agreed, agreed, partial.receivedBytes),
                doNotify = false)
        }
        agreed
    } catch (_: TimeoutCancellationException) {
        append(bufKey(netId, "*server*"), from = null,
            text = "*** " + appContext.getString(R.string.vm_dcc_resume_none, offer.from),
            doNotify = false)
        0L
    } finally {
        pendingResumeRequests.remove(key)
    }
}

internal fun IrcViewModel.isDccChatBufferName(name: String): Boolean = name.startsWith("DCCCHAT:")

internal fun IrcViewModel.dccChatBufferKey(netId: String, peerNick: String): String = bufKey(netId, "DCCCHAT:$peerNick")

/** Key of [netId]'s DCC chat buffer for [nick], matching an open one case-insensitively. */
internal fun IrcViewModel.findDccChatKey(netId: String, nick: String): String {
    val exact = dccChatBufferKey(netId, nick)
    return sequenceOf(dccChatSessions.keys, dccChatPending.keys, _state.value.buffers.keys)
        .flatten()
        .firstOrNull { it.equals(exact, ignoreCase = true) } ?: exact
}

/** Port text for DCC chat messages, marked when the chat is SDCC. */
internal fun IrcViewModel.dccChatPortLabel(port: Int, secure: Boolean): String =
    if (secure) "$port, SDCC/TLS" else port.toString()

internal fun IrcViewModel.closeDccChatSession(bufferKey: String, reason: String? = null) {
    val ses = dccChatSessions.remove(bufferKey) ?: return
    runCatching { ses.readJob.cancel() }
    viewModelScope.launch(Dispatchers.IO) { runCatching { ses.socket.close() } }
    val r = reason?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
    append(bufferKey, from = null, text = "*** " + appContext.getString(R.string.vm_dcc_chat_disconnected, r), doNotify = false)
}

internal fun IrcViewModel.startDccChatSession(netId: String, peer: String, bufferKey: String, socket: Socket) {
    // Replace any existing session for this buffer.
    closeDccChatSession(bufferKey, reason = "replaced")

    runCatching {
        socket.tcpNoDelay = true
        socket.keepAlive = true
    }

    val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

    val job = viewModelScope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
        try {
            val reader = EncodingLineReader(java.io.BufferedInputStream(socket.getInputStream()), "auto")
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) continue
                val isAction = line.length > 9 && line.startsWith("\u0001ACTION ") && line.endsWith("\u0001")
                val text = if (isAction) {
                    line.removePrefix("\u0001ACTION ").removeSuffix("\u0001")
                } else line
                withContext(Dispatchers.Main) {
                    append(bufferKey, from = peer, text = text, isAction = isAction, isPrivate = true)
                }
            }
        } catch (t: Throwable) {
            if (isActive) {
                withContext(Dispatchers.Main) {
                    append(bufferKey, from = null, text = "*** " + appContext.getString(R.string.vm_dcc_chat_failed, t.message ?: t::class.java.simpleName), isHighlight = true)
                }
            }
        } finally {
            runCatching { socket.close() }
            withContext(NonCancellable + Dispatchers.Main) {
                if (dccChatSessions[bufferKey]?.socket === socket) {
                    dccChatSessions.remove(bufferKey)
                    append(bufferKey, from = null, text = "*** " + appContext.getString(R.string.vm_dcc_chat_closed), doNotify = false)
                }
            }
        }
    }

    dccChatSessions[bufferKey] = DccChatSession(netId, peer, bufferKey, socket, writer, job)
    append(bufferKey, from = null, text = "*** " + appContext.getString(R.string.vm_dcc_chat_connected, peer), doNotify = false)
    job.start()
}

/** Show the protocol and certificate fingerprint of an SDCC chat; no-op for plain sockets. */
internal fun IrcViewModel.appendDccTlsInfo(bufferKey: String, socket: Socket, server: Boolean) {
    val ssl = socket as? javax.net.ssl.SSLSocket ?: return
    val info = runCatching { DccTls.describe(ssl, server) }.getOrNull() ?: return
    val res = if (server) R.string.vm_dcc_chat_tls_local else R.string.vm_dcc_chat_tls_peer
    append(bufferKey, from = null, text = "*** " + appContext.getString(res, info.protocol, info.serverFingerprint ?: "?"), doNotify = false)
}

/** Run [block] as [bufferKey]'s pending DCC chat connect, replacing any earlier one. */
internal fun IrcViewModel.launchDccChatConnect(bufferKey: String, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
    val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
        try {
            block()
        } finally {
            coroutineContext[Job]?.let { dccChatPending.remove(bufferKey, it) }
        }
    }
    dccChatPending.put(bufferKey, job)?.cancel()
    job.start()
}

internal fun IrcViewModel.sendDccChatLine(bufferKey: String, line: String, isAction: Boolean) {
    val ses = dccChatSessions[bufferKey]
    if (ses == null) {
        append(bufferKey, from = null, text = "*** " + appContext.getString(R.string.vm_dcc_chat_not_connected), isHighlight = true)
        return
    }

    val text = line.replace("\r", "").replace('\n', ' ')
    val payload = if (isAction) "\u0001ACTION $text\u0001" else text

    // Writing to a socket on the main thread can throw (StrictMode / NetworkOnMainThreadException)
    // and will also make typing feel laggy if the peer/network is slow. Always write on IO.
    viewModelScope.launch(Dispatchers.IO) {
        try {
            synchronized(ses.writer) {
                ses.writer.write(payload)
                ses.writer.write("\r\n")
                ses.writer.flush()
            }
        } catch (t: Throwable) {
            withContext(Dispatchers.Main.immediate) {
                append(
                    bufferKey,
                    from = null,
                    text = "*** " + appContext.getString(R.string.vm_dcc_chat_send_failed, t.message ?: t::class.java.simpleName),
                    isHighlight = true
                )
                if (dccChatSessions[bufferKey] === ses) closeDccChatSession(bufferKey, reason = t.message)
            }
            return@launch
        }

        withContext(Dispatchers.Main.immediate) {
            val myNick = _state.value.connections[ses.netId]?.myNick ?: _state.value.myNick
            append(bufferKey, from = myNick, text = text, isAction = isAction)
        }
    }
}

/**
 * Offer a DCC chat to [targetNick] on [netIdArg] (default: the active network). [secure] forces
 * SCHAT on or off; null follows the SDCC setting.
 */
fun IrcViewModel.startDccChatFlow(targetNick: String, secure: Boolean? = null, netIdArg: String? = null) {
    val st = _state.value
    val netId = netIdArg ?: st.activeNetworkId ?: return
    val rt = runtimes[netId] ?: return
    val c = rt.client
    val peer = targetNick.trim().trimStart('~', '&', '@', '%', '+')
    if (peer.isBlank()) return

    if (!st.settings.dccEnabled) {
        append(bufKey(netId, "*server*"), from = "DCC", text = appContext.getString(R.string.vm_dcc_disabled), isHighlight = true)
        return
    }

    // Active DCC CHAT requires us to listen for the peer to connect in. A CONNECT-only
    // proxy can't do that, and listening locally while proxied would expose our real
    // address. There's no passive/reverse CHAT equivalent that's widely supported, so
    // refuse outright while a proxy is active.
    if (isProxiedNetwork(netId)) {
        append(bufKey(netId, "*server*"), from = "DCC",
            text = appContext.getString(R.string.vm_dcc_chat_proxy),
            isHighlight = true)
        return
    }

    val key = findDccChatKey(netId, peer)
    openBuffer(key)

    val minP = st.settings.dccIncomingPortMin
    val maxP = st.settings.dccIncomingPortMax
    val useTls = secure ?: st.settings.dccSecure

    launchDccChatConnect(key) {
        try {
            val chatVerb = if (useTls) "SCHAT" else "CHAT"
            val secureLabel = if (useTls) " (SDCC/TLS)" else ""
            append(key, from = null, text = "*** " + appContext.getString(R.string.vm_dcc_chat_offering, secureLabel, peer), doNotify = false)
            val socket = dcc.startChat(
                portMin = minP,
                portMax = maxP,
                secure = useTls,
                onClient = { addrField, port ->
                    val payload = "DCC $chatVerb chat $addrField $port"
                    c.ctcp(peer, payload)
                    append(bufKey(netId, "*server*"), from = null, text = "*** " + appContext.getString(R.string.vm_dcc_chat_sent, secureLabel, peer, port), doNotify = false)
                }
            )
            startDccChatSession(netId, peer, key, socket)
            appendDccTlsInfo(key, socket, server = true)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            if (isActive) {
                append(key, from = null, text = "*** " + appContext.getString(R.string.vm_dcc_chat_offer_failed, t.message ?: t::class.java.simpleName), isHighlight = true)
            }
        }
    }
}

/** Update the incoming transfer for [offer]. Atomic, since transfers report from IO threads. */
internal fun IrcViewModel.updateIncoming(offer: DccOffer, f: (DccTransferState.Incoming) -> DccTransferState.Incoming) {
    _state.update { st ->
        st.copy(dccTransfers = st.dccTransfers.map {
            if (it is DccTransferState.Incoming && it.offer == offer) f(it) else it
        })
    }
}

internal fun IrcViewModel.quoteDccFilenameIfNeeded(nameRaw: String): String {
    val name = nameRaw.replace('"', '_').trim()
    return if (name.any { it.isWhitespace() }) "\"$name\"" else name
}

internal fun IrcViewModel.sendDccFile(uri: android.net.Uri, targetNick: String, netId: String, statusKeyArg: String? = null) {
    val rt = runtimes[netId] ?: return
    val c = rt.client
    if (targetNick.isBlank()) return
    val target = targetNick.trimStart('~', '&', '@', '%', '+')

    if (!_state.value.settings.dccEnabled) {
        append(bufKey(netId, "*server*"), from = "DCC", text = appContext.getString(R.string.vm_dcc_disabled), isHighlight = true)
        return
    }

    val job = viewModelScope.launch {
        var offerNameForState: String? = null
        var stagedFile: File? = null
        // Buffer to show DCC status messages in: the one the send was typed in, else the
        // target's query buffer if open, otherwise the server buffer.
        val statusKey = statusKeyArg?.takeIf { _state.value.buffers.containsKey(it) } ?: run {
            val targetKey = resolveBufferKey(netId, target)
            if (_state.value.buffers.containsKey(targetKey)) targetKey
            else bufKey(netId, "*server*")
        }
        try {
            val prepared = prepareDccSendFile(uri)
            val file = prepared.file
            stagedFile = file
            val offerName = prepared.offerName
            offerNameForState = offerName
            val jobKey = "$target/$offerName"
            // coroutineContext[Job] is always non-null inside a launch block.
            outgoingSendJobs[jobKey] = checkNotNull(coroutineContext[kotlinx.coroutines.Job]) { "No Job in coroutine context" }
            val st = _state.value
            val minP = st.settings.dccIncomingPortMin
            val maxP = st.settings.dccIncomingPortMax
            val mode = st.settings.dccSendMode

            val offerNamePayload = quoteDccFilenameIfNeeded(offerName)
            val fileSize = runCatching { file.length() }.getOrDefault(0L)

            // This network's proxy, captured once before the send helpers so the passive
            // connect can tunnel through it. Loaded off the main thread (this launch runs
            // on Main) since proxyForNetwork reads the encrypted password. Passed
            // explicitly to dcc.sendFileConnect rather than via shared state, so concurrent
            // transfers can't race over it.
            val sendProxy = withContext(Dispatchers.IO) { proxyForNetwork(netId) }

            val outgoing = DccTransferState.Outgoing(target = target, filename = offerName, fileSize = fileSize)
            _state.update { it.copy(dccTransfers = it.dccTransfers + outgoing) }

            fun updateOutgoing(sent: Long) {
                _state.update { st2 ->
                    st2.copy(dccTransfers = st2.dccTransfers.map {
                        if (it is DccTransferState.Outgoing && it.target == target && it.filename == offerName) it.copy(bytesSent = sent) else it
                    })
                }
            }

            suspend fun doActiveSend() {
                val secure = st.settings.dccSecure
                val verb = if (secure) "SSEND" else "SEND"
                // We need to know our listening port before we can register the live-send
                // entry that incoming DCC RESUME requests are matched against. Bind happens
                // inside dcc.sendFile; the port is delivered to us in `onClient`. To bridge
                // that we keep a placeholder key and rewrite it once the port is known.
                val baseName = offerName.substringAfterLast('/').substringAfterLast('\\')
                var liveKey: String? = null
                val liveDeferred = CompletableDeferred<Long>()
                val absolutePath = file.absolutePath
                dcc.sendFile(
                    file = file,
                    portMin = minP,
                    portMax = maxP,
                    secure = secure,
                    onClient = { addrField, port, size ->
                        val payload = "DCC $verb $offerNamePayload $addrField $port $size"
                        // Register BEFORE sending the CTCP so a quick RESUME reply finds us.
                        val key = "${target.lowercase()}|$baseName|$size"
                        liveKey = key
                        liveOutgoingSends[key] = LiveOutgoingSend(
                            target = target,
                            filename = offerName,
                            absolutePath = absolutePath,
                            size = size,
                            port = port,
                            token = null,
                            resumeRequest = liveDeferred,
                        )
                        c.ctcp(target, payload)
                        val secureLabel = if (secure) " (SDCC/TLS)" else ""
                        append(statusKey, from = null, text = "*** " + appContext.getString(R.string.vm_dcc_offering_active, offerName, target, secureLabel, port), doNotify = false)
                    },
                    awaitStartOffset = {
                        // Short window for a late RESUME; if the peer didn't ask for resume,
                        // proceed from byte 0. The receiver-side spec says RESUME, if used,
                        // is sent before the data connection, but a tiny race grace is cheap.
                        // withTimeoutOrNull keeps us off the experimental getCompleted() API.
                        val offset = withTimeoutOrNull(500L) { liveDeferred.await() } ?: 0L
                        if (offset > 0L) {
                            _state.update { st4 ->
                                st4.copy(dccTransfers = st4.dccTransfers.map {
                                    if (it is DccTransferState.Outgoing && it.target == target && it.filename == offerName)
                                        it.copy(resumeOffset = offset, bytesSent = offset)
                                    else it
                                })
                            }
                        }
                        offset
                    },
                    onProgress = { sent, _ -> updateOutgoing(sent) }
                )
                liveKey?.let { liveOutgoingSends.remove(it) }
            }

            suspend fun doPassiveSend(timeoutMs: Long = 120_000L) {
                val secure = st.settings.dccSecure
                val verb = if (secure) "SSEND" else "SEND"
                val token = Random.nextLong(1L, 0x7FFFFFFFL)
                val def = CompletableDeferred<DccOffer>()
                val baseName = offerName.substringAfterLast('/').substringAfterLast('\\')
                pendingPassiveDccSends[token] = PendingPassiveDccSend(target, baseName, fileSize, def)
                val liveKey = "${target.lowercase()}|$baseName|$fileSize"
                val liveDeferred = CompletableDeferred<Long>()
                liveOutgoingSends[liveKey] = LiveOutgoingSend(
                    target = target,
                    filename = offerName,
                    absolutePath = file.absolutePath,
                    size = fileSize,
                    port = 0,
                    token = token,
                    resumeRequest = liveDeferred,
                )
                try {
                    // In passive DCC the advertised IP is unused for the connection (the
                    // receiver sends back its own address and we dial out). When proxied
                    // we deliberately advertise 0 rather than our real LAN IP: emitting
                    // the local address in the CTCP would leak network metadata about a
                    // user who turned the proxy on precisely for anonymity. Many clients
                    // already tolerate 0 here since the field is informational for passive.
                    val ipField = if (sendProxy.enabled) "0" else dcc.dccAddressField()
                    val payload = "DCC $verb $offerNamePayload $ipField 0 $fileSize $token"
                    c.ctcp(target, payload)
                    val secureLabel = if (secure) " (SDCC/TLS)" else ""
                    append(statusKey, from = null, text = "*** " + appContext.getString(R.string.vm_dcc_offering_passive, offerName, target, secureLabel), doNotify = false)

                    val reply = withTimeout(timeoutMs) { def.await() }
                    if (reply.port <= 0) throw IOException(appContext.getString(R.string.vm_dcc_invalid_reply))
                    // Resume race: per the DCC RESUME protocol the receiver sends RESUME
                    // *before* its DCC SEND reply opens the port, so by the time we get
                    // `reply` here the resume offset (if any) is already settled and the
                    // await() returns immediately. The small timeout is a generous safety
                    // margin for thread-scheduling races and avoids the experimental
                    // getCompleted() API.
                    val startOffset = withTimeoutOrNull(100L) { liveDeferred.await() } ?: 0L
                    if (startOffset > 0L) {
                        _state.update { st4 ->
                            st4.copy(dccTransfers = st4.dccTransfers.map {
                                if (it is DccTransferState.Outgoing && it.target == target && it.filename == offerName)
                                    it.copy(resumeOffset = startOffset, bytesSent = startOffset)
                                else it
                            })
                        }
                        append(statusKey, from = null, text = "*** " + appContext.getString(R.string.vm_dcc_accepted_resume, target, startOffset), doNotify = false)
                    } else {
                        append(statusKey, from = null, text = "*** " + appContext.getString(R.string.vm_dcc_accepted_connecting, target), doNotify = false)
                    }

                    dcc.sendFileConnect(
                        file = file,
                        host = reply.ip,
                        port = reply.port,
                        secure = secure,
                        startOffset = startOffset,
                        proxy = sendProxy,
                        onProgress = { sent, _ -> updateOutgoing(sent) }
                    )
                } finally {
                    pendingPassiveDccSends.remove(token)
                    liveOutgoingSends.remove(liveKey)
                }
            }

            // With a proxy active we can't listen for an inbound connection, so an ACTIVE
            // send (where the peer dials us) is impossible. Force PASSIVE — we dial the
            // peer outbound through the proxy instead. This mirrors how mIRC behind a
            // SOCKS firewall falls back to reverse/passive DCC. If the user explicitly
            // pinned ACTIVE, tell them why we're overriding.
            val effectiveMode = if (sendProxy.enabled && mode != DccSendMode.PASSIVE) {
                if (mode == DccSendMode.ACTIVE) {
                    append(statusKey, from = null,
                        text = "*** " + appContext.getString(R.string.vm_dcc_proxy_passive),
                        doNotify = false)
                }
                DccSendMode.PASSIVE
            } else mode

            when (effectiveMode) {
                DccSendMode.ACTIVE -> doActiveSend()
                DccSendMode.PASSIVE -> doPassiveSend()
                DccSendMode.AUTO -> {
                    // AUTO tries passive first. If the peer doesn't respond within the
                    // timeout we give up rather than sending a second unsolicited CTCP -
                    // firing two DCC SEND offers for the same file confuses clients and
                    // can result in duplicate transfers. The user can retry manually.
                    try {
                        doPassiveSend()
                    } catch (t: TimeoutCancellationException) {
                        // Re-throw as a plain IOException so the outer catch marks the
                        // transfer as an error rather than falling through to the success path.
                        throw IOException(appContext.getString(R.string.vm_dcc_timeout, target))
                    }
                }
            }

            val st3 = _state.value
            _state.value = st3.copy(dccTransfers = st3.dccTransfers.map {
                if (it is DccTransferState.Outgoing && it.target == target && it.filename == offerName) it.copy(done = true, endTimeMs = System.currentTimeMillis()) else it
            })
            outgoingSendJobs.remove(jobKey)
            append(statusKey, from = null, text = "*** " + appContext.getString(R.string.vm_dcc_send_complete, offerName, target), doNotify = false)
            scriptEvent("FILESENT", netId, "", target, text = offerName, fields = mapOf("filename" to offerName))

        } catch (t: Throwable) {
            // See incoming catch above for why !isActive is the reliable cancel signal:
            // a user cancel can manifest either as CancellationException at a suspension
            // point or as an IOException bubbling up from the closed socket. Surface
            // "Cancelled" as the error string (not null) so the Transfers screen renders
            // it as a stopped transfer rather than a successful one.
            val cancelled = !isActive || t is kotlinx.coroutines.CancellationException
            val msg = if (cancelled) "Cancelled" else (t.message ?: t::class.java.simpleName).trim()
            val stErr = _state.value
            offerNameForState?.let { fn ->
                outgoingSendJobs.remove("$target/$fn")
                _state.value = stErr.copy(dccTransfers = stErr.dccTransfers.map {
                    if (it is DccTransferState.Outgoing && it.target == target && it.filename == fn)
                        it.copy(done = true, error = msg, endTimeMs = System.currentTimeMillis())
                    else it
                })
                if (!cancelled) append(statusKey, from = "DCC", text = "*** " + appContext.getString(R.string.vm_dcc_send_failed, msg), isHighlight = true)
                else append(statusKey, from = null, text = "*** " + appContext.getString(R.string.vm_dcc_send_cancelled, fn), doNotify = false)
            } ?: run {
                _state.value = stErr.copy(dccTransfers = stErr.dccTransfers + DccTransferState.Outgoing(target = target, filename = "(unknown)", done = true, error = msg, endTimeMs = System.currentTimeMillis()))
                if (!cancelled) append(statusKey, from = "DCC", text = "*** " + appContext.getString(R.string.vm_dcc_send_failed, msg), isHighlight = true)
            }
            if (t is kotlinx.coroutines.CancellationException) throw t   // re-throw so coroutine completes correctly
        } finally {
            // The staged copy is only needed while this send runs.
            stagedFile?.let { f -> withContext(NonCancellable + Dispatchers.IO) { runCatching { f.delete() } } }
        }
    }

}

internal fun IrcViewModel.queryDisplayName(uri: android.net.Uri): String? {
    return try {
        val proj = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            "_display_name",
            "display_name"
        )
        appContext.contentResolver.query(uri, proj, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            for (col in proj) {
                val idx = c.getColumnIndex(col)
                if (idx >= 0) {
                    val v = runCatching { c.getString(idx) }.getOrNull()
                    if (!v.isNullOrBlank()) return@use v
                }
            }
            null
        }
    } catch (_: Throwable) {
        null
    }
}

internal suspend fun IrcViewModel.prepareDccSendFile(uri: android.net.Uri): PreparedDccSend = withContext(Dispatchers.IO) {
    // Try hard to preserve a meaningful filename for the DCC offer.
    val raw = queryDisplayName(uri)
        ?: runCatching { java.net.URLDecoder.decode(uri.lastPathSegment ?: "", "UTF-8") }.getOrNull()
        ?: ("dcc_send_" + System.currentTimeMillis())

    // Document IDs often look like "primary:Download/foo.txt".
    val cleaned = raw
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .substringAfterLast(':')

    val offerName = cleaned
        .replace(Regex("[^A-Za-z0-9._ -]"), "_")
        .trim()
        .ifBlank { "dcc_send_" + System.currentTimeMillis() }
        .replace(' ', '_') // avoid spaces in CTCP DCC payload

    // Own subdirectory so the FileProvider root can name it without exposing cacheDir.
    val stageDir = File(appContext.cacheDir, "dcc_out").apply { mkdirs() }
    // Copies left behind by sends that never finished (process killed mid-send).
    val staleBefore = System.currentTimeMillis() - 24L * 60 * 60 * 1000
    stageDir.listFiles()?.forEach { if (it.isFile && it.lastModified() < staleBefore) it.delete() }
    val out = run {
        val candidate = File(stageDir, offerName)
        if (!candidate.exists()) candidate else {
            val dot = offerName.lastIndexOf('.')
            val stem = if (dot > 0) offerName.take(dot) else offerName
            val ext = if (dot > 0) offerName.drop(dot) else ""
            File(stageDir, "${stem}_${System.currentTimeMillis()}$ext")
        }
    }

    val inp = appContext.contentResolver.openInputStream(uri) ?: throw IOException(appContext.getString(R.string.vm_file_open_failed))
    inp.use { input ->
        out.outputStream().use { fos -> input.copyTo(fos) }
    }

    PreparedDccSend(file = out, offerName = out.name)
}
