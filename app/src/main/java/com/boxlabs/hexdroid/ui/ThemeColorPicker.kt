/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** HSV surface: tap/drag chooses saturation and brightness; the gradient selects hue. */
@Composable
internal fun ThemeColorPicker(color: Int, onChange: (Int) -> Unit) {
    val hsv = remember(color) { FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    // Preserve a chosen hue while black/grey has no measurable hue of its own.
    LaunchedEffect(color) { if (hsv[1] > 0f && hsv[2] > 0f) hue = hsv[0] }
    val currentHue by rememberUpdatedState(hue)
    val update by rememberUpdatedState(onChange)
    Text("Selector visual de color", style = MaterialTheme.typography.titleSmall)
    Canvas(Modifier.fillMaxWidth().height(170.dp)
        .testTag("theme-saturation-value")
        .semantics { contentDescription = "Color: tocá o arrastrá para elegir saturación y brillo" }
        .pointerInput(Unit) {
            fun choose(position: Offset) {
                if (size.width <= 0 || size.height <= 0) return
                val saturation = (position.x / size.width).coerceIn(0f, 1f)
                val brightness = (1f - position.y / size.height).coerceIn(0f, 1f)
                update(android.graphics.Color.HSVToColor(floatArrayOf(currentHue, saturation, brightness)))
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                choose(down.position)
                do {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    choose(change.position)
                    change.consume()
                } while (true)
            }
        }) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val marker = Offset(hsv[1] * size.width, (1f - hsv[2]) * size.height)
        drawCircle(Color.Black, 8.dp.toPx(), marker, style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 8.dp.toPx(), marker, style = Stroke(1.5f.dp.toPx()))
    }
    Text("Tono: ${hue.toInt()}°")
    Box(Modifier.fillMaxWidth().height(48.dp)) {
        Canvas(Modifier.matchParentSize().padding(horizontal = 10.dp, vertical = 18.dp)) {
            drawRect(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 1f, 1f) }))
        }
        Slider(hue, { value ->
            hue = value
            onChange(android.graphics.Color.HSVToColor(floatArrayOf(value, hsv[1], hsv[2])))
        }, valueRange = 0f..360f, modifier = Modifier.fillMaxWidth().testTag("theme-hue")
            .semantics { contentDescription = "Tono del color" },
            colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent))
    }
}
