package com.stylusmemo.app.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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
import com.stylusmemo.app.plugin.memorize.MemorizeSession
import com.stylusmemo.app.plugin.memorize.RectDataMm
import com.stylusmemo.app.plugin.tools.ToolSpec
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
    /** Pages whose stroke list changed since the cached snapshot; drives incremental copying. */
    private val dirtyStrokePages = mutableSetOf<Int>()
    private var frozenStrokes: List<List<Stroke>>? = null
    /** Page ids whose strokes have been loaded; ignored when [allPagesLoaded] is true. */
    private val loadedPageIds = mutableSetOf<String>()
    private var allPagesLoaded = true
    var pageIndex: Int = 0
        private set
    /** Page the in-progress pen stroke started on; the stroke commits to this page even if the
     *  active page changes mid-gesture (e.g. a palm tap), which previously lost/moved strokes. */
    private var drawPageIndex = 0

    var pageLayoutMode: PageLayoutMode = PageLayoutMode.SINGLE
        private set
    private val pageContentBitmaps = LinkedHashMap<String, Bitmap>(16, 0.75f, true)

    /** Bounded cache of sheet-color-keyed background rasters (see [keyedBackground]). */
    private val keyedBackgroundCache = object : LinkedHashMap<KeyedBackground, Bitmap>(2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<KeyedBackground, Bitmap>): Boolean {
            if (size > 2) {
                eldest.value.recycle()
                return true
            }
            return false
        }
    }

    /** Bounded LRU of prebuilt StaticLayouts keyed by text box + current metrics. */
    private val textLayoutCache = object : LinkedHashMap<TextLayoutKey, android.text.StaticLayout>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TextLayoutKey, android.text.StaticLayout>): Boolean {
            return size > 32
        }
    }

    private data class TextLayoutKey(
        val text: String,
        val colorArgb: Int,
        val widthPx: Int,
        val maxLines: Int,
        val fontSizePx: Float,
    )

    /** Assets already requested from the host but not yet delivered, to avoid repeated requests. */
    private val pendingAssetNames = mutableSetOf<String>()

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

    /** Eraser segments (x1,y1,x2,y2...) accumulated during a gesture and applied once at pen-up. */
    private val eraseSegments = mutableListOf<Float>()

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

    /** Last time a stylus went down; used to reject palm/finger touches that follow a pen stroke. */
    private var lastStylusDownTime = 0L
    private val PALM_GRACE_MS = 600L
    /** Pointer id currently treated as a resting palm; ignored while it stays roughly stationary. */
    private var palmPointerId = -1
    private var palmBaseX = 0f
    private var palmBaseY = 0f
    private var lastPalmTouchTime = 0L
    private val PALM_MOVE_PX = 20f
    private val PALM_GAP_MS = 300L
    private val PEN_TYPE_TOOLS = setOf(
        EditorTool.PEN, EditorTool.HIGHLIGHT, EditorTool.SCRIBBLE_ERASE,
        EditorTool.STRAIGHT_LINE, EditorTool.LASSO,
    )

    /** Active drawing-tool plugin spec (e.g. the highlighter); used when [tool] == HIGHLIGHT. */
    private var highlightSpec: ToolSpec? = null

    // ------------------------------------------------------------------ memorize (red-sheet) overlay

    /**
     * Active memorization session (red sheet). Owned by the host; the view only renders the
     * overlay, hides matching content under the sheet and reports sheet drags / eyedropper taps.
     */
    private var memorizeSession: MemorizeSession? = null
    private var lastMemorizeRenderMs = 0L
    private var lastSheetScreenRect: RectF? = null
    var onSheetMoved: ((dxMm: Float, dyMm: Float) -> Unit)? = null
    var onSheetRectChanged: ((rectMm: RectDataMm) -> Unit)? = null
    var onSheetMoveEnd: (() -> Unit)? = null
    var onEyedropperPick: ((argb: Int) -> Unit)? = null
    private var sheetDragMode = SheetDragMode.NONE
    private var sheetDragPointerId = -1
    private var sheetLastScreenX = 0f
    private var sheetLastScreenY = 0f
    private var sheetResizeOrigLeftMm = 0f
    private var sheetResizeOrigTopMm = 0f
    private var sheetResizeOrigRightMm = 0f
    private var sheetResizeOrigBottomMm = 0f
    private var sheetResizeStartScreenX = 0f
    private var sheetResizeStartScreenY = 0f
    private val memorizePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private enum class SheetDragMode { NONE, MOVE, RESIZE_BL, RESIZE_BR }

    // ------------------------------------------------------------------ scratch surface

    /** When true, this view is a disposable scratch pad: strokes render but never autosave. */
    private var scratchOnly = false

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

    private var snipPointerId = -1
    private var snipPageIndex = -1
    private var snipStartX = 0f
    private var snipStartY = 0f
    private var snipEndX = 0f
    private var snipEndY = 0f
    private var snipBlocked = false

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
    private var lassoDirtyBounds: StrokeBounds? = null
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

    // Reused across page renders to avoid per-render allocations.
    private val renderMatrix = Matrix()
    private val renderStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val renderPath = Path()
    private val renderPoint = FloatArray(2)

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

        /** Page and page-mm region this op changed, when it only touched strokes. */
        fun affectedStrokeBounds(): Pair<Int, StrokeBounds>? = null
    }

    private inner class AddStrokeOp(val page: Int, val stroke: Stroke) : EditOp {
        override fun apply() { strokes.getOrNull(page)?.add(stroke); dirtyStrokePages.add(page) }
        override fun undo() { strokes.getOrNull(page)?.remove(stroke); dirtyStrokePages.add(page) }
        override fun affectedStrokeBounds() = page to strokeBounds(stroke)
    }

    private inner class RemoveStrokesOp(
        val page: Int,
        val removed: List<Pair<Int, Stroke>>,
    ) : EditOp {
        override fun apply() {
            val list = strokes.getOrNull(page) ?: return
            removed.map { it.second }.forEach { list.remove(it) }
            dirtyStrokePages.add(page)
        }

        override fun undo() {
            val list = strokes.getOrNull(page) ?: return
            removed.sortedBy { it.first }.forEach { (idx, s) ->
                list.add(min(idx, list.size), s)
            }
            dirtyStrokePages.add(page)
        }

        override fun affectedStrokeBounds(): Pair<Int, StrokeBounds>? =
            boundsOf(removed.map { it.second })?.let { page to it }
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
            dirtyStrokePages.add(page)
        }

        override fun undo() {
            val list = strokes.getOrNull(page) ?: return
            for ((j, si) in indices.withIndex()) {
                val b = before.getOrNull(j) ?: continue
                list[si] = b
            }
            dirtyStrokePages.add(page)
        }

        override fun affectedStrokeBounds(): Pair<Int, StrokeBounds>? {
            val beforeBounds = boundsOf(before) ?: return null
            val afterBounds = boundsOf(after) ?: beforeBounds
            return page to unionBounds(beforeBounds, afterBounds)
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

    private inner class MovePageOp(val from: Int, val to: Int) : EditOp {
        override fun apply() = movePageInternal(from, to, pushUndoFlag = false)
        override fun undo() = movePageInternal(to, from, pushUndoFlag = false)
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
                    var needFull = false
                    for ((_, stroke) in finished) {
                        // commitStroke converts to page-mm; append must use the converted stroke
                        // (the finished stroke is still in screen-pixel space).
                        val mmStroke = commitStroke(stroke)
                        if (!appendStrokeToBitmap(mmStroke)) needFull = true
                    }
                    if (needFull) renderContent() else invalidate()
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
        cancelSnip()
        scratchOnly = false
        note = snapshot.note
        strokes.clear()
        snapshot.strokes.forEach { strokes.add(it.toMutableList()) }
        loadedPageIds.clear()
        allPagesLoaded = snapshot.loadedPages == null
        snapshot.loadedPages?.forEach { idx ->
            snapshot.note.pages.getOrNull(idx)?.let { loadedPageIds.add(it.id) }
        }
        pageIndex = snapshot.note.lastPageIndex.coerceIn(0, (snapshot.note.pages.size - 1).coerceAtLeast(0))
        drawPageIndex = pageIndex
        undoStack.clear()
        redoStack.clear()
        selected = null
        clearLassoGesture()
        pendingAssetNames.clear()
        pageContentBitmaps.clear()
        clearKeyedBackgroundCache()
        invalidateStrokeSnapshot()
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
        // Remember the page the user was on so reopening the note restores it.
        val withPage = if (scratchOnly) n
            else n.copy(lastPageIndex = pageIndex.coerceIn(0, (n.pages.size - 1).coerceAtLeast(0)))
        val loaded = if (allPagesLoaded) null
        else n.pages.indices.filter { n.pages[it].id in loadedPageIds }.toSet()
        val size = n.pages.size
        val previous = frozenStrokes
        val copy: List<List<Stroke>> = if (previous == null || previous.size != size) {
            List(size) { i -> strokes.getOrNull(i)?.toList() ?: emptyList() }
        } else if (dirtyStrokePages.isEmpty()) {
            previous
        } else {
            ArrayList<List<Stroke>>(size).apply {
                for (i in 0 until size) {
                    val cached = previous.getOrNull(i)
                    add(
                        if (i in dirtyStrokePages || cached == null) {
                            strokes.getOrNull(i)?.toList() ?: emptyList()
                        } else {
                            cached
                        },
                    )
                }
            }
        }
        frozenStrokes = copy
        dirtyStrokePages.clear()
        return EditorSnapshot(withPage, copy, loaded)
    }

    private fun invalidateStrokeSnapshot() {
        frozenStrokes = null
        dirtyStrokePages.clear()
        invalidatePageOffsets()
    }

    fun isPageLoaded(page: Int): Boolean {
        val pages = note?.pages ?: return false
        if (allPagesLoaded) return true
        return pages.getOrNull(page)?.id in loadedPageIds
    }

    /** True when every page's strokes are available (required before reindexing pages). */
    fun areAllPagesLoaded(): Boolean {
        val pages = note?.pages ?: return true
        if (allPagesLoaded) return true
        return pages.all { it.id in loadedPageIds }
    }

    /** Installs the strokes of a page that was loaded in the background. */
    fun applyLoadedStrokes(page: Int, loaded: List<Stroke>) {
        val n = note ?: return
        val data = n.pages.getOrNull(page) ?: return
        while (strokes.size <= page) strokes.add(mutableListOf())
        strokes[page] = loaded.toMutableList()
        dirtyStrokePages.add(page)
        loadedPageIds.add(data.id)
        pageContentBitmaps.remove(data.id)
        invalidate()
    }

    fun undo() {
        val op = undoStack.removeLastOrNull() ?: return
        op.undo()
        redoStack.addLast(op)
        afterStructuralChange(op)
    }

    fun redo() {
        val op = redoStack.removeLastOrNull() ?: return
        op.apply()
        undoStack.addLast(op)
        afterStructuralChange(op)
    }

    fun updateSnips(noteId: String, transform: (List<com.stylusmemo.app.model.Snip>) -> List<com.stylusmemo.app.model.Snip>): Note? {
        val current = note?.takeIf { it.id == noteId && !scratchOnly } ?: return null
        note = current.copy(snips = transform(current.snips).toList())
        notifyDocChanged()
        return note
    }

    fun cancelSnip() {
        snipPointerId = -1
        snipPageIndex = -1
        snipBlocked = true
        invalidate()
    }

    private fun handleSnipTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelSnip()
                snipBlocked = false
                if (!isPageLoaded(pageIndex)) {
                    snipBlocked = true
                    controller?.onPageStrokesNeeded?.invoke(pageIndex)
                    return true
                }
                val stylus = isStylusTool(event, 0)
                if (stylus) lastStylusDownTime = System.currentTimeMillis()
                if (!stylus && (!fingerDrawEnabled || System.currentTimeMillis() - lastStylusDownTime < PALM_GRACE_MS)) {
                    snipBlocked = true
                    return true
                }
                val index = pageAtScreenPoint(event.x, event.y)
                val page = note?.pages?.getOrNull(index) ?: return true
                val x = (event.x - pageLeftPx(index)) / zoomPxPerMm
                val y = (event.y - pageTopPx(index)) / zoomPxPerMm
                if (x !in 0f..page.widthMm || y !in 0f..page.heightMm) return true
                snipPointerId = event.getPointerId(0)
                snipPageIndex = index
                snipStartX = x
                snipStartY = y
                snipEndX = x
                snipEndY = y
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> cancelSnip()
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (snipBlocked || snipPointerId < 0) return true
                val idx = event.findPointerIndex(snipPointerId)
                if (idx < 0 || event.pointerCount != 1 || (event.flags and MotionEvent.FLAG_CANCELED) != 0) {
                    cancelSnip()
                    return true
                }
                val page = note?.pages?.getOrNull(snipPageIndex) ?: return true
                snipEndX = ((event.getX(idx) - pageLeftPx(snipPageIndex)) / zoomPxPerMm).coerceIn(0f, page.widthMm)
                snipEndY = ((event.getY(idx) - pageTopPx(snipPageIndex)) / zoomPxPerMm).coerceIn(0f, page.heightMm)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val crop = com.stylusmemo.app.model.SnipCrop.create(
                        page.widthMm, page.heightMm, snipStartX, snipStartY, snipEndX, snipEndY,
                    )
                    if (crop == null) controller?.onSnipError?.invoke("SNIP: 1mm以上の範囲を選択してください")
                    else captureSnip(snipPageIndex, page, crop)
                    cancelSnip()
                }
            }
        }
        invalidate()
        return true
    }

    private fun captureSnip(index: Int, page: PageData, crop: com.stylusmemo.app.model.SnipCrop) {
        val id = note?.id ?: return
        val names = (listOfNotNull(page.background.backgroundImageName) + page.imageBoxes.map { it.assetName }).distinct()
        if (names.any { loadAssetBitmap(it) == null }) {
            names.forEach { name ->
                pendingAssetNames.remove(name)
                controller?.onAssetNeeded?.invoke(name)
            }
            controller?.onSnipError?.invoke("SNIP: 画像を読み込み中、または読み込めません。表示を確認して再選択してください")
            return
        }
        var bitmap: Bitmap? = null
        val session = memorizeSession
        try {
            bitmap = Bitmap.createBitmap(crop.widthPx, crop.heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val scale = crop.pixelsPerMm.toFloat()
            canvas.translate(-crop.leftMm * scale, -crop.topMm * scale)
            canvas.clipRect(0f, 0f, page.widthMm * scale, page.heightMm * scale)
            drawBackground(canvas, page, page.widthMm * scale, page.heightMm * scale, scale)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
            }
            val path = Path()
            for (stroke in strokes.getOrNull(index).orEmpty().sortedBy { if ((it.brush.colorIntArgb ushr 24) < 255) 0 else 1 }) {
                val inputs = stroke.inputs
                if (inputs.size == 0) continue
                paint.color = stroke.brush.colorIntArgb
                paint.strokeWidth = (stroke.brush.size * scale).coerceAtLeast(1f)
                paint.strokeCap = if ((paint.color ushr 24) < 255) Paint.Cap.SQUARE else Paint.Cap.ROUND
                path.reset()
                val first = inputs.get(0)
                path.moveTo(first.x * scale, first.y * scale)
                for (i in 1 until inputs.size) {
                    val point = inputs.get(i)
                    path.lineTo(point.x * scale, point.y * scale)
                }
                canvas.drawPath(path, paint)
            }
            memorizeSession = null
            drawBoxes(canvas, page, 0f, 0f, scale)
            val frozen = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: error("SNIP allocation failed")
            val callback = controller?.onSnipCaptured
            if (callback == null) frozen.recycle() else callback(id, frozen)
        } catch (_: OutOfMemoryError) {
            controller?.onSnipError?.invoke("SNIP: メモリ不足です。小さい範囲で再試行してください")
        } catch (_: Exception) {
            controller?.onSnipError?.invoke("SNIP: キャプチャに失敗しました。再試行してください")
        } finally {
            memorizeSession = session
            bitmap?.recycle()
        }
    }

    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()

    fun setTool(t: EditorTool) {
        if (tool != t) cancelSnip()
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

    /** Sets the highlighter/drawing-tool plugin spec used when [tool] == HIGHLIGHT. */
    fun setHighlightSpec(spec: ToolSpec?) {
        highlightSpec = spec
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
        invalidatePageOffsets()
        if (note != null) {
            fitViewport()
            invalidate()
        }
    }

    // ------------------------------------------------------------------ memorize overlay

    /** Attaches/detaches the memorization (red-sheet) session driving the overlay. */
    fun setMemorizeSession(session: MemorizeSession?) {
        if (memorizeSession === session) return
        memorizeSession = session
        sheetDragMode = SheetDragMode.NONE
        lastSheetScreenRect = null
        refreshMemorize(force = true)
    }

    /**
     * Re-renders the memoize-filtered content bitmap. During sheet drags the host passes
     * [force] = false so the bitmap re-renders at most every [MEMORIZE_RENDER_THROTTLE_MS];
     * the translucent sheet itself always tracks the finger via [invalidate].
     */
    fun refreshMemorize(force: Boolean = true) {
        val now = System.currentTimeMillis()
        val hadSession = lastMemorizeRenderMs != 0L
        if (memorizeSession == null) {
            if (hadSession) {
                lastMemorizeRenderMs = 0L
                renderContent()
            }
            invalidate()
            return
        }
        if (force || now - lastMemorizeRenderMs >= MEMORIZE_RENDER_THROTTLE_MS) {
            lastMemorizeRenderMs = now
            renderContent()
        }
        invalidate()
    }

    /** Redraws only the sheet overlay (no content re-render). Used while dragging the sheet. */
    fun invalidateMemorizeOverlay() {
        val rect = memorizeSession?.state?.sheetRectMm ?: return invalidate()
        val next = screenRectMm(rect)
        lastSheetScreenRect?.let { next.union(it) }
        val pad = 12f * density
        next.inset(-pad, -pad)
        lastSheetScreenRect = screenRectMm(rect)
        invalidate(next.left.toInt(), next.top.toInt(), next.right.toInt(), next.bottom.toInt())
    }

    private fun screenRectMm(rect: RectDataMm): RectF {
        val left = pageLeftPx(pageIndex) + rect.leftMm * zoomPxPerMm
        val top = pageTopPx(pageIndex) + rect.topMm * zoomPxPerMm
        return RectF(left, top, left + rect.widthMm * zoomPxPerMm, top + rect.heightMm * zoomPxPerMm)
    }

    /** Samples the rendered note color at a screen point (eyedropper). Null when off-page. */
    fun sampleColorAt(screenX: Float, screenY: Float): Int? {
        val page = note?.pages?.getOrNull(pageIndex) ?: return null
        val (ox, oy) = pageOffsetMm(pageIndex)
        val xMm = (screenX - panX) / zoomPxPerMm - ox
        val yMm = (screenY - panY) / zoomPxPerMm - oy
        if (xMm < 0f || yMm < 0f || xMm > page.widthMm || yMm > page.heightMm) return null
        for (box in page.textBoxes) {
            if (xMm >= box.leftMm && xMm <= box.leftMm + box.widthMm &&
                yMm >= box.topMm && yMm <= box.topMm + box.heightMm
            ) {
                return box.colorArgb.toInt()
            }
        }
        val bmp = pageContentBitmaps[page.id] ?: return null
        val bx = ((xMm / page.widthMm) * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val by = ((yMm / page.heightMm) * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        return bmp.getPixel(bx, by)
    }

    // ------------------------------------------------------------------ scratch surface

    /** Loads a blank scratch page. Nothing drawn here is ever autosaved (see [notifyDocChanged]). */
    fun loadScratch(widthMm: Float, heightMm: Float) {
        scratchOnly = true
        val page = PageData(widthMm, heightMm, BackgroundSpec())
        note = Note(title = "なぐり書き", pages = listOf(page))
        strokes.clear()
        strokes.add(mutableListOf())
        loadedPageIds.clear()
        allPagesLoaded = true
        pageIndex = 0
        undoStack.clear()
        redoStack.clear()
        selected = null
        clearLassoGesture()
        pageContentBitmaps.clear()
        invalidateStrokeSnapshot()
        fittedOnce = false
        if (width > 0 && height > 0) {
            fitViewport()
            fittedOnce = true
        }
        renderAllContent()
        invalidate()
    }

    // ------------------------------------------------------------------ layout helpers

    private val pageGapMm: Float = 24f
    private var pageOffsetsCache: List<Pair<Float, Float>>? = null

    private fun ensurePageOffsets(): List<Pair<Float, Float>> {
        pageOffsetsCache?.let { return it }
        val n = note ?: return emptyList()
        val out = ArrayList<Pair<Float, Float>>(n.pages.size)
        var x = 0f
        var y = 0f
        for (p in n.pages) {
            out.add(x to y)
            when (pageLayoutMode) {
                PageLayoutMode.VERTICAL -> y += p.heightMm + pageGapMm
                PageLayoutMode.HORIZONTAL -> x += p.widthMm + pageGapMm
                PageLayoutMode.SINGLE -> {}
            }
        }
        pageOffsetsCache = out
        return out
    }

    private fun invalidatePageOffsets() {
        pageOffsetsCache = null
    }

    private fun pageOffsetMm(i: Int): Pair<Float, Float> =
        ensurePageOffsets().getOrNull(i) ?: (0f to 0f)

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
        // Allow panning on both axes; when the document fits the viewport the range collapses
        // to a single value so there is no drift, and when zoomed in both axes can be scrolled.
        panX = panX.coerceIn(minOf(0f, width - docW), maxOf(0f, width - docW))
        panY = panY.coerceIn(minOf(0f, height - docH), maxOf(0f, height - docH))
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
        // Discard any stroke still being authored so it cannot land on the new page.
        activeStrokeId?.let { inProgressView.cancelStroke(it) }
        activeStrokeId = null
        activePointerId = -1
        scribbleEraseActive = false
        scribblePath.clear()
        scribbleRecentPoints.clear()
        lineActive = false
        lassoActive = false
        lassoMoveActive = false
        pageIndex = i
        drawPageIndex = i
        selected = null
        clearLassoGesture()
        if (!isPageLoaded(i)) controller?.onPageStrokesNeeded?.invoke(i)
        renderContent()
        scrollToPage(i)
        invalidate()
        notifyPageChanged()
        notifyDocChanged()
    }

    fun addPage() {
        val n = note ?: return
        val cur = n.pages.getOrNull(pageIndex) ?: n.pages.firstOrNull() ?: PageData()
        // Carry over the page size and template, but never the background image: an imported
        // PDF/image page must not be duplicated as a "blank" new page.
        val background = if (cur.background.backgroundImageName != null) BackgroundSpec()
            else cur.background
        val newPage = PageData(widthMm = cur.widthMm, heightMm = cur.heightMm, background = background)
        val index = n.pages.size
        pushUndo(AddPageOp(index, newPage))
        insertPage(index, newPage)
        switchPage(index)
        notifyDocChanged()
    }

    fun deletePage() {
        val n = note ?: return
        if (n.pages.size <= 1) return
        val index = pageIndex
        if (!isPageLoaded(index)) {
            controller?.onPageStrokesNeeded?.invoke(index)
            return
        }
        val removed = n.pages[index]
        val removedStrokes = strokes.getOrNull(index)?.toList() ?: emptyList()
        pushUndo(RemovePageOp(index, removed, removedStrokes))
        removePageAt(index)
        if (pageIndex >= (note?.pages?.size ?: 1)) pageIndex = max(0, pageIndex - 1)
        selected = null
        clearLassoGesture()
        renderContent()
        scrollToPage(pageIndex)
        invalidate()
        notifyPageChanged()
        notifyDocChanged()
    }

    /** Moves the page at [from] to index [to], keeping its strokes. */
    fun movePage(from: Int, to: Int) {
        val n = note ?: return
        if (from == to || from !in n.pages.indices || to !in n.pages.indices) return
        pushUndo(MovePageOp(from, to))
        movePageInternal(from, to, pushUndoFlag = false)
        notifyDocChanged()
    }

    private fun movePageInternal(from: Int, to: Int, pushUndoFlag: Boolean) {
        val n = note ?: return
        if (from == to || from !in n.pages.indices || to !in n.pages.indices) return
        val pages = n.pages.toMutableList()
        val page = pages.removeAt(from)
        pages.add(to, page)
        note = n.copy(pages = pages)
        if (from < strokes.size) {
            val st = strokes.removeAt(from)
            strokes.add(to.coerceIn(0, strokes.size), st)
        }
        invalidateStrokeSnapshot()
        pageIndex = to
        drawPageIndex = to
        selected = null
        clearLassoGesture()
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
        renderContent()
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
    fun currentNote(): Note? = note
    fun pageCountValue(): Int = note?.pages?.size ?: 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (note == null || w <= 0 || h <= 0) return
        // Fit on first layout; refit whenever the pane size actually changes (split toggle,
        // divider drag, rotation) so the page stays framed inside the pane.
        if (!fittedOnce || w != oldw || h != oldh) {
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
        invalidatePageOffsets()
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
        invalidateStrokeSnapshot()
    }

    private fun removePageAt(index: Int) {
        val n = note ?: return
        note = n.copy(pages = n.pages.toMutableList().apply { removeAt(index) })
        if (index < strokes.size) strokes.removeAt(index)
        invalidateStrokeSnapshot()
    }

    private fun pushUndo(op: EditOp) {
        undoStack.addLast(op)
        redoStack.clear()
        notifyUndoRedo()
    }

    private fun afterStructuralChange(op: EditOp? = null) {
        selected = null
        clampPageIndex()
        val dirty = op?.affectedStrokeBounds()
        if (dirty != null && dirty.first in (note?.pages?.indices ?: IntRange.EMPTY)) {
            redrawPageRegions(dirty.first, listOf(dirty.second))
        } else {
            renderContent()
        }
        invalidate()
        notifyUndoRedo()
        notifyPageChanged()
        notifyDocChanged()
    }

    /** Keeps the active page inside range after page insert/remove/move (e.g. undo of "add page"). */
    private fun clampPageIndex() {
        val count = note?.pages?.size ?: 0
        if (count <= 0) {
            pageIndex = 0
            drawPageIndex = 0
            return
        }
        pageIndex = pageIndex.coerceIn(0, count - 1)
        drawPageIndex = drawPageIndex.coerceIn(0, count - 1)
    }

    private fun notifyUndoRedo() {
        controller?.onUndoRedoChanged?.invoke(canUndo(), canRedo())
    }

    private fun notifyPageChanged() {
        controller?.onPageChanged?.invoke()
    }

    private fun notifyDocChanged() {
        cancelSnip()
        if (scratchOnly) return
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

    private fun commitStroke(screenStroke: Stroke): Stroke {
        val invZoom = 1f / zoomPxPerMm
        val targetPage = drawPageIndex.coerceIn(0, (note?.pages?.size ?: 1) - 1)
        val (ox, oy) = pageOffsetMm(targetPage)
        val screenToPage = Matrix().apply {
            setValues(floatArrayOf(
                invZoom, 0f, -panX * invZoom - ox,
                0f, invZoom, -panY * invZoom - oy,
                0f, 0f, 1f,
            ))
        }
        val mmStroke = InkUtil.transformStrokeToMm(screenStroke, screenToPage)
        val list = strokes.getOrNull(targetPage)
            ?: mutableListOf<Stroke>().also { strokes.add(it) }
        list.add(mmStroke)
        dirtyStrokePages.add(targetPage)
        pushUndo(AddStrokeOp(targetPage, mmStroke))
        notifyDocChanged()
        return mmStroke
    }

    // ------------------------------------------------------------------ input

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (note == null) return false
        val action = event.actionMasked
        handleStylusButtonShortcuts(event)
        if (tool == EditorTool.SNIP) return handleSnipTouch(event)

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val downIdx = event.actionIndex
            val downIsStylus = isStylusTool(event, downIdx)
            if (downIsStylus) lastStylusDownTime = System.currentTimeMillis()

            // A stylus drawing takes over from an in-progress finger (palm) gesture.
            if (downIsStylus && activePointerId >= 0 && !isStylusPointer(activePointerId, event) && tool in PEN_TYPE_TOOLS) {
                gestureMode = GestureMode.NONE
                activePointerId = -1
            }

            // Eyedropper (memorization mode): sample the color under the tap, draw nothing.
            if (tool == EditorTool.EYEDROPPER && memorizeSession != null) {
                sampleColorAt(event.getX(downIdx), event.getY(downIdx))?.let { onEyedropperPick?.invoke(it) }
                return true
            }

            // Sheet handle drags (memorization mode): moving / resizing the sheet wins
            // over drawing. While a sheet drag is in progress, other pointers are ignored
            // so a resting hand cannot hijack the gesture.
            if (memorizeSession != null) {
                if (sheetDragMode != SheetDragMode.NONE) return true
                val downX = event.getX(downIdx)
                val downY = event.getY(downIdx)
                val mode = sheetHitMode(downX, downY)
                if (mode != SheetDragMode.NONE) {
                    sheetDragMode = mode
                    sheetDragPointerId = event.getPointerId(downIdx)
                    sheetLastScreenX = downX
                    sheetLastScreenY = downY
                    if (mode == SheetDragMode.RESIZE_BL || mode == SheetDragMode.RESIZE_BR) {
                        val rect = memorizeSession?.state?.sheetRectMm
                        if (rect != null) {
                            sheetResizeOrigLeftMm = rect.leftMm
                            sheetResizeOrigTopMm = rect.topMm
                            sheetResizeOrigRightMm = rect.leftMm + rect.widthMm
                            sheetResizeOrigBottomMm = rect.topMm + rect.heightMm
                            sheetResizeStartScreenX = downX
                            sheetResizeStartScreenY = downY
                        }
                    }
                    return true
                }
            }

            // Palm rejection: a finger that touches while a stroke is being drawn (or keeps
            // touching, roughly stationary) is ignored — even after the pen lifts — so a resting
            // hand never triggers pan/zoom. This applies regardless of fingerDrawEnabled, because
            // any pointer other than the one actively drawing is the resting hand.
            val downIsFinger = event.getToolType(downIdx) == MotionEvent.TOOL_TYPE_FINGER
            if (downIsFinger && tool in PEN_TYPE_TOOLS) {
                val pid = event.getPointerId(downIdx)
                val x = event.getX(downIdx)
                val y = event.getY(downIdx)
                val now = System.currentTimeMillis()
                if (pid == palmPointerId) {
                    // Same resting contact continuing: keep ignoring only while still continuous.
                    if (now - lastPalmTouchTime <= PALM_GAP_MS) {
                        lastPalmTouchTime = now
                        return true
                    }
                    palmPointerId = -1
                } else if (activeStrokeId != null ||
                    (activePointerId >= 0 && isStylusPointer(activePointerId, event)) ||
                    now - lastStylusDownTime < PALM_GRACE_MS
                ) {
                    palmPointerId = pid
                    palmBaseX = x
                    palmBaseY = y
                    lastPalmTouchTime = now
                    return true
                }
            }

            if (activeStrokeId != null || activePointerId >= 0 || selectAction != SelectAction.NONE) {
                return true
            }
            val touchedPage = pageAtScreenPoint(
                event.getX(event.actionIndex),
                event.getY(event.actionIndex),
            )
            if (touchedPage != pageIndex) {
                pageIndex = touchedPage
                drawPageIndex = touchedPage
                selected = null
                if (!isPageLoaded(touchedPage)) controller?.onPageStrokesNeeded?.invoke(touchedPage)
                notifyPageChanged()
                notifyDocChanged()
            }
            val stylusIdx = stylusPointerIndex(event)
            val eraserTool = stylusIdx >= 0 && event.getToolType(stylusIdx) == MotionEvent.TOOL_TYPE_ERASER
            if (!isPageLoaded(pageIndex) &&
                (stylusIdx >= 0 || tool == EditorTool.SELECT || fingerDrawEnabled)
            ) {
                controller?.onPageStrokesNeeded?.invoke(pageIndex)
                return true
            }
            when {
                stylusIdx >= 0 && !eraserTool && (tool == EditorTool.PEN || tool == EditorTool.HIGHLIGHT) -> startDraw(event, stylusIdx)
                stylusIdx >= 0 && !eraserTool && tool == EditorTool.SCRIBBLE_ERASE -> startScribbleErase(event, stylusIdx)
                stylusIdx >= 0 && !eraserTool && tool == EditorTool.STRAIGHT_LINE -> startLine(event, stylusIdx)
                stylusIdx >= 0 && !eraserTool && tool == EditorTool.LASSO -> startLasso(event, stylusIdx)
                stylusIdx >= 0 && (eraserTool || tool == EditorTool.ERASER) -> startErase(event, stylusIdx)
                tool == EditorTool.SELECT -> startSelect(event, if (stylusIdx >= 0) stylusIdx else event.actionIndex)
                tool == EditorTool.PEN && fingerDrawEnabled -> startDraw(event, event.actionIndex)
                tool == EditorTool.HIGHLIGHT && fingerDrawEnabled -> startDraw(event, event.actionIndex)
                tool == EditorTool.SCRIBBLE_ERASE && fingerDrawEnabled -> startScribbleErase(event, event.actionIndex)
                tool == EditorTool.STRAIGHT_LINE && fingerDrawEnabled -> startLine(event, event.actionIndex)
                tool == EditorTool.LASSO && fingerDrawEnabled -> startLasso(event, event.actionIndex)
                else -> startGesture(event, event.actionIndex)
            }
            return true
        }

        if (action == MotionEvent.ACTION_MOVE) {
            // Eyedropper armed: consume everything, draw nothing.
            if (tool == EditorTool.EYEDROPPER) return true
            // Sheet drag (memorization mode): move or resize the sheet in page millimetres.
            // Other pointers are swallowed while a sheet drag is active.
            if (sheetDragMode != SheetDragMode.NONE) {
                if (event.getPointerId(event.actionIndex) != sheetDragPointerId) return true
                val idx = event.findPointerIndex(sheetDragPointerId)
                if (idx >= 0) {
                    val x = event.getX(idx)
                    val y = event.getY(idx)
                    when (sheetDragMode) {
                        SheetDragMode.MOVE -> {
                            onSheetMoved?.invoke(
                                (x - sheetLastScreenX) / zoomPxPerMm,
                                (y - sheetLastScreenY) / zoomPxPerMm,
                            )
                            sheetLastScreenX = x
                            sheetLastScreenY = y
                        }
                        SheetDragMode.RESIZE_BL, SheetDragMode.RESIZE_BR -> {
                            applySheetResize(x, y)
                        }
                        SheetDragMode.NONE -> {}
                    }
                }
                return true
            }
            // Keep ignoring a tracked palm while it stays roughly stationary and is still in contact
            // (it is cleared on its ACTION_POINTER_UP). This holds even after the pen lifts, so a
            // resting hand never triggers pan/zoom. Only the palm pointer's own move is swallowed;
            // the pen's moves must pass through.
            if (palmPointerId >= 0 && event.getPointerId(event.actionIndex) == palmPointerId) {
                val pidx = event.findPointerIndex(palmPointerId)
                if (pidx >= 0) {
                    val moved = hypot(event.getX(pidx) - palmBaseX, event.getY(pidx) - palmBaseY)
                    if (moved <= PALM_MOVE_PX) {
                        lastPalmTouchTime = System.currentTimeMillis()
                        return true
                    }
                    palmPointerId = -1
                } else {
                    palmPointerId = -1
                }
            }
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
                strokeId != null && tool == EditorTool.HIGHLIGHT -> {
                    inProgressView.addToStroke(event, activePointerId, strokeId)
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
            if (tool == EditorTool.EYEDROPPER) return true
            if (sheetDragMode != SheetDragMode.NONE) {
                if (event.getPointerId(event.actionIndex) != sheetDragPointerId) return true
                sheetDragMode = SheetDragMode.NONE
                sheetDragPointerId = -1
                onSheetMoveEnd?.invoke()
                return true
            }
            stylusPrimaryPatternPressed = false
            stylusSecondaryPatternPressed = false
            if (event.getPointerId(event.actionIndex) == palmPointerId) palmPointerId = -1
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
                    if (tool == EditorTool.SCRIBBLE_ERASE) {
                        applyDeferredErase(scribbleEraseWidthMm / 2f)
                        eraseSegments.clear()
                    }
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
            palmPointerId = -1
            sheetDragMode = SheetDragMode.NONE
            sheetDragPointerId = -1
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
            eraseSegments.clear()
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

    private fun isStylusTool(event: MotionEvent, idx: Int): Boolean {
        val t = event.getToolType(idx)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun isStylusPointer(pid: Int, event: MotionEvent): Boolean {
        val idx = event.findPointerIndex(pid)
        return idx >= 0 && isStylusTool(event, idx)
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
        drawPageIndex = pageIndex
        clearSelection()
        scribbleEraseActive = false
        scribblePath.clear()
        scribbleRecentPoints.clear()
        scribbleEraseLastX = toMmX(event, pointerIndex)
        scribbleEraseLastY = toMmY(event, pointerIndex)
        removedStrokesThisGesture.clear()
        val brush = if (tool == EditorTool.HIGHLIGHT && highlightSpec != null) {
            InkUtil.penBrush(
                highlightSpec!!.colorArgb,
                highlightSpec!!.sizeMm * zoomPxPerMm,
            )
        } else {
            InkUtil.penBrush(penColorArgb, penSizeMm * zoomPxPerMm)
        }
        requestUnbufferedDispatch(event)
        activeStrokeId = inProgressView.startStroke(event, activePointerId, brush)
    }

    private fun startErase(event: MotionEvent, pointerIndex: Int) {
        activePointerId = event.getPointerId(pointerIndex)
        drawPageIndex = pageIndex
        removedStrokesThisGesture.clear()
        eraseSegments.clear()
        lastEraseX = toMmX(event, pointerIndex)
        lastEraseY = toMmY(event, pointerIndex)
        requestUnbufferedDispatch(event)
    }

    private fun moveErase(event: MotionEvent) {
        val idx = event.findPointerIndex(activePointerId)
        if (idx < 0) return
        val x = toMmX(event, idx)
        val y = toMmY(event, idx)
        eraseSegments.add(lastEraseX)
        eraseSegments.add(lastEraseY)
        eraseSegments.add(x)
        eraseSegments.add(y)
        lastEraseX = x
        lastEraseY = y
    }

    /**
     * Applies the accumulated [eraseSegments] once, removing every stroke touched by any segment.
     * Kept cheap during the gesture: no stroke traversal or re-render happens until pen-up.
     */
    private fun applyDeferredErase(radiusMm: Float) {
        val list = strokes.getOrNull(drawPageIndex) ?: return
        if (eraseSegments.isEmpty()) return
        val toRemove = LinkedHashSet<Stroke>()
        val r2 = radiusMm * radiusMm
        val n = eraseSegments.size
        // Per-stroke bounding boxes let us skip the expensive per-point test for
        // strokes that are nowhere near the erase path (keeps large notes responsive).
        val bounds = list.map { strokeBounds(it) }
        for (i in 0 until n step 4) {
            val x1 = eraseSegments[i]
            val y1 = eraseSegments[i + 1]
            val x2 = eraseSegments[i + 2]
            val y2 = eraseSegments[i + 3]
            val sx0 = minOf(x1, x2) - radiusMm
            val sx1 = maxOf(x1, x2) + radiusMm
            val sy0 = minOf(y1, y2) - radiusMm
            val sy1 = maxOf(y1, y2) + radiusMm
            for (idx in list.indices) {
                val s = list[idx]
                if (s in toRemove) continue
                val b = bounds[idx]
                if (sx1 < b.x0 || sx0 > b.x1 || sy1 < b.y0 || sy0 > b.y1) continue
                if (strokeIntersects(s, x1, y1, x2, y2, r2)) toRemove.add(s)
            }
        }
        if (toRemove.isEmpty()) return
        for (s in toRemove) {
            val idx = list.indexOf(s)
            if (idx >= 0) removedStrokesThisGesture.add(idx to s)
            list.remove(s)
        }
        dirtyStrokePages.add(drawPageIndex)
        val removedBounds = boundsOf(toRemove)
        if (removedBounds != null) redrawPageRegions(drawPageIndex, listOf(removedBounds)) else renderContent()
        invalidate()
    }

    private data class StrokeBounds(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    private fun unionBounds(a: StrokeBounds, b: StrokeBounds): StrokeBounds =
        StrokeBounds(min(a.x0, b.x0), min(a.y0, b.y0), max(a.x1, b.x1), max(a.y1, b.y1))

    private fun boundsOf(strokes: Iterable<Stroke>): StrokeBounds? {
        var x0 = Float.POSITIVE_INFINITY
        var y0 = Float.POSITIVE_INFINITY
        var x1 = Float.NEGATIVE_INFINITY
        var y1 = Float.NEGATIVE_INFINITY
        var any = false
        for (s in strokes) {
            if (s.inputs.size == 0) continue
            val b = strokeBounds(s)
            any = true
            if (b.x0 < x0) x0 = b.x0
            if (b.y0 < y0) y0 = b.y0
            if (b.x1 > x1) x1 = b.x1
            if (b.y1 > y1) y1 = b.y1
        }
        return if (any) StrokeBounds(x0, y0, x1, y1) else null
    }

    /**
     * Re-draws only the given page regions instead of the whole page bitmap. Falls back to a full
     * render when there is no cached bitmap, while memorization is active, or the page was resized.
     */
    private fun redrawPageRegions(index: Int, regions: List<StrokeBounds>) {
        val n = note ?: return
        val page = n.pages.getOrNull(index) ?: return
        if (regions.isEmpty() || memorizeSession != null) {
            renderPageBitmap(index, force = true)
            return
        }
        val bmp = pageContentBitmaps[page.id]?.takeIf { !it.isRecycled }
            ?: return renderPageBitmap(index, force = true)
        val sx = bmp.width / page.widthMm
        val sy = bmp.height / page.heightMm
        val pad = 3f * sx
        val canvas = Canvas(bmp)
        val clip = RectF()
        for (b in regions) {
            clip.set(
                (b.x0 * sx - pad).coerceIn(0f, bmp.width.toFloat()),
                (b.y0 * sy - pad).coerceIn(0f, bmp.height.toFloat()),
                (b.x1 * sx + pad).coerceIn(0f, bmp.width.toFloat()),
                (b.y1 * sy + pad).coerceIn(0f, bmp.height.toFloat()),
            )
            if (clip.right <= clip.left || clip.bottom <= clip.top) continue
            canvas.save()
            canvas.clipRect(clip)
            canvas.drawColor(Color.WHITE)
            drawBackground(canvas, page, bmp.width.toFloat(), bmp.height.toFloat(), sx)
            val padMm = pad / sx
            drawPageStrokes(
                canvas, page, index, bmp.width, bmp.height,
                StrokeBounds(b.x0 - padMm, b.y0 - padMm, b.x1 + padMm, b.y1 + padMm),
            )
            canvas.restore()
        }
        invalidate()
    }

    private fun strokeBounds(s: Stroke): StrokeBounds {
        val batch = s.inputs
        if (batch.size == 0) return StrokeBounds(0f, 0f, 0f, 0f)
        var x0 = Float.POSITIVE_INFINITY
        var y0 = Float.POSITIVE_INFINITY
        var x1 = Float.NEGATIVE_INFINITY
        var y1 = Float.NEGATIVE_INFINITY
        for (i in 0 until batch.size) {
            val p = batch.get(i)
            if (p.x < x0) x0 = p.x
            if (p.y < y0) y0 = p.y
            if (p.x > x1) x1 = p.x
            if (p.y > y1) y1 = p.y
        }
        return StrokeBounds(x0, y0, x1, y1)
    }

    private fun strokeIntersects(s: Stroke, x1: Float, y1: Float, x2: Float, y2: Float, r2: Float): Boolean {
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
        drawPageIndex = pageIndex
        removedStrokesThisGesture.clear()
        eraseSegments.clear()
        scribbleEraseLastX = toMmX(event, pointerIndex)
        scribbleEraseLastY = toMmY(event, pointerIndex)
        clearSelection()
        val brush = InkUtil.penBrush(scribbleEraseColor, scribbleEraseWidthMm * zoomPxPerMm)
        requestUnbufferedDispatch(event)
        activeStrokeId = inProgressView.startStroke(event, activePointerId, brush)
    }

    /** Accumulates one scribble-erase segment; actual stroke removal happens at pen-up. */
    private fun scribbleEraseTo(x1: Float, y1: Float, x2: Float, y2: Float) {
        eraseSegments.add(x1)
        eraseSegments.add(y1)
        eraseSegments.add(x2)
        eraseSegments.add(y2)
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
        applyDeferredErase(scribbleEraseWidthMm / 2f)
        scribblePath.clear()
        scribbleRecentPoints.clear()
        eraseSegments.clear()
        if (removedStrokesThisGesture.isNotEmpty()) {
            pushUndo(RemoveStrokesOp(drawPageIndex, removedStrokesThisGesture.toList()))
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
        dirtyStrokePages.add(pageIndex)
        val moved = lassoedStrokeIndices.mapNotNull { list.getOrNull(it) }
        val frameBounds = boundsOf(moved)
        if (frameBounds != null) {
            lassoDirtyBounds = lassoDirtyBounds?.let { unionBounds(it, frameBounds) } ?: frameBounds
        }
        val region = lassoDirtyBounds
        if (region != null) redrawPageRegions(pageIndex, listOf(region)) else renderContent()
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
        lassoDirtyBounds = null
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
        applyDeferredErase(eraserRadiusMm)
        eraseSegments.clear()
        if (removedStrokesThisGesture.isNotEmpty()) {
            pushUndo(RemoveStrokesOp(drawPageIndex, removedStrokesThisGesture.toList()))
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
                // Pan both axes in every layout; clampPan decides how far each is allowed to go.
                panX += dx
                panY += dy
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
        val singleMode = pageLayoutMode == PageLayoutMode.SINGLE
        for (i in n.pages.indices) {
            // In single-page layout all pages share the same offset, so only the active page
            // must be drawn (otherwise the last page always overlays the current one).
            if (singleMode && i != pageIndex) continue
            if (!pageVisibleOnScreen(i) && i != pageIndex) continue
            renderPageBitmap(i, force = false)
            drawPageOnScreen(canvas, i)
        }
        evictInvisiblePages()
        if (snipPointerId >= 0 && snipPageIndex in n.pages.indices) {
            val left = pageLeftPx(snipPageIndex)
            val top = pageTopPx(snipPageIndex)
            canvas.drawRect(
                left + min(snipStartX, snipEndX) * zoomPxPerMm,
                top + min(snipStartY, snipEndY) * zoomPxPerMm,
                left + max(snipStartX, snipEndX) * zoomPxPerMm,
                top + max(snipStartY, snipEndY) * zoomPxPerMm,
                selectPaint,
            )
        }
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

        pageContentBitmaps[page.id]?.let { bmp ->
            val dst = RectF(left, top, right, bottom)
            canvas.drawBitmap(bmp, null, dst, imageFilter)
        }

        drawBoxes(canvas, page, left, top)
        if (i == pageIndex) drawSelection(canvas, page, left, top)
        if (memorizeSession != null && i == pageIndex) drawMemorizeSheet(canvas, left, top)

        canvas.drawRect(left, top, right, bottom, borderPaint)
    }

    /** Translucent red-sheet overlay with a drag handle at the sheet's top-center. */
    private fun drawMemorizeSheet(canvas: Canvas, originX: Float, originY: Float) {
        val state = memorizeSession?.state ?: return
        val rect = state.sheetRectMm
        val l = originX + rect.leftMm * zoomPxPerMm
        val t = originY + rect.topMm * zoomPxPerMm
        val r = l + rect.widthMm * zoomPxPerMm
        val b = t + rect.heightMm * zoomPxPerMm
        lastSheetScreenRect = RectF(l, t, r, b)
        memorizePaint.style = Paint.Style.FILL
        memorizePaint.color = state.memorizeColorArgb
        memorizePaint.alpha = SHEET_ALPHA
        canvas.drawRect(l, t, r, b, memorizePaint)
        memorizePaint.alpha = 255
        memorizePaint.style = Paint.Style.STROKE
        memorizePaint.strokeWidth = 2f * density
        canvas.drawRect(l, t, r, b, memorizePaint)
        // Move handle: wide pill on the top edge so it is easy to grab.
        val hx = (l + r) / 2f
        val hy = t
        val pillHalfW = SHEET_MOVE_PILL_HALF_W_PX * density
        val pillHalfH = SHEET_MOVE_PILL_HALF_H_PX * density
        memorizePaint.style = Paint.Style.FILL
        canvas.drawRoundRect(
            hx - pillHalfW, hy - pillHalfH, hx + pillHalfW, hy + pillHalfH,
            pillHalfH, pillHalfH, memorizePaint,
        )
        memorizePaint.color = Color.WHITE
        for (k in -1..1) {
            val ly = hy + k * 5f * density
            canvas.drawLine(hx - 8f * density, ly, hx + 8f * density, ly, memorizePaint)
        }
        // Resize handles: white circles with sheet-color outline at the bottom corners.
        drawSheetResizeHandle(canvas, l, b)
        drawSheetResizeHandle(canvas, r, b)
    }

    private fun drawSheetResizeHandle(canvas: Canvas, cx: Float, cy: Float) {
        val state = memorizeSession?.state ?: return
        memorizePaint.style = Paint.Style.FILL
        memorizePaint.color = Color.WHITE
        memorizePaint.alpha = 255
        canvas.drawCircle(cx, cy, SHEET_RESIZE_R_PX * density, memorizePaint)
        memorizePaint.style = Paint.Style.STROKE
        memorizePaint.color = state.memorizeColorArgb
        memorizePaint.strokeWidth = 2.5f * density
        canvas.drawCircle(cx, cy, SHEET_RESIZE_R_PX * density, memorizePaint)
        memorizePaint.color = state.memorizeColorArgb
        memorizePaint.strokeWidth = 2.5f * density
        val o = SHEET_RESIZE_R_PX * density * 0.45f
        canvas.drawLine(cx - o, cy + o, cx + o, cy - o, memorizePaint)
    }

    /** Which sheet handle (if any) is under the screen point. */
    private fun sheetHitMode(screenX: Float, screenY: Float): SheetDragMode {
        val rect = memorizeSession?.state?.sheetRectMm ?: return SheetDragMode.NONE
        val l = pageLeftPx(pageIndex) + rect.leftMm * zoomPxPerMm
        val t = pageTopPx(pageIndex) + rect.topMm * zoomPxPerMm
        val r = l + rect.widthMm * zoomPxPerMm
        val b = t + rect.heightMm * zoomPxPerMm
        val cornerHit = SHEET_RESIZE_HIT_PX * density
        if ((screenX - l) * (screenX - l) + (screenY - b) * (screenY - b) <= cornerHit * cornerHit) {
            return SheetDragMode.RESIZE_BL
        }
        if ((screenX - r) * (screenX - r) + (screenY - b) * (screenY - b) <= cornerHit * cornerHit) {
            return SheetDragMode.RESIZE_BR
        }
        val hx = (l + r) / 2f
        val pad = SHEET_MOVE_HIT_PAD_PX * density
        if (screenX >= hx - SHEET_MOVE_PILL_HALF_W_PX * density - pad &&
            screenX <= hx + SHEET_MOVE_PILL_HALF_W_PX * density + pad &&
            screenY >= t - SHEET_MOVE_PILL_HALF_H_PX * density - pad &&
            screenY <= t + SHEET_MOVE_PILL_HALF_H_PX * density + pad
        ) {
            return SheetDragMode.MOVE
        }
        return SheetDragMode.NONE
    }

    /**
     * Resizes the sheet from the drag start: the corner under the finger follows it while the
     * opposite edges stay anchored. Clamped to the page and to [SHEET_MIN_SIZE_MM].
     */
    private fun applySheetResize(screenX: Float, screenY: Float) {
        val page = note?.pages?.getOrNull(pageIndex) ?: return
        val dxMm = (screenX - sheetResizeStartScreenX) / zoomPxPerMm
        val dyMm = (screenY - sheetResizeStartScreenY) / zoomPxPerMm
        var newLeft: Float
        var newRight: Float
        if (sheetDragMode == SheetDragMode.RESIZE_BL) {
            newLeft = (sheetResizeOrigLeftMm + dxMm).coerceIn(
                0f, (sheetResizeOrigRightMm - SHEET_MIN_SIZE_MM).coerceAtLeast(0f),
            )
            newRight = sheetResizeOrigRightMm
        } else {
            newLeft = sheetResizeOrigLeftMm
            newRight = (sheetResizeOrigRightMm + dxMm).coerceIn(
                (sheetResizeOrigLeftMm + SHEET_MIN_SIZE_MM).coerceAtMost(page.widthMm),
                page.widthMm,
            )
        }
        val newTop = sheetResizeOrigTopMm
        val maxBottom = page.heightMm
        val newBottom = (sheetResizeOrigBottomMm + dyMm).coerceIn(
            (newTop + SHEET_MIN_SIZE_MM).coerceAtMost(maxBottom), maxBottom,
        )
        // Keep the sheet inside the page horizontally as well.
        newLeft = newLeft.coerceIn(0f, (page.widthMm - SHEET_MIN_SIZE_MM).coerceAtLeast(0f))
        newRight = newRight.coerceIn(
            (newLeft + SHEET_MIN_SIZE_MM).coerceAtMost(page.widthMm), page.widthMm,
        )
        val w = (newRight - newLeft).coerceAtLeast(SHEET_MIN_SIZE_MM)
        val h = (newBottom - newTop).coerceAtLeast(SHEET_MIN_SIZE_MM)
        onSheetRectChanged?.invoke(RectDataMm(newLeft, newTop, w, h))
    }

    private fun drawBoxes(canvas: Canvas, page: PageData, originX: Float, originY: Float, pxPerMm: Float = zoomPxPerMm) {
        val all = page.textBoxes.map { TextSel(it) as Selection } +
            page.imageBoxes.map { ImageSel(it) as Selection }
        for (sel in all.sortedBy { it.zIndex }) {
            if (memorizeHidesBox(sel)) continue
            canvas.save()
            canvas.translate(sel.left() * pxPerMm + originX, sel.top() * pxPerMm + originY)
            canvas.rotate(sel.rotationDeg())
            val wPx = sel.width() * pxPerMm
            val hPx = sel.height() * pxPerMm
            when (sel) {
                is TextSel -> drawTextBox(canvas, sel.box, pxPerMm)
                is ImageSel -> drawImageBox(canvas, sel.box, wPx, hPx)
            }
            canvas.restore()
        }
    }

    /** True when a box is hidden under the memorization sheet (text of matching color only). */
    private fun memorizeHidesBox(sel: Selection): Boolean {
        val session = memorizeSession ?: return false
        if (sel !is TextSel) return false
        val box = sel.box
        return session.contentHidden(
            RectDataMm(box.leftMm, box.topMm, box.widthMm, box.heightMm),
            box.colorArgb.toInt(),
        )
    }

    private fun drawTextBox(canvas: Canvas, box: TextBox, pxPerMm: Float) {
        if (box.text.isBlank()) return
        val layoutScale = BITMAP_DPI / MM_PER_INCH
        val paddingPx = 2f * BITMAP_DPI / 160f
        val fontSizePx = (box.fontSizeMm * layoutScale).coerceAtLeast(0.1f)
        val widthPx = (box.widthMm * layoutScale - 2f * paddingPx).toInt().coerceAtLeast(1)
        val maxLines = (box.heightMm * layoutScale / fontSizePx).toInt().coerceAtLeast(1)
        val layout = textLayoutCache.getOrPut(TextLayoutKey(box.text, box.colorArgb.toInt(), widthPx, maxLines, fontSizePx)) {
            val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = box.colorArgb.toInt()
                textSize = fontSizePx
            }
            android.text.StaticLayout.Builder
                .obtain(box.text, 0, box.text.length, textPaint, widthPx)
                .setMaxLines(maxLines)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
        }
        canvas.save()
        canvas.scale(pxPerMm / layoutScale, pxPerMm / layoutScale)
        canvas.translate(paddingPx, 0f)
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
        val bmp = controller?.loadAsset?.invoke(assetName)
        if (bmp == null) {
            // Not decoded yet: ask the host to load it asynchronously (once per asset).
            if (pendingAssetNames.add(assetName)) controller?.onAssetNeeded?.invoke(assetName)
            return null
        }
        pendingAssetNames.remove(assetName)
        return bmp
    }

    /** Called by the host once [name] has been decoded; re-renders the pages that use it. */
    fun invalidateAsset(name: String) {
        pendingAssetNames.remove(name)
        val n = note
        if (n != null) {
            for (i in n.pages.indices) {
                val p = n.pages[i]
                val uses = p.background.backgroundImageName == name ||
                    p.imageBoxes.any { it.assetName == name }
                if (uses) pageContentBitmaps.remove(p.id)
            }
        }
        val stale = keyedBackgroundCache.keys.filter { it.assetName == name }
        for (key in stale) keyedBackgroundCache.remove(key)?.recycle()
        invalidate()
    }

    private fun clearKeyedBackgroundCache() {
        keyedBackgroundCache.values.forEach { it.recycle() }
        keyedBackgroundCache.clear()
    }

    // ------------------------------------------------------------------ content bitmap

    /** Cheaply draws one committed stroke onto the existing page bitmap (no full re-render).
     *  Returns false when there was no bitmap to draw into (caller should do a full render). */
    private fun appendStrokeToBitmap(stroke: Stroke): Boolean {
        val page = note?.pages?.getOrNull(drawPageIndex) ?: return false
        val bmp = pageContentBitmaps[page.id] ?: return false
        val inputs = stroke.inputs
        if (inputs.size < 1) return true
        val color = stroke.brush.colorIntArgb
        val memSession = memorizeSession
        if (memSession != null && strokeHiddenBySheet(memSession, stroke, color)) return true
        val scale = bmp.width / page.widthMm
        val canvas = Canvas(bmp)
        val pageToBmp = Matrix().apply { setScale(scale, bmp.height / page.heightMm) }
        val translucent = ((color ushr 24) and 0xFF) < 0xFF
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeCap = if (translucent) Paint.Cap.SQUARE else Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            this.color = color
            strokeWidth = (stroke.brush.size * scale).coerceAtLeast(1f)
        }
        val path = Path()
        val pts = FloatArray(2)
        val p0 = inputs.get(0)
        pts[0] = p0.x; pts[1] = p0.y
        pageToBmp.mapPoints(pts)
        path.moveTo(pts[0], pts[1])
        for (k in 1 until inputs.size) {
            val pi = inputs.get(k)
            pts[0] = pi.x; pts[1] = pi.y
            pageToBmp.mapPoints(pts)
            path.lineTo(pts[0], pts[1])
        }
        canvas.drawPath(path, paint)
        return true
    }

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
        val w = page.widthMm.toDouble()
        val h = page.heightMm.toDouble()
        if (!w.isFinite() || !h.isFinite() || w <= 0.0 || h <= 0.0) return 1 to 1
        val scale = minOf(BITMAP_DPI.toDouble() / MM_PER_INCH, sqrt(MAX_CONTENT_PIXELS / (w * h)), MAX_BITMAP_DIM / max(w, h))
        return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
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
        evictInvisiblePages()
        if (pageLayoutMode == PageLayoutMode.SINGLE && i != pageIndex) return
        val existing = pageContentBitmaps[page.id]
        if (!force && existing != null) return

        val (baseW, baseH) = contentDims(page)
        var wPx = baseW
        var hPx = baseH
        // Reuse the existing bitmap when the dimensions match to avoid per-stroke allocations.
        var bmp: Bitmap? = existing?.takeIf { it.width == wPx && it.height == hPx && !it.isRecycled }
        if (bmp == null) pageContentBitmaps.remove(page.id)
        trimPageBitmapBudget(page.id, wPx.toLong() * hPx * 4)
        while (bmp == null) {
            try {
                bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                if (wPx == 1 && hPx == 1) break
                wPx = (wPx / 2).coerceAtLeast(1)
                hPx = (hPx / 2).coerceAtLeast(1)
            }
        }
        if (bmp == null) return
        trimPageBitmapBudget(page.id, bmp.allocationByteCount.toLong())
        pageContentBitmaps[page.id] = bmp
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        drawBackground(canvas, page, wPx.toFloat(), hPx.toFloat(), wPx / page.widthMm)
        val memSession = memorizeSession
        if (memSession != null) drawKeyedBackground(canvas, page, memSession, wPx, hPx)
        drawPageStrokes(canvas, page, i, wPx, hPx)
        evictInvisiblePages()
        invalidate()
    }

    /**
     * Draws the committed strokes of page [index] into a page bitmap. Highlighters (translucent)
     * are drawn in the first pass so pen ink stays on top; the two-pass walk avoids allocating and
     * sorting an index list on every render. When [clipMm] is given, strokes whose bounds do not
     * intersect it are skipped (used for partial redraws).
     */
    private fun drawPageStrokes(
        canvas: Canvas,
        page: PageData,
        index: Int,
        wPx: Int,
        hPx: Int,
        clipMm: StrokeBounds? = null,
    ) {
        val pageStrokes = strokes.getOrNull(index).orEmpty()
        if (pageStrokes.isEmpty()) return
        val sx = wPx / page.widthMm
        val pageToBmp = renderMatrix.apply { setScale(sx, hPx / page.heightMm) }
        val paint = renderStrokePaint
        val path = renderPath
        val pts = renderPoint
        val memSession = memorizeSession
        for (pass in 0..1) {
            val wantTranslucent = pass == 0
            for (s in pageStrokes) {
                val inputs = s.inputs
                if (inputs.size < 1) continue
                val color = s.brush.colorIntArgb
                val translucent = ((color ushr 24) and 0xFF) < 0xFF
                if (translucent != wantTranslucent) continue
                if (memSession != null && strokeHiddenBySheet(memSession, s, color)) continue
                if (clipMm != null) {
                    val b = strokeBounds(s)
                    if (b.x1 < clipMm.x0 || b.x0 > clipMm.x1 || b.y1 < clipMm.y0 || b.y0 > clipMm.y1) continue
                }
                paint.color = color
                paint.strokeWidth = (s.brush.size * sx).coerceAtLeast(1f)
                paint.strokeCap = if (translucent) Paint.Cap.SQUARE else Paint.Cap.ROUND
                path.reset()
                val p0 = inputs.get(0)
                pts[0] = p0.x; pts[1] = p0.y
                pageToBmp.mapPoints(pts)
                path.moveTo(pts[0], pts[1])
                for (k in 1 until inputs.size) {
                    val pi = inputs.get(k)
                    pts[0] = pi.x; pts[1] = pi.y
                    pageToBmp.mapPoints(pts)
                    path.lineTo(pts[0], pts[1])
                }
                canvas.drawPath(path, paint)
            }
        }
    }

    /**
     * Re-draws the raster background inside the sheet area using a color-keyed copy where pixels
     * matching the sheet color are white, so printed red content (PDF pages) disappears under the
     * sheet. The keyed copy is cached per (asset, color, tolerance) and only rebuilt when those
     * change, so moving the sheet costs no per-pixel work.
     */
    private fun drawKeyedBackground(canvas: Canvas, page: PageData, session: MemorizeSession, wPx: Int, hPx: Int) {
        val imgName = page.background.backgroundImageName ?: return
        val keyed = keyedBackground(imgName, session) ?: return
        val sx = wPx / page.widthMm
        val sy = hPx / page.heightMm
        val rect = session.state.sheetRectMm
        canvas.save()
        canvas.clipRect(
            rect.leftMm * sx,
            rect.topMm * sy,
            (rect.leftMm + rect.widthMm) * sx,
            (rect.topMm + rect.heightMm) * sy,
        )
        canvas.drawBitmap(keyed, null, RectF(0f, 0f, wPx.toFloat(), hPx.toFloat()), imageFilter)
        canvas.restore()
    }

    /** Cached copy of [assetName] with every sheet-colored pixel replaced by white. */
    private fun keyedBackground(assetName: String, session: MemorizeSession): Bitmap? {
        val color = session.state.memorizeColorArgb
        val tolerance = session.state.tolerance.toInt()
        val key = KeyedBackground(assetName, color, tolerance)
        keyedBackgroundCache[key]?.let { if (!it.isRecycled) return it }
        val source = loadAssetBitmap(assetName) ?: return null
        val out = try {
            source.copy(Bitmap.Config.ARGB_8888, true)
        } catch (_: OutOfMemoryError) {
            null
        } ?: return null
        val w = out.width
        val h = out.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        out.getPixels(pixels, 0, w, 0, 0, w, h)
        for (idx in pixels.indices) {
            if (session.colorMatches(pixels[idx])) pixels[idx] = 0xFFFFFFFF.toInt()
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        keyedBackgroundCache[key] = out
        return out
    }

    private data class KeyedBackground(val assetName: String, val colorArgb: Int, val tolerance: Int)

    /** True when a committed stroke is hidden under the memorization sheet. */
    private fun strokeHiddenBySheet(session: MemorizeSession, s: Stroke, color: Int): Boolean {
        val batch = s.inputs
        if (batch.size == 0) return false
        var x0 = Float.POSITIVE_INFINITY
        var y0 = Float.POSITIVE_INFINITY
        var x1 = Float.NEGATIVE_INFINITY
        var y1 = Float.NEGATIVE_INFINITY
        for (k in 0 until batch.size) {
            val p = batch.get(k)
            if (p.x < x0) x0 = p.x
            if (p.y < y0) y0 = p.y
            if (p.x > x1) x1 = p.x
            if (p.y > y1) y1 = p.y
        }
        return session.contentHidden(
            RectDataMm(x0, y0, (x1 - x0).coerceAtLeast(0f), (y1 - y0).coerceAtLeast(0f)),
            color,
        )
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
        val pages = note?.pages ?: return
        val keep = HashSet<String>()
        if (pageLayoutMode == PageLayoutMode.SINGLE) {
            pages.getOrNull(pageIndex)?.let { keep.add(it.id) }
        } else {
            pages.forEachIndexed { i, p -> if (i == pageIndex || pageVisibleOnScreen(i)) keep.add(p.id) }
        }
        val removable = pageContentBitmaps.keys.filter { it !in keep }
        for (k in removable) pageContentBitmaps.remove(k)
    }

    private fun trimPageBitmapBudget(activeKey: String, requiredBytes: Long) {
        var bytes = pageContentBitmaps.entries.sumOf { if (it.key == activeKey) 0L else it.value.allocationByteCount.toLong() }
        val activeId = note?.pages?.getOrNull(pageIndex)?.id
        val candidates = pageContentBitmaps.keys.filter { it != activeKey }.sortedBy { it == activeId }
        for (key in candidates) {
            if (bytes + requiredBytes <= MAX_PAGE_BITMAP_BYTES) break
            val removed = pageContentBitmaps.remove(key) ?: continue
            bytes -= removed.allocationByteCount
        }
    }

    private fun drawBackground(canvas: Canvas, page: PageData, wPx: Float, hPx: Float, scale: Float) {
        val bg = page.background
        val imgName = bg.backgroundImageName
        if (imgName != null) {
            val bmp = loadAssetBitmap(imgName)
            if (bmp != null) {
                canvas.drawBitmap(bmp, null, RectF(0f, 0f, wPx, hPx), imageFilter)
                return
            }
        }
        when (bg.type) {
            BackgroundType.BLANK -> {}
            BackgroundType.GRID -> drawGrid(canvas, bg, wPx, hPx, scale)
            BackgroundType.RULED -> drawRuled(canvas, bg, wPx, hPx, scale, page)
            BackgroundType.DOT -> drawDot(canvas, bg, wPx, hPx, scale)
        }
    }

    private fun drawGrid(canvas: Canvas, bg: BackgroundSpec, wPx: Float, hPx: Float, scale: Float) {
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

    private fun drawRuled(canvas: Canvas, bg: BackgroundSpec, wPx: Float, hPx: Float, scale: Float, page: PageData) {
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
        if (bg.showMargin) {
            canvas.drawLine(bg.marginXMm * scale, 0f, bg.marginXMm * scale, hPx.toFloat(), marginPaint)
        }
    }

    private fun drawDot(canvas: Canvas, bg: BackgroundSpec, wPx: Float, hPx: Float, scale: Float) {
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
        private const val MEMORIZE_RENDER_THROTTLE_MS = 100L
        private const val COMMIT_RENDER_DELAY_MS = 450L
        private const val SHEET_ALPHA = 110
        private const val SHEET_MOVE_PILL_HALF_W_PX = 32f
        private const val SHEET_MOVE_PILL_HALF_H_PX = 11f
        private const val SHEET_MOVE_HIT_PAD_PX = 10f
        private const val SHEET_RESIZE_R_PX = 11f
        private const val SHEET_RESIZE_HIT_PX = 26f
        private const val SHEET_MIN_SIZE_MM = 15f

        private const val BITMAP_DPI = 300f

        /** Hard cap on each content bitmap dimension (px) to bound memory. */
        private const val MAX_BITMAP_DIM = 8192

        private const val MAX_CONTENT_PIXELS = 8_000_000L
        private const val MAX_PAGE_BITMAP_BYTES = 48L * 1024 * 1024
    }
}

