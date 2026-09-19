package com.stylusmemo.app.ui.editor

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stylusmemo.app.StylusMemoApp
import com.stylusmemo.app.data.NoteRepository
import com.stylusmemo.app.data.SettingsRepository
import com.stylusmemo.app.model.BackgroundSpec
import com.stylusmemo.app.model.Note
import com.stylusmemo.app.model.Snip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.stylusmemo.app.model.PageData
import com.stylusmemo.app.model.PageLayoutMode
import com.stylusmemo.app.data.AppSettings
import com.stylusmemo.app.plugin.DataSink
import com.stylusmemo.app.plugin.ExportContext
import com.stylusmemo.app.plugin.PageRasterizer
import com.stylusmemo.app.plugin.aimarkdown.AiMarkdownExporterPlugin
import com.stylusmemo.app.plugin.memorize.MemorizeHost
import com.stylusmemo.app.plugin.memorize.MemorizeLayout
import com.stylusmemo.app.plugin.memorize.MemorizeModePlugin
import com.stylusmemo.app.plugin.memorize.MemorizePaneDirection
import com.stylusmemo.app.plugin.memorize.MemorizeSession
import com.stylusmemo.app.plugin.memorize.MemorizeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val noteRepo: NoteRepository = (app as StylusMemoApp).noteRepository
    private val settingsRepo: SettingsRepository = (app as StylusMemoApp).settingsRepository

    private val _note = MutableStateFlow<Note?>(null)
    val note: StateFlow<Note?> = _note

    private val _noteId = MutableStateFlow<String?>(null)
    val noteId: StateFlow<String?> = _noteId

    private val _tool = MutableStateFlow(EditorTool.PEN)
    val tool: StateFlow<EditorTool> = _tool

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo

    private val _penColorArgb = MutableStateFlow(0xFF1A1A1A)
    val penColorArgb: StateFlow<Long> = _penColorArgb

    private val _penSizeMm = MutableStateFlow(0.5f)
    val penSizeMm: StateFlow<Float> = _penSizeMm

    private val _highlightColorArgb = MutableStateFlow(0x66FFEB3BL)
    val highlightColorArgb: StateFlow<Long> = _highlightColorArgb

    private val _highlightSizeMm = MutableStateFlow(3.0f)
    val highlightSizeMm: StateFlow<Float> = _highlightSizeMm

    private val _fingerDraw = MutableStateFlow(false)
    val fingerDraw: StateFlow<Boolean> = _fingerDraw

    private val _currentPageData = MutableStateFlow<PageData?>(null)
    val currentPageData: StateFlow<PageData?> = _currentPageData

    private val _currentPageIndex = MutableStateFlow(0)
    val currentPageIndex: StateFlow<Int> = _currentPageIndex

    /** True while the note is being loaded from disk; the editor shows a spinner until it is ready. */
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    /** True while the strokes of the current page are still being loaded (lazy page loading). */
    private val _pageLoading = MutableStateFlow(false)
    val pageLoading: StateFlow<Boolean> = _pageLoading

    private val _selectedBox = MutableStateFlow<Pair<String, String>?>(null)
    val selectedBox: StateFlow<Pair<String, String>?> = _selectedBox

    private val _pageLayoutMode = MutableStateFlow(PageLayoutMode.SINGLE)
    val pageLayoutMode: StateFlow<PageLayoutMode> = _pageLayoutMode

    private val _memorizeActive = MutableStateFlow(false)
    val memorizeActive: StateFlow<Boolean> = _memorizeActive

    private val _memorizeState = MutableStateFlow<MemorizeState?>(null)
    val memorizeState: StateFlow<MemorizeState?> = _memorizeState

    private val _memorizeEyedropperArmed = MutableStateFlow(false)
    val memorizeEyedropperArmed: StateFlow<Boolean> = _memorizeEyedropperArmed

    private var noteView: EditorView? = null
    private var scratchView: EditorView? = null
    private var memorizeSession: MemorizeSession? = null
    private var memorizeTolerance: Float = 90f
    private var toolBeforeEyedropper: EditorTool = EditorTool.PEN
    private var highlightSpec: com.stylusmemo.app.plugin.tools.ToolSpec? = null
    private var highlightBaseSpec: com.stylusmemo.app.plugin.tools.ToolSpec? = null

    private val _selectedSnipId = MutableStateFlow<String?>(null)
    val selectedSnipId: StateFlow<String?> = _selectedSnipId
    private val _snipBusy = MutableStateFlow(false)
    val snipBusy: StateFlow<Boolean> = _snipBusy
    private val _snipError = MutableStateFlow<String?>(null)
    val snipError: StateFlow<String?> = _snipError
    private val snipDecodeMutex = Mutex()
    private var noteGeneration = 0L
    private var openJob: Job? = null
    private var pdfImportJob: Job? = null
    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastWrite: Job? = null

    private var saveJob: Job? = null
    private var penSaveJob: Job? = null
    private var pendingSnapshot: EditorSnapshot? = null
    private var viewBound = false
    private var lastSettings: AppSettings? = null
    private val assetCache = (app as StylusMemoApp).assetCache
    private val pendingAssetLoads = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val controller = EditorController()

    init {
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                val prev = lastSettings
                // Only adopt a setting when it actually changed, so in-session choices
                // (toolbar layout, last used pen) are not reset by unrelated emissions.
                if (prev == null || prev.defaultPenColorArgb != s.defaultPenColorArgb) {
                    _penColorArgb.value = s.defaultPenColorArgb
                }
                if (prev == null || prev.defaultPenSizeMm != s.defaultPenSizeMm) {
                    _penSizeMm.value = s.defaultPenSizeMm
                }
                if (prev == null || prev.defaultPageLayoutMode != s.defaultPageLayoutMode) {
                    _pageLayoutMode.value = s.defaultPageLayoutMode
                }
                if (prev == null || prev.highlighterColorArgb != s.highlighterColorArgb) {
                    _highlightColorArgb.value = s.highlighterColorArgb
                }
                if (prev == null || prev.highlighterSizeMm != s.highlighterSizeMm) {
                    _highlightSizeMm.value = s.highlighterSizeMm
                }
                if (prev == null || prev.memorizeTolerance != s.memorizeTolerance) {
                    memorizeTolerance = s.memorizeTolerance
                }
                if (prev == null || prev.highlighterColorArgb != s.highlighterColorArgb ||
                    prev.highlighterSizeMm != s.highlighterSizeMm
                ) {
                    applyHighlightSpec()
                }
                if (prev == null || prev.fingerDrawEnabled != s.fingerDrawEnabled) {
                    _fingerDraw.value = s.fingerDrawEnabled
                }
                lastSettings = s
                applySettings(s, prev)
            }
        }
        controller.onToolChanged = { _tool.value = it }
        controller.onUndoRedoChanged = { u, r -> _canUndo.value = u; _canRedo.value = r }
        controller.onPageChanged = {
            _currentPageData.value = controller.currentPageData()
            _currentPageIndex.value = controller.currentPageIndex()
            preloadWindowAround(controller.currentPageIndex())
        }
        controller.onPageStrokesNeeded = { page -> loadPageStrokes(page) }
        controller.onBoxSelected = { kind, id -> _selectedBox.value = kind to id }
        controller.onDocChanged = {
            controller.currentNote()?.takeIf { it.id == _noteId.value && !_loading.value }?.let { _note.value = it }
            scheduleSave()
        }
        controller.onSnipCaptured = { id, bitmap -> storeSnip(id, bitmap) }
        controller.onSnipError = { _snipError.value = it }
        controller.loadAsset = { name -> loadAssetBitmap(name) }
        controller.onAssetNeeded = { name -> requestAsset(name) }
    }

    private fun applySettings(s: AppSettings, prev: AppSettings?) {
        if (prev == null || prev.fingerDrawEnabled != s.fingerDrawEnabled) {
            controller.setFingerDrawEnabled(s.fingerDrawEnabled)
        }
        if (prev == null || prev.stylusPrimaryAction != s.stylusPrimaryAction ||
            prev.stylusSecondaryAction != s.stylusSecondaryAction
        ) {
            controller.setShortcutActions(s.stylusPrimaryAction, s.stylusSecondaryAction)
        }
        if (prev == null || prev.stylusPrimaryPattern != s.stylusPrimaryPattern ||
            prev.stylusSecondaryPattern != s.stylusSecondaryPattern
        ) {
            controller.setLearnedStylusPatterns(s.stylusPrimaryPattern, s.stylusSecondaryPattern)
        }
        if (prev == null || prev.defaultPageLayoutMode != s.defaultPageLayoutMode) {
            controller.setLayoutMode(s.defaultPageLayoutMode)
        }
        if (prev == null || prev.defaultPenColorArgb != s.defaultPenColorArgb ||
            prev.defaultPenSizeMm != s.defaultPenSizeMm
        ) {
            controller.setPen(s.defaultPenColorArgb.toInt(), s.defaultPenSizeMm)
        }
    }

    /** Re-applies the current in-session state to a freshly rebound editor view. */
    private fun applyCurrentStateToView() {
        controller.setFingerDrawEnabled(_fingerDraw.value)
        controller.setLayoutMode(_pageLayoutMode.value)
        controller.setPen(_penColorArgb.value.toInt(), _penSizeMm.value)
        applyHighlightSpec()
        controller.setTool(_tool.value)
        lastSettings?.let {
            controller.setShortcutActions(it.stylusPrimaryAction, it.stylusSecondaryAction)
            controller.setLearnedStylusPatterns(it.stylusPrimaryPattern, it.stylusSecondaryPattern)
        }
    }

    fun setPageLayoutMode(mode: PageLayoutMode) {
        _pageLayoutMode.value = mode
        controller.setLayoutMode(mode)
    }

    fun openNote(id: String) {
        if (_snipBusy.value) return
        if (_noteId.value == id && (_note.value != null || openJob?.isActive == true)) return
        saveJob?.cancel()
        persist()
        openJob?.cancel()
        pdfImportJob?.cancel()
        val generation = ++noteGeneration
        controller.cancelSnip()
        _noteId.value = id
        _note.value = null
        pendingSnapshot = null
        _selectedSnipId.value = null
        _snipBusy.value = false
        _snipError.value = null
        _pageLoading.value = false
        _loading.value = true
        val previousWrite = lastWrite
        openJob = viewModelScope.launch {
            previousWrite?.join()
            val snapshot = withContext(Dispatchers.IO) {
                val note = noteRepo.loadNote(id)
                val count = note.pages.size
                val center = note.lastPageIndex.coerceIn(0, (count - 1).coerceAtLeast(0))
                val window = assetWindow(center, count)
                val loaded = Array(count) { emptyList<androidx.ink.strokes.Stroke>() }
                for (i in window) loaded[i] = noteRepo.loadStrokes(id, i)
                EditorSnapshot(note, loaded.toList(), window.toSet())
            }
            if (generation != noteGeneration) return@launch
            pendingSnapshot = snapshot
            _note.value = snapshot.note
            _selectedSnipId.value = snapshot.note.snips.firstOrNull()?.id
            applyPending()
            val center = snapshot.note.lastPageIndex
                .coerceIn(0, (snapshot.note.pages.size - 1).coerceAtLeast(0))
            viewModelScope.launch(Dispatchers.IO) {
                preloadAssetsForPages(snapshot.note, assetWindow(center, snapshot.note.pages.size))
            }
        }
    }

    fun bindView(view: EditorView) {
        // The note pane is recreated when the layout switches (single <-> split), so carry the
        // live document over to the new instance.
        val old = noteView
        if (old != null && old !== view && !_loading.value) {
            old.buildSnapshot()?.takeIf { it.note.id == _noteId.value }?.let { pendingSnapshot = it }
        }
        noteView = view
        viewBound = true
        controller.bind(view)
        controller.setOnSheetMoved { dxMm, dyMm ->
            // During a drag only redraw the translucent sheet (cheap); the colour-hiding
            // re-render is deferred to drag end.
            memorizeSession?.moveSheet(dxMm, dyMm)
            controller.invalidateMemorizeOverlay()
        }
        controller.setOnSheetRectChanged { rectMm ->
            memorizeSession?.setSheetRect(rectMm)
            controller.invalidateMemorizeOverlay()
        }
        controller.setOnSheetMoveEnd { controller.refreshMemorize(force = true) }
        controller.setOnEyedropperPick { argb ->
            memorizeSession?.setColor(argb)
            setMemorizeEyedropper(false)
            controller.refreshMemorize(force = true)
        }
        memorizeSession?.let {
            controller.setMemorizeSession(it)
        }
        if (_memorizeEyedropperArmed.value) controller.setTool(EditorTool.EYEDROPPER)
        applyCurrentStateToView()
        applyPending()
    }

    /** Binds the scratch pane in memorization mode (blank, disposable, own tool state mirror). */
    fun bindScratchView(view: EditorView) {
        scratchView = view
        val page = controller.currentPageData()
        val w = page?.widthMm ?: 210f
        val h = page?.heightMm ?: 297f
        view.loadScratch(w, h)
        view.setTool(_tool.value.takeUnless { it == EditorTool.EYEDROPPER || it == EditorTool.SNIP } ?: EditorTool.PEN)
        view.setPen(_penColorArgb.value.toInt(), _penSizeMm.value)
        view.setFingerDrawEnabled(_fingerDraw.value)
        view.setLayoutMode(PageLayoutMode.SINGLE)
    }

    fun unbindScratchView() {
        scratchView = null
    }

    private fun applyPending() {
        val snap = pendingSnapshot ?: return
        if (!viewBound) return
        pendingSnapshot = null
        controller.load(snap)
        _currentPageData.value = controller.currentPageData()
        _currentPageIndex.value = controller.currentPageIndex()
        _loading.value = false
    }

    private suspend fun preloadAssetsForPages(note: Note, pageIndices: Collection<Int>) {
        val id = note.id
        val names = pageIndices.distinct()
            .mapNotNull { note.pages.getOrNull(it) }
            .flatMap { p ->
                listOfNotNull(p.background.backgroundImageName) + p.imageBoxes.map { it.assetName }
            }.distinct()
        for (name in names) {
            val key = "$id:$name"
            if (assetCache.get(key) != null) continue
            val bytes = noteRepo.readAsset(id, name) ?: continue
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { assetCache.put(key, it) }
        }
    }

    /** Page indices to load assets for: the given page plus one before and after. */
    private fun assetWindow(center: Int, pageCount: Int): List<Int> {
        if (pageCount <= 0) return emptyList()
        val c = center.coerceIn(0, pageCount - 1)
        return ((c - 1)..(c + 1)).filter { it in 0 until pageCount }
    }

    /** Loads (on a background thread) the assets around [index]; single-flight per asset. */
    private fun preloadWindowAround(index: Int) {
        val note = _note.value ?: return
        val window = assetWindow(index, note.pages.size)
        if (window.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { preloadAssetsForPages(note, window) }
    }

    /** Loads the strokes of a page the editor opened before they were eagerly loaded. */
    private fun loadPageStrokes(page: Int) {
        val id = _noteId.value ?: return
        val generation = noteGeneration
        if (page == controller.currentPageIndex()) _pageLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val strokes = noteRepo.loadStrokes(id, page)
            withContext(Dispatchers.Main) {
                if (generation == noteGeneration && _noteId.value == id && !_loading.value) {
                    controller.loadStrokesForPage(page, strokes)
                }
                if (generation == noteGeneration && _noteId.value == id) {
                    _pageLoading.value = !controller.isPageLoaded(controller.currentPageIndex())
                }
            }
        }
    }

    /** Called when the editor view needs an asset that was not preloaded; decodes then refreshes. */
    private fun requestAsset(name: String) {
        val id = _noteId.value ?: return
        val key = "$id:$name"
        if (assetCache.get(key) != null) {
            controller.invalidateAsset(name)
            return
        }
        if (!pendingAssetLoads.add(key)) return
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = noteRepo.readAsset(id, name)
            val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            pendingAssetLoads.remove(key)
            if (bmp != null) {
                assetCache.put(key, bmp)
                withContext(Dispatchers.Main) { if (_noteId.value == id) controller.invalidateAsset(name) }
            }
            // If the asset is missing/undecodable, leave the view's pending marker in place so it
            // does not retry reading the file on every frame.
        }
    }

    private fun loadAssetBitmap(name: String): Bitmap? = assetCache.get("${_noteId.value}:$name")

    private fun storeSnip(id: String, bitmap: Bitmap) {
        if (_noteId.value != id || _loading.value || _snipBusy.value) {
            bitmap.recycle()
            return
        }
        val generation = noteGeneration
        _snipBusy.value = true
        _snipError.value = null
        viewModelScope.launch {
            try {
                val width = bitmap.width
                val height = bitmap.height
                val assetName = withContext(Dispatchers.IO) {
                    val bytes = java.io.ByteArrayOutputStream().use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                        output.toByteArray()
                    }
                    noteRepo.importAsset(id, bytes.inputStream(), "image/png")
                }
                if (generation != noteGeneration || _noteId.value != id || _loading.value) return@launch
                val snip = Snip(assetName = assetName, widthPx = width, heightPx = height)
                val updated = controller.updateSnips(id) { it + snip } ?: return@launch
                _note.value = updated
                _selectedSnipId.value = snip.id
                flushSave()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == noteGeneration) _snipError.value = "SNIP: PNGの保存に失敗しました。再選択して試してください"
            } catch (_: OutOfMemoryError) {
                if (generation == noteGeneration) _snipError.value = "SNIP: メモリ不足です。小さい範囲で再試行してください"
            } finally {
                bitmap.recycle()
                if (generation == noteGeneration) _snipBusy.value = false
            }
        }
    }

    suspend fun loadSnipBitmap(noteId: String, assetName: String): Bitmap? = snipDecodeMutex.withLock {
        var bitmap: Bitmap? = null
        try {
            withContext(Dispatchers.IO) {
                val bytes = noteRepo.readAsset(noteId, assetName) ?: return@withContext
                bitmap = decodeSampledBitmap(bytes, 1600)
            }
            bitmap
        } catch (e: CancellationException) {
            bitmap?.recycle()
            throw e
        } catch (_: Exception) {
            bitmap?.recycle()
            null
        } catch (_: OutOfMemoryError) {
            bitmap?.recycle()
            null
        }
    }

    fun selectSnip(id: String) {
        if (_note.value?.snips?.any { it.id == id } == true) _selectedSnipId.value = id
    }

    fun resizeSnip(id: String, heightDp: Float) {
        if (!heightDp.isFinite()) return
        changeSnips(debounce = true) { snips -> snips.map { if (it.id == id) it.copy(displayHeightDp = heightDp.coerceIn(80f, 320f)) else it } }
    }

    fun collapseSnip(id: String) {
        changeSnips { snips -> snips.map { if (it.id == id) it.copy(collapsed = !it.collapsed) else it } }
    }

    fun deleteSnip(id: String) {
        changeSnips { snips -> snips.filterNot { it.id == id } }
        if (_selectedSnipId.value == id) _selectedSnipId.value = _note.value?.snips?.firstOrNull()?.id
    }

    private fun changeSnips(debounce: Boolean = false, transform: (List<Snip>) -> List<Snip>) {
        val id = _noteId.value ?: return
        if (_loading.value) return
        controller.updateSnips(id, transform)?.let { _note.value = it }
        if (debounce) scheduleSave() else flushSave()
    }

    fun dismissSnipError() {
        _snipError.value = null
    }

    fun undo() = controller.undo()
    fun redo() = controller.redo()

    fun setTool(t: EditorTool) {
        _tool.value = t
        controller.setTool(t)
        scratchView?.setTool(t.takeUnless { it == EditorTool.EYEDROPPER || it == EditorTool.SNIP } ?: EditorTool.PEN)
        if (t != EditorTool.SELECT) _selectedBox.value = null
    }

    fun setPenColor(argb: Long) {
        _penColorArgb.value = argb
        controller.setPen(argb.toInt(), _penSizeMm.value)
        scratchView?.setPen(argb.toInt(), _penSizeMm.value)
        persistPenDefaults()
    }

    fun setPenSize(mm: Float) {
        _penSizeMm.value = mm
        controller.setPen(_penColorArgb.value.toInt(), mm)
        scratchView?.setPen(_penColorArgb.value.toInt(), mm)
        persistPenDefaults()
    }

    /** Persists the last used pen colour/size so reopening a note keeps them. */
    private fun persistPenDefaults() {
        penSaveJob?.cancel()
        penSaveJob = viewModelScope.launch {
            delay(PEN_SAVE_DEBOUNCE_MS)
            settingsRepo.updateSettings {
                it.copy(
                    defaultPenColorArgb = _penColorArgb.value,
                    defaultPenSizeMm = _penSizeMm.value,
                    highlighterColorArgb = _highlightColorArgb.value,
                    highlighterSizeMm = _highlightSizeMm.value,
                )
            }
        }
    }

    fun setFingerDraw(enabled: Boolean) {
        _fingerDraw.value = enabled
        controller.setFingerDrawEnabled(enabled)
        scratchView?.setFingerDrawEnabled(enabled)
    }

    fun addPage() = runStructuralChange { controller.addPage() }

    fun deletePage() = runStructuralChange { controller.deletePage() }

    fun movePage(from: Int, to: Int) = runStructuralChange { controller.movePage(from, to) }

    private var structuralJob: Job? = null

    /**
     * Page reindexing (insert/remove/move) relies on page-indexed stroke files, so all pages must
     * be loaded first; otherwise unloaded pages would be saved against the wrong files. When
     * nothing is pending the change runs immediately, so the common case stays instant.
     */
    private fun runStructuralChange(action: () -> Unit) {
        if (controller.areAllPagesLoaded()) {
            action()
            syncPageState()
            return
        }
        if (structuralJob?.isActive == true) return
        val id = _noteId.value ?: return
        val generation = noteGeneration
        _pageLoading.value = true
        structuralJob = viewModelScope.launch {
            val snapshot = controller.buildSnapshot() ?: run {
                _pageLoading.value = false
                return@launch
            }
            val missing = snapshot.note.pages.indices.filter {
                snapshot.loadedPages?.contains(it) == false
            }
            withContext(Dispatchers.IO) {
                for (i in missing) {
                    val strokes = noteRepo.loadStrokes(id, i)
                    withContext(Dispatchers.Main) {
                        if (generation == noteGeneration && _noteId.value == id) {
                            controller.loadStrokesForPage(i, strokes)
                        }
                    }
                }
            }
            if (generation != noteGeneration || _noteId.value != id) return@launch
            action()
            syncPageState()
            _pageLoading.value = false
            flushSave()
        }
    }

    private fun syncPageState() {
        _currentPageData.value = controller.currentPageData()
        _currentPageIndex.value = controller.currentPageIndex()
    }

    fun setPageSize(widthMm: Float, heightMm: Float) {
        controller.setPageSize(widthMm, heightMm)
        _currentPageData.value = controller.currentPageData()
    }

    fun setBackground(spec: BackgroundSpec) {
        controller.setBackground(spec)
    }

    fun insertImage(assetName: String, widthMm: Float, heightMm: Float) {
        controller.insertImageBox(assetName, widthMm, heightMm)
    }

    fun addText(text: String, sizeMm: Float, colorArgb: Long) {
        controller.addTextBox(text, sizeMm, colorArgb.toInt())
    }

    fun updateSelectedText(boxId: String, text: String, sizeMm: Float, colorArgb: Long) {
        controller.updateSelectedText(boxId, text, sizeMm, colorArgb.toInt())
    }
    fun deleteSelectedBox() = controller.deleteSelectedBox()

    fun pageCount(): Int = controller.pageCount().takeIf { it > 0 } ?: (_note.value?.pages?.size ?: 0)
    fun currentPageIndex(): Int = controller.currentPageIndex()

    fun switchPage(i: Int) {
        controller.switchPage(i)
        _currentPageData.value = controller.currentPageData()
        _currentPageIndex.value = controller.currentPageIndex()
        _pageLoading.value = !controller.isPageLoaded(controller.currentPageIndex())
    }

    // ------------------------------------------------------------------ memorization mode

    fun memorizePlugins(): List<MemorizeModePlugin> {
        val app = getApplication<StylusMemoApp>()
        return app.pluginRegistry.memorizePlugins()
    }

    fun drawingToolPlugins(): List<com.stylusmemo.app.plugin.tools.DrawingToolPlugin> {
        val app = getApplication<StylusMemoApp>()
        return app.pluginRegistry.drawingToolPlugins()
    }

    /** Activates a drawing-tool plugin (e.g. the highlighter) as the current tool. */
    fun setHighlightTool(plugin: com.stylusmemo.app.plugin.tools.DrawingToolPlugin) {
        highlightBaseSpec = plugin.tool
        applyHighlightSpec()
        _tool.value = EditorTool.HIGHLIGHT
        controller.setTool(EditorTool.HIGHLIGHT)
        scratchView?.setTool(EditorTool.HIGHLIGHT)
        _selectedBox.value = null
    }

    /** Rebuilds the highlighter spec from the plugin base + user colour/size and pushes it. */
    private fun applyHighlightSpec() {
        val base = highlightBaseSpec ?: return
        val spec = base.copy(
            colorArgb = _highlightColorArgb.value.toInt(),
            sizeMm = _highlightSizeMm.value,
        )
        highlightSpec = spec
        controller.setHighlightSpec(spec)
        scratchView?.setHighlightSpec(spec)
    }

    fun setHighlightColor(argb: Long) {
        _highlightColorArgb.value = argb
        applyHighlightSpec()
        persistPenDefaults()
    }

    fun setHighlightSize(mm: Float) {
        _highlightSizeMm.value = mm
        applyHighlightSpec()
        persistPenDefaults()
    }

    fun startMemorize(plugin: MemorizeModePlugin) {
        if (memorizeSession != null) return
        val page = controller.currentPageData() ?: return
        val host = object : MemorizeHost {
            override val pageWidthMm: Float
                get() = controller.currentPageData()?.widthMm ?: page.widthMm
            override val pageHeightMm: Float
                get() = controller.currentPageData()?.heightMm ?: page.heightMm
        }
        val session = plugin.createSession(host)
        session.setTolerance(memorizeTolerance)
        memorizeSession = session
        _memorizeState.value = session.state
        // State changes only update Compose/overlay; the expensive colour-hiding re-render is
        // triggered explicitly (colour change, sheet drag end) so dragging stays smooth.
        session.listener = { s -> _memorizeState.value = s }
        controller.setMemorizeSession(session)
        _memorizeActive.value = true
    }

    fun endMemorize() {
        setMemorizeEyedropper(false)
        memorizeSession?.end()
        memorizeSession = null
        controller.setMemorizeSession(null)
        controller.setOnSheetMoved(null)
        controller.setOnSheetRectChanged(null)
        controller.setOnSheetMoveEnd(null)
        controller.setOnEyedropperPick(null)
        _memorizeState.value = null
        _memorizeActive.value = false
        unbindScratchView()
    }

    fun setMemorizeEyedropper(armed: Boolean) {
        if (!_memorizeActive.value && armed) return
        if (armed == _memorizeEyedropperArmed.value) return
        if (armed) {
            toolBeforeEyedropper = _tool.value
            controller.setTool(EditorTool.EYEDROPPER)
        } else {
            controller.setTool(toolBeforeEyedropper)
        }
        _memorizeEyedropperArmed.value = armed
    }

    fun setMemorizeColor(argb: Int) {
        memorizeSession?.setColor(argb)
        controller.refreshMemorize(force = true)
    }

    fun setMemorizeTolerance(tolerance: Float) {
        memorizeTolerance = tolerance
        memorizeSession?.setTolerance(tolerance)
        controller.refreshMemorize(force = true)
    }

    fun persistMemorizeTolerance(tolerance: Float) {
        viewModelScope.launch {
            settingsRepo.updateSettings { it.copy(memorizeTolerance = tolerance) }
        }
    }

    fun cycleMemorizeDirection() {
        val session = memorizeSession ?: return
        val values = MemorizePaneDirection.entries
        val next = values[(session.state.layout.direction.ordinal + 1) % values.size]
        session.setLayout(session.state.layout.withDirection(next))
    }

    fun setMemorizeRatio(ratio: Float) {
        val session = memorizeSession ?: return
        session.setLayout(session.state.layout.withRatio(ratio))
    }

    /** Adds [delta] to the current split ratio (used by the divider drag). */
    fun nudgeMemorizeRatio(delta: Float) {
        val session = memorizeSession ?: return
        val layout = session.state.layout
        session.setLayout(layout.withRatio(layout.notePaneRatio + delta))
    }

    fun clearScratch() {
        val page = controller.currentPageData()
        val view = scratchView ?: return
        view.loadScratch(page?.widthMm ?: 210f, page?.heightMm ?: 297f)
        view.setTool(_tool.value.takeUnless { it == EditorTool.EYEDROPPER || it == EditorTool.SNIP } ?: EditorTool.PEN)
        view.setPen(_penColorArgb.value.toInt(), _penSizeMm.value)
    }

    fun importPdf(context: Context, uri: Uri) {
        val id = _noteId.value ?: return
        if (_loading.value || pdfImportJob?.isActive == true) return
        val generation = noteGeneration
        val appContext = context.applicationContext
        pdfImportJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            val importedPages = try {
                withContext(Dispatchers.IO) {
                    val pages = PdfImporter.import(appContext, uri, noteRepo, id)
                    currentCoroutineContext().ensureActive()
                    val name = pages.first().background.backgroundImageName
                        ?: throw PdfImportException("PDF: 1ページ目の画像がありません")
                    try {
                        val bytes = noteRepo.readAsset(id, name)
                            ?: throw PdfImportException("PDF: 保存した1ページ目の画像を読み込めません。保存先を確認してください")
                        currentCoroutineContext().ensureActive()
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: throw PdfImportException("PDF: 1ページ目のプレビュー画像を展開できません")
                        try {
                            currentCoroutineContext().ensureActive()
                        } catch (e: CancellationException) {
                            bitmap.recycle()
                            throw e
                        }
                        assetCache.put("$id:$name", bitmap)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: PdfImportException) {
                        throw e
                    } catch (e: Exception) {
                        throw PdfImportException("PDF: 1ページ目のプレビューを読み込めません。保存先を確認してください", e)
                    }
                    pages
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_noteId.value == id && generation == noteGeneration) {
                    val message = (e as? PdfImportException)?.message ?: "PDFの読み込みに失敗しました。ファイルと保存先を確認してください"
                    Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                }
                return@launch
            } catch (_: OutOfMemoryError) {
                if (_noteId.value == id && generation == noteGeneration) {
                    Toast.makeText(appContext, "PDF: メモリ不足です。他のノートを閉じるか、PDFを分割して再試行してください", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            if (_noteId.value != id || generation != noteGeneration || _loading.value) return@launch
            val current = controller.buildSnapshot()?.takeIf { it.note.id == id } ?: return@launch
            val oldCount = current.note.pages.size
            val updated = current.note.copy(
                pages = current.note.pages + importedPages,
                lastPageIndex = oldCount,
            )
            val loaded = current.loadedPages?.toMutableSet()
                ?: current.note.pages.indices.toMutableSet()
            importedPages.indices.forEach { loaded.add(oldCount + it) }
            reload(
                EditorSnapshot(
                    updated,
                    current.strokes + List(importedPages.size) { emptyList() },
                    loaded,
                ),
            )
            flushSave()
        }
    }

    fun importImage(context: Context, uri: Uri, fitWidthMm: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = _noteId.value ?: return@launch
            val resolver = context.contentResolver
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
            val bmp = decodeSampledBitmap(bytes, 2400)
            if (bmp == null) return@launch
            val assetName = noteRepo.importAsset(id, bytes.inputStream(), "image/png")
            assetCache.put("$id:$assetName", bmp)
            val wMm = fitWidthMm
            val hMm = wMm * bmp.height / bmp.width
            insertImage(assetName, wMm, hMm)
        }
    }

    /** Exports the whole note as a PDF to [uri] chosen via the system file picker. */
    fun exportPdf(uri: Uri) {
        export(uri) { snap, loader ->
            NoteExporter.exportPdf(getApplication<StylusMemoApp>().contentResolver, uri, snap.note, snap.strokes, loader)
        }
    }

    /** Exports the current page as a JPEG to [uri] chosen via the system file picker. */
    fun exportJpeg(uri: Uri) {
        export(uri) { snap, loader ->
            NoteExporter.exportJpeg(
                getApplication<StylusMemoApp>().contentResolver,
                uri,
                snap.note,
                controller.currentPageIndex(),
                snap.strokes,
                loader,
            )
        }
    }

    /**
     * Runs a note-exporter [pluginId] discovered through [PluginRegistry] and writes its
     * output into the app's exports folder.
     */
    fun exportPlugin(pluginId: String) {
        viewModelScope.launch {
            val app = getApplication<StylusMemoApp>()
            val plugin = app.pluginRegistry.noteExporter(pluginId)
            if (plugin == null) {
                Toast.makeText(app, "プラグインが見つかりません", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val snap = withContext(Dispatchers.IO) {
                controller.buildSnapshot()?.let { ensureAllLoaded(it) }
            } ?: return@launch
            val note = snap.note
            val exportsDir = File(app.getExternalFilesDir(null), "exports").apply { mkdirs() }
            val settings = settingsRepo.settings.first()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    plugin.export(
                        ExportContext(
                            note = note,
                            config = mapOf(
                                AiMarkdownExporterPlugin.CONFIG_API_KEY to settings.aiMarkdownApiKey,
                                AiMarkdownExporterPlugin.CONFIG_MODEL to settings.aiMarkdownModel,
                            ),
                            rasterizer = PageRasterizer { pageIndex, maxDim ->
                                val page = note.pages.getOrNull(pageIndex) ?: return@PageRasterizer null
                                val strokes = snap.strokes.getOrNull(pageIndex).orEmpty()
                                NoteExporter.renderPagePng(page, strokes, { name -> loadExportAsset(name) }, maxDim)
                            },
                        ),
                        DataSink { fileName, bytes ->
                            runCatching {
                                val out = File(exportsDir, fileName)
                                out.parentFile?.mkdirs()
                                out.writeBytes(bytes)
                            }.isSuccess
                        },
                    )
                }.getOrElse { e ->
                    e.printStackTrace()
                    false
                }
            }
            val msg = if (result) "書き出しました: ${exportsDir.absolutePath}" else "書き出しに失敗しました"
            Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun export(uri: Uri, block: suspend (EditorSnapshot, suspend (String) -> Bitmap?) -> Boolean) {
        viewModelScope.launch {
            val snap = withContext(Dispatchers.IO) {
                controller.buildSnapshot()?.let { ensureAllLoaded(it) }
            } ?: return@launch
            val ok = withContext(Dispatchers.IO) {
                try {
                    block(snap) { name -> loadExportAsset(name) }
                } catch (t: Throwable) {
                    t.printStackTrace()
                    false
                }
            }
            Toast.makeText(getApplication(), if (ok) "書き出しました" else "書き出しに失敗しました", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun loadExportAsset(name: String): Bitmap? {
        val id = _noteId.value ?: return null
        assetCache.get("$id:$name")?.let { return it }
        val bytes = noteRepo.readAsset(id, name) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { assetCache.put("$id:$name", it) }
    }

    /** Fills in any page strokes that were not loaded eagerly, for full-note export. */
    private suspend fun ensureAllLoaded(snap: EditorSnapshot): EditorSnapshot {
        val loaded = snap.loadedPages ?: return snap
        val strokes = snap.strokes.toMutableList()
        while (strokes.size < snap.note.pages.size) strokes.add(emptyList())
        val missing = snap.note.pages.indices.filter { it !in loaded }
        if (missing.isNotEmpty()) {
            val id = snap.note.id
            coroutineScope {
                missing.map { i -> async(Dispatchers.IO) { i to noteRepo.loadStrokes(id, i) } }
                    .awaitAll()
                    .forEach { (i, pageStrokes) -> strokes[i] = pageStrokes }
            }
        }
        return EditorSnapshot(snap.note, strokes, null)
    }

    private fun reload(snapshot: EditorSnapshot) {
        _note.value = snapshot.note
        pendingSnapshot = snapshot
        applyPending()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            persist()
        }
    }

    private fun persist() {
        if (_loading.value) return
        val id = _noteId.value ?: return
        val snap = controller.buildSnapshot()?.takeIf { it.note.id == id } ?: return
        val previous = lastWrite
        lastWrite = storageScope.launch {
            previous?.join()
            try {
                val unloaded = snap.loadedPages?.let { loaded ->
                    snap.note.pages.indices.filterTo(mutableSetOf()) { it !in loaded }
                } ?: emptySet()
                noteRepo.saveNote(snap.note, snap.strokes, unloaded)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (_noteId.value == id) _snipError.value = "保存に失敗しました。再試行してください"
            }
        }
    }

    fun flushSave() {
        saveJob?.cancel()
        persist()
    }

    override fun onCleared() {
        flushSave()
        val write = lastWrite
        storageScope.launch {
            write?.join()
            storageScope.cancel()
        }
        super.onCleared()
    }

    companion object {
        private const val AUTOSAVE_DEBOUNCE_MS = 1200L
        private const val PEN_SAVE_DEBOUNCE_MS = 600L

        private fun decodeSampledBitmap(bytes: ByteArray, maxDim: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while ((maxOf(bounds.outWidth, bounds.outHeight).toLong() + sample - 1) / sample > maxDim) sample *= 2
            return BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }
    }
}

internal data class PdfRasterSize(val width: Int, val height: Int)

internal fun pdfRasterSize(widthPoints: Int, heightPoints: Int): PdfRasterSize {
    require(widthPoints > 0 && heightPoints > 0) { "PDF page dimensions must be positive" }
    val width = widthPoints.toDouble()
    val height = heightPoints.toDouble()
    val scale = minOf(
        300.0 / 72.0,
        4096.0 / maxOf(width, height),
        kotlin.math.sqrt(10_000_000.0 / (width * height)),
    )
    return PdfRasterSize(
        (width * scale).toInt().coerceIn(1, 4096),
        (height * scale).toInt().coerceIn(1, 4096),
    )
}

private class PdfImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

private object PdfImporter {
    private const val MM_PER_INCH = 25.4f

    suspend fun import(context: Context, uri: Uri, noteRepo: NoteRepository, noteId: String): List<PageData> {
        var failureMessage = "PDF: ファイルを開けません。読み取り権限とファイルの場所を確認してください"
        try {
            currentCoroutineContext().ensureActive()
            val fd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw PdfImportException(failureMessage)
            return fd.use {
                failureMessage = "PDF: 文書を解析できません。破損・暗号化されていないPDFを端末に保存して再選択してください"
                PdfRenderer(fd).use { renderer ->
                    if (renderer.pageCount == 0) throw PdfImportException("PDF: 読み込めるページがありません")
                    val pages = mutableListOf<PageData>()
                    for (i in 0 until renderer.pageCount) {
                        currentCoroutineContext().ensureActive()
                        failureMessage = "PDF: ${i + 1}ページ目を開けません。PDFファイルを確認してください"
                        renderer.openPage(i).use { page ->
                            failureMessage = "PDF: ${i + 1}ページ目のサイズが不正です"
                            val size = pdfRasterSize(page.width, page.height)
                            failureMessage = "PDF: ${i + 1}ページ目の画像を作成できません"
                            val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                            try {
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                val factor = minOf(size.width / page.width.toFloat(), size.height / page.height.toFloat())
                                val matrix = android.graphics.Matrix().apply { setScale(factor, factor) }
                                currentCoroutineContext().ensureActive()
                                failureMessage = "PDF: ${i + 1}ページ目を描画できません。PDFファイルを確認してください"
                                page.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                currentCoroutineContext().ensureActive()
                                failureMessage = "PDF: ${i + 1}ページ目を画像に変換できません"
                                val bytes = java.io.ByteArrayOutputStream().use { output ->
                                    if (!bmp.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                                        throw PdfImportException(failureMessage)
                                    }
                                    output.toByteArray()
                                }
                                currentCoroutineContext().ensureActive()
                                failureMessage = "PDF: ${i + 1}ページ目を保存できません。空き容量と保存先の権限を確認してください"
                                val name = noteRepo.importAsset(noteId, bytes.inputStream(), "image/jpeg")
                                pages.add(
                                    PageData(
                                        widthMm = page.width.toFloat() / 72f * MM_PER_INCH,
                                        heightMm = page.height.toFloat() / 72f * MM_PER_INCH,
                                        background = BackgroundSpec(backgroundImageName = name),
                                    ),
                                )
                            } finally {
                                bmp.recycle()
                            }
                            failureMessage = "PDF: ${i + 1}ページ目の読み込み終了処理に失敗しました"
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    failureMessage = "PDF: 文書の読み込み終了処理に失敗しました"
                    pages
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PdfImportException) {
            throw e
        } catch (e: Exception) {
            throw PdfImportException(failureMessage, e)
        }
    }
}
