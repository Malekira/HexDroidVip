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

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import com.boxlabs.hexdroid.IrcViewModel.Companion.CONNECT_FAN_OUT_DELAY_MS
import com.boxlabs.hexdroid.IrcViewModel.Companion.EVENT_DRAIN_BUFFER_CAPACITY
import com.boxlabs.hexdroid.IrcViewModel.Companion.LAN_LOOKUP_TIMEOUT_MS
import com.boxlabs.hexdroid.IrcViewModel.ConnStatusDedupEntry
import com.boxlabs.hexdroid.IrcViewModel.ConnectSecrets
import com.boxlabs.hexdroid.IrcViewModel.NetRuntime
import com.boxlabs.hexdroid.connection.ConnectionConstants
import com.boxlabs.hexdroid.data.IgnoreType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** Hydrate the in-memory flapPaused set from DataStore (called once, lazily, on first use). */
internal suspend fun IrcViewModel.ensureFlapPausedLoaded() {
    if (flapPausedLoaded) return
    flapPausedLock.withLock { loadFlapPausedLocked() }
}

internal suspend fun IrcViewModel.loadFlapPausedLocked() {
    if (flapPausedLoaded) return
    val now = System.currentTimeMillis()
    val stored = runCatching { repo.readFlapPaused() }.getOrDefault(emptyMap())
    flapPausedLoaded = true
    // Drop entries older than 2× the flap window so a week-old pause doesn't
    // block reconnect forever after a stable period.
    val active = stored.filter { (_, pausedAt) ->
        pausedAt + ConnectionConstants.FLAP_WINDOW_MS * 2 > now
    }
    flapPaused.addAll(active.keys)
    // Arm a resume timer for each surviving pause so it ends on schedule in this
    // process too, instead of only expiring on the next restart's hydration.
    for ((netId, pausedAt) in active) {
        scheduleFlapResume(netId, (pausedAt + ConnectionConstants.FLAP_WINDOW_MS) - now)
    }
    // Persist the cleaned-up map back so expired entries don't accumulate.
    if (active.size != stored.size) {
        val newMap = stored.filterKeys { it in flapPaused }
        runCatching { repo.writeFlapPaused(newMap) }
    }
}

/**
 * Load our own stored metadata from DataStore once. The loaded flag is set only after the read,
 * under the lock, so a concurrent caller can't save against an empty store. Values already in
 * memory win.
 */
internal suspend fun IrcViewModel.ensureOwnMetadataLoaded() {
    if (ownMetadataLoaded) return
    ownMetadataLock.withLock {
        if (ownMetadataLoaded) return
        val stored = runCatching { repo.readOwnMetadata() }.getOrDefault(emptyMap())
        stored.forEach { (netId, keys) ->
            val target = ownMetadataStore.getOrPut(netId) { java.util.concurrent.ConcurrentHashMap() }
            keys.forEach { (k, v) -> target.putIfAbsent(k, v) }
        }
        ownMetadataLoaded = true
    }
}

/** Re-send our stored own metadata to the server so it survives a reconnect. */
internal fun IrcViewModel.reapplyOwnMetadata(netId: String) {
    val rt = runtimes[netId] ?: return
    if (!rt.client.hasCap("draft/metadata-2") || rt.client.isSoju) return
    viewModelScope.launch {
        ensureOwnMetadataLoaded()
        val keys = ownMetadataStore[netId]?.toMap() ?: return@launch
        keys.forEach { (k, v) -> runCatching { rt.client.setOwnMetadata(k, v) } }
    }
}

internal suspend fun IrcViewModel.ensureStsPoliciesLoaded() {
    if (stsPoliciesLoaded) return
    stsLoadLock.withLock {
        if (stsPoliciesLoaded) return
        loadStsPoliciesLocked()
    }
}

internal suspend fun IrcViewModel.loadStsPoliciesLocked() {
    val now = System.currentTimeMillis()
    val stored = runCatching { repo.readStsPolicies() }.getOrDefault(emptyMap())
    val active = stored.filterValues { it.isActive(now) }
    // Entries learned while the read was running are newer than the stored ones.
    active.forEach { (host, entry) -> stsPolicies.putIfAbsent(host, entry) }
    stsPoliciesLoaded = true
    // Write back the pruned map so expired policies don't accumulate forever.
    if (active.size != stored.size) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { repo.writeStsPolicies(stsPolicies.toMap()) } }
    }
}

internal fun IrcViewModel.persistStsPolicies() {
    viewModelScope.launch(Dispatchers.IO) { runCatching { repo.writeStsPolicies(stsPolicies.toMap()) } }
}

/**
 * When a registered secure connection closes, push its host's STS expiry to now plus the
 * last advertised duration, as the STS spec requires.
 */
internal fun IrcViewModel.rescheduleStsOnClose(netId: String) {
    if (_state.value.connections[netId]?.connected != true) return
    if (runtimes[netId]?.client?.config?.useTls != true) return
    val host = _state.value.networks.firstOrNull { it.id == netId }?.host?.trim()?.lowercase() ?: return
    val entry = stsPolicies[host] ?: return
    if (entry.durationSec <= 0) return
    stsPolicies[host] = entry.copy(expiresAtMs = System.currentTimeMillis() + entry.durationSec * 1000L)
    persistStsPolicies()
}

internal fun IrcViewModel.scheduleFlapResume(netId: String, delayMs: Long) {
    flapResumeJobs.remove(netId)?.cancel()
    flapResumeJobs[netId] = viewModelScope.launch {
        delay(delayMs.coerceAtLeast(1000L))
        flapResumeJobs.remove(netId)
        if (!flapPaused.contains(netId)) return@launch
        clearFlapPaused(netId)
        pingTimeoutTimestamps.remove(netId)
        val conn = _state.value.connections[netId]
        if (desiredConnected.contains(netId) &&
            conn?.connected != true && conn?.connecting != true
        ) {
            append(
                bufKey(netId, "*server*"), from = null,
                text = "*** " + appContext.getString(R.string.status_cooldown_over),
                doNotify = false
            )
            if (_state.value.settings.autoReconnectEnabled) scheduleAutoReconnect(netId)
        }
    }
}

internal fun IrcViewModel.markFlapPaused(netId: String) {
    flapPaused.add(netId)
    scheduleFlapResume(netId, ConnectionConstants.FLAP_WINDOW_MS)
    viewModelScope.launch(Dispatchers.IO) {
        flapPausedLock.withLock {
            runCatching {
                val current = repo.readFlapPaused().toMutableMap()
                current[netId] = System.currentTimeMillis()
                repo.writeFlapPaused(current)
            }
        }
    }
}

internal fun IrcViewModel.clearFlapPaused(netId: String) {
    flapResumeJobs.remove(netId)?.cancel()
    flapPaused.remove(netId)
    viewModelScope.launch(Dispatchers.IO) {
        flapPausedLock.withLock {
            runCatching {
                val current = repo.readFlapPaused().toMutableMap()
                current.remove(netId)
                repo.writeFlapPaused(current)
            }
        }
    }
}

internal fun IrcViewModel.netLock(netId: String): Mutex {
    netOpLocks[netId]?.let { return it }
    val created = Mutex()
    val prev = netOpLocks.putIfAbsent(netId, created)
    return prev ?: created
}

internal suspend inline fun <T> IrcViewModel.withNetLock(netId: String, crossinline block: suspend () -> T): T {
    return netLock(netId).withLock { block() }
}

/**
 * True when the boot-time connect should hold. Latches off for good the moment the
 * user opens the app, an unmetered network arrives, or the setting is turned off,
 * so a later disconnect cannot re-arm the wait behind the user's back.
 */
