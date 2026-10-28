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

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Matching of server replies to what the client sent: echoes, refusals, MODE and WHO queries. */
class IrcClientRepliesTest {

    @Test
    fun whoisFromChannelRetainsNickAndQueryUntil318() {
        val h = harness()
        h.client.strings = { id, args ->
            when (id) {
                R.string.personal_whois_idle -> "idle ${args[0]}"
                R.string.core_whois_signon -> "signon ${args[0]}"
                else -> "s$id"
            }
        }
        h.run { h.client.handleSlashCommand("whois Alice Alice", "#dreams") }
        h.server.expect { it == "WHOIS Alice Alice" }
        h.run { h.client.handleSlashCommand("whois Bob", "#other") }
        h.server.expect { it == "WHOIS Bob" }
        h.server.send(":srv 311 me Alice user host * :Alice Real")
        h.server.send(":srv 311 me Bob user host * :Bob Real")
        h.server.send(":srv 317 me Alice 176461 1700000000 :seconds idle, signon time")
        h.server.send(":srv 330 me Alice account :is logged in as")
        h.server.send(":srv 799 me Alice :custom WHOIS extension")
        h.server.send(":srv 318 me Bob :End of WHOIS")
        h.server.send(":srv 318 me Alice :End of WHOIS")
        val end = h.await<IrcEvent.ServerText> { it.code == "318" && it.whoisNick == "Alice" }
        val alice = h.events.filterIsInstance<IrcEvent.ServerText>().filter { it.whoisRequestId == end.whoisRequestId }
        assertEquals(listOf("311", "317", "330", "799", "318"), alice.map { it.code })
        assertTrue(alice.all { it.isWhoisReply && it.bufferName == "#dreams" && it.whoisNick == "Alice" })
        assertTrue(alice.single { it.code == "317" }.text.contains("2d 1h 1m 1s"))
        val bob = h.events.filterIsInstance<IrcEvent.ServerText>().filter { it.whoisNick == "Bob" }
        assertTrue(bob.all { it.bufferName == "#other" && it.whoisRequestId != end.whoisRequestId })
        h.server.send(":srv 301 me Alice :Away after WHOIS")
        h.sync("whois-finished")
        assertFalse(h.events.filterIsInstance<IrcEvent.ServerText>().last { it.code == "301" }.isWhoisReply)
    }

    @Test
    fun whoisWithout317DoesNotFabricateIdle() {
        val h = harness()
        h.run { h.client.handleSlashCommand("whois Alice", "#dreams") }
        h.server.expect { it == "WHOIS Alice" }
        h.server.send(":srv 311 me Alice user host * :Alice Real")
        h.server.send(":srv 318 me Alice :End of WHOIS")
        h.await<IrcEvent.ServerText> { it.code == "318" && it.whoisNick == "Alice" }
        assertFalse(h.events.filterIsInstance<IrcEvent.ServerText>().any { it.code == "317" })
    }

    @Test
    fun noticeMetadataAndServerIdentitySurviveForRoutingAndReplayDeduplication() {
        val h = harness()
        h.server.send("@msgid=notice-one;time=2026-01-01T00:00:00Z :Alice!u@host NOTICE me :hello")
        val notice = h.await<IrcEvent.Notice> { it.text == "hello" }
        assertEquals("Alice", notice.from)
        assertEquals("notice-one", notice.msgId)
        assertNotNull(notice.timeMs)
        assertFalse(notice.isServer)
        h.server.send(":irc.example.org NOTICE me :server notice")
        assertTrue(h.await<IrcEvent.Notice> { it.text == "server notice" }.isServer)
    }

    private val harnesses = mutableListOf<IrcTestHarness>()

    @Test fun callerIdAdvertisementAndBlockedNoticeRemainRealServerEvents() {
        val h = harness()
        h.server.send(":srv 005 me CALLERID=g :supported")
        h.sync("callerid-advertised")
        assertEquals("g", h.client.callerIdMode)
        h.server.send(":srv 718 me DemoNick01 demo@host.invalid :is messaging you while caller-id is enabled")
        val blocked = h.await<IrcEvent.ServerText> { it.code == "718" }
        assertEquals("DemoNick01", blocked.serverParams[1])
        assertFalse(blocked.isWhoisReply)
        h.server.send(":srv 005 me -CALLERID :supported")
        h.sync("callerid-withdrawn")
        assertNull(h.client.callerIdMode)
    }

    private fun harness(caps: String = LABELED) = IrcTestHarness(caps).also { harnesses += it }

    @After
    fun tearDown() = harnesses.forEach { it.close() }

    private fun labelOf(line: String) =
        Regex("""label=([^; ]+)""").find(line)?.groupValues?.get(1) ?: error("no label in $line")

