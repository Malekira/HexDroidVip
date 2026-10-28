/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow

/** Independent of UI palettes; only valid mIRC indexes ever reach the wire. */
data class WritingStyle(
    val foreground: Int? = null, val background: Int? = null,
    val bold: Boolean = false, val italic: Boolean = false,
    val underline: Boolean = false, val reverse: Boolean = false,
) {
    fun apply(text: String): String {
        if (stripIrcFormatting(text).trimStart().startsWith('/')) return text
        val prefix = buildString {
            if (bold) append('\u0002')
            if (italic) append('\u001d')
            if (underline) append('\u001f')
            if (reverse) append('\u0016')
            val fg = foreground?.takeIf { it in 0..98 }
            val bg = background?.takeIf { it in 0..98 }
            if (fg != null || bg != null) {
                append('\u0003').append((fg ?: 99).toString().padStart(2, '0'))
                if (bg != null) append(',').append(bg.toString().padStart(2, '0'))
                // Prevent a leading comma/digit from extending the mIRC colour sequence.
                if (text.firstOrNull()?.let { it.isDigit() || it == ',' } == true) append("\u0002\u0002")
            }
        }
        return prefix + text
    }
}

enum class ThemeRole(val label: String, val dark: Int, val light: Int) {
    APP("Fondo de aplicación", 0xff0d1117.toInt(), 0xfff5f7fb.toInt()),
    CHAT("Fondo de salas y privados", 0xff0d1117.toInt(), 0xffffffff.toInt()),
    TOP("Barras superiores", 0xff161b22.toInt(), 0xffe2e8f0.toInt()),
    TABS("Pestañas", 0xff1e3a5f.toInt(), 0xffdbeafe.toInt()),
    BUTTON("Botones", 0xff60a5fa.toInt(), 0xff1d4ed8.toInt()),
    MENU("Menús", 0xff161b22.toInt(), 0xffffffff.toInt()),
    SIDEBAR("Panel lateral", 0xff161b22.toInt(), 0xffe2e8f0.toInt()),
    NICKLIST("Lista de usuarios", 0xff161b22.toInt(), 0xfff1f5f9.toInt()),
    TEXT("Mensajes", 0xffe6edf3.toInt(), 0xff0f172a.toInt()),
    NICK("Nicks sin color IRC", 0xff93c5fd.toInt(), 0xff1e40af.toInt()),
    NOTICE("Avisos", 0xffc4b5fd.toInt(), 0xff6d28d9.toInt()),
    UI_TEXT("Texto de interfaz", 0xffe6edf3.toInt(), 0xff0f172a.toInt()),
    INPUT("Barra de escritura", 0xff161b22.toInt(), 0xfff1f5f9.toInt()),
    INPUT_TEXT("Texto de escritura", 0xffe6edf3.toInt(), 0xff0f172a.toInt()),
}

data class PersonalTheme(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "Mi tema",
    val darkColors: Map<String, Int> = emptyMap(),
    val lightColors: Map<String, Int> = emptyMap(),
) {
    fun color(role: ThemeRole, dark: Boolean): Int =
        (if (dark) darkColors else lightColors)[role.name] ?: if (dark) role.dark else role.light
}

fun parseThemeHex(text: String): Int? = text.removePrefix("#").takeIf { it.length == 6 && it.all { c -> c in '0'..'9' || c.lowercaseChar() in 'a'..'f' } }
    ?.toLongOrNull(16)?.let { (it or 0xff000000L).toInt() }