internal fun IrcViewModel.waitingForWifi(): Boolean {
    if (!bootWifiWait) return false
    if (!_state.value.settings.connectOnBootWifiOnly) { bootWifiWait = false; return false }
    // The user is looking at the app, so they can decide about mobile data themselves.
    if (AppVisibility.isActivityStarted) { bootWifiWait = false; return false }
    if (hasUnmeteredConnection()) { bootWifiWait = false; return false }
    return true
}

/** True when the active network carries internet and is not metered. */
internal fun IrcViewModel.hasUnmeteredConnection(): Boolean {
    val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

internal fun IrcViewModel.hasInternetConnection(): Boolean {
    val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    val hasTransport =
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    return hasTransport && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/**
 * True when [host] is a loopback, private, link-local or unique-local address or a .local name,
 * judged from the string alone (no DNS lookup); other hostnames count as public. Such a DCC
 * target is refused through a proxy. [isLanHost] is the part the local-network permission covers.
 */
internal fun IrcViewModel.isLocalHost(host: String): Boolean {
    val h = host.trim().lowercase()
    // Loopback
    if (h == "localhost" || h == "::1" || h.startsWith("127.")) return true
    // Private IPv4 ranges: 10.x, 172.16–31.x, 192.168.x
    if (h.startsWith("10.")) return true
    if (h.startsWith("192.168.")) return true
    if (h.startsWith("172.")) {
        val second = h.split(".").getOrNull(1)?.toIntOrNull() ?: return false
        if (second in 16..31) return true
    }
    // IPv4 link-local (self-assigned, always LAN)
    if (h.startsWith("169.254.")) return true
    // IPv6 link-local (fe80::) and unique-local (fc00::/7 = fc..-fd..).
    // Require a colon so hostnames like "fcafe.chat" or "fdn.example" aren't
    // misclassified as IPv6 literals.
    if (h.contains(':')) {
        if (h.startsWith("fe80:")) return true
        if (h.startsWith("fc") || h.startsWith("fd")) return true
    }
    // mDNS names are local by definition
    if (h.endsWith(".local")) return true
    // Let DNS sort out anything else
    return false
}

/** [isLocalHost] without loopback, which stays on the device and needs no local-network permission. */
internal fun IrcViewModel.isLanHost(host: String): Boolean {
    val h = host.trim().lowercase()
    if (h == "localhost" || h == "::1" || h.startsWith("127.")) return false
    return isLocalHost(h)
}

/**
 * True when a connection to [host] goes to the local network, which Android 17+ allows only with
 * ACCESS_LOCAL_NETWORK: a private, link-local or unique-local address, or a .local name. A
 * hostname is resolved, with a time limit, and counts when any of its addresses does; one that
 * doesn't resolve counts as public. Loopback stays on the device and doesn't count. Blocking.
 */
internal fun IrcViewModel.reachesLocalNetwork(host: String): Boolean {
    val h = host.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
    if (h.isEmpty() || h.equals("localhost", ignoreCase = true)) return false
    if (h.endsWith(".local", ignoreCase = true) || h.endsWith(".local.", ignoreCase = true)) return true
    val addresses = if (android.os.Build.VERSION.SDK_INT >= 29 && android.net.InetAddresses.isNumericAddress(h)) {
        arrayOf(android.net.InetAddresses.parseNumericAddress(h))
    } else {
        runCatching { resolveAllWithTimeout(h, null, LAN_LOOKUP_TIMEOUT_MS) }.getOrNull() ?: return false
    }
    return addresses.any { a ->
        !a.isLoopbackAddress && (a.isSiteLocalAddress || a.isLinkLocalAddress ||
            (a is java.net.Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc))
    }
}

/**
 * The SOCKS proxy configured for [netId]'s profile, or a disabled config when it has none. DCC
 * connections go through the same proxy as the IRC link, and listen-based DCC is unavailable
 * behind one. The password is loaded for proxies that authenticate per connection.
 */
internal fun IrcViewModel.proxyForNetwork(netId: String): com.boxlabs.hexdroid.connection.ProxyConfig {
    val profile = _state.value.networks.firstOrNull { it.id == netId }
        ?: return com.boxlabs.hexdroid.connection.ProxyConfig()
    if (profile.proxyType == com.boxlabs.hexdroid.connection.ProxyType.NONE) {
        return com.boxlabs.hexdroid.connection.ProxyConfig()
    }
    val pw = runCatching { repo.secretStore.getProxyPassword(netId) }.getOrNull()
    return com.boxlabs.hexdroid.connection.ProxyConfig(
        type = profile.proxyType,
        host = profile.proxyHost,
        port = profile.proxyPort,
        username = profile.proxyUsername?.takeIf { it.isNotEmpty() },
        password = pw?.takeIf { it.isNotEmpty() },
    )
}

/**
 * Cheap check (no keystore/disk access) for whether [netId] is configured to use a
 * proxy. Safe to call on the main thread; use this for UI-thread gating, and call
 * [proxyForNetwork] (which loads the encrypted password) only on a background dispatcher.
 */
internal fun IrcViewModel.isProxiedNetwork(netId: String): Boolean {
    val profile = _state.value.networks.firstOrNull { it.id == netId } ?: return false
    return profile.proxyType != com.boxlabs.hexdroid.connection.ProxyType.NONE &&
        profile.proxyHost.isNotBlank() && profile.proxyPort in 1..65535
}

/**
 * True when accepting [offer] would be refused by the Android 17+ local-network permission
 * (ACCESS_LOCAL_NETWORK), which both active and passive DCC need for a LAN peer. Checked before
 * auto-accepting too, so a blocked offer falls back to the normal prompt.
 */
internal fun IrcViewModel.dccBlockedByLanPermission(offer: DccOffer): Boolean =
    isLanHost(offer.ip) && !hasLocalNetworkPermission()

internal fun IrcViewModel.hasLocalNetworkPermission(): Boolean {
    if (android.os.Build.VERSION.SDK_INT < 37) return true
    return android.content.pm.PackageManager.PERMISSION_GRANTED ==
        androidx.core.content.ContextCompat.checkSelfPermission(
            appContext, "android.permission.ACCESS_LOCAL_NETWORK"
        )
}

/** Writes the desired set. Each write takes its snapshot under the lock, so the last one to run is the current state. */
internal fun IrcViewModel.persistDesiredNetworkIds() {
    viewModelScope.launch(Dispatchers.IO) {
        desiredPersistLock.withLock {
            runCatching { repo.setDesiredNetworkIds(desiredConnected.toSet()) }
        }
    }
}

internal fun IrcViewModel.maybeRestoreDesiredConnections() {
    if (desiredNetworkIdsApplied) return
    if (!desiredNetworkIdsLoaded) return
    val st = _state.value
    if (st.networks.isEmpty()) return

    if (!st.settings.keepAliveInBackground) return

    desiredNetworkIdsApplied = true
    val existing = st.networks.map { it.id }.toSet()
    // Restore only networks that were desired AND have autoConnect on, so turning autoConnect
    // off takes effect for a network that was connected when the process last ended.
    val autoConnectIds = st.networks.filter { it.autoConnect }.map { it.id }.toSet()
    val targets = desiredConnected
        .filter { existing.contains(it) && autoConnectIds.contains(it) }
        .toList()

    // Drop any persisted desired-connect entries that no longer correspond to an
    // existing+autoConnect network so they don't re-trigger on subsequent launches.
    // (Networks deleted entirely are also pruned here.)
    val before = desiredConnected.size
    desiredConnected.retainAll { existing.contains(it) && autoConnectIds.contains(it) }
    if (desiredConnected.size != before) persistDesiredNetworkIds()

    if (targets.isEmpty()) return
    // Same rationale as maybeAutoConnect: stagger to avoid bouncer rate-limit bounces.
    viewModelScope.launch {
        targets.forEachIndexed { i, id ->
            if (i > 0) delay(CONNECT_FAN_OUT_DELAY_MS)
            connectNetwork(id)
        }
    }
}

internal fun IrcViewModel.registerNetworkCallback() {
    val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

    val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            // Network became available - check if any desired connections need reconnecting
            reconnectDesiredDisconnected()
        }

        override fun onLost(network: android.net.Network) {
            validatedNetworks.remove(network)
            // Network lost - check if we still have connectivity via another network.
            viewModelScope.launch {
                delay(500) // Brief window to let a failover interface take over.
                if (!hasInternetConnection()) {
                    // No connectivity at all: tear down each affected socket so its read
                    // unblocks now rather than after SOCKET_READ_TIMEOUT_MS.
                    // dropConnectionForNetworkLoss() keeps the network in desiredConnected.
                    val st = _state.value
                    val snapshot = desiredConnected.toList()
                    for (netId in snapshot) {
                        val conn = st.connections[netId]
                        if (conn?.connected == true || conn?.connecting == true) {
                            val serverKey = bufKey(netId, "*server*")
                            val lostText = if (autoReconnectAllowed(netId)) R.string.status_network_lost else R.string.status_disconnected
                            append(serverKey, from = null, text = "*** " + appContext.getString(lostText), doNotify = false)
                            noNetworkNotice.add(netId)
                            dropConnectionForNetworkLoss(netId)
                        }
                    }
                } else {
                    // Connectivity remains, but a socket may have been bound to the network
                    // that just went away, and onAvailable won't fire for an already-up
                    // failover link. Drop and reconnect any connection bound to it.
                    val st = _state.value
                    val snapshot = desiredConnected.toList()
                    for (netId in snapshot) {
                        val conn = st.connections[netId]
                        val ridingLostNet = runtimes[netId]?.boundNetwork == network
                        if (ridingLostNet && (conn?.connected == true || conn?.connecting == true)) {
                            val serverKey = bufKey(netId, "*server*")
                            val changedText = if (autoReconnectAllowed(netId)) R.string.status_network_changed else R.string.status_disconnected
                            append(serverKey, from = null, text = "*** " + appContext.getString(changedText), doNotify = false)
                            dropConnectionForNetworkLoss(netId, waitingText = appContext.getString(R.string.vm_status_reconnecting))
                        }
                    }
                }
            }
        }

        override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) {
            // A router restart that leaves Wi-Fi associated only toggles VALIDATED, with no
            // onLost/onAvailable. Reconnect on the not-validated to validated edge rather than
            // waiting out the backoff; other capability updates are ignored.
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val was = validatedNetworks.put(network, validated)
            if (validated && was == false) reconnectDesiredDisconnected()
        }
    }
    networkCallback = cb
    try {
        val request = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, cb)
    } catch (e: Exception) {
        // Registration can fail on some OEM ROMs (e.g. missing permission, broken
        // ConnectivityManager implementation). Log it to every server buffer so the
        // user knows auto-reconnect on network change won't work.
        val msg = "*** " + appContext.getString(R.string.vm_netcallback_failed, e.message ?: e.javaClass.simpleName)
        viewModelScope.launch {
            for (netId in _state.value.networks.map { it.id }) {
                append(bufKey(netId, "*server*"), from = null, text = msg, doNotify = false)
            }
        }
    }
}

