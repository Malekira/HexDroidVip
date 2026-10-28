/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.*
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonalHistoryScreen(archive: HistoryArchive, back: () -> Unit, networkLabels: Map<String, String> = emptyMap()) {
    fun label(path: String): String = (networkLabels[path.substringBefore('/')] ?: path.substringBefore('/')) + "/" + path.substringAfter('/')
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<HistoryConversation>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var conversation by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(HistoryPage(emptyList(), null)) }
    var offset by remember { mutableLongStateOf(0) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Set<String>?>(null) }
    var exportPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var exportPath by remember { mutableStateOf<String?>(null) }
    suspend fun refresh() { files = withContext(Dispatchers.IO) { archive.list() } }
    fun work(task: suspend () -> String) {
        scope.launch {
            busy = true
            status = try { task() } catch (e: Exception) { "Error: ${e.message}" }
            busy = false
            refresh()
        }
    }
    val zip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) { val paths = exportPaths; work { withContext(Dispatchers.IO) { archive.export(paths, requireNotNull(ctx.contentResolver.openOutputStream(uri))) }; "Copia exportada: ${paths.size} conversaciones" } }
    }
    val txt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportPath?.let { path -> work { withContext(Dispatchers.IO) { archive.exportText(path, requireNotNull(ctx.contentResolver.openOutputStream(uri))) }; "TXT exportado" } }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) work {
            val count = withContext(Dispatchers.IO) { archive.restore(requireNotNull(ctx.contentResolver.openInputStream(uri))) }
            "Restauración terminada: $count líneas nuevas; las existentes se conservaron"
        }
    }
    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(conversation, query, offset) {
        val path = conversation ?: return@LaunchedEffect
        delay(200)
        page = withContext(Dispatchers.IO) { archive.page(path, query, offset) }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Historial permanente") }, navigationIcon = { TextButton(onClick = { if (conversation != null) { conversation = null; query = ""; offset = 0 } else back() }) { Text("Volver") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Almacenamiento privado: ${files.sumOf { it.bytes }} bytes · ${files.size} conversaciones")
            Text("Los TXT y ZIP exportados pueden contener información privada y mensajes descifrados. Guardalos en un lugar seguro.")
            if (status.isNotBlank()) SelectionContainer { Text(status) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (conversation == null) {
                Row {
                    TextButton(onClick = { exportPaths = selected.ifEmpty { files.map { it.path }.toSet() }; zip.launch("HexDroid-Personal-historial.zip") }, enabled = !busy && files.isNotEmpty()) { Text("Exportar ZIP") }
                    TextButton(onClick = { restore.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !busy) { Text("Restaurar ZIP") }
                }
                Row {
                    TextButton(onClick = { selected = if (selected.size == files.size) emptySet() else files.map { it.path }.toSet() }) { Text("Seleccionar todos") }
                    TextButton(onClick = { deleting = selected }, enabled = !busy && selected.isNotEmpty()) { Text("Eliminar selección") }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(files, key = { it.path }) { file ->
                        ListItem(headlineContent = { Text(label(file.path)) }, supportingContent = { Text("${file.bytes} bytes") }, leadingContent = {
                            Checkbox(file.path in selected, { selected = if (file.path in selected) selected - file.path else selected + file.path })
                        }, modifier = Modifier.clickable { conversation = file.path; offset = 0 })
                    }
                }
            } else {
                Text(label(conversation!!), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(query, { query = it; offset = 0 }, label = { Text("Buscar palabra, nick o fecha (AAAA-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(onClick = { exportPath = conversation; txt.launch(conversation!!.substringAfterLast('/')) }, enabled = !busy) { Text("Exportar TXT") }
                    TextButton(onClick = { offset = 0 }) { Text("Inicio") }
                    TextButton(onClick = { page.next?.let { offset = it } }, enabled = page.next != null) { Text("Siguiente") }
                }
                LazyColumn(Modifier.weight(1f)) { items(page.lines.size) { i -> SelectionContainer { Text(page.lines[i], Modifier.fillMaxWidth().padding(vertical = 6.dp)) } } }
            }
        }
    }
    deleting?.let { paths -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("¿Eliminar ${paths.size} historiales?") }, text = { Text("Esta acción elimina los archivos seleccionados. Exportá una copia primero si querés conservarlos.") },
        confirmButton = { TextButton(onClick = { deleting = null; work { withContext(Dispatchers.IO) { archive.delete(paths) }; selected = emptySet(); "Historiales eliminados" } }) { Text("Eliminar") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } }) }
}
