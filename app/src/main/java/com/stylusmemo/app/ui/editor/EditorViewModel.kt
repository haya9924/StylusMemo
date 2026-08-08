package com.stylusmemo.app.ui.editor

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stylusmemo.app.StylusMemoApp
import com.stylusmemo.app.data.NoteRepository
import com.stylusmemo.app.data.SettingsRepository
import com.stylusmemo.app.model.BackgroundSpec
import com.stylusmemo.app.model.Note
import com.stylusmemo.app.model.PageData
import com.stylusmemo.app.model.PageLayoutMode
import com.stylusmemo.app.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

    private val _fingerDraw = MutableStateFlow(false)
    val fingerDraw: StateFlow<Boolean> = _fingerDraw

    private val _currentPageData = MutableStateFlow<PageData?>(null)
    val currentPageData: StateFlow<PageData?> = _currentPageData

    private val _selectedBox = MutableStateFlow<Pair<String, String>?>(null)
    val selectedBox: StateFlow<Pair<String, String>?> = _selectedBox

    private val _pageLayoutMode = MutableStateFlow(PageLayoutMode.SINGLE)
    val pageLayoutMode: StateFlow<PageLayoutMode> = _pageLayoutMode

    private var saveJob: Job? = null
    private var pendingSnapshot: EditorSnapshot? = null
    private var viewBound = false
    private var lastSettings: AppSettings? = null
    private val assetCache = mutableMapOf<String, Bitmap>()
    private val controller = EditorController()

    init {
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                lastSettings = s
                _fingerDraw.value = s.fingerDrawEnabled
                _penColorArgb.value = s.defaultPenColorArgb
                _penSizeMm.value = s.defaultPenSizeMm
                _pageLayoutMode.value = s.defaultPageLayoutMode
                applySettings(s)
            }
        }
        controller.onToolChanged = { _tool.value = it }
        controller.onUndoRedoChanged = { u, r -> _canUndo.value = u; _canRedo.value = r }
        controller.onPageChanged = { _currentPageData.value = controller.currentPageData() }
        controller.onBoxSelected = { kind, id -> _selectedBox.value = kind to id }
        controller.onDocChanged = { scheduleSave() }
        controller.loadAsset = { name -> loadAssetBitmap(name) }
    }

    private fun applySettings(s: AppSettings) {
        controller.setFingerDrawEnabled(s.fingerDrawEnabled)
        controller.setShortcutActions(s.stylusPrimaryAction, s.stylusSecondaryAction)
        controller.setLearnedStylusPatterns(s.stylusPrimaryPattern, s.stylusSecondaryPattern)
        controller.setLayoutMode(s.defaultPageLayoutMode)
        controller.setPen(s.defaultPenColorArgb.toInt(), s.defaultPenSizeMm)
    }

    fun setPageLayoutMode(mode: PageLayoutMode) {
        _pageLayoutMode.value = mode
        controller.setLayoutMode(mode)
    }

    fun openNote(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _noteId.value = id
            val note = noteRepo.loadNote(id)
            val strokes = mutableListOf<List<androidx.ink.strokes.Stroke>>()
            for (i in note.pages.indices) strokes.add(noteRepo.loadStrokes(id, i))
            preloadAssets(note)
            pendingSnapshot = EditorSnapshot(note, strokes)
            _note.value = note
            applyPending()
        }
    }

    fun bindView(view: EditorView) {
        viewBound = true
        controller.bind(view)
        lastSettings?.let { applySettings(it) }
        applyPending()
    }

    private fun applyPending() {
        val snap = pendingSnapshot ?: return
        if (!viewBound) return
        pendingSnapshot = null
        controller.load(snap)
        _currentPageData.value = controller.currentPageData()
    }

    private suspend fun preloadAssets(note: Note) {
        val id = _noteId.value ?: return
        val names = note.pages.flatMap { p ->
            listOfNotNull(p.background.backgroundImageName) + p.imageBoxes.map { it.assetName }
        }.distinct()
        for (name in names) {
            if (assetCache.containsKey(name)) continue
            val bytes = noteRepo.readAsset(id, name) ?: continue
            assetCache[name] = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    private fun loadAssetBitmap(name: String): Bitmap? = assetCache[name]

    fun undo() = controller.undo()
    fun redo() = controller.redo()

    fun setTool(t: EditorTool) {
        _tool.value = t
        controller.setTool(t)
        if (t != EditorTool.SELECT) _selectedBox.value = null
    }

    fun setPenColor(argb: Long) {
        _penColorArgb.value = argb
        controller.setPen(argb.toInt(), _penSizeMm.value)
    }

    fun setPenSize(mm: Float) {
        _penSizeMm.value = mm
        controller.setPen(_penColorArgb.value.toInt(), mm)
    }

    fun setFingerDraw(enabled: Boolean) {
        _fingerDraw.value = enabled
        controller.setFingerDrawEnabled(enabled)
    }

    fun addPage() {
        controller.addPage()
        _currentPageData.value = controller.currentPageData()
    }

    fun deletePage() {
        controller.deletePage()
        _currentPageData.value = controller.currentPageData()
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

    fun pageCount(): Int = _note.value?.pages?.size ?: 0
    fun currentPageIndex(): Int = controller.currentPageIndex()

    fun switchPage(i: Int) {
        controller.switchPage(i)
        _currentPageData.value = controller.currentPageData()
    }

    fun importPdf(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = _noteId.value ?: return@launch
            val current = controller.buildSnapshot() ?: return@launch
            val importedPages = PdfImporter.import(context, uri, noteRepo, id)
            val updated = current.note.copy(pages = current.note.pages + importedPages)
            preloadAssets(updated)
            reload(EditorSnapshot(updated, current.strokes + List(importedPages.size) { emptyList() }))
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
            assetCache[assetName] = bmp
            val wMm = fitWidthMm
            val hMm = wMm * bmp.height / bmp.width
            insertImage(assetName, wMm, hMm)
        }
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
        val id = _noteId.value ?: return
        val snap = controller.buildSnapshot() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            noteRepo.saveNote(snap.note, snap.strokes)
        }
    }

    override fun onCleared() {
        super.onCleared()
        persist()
    }

    companion object {
        private const val AUTOSAVE_DEBOUNCE_MS = 1200L

        private fun decodeSampledBitmap(bytes: ByteArray, maxDim: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) > maxDim) sample *= 2
            return BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }
    }
}