fun themeContrast(a: Int, b: Int): Double {
    fun lum(v: Int): Double {
        fun channel(shift: Int): Double {
            val s = ((v ushr shift) and 255) / 255.0
            return if (s <= .04045) s / 12.92 else ((s + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }
    return (maxOf(lum(a), lum(b)) + .05) / (minOf(lum(a), lum(b)) + .05)
}
fun readableThemeText(requested: Int, background: Int): Int =
    if (themeContrast(requested, background) >= 4.5) requested
    else listOf(0xff000000.toInt(), 0xffffffff.toInt()).maxBy { themeContrast(it, background) }

/** New fields are nested so existing backups/preferences remain compatible. */
internal object Phase3Codec {
    fun themes(o: JSONObject): List<PersonalTheme> {
        val a = o.optJSONArray("personalThemes") ?: return emptyList()
        fun colors(p: JSONObject?): Map<String, Int> = ThemeRole.entries.mapNotNull { r ->
            p?.optString(r.name)?.let(::parseThemeHex)?.let { r.name to it }
        }.toMap()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { t ->
            val id = t.optString("id").takeIf { it.isNotBlank() } ?: return@let null
            PersonalTheme(id, t.optString("name", "Mi tema"), colors(t.optJSONObject("dark")), colors(t.optJSONObject("light")))
        } }.distinctBy { it.id }
    }
    fun style(o: JSONObject): WritingStyle {
        val s = o.optJSONObject("writingStyle") ?: return WritingStyle()
        fun c(k: String) = s.optInt(k, -1).takeIf { it in 0..98 }
        return WritingStyle(c("fg"), c("bg"), s.optBoolean("bold"), s.optBoolean("italic"), s.optBoolean("underline"), s.optBoolean("reverse"))
    }
    fun write(o: JSONObject, s: UiSettings) {
        o.put("personalThemes", JSONArray().apply { s.personalThemes.forEach { t ->
            fun colors(m: Map<String, Int>) = JSONObject().apply { m.forEach { (k, v) -> put(k, "%06X".format(v and 0xffffff)) } }
            put(JSONObject().put("id", t.id).put("name", t.name).put("dark", colors(t.darkColors)).put("light", colors(t.lightColors)))
        } })
        o.put("selectedThemeId", s.selectedThemeId)
        o.put("writingStyle", JSONObject().put("fg", s.writingStyle.foreground ?: -1).put("bg", s.writingStyle.background ?: -1)
            .put("bold", s.writingStyle.bold).put("italic", s.writingStyle.italic).put("underline", s.writingStyle.underline).put("reverse", s.writingStyle.reverse))
        o.put("encryptedLogEnabled", s.encryptedLogEnabled)
        o.put("privateRequestsEnabled", s.privateRequestsEnabled).put("privateAutoMessages", s.privateAutoMessages)
        o.put("privateRememberAccepted", s.privateRememberAccepted)
        o.put("privateWaitText", s.privateWaitText).put("privateAcceptText", s.privateAcceptText).put("privateRejectText", s.privateRejectText)
    }
}

internal fun completionMatches(nicks: List<String>, prefix: String): List<String> =
    nicks.map(::bareMember).filter { it.startsWith(prefix, true) && isReplyNick(it) }
        .distinctBy { it.lowercase(java.util.Locale.ROOT) }.sortedBy { it.lowercase(java.util.Locale.ROOT) }

internal data class NickCompletion(val start: Int, val hadAt: Boolean, val matches: List<String>, val index: Int, val text: String, val cursor: Int, val prefix: String)
internal fun advanceNickCompletion(text: String, cursor: Int, nicks: List<String>, previous: NickCompletion?, backwards: Boolean = false): NickCompletion? {
    val cycle = previous?.takeIf { it.text == text && it.cursor == cursor }
    val token = if (cycle == null) nickToken(text, cursor) ?: return null
        else NickToken(cycle.start, cursor, cursor, "", cycle.hadAt)
    val prefix = cycle?.prefix ?: token.prefix
    val matches = completionMatches(nicks, prefix)
    if (matches.isEmpty()) return null
    val previousIndex = cycle?.let { matches.indexOf(it.matches[it.index]) } ?: -1
    val index = if (previousIndex < 0) if (backwards) matches.lastIndex else 0
        else (previousIndex + if (backwards) matches.size - 1 else 1) % matches.size
    val (value, position) = completeNickToken(text, token, matches[index])
    return NickCompletion(token.start, token.hadAt, matches, index, value, position, prefix)
}

internal const val HISTORY_ESCAPED = "!hexdroid-v3!"
internal fun encodeHistoryBody(text: String): String = text.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")
internal fun decodeHistoryBody(text: String): String = buildString {
    var i = 0
    while (i < text.length) {
        if (text[i] == '\\' && i + 1 < text.length) {
            append(when (val c = text[++i]) { 'n' -> '\n'; 'r' -> '\r'; else -> c })
        } else append(text[i])
        i++
    }
}
