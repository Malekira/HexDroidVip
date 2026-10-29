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
        val scriptName = "phase3-greeting-test-${java.util.UUID.randomUUID()}.hex"
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

            val room = "$netId::#demo"
            val status = "$netId::*server*"
            fun caller(nick: String) = IrcEvent.ServerText("$nick demo@example.invalid intentó enviar un privado", code = "718",
                whoisParams = listOf("OwnDemoNick", nick, "demo@example.invalid"))
            main {
                vm.openBuffer(room)
                vm.handleServerEvent(netId, caller("CallerDemo01"))
                vm.handleServerEvent(netId, caller("CallerDemo01"))
            }
            until { vm.state.value.privateRequests.any { it.network == netId && it.nick == "CallerDemo01" } }
            val roomNotice = vm.state.value.buffers.getValue(room).messages.single { "CallerDemo01" in it.text }
            assertTrue(roomNotice.isHighlightedNotice)
            assertFalse(roomNotice.isMention)
            assertNull(roomNotice.from)
            assertTrue("+g" in roomNotice.text)
            assertEquals(1, vm.state.value.buffers.getValue(status).messages.count { "CallerDemo01" in it.text })
            assertFalse(vm.state.value.buffers.containsKey("$netId::CallerDemo01"))
            main {
                vm.openBuffer(status)
                vm.handleServerEvent(netId, caller("CallerDemo02"))
            }
            until { vm.state.value.privateRequests.any { it.network == netId && it.nick == "CallerDemo02" } }
            assertTrue(vm.state.value.buffers.getValue(status).messages.single { "CallerDemo02" in it.text }.isHighlightedNotice)
            val foreign = "foreign-$netId::#foreign-demo"
            main {
                vm.openBuffer(foreign)
                vm.handleServerEvent(netId, caller("CallerDemo03"))
                vm.handleServerEvent(netId, caller("IgnoredDemoNick"))
            }
            until { vm.state.value.privateRequests.any { it.network == netId && it.nick == "CallerDemo03" } }
            assertFalse(vm.state.value.buffers.getValue(foreign).messages.any { "CallerDemo" in it.text })
            assertFalse(vm.state.value.privateRequests.any { it.nick == "IgnoredDemoNick" })
            assertEquals(1, vm.state.value.buffers.getValue(status).messages.count { "CallerDemo03" in it.text })

            // Exercise imported .hex aliases through the actual composer dispatch, offline.
            val dollar = '$'
            val source = "alias buenosdias {\n echo -a SALUDO_SCRIPT_DEMO ${dollar}1\n}\nalias buenasnoches {\n echo -a NOCHE_SCRIPT_DEMO\n}\non NUMERIC {\n if (${dollar}code == 718) { halt }\n}"
            main {
                vm.openBuffer(room)
                vm.installScript(scriptName, source)
                vm.sendUserInput("/Buenosdias todos", room)
                vm.sendUserInput("/BUENASNOCHES", room)
                vm.handleServerEvent(netId, caller("SuppressedCallerDemo"))
            }
            until { vm.state.value.buffers.getValue(room).messages.count { "SCRIPT_DEMO" in it.text } == 2 }
            assertTrue(vm.state.value.buffers.getValue(room).messages.any { it.text.trim() == "SALUDO_SCRIPT_DEMO todos" })
            assertFalse(vm.state.value.privateRequests.any { it.nick == "SuppressedCallerDemo" })
            assertFalse(vm.state.value.buffers.getValue(status).messages.any { "SuppressedCallerDemo" in it.text })
            main { vm.reloadScripts(); vm.sendUserInput("/buenosdias", room) }
            until { vm.state.value.buffers.getValue(room).messages.count { "SCRIPT_DEMO" in it.text } == 3 }
            assertEquals(source, vm.readScript(scriptName))

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
                main { vm.removeScript(scriptName); store.clear() }
                vm.privateRequests.pending().filter { it.network == netId }.forEach { vm.privateRequests.finish(it.id, "IGNORED", false) }
                vm.historyArchive.delete(vm.historyArchive.list().filter { it.path.startsWith(vm.personalLogNetwork(netId) + "/") }.map { it.path }.toSet())
                vm.privateRequests.close(); vm.historyArchive.close()
            }
            repo.saveNetworks(originalNetworks)
            repo.updateSettings { original }
        }
    }

    @Test fun dreamsChannelEventsAndNetworkDuplicationUseTheRealViewModel() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val repo = SettingsRepository(ctx)
        val original = repo.settingsFlow.first()
        val networks = repo.networksFlow.first()
        val id = "dreams-test-${java.util.UUID.randomUUID()}"
        val room = "$id::#demo"
        val source = repo.defaultNetworks().first().copy(id = id, name = "Red demo", host = "irc.example.invalid", autoConnect = false)
        val store = ViewModelStore()
        lateinit var vm: IrcViewModel
        var created = false
        var copyId: String? = null
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        suspend fun until(condition: () -> Boolean) { withTimeout(10_000) { while (!condition()) delay(25) } }
        try {
            repo.updateSettings { it.copy(loggingEnabled = false, notificationsEnabled = false, keepAliveInBackground = false,
                showConnectionStatusNotification = false, hideJoins = false, hideParts = false, hideQuits = false,
                hideNickChanges = false, smartFilter = false, hideHostnames = false, colorChannelEvents = true) }
            repo.upsertNetwork(source)
            repo.secretStore.setServerPassword(id, "fake-test-password")
            main { vm = IrcViewModel(repo, ctx); store.put("dreams", vm); created = true }
            until { vm.state.value.settingsLoaded && vm.state.value.networks.any { it.id == id } }
            main {
                vm._state.value = vm.state.value.copy(activeNetworkId = id, connections = mapOf(id to NetConnState(myNick = "OwnDemoNick")))
                vm.openBuffer(room)
                vm.handleEvent(id, IrcEvent.Joined("#demo", "DemoNick01", "demo@2001:db8::1234", account = "DemoAccount"))
                val before = vm.state.value.buffers.mapValues { it.value.messages.size }
                vm.handleEvent(id, IrcEvent.Chghost("DemoNick01", "newident", "cloak.example.invalid"))
                vm.handleEvent(id, IrcEvent.AccountChanged("DemoNick01", "DemoAccount"))
                vm.handleEvent(id, IrcEvent.AccountChanged("OwnDemoNick", "OwnDemoAccount"))
                vm.handleEvent(id, IrcEvent.ServerText("host virtual de prueba", code = "396"))
                assertEquals(before, vm.state.value.buffers.mapValues { it.value.messages.size })
                assertEquals("OwnDemoAccount", vm.state.value.connections.getValue(id).myAccount)
                vm.handleEvent(id, IrcEvent.AccountChanged("OwnDemoNick", "*"))
                assertNull(vm.state.value.connections.getValue(id).myAccount)
                vm.handleEvent(id, IrcEvent.NickChanged("DemoNick01", "DemoNick02"))
                assertTrue(vm.state.value.nicklists.getValue(room).contains("DemoNick02"))
                vm.handleEvent(id, IrcEvent.Parted("#demo", "DemoNick02", "newident@cloak.example.invalid", "Hasta luego"))
                vm.handleEvent(id, IrcEvent.Joined("#demo", "DemoNick03", "demo@192.0.2.1"))
                vm.handleEvent(id, IrcEvent.Quit("DemoNick03", "demo@192.0.2.1", "Salida de prueba"))
                val text = vm.state.value.buffers.getValue(room).messages.map { stripIrcFormatting(it.text) }
                assertTrue(text.contains("→ JOIN DemoNick01 (demo@2001:db8::1234) #demo"))
                assertTrue(text.contains("← PART DemoNick02 (newident@cloak.example.invalid) #demo [Hasta luego]"))
                assertTrue(text.contains("← QUIT DemoNick03 (demo@192.0.2.1) [Salida de prueba]"))
                assertTrue(text.any { "DemoNick01" in it && "DemoNick02" in it })
                assertFalse(text.any { "DemoAccount" in it || "host virtual de prueba" in it })
                vm.startDuplicateNetwork(id)
            }
            until { vm.state.value.editingNetwork?.id?.let { it != id } == true }
            val draft = vm.state.value.editingNetwork!!
            copyId = draft.id
            assertEquals("Red demo — copia", draft.name)
            assertEquals("fake-test-password", draft.serverPassword)
            assertFalse(repo.networksFlow.first().any { it.id == draft.id })
            main { vm.saveEditingNetwork(draft, null, false) }
            until { vm.state.value.networks.any { it.id == draft.id } }
            assertEquals("fake-test-password", repo.secretStore.getServerPassword(draft.id))
            assertEquals("fake-test-password", repo.secretStore.getServerPassword(id))
            assertEquals("Red demo", vm.state.value.networks.first { it.id == id }.name)
            assertNull(vm.state.value.networks.first { it.id == draft.id }.serverPassword)
        } finally {
            if (created) main { store.clear(); vm.privateRequests.close(); vm.historyArchive.close() }
            repo.secretStore.clearServerPassword(id)
            copyId?.let { repo.secretStore.clearServerPassword(it) }
            repo.saveNetworks(networks)
            repo.updateSettings { original }
        }
    }
}
