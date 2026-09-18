package com.stylusmemo.app.model

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Serializable
data class Snip(
    val id: String = newId("s"),
    val assetName: String,
    val widthPx: Int,
    val heightPx: Int,
    val displayHeightDp: Float = 160f,
    val collapsed: Boolean = false,
)

data class SnipCrop private constructor(
    val leftMm: Float,
    val topMm: Float,
    val rightMm: Float,
    val bottomMm: Float,
    val widthPx: Int,
    val heightPx: Int,
    val pixelsPerMm: Double,
) {
    companion object {
        const val MAX_PIXELS = 2_000_000L
        const val MAX_DIMENSION = 8192

        fun create(pageWidth: Float, pageHeight: Float, x0: Float, y0: Float, x1: Float, y1: Float): SnipCrop? {
            if (listOf(pageWidth, pageHeight, x0, y0, x1, y1).any { !it.isFinite() }) return null
            if (pageWidth <= 0f || pageHeight <= 0f) return null
            val left = min(x0, x1).coerceIn(0f, pageWidth)
            val top = min(y0, y1).coerceIn(0f, pageHeight)
            val right = max(x0, x1).coerceIn(0f, pageWidth)
            val bottom = max(y0, y1).coerceIn(0f, pageHeight)
            val w = (right - left).toDouble()
            val h = (bottom - top).toDouble()
            if (w < 1.0 || h < 1.0) return null
            val scale = minOf(300.0 / 25.4, sqrt(MAX_PIXELS / (w * h)), MAX_DIMENSION / max(w, h))
            val width = (w * scale).roundToInt().coerceAtLeast(1)
            val height = (h * scale).roundToInt().coerceAtLeast(1)
            if (width.toLong() * height > MAX_PIXELS) return null
            return SnipCrop(left, top, right, bottom, width, height, scale)
        }
    }
}
