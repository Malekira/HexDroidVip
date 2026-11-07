package com.boxlabs.hexdroid

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.boxlabs.hexdroid.data.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ChatHistoryRegressionTest {
    @Test fun statusAndFloatingWhoisPersistWithoutDuplicateWindowEntries() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = ViewModelStore()
        val network = "history-regression-${java.util.UUID.randomUUID()}"
        lateinit var vm: IrcViewModel
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        suspend fun until(condition: () -> Boolean) {
            withTimeout(10_000) { while (!condition()) delay(25) }
        }
        main { vm = IrcViewModel(SettingsRepository(context), context); store.put("test", vm) }
        val statusFile = vm.logs.logFileInternal(vm.personalLogNetwork(network), "*server*")
        val whoisFile = vm.logs.logFileInternal(vm.personalLogNetwork(network), WHOIS_BUFFER)
        try {
            until { vm.state.value.settingsLoaded }
            main {
                vm._state.update { it.copy(settings = it.settings.copy(loggingEnabled = true, logServerBuffer = true)) }
                vm.append("$network::*server*", null, "Status de prueba", doNotify = false)
                vm.whoisBlocks.add(network, 1L, "DemoNick", emptySet(), "WHOIS flotante", floating = true)
                vm.flushWhoisBlock(network, 1L)
                vm.whoisBlocks.add(network, 2L, "DemoNick", setOf("$network::$WHOIS_BUFFER"), "WHOIS ventana")
                vm.flushWhoisBlock(network, 2L)
            }
            until { statusFile.exists() && whoisFile.exists() && whoisFile.readLines().size == 2 }
            assertTrue(statusFile.readText().contains("Status de prueba"))
            assertEquals(1, whoisFile.readLines().count { "WHOIS flotante" in it })
            assertEquals(1, whoisFile.readLines().count { "WHOIS ventana" in it })
            main { store.clear() }
            // A newly constructed archive sees the same records after ViewModel teardown.
            val reopened = HistoryArchive(context)
            try {
                val path = whoisFile.relativeTo(reopened.root).invariantSeparatorsPath
                assertEquals(2, reopened.page(path, "", 0).lines.size)
                assertEquals(2, LogWriter(context).readTail(vm.personalLogNetwork(network), WHOIS_BUFFER, 100, null).size)
            } finally { reopened.close() }
        } finally {
            main { store.clear() }
            vm.historyArchive.delete(vm.historyArchive.list().filter {
                it.path.startsWith(vm.personalLogNetwork(network) + "/")
            }.map { it.path }.toSet())
        }
    }
}
