package com.stylusmemo.app.plugin.memorize

/** Axis-aligned rectangle expressed in page millimetres (pure data, no Android types). */
data class RectDataMm(
    val leftMm: Float,
    val topMm: Float,
    val widthMm: Float,
    val heightMm: Float,
) {
    fun intersects(other: RectDataMm): Boolean {
        if (leftMm >= other.leftMm + other.widthMm) return false
        if (other.leftMm >= leftMm + widthMm) return false
        if (topMm >= other.topMm + other.heightMm) return false
        if (other.topMm >= topMm + heightMm) return false
        return true
    }

    fun movedBy(dxMm: Float, dyMm: Float): RectDataMm =
        copy(leftMm = leftMm + dxMm, topMm = topMm + dyMm)
}