/**
 * Recovery when connectivity returns (onAvailable, or the validated edge): for every wanted
 * network that is neither connected nor connecting, clear the backoff countdown and attempt
 * counter and connect.
 */
internal fun IrcViewModel.reconnectDesiredDisconnected() {
    viewModelScope.launch {
        delay(1000) // Brief delay to let the network stabilize
        val st = _state.value
        // Snapshot before iterating: connectNetwork() inside the loop body adds
        // to desiredConnected (and the auth-fail path may also remove from it
        // via downstream disconnect handlers), so iterating the live set risks
        // ConcurrentModificationException on any background ramp-up.
        val snapshot = desiredConnected.toList()
        for (netId in snapshot) {
            if (netId !in deferredConnects && !autoReconnectAllowed(netId)) continue
            val conn = st.connections[netId]
            if (conn?.connected != true && conn?.connecting != true) {
                val serverKey = bufKey(netId, "*server*")
                if (noNetworkNotice.remove(netId)) {
                    append(serverKey, from = null, text = "*** " + appContext.getString(R.string.status_network_available_reconnect), doNotify = false)
                }
                // A network change invalidates the failure history, so cancel any backoff
                // countdown and reset the counter, as dropConnectionForNetworkLoss does on the
                // way down.
                autoReconnectJobs.remove(netId)?.cancel()
                reconnectAttempts.remove(netId)
                connectNetwork(netId, force = true)
            }
        }
    }
}

internal fun IrcViewModel.maybeAutoConnect() {
    val st = _state.value
    if (autoConnectAttempted) return
    if (st.networks.isEmpty()) return
    autoConnectAttempted = true
    val targets = st.networks.filter { it.autoConnect }
    if (targets.isEmpty()) return
    // Stagger the fan-out. Bouncers and many IRCds rate-limit new TCP from one IP,
    // so firing N connect attempts simultaneously triggers "reconnect too fast" errors
    // against soju in particular. A small inter-connect delay is invisible to the user
    // (the UI still shows all networks connecting) but satisfies typical rate limits.
    viewModelScope.launch {
        targets.forEachIndexed { i, n ->
            if (i > 0) delay(CONNECT_FAN_OUT_DELAY_MS)
            connectNetwork(n.id)
        }
    }
}

/**
 * Purge the per-network in-memory maps for [netId] (nick tracking, away state, echo queue,
 * pending closes, typing timers and, with [resetReconnectState], reconnect state) so they don't
 * accumulate for networks that no longer exist.
 */
