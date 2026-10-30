/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

internal enum class MembershipEvent(val arrow: String, val color: Int) {
    JOIN("→", 3), PART("←", 6), QUIT("←", 4),
}

/** Local event text, also readable in exported logs; never sent to IRC. */
internal fun membershipEventLine(
    event: MembershipEvent,
    nick: String,
    userHost: String?,
    channel: String? = null,
    reason: String? = null,
    hideHostnames: Boolean = false,
    colors: Boolean = true,
): String {
    val body = buildString {
        append(" ${event.name} $nick")
        if (!hideHostnames && !userHost.isNullOrBlank()) append(" ($userHost)")
        if (event != MembershipEvent.JOIN && !channel.isNullOrBlank()) append(" $channel")
        if (!reason.isNullOrBlank()) append(" [$reason]")
    }
    // A formatted quit reason must not recolour the event. Missing hosts stay absent.
    val plain = stripIrcFormatting(body)
    return if (colors) "\u0003${event.color}${event.arrow}\u000314$plain\u000f"
        else event.arrow + plain
}