/** Renders PDF pages to PNG assets via [PdfRenderer]. */
private object PdfImporter {
    private const val MM_PER_INCH = 25.4f
    private const val RENDER_DPI = 150f

    suspend fun import(context: Context, uri: Uri, noteRepo: NoteRepository, noteId: String): List<PageData> {
        val resolver = context.contentResolver
        val fd = resolver.openFileDescriptor(uri, "r") ?: return emptyList()
        val pages = mutableListOf<PageData>()
        try {
            PdfRenderer(fd).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val wMm = page.width.toFloat() / 72f * MM_PER_INCH
                        val hMm = page.height.toFloat() / 72f * MM_PER_INCH
                        val wPx = (wMm / MM_PER_INCH * RENDER_DPI).toInt().coerceAtLeast(32)
                        val hPx = (hMm / MM_PER_INCH * RENDER_DPI).toInt().coerceAtLeast(32)
                        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
                        page.render(bmp, null, android.graphics.Matrix(), PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val stream = java.io.ByteArrayOutputStream().apply {
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, this)
                        }
                        val name = noteRepo.importAsset(noteId, stream.toByteArray().inputStream(), "image/png")
                        pages.add(
                            PageData(
                                widthMm = wMm,
                                heightMm = hMm,
                                background = BackgroundSpec(backgroundImageName = name),
                            ),
                        )
                    }
                }
            }
        } finally {
            fd.close()
        }
        return pages
    }
}
