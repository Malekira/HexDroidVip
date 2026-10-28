/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

/** The existing numeric payload is also retained for caller-id output. */
internal val IrcEvent.ServerText.serverParams: List<String> get() = whoisParams

/** Never claim a server authorisation while offline or without the advertised dialect. */
internal fun callerIdCommand(mode: String?, connected: Boolean, ownNick: String, operation: String, target: String? = null): String {
    require(mode == "g") { "La red no anunció CALLERID=g" }
    check(connected) { "Red desconectada; no se envió la operación +g" }
    require(isReplyNick(ownNick)) { "Nick propio no disponible" }
    return when (operation) {
        "enable" -> "MODE $ownNick +g"
        "disable" -> "MODE $ownNick -g"
        "list" -> "ACCEPT *"
        "accept" -> { require(target != null && isReplyNick(target)) { "Nick de solicitud no válido" }; "ACCEPT +$target" }
        else -> error("Operación desconocida")
    }
}
