/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid.ui.theme

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.boxlabs.hexdroid.*

data class PersonalColors(val theme: PersonalTheme?, val dark: Boolean) {
    fun color(role: ThemeRole, fallback: Color): Color = theme?.let { Color(it.color(role, dark)) } ?: fallback
    fun text(role: ThemeRole, background: Color, fallback: Color): Color = theme?.let {
        Color(readableThemeText(it.color(role, dark), background.toArgb()))
    } ?: fallback
}
val LocalPersonalColors = staticCompositionLocalOf { PersonalColors(null, true) }
internal fun personalScheme(base: ColorScheme, c: PersonalColors): ColorScheme {
    if (c.theme == null) return base
    fun bg(r: ThemeRole) = c.color(r, base.surface)
    fun fg(r: ThemeRole, b: ThemeRole) = c.text(r, bg(b), base.onSurface)
    val button = c.text(ThemeRole.BUTTON, bg(ThemeRole.MENU), base.primary)
    return base.copy(
        background = bg(ThemeRole.APP), onBackground = fg(ThemeRole.UI_TEXT, ThemeRole.APP),
        surface = bg(ThemeRole.MENU), onSurface = fg(ThemeRole.UI_TEXT, ThemeRole.MENU),
        surfaceVariant = bg(ThemeRole.TOP), onSurfaceVariant = fg(ThemeRole.UI_TEXT, ThemeRole.TOP),
        surfaceContainer = bg(ThemeRole.MENU), surfaceContainerLow = bg(ThemeRole.MENU),
        surfaceContainerHigh = bg(ThemeRole.MENU), surfaceContainerHighest = bg(ThemeRole.MENU),
        surfaceContainerLowest = bg(ThemeRole.APP), surfaceBright = bg(ThemeRole.MENU), surfaceDim = bg(ThemeRole.MENU),
        primary = button, onPrimary = c.text(ThemeRole.UI_TEXT, button, base.onPrimary),
        primaryContainer = bg(ThemeRole.TABS), onPrimaryContainer = fg(ThemeRole.UI_TEXT, ThemeRole.TABS),
        secondaryContainer = bg(ThemeRole.TABS), onSecondaryContainer = fg(ThemeRole.UI_TEXT, ThemeRole.TABS),
        tertiary = fg(ThemeRole.NOTICE, ThemeRole.CHAT),
    )
}

/** Per-area foreground roles keep arbitrary custom backgrounds readable without changing IRC spans. */
@Composable
internal fun PersonalArea(role: ThemeRole, content: @Composable () -> Unit) {
    val colors = LocalPersonalColors.current
    if (colors.theme == null) { content(); return }
    val scheme = MaterialTheme.colorScheme
    val bg = colors.color(role, scheme.surface)
    val fgRole = when (role) { ThemeRole.CHAT -> ThemeRole.TEXT; ThemeRole.INPUT -> ThemeRole.INPUT_TEXT; else -> ThemeRole.UI_TEXT }
    val fg = colors.text(fgRole, bg, scheme.onSurface)
    val button = colors.text(ThemeRole.BUTTON, bg, scheme.primary)
    MaterialTheme(colorScheme = scheme.copy(onSurface = fg, onSurfaceVariant = fg, onBackground = fg, primary = button, onPrimary = colors.text(ThemeRole.UI_TEXT, button, scheme.onPrimary))) {
        CompositionLocalProvider(LocalContentColor provides fg, content = content)
    }
}