    @Test
    fun labeledEchoCarriesTheSendId() {
        val h = harness()
        val id = h.run { h.client.privmsg("#c", "hello") }!!
        val label = labelOf(h.server.expect { it.endsWith("PRIVMSG #c :hello") })
        h.server.send("@label=$label :me!u@h PRIVMSG #c :hello")
        h.await<IrcEvent.ChatMessage> { it.text == "hello" && it.ownSendId == id }
    }

    @Test
    fun refusalFailsTheExactSend() {
        val h = harness()
        val id = h.run { h.client.privmsg("#x", "nope") }!!
        val label = labelOf(h.server.expect { it.endsWith("PRIVMSG #x :nope") })
        h.server.send("@label=$label :srv 404 me #x :Cannot send to channel")
        h.await<IrcEvent.SendResolved> { it.ids == listOf(id) && it.failed }
    }

    @Test
    fun laterEchoFinishesEarlierSendsToTheSameTarget() {
        val h = harness()
        val first = h.run { h.client.privmsg("#c", "first") }!!
        val second = h.run { h.client.privmsg("#c", "second") }!!
        h.server.expect { it.endsWith(":first") }
        val label = labelOf(h.server.expect { it.endsWith(":second") })
        h.server.send("@label=$label :me!u@h PRIVMSG #c :second")
        h.await<IrcEvent.SendResolved> { it.ids == listOf(first) && !it.failed }
        h.await<IrcEvent.ChatMessage> { it.ownSendId == second }
    }

    @Test
    fun labeledRepliesOtherThanTheEchoFinishASend() {
        val h = harness()
        val acked = h.run { h.client.privmsg("#c", "acked") }!!
        h.server.send("@label=${labelOf(h.server.expect { it.endsWith(":acked") })} :srv ACK")
        h.await<IrcEvent.SendResolved> { it.ids == listOf(acked) && !it.failed }

        val noticed = h.run { h.client.privmsg("bob", "are you there") }!!
        val l2 = labelOf(h.server.expect { it.endsWith(":are you there") })
        h.server.send("@label=$l2 :srv NOTICE me :You must be identified to message bob")
        h.await<IrcEvent.SendResolved> { it.ids == listOf(noticed) && !it.failed }

        val refused = h.run { h.client.privmsg("#bad", "x") }!!
        val l3 = labelOf(h.server.expect { it.endsWith("PRIVMSG #bad :x") })
        h.server.send("@label=$l3 :srv FAIL PRIVMSG INVALID_TARGET #bad :Invalid target")
        h.await<IrcEvent.SendResolved> { it.ids == listOf(refused) && it.failed }
    }

    @Test
    fun echoInsideALabeledBatchKeepsItsSendId() {
        val h = harness()
        val id = h.run { h.client.privmsg("bob", "ping") }!!
        val label = labelOf(h.server.expect { it.endsWith("PRIVMSG bob :ping") })
        h.server.send("@label=$label :srv BATCH +lb labeled-response")
        h.server.send("@batch=lb :srv 301 me bob :Gone for lunch")
        h.server.send("@batch=lb :me!u@h PRIVMSG bob :ping")
        h.server.send(":srv BATCH -lb")
        h.await<IrcEvent.ChatMessage> { it.ownSendId == id }
        assertFalse(h.events.any { it is IrcEvent.SendResolved && id in it.ids })
    }

    @Test
    fun msgEchoIsDroppedAfterTheLocalPrint() {
        val h = harness()
        h.run { h.client.handleSlashCommand("msg bob hi there", "#c") }
        val label = labelOf(h.server.expect { it.endsWith("PRIVMSG bob :hi there") })
        h.server.send("@label=$label :me!u@h PRIVMSG bob :hi there")
        h.sync("synced")
        assertFalse(h.events.any { it is IrcEvent.ChatMessage && it.text == "hi there" })
        assertTrue(h.events.any { it is IrcEvent.OutgoingEcho && it.text == "hi there" })
    }

    @Test
    fun pseudoUserMessageIsFinishedWhenShown() {
        val h = harness()
        val id = h.run { h.client.privmsg("*status", "help") }!!
        val label = labelOf(h.server.expect { it.endsWith("PRIVMSG *status :help") })
        h.server.send("@label=$label :me!u@h PRIVMSG *status :help")
        h.await<IrcEvent.SendResolved> { it.ids == listOf(id) && !it.failed }
    }

