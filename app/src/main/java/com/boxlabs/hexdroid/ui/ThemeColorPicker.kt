/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import kotlin.math.*

/** Compact HSV wheel: angle selects hue, distance selects saturation; slider controls brightness. */
@Composable
internal fun ThemeColorPicker(color: Int, onChange: (Int) -> Unit) {
    val hsv = remember(color) { FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    // Preserve the chosen hue while black or grey has no measurable hue of its own.
    LaunchedEffect(color) { if (hsv[1] > 0f && hsv[2] > 0f) hue = hsv[0] }
    val brightness by rememberUpdatedState(hsv[2])
    val update by rememberUpdatedState(onChange)
    Text("Selector visual de color", style = MaterialTheme.typography.titleSmall)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(160.dp).testTag("theme-color-wheel")
            .semantics { contentDescription = "Rueda de color: tocá o arrastrá; el centro es blanco y el borde tiene colores intensos" }
            .pointerInput(Unit) {
                fun choose(position: Offset) {
                    val radius = min(size.width, size.height) / 2f
                    if (radius <= 0f) return
                    val x = position.x - size.width / 2f
                    val y = position.y - size.height / 2f
                    val saturation = (hypot(x, y) / radius).coerceIn(0f, 1f)
                    val angle = ((atan2(y, x) * 180f / PI.toFloat()) + 360f) % 360f
                    hue = angle
                    update(android.graphics.Color.HSVToColor(floatArrayOf(angle, saturation, brightness)))
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val radius = min(size.width, size.height) / 2f
                    // Do not capture scrolling gestures in the corners outside the circle.
                    if ((down.position - Offset(size.width / 2f, size.height / 2f)).getDistance() <= radius) {
                        down.consume()
                        choose(down.position)
                        do {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            choose(change.position)
                            change.consume()
                        } while (true)
                    }
                }
            }) {
            val radius = size.minDimension / 2f
            drawCircle(Brush.sweepGradient((0..6).map { Color.hsv(it * 60f, 1f, hsv[2]) }))
            drawCircle(Brush.radialGradient(listOf(Color.hsv(0f, 0f, hsv[2]), Color.Transparent), radius = radius))
            val angle = hue * PI.toFloat() / 180f
            val marker = center + Offset(cos(angle), sin(angle)) * (hsv[1] * (radius - 2.dp.toPx()))
            drawCircle(Color.Black, 6.dp.toPx(), marker, style = Stroke(3.dp.toPx()))
            drawCircle(Color.White, 6.dp.toPx(), marker, style = Stroke(1.5f.dp.toPx()))
        }
    }
    Text("Brillo: ${(hsv[2] * 100).toInt()} %")
    Slider(hsv[2], { value ->
        onChange(android.graphics.Color.HSVToColor(floatArrayOf(hue, hsv[1], value)))
    }, valueRange = 0f..1f, modifier = Modifier.fillMaxWidth().testTag("theme-brightness")
        .semantics { contentDescription = "Brillo del color" })
}
