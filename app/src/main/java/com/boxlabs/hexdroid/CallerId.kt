/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

/** The existing numeric payload is also retained for caller-id output. */
internal val IrcEvent.ServerText.serverParams: List<String> get() = whoisParams