    @Test
    fun multilineEchoAndRejectionNameTheirSend() {
        val h = harness()
        val ok = h.run { h.client.privmsgMultiline("#c", listOf("alpha", "beta")) }!!
        val open = h.server.expect { it.contains("BATCH +") }
        h.server.expect { it.startsWith("BATCH -") }
        h.server.send("@label=${labelOf(open)} :me!u@h BATCH +zz draft/multiline #c")
        h.server.send("@batch=zz :me!u@h PRIVMSG #c :alpha")
        h.server.send("@batch=zz :me!u@h PRIVMSG #c :beta")
        h.server.send(":me!u@h BATCH -zz")
        h.await<IrcEvent.ChatMessage> { it.multiline && it.ownSendId == ok.single() && it.text == "alpha\nbeta" }

        val bad = h.run { h.client.privmsgMultiline("#c", listOf("one", "two")) }!!
        val open2 = h.server.expect { it.contains("BATCH +") }
        h.server.expect { it.startsWith("BATCH -") }
        h.server.send("@label=${labelOf(open2)} :srv FAIL BATCH MULTILINE_MAX_BYTES 4096 :Too long")
        h.await<IrcEvent.MultilineSendFailed> { it.sendIds == bad && it.target == "#c" }
    }

    @Test
    fun silentAndTypedModeQueriesGetTheirOwnReplies() {
        val h = harness()
        h.run { h.client.querySilentModes("#c") }
        h.server.expect { it == "MODE #c" }
        h.run { h.client.handleSlashCommand("mode #c", "#c") }
        h.server.expect { it == "MODE #c" }
        h.server.send(":srv 324 me #c +nt")
        h.server.send(":srv 329 me #c 1700000000")
        h.server.send(":srv 324 me #c +ntk key")
        h.server.send(":srv 329 me #c 1700000000")
        h.sync("synced")
        val modes = h.events.filterIsInstance<IrcEvent.ChannelModeIs>()
        assertTrue(modes.single { it.modes == "+nt" }.silent)
        assertFalse(modes.single { it.modes == "+ntk key" }.silent)
        // 324 is shown through ChannelModeIs; only the typed query's 329 is printed.
        val printed = h.events.filterIsInstance<IrcEvent.ServerText>().filter { it.code == "324" || it.code == "329" }
        assertEquals(listOf("329"), printed.map { it.code })
    }

    @Test
    fun modeReplyWithout329EndsAtTheNextLine() {
        val h = harness()
        h.run { h.client.querySilentModes("#c") }
        h.server.expect { it == "MODE #c" }
        h.run { h.client.handleSlashCommand("mode #c", "#c") }
        h.server.expect { it == "MODE #c" }
        h.server.send(":srv 324 me #c +s")
        h.server.send(":srv 324 me #c +si")
        h.server.send(":srv 329 me #c 1700000000")
        h.sync("synced")
        val modes = h.events.filterIsInstance<IrcEvent.ChannelModeIs>()
        assertTrue(modes.single { it.modes == "+s" }.silent)
        assertFalse(modes.single { it.modes == "+si" }.silent)
    }

    @Test
    fun modeErrorEndsTheQuery() {
        val h = harness()
        h.run { h.client.querySilentModes("#gone") }
        h.server.expect { it == "MODE #gone" }
        h.server.send(":srv 403 me #gone :No such channel")
        h.run { h.client.handleSlashCommand("mode #gone", "#c") }
        h.server.expect { it == "MODE #gone" }
        h.server.send(":srv 324 me #gone +n")
        h.server.send(":srv 329 me #gone 1700000000")
        assertFalse(h.await<IrcEvent.ChannelModeIs> { it.channel == "#gone" }.silent)
    }

    @Test
    fun whoRepliesGoToTheWhoThatAsked() {
        val h = harness()
        h.run { h.client.refreshChannelWho("#c") }
        h.run { h.client.handleSlashCommand("who #d", "#c") }
        repeat(2) {
            val chan = h.server.expect { it.startsWith("WHO ") }.split(' ')[1]
            h.server.send(":srv 352 me $chan user host srv nick$chan H :0 Real")
            h.server.send(":srv 315 me $chan :End of WHO list")
        }
        h.sync("synced")
        val printed = h.events.filterIsInstance<IrcEvent.ServerText>().filter { it.code == "352" || it.code == "315" }
        assertEquals(2, printed.size)
        assertTrue(printed.all { it.bufferName == "#c" && "#d" in it.text })
    }

