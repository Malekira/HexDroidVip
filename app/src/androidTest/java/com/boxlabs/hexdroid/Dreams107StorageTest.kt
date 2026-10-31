/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.boxlabs.hexdroid.data.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class Dreams107StorageTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val url = "https://images.example.invalid/profile.jpg"
    private val other = "https://images.example.invalid/other.jpg"
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private suspend fun until(condition: () -> Boolean) { withTimeout(15_000) { while (!condition()) delay(25) } }

    @Test fun dreams107LegacyPhotoMigratesAndJsonBackupRestoresItsUrlIncludingRemoval() = runBlocking {
        val repo = SettingsRepository(context)
        val originalNetworks = repo.networksFlow.first()
        val originalMeta = repo.readOwnMetadata()
        val settings = repo.settingsFlow.first()
        val net = repo.defaultNetworks().first().copy(id = "photo-${java.util.UUID.randomUUID()}", autoConnect = false)
        try {
            repo.writeOwnMetadata(originalMeta + (net.id to mapOf("avatar" to url)))
            repo.saveNetworks(listOf(net)) // Emulate old storage: profile has no photo field, metadata does.
            val migrated = repo.networksFlow.first().single()
            assertEquals(url, migrated.avatarUrl)
            val json = repo.exportBackupJson(listOf(migrated), settings)
            assertEquals(url, JSONObject(json).getJSONArray("networks").getJSONObject(0).getString("avatarUrl"))
            repo.upsertNetwork(net.copy(avatarUrl = other))
            repo.importBackup(json)
            assertEquals(url, SettingsRepository(context).networksFlow.first().single().avatarUrl)
            assertEquals(url, repo.readOwnMetadata()[net.id]?.get("avatar"))
            val older = JSONObject(json)
            older.getJSONArray("networks").getJSONObject(0).remove("avatarUrl")
            repo.importBackup(older.toString())
            assertEquals(url, repo.networksFlow.first().single().avatarUrl)
            val cleared = JSONObject(json)
            cleared.getJSONArray("networks").getJSONObject(0).put("avatarUrl", "")
            repo.importBackup(cleared.toString())
            assertEquals("", SettingsRepository(context).networksFlow.first().single().avatarUrl)
            assertEquals("", repo.readOwnMetadata()[net.id]?.get("avatar"))
        } finally { repo.saveNetworks(originalNetworks); repo.writeOwnMetadata(originalMeta) }
    }

    @Test fun dreams107ConcurrentMetadataEditsKeepBothThePhotoAndOtherProfileKeys() = runBlocking {
        val repo = SettingsRepository(context)
        val originalNetworks = repo.networksFlow.first()
        val originalMeta = repo.readOwnMetadata()
        val net = repo.defaultNetworks().first().copy(id = "photo-atomic-${java.util.UUID.randomUUID()}", autoConnect = false)
        try {
            repo.saveNetworks(listOf(net))
            coroutineScope {
                val photo = async { repo.updateOwnMetadataEntry(net.id, "avatar", url) }
                val display = async { repo.updateOwnMetadataEntry(net.id, "display-name", "Perfil de prueba") }
                photo.await(); display.await()
            }
            assertEquals(url, repo.readOwnMetadata()[net.id]?.get("avatar"))
            assertEquals("Perfil de prueba", repo.readOwnMetadata()[net.id]?.get("display-name"))
            assertEquals(url, repo.networksFlow.first().single().avatarUrl)
        } finally { repo.saveNetworks(originalNetworks); repo.writeOwnMetadata(originalMeta) }
    }

    @Test fun dreams107CombinedZipRestoresPhotoAndKeepsTheOriginalConversation() = runBlocking {
        val repo = SettingsRepository(context)
        val originalNetworks = repo.networksFlow.first()
        val originalMeta = repo.readOwnMetadata()
        val settings = repo.settingsFlow.first()
        val ctx = Phase3TestContext(context)
        val archive = HistoryArchive(ctx)
        val net = repo.defaultNetworks().first().copy(id = "photo-zip-${java.util.UUID.randomUUID()}", autoConnect = false, avatarUrl = url)
        val path = "network-demo/#demo.txt"
        try {
            archive.append(path, "2026-10-31 12:00:00\t<DemoNick01> Acentos ñ 🙂")
            val before = HistoryFiles.resolve(archive.root, path).readBytes()
            val json = repo.exportBackupJson(listOf(net), settings)
            val zip = ByteArrayOutputStream()
            ConversationBackup.write(zip, json, archive::exportAll)
            repo.saveNetworks(listOf(net.copy(avatarUrl = other)))
            ConversationBackup.read(zip.toByteArray().inputStream(), ctx.root).use { contents ->
                repo.importBackup(contents.json)
                assertEquals(0, contents.history!!.inputStream().use { archive.restore(it) })
            }
            assertEquals(url, repo.networksFlow.first().single().avatarUrl)
            assertEquals(url, repo.readOwnMetadata()[net.id]?.get("avatar"))
            assertArrayEquals(before, HistoryFiles.resolve(archive.root, path).readBytes())
        } finally {
            archive.close(); ctx.root.deleteRecursively()
            repo.saveNetworks(originalNetworks); repo.writeOwnMetadata(originalMeta)
        }
    }

    @Test fun dreams107SavingTheRealProfileAndRestoringItsBackupRefreshesTheLivePhoto() = runBlocking {
        val repo = SettingsRepository(context)
        val originalNetworks = repo.networksFlow.first()
        val originalMeta = repo.readOwnMetadata()
        val originalSettings = repo.settingsFlow.first()
        val net = repo.defaultNetworks().first().copy(id = "photo-vm-${java.util.UUID.randomUUID()}", autoConnect = false)
        val file = File(context.cacheDir, "profile-${java.util.UUID.randomUUID()}.json")
        val store = ViewModelStore()
        lateinit var vm: IrcViewModel
        var created = false
        try {
            repo.saveNetworks(listOf(net))
            repo.updateSettings { it.copy(loggingEnabled = false, notificationsEnabled = false, keepAliveInBackground = false, showConnectionStatusNotification = false) }
            main { vm = IrcViewModel(repo, context); store.put("photo", vm); created = true }
            until { vm.state.value.settingsLoaded && vm.state.value.networks.any { it.id == net.id } }
            main { vm.startEditNetwork(net.id) }
            until { vm.state.value.editingNetwork?.id == net.id }
            main { vm.saveEditingNetwork(vm.state.value.editingNetwork!!.copy(avatarUrl = url), null, false) }
            until { vm.state.value.profileAvatarUrls[net.id] == url && vm.state.value.networks.first().avatarUrl == url }
            main { vm.exportBackup(Uri.fromFile(file), null, false) }
            until { vm.state.value.backupMessage != null }
            assertFalse(vm.state.value.backupIsError)
            main { vm.setOwnMetadata(net.id, "avatar", other) }
            until { vm.state.value.profileAvatarUrls[net.id] == other }
            main { vm.clearBackupMessage(); vm.importBackup(Uri.fromFile(file)) }
            until { vm.state.value.backupMessage != null }
            assertFalse(vm.state.value.backupIsError)
            assertEquals(url, vm.state.value.profileAvatarUrls[net.id])
            assertEquals(url, repo.networksFlow.first().single().avatarUrl)
        } finally {
            if (created) main { store.clear(); vm.privateRequests.close(); vm.historyArchive.close() }
            file.delete()
            repo.saveNetworks(originalNetworks); repo.writeOwnMetadata(originalMeta); repo.updateSettings { originalSettings }
        }
    }
}
