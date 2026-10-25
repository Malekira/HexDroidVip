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

import com.boxlabs.hexdroid.data.NetworkProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** soju extensions: bouncer networks, BOUNCER_NETID and SAFERATE, shared mutes, search, account-required. */
class IrcClientSojuTest {

    private val harnesses = mutableListOf<IrcTestHarness>()

    private fun soju(caps: String = SOJU_CAPS, isupport: String = "BOUNCER_NETID=3 SOJU.IM/SAFERATE") =
        IrcTestHarness(
            caps,
            listOf(
                ":srv 001 me :Welcome",
                ":srv 005 me CHANTYPES=# PREFIX=(ov)@+ $isupport :are supported",
                ":srv 422 me :No MOTD",
            ),
        ).also { harnesses += it }

    @After
    fun tearDown() = harnesses.forEach { it.close() }

    private fun labelOf(line: String) =
        Regex("""label=([^; ]+)""").find(line)?.groupValues?.get(1) ?: error("no label in $line")

    @Test
    fun bouncerNetworkCarriesEveryAttribute() {
        val h = soju()
        h.server.send(":srv BOUNCER NETWORK 5 name=libera;host=irc.libera.chat;port=6697;tls=1;state=connected;realname=Jo\\sDoe;error")
        val ev = h.await<IrcEvent.BouncerNetwork> { it.networkId == "5" }
        assertEquals("libera", ev.name)
        assertEquals("irc.libera.chat", ev.host)
        assertEquals("6697", ev.attrs["port"])
        assertEquals("1", ev.attrs["tls"])
        assertEquals("Jo Doe", ev.attrs["realname"])
        assertEquals(setOf("error"), ev.clearedKeys)

        h.server.send(":srv BOUNCER NETWORK 5 *")
        h.await<IrcEvent.BouncerNetwork> { it.networkId == "5" && it.removed }
    }

    @Test
    fun addNetworkEncodesAttributesAndReportsTheNewId() {
        val h = soju()
        h.run { h.client.bouncerAddNetwork(linkedMapOf("host" to "irc.example.org", "name" to "my net;x")) }
        val line = h.server.expect { it.startsWith("BOUNCER ADDNETWORK") }
        assertEquals("BOUNCER ADDNETWORK host=irc.example.org;name=my\\snet\\:x", line)
        h.server.send(":srv BOUNCER ADDNETWORK 7")
        h.await<IrcEvent.BouncerCommandResult> { it.subcommand == "ADDNETWORK" && it.networkId == "7" && !it.failed }
    }

    @Test
    fun failBouncerBecomesAResultNotAnError() {
        val h = soju()
        h.server.send(":srv FAIL BOUNCER UNKNOWN_ATTRIBUTE ADDNETWORK colour :Unknown attribute")
        val attr = h.await<IrcEvent.BouncerCommandResult> { it.failed && it.subcommand == "ADDNETWORK" }
        assertEquals("UNKNOWN_ATTRIBUTE", attr.code)
        assertEquals("colour", attr.attribute)

        h.server.send(":srv FAIL BOUNCER INVALID_NETID DELNETWORK 9 :Invalid network ID")
        val netid = h.await<IrcEvent.BouncerCommandResult> { it.failed && it.subcommand == "DELNETWORK" }
        assertEquals("9", netid.networkId)
        assertNull(netid.attribute)
        h.sync("done")
        assertFalse(h.events.any { it is IrcEvent.Error })
    }

    @Test
    fun isupportReportsTheBindingAndSafeRate() {
        val h = soju()
        val ev = h.await<IrcEvent.ISupport> { true }
        assertEquals("3", ev.bouncerNetId)
        assertTrue(ev.safeRate)
        assertEquals("3", h.client.bouncerNetId)
        assertTrue(h.client.isSoju)
    }

    @Test
    fun sojuIsAskedOnlyForItsMuteKeyOnceBound() {
        val h = soju()
        assertEquals("METADATA * SUB soju.im/muted", h.server.expect { it.startsWith("METADATA") })
        h.server.send(":srv METADATA #chan soju.im/muted * 1")
        h.server.send(":srv 770 me soju.im/muted")
        h.await<IrcEvent.MetadataChanged> { it.target == "#chan" && it.key == "soju.im/muted" && it.value == "1" }
        h.await<IrcEvent.MetadataSubscribed> { it.key == "soju.im/muted" }
    }

    @Test
    fun anUnboundSojuConnectionSubscribesToNothing() {
        val h = soju(isupport = "SOJU.IM/SAFERATE")
        h.sync("registered")
        assertFalse(h.server.pending().any { it.startsWith("METADATA") })
    }