internal fun IrcViewModel.cleanupNetworkMaps(netId: String, resetReconnectState: Boolean = false) {
    // Per-channel nick maps
    val chanPrefix = "$netId::"
    chanNickCase.keys.filter   { it.startsWith(chanPrefix) }.forEach { chanNickCase.remove(it) }
    chanNickStatus.keys.filter { it.startsWith(chanPrefix) }.forEach { chanNickStatus.remove(it) }
    // The UI-visible nicklist is derived from the two maps above. Clear it as well, or the
    // channel keeps rendering a stale member list after a disconnect, making it look like
    // you're still joined when you aren't. Normally a rejoin's NAMES reply rebuilds it within
    // a second, so this is invisible; it only became visible when the rejoin itself failed
    // (manually-joined channels, see manuallyJoinedChannels preservation in connectNetworkInternal).
    _state.update { st ->
        val staleKeys = st.nicklists.keys.filter { it.startsWith(chanPrefix) }.toSet()
        if (staleKeys.isEmpty()) st else st.copy(nicklists = st.nicklists - staleKeys)
    }
    // Away state
    nickAwayState.remove(netId)
    // Sent lines awaiting their echo: the connection that would answer them is gone.
    flushOwnSends(netId)
    // recentSelfSends is kept across disconnects: it exists to dedup the bouncer's replay on
    // the reconnect that follows. It self-expires, is capped per buffer, and is cleared in
    // deleteNetwork().
    //
    // Pending close-after-part
    pendingCloseAfterPart.removeAll(pendingCloseAfterPart.filter { it.startsWith(chanPrefix) }.toSet())
    // Typing expiry jobs: cancel and remove all for this network
    val typingKeys = receivedTypingExpiryJobs.keys.filter { it.startsWith(chanPrefix) }
    typingKeys.forEach { receivedTypingExpiryJobs.remove(it)?.cancel() }
    // Notice-routing recently-joined fallback: drop entries for buffers on this network.
    // The opportunistic eviction in the JOIN handler already prunes by the 5-second
    // window cutoff, but if a user disconnects from a network without joining anything
    // afterwards, those entries linger until the cap eviction fires later. Cleaning
    // here is purely an upper bound on the leak.
    recentJoinAtMs.keys.filter { it.startsWith(chanPrefix) }.toList()
        .forEach { recentJoinAtMs.remove(it) }
    // Same per-network sweep for the chathistory marker armed window.
    chatHistory.forgetNetwork(netId)
    // Flap history and the backoff counter must survive a transient disconnect, since both
    // accumulate across reconnects; they are cleared only on a permanent teardown (user
    // disconnect, profile deletion, import cleanup).
    if (resetReconnectState) {
        pingTimeoutTimestamps.remove(netId)
        reconnectAttempts.remove(netId)
    }
    autoReconnectJobs.remove(netId)?.cancel()
    stableConnectionJobs.remove(netId)?.cancel()
    noNetworkNotice.remove(netId)
    waitingForWifiNotice.remove(netId)
    webPushFailureNotice.remove(netId)
    // The flap-PAUSED set (flapPaused) is likewise NOT cleared here: a network that
    // tripped flap protection must stay paused across reconnect cycles until the user
    // explicitly intervenes. Cleared in reconnectNetwork() on a manual reconnect.

    // Bouncer upstream cache: drop so a reconnect doesn't surface stale "discovered"
    // entries from the previous session. The bouncer re-announces the full list on
    // every reconnect, so rebuilding from scratch is correct and cheap.
    _state.update { st ->
        if (!st.bouncerNetworks.containsKey(netId)) st
        else st.copy(bouncerNetworks = st.bouncerNetworks - netId)
    }
    // NOTE: authBlockedReconnect is deliberately NOT cleared here, cleanupNetworkMaps
    // runs on every disconnect (including the auth-failure-induced disconnect that
    // SET the block), and clearing here would defeat the block entirely. Cleared
    // explicitly in connectNetwork(), reconnectNetwork(), saveEditingNetwork(), and
    // deleteNetwork() instead.
}

/**
 * Append a connection-status line to [netId]'s server buffer. A repeat within
 * [CONN_STATUS_DEDUP_WINDOW_MS] updates the existing line's "(×N)" count instead. No
 * notification or highlight by default. With [broadcast], a new line is also copied into the
 * network's other buffers. Returns true when a new line was added. Serialised on
 * [connStatusLock].
 */
internal fun IrcViewModel.appendConnStatus(
    netId: String,
    text: String,
    isHighlight: Boolean = false,
    doNotify: Boolean = false,
    from: String? = null,
    broadcast: Boolean = false,
    isError: Boolean = false,
): Boolean {
    val appendedFresh: Boolean = synchronized(connStatusLock) {
        val now = System.currentTimeMillis()
        val key = "${from ?: ""}|$text"
        val bufferKey = bufKey(netId, "*server*")
        val prev = lastConnStatusLine[netId]

        if (prev != null && prev.key == key && now - prev.lastSeenMs < CONN_STATUS_DEDUP_WINDOW_MS) {
            // Same line, same network, still within window — try to bump the existing
            // message's counter rather than appending a new one.
            val newCount = prev.count + 1
            val newText = "${prev.baseText} (×$newCount)"
            // What the buffered message currently reads. First repeat: it's just the
            // baseText (no suffix yet). Subsequent repeats: "(×N)" where N = prev.count.
            val expectedText = if (prev.count == 1) prev.baseText
            else "${prev.baseText} (×${prev.count})"
                var collapsed = false
                _state.update { st ->
                    // Reset on every CAS retry so a failed retry doesn't leave `collapsed`
                    // stuck at true from a previous attempt that didn't make it into state.
                    collapsed = false
                    val buf = st.buffers[bufferKey] ?: return@update st
                    // Connection-status lines are recent by construction, so search only the
                    // last few messages (bounded scan, ignores the full scrollback.) Matching
                    // on (from, text) is enough here: the count suffix differs between calls
                    // but expectedText reflects what the message reads RIGHT NOW.
                    val tailStart = (buf.messages.size - 8).coerceAtLeast(0)
                    val tail = buf.messages.subList(tailStart, buf.messages.size)
                    val idxInTail = tail.indexOfLast { it.from == from && it.text == expectedText }
                    if (idxInTail < 0) return@update st
                        val realIdx = tailStart + idxInTail
                        val updated = buf.messages[realIdx].copy(text = newText)
                        collapsed = true
                        st.copy(buffers = st.buffers + (bufferKey to buf.copy(log = buf.log.replaceAt(realIdx, updated))))
                }
                if (collapsed) {
                    lastConnStatusLine[netId] = prev.copy(count = newCount, lastSeenMs = now)
                    return@synchronized false
                }
                // Else: original message has been trimmed out of scrollback (the buffer rolled
                // past it). Fall through and append fresh as a new line.
        }

        append(bufferKey, from = from, text = text, isHighlight = isHighlight, doNotify = doNotify, isError = isError)
        lastConnStatusLine[netId] = ConnStatusDedupEntry(
            key = key,
            baseText = text,
            from = from,
            lastSeenMs = now,
            count = 1,
            bufferKey = bufferKey,
        )
        true
    }

    if (broadcast && appendedFresh) {
        broadcastConnStatusToOtherBuffers(netId, text, from)
    }

    return appendedFresh
}

/**
 * Copy a connection-status line into every channel and query buffer on [netId], excluding the
 * server buffer and DCC chats. The copies don't count as unread or notify, but are logged.
 */
internal fun IrcViewModel.broadcastConnStatusToOtherBuffers(netId: String, text: String, from: String?) {
    val keys = _state.value.buffers.keys.filter { key ->
        val (kNetId, kBufName) = splitKey(key)
        kNetId == netId && !isPseudoBuffer(kBufName) && !isDccChatBufferName(kBufName)
    }
    for (key in keys) {
        append(
            bufferKey = key,
            from = from,
            text = text,
            isLocal = true,
            doNotify = false,
        )
    }
}

/**
 * Forget the dedup tracker for [netId] so subsequent connection-status lines pass
 * unconditionally. Called after a successful registration: the user has been on a
 * working connection long enough that a fresh disconnect deserves a fresh log line,
 * even if the new reason happens to match the last one we saw an hour ago.
 */
internal fun IrcViewModel.resetConnStatusDedup(netId: String) {
    lastConnStatusLine.remove(netId)
}

