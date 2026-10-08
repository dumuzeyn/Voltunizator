package com.dumuzeyn.mp3player

import kotlin.math.hypot
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverShapeGeometryTest {
    @Test fun hexagonHasSixEqualSidesOnNonSquareArtwork() {
        val vertices = CoverShapeGeometry.vertices("hexagon", 140f, 100f)
        assertEquals(6, vertices.size)
        val sides = vertices.indices.map { index ->
            val first = vertices[index]
            val next = vertices[(index + 1) % vertices.size]
            hypot(first.first - next.first, first.second - next.second)
        }
        assertTrue(sides.maxOrNull()!! - sides.minOrNull()!! < 0.001f)
    }

    @Test fun starHasFiveSymmetricOuterPointsAndShallowNotches() {
        val vertices = CoverShapeGeometry.vertices("star", 100f, 100f)
        assertEquals(10, vertices.size)
        val outer = vertices.filterIndexed { index, _ -> index % 2 == 0 }
        val radii = outer.map { hypot(it.first - 50f, it.second - 50f) }
        assertTrue(radii.maxOrNull()!! - radii.minOrNull()!! < 0.001f)
        assertTrue(vertices.any { it.second == 0f })
        val inner = vertices.filterIndexed { index, _ -> index % 2 != 0 }
        val innerRadii = inner.map { hypot(it.first - 50f, it.second - 50f) }
        assertTrue(innerRadii.all { it / radii.first() in 0.54f..0.56f })
    }

    @Test fun triangleHasEqualSidesAndFitsNonSquareArtwork() {
        for ((width, height) in listOf(100f to 100f, 140f to 100f, 100f to 140f)) {
            val vertices = CoverShapeGeometry.vertices("triangle", width, height)
            assertEquals(3, vertices.size)
            val sides = vertices.indices.map { index ->
                val first = vertices[index]
                val next = vertices[(index + 1) % vertices.size]
                hypot(first.first - next.first, first.second - next.second)
            }
            assertTrue(sides.maxOrNull()!! - sides.minOrNull()!! < 0.001f)
            assertTrue(vertices.all { it.first in 0f..width && it.second in 0f..height })
            assertEquals(height / 2f, vertices[1].second, 0.001f)
        }
    }

    @Test fun rotatedSquareAlwaysFitsItsOriginalBounds() {
        for (degree in 0..90) {
            val scale = CoverShapeGeometry.rotationFitScale("rounded", 100f, 100f)
            val radians = degree * PI / 180.0
            val transformedExtent = scale * (abs(cos(radians)) + abs(sin(radians)))
            assertTrue(transformedExtent <= 1.0001)
        }
        assertEquals(1f, CoverShapeGeometry.rotationFitScale("circle", 100f, 100f), 0f)
    }

    @Test fun everyPolygonFitsAtEveryAngleUsingOneFixedScale() {
        for (shape in listOf("triangle", "hexagon", "star", "diamond", "rounded")) {
            for ((width, height) in listOf(100f to 100f, 140f to 100f, 100f to 140f)) {
                val scale = CoverShapeGeometry.rotationFitScale(shape, width, height)
                val vertices = when (shape) {
                    "rounded" -> listOf(0f to 0f, width to 0f, width to height, 0f to height)
                    "diamond" -> listOf(width / 2f to 0f, width to height / 2f, width / 2f to height, 0f to height / 2f)
                    else -> CoverShapeGeometry.vertices(shape, width, height)
                }
                for (degree in 0..359) {
                    val angle = degree * PI / 180.0
                    for ((x, y) in vertices) {
                        val dx = (x - width / 2f) * scale
                        val dy = (y - height / 2f) * scale
                        val rotatedX = dx * cos(angle) - dy * sin(angle)
                        val rotatedY = dx * sin(angle) + dy * cos(angle)
                        assertTrue("$shape clipped at $degree", abs(rotatedX) <= width / 2f + 0.001f &&
                            abs(rotatedY) <= height / 2f + 0.001f)
                    }
                }
            }
        }
    }
}
