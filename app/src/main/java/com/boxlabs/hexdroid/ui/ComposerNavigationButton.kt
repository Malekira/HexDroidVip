/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Matching compact composer controls; recall never intercepts the keyboard's delete key. */
@Composable
internal fun ComposerNavigationButton(
    onClick: () -> Unit,
    enabled: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    backwards: Boolean = false,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(4.dp)
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
    Box(modifier.size(36.dp)
        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 1f else 0.38f), shape)
        .focusHighlight(shape)
        .semantics { contentDescription = description }
        .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick,
            onLongClickLabel = onLongClickLabel, onLongClick = onLongClick), contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Filled.KeyboardTab, contentDescription = null, tint = color,
            modifier = Modifier.size(20.dp).graphicsLayer { scaleX = if (backwards) -1f else 1f })
    }
}
