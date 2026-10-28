/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.boxlabs.hexdroid.ThemeRole
import com.boxlabs.hexdroid.ui.theme.*

/** Every application header uses TOP, including settings, history and script management. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonalTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
) {
    PersonalArea(ThemeRole.TOP) {
        val personal = LocalPersonalColors.current
        val bg = personal.color(ThemeRole.TOP, colors.containerColor)
        val fg = personal.text(ThemeRole.UI_TEXT, bg, colors.titleContentColor)
        TopAppBar(title = title, modifier = modifier, navigationIcon = navigationIcon, actions = actions,
            colors = if (personal.theme == null) colors else colors.copy(
                containerColor = bg, scrolledContainerColor = bg, titleContentColor = fg,
                navigationIconContentColor = fg, actionIconContentColor = fg,
            ))
    }
}

/** A popup restores the menu palette even when its anchor lives in a contrasting header/panel. */
@Composable
internal fun PersonalDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = LocalPersonalBaseScheme.current
    val personal = LocalPersonalColors.current
    val menu: @Composable () -> Unit = { DropdownMenu(expanded, onDismissRequest, modifier, content = content) }
    if (base == null || personal.theme == null) menu()
    else MaterialTheme(colorScheme = base) {
        PersonalArea(ThemeRole.MENU) { menu() }
    }
}
