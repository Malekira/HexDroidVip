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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal const val TEST_TIMEOUT_MS = 8_000L

/** What a test server sends once the client ends capability negotiation. */
internal val DEFAULT_WELCOME = listOf(
    ":srv 001 me :Welcome",
    ":srv 005 me CHANTYPES=# PREFIX=(ov)@+ :are supported",
    ":srv 376 me :End of MOTD",
)

/**
 * A local IRC server that offers [caps], sends [welcome] after CAP END, answers PING, and
 * records every other line.
 */
internal class FakeIrcServer(private val caps: String, private val welcome: List<String> = DEFAULT_WELCOME) {
    private val socket = ServerSocket(0)
    val port: Int get() = socket.localPort
    private lateinit var out: BufferedWriter
    private val received = LinkedBlockingQueue<String>()

    init {
        thread(isDaemon = true) {
            val s = runCatching { socket.accept() }.getOrNull() ?: return@thread
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            out = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            while (true) handle(reader.readLine() ?: break)
        }
    }

    fun send(line: String) = synchronized(this) { out.write(line + "\r\n"); out.flush() }

    private fun handle(line: String) {
        val cmd = line.split(' ').dropWhile { it.startsWith("@") }.firstOrNull()
        when {
            line.startsWith("CAP LS") -> send(":srv CAP * LS :$caps")
            line.startsWith("CAP REQ") -> send(":srv CAP * ACK :${line.substringAfter(':')}")
            line == "CAP END" -> welcome.forEach { send(it) }
            cmd == "PING" -> send(":srv PONG srv :${line.substringAfter(':')}")
            else -> received.add(line)
        }
    }

    /** The next received line matching [pred]; lines before it are skipped. */
    fun expect(pred: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + TEST_TIMEOUT_MS
        while (true) {
            val left = deadline - System.currentTimeMillis()
            check(left > 0) { "no matching line from the client" }
            val line = received.poll(left, TimeUnit.MILLISECONDS) ?: continue
            if (pred(line)) return line
        }
    }

    /** Every line received so far that was not yet taken by [expect]. */
    fun pending(): List<String> = received.toList()

    fun close() = runCatching { socket.close() }
}

/** An [IrcClient] connected to a [FakeIrcServer], with every event it emits collected. */
internal class IrcTestHarness(caps: String, welcome: List<String> = DEFAULT_WELCOME) {
    val server = FakeIrcServer(caps, welcome)
    val client = IrcClient(
        IrcConfig(
            host = "127.0.0.1", port = server.port, useTls = false, allowInvalidCerts = false,
            nick = "me", altNick = null, username = "u", realname = "r",
            // Skips flood pacing, so the test doesn't wait on it.
            isBouncer = true,
        )
    )
    val events = CopyOnWriteArrayList<IrcEvent>()
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        client.strings = { id, _ -> "s$id" }
        client.plurals = { id, q, _ -> "p$id/$q" }
        scope.launch { client.events().collect { events.add(it) } }
        waitFor { client.hasCap("echo-message") && events.any { it is IrcEvent.ISupport } }
    }

    fun waitFor(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TEST_TIMEOUT_MS
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for an event" }
            Thread.sleep(10)
        }
    }

    inline fun <reified T : IrcEvent> await(crossinline pred: (T) -> Boolean): T {
        var found: T? = null
        waitFor { found = events.filterIsInstance<T>().firstOrNull { pred(it) }; found != null }
        return found!!
    }

    /** Sends a notice through the server and waits for it, so everything before it was handled. */
    fun sync(marker: String) {
        server.send(":srv NOTICE me :$marker")
        await<IrcEvent.Notice> { it.text == marker }
    }

    fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    fun close() {
        scope.cancel()
        client.forceClose()
        server.close()
    }
}
