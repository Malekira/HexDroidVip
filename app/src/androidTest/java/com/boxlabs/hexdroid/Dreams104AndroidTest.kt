/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import android.app.NotificationManager
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.boxlabs.hexdroid.data.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class Dreams104AndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private suspend fun until(condition: () -> Boolean) { withTimeout(10_000) { while (!condition()) delay(25) } }

    private suspend fun withModel(block: suspend (IrcViewModel, SettingsRepository, String) -> Unit) {
        val repo = SettingsRepository(context)
        val oldSettings = repo.settingsFlow.first()
        val oldNetworks = repo.networksFlow.first()
        val oldMeta = repo.readOwnMetadata()
        val id = "dreams104-${java.util.UUID.randomUUID()}"
        val net = repo.defaultNetworks().first().copy(id = id, name = "Red de prueba", host = "irc.example.invalid", autoConnect = false,
            ignoredNicks = listOf("IgnoredDemoNick"))
        val store = ViewModelStore()
        lateinit var vm: IrcViewModel
        var created = false
        try {
            repo.saveNetworks(listOf(net))
            repo.updateSettings { it.copy(loggingEnabled = false, notificationsEnabled = false, privateAttemptActiveNotice = true, privateAttemptFloating = true) }
            main { vm = IrcViewModel(repo, context); store.put("dreams104", vm); created = true }
            until { vm.state.value.settingsLoaded && vm.state.value.networks.any { it.id == id } }
            block(vm, repo, id)
        } finally {
            if (created) main { store.clear() }
            repo.writeOwnMetadata(oldMeta)
            repo.saveNetworks(oldNetworks)
            repo.updateSettings { oldSettings }
        }
    }

    @Test fun dreams104NativeDestinationsRespectNetworkIgnoresAndNeverCreateBlockedQueries() = runBlocking {
        withModel { vm, _, id ->
            val active = "$id::#demo"
            fun blocked(nick: String) = IrcEvent.ServerText("Aviso real de prueba", code = "718", whoisParams = listOf("OwnDemoNick", nick, "user@host.invalid"))
            main {
                vm.openBuffer(active)
                vm.handleEvent(id, blocked("DemoNick01"))
                assertEquals(1, vm.state.value.buffers.getValue(active).messages.count { it.isHighlightedNotice })
                assertEquals(1, vm.state.value.buffers.getValue("$id::*server*").messages.count { "DemoNick01" in it.text })
                assertEquals("DemoNick01", vm.state.value.privateAttemptAlerts.single().nick)
                assertFalse(vm.state.value.buffers.containsKey("$id::DemoNick01"))
                assertFalse(vm.hasPrivateRequestPolicy())
                vm.dismissPrivateAttempt(vm.state.value.privateAttemptAlerts.single().id)
                assertTrue(vm.state.value.privateAttemptAlerts.isEmpty())
                vm.updateSettings { copy(privateAttemptActiveNotice = false, privateAttemptFloating = true) }
                vm.handleEvent(id, blocked("DemoNick02"))
                assertTrue(vm.state.value.buffers.getValue(active).messages.none { "DemoNick02" in it.text })
                assertEquals("DemoNick02", vm.state.value.privateAttemptAlerts.single().nick)
                vm.handleEvent(id, blocked("IgnoredDemoNick"))
                assertTrue(vm.state.value.privateAttemptAlerts.none { it.nick == "IgnoredDemoNick" })
                vm.openBuffer("foreign-demo::#other")
                vm.handleEvent(id, blocked("DemoNick03"))
                assertTrue(vm.state.value.buffers.getValue("foreign-demo::#other").messages.none { "DemoNick03" in it.text })
                vm.handleEvent(id, IrcEvent.OwnUserModes("+gR", snapshot = true))
                assertEquals("Rg", vm.state.value.connections.getValue(id).userModes)
                vm.handleEvent(id, IrcEvent.OwnUserModes("-g"))
                assertEquals("R", vm.state.value.connections.getValue(id).userModes)
            }
            assertTrue(vm.privateRequests.pending().none { it.network == id })
        }
    }

    @Test fun dreams104NativePreferencesAndOfflineAvatarIncludingRemovalPersist() = runBlocking {
        withModel { vm, repo, id ->
            val url = "https://images.example.invalid/profile.jpg"
            main { vm.setOwnMetadata(id, "avatar", url) }
            until { vm.state.value.profileAvatarUrls[id] == url }
            assertEquals(url, SettingsRepository(context).readOwnMetadata()[id]?.get("avatar"))
            main { vm.setOwnMetadata(id, "avatar", "http://images.example.invalid/rejected.jpg") }
            delay(100)
            assertEquals(url, repo.readOwnMetadata()[id]?.get("avatar"))
            main { vm.setOwnMetadata(id, "avatar", null) }
            until { vm.state.value.profileAvatarUrls[id] == "" }
            assertEquals("", SettingsRepository(context).readOwnMetadata()[id]?.get("avatar"))
            repo.updateSettings { it.copy(soundMentionsWhileVisible = true, privateAttemptActiveNotice = false, privateAttemptFloating = true) }
            val restored = SettingsRepository(context).settingsFlow.first()
            assertTrue(restored.soundMentionsWhileVisible)
            assertFalse(restored.privateAttemptActiveNotice)
            assertTrue(restored.privateAttemptFloating)
        }
    }

    @Test fun dreams104VisibleMentionUsesOneAudibleAndroidNotificationAndDeduplicatesReplay() = runBlocking {
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
        val foreground = AppVisibility.isForeground
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            withModel { vm, _, id ->
                val key = "$id::#demo"
                val tag = NotificationHelper.notifTagFor(id, "#demo")
                fun notices() = manager.activeNotifications.filter { it.tag == tag }
                val msgId = "dreams104-notify-${java.util.UUID.randomUUID()}"
                main {
                    AppVisibility.isForeground = true
                    vm.openBuffer(key)
                    vm.updateSettings { copy(notificationsEnabled = true, notifyOnHighlights = true, notifyMentionsWhileVisible = false,
                        soundMentionsWhileVisible = true, playSoundOnHighlight = false, vibrateOnHighlight = false) }
                    vm.append(key, "DemoNick01", "Mención de prueba", isHighlight = true, msgId = msgId)
                }
                until { notices().size == 1 }
                assertEquals(NotificationHelper.networkHighlightChannelId("Red de prueba", true), notices().single().notification.channelId)
                assertNotNull(manager.getNotificationChannel(notices().single().notification.channelId).sound)
                assertNotNull(notices().single().notification.contentIntent)
                main { vm.append(key, "DemoNick01", "Mención de prueba", isHighlight = true, msgId = msgId, isHistory = true) }
                assertEquals(1, notices().size)
                assertEquals(1, vm.state.value.buffers.getValue(key).messages.count { it.msgId == msgId })
                manager.cancel(tag, notices().single().id)
                main {
                    AppVisibility.isForeground = true
                    vm.updateSettings { copy(soundMentionsWhileVisible = false) }
                    vm.append(key, "DemoNick01", "Segunda mención", isHighlight = true, msgId = "$msgId-disabled")
                }
                delay(250)
                assertTrue(notices().isEmpty())
            }
        } finally { AppVisibility.isForeground = foreground }
    }
}
