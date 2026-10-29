/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import com.boxlabs.hexdroid.script.EventData

internal const val PRIVATE_REQUEST_SCRIPT = "solicitudes-pv.hex"

internal fun IrcViewModel.hasPrivateRequestPolicy(): Boolean {
    initScripts()
    return scriptEngineDelegate.isInitialized() && scriptEngine.hasPrivateRequestHandler()
}

internal fun IrcViewModel.scriptHoldsPrivateRequest(
    network: String, nick: String, text: String = "", history: Boolean = false,
    blocked: Boolean = false, action: Boolean = false,
): Boolean = hasPrivateRequestPolicy() && scriptEngine.onPrivateRequest(EventData(
    network = network, buffer = nick, from = nick, text = text, isPrivate = true,
    isAction = action, fields = mapOf("history" to history.toString(), "serverblocked" to blocked.toString()),
))

/** Script entry points to the durable Android queue and its configuration UI. */
fun IrcViewModel.scriptPrivateCapability(name: String, args: List<String>): String = when (name) {
    "private.open" -> { goTo(AppScreen.PRIVATE_REQUESTS); "true" }
    "private.settings" -> { goTo(AppScreen.PRIVATE_SCRIPT_SETTINGS); "true" }
    else -> ""
}