    @Test
    fun nickWhoIsNotMistakenForALaterChannelRefresh() {
        val h = harness()
        h.run { h.client.handleSlashCommand("who carol", "#c") }
        h.server.expect { it == "WHO carol" }
        h.run { h.client.refreshChannelWho("#foo") }
        h.server.expect { it == "WHO #foo" }
        h.server.send(":srv 352 me #foo cu ch srv carol H :0 Carol")
        h.server.send(":srv 315 me carol :End of WHO list")
        h.server.send(":srv 352 me #foo u h srv dave H :0 Dave")
        h.server.send(":srv 315 me #foo :End of WHO list")
        h.sync("synced")
        val printed = h.events.filterIsInstance<IrcEvent.ServerText>().filter { it.code == "352" || it.code == "315" }
        assertEquals(2, printed.size)
        assertTrue(printed.any { "carol" in it.text } && printed.none { "dave" in it.text })
    }

    @Test
    fun unansweredRefreshDoesNotSwallowATypedWho() {
        val h = harness()
        h.run { h.client.refreshChannelWho("#quiet") }
        h.server.expect { it == "WHO #quiet" }
        h.run { h.client.handleSlashCommand("who #e", "#c") }
        h.server.expect { it == "WHO #e" }
        h.server.send(":srv 352 me #e u h srv erin H :0 Erin")
        h.server.send(":srv 315 me #e :End of WHO list")
        h.sync("synced")
        val printed = h.events.filterIsInstance<IrcEvent.ServerText>().filter { it.code == "352" || it.code == "315" }
        assertEquals(2, printed.size)
        assertTrue(printed.all { "#e" in it.text })
    }

    @Test
    fun unlabeledEchoMatchesByTextIgnoringFormatting() {
        val h = harness(UNLABELED)
        val id = h.run { h.client.privmsg("#c", "\u0002bold\u0002 text") }!!
        h.server.expect { it.contains("PRIVMSG #c") }
        h.server.send(":me!u@h PRIVMSG #c :bold text")
        h.await<IrcEvent.ChatMessage> { it.ownSendId == id }
        h.server.send(":me!u@h PRIVMSG #c :typed elsewhere")
        h.await<IrcEvent.ChatMessage> { it.text == "typed elsewhere" && it.ownSendId == null }
    }

    @Test
    fun unlabeledRefusalsFailTheOldestSendToThatTarget() {
        val h = harness(UNLABELED)
        val a = h.run { h.client.privmsg("#x", "one") }!!
        val b = h.run { h.client.privmsg("#x", "two") }!!
        h.server.expect { it.endsWith(":one") }
        h.server.expect { it.endsWith(":two") }
        h.server.send(":srv 404 me #x :Cannot send to channel")
        h.await<IrcEvent.SendResolved> { it.ids == listOf(a) && it.failed }
        h.server.send(":srv 404 me #x :Cannot send to channel")
        h.await<IrcEvent.SendResolved> { it.ids == listOf(b) && it.failed }
        val c = h.run { h.client.privmsg("#c", "too fast") }!!
        h.server.expect { it.endsWith(":too fast") }
        h.server.send(":srv 263 me PRIVMSG :Please wait a while and try again.")
        h.await<IrcEvent.SendResolved> { it.ids == listOf(c) && it.failed }
    }

    @Test
    fun ctcpReplyEchoIsDropped() {
        val h = harness(UNLABELED)
        h.server.send(":bob!u@h PRIVMSG me :\u0001PING 123\u0001")
        val reply = h.server.expect { it.startsWith("NOTICE bob :") }
        h.server.send(":me!u@h $reply")
        h.sync("synced")
        assertFalse(h.events.any { it is IrcEvent.Notice && it.from == "me" && "PING 123" in it.text })
    }

    @Test
    fun unlabeledMultilineEchoMatchesByItsJoinedText() {
        val h = harness("echo-message batch message-tags draft/multiline=max-bytes=4096,max-lines=24")
        val ids = h.run { h.client.privmsgMultiline("#c", listOf("first line  ", "", "third")) }!!
        h.server.expect { it.startsWith("BATCH -") }
        h.server.send(":me!u@h BATCH +q draft/multiline #c")
        h.server.send("@batch=q :me!u@h PRIVMSG #c :first line")
        h.server.send("@batch=q :me!u@h PRIVMSG #c :")
        h.server.send("@batch=q :me!u@h PRIVMSG #c :third")
        h.server.send(":me!u@h BATCH -q")
        h.await<IrcEvent.ChatMessage> { it.multiline && it.ownSendId == ids.single() }
    }

    @Test
    fun sendIdsAreUniqueAcrossConnections() {
        val one = harness()
        val two = harness()
        val a = one.run { one.client.privmsg("#c", "a") }
        val b = two.run { two.client.privmsg("#c", "b") }
        assertNotEquals(a, b)
    }

    private companion object {
        const val LABELED = "echo-message labeled-response batch message-tags server-time " +
            "draft/multiline=max-bytes=4096,max-lines=24"
        const val UNLABELED = "echo-message batch message-tags"
    }
}
