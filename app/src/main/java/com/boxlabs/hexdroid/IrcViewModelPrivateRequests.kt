/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import androidx.lifecycle.viewModelScope
import com.boxlabs.hexdroid.data.IgnoreType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update

internal const val PRIVATE_REQUESTS_BUFFER = "*personal-private-requests*"
internal fun IrcViewModel.refreshPrivateRequests() {
    viewModelScope.launch {
        val pending = withContext(privateRequestLane) { privateRequests.pending() }
        _state.update { it.copy(privateRequests = pending) }
    }
}
internal fun IrcViewModel.gatePrivateMessage(netId: String, ev: IrcEvent.ChatMessage, shown: String, targetKey: String): Boolean {
    val s = _state.value.settings
    if (!ev.isPrivate || isMyNick(netId, ev.from) || !isReplyNick(ev.from)) return false
    val id = privateRequestId(netId, casefoldText(netId, ev.from))
    privateRequestNetworkKeys.getOrPut(netId) { mutableSetOf() }.add(id)
    if (id in sessionPrivateAccepted) return false
    if (!ev.msgId.isNullOrBlank() && _state.value.buffers[targetKey]?.log?.seenIds?.contains(ev.msgId) == true) return false
    if (!scriptHoldsPrivateRequest(netId, ev.from, shown, ev.isHistory, action = ev.isAction)) return false
    viewModelScope.launch {
        try {
            val added = withContext(privateRequestLane) {
                if (s.privateRememberAccepted && privateRequests.authorized(id)) null
                else privateRequests.enqueue(id, netId, ev.from, PendingPrivateMessage(shown, ev.timeMs ?: System.currentTimeMillis(), ev.isAction, ev.msgId, ev.encryption), false)
            }
            if (added == null) {
                sessionPrivateAccepted.add(id)
                append(targetKey, from = ev.from, text = shown, isAction = ev.isAction, isPrivate = true,
                    timeMs = ev.timeMs, msgId = ev.msgId, isHistory = ev.isHistory, encryption = ev.encryption,
                    doNotify = !ev.isHistory || s.ircHistoryTriggersNotifications)
                return@launch
            }
            val pending = withContext(privateRequestLane) { privateRequests.pending() }
            _state.update { it.copy(privateRequests = pending) }
            if (added && !ev.isHistory) {
                if (s.notificationsEnabled && s.notifyOnPrivateMessages && !isNotifyIgnoredSender(netId, ev.from)) {
                    runCatching { notifier.notifySimple(netId, PRIVATE_REQUESTS_BUFFER, "Solicitud de privado: ${ev.from}", "${_state.value.networks.firstOrNull { it.id == netId }?.name ?: netId} · Ver solicitudes pendientes") }
                }
                if (s.privateAutoMessages && s.privateWaitText.isNotBlank() && withContext(privateRequestLane) { privateRequests.claimWait(id) }) {
                    sendPrivateRequestNotice(netId, ev.from, s.privateWaitText)
                }
            }
        } catch (e: Exception) {
            _state.update { it.copy(privateRequestMessage = "No se pudo guardar la solicitud: ${e.message}. El remitente puede reintentar.") }
        }
    }
    return true
}

/** NOTICE avoids autoresponder loops and never opens a query buffer. */
internal suspend fun IrcViewModel.sendPrivateRequestNotice(netId: String, nick: String, configured: String) {
    if (!isReplyNick(nick) || isIgnoredSender(netId, nick, IgnoreType.PRIVATE)) return
    val text = safeAutomaticText(configured)
    if (text.isEmpty()) return
    val rt = runtimes[netId] ?: error("Red desconectada; no se envió el aviso")
    check(_state.value.connections[netId]?.connected == true) { "Red desconectada; no se envió el aviso" }
    val line = "NOTICE $nick :$text"
    require(line.toByteArray(Charsets.UTF_8).size + 2 <= (rt.support.linelen ?: 512) - 64) { "El mensaje automático excede el límite IRC; acortalo en Ajustes" }
    rt.client.sendNotice(nick, text, suppressEcho = true)
}

