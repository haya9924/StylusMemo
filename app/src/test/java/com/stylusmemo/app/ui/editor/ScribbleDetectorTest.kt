package com.stylusmemo.app.ui.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ScribbleDetectorTest {

    /** A zigzag with the given amplitude/wavelength, sampled every `spacing` mm. */
    private fun zigzag(
        startX: Float,
        startY: Float,
        amplitude: Float,
        wavelength: Float,
        passes: Int,
        spacing: Float,
    ): List<Pair<Float, Float>> {
        val out = mutableListOf<Pair<Float, Float>>()
        var x = startX
        var y = startY
        var up = true
        var traveled = 0f
        var phase = 0f
        while (traveled < wavelength * passes) {
            val dx = spacing
            val dy = if (up) amplitude * spacing else -amplitude * spacing
            x += dx
            y += dy
            out.add(x to y)
            traveled += spacing
            phase += spacing
            if (phase >= wavelength) {
                up = !up
                phase = 0f
            }
        }
        return out
    }

    private fun circle(
        cx: Float,
        cy: Float,
        radius: Float,
        revolutions: Float,
        samples: Int,
    ): List<Pair<Float, Float>> {
        return (0 until samples).map { i ->
            val angle = 2.0 * Math.PI * revolutions * i / (samples - 1)
            (cx + radius * cos(angle)).toFloat() to (cy + radius * sin(angle)).toFloat()
        }
    }

    @Test
    fun zigzagPositiveXDetects() {
        val pts = zigzag(0f, 0f, amplitude = 1.5f, wavelength = 0.8f, passes = 8, spacing = 0.4f)
        assertTrue(ScribbleDetector.detectAll(pts))
    }

    @Test
    fun zigzagNegativeXDetects() {
        val pts = zigzag(30f, 0f, amplitude = 1.5f, wavelength = 0.8f, passes = 8, spacing = 0.4f)
            .map { (x, y) -> -x to y }
        assertTrue(ScribbleDetector.detectAll(pts))
    }

    @Test
    fun zigzagPositiveYDetects() {
        val pts = zigzag(0f, 0f, amplitude = 1.5f, wavelength = 0.8f, passes = 8, spacing = 0.4f)
            .map { (x, y) -> y to x }
        assertTrue(ScribbleDetector.detectAll(pts))
    }

    @Test
    fun zigzagNegativeYDetects() {
        val pts = zigzag(0f, 30f, amplitude = 1.5f, wavelength = 0.8f, passes = 8, spacing = 0.4f)
            .map { (x, y) -> y to -x }
        assertTrue(ScribbleDetector.detectAll(pts))
    }

    @Test
    fun circularScrubDetects() {
        val pts = circle(0f, 0f, radius = 2f, revolutions = 3f, samples = 40)
        assertTrue(ScribbleDetector.detectAll(pts))
    }

    @Test
    fun straightLineDoesNotDetect() {
        val pts = (0..30).map { i -> i * 0.8f to 0f }
        assertFalse(ScribbleDetector.detectAll(pts))
    }

    @Test
    fun gentleCurveDoesNotDetect() {
        val pts = (0..30).map { i ->
            val t = i * 0.2f
            t to (0.3f * sin(t * 0.5f))
        }
        assertFalse(ScribbleDetector.detectAll(pts))
    }

    @Test
    fun singleFoldLetterDoesNotDetect() {
        val pts = listOf(
            0f to 0f,
            2f to 1f,
            4f to 3f,
            6f to 6f,
            5f to 8f,
            3f to 10f,
            1f to 12f,
            0f to 13f,
        )
        assertFalse(ScribbleDetector.detectAll(pts))
    }
}