internal suspend fun IrcViewModel.connectNetworkInternal(netId: String, force: Boolean = false) {
    if (!force) {
        val c = _state.value.connections[netId]
        if (c?.connected == true || c?.connecting == true) return
    }
    // Keystore and certificate reads are slow on some devices, so they happen off the main
    // thread before anything else; the rest runs in one go on fresh state.
    val connectSecrets = _state.value.networks.firstOrNull { it.id == netId }?.let { p ->
        withContext(Dispatchers.IO) {
            ConnectSecrets(
                profileId = p.id,
                certId = p.tlsClientCertId,
                sasl = repo.secretStore.getSaslPasswordResult(p.id),
                server = repo.secretStore.getServerPassword(p.id),
                proxy = repo.secretStore.getProxyPassword(p.id),
                cert = repo.secretStore.loadTlsClientCert(p.id, p.tlsClientCertId),
            )
        }
    }
    // Android 17+ needs ACCESS_LOCAL_NETWORK to reach the LAN. Through a proxy only the proxy
    // is dialled from here, and it resolves the server itself.
    val lanTarget = _state.value.networks.firstOrNull { it.id == netId }?.let { p ->
        if (p.proxyType != com.boxlabs.hexdroid.connection.ProxyType.NONE && p.proxyHost.isNotBlank()) p.proxyHost
        else p.host
    }
    val needsLanPermission = lanTarget != null && !hasLocalNetworkPermission() &&
        withContext(Dispatchers.IO) { reachesLocalNetwork(lanTarget) }
    val st = _state.value
    val conn = st.connections[netId]
    if (!force && (conn?.connected == true || conn?.connecting == true)) return

    val profilePre = st.networks.firstOrNull { it.id == netId }
    // An active STS policy forces this connection onto TLS below, so the plaintext
    // warning dialog would be wrong - the connection will not be plaintext.
    val stsForcesTls = profilePre != null &&
        stsPolicies[profilePre.host.trim().lowercase()]?.isActive(System.currentTimeMillis()) == true
    if (profilePre != null && !profilePre.useTls && !profilePre.allowInsecurePlaintext && !stsForcesTls) {
        val removedDesired = desiredConnected.remove(netId)
        if (removedDesired) persistDesiredNetworkIds()
        manualDisconnecting.remove(netId)
        autoReconnectJobs.remove(netId)?.cancel()
        setNetConn(netId) { it.copy(connected = false, connecting = false, status = appContext.getString(R.string.vm_status_plaintext_disabled)) }
        if (_state.value.activeNetworkId == netId) clearConnectionNotification()
        _state.update { it.copy(plaintextWarningNetworkId = netId) }
        return
    }

    if (profilePre != null && needsLanPermission && !hasLocalNetworkPermission()) {
        val removedDesired2 = desiredConnected.remove(netId)
        if (removedDesired2) persistDesiredNetworkIds()
        manualDisconnecting.remove(netId)
        autoReconnectJobs.remove(netId)?.cancel()
        setNetConn(netId) { it.copy(connected = false, connecting = false, status = appContext.getString(R.string.vm_status_lan_perm)) }
        if (_state.value.activeNetworkId == netId) clearConnectionNotification()
        _state.update { it.copy(localNetworkWarningNetworkId = netId) }
        return
    }

    val addedDesired = desiredConnected.add(netId)
    if (addedDesired) persistDesiredNetworkIds()
    manualDisconnecting.remove(netId)
    autoReconnectJobs.remove(netId)?.cancel()

    val existing = runtimes.remove(netId)
    // Carry the manually joined channels over to the new runtime so they are rejoined.
    val carriedManualJoins = existing?.manuallyJoinedChannels?.toMap().orEmpty()
    if (existing != null) {
        runCatching { existing.client.forceClose("Reconnecting") }
        runCatching { existing.job?.cancel() }
    }

    val profile = profilePre ?: st.networks.firstOrNull { it.id == netId }
    if (profile == null) {
        val removedDesired = desiredConnected.remove(netId)
        if (removedDesired) persistDesiredNetworkIds()
        manualDisconnecting.remove(netId)
        autoReconnectJobs.remove(netId)?.cancel()
        setNetConn(netId) { it.copy(connected = false, connecting = false, status = appContext.getString(R.string.vm_status_not_configured)) }
        if (_state.value.activeNetworkId == netId) clearConnectionNotification()
        return
    }
    val secrets = connectSecrets
        ?.takeIf { it.profileId == profile.id && it.certId == profile.tlsClientCertId }
        ?: ConnectSecrets(
            profileId = profile.id,
            certId = profile.tlsClientCertId,
            sasl = repo.secretStore.getSaslPasswordResult(profile.id),
            server = repo.secretStore.getServerPassword(profile.id),
            proxy = repo.secretStore.getProxyPassword(profile.id),
            cert = repo.secretStore.loadTlsClientCert(profile.id, profile.tlsClientCertId),
        )
    val saslPasswordResult = secrets.sasl
    val saslPassword = when (saslPasswordResult) {
        is com.boxlabs.hexdroid.data.SecretStore.SecretResult.Value -> saslPasswordResult.secret
        is com.boxlabs.hexdroid.data.SecretStore.SecretResult.KeystoreInvalidated -> {
            // Keystore key was invalidated (biometric change, factory reset of
            // Keystore, etc.). The stored SASL password has been cleared.
            ensureServerBuffer(netId)
            appendConnStatus(
                netId = netId,
                text = "*** ⚠ " + appContext.getString(R.string.vm_sasl_keystore_invalid),
                from = null,
                isHighlight = false,
                doNotify = false,
            )
            null
        }
        is com.boxlabs.hexdroid.data.SecretStore.SecretResult.NotSet -> null
    }
    val serverPassword = secrets.server
    val proxyPassword = secrets.proxy
    // An imported certificate wins; otherwise the network's own one, when switched on.
    val tlsCert = secrets.cert
        ?: if (profile.useTls && profile.autoClientCert) {
            TlsClientCert(keystoreAlias = NetworkCertificates.aliasFor(profile.id))
        } else null
    val cfgBase = profile.toIrcConfig(
                    saslPasswordOverride = saslPassword,
                    serverPasswordOverride = serverPassword,
                    proxyPasswordOverride = proxyPassword,
                    tlsClientCert = tlsCert
                ).copy(
                    historyLimit = st.settings.ircHistoryLimit,
                    initialAwayMessage = awayMessages[netId],
                    ctcpRepliesEnabled = st.settings.ctcpRepliesEnabled,
                )

    // IRCv3 STS enforcement. A one-shot upgrade port (from an insecure CAP LS
    // `sts port=` moments ago) or an unexpired stored policy for this host forces
    // TLS regardless of the profile, and disables the allow-invalid-certs escape
    // hatch - the spec forbids certificate click-through while a policy is active
    // (TOFU pinning still applies on top). Plaintext-configured profiles connect
    // to the learned policy port, falling back to the standard 6697.
    val stsUpgradePort = stsUpgradePorts.remove(netId)
    val stsPolicy = stsPolicies[profile.host.trim().lowercase()]
        ?.takeIf { it.isActive(System.currentTimeMillis()) }
    val cfg = when {
        stsUpgradePort != null ->
            cfgBase.copy(useTls = true, port = stsUpgradePort, allowInvalidCerts = false)
        stsPolicy != null && !cfgBase.useTls ->
            cfgBase.copy(useTls = true, port = stsPolicy.port ?: 6697, allowInvalidCerts = false)
        stsPolicy != null ->
            cfgBase.copy(allowInvalidCerts = false)
        else -> cfgBase
    }

    ensureServerBuffer(netId)

    val serverKey = bufKey(netId, "*server*")

    if (cfg.useTls && !cfgBase.useTls) {
        append(serverKey, from = null,
            text = "*** " + appContext.getString(R.string.vm_sts_secure, profile.host, cfg.port),
            doNotify = false)
    }

    // If there's no active network with Internet capability, don't attempt to connect (it will just fail and spam).
    if (!hasInternetConnection()) {
        if (!noNetworkNotice.contains(netId)) {
            noNetworkNotice.add(netId)
            append(serverKey, from = null, text = "*** " + appContext.getString(R.string.vm_turn_on_data), doNotify = false)
        }
        deferredConnects.add(netId)
        setNetConn(netId) { it.copy(connected = false, connecting = false, status = appContext.getString(R.string.vm_status_waiting_network), myNick = cfg.nick) }
        if (_state.value.activeNetworkId == netId) updateConnectionNotification(appContext.getString(R.string.vm_status_waiting_network))
        if (_state.value.settings.autoReconnectEnabled) scheduleAutoReconnect(netId)
        return
    } else {
        noNetworkNotice.remove(netId)
    }

    // Connect on boot, waiting for Wi-Fi: mobile data is up but the user asked not to use it.
    if (waitingForWifi()) {
        // add() returns false when the notice is already on the buffer, so a retry
        // every reconnect interval doesn't repeat the line.
        if (waitingForWifiNotice.add(netId)) {
            append(serverKey, from = null,
                text = "*** " + appContext.getString(R.string.vm_waiting_for_wifi), doNotify = false)
        }
        deferredConnects.add(netId)
        setNetConn(netId) { it.copy(connected = false, connecting = false, status = appContext.getString(R.string.vm_status_waiting_wifi), myNick = cfg.nick) }
        if (_state.value.activeNetworkId == netId) updateConnectionNotification(appContext.getString(R.string.vm_status_waiting_wifi))
        // Backstop for the case the NetworkCallback failed to register (some OEM
        // ROMs), which would otherwise leave the wait with nothing to end it.
        if (_state.value.settings.autoReconnectEnabled) scheduleAutoReconnect(netId)
        return
    }

    waitingForWifiNotice.remove(netId)
    deferredConnects.remove(netId)

    // A connect started from the Networks list opens the chat screen; reconnects leave the user
    // where they are. The buffer is only selected when none is.
    val connectingStatus = appContext.getString(R.string.vm_status_connecting)
    _state.update { cur ->
        val preservedListModes = cur.connections[netId]?.listModes ?: NetConnState().listModes
        syncActiveNetworkSummary(
            cur.copy(
                connections = cur.connections + (netId to NetConnState(
                    connected = false,
                    connecting = true,
                    status = connectingStatus,
                    myNick = cfg.nick,
                    listModes = preservedListModes
                )),
                screen = if (cur.screen == AppScreen.NETWORKS) AppScreen.CHAT else cur.screen,
                selectedBuffer = cur.selectedBuffer.ifBlank { serverKey },
            )
        )
    }

    // Pin the connection to the network it is made on, so a handoff drops the socket promptly
    // and onLost can match it. Never pinned on a VPN, whose Network objects are replaced
    // constantly; the OS migrates VPN connections itself.
    val chosenNetwork = runCatching {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = cm?.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) null else active
    }.getOrNull()

    // What is on disk for each of this network's buffers as the connection begins.
    for ((k, v) in lastLoggedServerMs) {
        if (k.startsWith("$netId::")) loggedBeforeConnectMs.merge(k, v, ::maxOf)
    }

    val client = IrcClient(cfg.copy(pinnedNetwork = chosenNetwork))
    // Set before the connect coroutine starts so the handshake itself is logged, which
    // is where most of the interesting protocol traffic lives.
    client.rawLogEnabled = _state.value.settings.rawLog
    client.nickRegainEnabled = _state.value.settings.nickRegainEnabled
    client.ctcpIgnored = { nick, userHost -> isIgnoredSender(netId, nick, IgnoreType.CTCP, userHost) }
    // Wire localized status text into the protocol engine (and its session).
    client.strings = { id, args -> appContext.getString(id, *args) }
    client.plurals = { id, qty, args -> appContext.resources.getQuantityString(id, qty, *args) }
    // Attach the E2E codec for this network. The codec wraps the per-process
    // shared keystore (which lazily hydrates from SecretStore) and is held by
    // the IrcClient for its lifetime - reconnecting the same network reuses
    // the same client instance, so existing per-target keys carry over. A
    // network with no keys configured pays only the per-message null-check
    // since encryptOutgoing/decryptIncoming short-circuit on an empty cache.
    client.e2eCodec = com.boxlabs.hexdroid.crypto.E2eCodec(netId, e2eKeyStore)
    // Fail-closed predicate for the +AGE manual path: tells IrcCore.privmsg to refuse plaintext
    // on any buffer where the user has turned on +AGE (typed +AGE messaging isn't wired yet).
    client.ageEnabledForTarget = { tgt -> ageEnabledPrefs.getBoolean(ageKey(netId, tgt), false) }
    val thisClient = client
    val rt = NetRuntime(netId = netId, client = client, myNick = cfg.nick, suppressMotd = _state.value.settings.hideMotdOnConnect)
    rt.boundNetwork = chosenNetwork
    // Restore manually-joined channels so they're rejoined after a reconnect (see the
    // capture of carriedManualJoins above). Empty on a first connect.
    rt.manuallyJoinedChannels.putAll(carriedManualJoins)
    runtimes[netId] = rt

    if (_state.value.activeNetworkId == netId) updateConnectionNotification(connectingStatus)

    rt.job?.cancel()
    rt.job = viewModelScope.launch(Dispatchers.IO) {
        // Hold a scoped WakeLock for the connect/TLS handshake burst, then release it.
        // The foreground service keeps the process alive; the lock just covers the CPU-
        // intensive initial handshake so Android can't suspend us mid-handshake.
        // Pass netId so concurrent multi-network connects each get their own lock.
        val connectWakeLock = KeepAliveService.acquireScopedWakeLock(appContext, netId)
        try {
            // Outer try around the whole collection: events() can throw mid-stream (a write on
            // a socket that dropped during registration, an encoding error, a malformed
            // CAP/SASL sequence). CancellationException is rethrown so teardown still works;
            // anything else is logged, shown in the server buffer and handled as a disconnect,
            // so the connection reaches a proper end state instead of the throw ending the
            // process.
            try {
                client.events()
                    // Decouple the socket read loop from Main-thread event handling. Without
                    // this, the channelFlow's small default buffer (64) fills whenever Main
                    // lags, the producer's send() suspends, and the socket stops being
                    // drained, which makes us a "slow reader".
                    .buffer(capacity = EVENT_DRAIN_BUFFER_CAPACITY)
                    .collect { ev ->
                    // Handle events on Main: UI actions also write _state on Main, so every
                    // read-modify-write of _state happens on one thread. Handler throws are
                    // caught here; the outer try covers throws from the flow itself.
                    withContext(Dispatchers.Main.immediate) {
                        runCatching { handleEvent(netId, ev) }
                            .onFailure { t ->
                                val msg = (t.message ?: t::class.java.simpleName)
                                append(bufKey(netId, "*server*"), from = "CLIENT", text = appContext.getString(R.string.vm_event_handler_error, msg), isHighlight = true)
                            }
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.e("IrcViewModel", "events() flow crashed for $netId", t)
                val msg = (t.message ?: t::class.java.simpleName)
                withContext(Dispatchers.Main.immediate) {
                    runCatching {
                        append(
                            bufKey(netId, "*server*"),
                            from = "CLIENT",
                            text = "*** " + appContext.getString(R.string.vm_conn_error, msg),
                            isHighlight = true,
                        )
                    }
                    // Drive the connection back to a clean Disconnected state so the UI
                    // doesn't get stuck on the pre-crash status. We can't rely on the
                    // server side sending QUIT here - the throw probably happened before
                    // any clean shutdown sequence ran.
                    runCatching { handleEvent(netId, IrcEvent.Disconnected(msg)) }
                }
            }
        } finally {
            KeepAliveService.releaseScopedWakeLock(netId, connectWakeLock)
        }
    }

    // Fallback for a collector that ends without emitting Disconnected: clean up and schedule a
    // reconnect. Skipped when the job was cancelled (intentional teardown) and in the normal
    // path, where the Disconnected handler schedules the reconnect itself.
    rt.job?.invokeOnCompletion { cause ->
        if (cause is kotlinx.coroutines.CancellationException) return@invokeOnCompletion
        viewModelScope.launch {
            if (runtimes[netId]?.client !== thisClient) return@launch

            val cur = _state.value.connections[netId]
            val wasConnectedOrConnecting = (cur?.connected == true || cur?.connecting == true)
            if (!wasConnectedOrConnecting) {
                // Disconnected handler already ran (it sets connected/connecting = false
                // and schedules its own auto-reconnect). Nothing to do here.
                return@launch
            }

            // True fallback path: do the cleanup the Disconnected handler would have
            // done, then schedule reconnect.
            append(bufKey(netId, "*server*"), from = null, text = "*** " + appContext.getString(R.string.status_disconnected), doNotify = false)
            setNetConn(netId) { it.copy(connected = false, connecting = false, status = appContext.getString(R.string.vm_status_disconnected)) }
            if (_state.value.activeNetworkId == netId) clearConnectionNotification()

            if (!_state.value.settings.autoReconnectEnabled) return@launch
            val wasManual = manualDisconnecting.remove(netId)
            if (wasManual && !desiredConnected.contains(netId)) return@launch
            if (desiredConnected.contains(netId)) scheduleAutoReconnect(netId)
        }
    }
}

fun IrcViewModel.reconnectNetwork(netId: String) {
    val quitMsg = "Reconnecting"
    viewModelScope.launch {
        withNetLock(netId) {
        val addedDesired = desiredConnected.add(netId)
        if (addedDesired) persistDesiredNetworkIds()
        manualDisconnecting.add(netId)
        autoReconnectJobs.remove(netId)?.cancel()
        reconnectAttempts.remove(netId)
        // Clear flap detection state: the user has explicitly chosen to reconnect,
        // so we give the connection a fresh start.
        clearFlapPaused(netId)
        pingTimeoutTimestamps.remove(netId)
        // Manual reconnect also clears the auth-failure block (see connectNetwork
        // for rationale). Reaches the user-facing "reconnect" button via
        // reconnectActive() and the bouncer-side reconnect via this path.
        authBlockedReconnect.remove(netId)
        // Forget the connection-status dedup tracker too: the user explicitly chose
        // to retry, so any failure on this fresh attempt should surface even if it
        // matches the last failure verbatim.
        resetConnStatusDedup(netId)
        // Same lifecycle: drop any stashed server-error text. A current session
        // that registered cleanly invalidates any pre-registration ERROR we might
        // have correlated with a disconnect later on.
        lastServerErrorByNet.remove(netId)

        val oldRt = runtimes.remove(netId)
        runCatching { oldRt?.client?.disconnect(quitMsg) }
        runCatching { oldRt?.client?.forceClose() }
        runCatching { oldRt?.job?.cancel() }

        // Marked as connecting already, so nothing starts a second connect while this one
        // reads its credentials. Every early exit of connectNetworkInternal clears it.
        setNetConn(netId) { it.copy(connected = false, connecting = true, status = appContext.getString(R.string.vm_status_reconnecting)) }

        // Bypass the "already connecting" guard by calling the internal variant.
        connectNetworkInternal(netId, force = true)
        }
    }
}

/**
 * User-requested full shutdown ("Exit").
 *
 * We aggressively suppress auto-reconnect + foreground-service restarts so the user doesn't
 * have to Force Stop the app.
 */
fun IrcViewModel.exitApp() {
    appExitRequested = true

    // Prevent auto-reconnect from bringing things back up while we're exiting.
    desiredConnected.clear()
    persistDesiredNetworkIds()
    manualDisconnecting.clear()
    reconnectAttempts.clear()
    autoReconnectJobs.values.forEach { it.cancel() }
    autoReconnectJobs.clear()
    stableConnectionJobs.values.forEach { it.cancel() }
    stableConnectionJobs.clear()
    noNetworkNotice.clear()
    waitingForWifiNotice.clear()
    deferredConnects.clear()

    // Stop the foreground service + cancel notifications immediately.
    runCatching { NotificationHelper.cancelAll(appContext) }
    runCatching { appContext.stopService(Intent(appContext, KeepAliveService::class.java)) }
    stopKeepAliveService()
    runCatching { notifier.cancelConnection() }

    // Then disconnect everything.
    disconnectAll()
}

fun IrcViewModel.disconnectAll() {
    val netIds = runtimes.keys.toList()
    for (id in netIds) disconnectNetwork(id)
}

/**
 * Orderly shutdown when the app is swiped from recents with background persistence off: send
 * QUIT and close each socket so the server doesn't keep a ghost until ping timeout. Runs on IO.
 * Returns true if QUITs were sent; [onDone] then stops the service.
 */
fun IrcViewModel.onTaskRemovedGracefulQuit(onDone: () -> Unit): Boolean {
    if (_state.value.settings.keepAliveInBackground) return false
    val clients = runtimes.values.map { it.client }
    if (clients.isEmpty()) return false
    // Suppress auto-reconnect for teardown
    appExitRequested = true
    val reason = _state.value.settings.quitMessage.ifBlank { "Client disconnect" }
    appScope.launch {
        runCatching {
            withTimeoutOrNull(2_000L) {
                clients.map { c ->
                    launch(Dispatchers.IO) { runCatching { c.disconnect(reason) } }
                }.forEach { it.join() }
            }
        }
        runCatching { onDone() }
    }
    return true
}

/**
 * Tear down a live connection because its network went away, so the UI updates immediately
 * instead of waiting for the read timeout. Recovery goes through [scheduleAutoReconnect].
 */
internal fun IrcViewModel.dropConnectionForNetworkLoss(
    netId: String,
    waitingText: String = appContext.getString(R.string.vm_status_waiting_network),
) {
    viewModelScope.launch {
        withNetLock(netId) {
            val statusText = if (autoReconnectAllowed(netId)) waitingText
                else appContext.getString(R.string.vm_status_disconnected)
            rescheduleStsOnClose(netId)
            val oldRt = runtimes.remove(netId)
            runCatching { oldRt?.client?.forceClose("Network lost") }
            runCatching { oldRt?.job?.cancel() }
            // Reset the backoff counter so the first attempt after the network
            // returns fires promptly instead of waiting out a stale exponential delay.
            reconnectAttempts.remove(netId)
            setNetConn(netId) {
                it.copy(connected = false, connecting = false, status = statusText, lagMs = null)
            }
            if (_state.value.activeNetworkId == netId) updateConnectionNotification(statusText)
                if (_state.value.settings.autoReconnectEnabled && desiredConnected.contains(netId)) {
                    scheduleAutoReconnect(netId)
                }
        }
    }
}

/** True when [netId] may be brought back without the user asking: global and per-network auto-reconnect, and not exiting. */
internal fun IrcViewModel.autoReconnectAllowed(netId: String): Boolean {
    val st = _state.value
    if (!st.settings.autoReconnectEnabled) return false
    if (appExitRequested) return false
    return st.networks.firstOrNull { it.id == netId }?.autoReconnect != false
}

// Auto-reconnect
internal fun IrcViewModel.scheduleAutoReconnect(netId: String) {
    if (!autoReconnectAllowed(netId)) return
    // Auth failure on the previous attempt: do nothing. Reconnecting with the same
    // (wrong) credentials would just trigger the same 464 / SASL fail in a tight
    // loop, flooding the server log and hitting bouncer rate limits or IRCd bans.
    // The user must explicitly reconnect (or fix the profile) to clear the block.
    if (netId in authBlockedReconnect) {
        val serverKey = bufKey(netId, "*server*")
        append(serverKey, from = null,
            text = "*** " + appContext.getString(R.string.vm_reconnect_halted_auth),
            doNotify = false)
        setNetConn(netId) { it.copy(status = appContext.getString(R.string.vm_status_auth_halted)) }
        return
    }
    // One job per network. Started only once registered, so a loop that ends at once
    // doesn't leave a finished job in the map.
    autoReconnectJobs.remove(netId)?.cancel()
    val serverKey = bufKey(netId, "*server*")
    val reconnectJob = viewModelScope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
        // Outer guard: the loop body touches state, notifications, logs, the wakelock and the
        // socket, and any of them can throw on some devices. A failure is logged and the next
        // reconnect starts fresh; CancellationException is rethrown so cancel() still works.
        try {
            // The attempt whose backoff countdown has already run. The `continue` paths below
            // (connect in flight, manual disconnect in progress) poll every second instead of
            // re-running the countdown for the same attempt.
            var countdownDoneForAttempt = -1
            while (isActive) {
            val attempt = reconnectAttempts[netId] ?: 0
            // Already connected: skip the countdown rather than announcing a reconnect.
            if (_state.value.connections[netId]?.connected == true) {
                reconnectAttempts.remove(netId)
                break
            }
                val baseDelaySec = _state.value.settings.autoReconnectDelaySec.coerceIn(
                ConnectionConstants.RECONNECT_BASE_DELAY_MIN_SEC,
                ConnectionConstants.RECONNECT_BASE_DELAY_MAX_SEC
            )
            if (attempt > 0 && attempt != countdownDoneForAttempt) {
                countdownDoneForAttempt = attempt
                val exp = attempt.coerceAtMost(ConnectionConstants.RECONNECT_MAX_EXPONENT)
                val planned = (baseDelaySec.toLong() * (1L shl exp)).coerceAtMost(ConnectionConstants.RECONNECT_MAX_DELAY_SEC)
                val jitter = (planned * ConnectionConstants.RECONNECT_JITTER_FACTOR).toLong()
                val actual = if (jitter > 0) planned - jitter + Random.nextLong(jitter * 2 + 1) else planned
                setNetConn(netId) { it.copy(status = appContext.getString(R.string.vm_status_reconnecting_in, actual)) }
                // Route through appendConnStatus for consistency with the rest of the
                // connection-status pipeline. Each attempt has a different N and Xs so
                // dedup never collapses these in practice, but a malicious server that
                // forces us to retry on the same counter twice in a row wouldn't get to
                // print two identical lines.
                appendConnStatus(
                    netId = netId,
                    text = "*** " + appContext.getString(R.string.status_reconnect_in, "${actual}s", attempt + 1),
                    from = null,
                    doNotify = false,
                    isHighlight = false,
                    broadcast = true,
                )
                // While backgrounded, no one's looking at the countdown — skip the tick
                // loop and just wait the full duration in one delay() call. Avoids waking
                // the CPU every 1-5 s purely to update a status string the user can't see.
                if (!AppVisibility.isForeground) {
                    delay(actual * 1000L)
                } else {
                    // Show countdown in the server buffer, updating every 5s for long delays.
                    val tickInterval = when {
                        actual > 30 -> 5L
                        actual > 10 -> 2L
                        else -> 1L
                    }
                    var remaining = actual
                    while (remaining > 0 && isActive) {
                        // If the connection came up mid-countdown (an in-flight attempt
                        // succeeded, or onAvailable reconnected us), stop ticking now so we
                        // don't paint a stale "Reconnecting in Ns…" over "Connected".
                        if (_state.value.connections[netId]?.connected == true) break
                        val tick = remaining.coerceAtMost(tickInterval)
                        delay(tick * 1000L)
                        remaining -= tick
                        if (remaining > 0) {
                            // Re-check foreground state on every tick: if the user backgrounds the
                            // app mid-countdown, swallow the rest of the wait without further updates.
                            if (!AppVisibility.isForeground) {
                                delay(remaining * 1000L)
                                remaining = 0
                            } else {
                                if (_state.value.connections[netId]?.connected == true) break
                                setNetConn(netId) { it.copy(status = appContext.getString(R.string.vm_status_reconnecting_in, remaining)) }
                            }
                        }
                    }
                }
                if (!isActive) break
            }

            // Stop if the user no longer wants this network connected.
            if (!desiredConnected.contains(netId)) break
            // The user disconnected or reconnected deliberately: don't fight them. Poll rather
            // than spin.
            if (manualDisconnecting.contains(netId)) { delay(1000L); continue }

            val st = _state.value
            // If the profile no longer exists, stop retrying.
            if (st.networks.none { it.id == netId }) {
                desiredConnected.remove(netId)
                reconnectAttempts.remove(netId)
                break
            }

            val cur = st.connections[netId]
            if (cur?.connected == true) {
                reconnectAttempts.remove(netId)
                break
            }
            // A connect attempt is in flight: wait for it rather than starting another.
            if (cur?.connecting == true) { delay(1000L); continue }

            // If there's no connectivity at all (Wi‑Fi + Mobile disabled), pause auto-reconnect until it returns.
            if (!hasInternetConnection()) {
                if (!noNetworkNotice.contains(netId)) {
                    noNetworkNotice.add(netId)
                    append(serverKey, from = null, text = "*** " + appContext.getString(R.string.vm_turn_on_data), doNotify = false)
                    setNetConn(netId) { it.copy(connected = false, connecting = false, status = appContext.getString(R.string.vm_status_waiting_network)) }
                    if (_state.value.activeNetworkId == netId) updateConnectionNotification(appContext.getString(R.string.vm_status_waiting_network))
                }
                delay(5000L)
                continue
            } else if (noNetworkNotice.remove(netId)) {
                // Connectivity is back; let the user know once and try again.
                append(serverKey, from = null, text = "*** " + appContext.getString(R.string.status_network_available_retry), doNotify = false)
            }

            // Pause reconnect when battery saver is active to avoid draining battery.
            // We still attempt to reconnect when the user has the app in the foreground,
            // but when backgrounded + battery saver on, we wait until saver turns off.
            if (!AppVisibility.isForeground) {
                val pm = appContext.getSystemService(android.content.Context.POWER_SERVICE)
                    as? android.os.PowerManager
                if (pm?.isPowerSaveMode == true) {
                    append(serverKey, from = null, text = "*** " + appContext.getString(R.string.status_battery_saver_paused), doNotify = false)
                    setNetConn(netId) { it.copy(status = appContext.getString(R.string.vm_status_paused_battery)) }
                    // Poll every 30s until battery saver is disabled or app comes to foreground.
                    while (!AppVisibility.isForeground && pm.isPowerSaveMode && isActive) {
                        delay(30_000L)
                    }
                    if (isActive && !pm.isPowerSaveMode) {
                        append(serverKey, from = null, text = "*** " + appContext.getString(R.string.status_battery_saver_off), doNotify = false)
                    }
                    continue
                }
            }

            if (attempt > 0) {
                appendConnStatus(
                    netId = netId,
                    text = "*** " + appContext.getString(R.string.status_retry_connect, attempt + 1),
                    from = null,
                    doNotify = false,
                    isHighlight = false,
                )
            }
            setNetConn(netId) { it.copy(status = appContext.getString(R.string.vm_status_retrying)) }
            if (st.activeNetworkId == netId) updateConnectionNotification(appContext.getString(R.string.vm_status_retrying))

            // Force a clean reconnect (drops stale runtimes if present).
            withNetLock(netId) { KeepAliveService.withWakeLock(appContext) { connectNetworkInternal(netId, force = true) } }
            reconnectAttempts[netId] = (attempt + 1).coerceAtMost(ConnectionConstants.RECONNECT_MAX_ATTEMPTS)
        }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Normal cancellation from autoReconnectJobs.remove(netId)?.cancel() upstream.
            // Re-throw so structured concurrency cleanup runs normally.
            throw ce
        } catch (t: Throwable) {
            android.util.Log.e("IrcViewModel", "autoReconnect loop crashed for $netId", t)
            runCatching {
                append(
                    serverKey,
                    from = "CLIENT",
                    text = "*** " + appContext.getString(R.string.vm_reconnect_halted, t.message ?: t::class.java.simpleName),
                    isHighlight = true,
                )
            }
        } finally {
            // Only this job's entry: a replacement may already be registered.
            autoReconnectJobs.remove(netId, coroutineContext.job)
        }
    }
    autoReconnectJobs[netId] = reconnectJob
    reconnectJob.start()
}
