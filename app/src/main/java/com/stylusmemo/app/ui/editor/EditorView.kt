package com.stylusmemo.app.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.stylusmemo.app.data.InkUtil
import com.stylusmemo.app.model.BackgroundSpec
import com.stylusmemo.app.model.BackgroundType
import com.stylusmemo.app.model.ImageBox
import com.stylusmemo.app.model.Note
import com.stylusmemo.app.model.PageData
import com.stylusmemo.app.model.PageLayoutMode
import com.stylusmemo.app.model.ShortcutAction
import com.stylusmemo.app.model.StylusButtonPattern
import com.stylusmemo.app.model.TextBox
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The core drawing surface. Handles stylus authoring (via [InProgressStrokesView] for low-latency
 * in-progress rendering), palm rejection (only stylus-tool pointers ink), zoom/pan, page background
 * templates, boxes (images/text), selection and undo/redo.
 *
 * Strokes are stored in page-millimeter space. During authoring the in-progress view works in
 * screen-pixel space; finished strokes are transformed into mm before being committed.
 */
class EditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var controller: EditorController? = null

    private var note: Note? = null
    private val strokes = mutableListOf<MutableList<Stroke>>()
    var pageIndex: Int = 0
        private set

    var pageLayoutMode: PageLayoutMode = PageLayoutMode.SINGLE
        private set
    private val pageContentBitmaps = mutableMapOf<Int, Bitmap>()
    private val assetBitmaps = mutableMapOf<String, Bitmap>()

    var zoomPxPerMm: Float = 3f
        private set
    var panX: Float = 0f
        private set
    var panY: Float = 0f
        private set
    private var fittedOnce = false

    var tool: EditorTool = EditorTool.PEN
        private set
    private var penColorArgb: Int = Color.rgb(0x1A, 0x1A, 0x1A)
    private var penSizeMm: Float = 0.5f
    private var eraserRadiusMm: Float = 1.5f
    private var fingerDrawEnabled: Boolean = false
    private var primaryAction: ShortcutAction = ShortcutAction.TOGGLE_ERASER
    private var secondaryAction: ShortcutAction = ShortcutAction.UNDO
    private var primaryPattern: StylusButtonPattern? = null
    private var secondaryPattern: StylusButtonPattern? = null

    private val inProgressView = InProgressStrokesView(context)
    private var activeStrokeId: InProgressStrokeId? = null
    private var activePointerId: Int = -1
    private var stylusPrimaryPressed = false
    private var stylusSecondaryPressed = false
    private var stylusPrimaryPatternPressed = false
    private var stylusSecondaryPatternPressed = false

    private var lastEraseX = 0f
    private var lastEraseY = 0f
    private val removedStrokesThisGesture = mutableListOf<Pair<Int, Stroke>>()

    private var gestureMode = GestureMode.NONE
    private var gesturePrimaryId = -1
    private var gStartZoom = 3f
    private var gStartPanX = 0f
    private var gStartPanY = 0f
    private var gStartDist = 0f
    private var gStartMidX = 0f
    private var gStartMidY = 0f
    private var lastPanX = 0f
    private var lastPanY = 0f

    private var selected: Selection? = null
    private var selectAction = SelectAction.NONE
    private var grabOffsetX = 0f
    private var grabOffsetY = 0f

    private val undoStack = ArrayDeque<EditOp>()
    private val redoStack = ArrayDeque<EditOp>()

    private val density = resources.displayMetrics.density
    private var scribbleEraseWidthMm: Float = 2.5f
    private var scribbleEraseColor: Int = Color.rgb(0xE5, 0x39, 0x35)
    private var scribbleEraseLastX = 0f
    private var scribbleEraseLastY = 0f
    private var scribbleEraseActive = false
    private val scribblePath = mutableListOf<Float>()
    private val scribbleRecentPoints = mutableListOf<Float>()

    private var lineActive = false
    private var lineStartMmX = 0f
    private var lineStartMmY = 0f
    private var lineEndMmX = 0f
    private var lineEndMmY = 0f
    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private var lassoActive = false
    private var lassoMoveActive = false
    private val lassoPoints = mutableListOf<Float>()
    private val lassoedStrokeIndices = mutableListOf<Int>()
    private var lassoMoveStartScreenX = 0f
    private var lassoMoveStartScreenY = 0f
    private var lassoMoveOriginStrokes = emptyList<Stroke>()
    private var lassoMovedThisGesture = false
    private val lassoPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.rgb(0x1E, 0x88, 0xE5)
        strokeWidth = 2f * density
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
        isAntiAlias = true
    }
    private val lassoSelectPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(70, 0x1E, 0x88, 0xE5)
        strokeWidth = 4f * density
        isAntiAlias = true
    }
    private val scribbleTrailPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val whitePaint = Paint().apply { color = Color.WHITE }
    private val shadowPaint = Paint().apply { color = Color.argb(60, 0, 0, 0) }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.rgb(0x90, 0x95, 0xA0)
        strokeWidth = 1f * density
    }
    private val selectPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.rgb(0x1E, 0x88, 0xE5)
        strokeWidth = 2f * density
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val handlePaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.rgb(0x1E, 0x88, 0xE5)
    }
    private val imageFilter = Paint().apply { isFilterBitmap = true }
    private val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)

    private enum class GestureMode { NONE, PAN, SCALE }
    private enum class SelectAction { NONE, MOVE, RESIZE, ROTATE }

    private sealed interface Selection {
        val zIndex: Int
        val id: String
        fun left(): Float
        fun top(): Float
        fun width(): Float
        fun height(): Float
        fun rotationDeg(): Float
        fun apply(left: Float, top: Float, width: Float, height: Float, rot: Float)
        fun state(): Any
    }

    private class TextSel(var box: TextBox) : Selection {
        override val zIndex get() = box.zIndex
        override val id get() = box.id
        override fun left() = box.leftMm
        override fun top() = box.topMm
        override fun width() = box.widthMm
        override fun height() = box.heightMm
        override fun rotationDeg() = box.rotationDeg
        override fun apply(left: Float, top: Float, width: Float, height: Float, rot: Float) {
            box = box.copy(leftMm = left, topMm = top, widthMm = width, heightMm = height, rotationDeg = rot)
        }
        override fun state() = box
    }

    private class ImageSel(var box: ImageBox) : Selection {
        override val zIndex get() = box.zIndex
        override val id get() = box.id
        override fun left() = box.leftMm
        override fun top() = box.topMm
        override fun width() = box.widthMm
        override fun height() = box.heightMm
        override fun rotationDeg() = box.rotationDeg
        override fun apply(left: Float, top: Float, width: Float, height: Float, rot: Float) {
            box = box.copy(leftMm = left, topMm = top, widthMm = width, heightMm = height, rotationDeg = rot)
        }
        override fun state() = box
    }

    private sealed interface EditOp {
        fun apply()
        fun undo()
    }

    private inner class AddStrokeOp(val page: Int, val stroke: Stroke) : EditOp {
        override fun apply() { strokes.getOrNull(page)?.add(stroke) }
        override fun undo() { strokes.getOrNull(page)?.remove(stroke) }
    }

    private inner class RemoveStrokesOp(
        val page: Int,
        val removed: List<Pair<Int, Stroke>>,
    ) : EditOp {
        override fun apply() {
            val list = strokes.getOrNull(page) ?: return
            removed.map { it.second }.forEach { list.remove(it) }
        }

        override fun undo() {
            val list = strokes.getOrNull(page) ?: return
            removed.sortedBy { it.first }.forEach { (idx, s) ->
                list.add(min(idx, list.size), s)
            }
        }
    }

    private inner class MoveStrokesOp(
        val page: Int,
        val indices: List<Int>,
        val before: List<Stroke>,
        val after: List<Stroke>,
    ) : EditOp {
        override fun apply() {
            val list = strokes.getOrNull(page) ?: return
            for ((j, si) in indices.withIndex()) {
                val a = after.getOrNull(j) ?: continue
                list[si] = a
            }
        }

        override fun undo() {
            val list = strokes.getOrNull(page) ?: return
            for ((j, si) in indices.withIndex()) {
                val b = before.getOrNull(j) ?: continue
                list[si] = b
            }
        }
    }

    private inner class BoxOp(val page: Int, val before: Any?, val after: Any?) : EditOp {
        override fun apply() = replaceBox(page, after)
        override fun undo() = replaceBox(page, before)
    }

    private inner class AddPageOp(val index: Int, val data: PageData) : EditOp {
        override fun apply() = insertPage(index, data)
        override fun undo() = removePageAt(index)
    }

    private inner class RemovePageOp(
        val index: Int,
        val data: PageData,
        val pageStrokes: List<Stroke>,
    ) : EditOp {
        override fun apply() = removePageAt(index)
        override fun undo() {
            insertPage(index, data)
            strokes.getOrNull(index)?.addAll(pageStrokes)
        }
    }

    private inner class PagePropsOp(
        val page: Int,
        val before: PageData,
        val after: PageData,
    ) : EditOp {
        override fun apply() = updatePageData(page, after)
        override fun undo() = updatePageData(page, before)
    }

    init {
        setWillNotDraw(false)
        isFocusable = true
        isFocusableInTouchMode = true
        inProgressView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(inProgressView)
        inProgressView.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(finished: Map<InProgressStrokeId, Stroke>) {
                val ids = finished.keys
                if (tool == EditorTool.SCRIBBLE_ERASE) {
                    if (removedStrokesThisGesture.isNotEmpty()) {
                        pushUndo(RemoveStrokesOp(pageIndex, removedStrokesThisGesture.toList()))
                        removedStrokesThisGesture.clear()
                        notifyDocChanged()
                    }
                } else {
                    for ((_, stroke) in finished) commitStroke(stroke)
                    renderContent()
                }
                inProgressView.removeFinishedStrokes(ids)
            }
        })
    }

    // ------------------------------------------------------------------ public API

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (primaryPattern?.keyCode != 0 || secondaryPattern?.keyCode != 0) {
            requestFocus()
        }
    }

    fun load(snapshot: EditorSnapshot) {
        note = snapshot.note
        strokes.clear()
        snapshot.strokes.forEach { strokes.add(it.toMutableList()) }
        pageIndex = 0
        undoStack.clear()
        redoStack.clear()
        selected = null
        clearLassoGesture()
        assetBitmaps.clear()
        pageContentBitmaps.clear()
        fittedOnce = false
        if (width > 0 && height > 0) {
            fitViewport()
            fittedOnce = true
        }
        renderAllContent()
        invalidate()
        notifyUndoRedo()
        notifyPageChanged()
    }

    fun buildSnapshot(): EditorSnapshot? {
        val n = note ?: return null
        return EditorSnapshot(n, strokes.map { it.toList() })
    }

    fun undo() {
        val op = undoStack.removeLastOrNull() ?: return
        op.undo()
        redoStack.addLast(op)
        afterStructuralChange()
    }

    fun redo() {
        val op = redoStack.removeLastOrNull() ?: return
        op.apply()
        undoStack.addLast(op)
        afterStructuralChange()
    }

    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()

    fun setTool(t: EditorTool) {
        tool = t
        if (t != EditorTool.SELECT) clearSelection()
        if (t != EditorTool.LASSO) clearLassoGesture()
        lineActive = false
        invalidate()
    }

    fun setPen(colorArgb: Int, sizeMm: Float) {
        penColorArgb = colorArgb
        penSizeMm = sizeMm
    }

    fun setShortcutActions(primary: ShortcutAction, secondary: ShortcutAction) {
        primaryAction = primary
        secondaryAction = secondary
    }

    fun setLearnedStylusPatterns(primary: StylusButtonPattern?, secondary: StylusButtonPattern?) {
        primaryPattern = primary
        secondaryPattern = secondary
        if (primary?.keyCode != 0 || secondary?.keyCode != 0) {
            requestFocus()
        }
    }

    fun setFingerDrawEnabled(enabled: Boolean) {
        fingerDrawEnabled = enabled
    }

    fun setLayoutMode(mode: PageLayoutMode) {
        if (pageLayoutMode == mode) return
        pageLayoutMode = mode
        selected = null
        if (note != null) {
            fitViewport()
            renderAllContent()
            invalidate()
        }
    }

    // ------------------------------------------------------------------ layout helpers

    private val pageGapMm: Float = 24f

    private fun pageOffsetMm(i: Int): Pair<Float, Float> {
        val n = note ?: return 0f to 0f
        var x = 0f
        var y = 0f
        for (j in 0 until i) {
            val p = n.pages[j]
            when (pageLayoutMode) {
                PageLayoutMode.VERTICAL -> y += p.heightMm + pageGapMm
                PageLayoutMode.HORIZONTAL -> x += p.widthMm + pageGapMm
                PageLayoutMode.SINGLE -> {}
            }
        }
        return x to y
    }

    private fun pageLeftPx(i: Int): Float = panX + pageOffsetMm(i).first * zoomPxPerMm

    private fun pageTopPx(i: Int): Float = panY + pageOffsetMm(i).second * zoomPxPerMm

    private fun documentSizeMm(): Pair<Float, Float> {
        val n = note ?: return 0f to 0f
        var w = 0f
        var h = 0f
        for (p in n.pages) {
            when (pageLayoutMode) {
                PageLayoutMode.VERTICAL -> {
                    w = max(w, p.widthMm)
                    h += p.heightMm + pageGapMm
                }
                PageLayoutMode.HORIZONTAL -> {
                    w += p.widthMm + pageGapMm
                    h = max(h, p.heightMm)
                }
                PageLayoutMode.SINGLE -> {
                    w = max(w, p.widthMm)
                    h = max(h, p.heightMm)
                }
            }
        }
        return w to h
    }

    private fun clampPan() {
        if (pageLayoutMode == PageLayoutMode.SINGLE) return
        val (docWmm, docHmm) = documentSizeMm()
        val docW = docWmm * zoomPxPerMm
        val docH = docHmm * zoomPxPerMm
        when (pageLayoutMode) {
            PageLayoutMode.VERTICAL -> {
                panY = panY.coerceIn(minOf(0f, height - docH), maxOf(0f, height - docH))
            }
            PageLayoutMode.HORIZONTAL -> {
                panX = panX.coerceIn(minOf(0f, width - docW), maxOf(0f, width - docW))
            }
            PageLayoutMode.SINGLE -> {}
        }
    }

    private fun scrollToPage(i: Int) {
        val n = note ?: return
        if (i < 0 || i >= n.pages.size) return
        val (ox, oy) = pageOffsetMm(i)
        val marginPx = 24f * density
        when (pageLayoutMode) {
            PageLayoutMode.VERTICAL -> panY = -oy * zoomPxPerMm + marginPx
            PageLayoutMode.HORIZONTAL -> panX = -ox * zoomPxPerMm + marginPx
            PageLayoutMode.SINGLE -> {}
        }
        clampPan()
        invalidate()
    }

    private fun pageAtScreenPoint(x: Float, y: Float): Int {
        val n = note ?: return pageIndex
        if (pageLayoutMode == PageLayoutMode.SINGLE) return pageIndex
        val docX = (x - panX) / zoomPxPerMm
        val docY = (y - panY) / zoomPxPerMm
        for (i in n.pages.indices) {
            val (ox, oy) = pageOffsetMm(i)
            val p = n.pages[i]
            if (docX >= ox && docX <= ox + p.widthMm && docY >= oy && docY <= oy + p.heightMm) return i
        }
        return pageIndex
    }

    fun switchPage(i: Int) {
        val n = note ?: return
        if (i < 0 || i >= n.pages.size || i == pageIndex) return
        pageIndex = i
        selected = null
        clearLassoGesture()
        renderContent()
        scrollToPage(i)
        invalidate()
        notifyPageChanged()
    }

    fun addPage() {
        val n = note ?: return
        val cur = n.pages.getOrNull(pageIndex) ?: n.pages.firstOrNull() ?: PageData()
        val newPage = PageData(widthMm = cur.widthMm, heightMm = cur.heightMm, background = cur.background)
        val index = n.pages.size
        pushUndo(AddPageOp(index, newPage))
        insertPage(index, newPage)
        renderContent()
        switchPage(index)
        notifyDocChanged()
    }

    fun deletePage() {
        val n = note ?: return
        if (n.pages.size <= 1) return
        val index = pageIndex
        val removed = n.pages[index]
        val removedStrokes = strokes.getOrNull(index)?.toList() ?: emptyList()
        pushUndo(RemovePageOp(index, removed, removedStrokes))
        removePageAt(index)
        if (pageIndex >= (note?.pages?.size ?: 1)) pageIndex = max(0, pageIndex - 1)
        selected = null
        clearLassoGesture()
        renderAllContent()
        scrollToPage(pageIndex)
        invalidate()
        notifyPageChanged()
        notifyDocChanged()
    }

    fun setPageSize(widthMm: Float, heightMm: Float) {
        val page = currentPage() ?: return
        val before = page
        val after = page.copy(widthMm = widthMm, heightMm = heightMm)
        pushUndo(PagePropsOp(pageIndex, before, after))
        updatePageData(pageIndex, after)
        fitViewport()
        renderAllContent()
        invalidate()
        notifyDocChanged()
    }

    fun setBackground(spec: BackgroundSpec) {
        val page = currentPage() ?: return
        val before = page
        val after = page.copy(background = spec)
        pushUndo(PagePropsOp(pageIndex, before, after))
        updatePageData(pageIndex, after)
        renderContent()
        invalidate()
        notifyDocChanged()
    }

    fun addTextBox(text: String, fontSizeMm: Float, colorArgb: Int) {
        val page = currentPage() ?: return
        val box = TextBox(
            text = text,
            fontSizeMm = fontSizeMm,
            colorArgb = colorArgb.toLong() and 0xFFFFFFFFL,
            leftMm = page.widthMm * 0.1f,
            topMm = page.heightMm * 0.1f,
            widthMm = min(120f, page.widthMm * 0.6f),
            heightMm = (fontSizeMm * 1.8f).coerceAtLeast(20f),
            zIndex = (page.textBoxes.maxOfOrNull { it.zIndex } ?: 0) + 1,
        )
        pushUndo(BoxOp(pageIndex, null, box))
        updatePage { p -> p.copy(textBoxes = p.textBoxes + box) }
        notifyDocChanged()
    }

    fun insertImageBox(assetName: String, widthMm: Float, heightMm: Float) {
        val page = currentPage() ?: return
        val top = page.imageBoxes.maxOfOrNull { it.topMm + it.heightMm } ?: page.heightMm * 0.1f
        val box = ImageBox(
            assetName = assetName,
            leftMm = (page.widthMm - widthMm) / 2f,
            topMm = top,
            widthMm = widthMm,
            heightMm = heightMm,
            zIndex = (page.imageBoxes.maxOfOrNull { it.zIndex } ?: 0) + 1,
        )
        pushUndo(BoxOp(pageIndex, null, box))
        updatePage { p -> p.copy(imageBoxes = p.imageBoxes + box) }
        notifyDocChanged()
    }

    fun updateSelectedText(boxId: String, text: String, fontSizeMm: Float, colorArgb: Int) {
        val page = currentPage() ?: return
        val old = page.textBoxes.firstOrNull { it.id == boxId } ?: return
        val updated = old.copy(
            text = text,
            fontSizeMm = fontSizeMm,
            colorArgb = colorArgb.toLong() and 0xFFFFFFFFL,
        )
        pushUndo(BoxOp(pageIndex, old, updated))
        updatePage { p -> p.copy(textBoxes = p.textBoxes.map { if (it.id == boxId) updated else it }) }
        (selected as? TextSel)?.box = updated
        invalidate()
        notifyDocChanged()
    }

    fun deleteSelectedBox() {
        val sel = selected ?: return
        pushUndo(BoxOp(pageIndex, sel.state(), null))
        if (sel is TextSel) {
            updatePage { p -> p.copy(textBoxes = p.textBoxes.filterNot { it.id == sel.id }) }
        } else if (sel is ImageSel) {
            updatePage { p -> p.copy(imageBoxes = p.imageBoxes.filterNot { it.id == sel.id }) }
        }
        selected = null
        invalidate()
        notifyDocChanged()
    }

    fun currentPageData(): PageData? = currentPage()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!fittedOnce && note != null && w > 0 && h > 0) {
            fitViewport()
            fittedOnce = true
            renderContent()
            invalidate()
        }
    }

    // ------------------------------------------------------------------ internals

    private fun currentPage(): PageData? = note?.pages?.getOrNull(pageIndex)

    private fun fitViewport() {
        if (width <= 0 || height <= 0) return
        val n = note ?: return
        val marginPx = 24f * density
        when (pageLayoutMode) {
            PageLayoutMode.SINGLE -> {
                val page = currentPage() ?: return
                zoomPxPerMm = ((width - marginPx * 2f) / page.widthMm).coerceIn(0.3f, 30f)
                panX = marginPx
                panY = (height - page.heightMm * zoomPxPerMm) / 2f
            }
            PageLayoutMode.VERTICAL -> {
                val p0 = n.pages.firstOrNull() ?: return
                zoomPxPerMm = ((width - marginPx * 2f) / p0.widthMm).coerceIn(0.3f, 30f)
                panX = marginPx
                panY = marginPx
            }
            PageLayoutMode.HORIZONTAL -> {
                val p0 = n.pages.firstOrNull() ?: return
                zoomPxPerMm = ((height - marginPx * 2f) / p0.heightMm).coerceIn(0.3f, 30f)
                panX = marginPx
                panY = marginPx
            }
        }
    }

    private fun updatePage(transform: (PageData) -> PageData) {
        val n = note ?: return
        note = n.copy(pages = n.pages.mapIndexed { i, p -> if (i == pageIndex) transform(p) else p })
        invalidate()
    }

    private fun updatePageData(page: Int, data: PageData) {
        val n = note ?: return
        note = n.copy(pages = n.pages.mapIndexed { i, p -> if (i == page) data else p })
        invalidate()
    }

    private fun replaceBox(page: Int, box: Any?) {
        val n = note ?: return
        note = n.copy(
            pages = n.pages.mapIndexed { i, p ->
                if (i != page) p else when (box) {
                    null -> p
                    is TextBox -> p.copy(
                        textBoxes = if (p.textBoxes.any { it.id == box.id }) {
                            p.textBoxes.map { if (it.id == box.id) box else it }
                        } else p.textBoxes + box,
                    )
                    is ImageBox -> p.copy(
                        imageBoxes = if (p.imageBoxes.any { it.id == box.id }) {
                            p.imageBoxes.map { if (it.id == box.id) box else it }
                        } else p.imageBoxes + box,
                    )
                    else -> p
                }
            },
        )
        invalidate()
    }

    private fun insertPage(index: Int, data: PageData) {
        val n = note ?: return
        note = n.copy(pages = n.pages.toMutableList().apply { add(index, data) })
        strokes.add(index, mutableListOf())
    }

    private fun removePageAt(index: Int) {
        val n = note ?: return
        note = n.copy(pages = n.pages.toMutableList().apply { removeAt(index) })
        if (index < strokes.size) strokes.removeAt(index)
    }

    private fun pushUndo(op: EditOp) {
        undoStack.addLast(op)
        redoStack.clear()
        notifyUndoRedo()
    }

    private fun afterStructuralChange() {
        selected = null
        renderAllContent()
        invalidate()
        notifyUndoRedo()
        notifyDocChanged()
    }

    private fun notifyUndoRedo() {
        controller?.onUndoRedoChanged?.invoke(canUndo(), canRedo())
    }

    private fun notifyPageChanged() {
        controller?.onPageChanged?.invoke()
    }

    private fun notifyDocChanged() {
        controller?.onDocChanged?.invoke()
    }

    private fun notifyToolChanged() {
        controller?.onToolChanged?.invoke(tool)
    }

    private fun clearSelection() {
        selected = null
        selectAction = SelectAction.NONE
        invalidate()
    }

    private fun commitStroke(screenStroke: Stroke) {
        val invZoom = 1f / zoomPxPerMm
        val (ox, oy) = pageOffsetMm(pageIndex)
        val screenToPage = Matrix().apply {
            setValues(floatArrayOf(
                invZoom, 0f, -panX * invZoom - ox,
                0f, invZoom, -panY * invZoom - oy,
                0f, 0f, 1f,
            ))
        }
        val mmStroke = InkUtil.transformStrokeToMm(screenStroke, screenToPage)
        val list = strokes.getOrNull(pageIndex) ?: mutableListOf<Stroke>().also { strokes.add(it) }
        list.add(mmStroke)
        pushUndo(AddStrokeOp(pageIndex, mmStroke))
        notifyDocChanged()
    }

    // ------------------------------------------------------------------ input

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (note == null) return false
        val action = event.actionMasked
        handleStylusButtonShortcuts(event)

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (activeStrokeId != null || activePointerId >= 0 || selectAction != SelectAction.NONE) {
                return true
            }
            val touchedPage = pageAtScreenPoint(
                event.getX(event.actionIndex),
                event.getY(event.actionIndex),
            )
            if (touchedPage != pageIndex) {
                pageIndex = touchedPage
                selected = null
                notifyPageChanged()
            }
            val stylusIdx = stylusPointerIndex(event)
            val eraserTool = stylusIdx >= 0 && event.getToolType(stylusIdx) == MotionEvent.TOOL_TYPE_ERASER
            when {
                stylusIdx >= 0 && !eraserTool && tool == EditorTool.PEN -> startDraw(event, stylusIdx)
                stylusIdx >= 0 && !eraserTool && tool == EditorTool.SCRIBBLE_ERASE -> startScribbleErase(event, stylusIdx)
                stylusIdx >= 0 && !eraserTool && tool == EditorTool.STRAIGHT_LINE -> startLine(event, stylusIdx)
                stylusIdx >= 0 && !eraserTool && tool == EditorTool.LASSO -> startLasso(event, stylusIdx)
                stylusIdx >= 0 && (eraserTool || tool == EditorTool.ERASER) -> startErase(event, stylusIdx)
                tool == EditorTool.SELECT -> startSelect(event, if (stylusIdx >= 0) stylusIdx else event.actionIndex)
                tool == EditorTool.PEN && fingerDrawEnabled -> startDraw(event, event.actionIndex)
                tool == EditorTool.SCRIBBLE_ERASE && fingerDrawEnabled -> startScribbleErase(event, event.actionIndex)
                tool == EditorTool.STRAIGHT_LINE && fingerDrawEnabled -> startLine(event, event.actionIndex)
                tool == EditorTool.LASSO && fingerDrawEnabled -> startLasso(event, event.actionIndex)
                else -> startGesture(event, event.actionIndex)
            }
            return true
        }

        if (action == MotionEvent.ACTION_MOVE) {
            checkLearnedPatternPressed(event)
            val strokeId = activeStrokeId
            when {
                tool == EditorTool.PEN && scribbleEraseActive -> {
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx >= 0) {
                        val hist = event.historySize
                        for (h in 0 until hist) {
                            val sx = event.getHistoricalX(idx, h)
                            val sy = event.getHistoricalY(idx, h)
                            processScribbleEraseMove(sx, sy)
                        }
                        processScribbleEraseMove(event.getX(idx), event.getY(idx))
                        invalidate()
                    }
                }
                strokeId != null && tool == EditorTool.PEN -> {
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx >= 0) {
                        val hist = event.historySize
                        var scribbleFound = false
                        for (h in 0 until hist) {
                            val sx = event.getHistoricalX(idx, h)
                            val sy = event.getHistoricalY(idx, h)
                            if (processPenMoveForScribble(sx, sy)) scribbleFound = true
                        }
                        if (processPenMoveForScribble(event.getX(idx), event.getY(idx))) scribbleFound = true
                        if (scribbleFound) {
                            inProgressView.cancelStroke(strokeId)
                            activeStrokeId = null
                            backfillScribbleErase()
                        } else {
                            inProgressView.addToStroke(event, activePointerId, strokeId)
                        }
                    }
                }
                tool == EditorTool.STRAIGHT_LINE && lineActive -> {
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx >= 0) {
                        lineEndMmX = toMmX(event, idx)
                        lineEndMmY = toMmY(event, idx)
                        invalidate()
                    }
                }
                tool == EditorTool.LASSO && lassoMoveActive -> moveLasso(event)
                tool == EditorTool.LASSO && lassoActive -> {
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx >= 0) {
                        lassoPoints.add(toMmX(event, idx))
                        lassoPoints.add(toMmY(event, idx))
                        invalidate()
                    }
                }
                strokeId != null && tool == EditorTool.SCRIBBLE_ERASE -> {
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx >= 0) {
                        inProgressView.addToStroke(event, activePointerId, strokeId)
                        val x = toMmX(event, idx)
                        val y = toMmY(event, idx)
                        scribbleEraseTo(scribbleEraseLastX, scribbleEraseLastY, x, y)
                        scribbleEraseLastX = x
                        scribbleEraseLastY = y
                    }
                }
                strokeId != null -> inProgressView.addToStroke(event, activePointerId, strokeId)
                activePointerId >= 0 && tool == EditorTool.ERASER -> moveErase(event)
                selectAction != SelectAction.NONE -> moveSelect(event)
                else -> updateGesture(event)
            }
            return true
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            stylusPrimaryPatternPressed = false
            stylusSecondaryPatternPressed = false
            val strokeId = activeStrokeId
            when {
                tool == EditorTool.PEN && scribbleEraseActive -> {
                    if (isNonActivePointerUp(event)) return true
                    activeStrokeId = null
                    activePointerId = -1
                    finalizeScribbleErase()
                }
                tool == EditorTool.STRAIGHT_LINE && lineActive -> {
                    if (isNonActivePointerUp(event)) return true
                    endLine()
                }
                tool == EditorTool.LASSO && lassoMoveActive -> {
                    if (isNonActivePointerUp(event)) return true
                    endLassoMove()
                    activePointerId = -1
                }
                tool == EditorTool.LASSO && lassoActive -> {
                    if (isNonActivePointerUp(event)) return true
                    endLasso()
                    activePointerId = -1
                }
                strokeId != null -> {
                    if (isNonActivePointerUp(event)) return true
                    inProgressView.finishStroke(event, activePointerId, strokeId)
                    activeStrokeId = null
                    activePointerId = -1
                }
                activePointerId >= 0 && tool == EditorTool.ERASER -> {
                    if (isNonActivePointerUp(event)) return true
                    endErase()
                    activePointerId = -1
                }
                selectAction != SelectAction.NONE -> {
                    if (isNonActivePointerUp(event)) return true
                    endSelect()
                    activePointerId = -1
                }
                else -> endGesture(event)
            }
            return true
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            stylusPrimaryPatternPressed = false
            stylusSecondaryPatternPressed = false
            activeStrokeId?.let { inProgressView.cancelStroke(it) }
            activeStrokeId = null
            activePointerId = -1
            selectAction = SelectAction.NONE
            gestureMode = GestureMode.NONE
            lineActive = false
            lassoActive = false
            lassoMoveActive = false
            lassoPoints.clear()
            scribbleEraseActive = false
            scribblePath.clear()
            scribbleRecentPoints.clear()
            return true
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (note == null) return super.onGenericMotionEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                val primary = event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)
                val secondary = event.isButtonPressed(MotionEvent.BUTTON_STYLUS_SECONDARY)
                if (primary && !stylusPrimaryPressed) runAction(primaryAction)
                if (secondary && !stylusSecondaryPressed) runAction(secondaryAction)
                stylusPrimaryPressed = primary
                stylusSecondaryPressed = secondary

                val learnedPrimary = matchesPattern(event, primaryPattern)
                val learnedSecondary = matchesPattern(event, secondaryPattern)
                if (learnedPrimary && !stylusPrimaryPatternPressed) runAction(primaryAction)
                if (learnedSecondary && !stylusSecondaryPatternPressed) runAction(secondaryAction)
                stylusPrimaryPatternPressed = learnedPrimary
                stylusSecondaryPatternPressed = learnedSecondary
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                stylusPrimaryPressed = false
                stylusSecondaryPressed = false
                stylusPrimaryPatternPressed = false
                stylusSecondaryPatternPressed = false
            }
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val code = event.keyCode
            if (primaryPattern?.keyCode == code) {
                runAction(primaryAction)
                return true
            }
            if (secondaryPattern?.keyCode == code) {
                runAction(secondaryAction)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleStylusButtonShortcuts(event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return
        if (matchesPattern(event, primaryPattern)) {
            runAction(primaryAction)
            return
        }
        if (matchesPattern(event, secondaryPattern)) {
            runAction(secondaryAction)
            return
        }
        val state = event.buttonState
        if (state and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) runAction(primaryAction)
        else if (state and MotionEvent.BUTTON_STYLUS_SECONDARY != 0) runAction(secondaryAction)
    }

    private fun matchesPattern(event: MotionEvent, p: StylusButtonPattern?): Boolean {
        if (p == null || p.isEmpty() || p.keyCode != 0) return false
        if (p.buttonState != 0 && event.buttonState and p.buttonState != 0) return true
        if (p.isToolTypeDistinctive) {
            for (i in 0 until event.pointerCount) {
                if (event.getToolType(i) == p.toolType) return true
            }
        }
        return false
    }

    /** Edge-triggered detection for touch events (styli without hover support). */
    private fun checkLearnedPatternPressed(event: MotionEvent) {
        val learnedPrimary = matchesPattern(event, primaryPattern)
        val learnedSecondary = matchesPattern(event, secondaryPattern)
        if (learnedPrimary && !stylusPrimaryPatternPressed) runAction(primaryAction)
        if (learnedSecondary && !stylusSecondaryPatternPressed) runAction(secondaryAction)
        stylusPrimaryPatternPressed = learnedPrimary
        stylusSecondaryPatternPressed = learnedSecondary
    }

    private fun runAction(action: ShortcutAction) {
        when (action) {
            ShortcutAction.NONE -> {}
            ShortcutAction.TOGGLE_ERASER -> {
                tool = if (tool == EditorTool.ERASER) EditorTool.PEN else EditorTool.ERASER
                clearSelection()
                clearLassoGesture()
                lineActive = false
                notifyToolChanged()
            }
            ShortcutAction.UNDO -> undo()
            ShortcutAction.REDO -> redo()
        }
    }

    private fun stylusPointerIndex(event: MotionEvent): Int {
        for (i in 0 until event.pointerCount) {
            val t = event.getToolType(i)
            if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) return i
        }
        return -1
    }

    private fun toMmX(event: MotionEvent, pointerIndex: Int): Float =
        (event.getX(pointerIndex) - panX) / zoomPxPerMm - pageOffsetMm(pageIndex).first

    private fun toMmY(event: MotionEvent, pointerIndex: Int): Float =
        (event.getY(pointerIndex) - panY) / zoomPxPerMm - pageOffsetMm(pageIndex).second

    private fun mmToScreenX(xMm: Float): Float =
        panX + pageOffsetMm(pageIndex).first * zoomPxPerMm + xMm * zoomPxPerMm

    private fun mmToScreenY(yMm: Float): Float =
        panY + pageOffsetMm(pageIndex).second * zoomPxPerMm + yMm * zoomPxPerMm

    private fun startDraw(event: MotionEvent, pointerIndex: Int) {
        activePointerId = event.getPointerId(pointerIndex)
        clearSelection()
        scribbleEraseActive = false
        scribblePath.clear()
        scribbleRecentPoints.clear()
        scribbleEraseLastX = toMmX(event, pointerIndex)
        scribbleEraseLastY = toMmY(event, pointerIndex)
        removedStrokesThisGesture.clear()
        val brush = InkUtil.penBrush(penColorArgb, penSizeMm * zoomPxPerMm)
        requestUnbufferedDispatch(event)
        activeStrokeId = inProgressView.startStroke(event, activePointerId, brush)
    }

    private fun startErase(event: MotionEvent, pointerIndex: Int) {
        activePointerId = event.getPointerId(pointerIndex)
        removedStrokesThisGesture.clear()
        lastEraseX = toMmX(event, pointerIndex)
        lastEraseY = toMmY(event, pointerIndex)
        requestUnbufferedDispatch(event)
    }

    private fun moveErase(event: MotionEvent) {
        val idx = event.findPointerIndex(activePointerId)
        if (idx < 0) return
        val x = toMmX(event, idx)
        val y = toMmY(event, idx)
        eraseSegment(lastEraseX, lastEraseY, x, y)
        lastEraseX = x
        lastEraseY = y
    }

    private fun eraseSegment(x1: Float, y1: Float, x2: Float, y2: Float) {
        val list = strokes.getOrNull(pageIndex) ?: return
        val toRemove = list.filter { strokeIntersects(it, x1, y1, x2, y2) }
        if (toRemove.isNotEmpty()) {
            for (s in toRemove) {
                val idx = list.indexOf(s)
                if (idx >= 0) removedStrokesThisGesture.add(idx to s)
                list.remove(s)
            }
            renderContent()
            invalidate()
        }
    }

    private fun strokeIntersects(s: Stroke, x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val r2 = eraserRadiusMm * eraserRadiusMm
        val batch = s.inputs
        for (i in 0 until batch.size) {
            val p = batch.get(i)
            if (distToSegmentSq(p.x, p.y, x1, y1, x2, y2) <= r2) return true
        }
        return false
    }

    // ------------------------------------------------------------------ scribble erase

    private fun startScribbleErase(event: MotionEvent, pointerIndex: Int) {
        activePointerId = event.getPointerId(pointerIndex)
        removedStrokesThisGesture.clear()
        scribbleEraseLastX = toMmX(event, pointerIndex)
        scribbleEraseLastY = toMmY(event, pointerIndex)
        clearSelection()
        val brush = InkUtil.penBrush(scribbleEraseColor, scribbleEraseWidthMm * zoomPxPerMm)
        requestUnbufferedDispatch(event)
        activeStrokeId = inProgressView.startStroke(event, activePointerId, brush)
    }

    private fun scribbleEraseTo(x1: Float, y1: Float, x2: Float, y2: Float) {
        val list = strokes.getOrNull(pageIndex) ?: return
        val r2 = (scribbleEraseWidthMm / 2f) * (scribbleEraseWidthMm / 2f)
        val toRemove = list.filter { s ->
            val batch = s.inputs
            (0 until batch.size).any { i ->
                val p = batch.get(i)
                distToSegmentSq(p.x, p.y, x1, y1, x2, y2) <= r2
            }
        }
        if (toRemove.isEmpty()) return
        for (s in toRemove) {
            val idx = list.indexOf(s)
            if (idx >= 0) removedStrokesThisGesture.add(idx to s)
            list.remove(s)
        }
        renderContent()
        invalidate()
    }

    /**
     * GoodNotes-style auto erase: called while drawing in the normal PEN tool. Returns true when the
     * recent motion looks like a dense back-and-forth scribble, in which case the stroke is treated
     * as an eraser instead of ink.
     */
    private fun detectScribble(x: Float, y: Float): Boolean =
        ScribbleDetector.detect(x, y, scribbleRecentPoints)

    /** Feeds one screen-space pen sample into scribble detection; returns true once detected. */
    private fun processPenMoveForScribble(screenX: Float, screenY: Float): Boolean {
        val x = toMmX(screenX)
        val y = toMmY(screenY)
        scribblePath.add(x)
        scribblePath.add(y)
        if (detectScribble(x, y)) {
            scribbleEraseActive = true
            return true
        }
        return false
    }

    /** Feeds one screen-space sample into an already-active scribble-erase gesture. */
    private fun processScribbleEraseMove(screenX: Float, screenY: Float) {
        val x = toMmX(screenX)
        val y = toMmY(screenY)
        scribblePath.add(x)
        scribblePath.add(y)
        scribbleEraseTo(scribbleEraseLastX, scribbleEraseLastY, x, y)
        scribbleEraseLastX = x
        scribbleEraseLastY = y
    }

    /** Screen space to mm, using raw pixel coordinates (used for historical samples). */
    private fun toMmX(screenX: Float): Float =
        (screenX - panX) / zoomPxPerMm - pageOffsetMm(pageIndex).first

    private fun toMmY(screenY: Float): Float =
        (screenY - panY) / zoomPxPerMm - pageOffsetMm(pageIndex).second

    /** Erases every stroke touched by the entire scribble path drawn so far. */
    private fun backfillScribbleErase() {
        val size = scribblePath.size
        if (size < 4) return
        for (i in 2 until size step 2) {
            scribbleEraseTo(scribblePath[i - 2], scribblePath[i - 1], scribblePath[i], scribblePath[i + 1])
        }
        scribbleEraseLastX = scribblePath[size - 2]
        scribbleEraseLastY = scribblePath[size - 1]
        invalidate()
    }

    private fun finalizeScribbleErase() {
        scribbleEraseActive = false
        scribblePath.clear()
        scribbleRecentPoints.clear()
        if (removedStrokesThisGesture.isNotEmpty()) {
            pushUndo(RemoveStrokesOp(pageIndex, removedStrokesThisGesture.toList()))
            removedStrokesThisGesture.clear()
            notifyDocChanged()
        }
        invalidate()
    }

    // ------------------------------------------------------------------ straight line

    private fun startLine(event: MotionEvent, pointerIndex: Int) {
        activePointerId = event.getPointerId(pointerIndex)
        lineStartMmX = toMmX(event, pointerIndex)
        lineStartMmY = toMmY(event, pointerIndex)
        lineEndMmX = lineStartMmX
        lineEndMmY = lineStartMmY
        lineActive = true
        clearSelection()
        requestUnbufferedDispatch(event)
        invalidate()
    }

    private fun endLine() {
        lineActive = false
        val brush = InkUtil.penBrush(penColorArgb, penSizeMm)
        val mmStroke = buildLineStroke(lineStartMmX, lineStartMmY, lineEndMmX, lineEndMmY, brush)
        activePointerId = -1
        if (mmStroke != null) {
            val list = strokes.getOrNull(pageIndex) ?: mutableListOf<Stroke>().also { strokes.add(it) }
            list.add(mmStroke)
            pushUndo(AddStrokeOp(pageIndex, mmStroke))
            notifyDocChanged()
            renderContent()
        }
        invalidate()
    }

    private fun buildLineStroke(x1: Float, y1: Float, x2: Float, y2: Float, brush: Brush): Stroke? {
        if (hypot(x2 - x1, y2 - y1) < 0.05f) return null
        val out = MutableStrokeInputBatch()
        out.add(InputToolType.STYLUS, x1, y1, 0L, 0.1f, 1f, 0f, 0f)
        out.add(InputToolType.STYLUS, x2, y2, 1L, 0.1f, 1f, 0f, 0f)
        return Stroke(brush, out.toImmutable())
    }

    // ------------------------------------------------------------------ lasso

    private fun startLasso(event: MotionEvent, pointerIndex: Int) {
        activePointerId = event.getPointerId(pointerIndex)
        val xMm = toMmX(event, pointerIndex)
        val yMm = toMmY(event, pointerIndex)
        if (lassoedStrokeIndices.isNotEmpty() && pointHitsLassoedStroke(xMm, yMm)) {
            lassoMoveActive = true
            lassoMoveStartScreenX = event.getX(pointerIndex)
            lassoMoveStartScreenY = event.getY(pointerIndex)
            lassoMoveOriginStrokes = lassoedStrokeIndices.mapNotNull { strokes.getOrNull(pageIndex)?.getOrNull(it) }
            lassoMovedThisGesture = false
        } else {
            clearLassoGesture()
            lassoActive = true
            lassoPoints.clear()
            lassoPoints.add(xMm)
            lassoPoints.add(yMm)
        }
        requestUnbufferedDispatch(event)
        invalidate()
    }

    private fun moveLasso(event: MotionEvent) {
        val idx = event.findPointerIndex(activePointerId)
        if (idx < 0) return
        val dxScreen = event.getX(idx) - lassoMoveStartScreenX
        val dyScreen = event.getY(idx) - lassoMoveStartScreenY
        val dxMm = dxScreen / zoomPxPerMm
        val dyMm = dyScreen / zoomPxPerMm
        if (hypot(dxMm, dyMm) < 0.05f) return
        lassoMovedThisGesture = true
        val list = strokes.getOrNull(pageIndex) ?: return
        val matrix = Matrix().apply { setTranslate(dxMm, dyMm) }
        for ((j, si) in lassoedStrokeIndices.withIndex()) {
            val original = lassoMoveOriginStrokes.getOrNull(j) ?: continue
            list[si] = InkUtil.transformStrokeToMm(original, matrix)
        }
        renderContent()
        invalidate()
    }

    private fun endLassoMove() {
        lassoMoveActive = false
        if (lassoMovedThisGesture) {
            val after = lassoedStrokeIndices.map { strokes[pageIndex][it] }
            pushUndo(MoveStrokesOp(pageIndex, lassoedStrokeIndices.toList(), lassoMoveOriginStrokes, after))
            notifyDocChanged()
        }
        lassoMoveOriginStrokes = emptyList()
        invalidate()
    }

    private fun endLasso() {
        lassoActive = false
        if (lassoPoints.size >= 6) {
            val indices = selectStrokesInPolygon()
            lassoedStrokeIndices.clear()
            lassoedStrokeIndices.addAll(indices)
        }
        lassoPoints.clear()
        invalidate()
    }

    private fun selectStrokesInPolygon(): List<Int> {
        val list = strokes.getOrNull(pageIndex) ?: return emptyList()
        return list.mapIndexedNotNull { si, s ->
            val batch = s.inputs
            val hit = (0 until batch.size).any { i -> pointInPolygon(batch.get(i).x, batch.get(i).y) }
            if (hit) si else null
        }
    }

    private fun pointInPolygon(x: Float, y: Float): Boolean {
        val n = lassoPoints.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = lassoPoints[i * 2]
            val yi = lassoPoints[i * 2 + 1]
            val xj = lassoPoints[j * 2]
            val yj = lassoPoints[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun pointHitsLassoedStroke(xMm: Float, yMm: Float): Boolean {
        val list = strokes.getOrNull(pageIndex) ?: return false
        for (si in lassoedStrokeIndices) {
            val s = list.getOrNull(si) ?: continue
            val batch = s.inputs
            for (i in 0 until batch.size) {
                val p = batch.get(i)
                if (hypot(p.x - xMm, p.y - yMm) <= 3f) return true
            }
        }
        return false
    }

    private fun clearLassoGesture() {
        lassoActive = false
        lassoMoveActive = false
        lassoPoints.clear()
        lassoedStrokeIndices.clear()
        lassoMoveOriginStrokes = emptyList()
        lassoMovedThisGesture = false
    }

    private fun distToSegmentSq(
        px: Float, py: Float,
        x1: Float, y1: Float, x2: Float, y2: Float,
    ): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-9f) return (px - x1) * (px - x1) + (py - y1) * (py - y1)
        val t = (((px - x1) * dx + (py - y1) * dy) / len2).coerceIn(0f, 1f)
        val cx = x1 + t * dx
        val cy = y1 + t * dy
        return (px - cx) * (px - cx) + (py - cy) * (py - cy)
    }

    private fun endErase() {
        if (removedStrokesThisGesture.isNotEmpty()) {
            pushUndo(RemoveStrokesOp(pageIndex, removedStrokesThisGesture.toList()))
            removedStrokesThisGesture.clear()
            notifyDocChanged()
        }
    }

    // ------------------------------------------------------------------ selection

    private fun boxAt(mmX: Float, mmY: Float): Selection? {
        val page = currentPage() ?: return null
        val all = page.textBoxes.map { TextSel(it) as Selection } +
            page.imageBoxes.map { ImageSel(it) as Selection }
        return all.filter { containsBox(it, mmX, mmY) }.maxByOrNull { it.zIndex }
    }

    private fun containsBox(sel: Selection, x: Float, y: Float): Boolean {
        val rot = Math.toRadians(sel.rotationDeg().toDouble())
        val cx = sel.left() + sel.width() / 2f
        val cy = sel.top() + sel.height() / 2f
        val dx = x - cx
        val dy = y - cy
        val c = cos(rot).toFloat()
        val s = sin(rot).toFloat()
        val rx = dx * c + dy * s
        val ry = -dx * s + dy * c
        return abs(rx) <= sel.width() / 2f && abs(ry) <= sel.height() / 2f
    }

    private fun startSelect(event: MotionEvent, pointerIndex: Int) {
        activePointerId = event.getPointerId(pointerIndex)
        val x = toMmX(event, pointerIndex)
        val y = toMmY(event, pointerIndex)
        val hit = boxAt(x, y)
        if (hit != null) {
            selected = hit
            controller?.onBoxSelected?.invoke(if (hit is TextSel) "text" else "image", hit.id)
            val brX = hit.left() + hit.width()
            val brY = hit.top() + hit.height()
            val rotX = hit.left() + hit.width() / 2f
            val rotY = hit.top() - 6f
            val hitR = 8f
            selectAction = when {
                abs(x - brX) <= hitR && abs(y - brY) <= hitR -> SelectAction.RESIZE
                abs(x - rotX) <= hitR && abs(y - rotY) <= hitR -> SelectAction.ROTATE
                else -> SelectAction.MOVE
            }
            grabOffsetX = x - hit.left()
            grabOffsetY = y - hit.top()
        } else {
            clearSelection()
            startGesture(event, pointerIndex)
        }
        invalidate()
    }

    private fun moveSelect(event: MotionEvent) {
        val sel = selected ?: return
        val idx = event.findPointerIndex(activePointerId)
        if (idx < 0) return
        val x = toMmX(event, idx)
        val y = toMmY(event, idx)
        val page = currentPage() ?: return
        when (selectAction) {
            SelectAction.MOVE -> {
                val nw = (x - grabOffsetX).coerceIn(0f, page.widthMm)
                val nh = (y - grabOffsetY).coerceIn(0f, page.heightMm)
                sel.apply(nw, nh, sel.width(), sel.height(), sel.rotationDeg())
                persistSelection()
            }
            SelectAction.RESIZE -> {
                val nw = (x - sel.left()).coerceAtLeast(5f)
                val nh = if (sel is ImageSel) {
                    nw / sel.width() * sel.height()
                } else {
                    (y - sel.top()).coerceAtLeast(5f)
                }
                sel.apply(
                    sel.left(), sel.top(),
                    min(nw, page.widthMm - sel.left()),
                    if (sel is ImageSel) min(nh, page.heightMm - sel.top()) else nh,
                    sel.rotationDeg(),
                )
                persistSelection()
            }
            SelectAction.ROTATE -> {
                val cx = sel.left() + sel.width() / 2f
                val cy = sel.top() + sel.height() / 2f
                val deg = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
                sel.apply(sel.left(), sel.top(), sel.width(), sel.height(), deg)
                persistSelection()
            }
            SelectAction.NONE -> {}
        }
    }

    private fun persistSelection() {
        val sel = selected ?: return
        if (sel is TextSel) {
            updatePage { p -> p.copy(textBoxes = p.textBoxes.map { if (it.id == sel.id) sel.box else it }) }
        } else if (sel is ImageSel) {
            updatePage { p -> p.copy(imageBoxes = p.imageBoxes.map { if (it.id == sel.id) sel.box else it }) }
        }
        invalidate()
    }

    private fun endSelect() {
        if (selectAction != SelectAction.NONE) notifyDocChanged()
        selectAction = SelectAction.NONE
    }

    // ------------------------------------------------------------------ gestures

    private fun startGesture(event: MotionEvent, pointerIndex: Int) {
        gesturePrimaryId = event.getPointerId(pointerIndex)
        gestureMode = GestureMode.PAN
        gStartZoom = zoomPxPerMm
        gStartPanX = panX
        gStartPanY = panY
        lastPanX = event.getX(pointerIndex)
        lastPanY = event.getY(pointerIndex)
        gStartDist = 0f
    }

    private fun updateGesture(event: MotionEvent) {
        if (event.pointerCount >= 2 && gestureMode != GestureMode.SCALE) {
            val (midX, midY) = midpoint(event)
            val dist = distance(event)
            if (dist > 0f) {
                gestureMode = GestureMode.SCALE
                gStartDist = dist
                gStartMidX = midX
                gStartMidY = midY
                gStartZoom = zoomPxPerMm
                gStartPanX = panX
                gStartPanY = panY
            }
        }
        when (gestureMode) {
            GestureMode.PAN -> {
                val idx = event.findPointerIndex(gesturePrimaryId)
                if (idx < 0) return
                val dx = event.getX(idx) - lastPanX
                val dy = event.getY(idx) - lastPanY
                when (pageLayoutMode) {
                    PageLayoutMode.SINGLE -> {
                        panX += dx
                        panY += dy
                    }
                    PageLayoutMode.VERTICAL -> panY += dy
                    PageLayoutMode.HORIZONTAL -> panX += dx
                }
                lastPanX = event.getX(idx)
                lastPanY = event.getY(idx)
                clampPan()
                invalidate()
            }
            GestureMode.SCALE -> {
                if (gStartDist <= 0f) return
                val dist = distance(event)
                val newZoom = (gStartZoom * dist / gStartDist).coerceIn(0.3f, 25f)
                val (midX, midY) = midpoint(event)
                val k = newZoom / zoomPxPerMm
                panX = midX - (midX - panX) * k
                panY = midY - (midY - panY) * k
                zoomPxPerMm = newZoom
                clampPan()
                invalidate()
            }
            GestureMode.NONE -> {}
        }
    }

    private fun endGesture(event: MotionEvent) {
        if (event.pointerCount - 1 <= 1) {
            gestureMode = GestureMode.NONE
            if (event.pointerCount - 1 == 1) {
                for (i in 0 until event.pointerCount) {
                    if (i != event.actionIndex) {
                        gesturePrimaryId = event.getPointerId(i)
                        gestureMode = GestureMode.PAN
                        gStartPanX = panX
                        gStartPanY = panY
                        lastPanX = event.getX(i)
                        lastPanY = event.getY(i)
                        break
                    }
                }
            }
        }
    }

    /** True when an extra pointer (e.g. a palm resting on the screen) lifted while the active pointer is still down. */
    private fun isNonActivePointerUp(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_POINTER_UP) return false
        return event.findPointerIndex(activePointerId) >= 0
    }

    private fun midpoint(event: MotionEvent): Pair<Float, Float> {
        var sx = 0f
        var sy = 0f
        for (i in 0 until event.pointerCount) {
            sx += event.getX(i)
            sy += event.getY(i)
        }
        return sx / event.pointerCount to sy / event.pointerCount
    }

    private fun distance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
    }

    // ------------------------------------------------------------------ rendering

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = note ?: return
        canvas.drawColor(Color.rgb(0xE4, 0xE7, 0xEC))
        for (i in n.pages.indices) {
            if (!pageVisibleOnScreen(i) && i != pageIndex) continue
            renderPageBitmap(i, force = false)
            drawPageOnScreen(canvas, i)
        }
        evictInvisiblePages()
        if (tool == EditorTool.STRAIGHT_LINE && lineActive) {
            linePaint.color = penColorArgb
            linePaint.strokeWidth = (penSizeMm * zoomPxPerMm).coerceAtLeast(1f)
            canvas.drawLine(
                mmToScreenX(lineStartMmX), mmToScreenY(lineStartMmY),
                mmToScreenX(lineEndMmX), mmToScreenY(lineEndMmY),
                linePaint,
            )
        }
        if (lassoActive) {
            val path = Path()
            if (lassoPoints.size >= 4) {
                path.moveTo(mmToScreenX(lassoPoints[0]), mmToScreenY(lassoPoints[1]))
                for (i in 2 until lassoPoints.size step 2) {
                    path.lineTo(mmToScreenX(lassoPoints[i]), mmToScreenY(lassoPoints[i + 1]))
                }
            }
            canvas.drawPath(path, lassoPaint)
        }
        if (tool == EditorTool.PEN && scribbleEraseActive && scribblePath.size >= 4) {
            scribbleTrailPaint.color = scribbleEraseColor
            scribbleTrailPaint.strokeWidth = (scribbleEraseWidthMm * zoomPxPerMm).coerceAtLeast(2f * density)
            val path = Path()
            path.moveTo(mmToScreenX(scribblePath[0]), mmToScreenY(scribblePath[1]))
            for (i in 2 until scribblePath.size step 2) {
                path.lineTo(mmToScreenX(scribblePath[i]), mmToScreenY(scribblePath[i + 1]))
            }
            canvas.drawPath(path, scribbleTrailPaint)
        }
        if (lassoedStrokeIndices.isNotEmpty()) {
            val list = strokes.getOrNull(pageIndex) ?: return
            for (si in lassoedStrokeIndices) {
                val s = list.getOrNull(si) ?: continue
                val batch = s.inputs
                if (batch.size < 1) continue
                val path = Path()
                val p0 = batch.get(0)
                path.moveTo(mmToScreenX(p0.x), mmToScreenY(p0.y))
                for (k in 1 until batch.size) {
                    val p = batch.get(k)
                    path.lineTo(mmToScreenX(p.x), mmToScreenY(p.y))
                }
                canvas.drawPath(path, lassoSelectPaint)
            }
        }
    }

    private fun drawPageOnScreen(canvas: Canvas, i: Int) {
        val n = note ?: return
        val page = n.pages[i]
        val left = pageLeftPx(i)
        val top = pageTopPx(i)
        val right = left + page.widthMm * zoomPxPerMm
        val bottom = top + page.heightMm * zoomPxPerMm
        if (right < 0f || bottom < 0f || left > width || top > height) return

        canvas.drawRect(left + 6f * density, top + 6f * density, right + 6f * density, bottom + 6f * density, shadowPaint)
        canvas.drawRect(left, top, right, bottom, whitePaint)

        pageContentBitmaps[i]?.let { bmp ->
            val dst = RectF(left, top, right, bottom)
            canvas.drawBitmap(bmp, null, dst, imageFilter)
        }

        drawBoxes(canvas, page, left, top)
        if (i == pageIndex) drawSelection(canvas, page, left, top)

        canvas.drawRect(left, top, right, bottom, borderPaint)
    }

    private fun drawBoxes(canvas: Canvas, page: PageData, originX: Float, originY: Float) {
        val all = page.textBoxes.map { TextSel(it) as Selection } +
            page.imageBoxes.map { ImageSel(it) as Selection }
        for (sel in all.sortedBy { it.zIndex }) {
            canvas.save()
            canvas.translate(sel.left() * zoomPxPerMm + originX, sel.top() * zoomPxPerMm + originY)
            canvas.rotate(sel.rotationDeg())
            val wPx = sel.width() * zoomPxPerMm
            val hPx = sel.height() * zoomPxPerMm
            when (sel) {
                is TextSel -> drawTextBox(canvas, sel.box, wPx, hPx)
                is ImageSel -> drawImageBox(canvas, sel.box, wPx, hPx)
            }
            canvas.restore()
        }
    }

    private fun drawTextBox(canvas: Canvas, box: TextBox, wPx: Float, hPx: Float) {
        if (box.text.isBlank()) return
        textPaint.color = box.colorArgb.toInt()
        textPaint.textSize = box.fontSizeMm * zoomPxPerMm
        textPaint.isAntiAlias = true
        val widthPx = (wPx - 4f * density).coerceAtLeast(1f)
        val layout = android.text.StaticLayout.Builder
            .obtain(box.text, 0, box.text.length, textPaint, widthPx.toInt())
            .setMaxLines((hPx / textPaint.textSize).toInt().coerceAtLeast(1))
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(2f * density, 0f)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawImageBox(canvas: Canvas, box: ImageBox, wPx: Float, hPx: Float) {
        val bmp = loadAssetBitmap(box.assetName) ?: return
        if (wPx <= 0 || hPx <= 0) return
        val scale = max(wPx / bmp.width, hPx / bmp.height)
        val sw = bmp.width * scale
        val sh = bmp.height * scale
        val sx = (wPx - sw) / 2f
        val sy = (hPx - sh) / 2f
        canvas.drawBitmap(bmp, null, RectF(sx, sy, sx + sw, sy + sh), imageFilter)
    }

    private fun drawSelection(canvas: Canvas, page: PageData, originX: Float, originY: Float) {
        val sel = selected ?: return
        val left = sel.left() * zoomPxPerMm + originX
        val top = sel.top() * zoomPxPerMm + originY
        val w = sel.width() * zoomPxPerMm
        val h = sel.height() * zoomPxPerMm
        canvas.save()
        canvas.rotate(sel.rotationDeg(), left + w / 2f, top + h / 2f)
        canvas.drawRect(left, top, left + w, top + h, selectPaint)
        val r = 6f * density
        canvas.drawCircle(left + w, top + h, r, handlePaint)
        canvas.drawCircle(left + w / 2f, top - 12f * density, r, handlePaint)
        canvas.restore()
    }

    private fun loadAssetBitmap(assetName: String): Bitmap? {
        assetBitmaps[assetName]?.let { return it }
        val bmp = controller?.loadAsset?.invoke(assetName) ?: return null
        assetBitmaps[assetName] = bmp
        return bmp
    }

    // ------------------------------------------------------------------ content bitmap

    private fun renderContent() {
        renderPageBitmap(pageIndex, force = true)
    }

    private fun renderPageContent(i: Int) {
        renderPageBitmap(i, force = true)
    }

    private fun renderAllContent() {
        pageContentBitmaps.clear()
        renderPageBitmap(pageIndex, force = true)
        invalidate()
    }

    /** Desired content bitmap dimensions (px) for [page], bounded by [MAX_CONTENT_PIXELS]. */
    private fun contentDims(page: PageData): Pair<Int, Int> {
        var wPx = (page.widthMm / MM_PER_INCH * BITMAP_DPI).toInt().coerceIn(32, MAX_BITMAP_DIM)
        var hPx = (page.heightMm / MM_PER_INCH * BITMAP_DPI).toInt().coerceIn(32, MAX_BITMAP_DIM)
        val pxTotal = wPx.toLong() * hPx
        if (pxTotal > MAX_CONTENT_PIXELS) {
            val s = sqrt(MAX_CONTENT_PIXELS.toDouble() / pxTotal)
            wPx = (wPx * s).toInt().coerceAtLeast(32)
            hPx = (hPx * s).toInt().coerceAtLeast(32)
        }
        return wPx to hPx
    }

    /**
     * Renders page [i] into its cached bitmap. With [force] == false the existing bitmap is kept
     * (used by onDraw for lazy first-time rendering); pass true after any content edit so committed
     * strokes/boxes are baked in. Allocation falls back to progressively smaller resolutions when
     * memory is tight, so rendering never crashes with OOM (which previously caused blank screens).
     */
    private fun renderPageBitmap(i: Int, force: Boolean) {
        val n = note ?: return
        val page = n.pages.getOrNull(i) ?: return
        val existing = pageContentBitmaps[i]
        if (!force && existing != null) return

        val (baseW, baseH) = contentDims(page)
        var wPx = baseW
        var hPx = baseH
        var bmp: Bitmap? = null
        while (bmp == null && wPx >= 128 && hPx >= 128) {
            try {
                bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                wPx = (wPx / 2).coerceAtLeast(32)
                hPx = (hPx / 2).coerceAtLeast(32)
            }
        }
        if (bmp == null) return
        pageContentBitmaps[i] = bmp
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        drawBackground(canvas, page, wPx, hPx)
        val pageToBmp = Matrix().apply {
            setScale(wPx / page.widthMm, hPx / page.heightMm)
        }
        val strokeScale = wPx / page.widthMm
        val strokePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }
        val path = Path()
        for (s in strokes.getOrNull(i).orEmpty()) {
            val inputs = s.inputs
            if (inputs.size < 1) continue
            strokePaint.color = s.brush.colorIntArgb
            strokePaint.strokeWidth = (s.brush.size * strokeScale).coerceAtLeast(1f)
            path.reset()
            val p0 = inputs.get(0)
            val pts = floatArrayOf(p0.x, p0.y)
            pageToBmp.mapPoints(pts)
            path.moveTo(pts[0], pts[1])
            for (k in 1 until inputs.size) {
                val pi = inputs.get(k)
                pts[0] = pi.x; pts[1] = pi.y
                pageToBmp.mapPoints(pts)
                path.lineTo(pts[0], pts[1])
            }
            canvas.drawPath(path, strokePaint)
        }
        evictInvisiblePages()
        invalidate()
    }

    private fun pageVisibleOnScreen(i: Int): Boolean {
        val n = note ?: return false
        val page = n.pages.getOrNull(i) ?: return false
        val left = pageLeftPx(i)
        val top = pageTopPx(i)
        val right = left + page.widthMm * zoomPxPerMm
        val bottom = top + page.heightMm * zoomPxPerMm
        return !(right < 0f || bottom < 0f || left > width || top > height)
    }

    /** Frees bitmaps of pages that are not visible and not the active page. */
    private fun evictInvisiblePages() {
        val removable = pageContentBitmaps.keys.filter {
            it != pageIndex && !pageVisibleOnScreen(it)
        }
        for (k in removable) pageContentBitmaps.remove(k)
    }

    private fun drawBackground(canvas: Canvas, page: PageData, wPx: Int, hPx: Int) {
        val bg = page.background
        val imgName = bg.backgroundImageName
        if (imgName != null) {
            val bmp = loadAssetBitmap(imgName)
            if (bmp != null) {
                canvas.drawBitmap(bmp, null, Rect(0, 0, wPx, hPx), imageFilter)
                return
            }
        }
        val scale = wPx / page.widthMm
        when (bg.type) {
            BackgroundType.BLANK -> {}
            BackgroundType.GRID -> drawGrid(canvas, bg, wPx, hPx, scale)
            BackgroundType.RULED -> drawRuled(canvas, bg, wPx, hPx, scale, page)
            BackgroundType.DOT -> drawDot(canvas, bg, wPx, hPx, scale)
        }
    }

    private fun drawGrid(canvas: Canvas, bg: BackgroundSpec, wPx: Int, hPx: Int, scale: Float) {
        val minor = Paint().apply {
            color = bg.minorColorArgb.toInt()
            strokeWidth = (bg.lineThicknessMm * scale).coerceAtLeast(1f)
        }
        val major = Paint().apply {
            color = bg.majorColorArgb.toInt()
            strokeWidth = (bg.lineThicknessMm * scale * 1.6f).coerceAtLeast(1.5f)
        }
        val spacing = (bg.spacingMm * scale).coerceAtLeast(1f)
        var x = spacing
        var step = 1
        while (x < wPx) {
            val paint = if (bg.majorEvery > 1 && step % bg.majorEvery == 0) major else minor
            canvas.drawLine(x, 0f, x, hPx.toFloat(), paint)
            x += spacing; step++
        }
        x = spacing; step = 1
        while (x < hPx) {
            val paint = if (bg.majorEvery > 1 && step % bg.majorEvery == 0) major else minor
            canvas.drawLine(0f, x, wPx.toFloat(), x, paint)
            x += spacing; step++
        }
    }

    private fun drawRuled(canvas: Canvas, bg: BackgroundSpec, wPx: Int, hPx: Int, scale: Float, page: PageData) {
        val line = Paint().apply {
            color = bg.ruledColorArgb.toInt()
            strokeWidth = (bg.lineThicknessMm * scale).coerceAtLeast(1f)
        }
        val spacing = (bg.spacingMm * scale).coerceAtLeast(1f)
        var y = spacing
        while (y < hPx) {
            canvas.drawLine(0f, y, wPx.toFloat(), y, line)
            y += spacing
        }
        val marginPaint = Paint().apply {
            color = bg.marginColorArgb.toInt()
            strokeWidth = (bg.lineThicknessMm * scale).coerceAtLeast(1f)
        }
        canvas.drawLine(bg.marginXMm * scale, 0f, bg.marginXMm * scale, hPx.toFloat(), marginPaint)
    }

    private fun drawDot(canvas: Canvas, bg: BackgroundSpec, wPx: Int, hPx: Int, scale: Float) {
        val dot = Paint().apply {
            color = bg.dotColorArgb.toInt()
            style = Paint.Style.FILL
        }
        val spacing = (bg.spacingMm * scale).coerceAtLeast(2f)
        val radius = (0.15f * scale).coerceIn(1f, 2.5f)
        var y = spacing
        while (y < hPx) {
            var x = spacing
            while (x < wPx) {
                canvas.drawCircle(x, y, radius, dot)
                x += spacing
            }
            y += spacing
        }
    }

    companion object {
        private const val MM_PER_INCH = 25.4f

        /** Content raster density. 600 dpi = 3x the original 200 dpi for crisper strokes/lines. */
        private const val BITMAP_DPI = 600f

        /** Hard cap on each content bitmap dimension (px) to bound memory. */
        private const val MAX_BITMAP_DIM = 8192

        /** Per-page bitmap pixel budget (24 MP ≈ 96 MB ARGB) to avoid OOM on huge pages. */
        private const val MAX_CONTENT_PIXELS = 24L * 1024 * 1024
    }
}

