package com.stylusmemo.app.plugin.memorize

import kotlin.math.sqrt

/** Pure RGB-distance color matching behind the red-sheet hiding logic. */
object ColorMatcher {

    /** Euclidean distance of the RGB channels, alpha ignored. */
    fun distanceArgb(a: Int, b: Int): Float {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toFloat())
    }

    /**
     * Distance from [candidateArgb] to the line segment from [targetArgb] to white. Anti-aliased
     * edges of colored ink are blends of the ink color with the white paper, so they lie close to
     * this segment even when their plain RGB distance to the ink color is large.
     */
    fun fringeDistanceArgb(candidateArgb: Int, targetArgb: Int): Float {
        val cr = (candidateArgb shr 16) and 0xFF
        val cg = (candidateArgb shr 8) and 0xFF
        val cb = candidateArgb and 0xFF
        val tr = (targetArgb shr 16) and 0xFF
        val tg = (targetArgb shr 8) and 0xFF
        val tb = targetArgb and 0xFF
        val vr = 255 - tr
        val vg = 255 - tg
        val vb = 255 - tb
        val lenSq = vr * vr + vg * vg + vb * vb
        if (lenSq == 0) {
            val dr = cr - 255
            val dg = cg - 255
            val db = cb - 255
            return sqrt((dr * dr + dg * dg + db * db).toFloat())
        }
        val t = (((cr - tr) * vr + (cg - tg) * vg + (cb - tb) * vb).toFloat() / lenSq)
            .coerceIn(0f, 1f)
        val pr = tr + t * vr
        val pg = tg + t * vg
        val pb = tb + t * vb
        val dr = cr - pr
        val dg = cg - pg
        val db = cb - pb
        return sqrt(dr * dr + dg * dg + db * db)
    }

    /** True when the color is the target ink or an anti-aliased blend of it with white paper. */
    fun matches(candidateArgb: Int, targetArgb: Int, tolerance: Float): Boolean =
        distanceArgb(candidateArgb, targetArgb) <= tolerance ||
            fringeDistanceArgb(candidateArgb, targetArgb) <= tolerance
}
