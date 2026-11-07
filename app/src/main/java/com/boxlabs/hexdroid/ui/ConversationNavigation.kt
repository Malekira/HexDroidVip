package com.boxlabs.hexdroid.ui

import kotlin.math.abs

/** Left advances through the visible tabs; right returns. Edges never wrap. */
internal fun adjacentConversation(
    keys: List<String>,
    selected: String,
    horizontalDistance: Float,
    threshold: Float,
): String? {
    if (!horizontalDistance.isFinite() || abs(horizontalDistance) < threshold || horizontalDistance == 0f) return null
    val index = keys.indexOf(selected)
    if (index < 0) return null
    return keys.getOrNull(index + if (horizontalDistance < 0) 1 else -1)
}
