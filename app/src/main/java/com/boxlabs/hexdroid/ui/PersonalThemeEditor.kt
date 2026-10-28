/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.boxlabs.hexdroid.*
import com.boxlabs.hexdroid.ui.theme.HexDroidIRCTheme

@Composable
fun PersonalThemeSettings(s: UiSettings, update: (UiSettings.() -> UiSettings) -> Unit) {
    var edit by remember { mutableStateOf<PersonalTheme?>(null) }
    var deleting by remember { mutableStateOf<PersonalTheme?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Temas personalizados", style = MaterialTheme.typography.titleMedium)
        Text("Paletas claras y oscuras independientes. Los colores IRC recibidos se conservan.")
        Button(onClick = { edit = PersonalTheme() }) { Text("Crear tema") }
        s.personalThemes.forEach { t ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(t.name + if (s.selectedThemeId == t.id) " ✓" else "")
                    Row {
                        TextButton(onClick = { update { withPersonal { copy(selectedThemeId = t.id) } } }) { Text("Usar") }
                        TextButton(onClick = { edit = t }) { Text("Editar") }
                        TextButton(onClick = { edit = t.copy(id = java.util.UUID.randomUUID().toString(), name = t.name + " — copia") }) { Text("Duplicar") }
                    }
                    TextButton(onClick = { deleting = t }) { Text("Eliminar") }
                }
            }
        }
        TextButton(onClick = { update { withPersonal { copy(selectedThemeId = null) } } }) { Text("Restaurar tema original") }
    }
    edit?.let { theme -> ThemeEditor(theme, { edit = null }) { result ->
        update { withPersonal { copy(personalThemes = personalThemes.filterNot { it.id == result.id } + result, selectedThemeId = result.id) } }
        edit = null
    } }
    deleting?.let { t -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("¿Eliminar ${t.name}?") },
        confirmButton = { TextButton(onClick = { update { withPersonal { copy(personalThemes = personalThemes.filterNot { it.id == t.id }, selectedThemeId = selectedThemeId.takeUnless { it == t.id }) } }; deleting = null }) { Text("Eliminar") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } }) }
}

@Composable
internal fun ThemeEditor(initial: PersonalTheme, dismiss: () -> Unit, save: (PersonalTheme) -> Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var dark by remember { mutableStateOf(true) }
    var role by remember { mutableStateOf(ThemeRole.CHAT) }
    var hex by remember(role, dark, draft.color(role, dark)) { mutableStateOf("%06X".format(draft.color(role, dark) and 0xffffff)) }
    fun setColor(v: Int) {
        draft = if (dark) draft.copy(darkColors = draft.darkColors + (role.name to v))
            else draft.copy(lightColors = draft.lightColors + (role.name to v))
    }
    Dialog(onDismissRequest = dismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.94f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Editor de tema", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth())
                Row { FilterChip(dark, { dark = true }, label = { Text("Oscuro") }); Spacer(Modifier.width(8.dp)); FilterChip(!dark, { dark = false }, label = { Text("Claro") }) }
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text(role.label + " ▾") }
                    PersonalDropdownMenu(expanded, { expanded = false }, Modifier.heightIn(max = 340.dp)) {
                        ThemeRole.entries.forEach { r -> DropdownMenuItem(text = { Text(r.label) }, onClick = { role = r; expanded = false }) }
                    }
                }
                OutlinedTextField(hex, { hex = it.removePrefix("#").take(6); parseThemeHex(hex)?.let(::setColor) },
                    label = { Text("#RRGGBB") }, isError = parseThemeHex(hex) == null, modifier = Modifier.fillMaxWidth())
                // Visual RGB selector: permits every 24-bit colour, not just predefined swatches.
                val argb = draft.color(role, dark)
                listOf("Rojo" to 16, "Verde" to 8, "Azul" to 0).forEach { (label, shift) ->
                    Text("$label: ${(argb ushr shift) and 255}")
                    Slider(((argb ushr shift) and 255).toFloat(), { value ->
                        setColor((argb and (255 shl shift).inv()) or (value.toInt() shl shift))
                    }, valueRange = 0f..255f)
                }
                Box(Modifier.fillMaxWidth().height(32.dp).background(Color(argb)))
                Text("Vista previa — contraste mínimo 4,5:1 para texto sin formato IRC")
                HexDroidIRCTheme(themeMode = if (dark) com.boxlabs.hexdroid.data.ThemeMode.DARK else com.boxlabs.hexdroid.data.ThemeMode.LIGHT, personalTheme = draft) {
                    val bg = Color(draft.color(ThemeRole.CHAT, dark))
                    Surface(color = bg, contentColor = Color(readableThemeText(draft.color(ThemeRole.TEXT, dark), bg.toArgb()))) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(color = Color(draft.color(ThemeRole.TOP, dark)), contentColor = Color(readableThemeText(draft.color(ThemeRole.UI_TEXT, dark), draft.color(ThemeRole.TOP, dark)))) { Text("#sala-demo — HexDroid Personal", Modifier.padding(8.dp)) }
                            Text("<DemoNick01> Mensaje de prueba 🙂")
                            Text("Aviso del servidor", color = Color(readableThemeText(draft.color(ThemeRole.NOTICE, dark), draft.color(ThemeRole.CHAT, dark))))
                            OutlinedTextField("Escribir…", {}, readOnly = true, modifier = Modifier.fillMaxWidth())
                            Button(onClick = {}) { Text("Botón de vista previa") }
                        }
                    }
                }
                TextButton(onClick = { draft = draft.copy(darkColors = emptyMap(), lightColors = emptyMap()) }) { Text("Restaurar colores del editor") }
                Row { TextButton(onClick = dismiss) { Text("Cancelar") }; Button(onClick = { save(draft.copy(name = draft.name.trim())) }, enabled = draft.name.isNotBlank() && parseThemeHex(hex) != null) { Text("Guardar y aplicar") } }
            }
        }
    }
}
