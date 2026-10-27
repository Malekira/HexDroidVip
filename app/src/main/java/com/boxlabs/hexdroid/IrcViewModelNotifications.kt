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

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import com.boxlabs.hexdroid.connection.ProxyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Send [i] to the keep-alive service after a short pause, replacing any update still waiting.
 * Starts the service when it isn't running and the app may; otherwise shows a plain notification.
 */
internal fun IrcViewModel.postKeepAliveUpdate(i: Intent, netId: String, label: String, status: String) {
    keepAliveUpdateJob?.cancel()
    keepAliveUpdateJob = viewModelScope.launch {
        delay(KEEPALIVE_UPDATE_COALESCE_MS)
        runCatching {
            when {
                KeepAliveService.isRunning -> appContext.startService(i)
                AppVisibility.canStartForegroundService() -> {
                    KeepAliveService.startRequested = true
                    ContextCompat.startForegroundService(appContext, i)
                }
                else -> notifier.showConnection(netId, label, status)
            }
        }.onFailure {
            KeepAliveService.startRequested = false
            runCatching { notifier.showConnection(netId, label, status) }
        }
    }
}

/**
 * Stop the keep-alive service, dropping any update still waiting to be sent. Does nothing when
 * it is neither running nor starting, since the stop request would create it first.
 */
internal fun IrcViewModel.stopKeepAliveService() {
    keepAliveUpdateJob?.cancel()
    keepAliveUpdateJob = null
    lastNotifLabel = null
    lastNotifStatus = null
    if (!KeepAliveService.isRunning && !KeepAliveService.startRequested) return
    runCatching {
        appContext.startService(Intent(appContext, KeepAliveService::class.java).apply { action = KeepAliveService.ACTION_STOP })
    }
}

/** Vibrate once at [intensity] */
internal fun IrcViewModel.vibrateForHighlight(intensity: VibrateIntensity) =
    com.boxlabs.hexdroid.vibrateForHighlight(appContext, intensity)

/**
 * Give [netId] the device's push endpoint, if there is one and the server wants it.
 *
 * Runs on every registration. When no endpoint exists yet this asks the distributor
 * for one instead; that reply is asynchronous and arrives at the push service.
 */
internal fun IrcViewModel.maybeRegisterWebPush(netId: String) {
    if (!_state.value.settings.webPushEnabled) return
    val client = runtimes[netId]?.client ?: return
    if (!client.hasWebPushCap()) return

    val serverVapid = client.webPushVapidKey()
    val sub = com.boxlabs.hexdroid.push.WebPushManager.subscription(appContext)
    if (sub == null) {
        com.boxlabs.hexdroid.push.WebPushManager.register(appContext, serverVapid)
        return
    }

    // A subscription requested before any server was connected has no key bound to it,
    // and a server that signs its pushes will have them dropped by the distributor.
    // Ask for a fresh endpoint now that a key is known. Only when the stored key is
    // absent: replacing one server's key with another's would flap between two
    // webpush servers, and the first key still wins by design (see
    // WebPushManager.register).
    if (serverVapid != null &&
        com.boxlabs.hexdroid.push.WebPushManager.subscribedVapid(appContext) == null
    ) {
        com.boxlabs.hexdroid.push.WebPushManager.register(appContext, serverVapid)
        return
    }

    // Sent on every registration rather than once per endpoint: a server may expire a subscription at any time
    viewModelScope.launch {
        runCatching { client.webPushRegister(sub.endpoint, sub.auth, sub.p256dh) }
    }
}

internal fun IrcViewModel.updateConnectionNotification(status: String) {
    refreshConnectionNotification(statusOverride = status)
}

internal fun IrcViewModel.clearConnectionNotification() {
    refreshConnectionNotification(statusOverride = null)
}

