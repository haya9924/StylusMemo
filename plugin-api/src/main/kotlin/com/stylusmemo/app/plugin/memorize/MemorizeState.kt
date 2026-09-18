package com.stylusmemo.app.plugin.memorize

enum class MemorizePaneDirection(val displayName: String) {
    NOTE_LEFT("ノート左"),
    NOTE_RIGHT("ノート右"),
    NOTE_TOP("ノート上"),
    NOTE_BOTTOM("ノート下"),
}

/** Split-pane arrangement: where the memorized note sits and how much room it takes. */
data class MemorizeLayout(
    val direction: MemorizePaneDirection = MemorizePaneDirection.NOTE_LEFT,
    val notePaneRatio: Float = 0.5f,
) {
    fun withDirection(direction: MemorizePaneDirection): MemorizeLayout = copy(direction = direction)

    fun withRatio(ratio: Float): MemorizeLayout =
        copy(notePaneRatio = ratio.coerceIn(MIN_RATIO, MAX_RATIO))

    companion object {
        const val MIN_RATIO = 0.25f
        const val MAX_RATIO = 0.75f
    }
}

/** Full observable state of one memorization session. */
data class MemorizeState(
    val memorizeColorArgb: Int = DEFAULT_COLOR,
    val tolerance: Float = DEFAULT_TOLERANCE,
    val sheetRectMm: RectDataMm = DEFAULT_SHEET,
    val layout: MemorizeLayout = MemorizeLayout(),
) {
    companion object {
        const val DEFAULT_COLOR = 0xFFE53935.toInt()
        const val DEFAULT_TOLERANCE = 90f
        val DEFAULT_SHEET = RectDataMm(leftMm = 55f, topMm = 100f, widthMm = 120f, heightMm = 90f)
    }
}
