/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.boxlabs.hexdroid.data.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Exercise the actual receive/decision path, without external network credentials or a live server. */
class PersonalPhase3PrivateFlowTest {
    @Test fun phase3RealPrivateFlowRetainsPendingAndAcceptsRejectsIgnoresWithoutBlocking() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val repo = SettingsRepository(ctx)
        val original = repo.settingsFlow.first()
        val originalNetworks = repo.networksFlow.first()
        val netId = "phase3-demo-${java.util.UUID.randomUUID()}"
        val profile = repo.defaultNetworks().first().copy(id = netId, name = "Red de prueba", host = "irc.example.invalid", autoConnect = false,
            ignoredNicks = listOf("IgnoredDemoNick"))
        val store = ViewModelStore()
        lateinit var vm: IrcViewModel
        var created = false
        suspend fun until(condition: () -> Boolean) { withTimeout(10_000) { while (!condition()) delay(25) } }
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun message(nick: String, id: String) = IrcEvent.ChatMessage(from = nick, target = nick, text = "Mensaje de prueba 🙂", isPrivate = true, msgId = id)
        try {
            repo.updateSettings { it.copy(loggingEnabled = false, notificationsEnabled = false).withPersonal { copy(privateRequestsEnabled = true, privateAutoMessages = false, encryptedLogEnabled = false) } }
            repo.upsertNetwork(profile)
            main { vm = IrcViewModel(repo, ctx); store.put("phase3", vm); created = true }
            until { vm.state.value.settingsLoaded && vm.state.value.networks.any { it.id == netId } && vm.state.value.settings.privateRequestsEnabled }
            main {
                vm.handleMessageEvent(netId, message("IgnoredDemoNick", "ignored-demo-1"))
                vm.handleMessageEvent(netId, message("DemoNick01", "pending-demo-1"))
                vm.handleMessageEvent(netId, message("DemoNick01", "pending-demo-2"))
            }
            until { vm.state.value.privateRequests.any { it.network == netId && it.nick == "DemoNick01" && it.count == 2 } }
            assertFalse(vm.state.value.buffers.keys.any { it.startsWith("$netId::") && it.substringAfter("::").equals("DemoNick01", true) })
            assertFalse(vm.state.value.privateRequests.any { it.nick == "IgnoredDemoNick" })
            val pending = vm.state.value.privateRequests.first { it.network == netId && it.nick == "DemoNick01" }
            main { vm.resolvePrivateRequest(pending, "ACCEPTED") }
            until { vm.state.value.privateRequests.none { it.id == pending.id } }
            val acceptedBuffer = vm.state.value.buffers.entries.first { it.key.substringAfter("::").equals("DemoNick01", true) }.value
            assertEquals(2, acceptedBuffer.messages.count { it.from == "DemoNick01" })
            main { vm.handleMessageEvent(netId, message("DemoNick01", "accepted-demo-3")) }
            until { vm.state.value.buffers.values.any { it.messages.any { m -> m.msgId == "accepted-demo-3" } } }
            assertTrue(vm.state.value.privateRequests.none { it.network == netId && it.nick == "DemoNick01" })
            for ((nick, decision) in listOf("DemoNick02" to "REJECTED", "DemoNick03" to "IGNORED")) {
                main { vm.handleMessageEvent(netId, message(nick, "$nick-demo-1")) }
                until { vm.state.value.privateRequests.any { it.network == netId && it.nick == nick } }
                val request = vm.state.value.privateRequests.first { it.network == netId && it.nick == nick }
                main { vm.resolvePrivateRequest(request, decision, "Motivo de prueba") }
                until { vm.state.value.privateRequests.none { it.id == request.id } }
                assertFalse(vm.state.value.buffers.keys.any { it.startsWith("$netId::") && it.substringAfter("::").equals(nick, true) })
            }
            assertEquals(listOf("IgnoredDemoNick"), vm.state.value.networks.first { it.id == netId }.ignoredNicks)
            repo.updateSettings { it.copy(loggingEnabled = true) }
            until { vm.state.value.settings.loggingEnabled }
            assertEquals("$netId::*server*", vm.privateProtocolStatusKey(netId, "EncryptedDemoNick"))
            main { vm.appendIncomingAgeChat("EncryptedDemoNick", "EncryptedDemoNick", "Texto cifrado de prueba 1", netId) }
            until { vm.state.value.privateRequests.any { it.network == netId && it.nick == "EncryptedDemoNick" } }
            assertFalse(vm.state.value.buffers.keys.any { it.startsWith("$netId::") && it.substringAfter("::").equals("EncryptedDemoNick", true) })
            val encrypted = vm.state.value.privateRequests.first { it.network == netId && it.nick == "EncryptedDemoNick" }
            assertEquals(com.boxlabs.hexdroid.crypto.E2eScheme.AGE, vm.privateRequests.messages(encrypted.id).single().encryption)
            main { vm.resolvePrivateRequest(encrypted, "ACCEPTED") }
            until { vm.state.value.privateRequests.none { it.id == encrypted.id } }
            assertFalse(vm.historyArchive.list().any { it.path.startsWith(vm.personalLogNetwork(netId) + "/") })
            repo.updateSettings { it.withPersonal { copy(encryptedLogEnabled = true) } }
            until { vm.state.value.settings.encryptedLogEnabled }
            main { vm.appendIncomingAgeChat("EncryptedDemoNick", "EncryptedDemoNick", "Texto cifrado de prueba 2", netId) }
            until { vm.historyArchive.list().any { it.path.startsWith(vm.personalLogNetwork(netId) + "/") } }
            val encryptedLog = vm.historyArchive.list().first { it.path.startsWith(vm.personalLogNetwork(netId) + "/") }
            assertTrue(HistoryFiles.resolve(vm.historyArchive.root, encryptedLog.path).readText(Charsets.UTF_8).contains("Texto cifrado de prueba 2"))
            repo.updateSettings { it.copy(loggingEnabled = false).withPersonal { copy(privateRequestsEnabled = false) } }

            until { !vm.state.value.settings.privateRequestsEnabled }
            main { vm.handleMessageEvent(netId, message("DemoNick04", "normal-demo-1")) }
            until { vm.state.value.buffers.values.any { it.messages.any { m -> m.msgId == "normal-demo-1" } } }
            assertFalse(vm.state.value.privateRequests.any { it.network == netId && it.nick == "DemoNick04" })
        } finally {
            if (created) {
                main { store.clear() }
                vm.historyArchive.delete(vm.historyArchive.list().filter { it.path.startsWith(vm.personalLogNetwork(netId) + "/") }.map { it.path }.toSet())
                vm.privateRequests.close(); vm.historyArchive.close()
            }
            repo.saveNetworks(originalNetworks)
            repo.updateSettings { original }
        }
    }
}
