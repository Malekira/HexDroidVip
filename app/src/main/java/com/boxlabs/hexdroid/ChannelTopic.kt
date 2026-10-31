/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

/** Unknown channel modes do not grant permission to ordinary members. The server decides. */
internal fun canEditChannelTopic(
    channel: Boolean, connected: Boolean, member: Boolean, halfOpOrAbove: Boolean, modes: String?,
): Boolean = channel && connected && member && (halfOpOrAbove || (modes != null && 't' !in modes))

/** Explicit trailing parameter distinguishes clearing a topic from requesting its current value. */
internal fun topicEditCommand(channel: String, text: String, advertisedLimit: Int = 0): String {
    require(channel.isNotBlank() && channel.none { it.isWhitespace() || it in ":,\u0000" })
    require(text.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Topic must be one IRC line" }
    // Kotlin treats IRC italic/strike/underline bytes as whitespace. The existing command
    // pipeline trims its input; a neutral reset protects closing style bytes at the end.
    // Keep that workaround local to the topic editor, without changing other commands.
    val wireText = if (text.lastOrNull() in listOf('\u001d', '\u001e', '\u001f')) text + '\u000f' else text
    require(advertisedLimit <= 0 || wireText.length <= advertisedLimit) { "Topic exceeds the server limit" }
    require("TOPIC $channel :$wireText".toByteArray(Charsets.UTF_8).size <= 510) { "Topic exceeds the IRC line limit" }
    return "/topic $channel :$wireText"
}
