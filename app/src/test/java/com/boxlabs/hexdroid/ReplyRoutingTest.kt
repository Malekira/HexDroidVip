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

import org.junit.Assert.*
import org.junit.Test

class ReplyRoutingTest {
    private val status = "red::*server*"
    private fun route(mask: Int, selected: String = "red::#sala", nick: String? = "Alice", server: Boolean = false) =
        replyDestinations(mask, "red", NOTICES_BUFFER, selected, nick, serverNotice = server)

    @Test fun noticeAppearsInStatusPrivateAndActive() {
        assertEquals(setOf(status, "red::Alice", "red::#sala"), route(1 or 4 or 8))
    }
    @Test fun duplicateDestinationsCollapseIntoOneBuffer() {
        assertEquals(setOf(status), route(1 or 8, selected = status))
        assertEquals(setOf("red::Alice"), route(4 or 8, selected = "red::Alice"))
    }
    @Test fun otherNetworksNeverReceiveCopies() {
        assertEquals(setOf(status, "red::Alice"), route(1 or 4 or 8, selected = "otra::#sala"))
    }
    @Test fun serverNoticeNeverCreatesAPrivateConversation() {
        assertEquals(setOf(status, "red::#sala", "red::$NOTICES_BUFFER"), route(2 or 4 or 8, nick = "irc.example.org", server = true))
        assertEquals(setOf(status), route(4, nick = "BouncerServ", server = true))
    }
    @Test fun missingAndPseudoSendersFallBackToStatus() {
        for (nick in listOf(null, "", "*status", "#canal", "nick!user@host", "irc.example.org", "Nick with space", "AUTH" + '\n')) {
            assertEquals(nick, setOf(status), route(4, nick = nick))
        }
        assertEquals(setOf("red::NickServ"), route(4, nick = "NickServ"))
        assertTrue(isReplyNick("[Alice]-2"))
    }
    @Test fun originalNoticeRulesCanBeCombinedWithExplicitDestinations() {
        assertEquals(setOf(status, "red::#origin"), replyDestinations(1 or 16, "red", NOTICES_BUFFER, "otra::#x", "Alice", "red::#origin"))
    }
    @Test fun whoisFromAChannelReachesStatusAndWhoisWindow() {
        assertEquals(setOf(status, "red::$WHOIS_BUFFER"), replyDestinations(ReplyDestination.WHOIS_DEFAULT, "red", WHOIS_BUFFER, "red::#sala", "Alice"))
    }
    @Test fun caseMappingResolutionPreventsDuplicatePrivateCopies() {
        val destinations = replyDestinations(4 or 8, "red", WHOIS_BUFFER, "red::alice", "ALICE", resolve = { "red::" + it.lowercase() })
        assertEquals(setOf("red::alice"), destinations)
    }
    @Test fun emptyOrUnknownPreferenceValuesHaveSafeFallbacks() {
        assertEquals(3, ReplyDestination.sanitize(0, 15, 3))
        assertEquals(3, ReplyDestination.sanitize(128, 15, 3))
        assertEquals(8, ReplyDestination.sanitize(8 or 128, 15, 3))
        assertEquals(setOf(status), route(0))
    }
    @Test fun idleDurationUsesDaysAndDoesNotInventMissingSeconds() {
        assertNull(whoisDuration(null))
        assertNull(whoisDuration(-1))
        assertEquals("0s", whoisDuration(0))
        assertEquals("59s", whoisDuration(59))
        assertEquals("1m 0s", whoisDuration(60))
        assertEquals("1h 1m 1s", whoisDuration(3661))
        assertEquals("2d 1h 1m 1s", whoisDuration(176461))
        assertNotNull(whoisDuration(Long.MAX_VALUE))
    }
    @Test fun remoteIdleIsOptInAndPreservesExplicitServerAndMultiNickQueries() {
        assertEquals("whois Alice", whoisCommand("whois Alice", false))
        assertEquals("whois Alice Alice", whoisCommand("whois Alice", true))
        assertEquals("whois server Alice", whoisCommand("whois server Alice", true))
        assertEquals("whois Alice,Bob", whoisCommand("whois Alice,Bob", true))
    }
    @Test fun interleavedWhoisRequestsRetainTheirOwnOriginsUntil318() {
        val requests = WhoisRequests { 1000L }
        requests.remember("alice", "#one")
        requests.remember("bob", "#two")
        val alice = requests.receive("alice", "311")!!
        val bob = requests.receive("bob", "311")!!
        assertNotEquals(alice.id, bob.id)
        assertEquals("#one", alice.buffer)
        assertEquals("#two", bob.buffer)
        assertEquals(alice, requests.receive("alice", "317"))
        assertEquals(bob, requests.receive("bob", "318"))
        assertNull(requests.receive("bob", "311"))
        assertEquals(alice, requests.receive("alice", "318"))
        assertNull(requests.receive("alice", "317"))
    }
    @Test fun repeatedWhoisOfSameNickIsFifoAndExpiredRequestsAreNotReused() {
        var now = 0L
        val requests = WhoisRequests { now }
        requests.remember("alice", "#one")
        requests.remember("alice", "#two")
        assertEquals("#one", requests.receive("alice", "318")!!.buffer)
        assertEquals("#two", requests.receive("alice", "311")!!.buffer)
        now = 60_000
        assertNull(requests.receive("alice", "311"))
    }
    @Test fun whoisBlocksKeepInterleavedUsersSeparateAndSnapshotDestinations() {
        val blocks = WhoisBlocks()
        blocks.add("red", 1, "Alice", setOf(status), "Alice user@host")
        blocks.add("red", 2, "Bob", setOf("red::$WHOIS_BUFFER"), "Bob away")
        blocks.add("red", 1, "Alice", setOf("red::#changed"), "Alice idle 1m")
        // A script that halts numeric 318 still closes and publishes earlier lines.
        blocks.add("red", 1, "Alice", emptySet(), null)
        val alice = blocks.finish("red", 1)!!
        assertEquals(setOf(status), alice.destinations)
        assertEquals(listOf("Alice user@host", "Alice idle 1m"), alice.lines)
        assertEquals(listOf("Bob away"), blocks.finish("red", 2)!!.lines)
        assertNull(blocks.finish("red", 1))
    }
    @Test fun sameRequestIdOnDifferentNetworksDoesNotMixReplies() {
        val blocks = WhoisBlocks()
        blocks.add("red", 1, "Alice", setOf(status), "one")
        blocks.add("otra", 1, "Alice", setOf("otra::*server*"), "two")
        assertEquals(listOf("one"), blocks.finish("red", 1)!!.lines)
        assertEquals(listOf("two"), blocks.finish("otra", 1)!!.lines)
    }
    @Test fun replayedNoticeIsDeduplicatedIndependentlyInEveryDestination() {
        val destinations = route(1 or 4 or 8)
        val live = UiMessage(1, 100_000, null, "* <Alice> hello", msgId = "one")
        for (destination in destinations) {
            val first = BufferLog().insert(live, MessageOrigin.LIVE, 100, nowMs = 100_000)
            assertTrue(destination, first is BufferLog.Insert.Added)
            val replay = first.log.insert(live.copy(id = 2), MessageOrigin.REPLAY, 100, nowMs = 200_000)
            assertTrue(destination, replay is BufferLog.Insert.Duplicate)
            assertEquals(1, replay.log.messages.size)
        }
    }
    @Test fun historyWithoutMsgidStillDeduplicatesWithoutDroppingRepeatedLiveNotices() {
        val live = UiMessage(1, 100_000, null, "* <Alice> hello")
        val first = BufferLog().insert(live, MessageOrigin.LIVE, 100, nowMs = 100_000)
        assertTrue(first.log.insert(live.copy(id = 2), MessageOrigin.REPLAY, 100, nowMs = 200_000) is BufferLog.Insert.Duplicate)
        assertTrue(first.log.insert(live.copy(id = 3), MessageOrigin.LIVE, 100, nowMs = 100_000) is BufferLog.Insert.Added)
    }
    @Test fun failedWhoisWaitsForIts318WithoutClosingTheNextQuery() {
        val requests = WhoisRequests { 0L }
        requests.remember("alice", "#one")
        requests.remember("alice", "#two")
        val failure = requests.receive("alice", "401")!!
        assertEquals(failure, requests.receive("alice", "318"))
        assertEquals("#two", requests.receive("alice", "311")!!.buffer)
        requests.clear()
        assertNull(requests.receive("alice", "311"))
    }

    @Test fun reconnectingClientsCannotReuseAnUnfinishedWhoisId() {
        val before = WhoisRequests { 0L }
        val after = WhoisRequests { 0L }
        before.remember("alice", "#old")
        after.remember("alice", "#new")
        assertNotEquals(before.receive("alice", "311")!!.id, after.receive("alice", "311")!!.id)
    }

}