fun IrcViewModel.resolvePrivateRequest(request: PrivateRequest, decision: String, reason: String = "") {
    if (decision !in setOf("ACCEPTED", "REJECTED", "IGNORED") || request.id in resolvingPrivateRequests) return
    resolvingPrivateRequests.add(request.id)
    viewModelScope.launch {
        try {
            val s = _state.value.settings
            if (decision != "IGNORED") check(!isIgnoredSender(request.network, request.nick, IgnoreType.PRIVATE)) { "Este usuario está ignorado; solo se puede descartar su solicitud" }
            if (request.serverBlocked && decision == "ACCEPTED") {
                val rt = runtimes[request.network] ?: error("Conectá la red antes de solicitar ACCEPT")
                val ownNick = _state.value.connections[request.network]?.myNick.orEmpty()
                rt.client.sendRaw(callerIdCommand(rt.support.callerIdMode, rt.client.isConnectedNow(), ownNick, "accept", request.nick))
            }
            if (decision == "ACCEPTED") {
                val messages = withContext(privateRequestLane) { privateRequests.messages(request.id) }
                val key = resolveBufferKey(request.network, request.nick)
                if (s.privateAutoMessages && s.privateAcceptText.isNotBlank()) sendPrivateRequestNotice(request.network, request.nick, s.privateAcceptText)
                privateRequestNetworkKeys.getOrPut(request.network) { mutableSetOf() }.add(request.id)
                sessionPrivateAccepted.add(request.id)
                messages.forEach { m -> append(key, from = request.nick, text = m.text, isAction = m.action,
                    isPrivate = true, timeMs = m.timeMs, msgId = m.msgId, encryption = m.encryption, doNotify = false) }
                ensureBuffer(key)
                openBuffer(key)
            } else if (decision == "REJECTED" && s.privateAutoMessages) {
                // This is a local rejection notice, never a fabricated server-side block.
                sendPrivateRequestNotice(request.network, request.nick, rejectionText(s.privateRejectText, reason))
            }
            withContext(privateRequestLane) { privateRequests.finish(request.id, decision, decision == "ACCEPTED" && s.privateRememberAccepted) }
            val pending = withContext(privateRequestLane) { privateRequests.pending() }
            _state.update { it.copy(privateRequests = pending, privateRequestMessage = when {
                request.serverBlocked && decision == "ACCEPTED" -> "Aceptado localmente. Se envió ACCEPT; revisá la respuesta real del servidor en Status. El mensaje bloqueado no fue recibido: el remitente debe reenviarlo."
                decision == "IGNORED" -> "Solicitud descartada sin responder ni bloquear al usuario"
                else -> "Solicitud procesada"
            }) }
        } catch (e: Exception) {
            _state.update { it.copy(privateRequestMessage = "No se completó la acción: ${e.message}. La solicitud permanece pendiente.") }
        } finally { resolvingPrivateRequests.remove(request.id) }
    }
}
fun IrcViewModel.revokePrivateAuthorizations() {
    sessionPrivateAccepted.clear()
    viewModelScope.launch { withContext(privateRequestLane) { privateRequests.revokeAll() }; _state.update { it.copy(privateRequestMessage = "Autorizaciones locales eliminadas; la lista ACCEPT del servidor se administra por separado en Status") } }
}
internal fun IrcViewModel.handleCallerIdNumeric(netId: String, ev: IrcEvent.ServerText): Boolean {
    if (ev.code != "718") return false
    // charybdis/solanum: 718 <me> <source> <user@host> :is messaging you ...
    val nick = ev.serverParams.getOrNull(1)?.takeIf(::isReplyNick) ?: return false
    if (isIgnoredSender(netId, nick, IgnoreType.PRIVATE)) return false
    if (!hasPrivateRequestPolicy()) {
        // Keep the real +g notice visible after withdrawing the optional request addon.
        // It must not enqueue a request, send an auto-reply or create a private buffer.
        val notice = "* <$nick> Intento de PV bloqueado por +g. El servidor no entregó el contenido del mensaje."
        showNativePrivateAttempt(netId, nick, notice, ev.text)
        return true
    }
    if (!scriptHoldsPrivateRequest(netId, nick, blocked = true)) return false
    val id = privateRequestId(netId, casefoldText(netId, nick))
    // Capture the visible conversation at receipt; a later screen switch must not reroute it.
    val status = bufKey(netId, "*server*")
    val active = _state.value.selectedBuffer.takeIf {
        splitKey(it).first == netId && it in _state.value.buffers
    }
    viewModelScope.launch {
        try {
            val first = withContext(privateRequestLane) {
                val alreadyPending = privateRequests.pending().any { it.id == id && it.serverBlocked }
                privateRequests.enqueue(id, netId, nick, null, true)
                !alreadyPending
            }
            if (first) {
                val notice = "* <$nick> Solicitud de PV bloqueada por +g. El servidor no entregó el contenido del mensaje. Revisá Privados pendientes."
                for (key in setOfNotNull(status, active)) {
                    append(key, from = null, text = if (key == status) "$notice ${ev.text}" else notice,
                        isHighlightedNotice = key == active, doNotify = false)
                }
            }
            refreshPrivateRequests()
            _state.update { it.copy(privateRequestMessage = "El servidor bloqueó un intento de $nick mediante +g. No recibimos el contenido del mensaje.") }
            val s = _state.value.settings
            if (first && s.notificationsEnabled && s.notifyOnPrivateMessages && !isNotifyIgnoredSender(netId, nick) && withContext(privateRequestLane) { privateRequests.claimWait(id) })
                runCatching { notifier.notifySimple(netId, PRIVATE_REQUESTS_BUFFER, "Intento de privado bloqueado: $nick", "El servidor retuvo el mensaje (+g)") }
        } catch (e: Exception) {
            // Keep the real server response visible even if the pending store failed.
            append(status, from = null, text = ev.text, doNotify = false)
            _state.update { it.copy(privateRequestMessage = "No se pudo guardar el aviso +g: ${e.message}") }
        }
    }
    return true
}

