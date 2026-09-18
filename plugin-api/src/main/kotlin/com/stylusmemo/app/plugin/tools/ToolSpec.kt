package com.stylusmemo.app.plugin.tools

/**
 * Pure-Kotlin description of a drawing tool contributed by a plugin.
 *
 * The host turns this into an actual ink brush, so plugins stay free of Android/ink types.
 *
 * @param colorArgb stroke colour including alpha (translucent colours act like highlighters).
 * @param sizeMm stroke width in millimetres.
 * @param squareCap use a flat/square end instead of a round one (marker-like).
 * @param drawBehind draw these strokes underneath regular ink (so pen text stays readable).
 * @param blendMultiply multiply-blend the stroke (classic highlighter over text).
 */
data class ToolSpec(
    val id: String,
    val colorArgb: Int,
    val sizeMm: Float,
    val squareCap: Boolean = false,
    val drawBehind: Boolean = true,
    val blendMultiply: Boolean = true,
) {
    val isTranslucent: Boolean
        get() = ((colorArgb ushr 24) and 0xFF) < 0xFF
}
