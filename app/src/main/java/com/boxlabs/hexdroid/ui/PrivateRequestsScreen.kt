/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.*

@Composable
internal fun PrivateRequestSettings(s: UiSettings, update: (UiSettings.() -> UiSettings) -> Unit, pending: Int, open: () -> Unit, revoke: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Solicitudes de privados", style = MaterialTheme.typography.titleMedium)
        Text("Los mensajes quedan pendientes sin abrir una conversación. Las solicitudes se conservan al cerrar la app. Los mensajes pendientes se cifran en el teléfono mediante Android Keystore; no forman parte de la copia TXT del historial.")
        Row { Text("Activar solicitudes", Modifier.weight(1f)); Switch(s.privateRequestsEnabled, { v -> update { withPersonal { copy(privateRequestsEnabled = v) } } }) }
        Text("Los avisos automáticos se envían sin cifrar y no incluyen el contenido del privado. Evitá añadir información sensible a sus textos o al motivo de rechazo.")
        Row { Text("Mensajes automáticos (NOTICE)", Modifier.weight(1f)); Switch(s.privateAutoMessages, { v -> update { withPersonal { copy(privateAutoMessages = v) } } }) }
        Row { Text("Recordar aceptados entre sesiones", Modifier.weight(1f)); Switch(s.privateRememberAccepted, { v -> update { withPersonal { copy(privateRememberAccepted = v) } } }) }
        OutlinedTextField(s.privateWaitText, { text -> update { withPersonal { copy(privateWaitText = text) } } }, label = { Text("Mensaje de espera") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(s.privateAcceptText, { text -> update { withPersonal { copy(privateAcceptText = text) } } }, label = { Text("Mensaje de aceptación") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(s.privateRejectText, { text -> update { withPersonal { copy(privateRejectText = text) } } }, label = { Text("Mensaje de rechazo (se indica siempre que fue rechazado)") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = open) { Text("Ver privados pendientes ($pending)") }
        TextButton(onClick = revoke) { Text("Revocar autorizaciones locales") }
        Text("+g depende de la red. Un aviso 718 solo informa de un intento bloqueado; su texto no llegó a la app. ACCEPT se envía únicamente si la red anuncia CALLERID=g. Rechazar o ignorar no bloquea al usuario en el servidor.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivateRequestsScreen(state: UiState, back: () -> Unit, resolve: (PrivateRequest, String, String) -> Unit, callerId: (String, String) -> Unit) {
    var rejecting by remember { mutableStateOf<PrivateRequest?>(null) }
    var reason by remember { mutableStateOf("") }
    Scaffold(topBar = { PersonalTopAppBar(title = { Text("Privados pendientes (${state.privateRequests.size})") }, navigationIcon = { TextButton(onClick = back) { Text("Volver") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Aceptar abre el privado; rechazar puede enviar un NOTICE; ignorar descarta sin responder ni bloquear. Las notificaciones llevan a esta lista.") }
            state.callerIdNetworks.forEach { netId -> item {
                Column {
                    Text("+g · ${state.networks.firstOrNull { it.id == netId }?.name ?: netId}")
                    Row { TextButton(onClick = { callerId(netId, "enable") }) { Text("Pedir +g") }; TextButton(onClick = { callerId(netId, "disable") }) { Text("Pedir -g") }; TextButton(onClick = { callerId(netId, "list") }) { Text("Lista ACCEPT") } }
                }
            } }
            state.privateRequestMessage?.let { message -> item { Text(message) } }
            if (state.privateRequests.isEmpty()) item { Text("No hay solicitudes pendientes") }
            items(state.privateRequests, key = { it.id }) { request ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val networkName = state.networks.firstOrNull { it.id == request.network }?.name ?: request.network
                        Text("${request.nick} · $networkName", style = MaterialTheme.typography.titleMedium)
                        Text(if (request.serverBlocked) "Intento bloqueado por el servidor (+g). No recibimos ese mensaje." else "${request.count} mensajes recibidos y guardados pendientes")
                        Row {
                            TextButton(onClick = { resolve(request, "ACCEPTED", "") }) { Text("Aceptar") }
                            TextButton(onClick = { reason = ""; rejecting = request }) { Text("Rechazar") }
                            TextButton(onClick = { resolve(request, "IGNORED", "") }) { Text("Ignorar") }
                        }
                    }
                }
            }
        }
    }
    rejecting?.let { request -> AlertDialog(onDismissRequest = { rejecting = null }, title = { Text("Rechazar el privado de ${request.nick}") },
        text = { Column { Text(if (state.settings.privateAutoMessages) "Se enviará un aviso de rechazo" else "Los mensajes automáticos están desactivados: no se enviará respuesta")
            OutlinedTextField(reason, { reason = it }, label = { Text("Motivo opcional") }) } },
        confirmButton = { TextButton(onClick = { resolve(request, "REJECTED", reason); rejecting = null }) { Text("Rechazar") } },
        dismissButton = { TextButton(onClick = { rejecting = null }) { Text("Cancelar") } }) }
}
