package com.dumuzeyn.mp3player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Regular polygons fitted inside the artwork bounds without stretching their sides. */
internal object CoverShapeGeometry {
    fun vertices(shape: String, width: Float, height: Float): List<Pair<Float, Float>> {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(width / 2f, height / 2f)
        return when (shape) {
            "triangle" -> {
                val side = min(width * 0.92f * 2f / sqrt(3f), height * 0.92f)
                val triangleWidth = side * sqrt(3f) / 2f
                val left = centerX - triangleWidth / 2f
                listOf(
                    Pair(left, centerY - side / 2f),
                    Pair(centerX + triangleWidth / 2f, centerY),
                    Pair(left, centerY + side / 2f),
                )
            }
            "hexagon" -> {
                val r = min(width / 2f, height / sqrt(3f))
                List(6) { index ->
                    val angle = PI * index / 3.0
                    Pair(centerX + (cos(angle) * r).toFloat(),
                        centerY + (sin(angle) * r).toFloat())
                }
            }
            "star" -> List(10) { index ->
                val angle = -PI / 2 + PI * index / 5.0
                val pointRadius = if (index % 2 == 0) radius else radius * 0.55f
                Pair(centerX + (cos(angle) * pointRadius).toFloat(),
                    centerY + (sin(angle) * pointRadius).toFloat())
            }
            else -> emptyList()
        }
    }

    fun rotationFitScale(shape: String, width: Float, height: Float): Float {
        if (width <= 0f || height <= 0f) return 1f
        val radius = when (shape) {
            "circle", "diamond" -> maxOf(width, height) / 2f
            "hexagon", "triangle", "star" -> vertices(shape, width, height)
                .maxOf { hypot(it.first - width / 2f, it.second - height / 2f) }
            else -> hypot(width / 2f, height / 2f)
        }
        // A fixed bounding circle fits at every angle, so rotation never pulses in size.
        return min(1f, min(width, height) / (2f * radius))
    }
}
