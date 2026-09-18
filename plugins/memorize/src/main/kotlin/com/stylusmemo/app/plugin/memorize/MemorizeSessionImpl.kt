package com.stylusmemo.app.plugin.memorize

/**
 * Built-in red-sheet memorization session. Owns the session state machine; the host
 * app renders split panes, the sheet overlay and the scratch surface from [state].
 */
class MemorizeSessionImpl(private val host: MemorizeHost) : MemorizeSession {

    private var current: MemorizeState = MemorizeState(sheetRectMm = defaultSheet())

    override val state: MemorizeState
        get() = current

    override var listener: ((MemorizeState) -> Unit)? = null

    private fun defaultSheet(): RectDataMm {
        val pageW = host.pageWidthMm.takeIf { it > 0f } ?: 210f
        val pageH = host.pageHeightMm.takeIf { it > 0f } ?: 297f
        val w = (pageW * 0.6f).coerceAtLeast(10f)
        val h = (pageH * 0.3f).coerceAtLeast(10f)
        return clampToPage(
            RectDataMm(leftMm = (pageW - w) / 2f, topMm = (pageH - h) / 2f, widthMm = w, heightMm = h),
        )
    }

    private fun emit() {
        listener?.invoke(current)
    }

    override fun setColor(argb: Int) {
        if (current.memorizeColorArgb == argb) return
        current = current.copy(memorizeColorArgb = argb)
        emit()
    }

    override fun setTolerance(tolerance: Float) {
        val t = tolerance.coerceIn(0f, 441f)
        if (current.tolerance == t) return
        current = current.copy(tolerance = t)
        emit()
    }

    override fun setSheetRect(rectMm: RectDataMm) {
        val clamped = clampToPage(rectMm)
        if (current.sheetRectMm == clamped) return
        current = current.copy(sheetRectMm = clamped)
        emit()
    }

    override fun moveSheet(dxMm: Float, dyMm: Float) {
        if (dxMm == 0f && dyMm == 0f) return
        setSheetRect(current.sheetRectMm.movedBy(dxMm, dyMm))
    }

    override fun setLayout(layout: MemorizeLayout) {
        val normalized = layout.copy(notePaneRatio = layout.notePaneRatio.coerceIn(
            MemorizeLayout.MIN_RATIO, MemorizeLayout.MAX_RATIO,
        ))
        if (current.layout == normalized) return
        current = current.copy(layout = normalized)
        emit()
    }

    override fun colorMatches(argb: Int): Boolean =
        ColorMatcher.matches(argb, current.memorizeColorArgb, current.tolerance)

    override fun contentHidden(bboxMm: RectDataMm, argb: Int): Boolean =
        colorMatches(argb) && bboxMm.intersects(current.sheetRectMm)

    override fun end() {
        listener = null
    }

    private fun clampToPage(rect: RectDataMm): RectDataMm {
        val pageW = host.pageWidthMm.takeIf { it > 0f } ?: return rect
        val pageH = host.pageHeightMm.takeIf { it > 0f } ?: return rect
        val w = rect.widthMm.coerceIn(1f, pageW)
        val h = rect.heightMm.coerceIn(1f, pageH)
        val left = rect.leftMm.coerceIn(0f, (pageW - w).coerceAtLeast(0f))
        val top = rect.topMm.coerceIn(0f, (pageH - h).coerceAtLeast(0f))
        return RectDataMm(left, top, w, h)
    }
}
