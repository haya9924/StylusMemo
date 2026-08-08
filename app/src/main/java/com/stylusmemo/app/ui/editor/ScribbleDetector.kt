package com.stylusmemo.app.ui.editor

import android.util.Log
import kotlin.math.hypot
import kotlin.math.max

/**
 * GoodNotes-style scribble-to-erase detector. Keeps a small sliding window of raw touch points
 * — no decimation — and checks whether the motion looks like a dense back-and-forth scribble.
 *
 * Detection uses three complementary, direction-invariant metrics (any one triggers):
 * 1. Axis sign flips of segment velocity — catches zigzags.
 * 2. Dot-product reversals of consecutive vectors — catches sharp direction changes.
 * 3. Tortuosity (path-length / net-displacement) — catches circles and random scrubbing.
 */
object ScribbleDetector {

    const val WINDOW_POINTS = 14
    const val MIN_REVERSALS = 5
    const val MIN_PATH_LENGTH_MM = 4.5f
    const val TORTUOSITY_PATH_MM = 7.5f
    const val TORTUOSITY_RATIO = 3.75f

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
     * Direction-invariant scribble detection with three complementary metrics.
     */
    fun isScribbleMotion(points: List<Float>): Boolean {
        val n = points.size / 2
        if (n < 5) return false

        var xFlips = 0
        var yFlips = 0
        var lastXSign = 0
        var lastYSign = 0
        var reversals = 0
        var prevDx = 0f
        var prevDy = 0f
        var hasPrevVec = false
        var pathLen = 0f

        val firstX = points[0]
        val firstY = points[1]
        val lastX = points[(n - 1) * 2]
        val lastY = points[(n - 1) * 2 + 1]

        for (i in 1 until n) {
            val ax = points[(i - 1) * 2]
            val ay = points[(i - 1) * 2 + 1]
            val bx = points[i * 2]
            val by = points[i * 2 + 1]
            val dx = bx - ax
            val dy = by - ay
            val len = hypot(dx, dy)

            pathLen += len

            if (len < 0.05f) continue

            val xSign = if (dx > 0.1f) 1 else if (dx < -0.1f) -1 else 0
            val ySign = if (dy > 0.1f) 1 else if (dy < -0.1f) -1 else 0
            if (xSign != 0) {
                if (lastXSign != 0 && xSign != lastXSign) xFlips++
                lastXSign = xSign
            }
            if (ySign != 0) {
                if (lastYSign != 0 && ySign != lastYSign) yFlips++
                lastYSign = ySign
            }
            if (hasPrevVec) {
                if (dx * prevDx + dy * prevDy < 0f) reversals++
            }
            prevDx = dx
            prevDy = dy
            hasPrevVec = true
        }

        val netDisp = hypot(lastX - firstX, lastY - firstY)
        val tortuous = pathLen >= TORTUOSITY_PATH_MM && (netDisp <= 0f || pathLen > TORTUOSITY_RATIO * netDisp)

        val flips = max(xFlips, yFlips)
        val result = flips >= MIN_REVERSALS || reversals >= MIN_REVERSALS || tortuous

        if (pathLen >= 2f) {
            Log.d("ScribbleDetect",
                "n=$n path=$pathLen net=$netDisp xFlip=$xFlips yFlip=$yFlips rev=$reversals tort=$tortuous result=$result")
        }

        if (pathLen < MIN_PATH_LENGTH_MM) return false
        return result
    }
}
