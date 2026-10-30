/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import org.junit.Assert.*
import org.junit.Test

class Dreams104Test {
    @Test fun joinAliasPreservesListsKeysAndNetworkPrefixes() {
        assertEquals("join #sala", normalizeJoinAlias("j sala"))
        assertEquals("join #sala,#otra,&local clave1,clave2", normalizeJoinAlias("J sala,#otra,&local clave1,clave2"))
        assertEquals("join +local", normalizeJoinAlias("j +local", "#+"))
        assertEquals("join #+local", normalizeJoinAlias("j +local", "#&"))
        assertEquals("join 0", normalizeJoinAlias("j 0"))
        assertEquals("join", normalizeJoinAlias("j"))
        assertEquals("join sala", normalizeJoinAlias("join sala"))
        assertEquals("msg DemoNick01 hello", normalizeJoinAlias("msg DemoNick01 hello"))
    }

    @Test fun actualClientSendsJoinAliasAndReceivesConfirmedUserModes() {
        val h = IrcTestHarness("echo-message", listOf(
            ":srv 001 me :Welcome", ":srv 004 me srv test ioRg nt", ":srv 005 me CHANTYPES=#& PREFIX=(ov)@+ CALLERID=g :supported", ":srv 376 me :End"))
        try {
            h.server.expect { it == "MODE me" }
            assertEquals("ioRg", h.await<IrcEvent.SupportedUserModes> { true }.modes)
            h.run { h.client.handleSlashCommand("j sala,otra llave1,llave2", "*server*") }
            h.server.expect { it == "JOIN #sala,#otra llave1,llave2" }
            h.server.send(":srv 221 me +iR")
            assertEquals("+iR", h.await<IrcEvent.OwnUserModes> { it.snapshot }.modes)
            h.server.send(":me!u@host MODE me :+g-R")
            assertEquals("+g-R", h.await<IrcEvent.OwnUserModes> { !it.snapshot }.modes)
            h.server.send(":DemoNick01!u@host MODE DemoNick01 +g")
            h.sync("after-foreign-mode")
            assertEquals(2, h.events.filterIsInstance<IrcEvent.OwnUserModes>().size)
        } finally { h.close() }
    }

    @Test fun metadataAvatarUsesRealSetAndClearCommands() {
        val h = IrcTestHarness("echo-message batch draft/metadata-2")
        try {
            assertTrue(h.client.hasCap("draft/metadata-2"))
            h.run { h.client.setOwnMetadata("avatar", "https://images.example.invalid/photo.jpg") }
            h.server.expect { it == "METADATA * SET avatar :https://images.example.invalid/photo.jpg" }
            h.run { h.client.setOwnMetadata("avatar", null) }
            h.server.expect { it == "METADATA * SET avatar" }
        } finally { h.close() }
    }

    @Test fun modesNeverClaimUnsupportedOrOfflineChanges() {
        assertEquals("MODE DemoNick01 +g", privateModeCommand(true, "DemoNick01", 'g', true, "i", true, "i"))
        assertEquals("MODE DemoNick01 +R", privateModeCommand(true, "DemoNick01", 'R', true, "iR", false, "i"))
        assertEquals("MODE DemoNick01 -R", privateModeCommand(true, "DemoNick01", 'R', false, "", false, "R"))
        assertEquals("gi", updatedUserModes("iR", "+g-R"))
        assertEquals("R", updatedUserModes("gi", "+R", snapshot = true))
        assertEquals("", updatedUserModes("gi", "+", snapshot = true))
        try { privateModeCommand(true, "DemoNick01", 'R', true, "i", true, ""); fail("Unsupported R") } catch (_: IllegalArgumentException) { }
        try { privateModeCommand(false, "DemoNick01", 'g', true, "g", true, ""); fail("Offline send") } catch (_: IllegalStateException) { }
        try { privateModeCommand(true, "DemoNick01\r\nQUIT", 'g', true, "g", true, ""); fail("Injection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun avatarUrlsRequireHttpsAndNeverCredentialsOrControlCharacters() {
        assertTrue(validAvatarUrl("https://images.example.invalid/photo.jpg?size=500"))
        assertTrue(validAvatarUrl(""))
        listOf("http://images.example.invalid/photo.jpg", "file:///photo.jpg", "https://user:pass@example.invalid/photo.jpg",
            "https://example.invalid/photo.jpg#fragment", "https://example.invalid/\r\nQUIT", "https:///photo", "https://example.invalid/a b").forEach { assertFalse(it, validAvatarUrl(it)) }
    }

    @Test fun recallRetainsPlainMessagesAndExcludesCommandsAndSecrets() {
        assertTrue(mayRecallInput("Hola á 🙂")); assertTrue(mayRecallInput("\u000304mensaje con color"))
        listOf("", " /ns identify example", "/join #demo key", "\u0002/oper demo example", "token=example", "password:example").forEach { assertFalse(it, mayRecallInput(it)) }
    }
}
