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

import com.boxlabs.hexdroid.script.*
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class PersonalPhase2Test {
    @Test fun floatingIsDefaultWithoutAnIrcBuffer() {
        assertEquals(ReplyDestination.FLOATING, UiSettings().whoisDestinations)
        assertTrue(replyDestinations(ReplyDestination.WHOIS_DEFAULT, "red", WHOIS_BUFFER, "red::#room", "Alice").isEmpty())
        assertEquals(setOf("red::*server*", "red::*whois*", "red::#room"),
            replyDestinations(ReplyDestination.FLOATING or 1 or 2 or 8, "red", WHOIS_BUFFER, "red::#room", "Alice"))
        assertEquals(0, ReplyDestination.WHOIS_MASK and ReplyDestination.PRIVATE)
        assertEquals(0, ReplyDestination.NOTICE_MASK and ReplyDestination.FLOATING)
    }
    @Test fun existingDefaultMigratesButCustomDestinationsRemain() {
        assertEquals(32, migrateWhoisDestinations(3, 0))
        assertEquals(9, migrateWhoisDestinations(9, 0))
        assertEquals(3, migrateWhoisDestinations(3, 2))
        assertEquals(33, migrateWhoisDestinations(5, 0))
    }
    @Test fun whoisFieldsAreServerProvidedAndKeepEachRequestSeparate() {
        val blocks = WhoisBlocks()
        blocks.add("red", 1, "Alice", setOf("red::*server*"), "Alice", true,
            whoisFields("311", listOf("me", "Alice", "user", "host", "*"), "Real name", 1000))
        blocks.add("red", 2, "Bob", emptySet(), "Bob", true,
            whoisFields("311", listOf("me", "Bob", "other", "elsewhere", "*"), "Bob name", 1000))
        blocks.add("red", 1, "Alice", emptySet(), "idle", false,
            whoisFields("317", listOf("me", "Alice", "125", "400"), null, 1000))
        val alice = blocks.finish("red", 1)!!
        assertTrue(alice.floating)
        assertEquals(setOf("red::*server*"), alice.destinations)
        assertEquals("host", alice.fields["host"])
        assertEquals("2m 5s", alice.fields["idle"])
        assertEquals("10m 0s", alice.fields["connected"])
        assertEquals("elsewhere", blocks.finish("red", 2)!!.fields["host"])
    }
    @Test fun absentOrInvalidIdleAndSignonAreNotInvented() {
        assertTrue(whoisFields("317", listOf("me", "Alice", "-1", "not-a-date"), null, 1000).isEmpty())
        assertTrue(whoisFields("317", listOf("me", "Alice"), null, 1000).isEmpty())
        assertFalse(whoisFields("317", listOf("me", "Alice", "0", "2000"), null, 1000).containsKey("connected"))
        val blocks = WhoisBlocks()
        blocks.add("red", 1, "Alice", emptySet(), null, true, mapOf("host" to "redacted"))
        assertTrue(blocks.finish("red", 1)!!.fields.isEmpty())
    }
    @Test fun noticeAndWhoisGroupingKeepNumericHooksIndependentOfPanel() {
        val blocks = WhoisBlocks()
        blocks.add("red", 1, "Alice", emptySet(), "rewritten", true)
        blocks.add("red", 1, "Alice", emptySet(), null, true)
        assertEquals(listOf("rewritten"), blocks.finish("red", 1)!!.lines)
    }
    @Test fun tokenCompletionPreservesBothSidesOfCursorAndStatusPrefixes() {
        val text = "Hola aleZZ resto"
        val token = nickToken(text, 8)!!
        assertEquals("ale", token.prefix)
        val (result, cursor) = completeNickToken(text, token, "@Alex")
        assertEquals("Hola Alex resto", result)
        assertEquals(9, cursor)
        assertEquals("Alex: ", completeNickToken("ale", nickToken("ale", 3)!!, "+Alex").first)
        assertEquals("Hola @Alex resto", completeNickToken("Hola @ale resto", nickToken("Hola @ale resto", 9)!!, "Alex").first)
        assertNull(nickToken("hola ", 5))
    }
    @Test fun visibleMentionNotificationRequiresOptInAndExcludesOwnAndReplay() {
        assertFalse(mayNotifyVisibleMention(true, false, true, false, false))
        assertTrue(mayNotifyVisibleMention(true, true, true, false, false))
        assertFalse(mayNotifyVisibleMention(true, true, true, true, false))
        assertFalse(mayNotifyVisibleMention(true, true, true, false, true))
        assertFalse(mayNotifyVisibleMention(true, true, false, false, false))
        assertTrue(mayNotifyVisibleMention(false, false, true, false, false))
    }
    @Test fun greetingsIncludeOperatorsAndVoicesButExcludeSelfAndConfiguredBots() {
        assertEquals("Buenos días Alice Bob", greeting(listOf("@Alice", "+Bob", "Me", "@Bot", "Alice"), "me", setOf("bot")) { it.lowercase() })
        assertEquals("Buenos días", greeting(listOf("@Me"), "me", emptySet()) { it.lowercase() })
    }
    @Test fun scriptInterpreterReadsRealMembersAndNamesViaCallbacks() {
        val commands = mutableMapOf<String, Any>()
        val output = mutableListOf<String>()
        val channels = mutableListOf<String>()
        val callbacks = Proxy.newProxyInstance(EngineCallbacks::class.java.classLoader, arrayOf(EngineCallbacks::class.java)) { _, method, args ->
            when (method.name) {
                "registerCommand" -> { commands[args[0] as String] = args[1]; Unit }
                "members" -> { channels += args[0] as String; listOf("@Alice", "+Bob", "me") }
                "echo" -> { output += args[2] as String; Unit }
                "log" -> Unit
                else -> null
            }
        } as EngineCallbacks
        val backend = HexScriptBackend()
        backend.start(callbacks, Budget(10000, 1000))
        val dollar = '$'
        assertNull(backend.loadScript("members.hex", "alias members_test {\n echo -a ${dollar}members(#room)\n echo -a ${dollar}membernames()\n}"))
        backend.runCommand(commands["members_test"]!!, "", "red", "#room")
        assertEquals(listOf("#room", "#room"), channels)
        assertEquals(listOf("@Alice +Bob me", "Alice Bob me"), output)
    }
    @Test fun lateIdleUpdatesTheSameMinimizedWindowWithoutLosingHostOrPosition() {
        val old = WhoisWindow(1, "red", "Alice", mapOf("host" to "host.example"), listOf("311"), minimized = true, offsetX = 12f, offsetY = 40f)
        val other = WhoisWindow(2, "red", "Bob", emptyMap(), listOf("Bob"))
        val late = WhoisWindow(1, "red", "Alice", mapOf("idle" to "2m 0s"), listOf("317", "318"))
        val merged = mergeWhoisWindow(listOf(old, other), late)
        assertEquals(2, merged.size)
        assertEquals(mapOf("host" to "host.example", "idle" to "2m 0s"), merged.first().fields)
        assertEquals(listOf("311", "317", "318"), merged.first().lines)
        assertTrue(merged.first().minimized)
        assertEquals(40f, merged.first().offsetY)
        assertEquals(other, merged.last())
    }

}
