/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import org.junit.Assert.*
import org.junit.Test

class ChannelTopicTest {
    @Test fun topicPermissionsFollowMembershipConnectionAndTheActualTMode() {
        assertTrue(canEditChannelTopic(true, true, true, true, "+nt"))
        assertTrue(canEditChannelTopic(true, true, true, false, "+ns"))
        assertFalse(canEditChannelTopic(true, true, true, false, "+nt"))
        assertFalse(canEditChannelTopic(true, true, true, false, null))
        assertFalse(canEditChannelTopic(true, false, true, true, "+nt"))
        assertFalse(canEditChannelTopic(true, true, false, true, "+ns"))
        assertFalse(canEditChannelTopic(false, true, true, true, "+nt"))
    }

    @Test fun editingPreservesTopicContentAndRejectsInjectionOrSilentTruncation() {
        assertEquals("/topic #demo :", topicEditCommand("#demo", ""))
        assertEquals("/topic #demo ::Literal  ñ 🙂", topicEditCommand("#demo", ":Literal  ñ 🙂"))
        assertEquals("/topic &demo :\u000304Rojo", topicEditCommand("&demo", "\u000304Rojo"))
        for ((channel, text, limit) in listOf(
            Triple("#demo", "a\nQUIT", 0), Triple("#demo", "a\rQUIT", 0), Triple("#demo", "a\u0000b", 0),
            Triple("#demo,#other", "texto", 0), Triple("#demo", "demasiado", 3), Triple("#demo", "🙂".repeat(200), 0),
        )) {
            try { topicEditCommand(channel, text, limit); fail("Invalid topic accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun nativeTopicCommandsSetClearAndQueryOnTheRealIrcWireWithoutFakingReplies() {
        val h = IrcTestHarness("echo-message")
        try {
            val topic = ":Texto  de prueba ñ 🙂 \u000304rojo\u000f"
            h.run { h.client.handleSlashCommand(topicEditCommand("#demo", topic).removePrefix("/"), "#demo") }
            h.server.expect { it == "TOPIC #demo :$topic" }
            assertTrue(h.events.none { it is IrcEvent.Topic })
            h.server.send(":DemoNick01!user@host.invalid TOPIC #demo :$topic")
            assertEquals(topic, h.await<IrcEvent.Topic> { it.channel == "#demo" }.topic)
            h.run { h.client.handleSlashCommand("topic #demo :", "#demo") }
            h.server.expect { it == "TOPIC #demo :" }
            h.run { h.client.handleSlashCommand("topic #demo", "#demo") }
            h.server.expect { it == "TOPIC #demo" }
            h.run { h.client.handleSlashCommand("topic Texto  sin canal", "#demo") }
            h.server.expect { it == "TOPIC #demo :Texto  sin canal" }
        } finally { h.close() }
    }

    @Test fun joinKeepsTheFullAvailableMaskAndGreenArrowWithoutRepeatingItsRoom() {
        val colored = membershipEventLine(MembershipEvent.JOIN, "DemoNick01", "ident@2001:db8::1234", "#demo")
        assertEquals("→ JOIN DemoNick01 (ident@2001:db8::1234)", stripIrcFormatting(colored))
        assertTrue(colored.startsWith("\u00033→\u000314"))
        assertFalse(colored.contains("#demo"))
        assertEquals("→ JOIN DemoNick01", membershipEventLine(MembershipEvent.JOIN, "DemoNick01", null, "#demo", colors = false))
        assertTrue(membershipEventLine(MembershipEvent.PART, "DemoNick01", "ident@host.invalid", "#demo").contains("#demo"))
    }
}
