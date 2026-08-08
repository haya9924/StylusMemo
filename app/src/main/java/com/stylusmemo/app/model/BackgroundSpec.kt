package com.stylusmemo.app.model

import kotlinx.serialization.Serializable

enum class BackgroundType(val displayName: String) {
    BLANK("無地"),
    GRID("方眼"),
    RULED("罫線"),
    DOT("ドット"),
}

/**
 * Page background template definition. Colors are ARGB stored in a Long (upper 32 bits).
 * If [backgroundImageName] is set, that bitmap (stored in the note's assets folder) is drawn
 * instead of the pattern; the pattern fields are then ignored.
 */
@Serializable
data class BackgroundSpec(
    val type: BackgroundType = BackgroundType.BLANK,
    val spacingMm: Float = 5f,
    val minorColorArgb: Long = 0xFFB0BEC5,
    val majorColorArgb: Long = 0xFF78909C,
    val majorEvery: Int = 5,
    val ruledColorArgb: Long = 0xFF90A4AE,
    val lineThicknessMm: Float = 0.3f,
    val marginColorArgb: Long = 0xFFE57373,
    val marginXMm: Float = 25f,
    val dotColorArgb: Long = 0xFF90A4AE,
    val backgroundImageName: String? = null,
) {
    companion object {
        fun defaultGrid() = BackgroundSpec(type = BackgroundType.GRID)

        fun defaultRuled() = BackgroundSpec(
            type = BackgroundType.RULED,
            spacingMm = 8f,
            lineThicknessMm = 0.4f,
        )

        fun defaultDot() = BackgroundSpec(type = BackgroundType.DOT)
    }
}
