package com.stylusmemo.app.plugin.memorize

/**
 * Information the host app gives to a memorization session.
 * Pure interface so plugins stay free of Android dependencies.
 */
interface MemorizeHost {
    /** Size of the page currently shown in the note pane (millimetres). */
    val pageWidthMm: Float
    val pageHeightMm: Float
}

/**
 * One memorization session: the pure state machine behind the red-sheet mode.
 * The host renders the UI (split panes, sheet overlay, scratch surface) from
 * [state]; every mutation notifies [listener] synchronously.
 */
interface MemorizeSession {
    val state: MemorizeState

    /** Called on every state change (on the thread that made the change). */
    var listener: ((MemorizeState) -> Unit)?

    fun setColor(argb: Int)
    fun setTolerance(tolerance: Float)
    fun setSheetRect(rectMm: RectDataMm)
    fun moveSheet(dxMm: Float, dyMm: Float)
    fun setLayout(layout: MemorizeLayout)

    /** True when a stroke/text color should be treated as the memorization color. */
    fun colorMatches(argb: Int): Boolean

    /**
     * True when content with the given color and bounding box (page millimetres)
     * is currently hidden under the sheet.
     */
    fun contentHidden(bboxMm: RectDataMm, argb: Int): Boolean

    fun end()
}