    @Test
    fun searchResultsAreCollectedNotShown() {
        val h = soju()
        val id = h.run { h.client.searchMessages("#chan", "hello world", limit = 50) }!!
        val line = h.server.expect { it.contains("SEARCH ") }
        assertTrue(line, line.endsWith("SEARCH in=#chan;text=hello\\sworld;limit=50"))
        val label = labelOf(line)
        assertEquals(id, label)
        h.server.send("@label=$label :srv BATCH +1 labeled-response")
        h.server.send("@batch=1 :srv BATCH +2 soju.im/search")
        h.server.send("@batch=2;time=2026-01-02T03:04:05.000Z;msgid=abc :bob!b@h PRIVMSG #chan :hello world")
        h.server.send("@batch=2;time=2026-01-02T03:04:06.000Z;msgid=def :me!u@h PRIVMSG #chan :\u0001ACTION says hello world\u0001")
        h.server.send("@batch=1 :srv BATCH -2")
        h.server.send(":srv BATCH -1")
        val res = h.await<IrcEvent.SearchResults> { it.requestId == id }
        assertFalse(res.failed)
        assertEquals(2, res.hits.size)
        assertEquals("bob", res.hits[0].from)
        assertEquals("abc", res.hits[0].msgId)
        assertEquals("#chan", res.hits[0].target)
        assertEquals(1767323045000L, res.hits[0].timeMs)
        assertTrue(res.hits[1].isAction)
        assertEquals("says hello world", res.hits[1].text)
        h.sync("after")
        assertFalse(h.events.any { it is IrcEvent.ChatMessage && it.text.contains("hello world") })
    }

    @Test
    fun unlabeledSearchesAreAnsweredInOrder() {
        val h = soju(caps = SOJU_CAPS.replace("labeled-response ", ""))
        val first = h.run { h.client.searchMessages("#a", "x") }!!
        val second = h.run { h.client.searchMessages("#b", "y") }!!
        h.server.expect { it.startsWith("SEARCH in=#a") }
        h.server.expect { it.startsWith("SEARCH in=#b") }
        h.server.send(":srv FAIL SEARCH INTERNAL_ERROR :Messages could not be retrieved")
        h.server.send(":srv BATCH +s soju.im/search")
        h.server.send("@batch=s;time=2026-01-02T03:04:05.000Z :bob!b@h PRIVMSG #b :y")
        h.server.send(":srv BATCH -s")
        val failed = h.await<IrcEvent.SearchResults> { it.requestId == first }
        assertTrue(failed.failed)
        assertEquals("INTERNAL_ERROR", failed.code)
        val ok = h.await<IrcEvent.SearchResults> { it.requestId == second }
        assertEquals(listOf("y"), ok.hits.map { it.text })
    }

    @Test
    fun accountRequiredStopsReconnecting() {
        val h = soju()
        h.server.send(":srv FAIL * ACCOUNT_REQUIRED :Authentication required")
        h.await<IrcEvent.AuthFailed> { it.source == "ACCOUNT_REQUIRED" && it.reason == "Authentication required" }
    }

    @Test
    fun zncRowsGiveHostPortAndTls() {
        val row = parseZncListNetworksLine("| libera | Yes | irc.libera.chat:+6697 | me!u@h |")!!
        assertEquals("libera", row.networkId)
        assertEquals("irc.libera.chat", row.host)
        assertEquals("6697", row.attrs["port"])
        assertEquals("1", row.attrs["tls"])
        assertEquals("connected", row.state)
        val plain = parseZncListNetworksLine("| oftc | No | irc.oftc.net:6667 | |")!!
        assertEquals("0", plain.attrs["tls"])
        assertEquals("disconnected", plain.state)
    }

    @Test
    fun editsSendOnlyWhatChanged() {
        val current = BouncerUpstreamInfo(
            "5",
            mapOf("name" to "libera", "host" to "irc.libera.chat", "port" to "6697", "tls" to "1", "nickname" to "me"),
        )
        val draft = current.toDraft().copy(name = "Libera", port = "", nickname = "", pass = "pw")
        assertEquals(
            mapOf("name" to "Libera", "port" to "", "nickname" to "", "pass" to "pw"),
            draft.toChangeAttrs(current),
        )
        assertEquals(emptyMap<String, String>(), current.toDraft().toChangeAttrs(current))
        assertEquals(
            mapOf("host" to "irc.example.org", "tls" to "0", "name" to "ex"),
            BouncerNetworkDraft(name = " ex ", host = "irc.example.org", tls = false).toAddAttrs(),
        )
    }

    @Test
    fun importedProfilesMatchByNetidOrName() {
        val parent = profile("p", networkName = null)
        val upstream = BouncerUpstreamInfo("5", mapOf("name" to "libera"))
        assertTrue(profile("a", networkName = "libera").importsUpstream(parent, upstream))
        assertTrue(profile("b", networkName = "old", networkId = "5").importsUpstream(parent, upstream))
        assertFalse(profile("c", networkName = "oftc", networkId = "6").importsUpstream(parent, upstream))
        assertFalse(profile("d", networkName = "libera").copy(port = 6698).importsUpstream(parent, upstream))
    }

    @Test
    fun searchQueryKeepsTheNickAsTyped() {
        val q = LogSearch.parse("from:<@Alice> hello")
        assertEquals("alice", q.from)
        assertEquals("Alice", q.fromTyped)
    }

    private fun profile(id: String, networkName: String?, networkId: String? = null) = NetworkProfile(
        id = id, name = id, host = "bnc.example", port = 6697, useTls = true, allowInvalidCerts = false,
        nick = "me", altNick = null, username = "u", realname = "r",
        saslEnabled = false, saslMechanism = SaslMechanism.PLAIN, caps = CapPrefs(), autoJoin = emptyList(),
        bouncerKind = BouncerKind.SOJU, bouncerNetworkName = networkName, bouncerNetworkId = networkId,
    )

    private companion object {
        const val SOJU_CAPS = "echo-message labeled-response batch message-tags server-time " +
            "draft/metadata-2=before-connect,max-keys=0,max-value-bytes=1 " +
            "soju.im/bouncer-networks soju.im/bouncer-networks-notify soju.im/search soju.im/account-required"
    }
}