internal fun IrcViewModel.refreshConnectionNotification(statusOverride: String? = null) {
    val st = _state.value
    if (appExitRequested) {
        // Don't resurrect the notification/FGS during an explicit user exit.
        runCatching { appContext.stopService(Intent(appContext, KeepAliveService::class.java)) }
        stopKeepAliveService()
        runCatching { notifier.cancelConnection() }
        return
    }
    if (!st.settings.showConnectionStatusNotification && !st.settings.keepAliveInBackground) {
        // Ensure we don't leave stale notifications behind.
        stopKeepAliveService()
        notifier.cancelConnection()
        return
    }

    val connectedIds = st.connections.filterValues { it.connected }.keys
    val connectingIds = st.connections.filterValues { it.connecting }.keys

    val displayIds: List<String> = when {
        connectedIds.isNotEmpty() -> connectedIds.toList()
        connectingIds.isNotEmpty() -> connectingIds.toList()
        else -> emptyList()
    }

    if (displayIds.isEmpty()) {
        // If keep-alive is enabled and the user still wants networks connected (auto-reconnect),
        // keep the foreground service alive so the process + reconnect loop can keep running.
        if (st.settings.keepAliveInBackground && desiredConnected.isNotEmpty()) {
            val wanted = desiredConnected.toList()
            val namesWanted = wanted.mapNotNull { id -> st.networks.firstOrNull { it.id == id }?.name }.ifEmpty { wanted }
            val labelWanted = if (wanted.size > 1) {
                appContext.getString(R.string.vm_notif_networks, wanted.size, namesWanted.joinToString(", "))
            } else {
                val net = st.networks.firstOrNull { it.id == wanted.first() }
                if (net != null) "${net.name} • ${net.host}:${net.port}" else appContext.getString(R.string.app_name)
            }
            val netIdForIntent = st.activeNetworkId?.takeIf { wanted.contains(it) } ?: wanted.first()
            val statusTxt = if (!hasInternetConnection()) appContext.getString(R.string.vm_status_waiting_network)
                    else appContext.getString(R.string.vm_status_reconnecting)
            if (lastNotifViaService && labelWanted == lastNotifLabel && statusTxt == lastNotifStatus &&
                KeepAliveService.isRunning) return
            lastNotifLabel = labelWanted
            lastNotifStatus = statusTxt
            lastNotifViaService = true
            val i = Intent(appContext, KeepAliveService::class.java).apply {
                action = KeepAliveService.ACTION_UPDATE
                putExtra(KeepAliveService.EXTRA_NETWORK_ID, netIdForIntent)
                putExtra(KeepAliveService.EXTRA_SERVER_LABEL, labelWanted)
                putExtra(KeepAliveService.EXTRA_STATUS, statusTxt)
            }
            postKeepAliveUpdate(i, netIdForIntent, labelWanted, statusTxt)
            return
        }

        stopKeepAliveService()
        notifier.cancelConnection()
        return
    }

    val netIdForIntent = st.activeNetworkId?.takeIf { displayIds.contains(it) } ?: displayIds.first()
    val names = displayIds.mapNotNull { id -> st.networks.firstOrNull { it.id == id }?.name }.ifEmpty { displayIds }

    val label = if (displayIds.size > 1) {
        appContext.getString(R.string.vm_notif_networks, displayIds.size, names.joinToString(", "))
    } else {
        val net = st.networks.firstOrNull { it.id == displayIds.first() }
        if (net != null) "${net.name} • ${net.host}:${net.port}" else appContext.getString(R.string.app_name)
    }

    val status = statusOverride ?: when {
        connectedIds.isNotEmpty() && connectingIds.isNotEmpty() ->
            appContext.getString(R.string.vm_notif_connected_connecting, connectedIds.size, connectingIds.size)
        connectedIds.isNotEmpty() -> appContext.getString(R.string.vm_status_connected)
        else -> appContext.getString(R.string.vm_status_connecting)
    }

    if (st.settings.keepAliveInBackground) {
        // Skip the Binder IPC if the visible text hasn't changed - avoids waking
        // NotificationManager on every lag update / ping cycle (once per minute).
        if (lastNotifViaService && label == lastNotifLabel && status == lastNotifStatus &&
            KeepAliveService.isRunning) return
        lastNotifLabel = label
        lastNotifStatus = status
        lastNotifViaService = true

        val i = Intent(appContext, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_UPDATE
            putExtra(KeepAliveService.EXTRA_NETWORK_ID, netIdForIntent)
            putExtra(KeepAliveService.EXTRA_SERVER_LABEL, label)
            putExtra(KeepAliveService.EXTRA_STATUS, status)
        }

        postKeepAliveUpdate(i, netIdForIntent, label, status)
        return
    }

    if (st.settings.showConnectionStatusNotification) {
        // Same rule without the service: repost only when the text changes.
        if (!lastNotifViaService && label == lastNotifLabel && status == lastNotifStatus) return
        lastNotifLabel = label
        lastNotifStatus = status
        lastNotifViaService = false
        notifier.showConnection(netIdForIntent, label, status)
    } else {
        lastNotifLabel = null
        lastNotifStatus = null
        notifier.cancelConnection()
    }
}

// DCC

/**
 * True when [buffer] is the conversation currently on screen in the foreground app.
 *
 * The name is compared under the selected network's CASEMAPPING, because a pushed
 * message names a target rather than one of our buffer keys.
 */
fun IrcViewModel.isBufferActivelyVisible(buffer: String): Boolean {
    if (buffer.isBlank()) return false
    if (!AppVisibility.isForeground) return false
    val st = _state.value
    if (st.screen != AppScreen.CHAT) return false
    val selected = st.selectedBuffer.takeIf { it.isNotBlank() } ?: return false
    val (selNet, selBuf) = splitKey(selected)
    return casefoldText(selNet, selBuf) == casefoldText(selNet, buffer)
}

/**
 * The network id and display name that a pushed message about [buffer] belongs to.
 */
fun IrcViewModel.networkForPushTarget(buffer: String): Pair<String, String>? {
    if (buffer.isBlank()) return null
    val st = _state.value
    val matches = runtimes.keys.toList().filter { netId ->
        if (!hasLiveConnection(netId)) return@filter false
        val fold = casefoldText(netId, buffer)
        st.buffers.keys.any { k ->
            val (nid, bn) = splitKey(k)
            nid == netId && casefoldText(netId, bn) == fold
        }
    }
    val netId = matches.singleOrNull() ?: return null
    return netId to (st.networks.firstOrNull { it.id == netId }?.name ?: "")
}

/**
 * Hand the newly issued push endpoint to every connected server that wants one.
 *
 * The distributor answers [WebPushManager.register] asynchronously, so the endpoint does
 * not exist yet when the request is made. Called by the push service when it arrives.
 */
fun IrcViewModel.onPushEndpointChanged() {
    if (!_state.value.settings.webPushEnabled) return
    for (netId in runtimes.keys.toList()) maybeRegisterWebPush(netId)
}

/**
 * Keep [Avatars] up to date with the metadata avatars notifications may fetch: those on
 * unproxied networks, while image previews are on.
 */
internal fun IrcViewModel.launchNotificationAvatarSync() {
    viewModelScope.launch {
        _state
            .map { st ->
                if (!st.settings.imagePreviewsEnabled) emptyMap()
                else st.networks.mapNotNull { net ->
                    val urls = st.connections[net.id]?.avatarUrls
                    if (net.proxyType != ProxyType.NONE || urls.isNullOrEmpty()) null else net.id to urls
                }.toMap()
            }
            .distinctUntilChanged()
            .collect { Avatars.setAllowedUrls(it) }
    }
}