/** Native presentation only: no addon, pending conversation, automatic response or fake content. */
internal fun IrcViewModel.showNativePrivateAttempt(netId: String, nick: String, text: String, serverText: String? = null) {
    val st = _state.value
    val status = bufKey(netId, "*server*")
    val muted = isNotifyIgnoredSender(netId, nick)
    val active = st.selectedBuffer.takeIf { splitKey(it).first == netId && it in st.buffers }
    val targets = linkedSetOf(status)
    if (st.settings.privateAttemptActiveNotice && !muted && active != null) targets.add(active)
    for (key in targets) append(key, from = null, text = if (key == status && serverText != null) "$text $serverText" else text,
        isHighlightedNotice = key == active && st.settings.privateAttemptActiveNotice && !muted, doNotify = false)
    if (st.settings.privateAttemptFloating && !muted) {
        val alert = PrivateAttemptAlert(nextUiMsgId.getAndIncrement(), netId, nick, text)
        _state.update { it.copy(privateAttemptAlerts = (it.privateAttemptAlerts + alert).takeLast(8)) }
    }
}

fun IrcViewModel.dismissPrivateAttempt(id: Long) {
    _state.update { it.copy(privateAttemptAlerts = it.privateAttemptAlerts.filterNot { alert -> alert.id == id }) }
}

fun IrcViewModel.setNativePrivateMode(netId: String, mode: Char, enabled: Boolean) {
    viewModelScope.launch {
        try {
            val rt = runtimes[netId] ?: error("Red desconectada")
            val st = _state.value
            val conn = st.connections[netId] ?: error("Red desconectada")
            val command = privateModeCommand(rt.client.isConnectedNow(), conn.myNick, mode, enabled,
                conn.supportedUserModes, netId in st.callerIdNetworks, conn.userModes)
            _state.update { it.copy(privateModeMessages = it.privateModeMessages + (netId to "Se solicitó ${if (enabled) '+' else '-'}$mode. El control mostrará el estado confirmado por el servidor; revisá Status.")) }
            rt.client.sendRaw(command)
            // The switch changes only after the server's actual MODE/221 reply.
            rt.client.sendRaw("MODE ${conn.myNick}")
        } catch (e: Exception) {
            _state.update { it.copy(privateModeMessages = it.privateModeMessages + (netId to "No se cambió el modo: ${e.message}")) }
        }
    }
}

fun IrcViewModel.callerIdAction(netId: String, operation: String) {
    viewModelScope.launch {
        try {
            val rt = runtimes[netId] ?: error("Red desconectada")
            val myNick = _state.value.connections[netId]?.myNick.orEmpty()
            val command = callerIdCommand(rt.support.callerIdMode, rt.client.isConnectedNow(), myNick, operation)
            rt.client.sendRaw(command)
            _state.update { it.copy(privateRequestMessage = "Solicitud $command enviada; consultá la respuesta del servidor en Status") }
        } catch (e: Exception) { _state.update { it.copy(privateRequestMessage = "No se envió la operación +g: ${e.message}") } }
    }
}

/** Handshake/status output must not create an unaccepted encrypted private buffer. */
internal fun IrcViewModel.privateProtocolStatusKey(netId: String, peer: String): String {
    val key = resolveBufferKey(netId, peer)
    val id = privateRequestId(netId, casefoldText(netId, peer))
    return if (hasPrivateRequestPolicy() && id !in sessionPrivateAccepted && key !in _state.value.buffers)
        bufKey(netId, "*server*") else key
}
