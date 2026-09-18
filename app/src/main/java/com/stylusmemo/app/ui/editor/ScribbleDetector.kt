package com.stylusmemo.app.ui.editor

import android.util.Log
import kotlin.math.hypot
import kotlin.math.max

/**
 * GoodNotes-style scribble-to-erase detector. Keeps a small sliding window of raw points
 * — no decimation — and checks whether the motion looks like a dense back-and-forth scribble.
 *
 * Detection is deliberately rate/speed independent: instead of comparing the sign of every tiny
 * segment (which misses slow scribbles whose per-sample delta is below the threshold), it
 * accumulates movement until it is significant and then looks for direction reversals.
 *
 * Triggers when any of these hold:
 * 1. Enough axis direction flips of the accumulated velocity — catches zigzags.
 * 2. Enough dot-product reversals of consecutive accumulated vectors — catches sharp turns.
 * 3. Tortuosity (path-length / net-displacement) — catches circles and random scrubbing.
 */
object ScribbleDetector {

    const val WINDOW_POINTS = 24
    const val MIN_REVERSALS = 4
    const val MIN_PATH_LENGTH_MM = 4.5f
    const val TORTUOSITY_PATH_MM = 8.0f
    const val TORTUOSITY_RATIO = 3.5f

    /** Accumulated movement (mm) before a direction flip is counted. */
    const val FLIP_MIN_MM = 0.5f

    /** Runs the detector over a stream of (x, y) samples. Returns true once scribble is detected. */
    fun detectAll(samples: List<Pair<Float, Float>>): Boolean {
        val window = mutableListOf<Float>()
        var detected = false
        for ((x, y) in samples) {
            if (detect(x, y, window)) detected = true
        }
        return detected
    }

    /**
     * Stateful streaming detection: feed one raw point at a time, returns true once the recent
     * window looks like scribbling.
     */
    fun detect(x: Float, y: Float, points: MutableList<Float>): Boolean {
        points.add(x)
        points.add(y)
        while (points.size > WINDOW_POINTS * 2) {
            points.removeAt(0)
            points.removeAt(0)
        }
        return isScribbleMotion(points)
    }

    /**
     * Direction-invariant, speed-invariant scribble detection with three complementary metrics.
     */
    fun isScribbleMotion(points: List<Float>): Boolean {
        val n = points.size / 2
        if (n < 5) return false

        var xFlips = 0
        var yFlips = 0
        var reversals = 0
        var pathLen = 0f
        var netDx = 0f
        var netDy = 0f

        // Accumulated direction since the last counted flip, in each axis.
        var accX = 0f
        var accY = 0f
        var lastXSign = 0
        var lastYSign = 0
        var lastVecX = 0f
        var lastVecY = 0f

        val firstX = points[0]
        val firstY = points[1]

        for (i in 1 until n) {
            val ax = points[(i - 1) * 2]
            val ay = points[(i - 1) * 2 + 1]
            val bx = points[i * 2]
            val by = points[i * 2 + 1]
            val dx = bx - ax
            val dy = by - ay
            pathLen += hypot(dx, dy)
            netDx += dx
            netDy += dy
            accX += dx
            accY += dy

            if (accX >= FLIP_MIN_MM || accX <= -FLIP_MIN_MM) {
                val sign = if (accX > 0f) 1 else -1
                if (lastXSign != 0 && sign != lastXSign) xFlips++
                lastXSign = sign
                accX = 0f
            }
            if (accY >= FLIP_MIN_MM || accY <= -FLIP_MIN_MM) {
                val sign = if (accY > 0f) 1 else -1
                if (lastYSign != 0 && sign != lastYSign) yFlips++
                lastYSign = sign
                accY = 0f
            }
            // Dot-product reversal of significant accumulated vectors.
            val mag = hypot(netDx, netDy)
            if (mag >= FLIP_MIN_MM) {
                if (lastVecX != 0f || lastVecY != 0f) {
                    if (netDx * lastVecX + netDy * lastVecY < 0f) reversals++
                }
                lastVecX = netDx
                lastVecY = netDy
                netDx = 0f
                netDy = 0f
            }
        }

        val totalNet = hypot(points[(n - 1) * 2] - firstX, points[(n - 1) * 2 + 1] - firstY)
        val tortuous = pathLen >= TORTUOSITY_PATH_MM &&
            (totalNet <= 0f || pathLen > TORTUOSITY_RATIO * totalNet)

        val flips = max(xFlips, yFlips)
        val result = flips >= MIN_REVERSALS || reversals >= MIN_REVERSALS || tortuous

        if (pathLen >= 2f) {
            Log.d("ScribbleDetect",
                "n=$n path=$pathLen net=$totalNet xFlip=$xFlips yFlip=$yFlips rev=$reversals tort=$tortuous result=$result")
        }

        if (pathLen < MIN_PATH_LENGTH_MM) return false
        return result
    }
}
