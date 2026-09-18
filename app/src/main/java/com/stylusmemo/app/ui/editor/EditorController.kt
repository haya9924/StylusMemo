package com.stylusmemo.app.ui.editor

import android.graphics.Bitmap
import androidx.ink.strokes.Stroke
import com.stylusmemo.app.model.BackgroundSpec
import com.stylusmemo.app.model.Note
import com.stylusmemo.app.model.PageData
import com.stylusmemo.app.model.PageLayoutMode
import com.stylusmemo.app.model.ShortcutAction
import com.stylusmemo.app.model.StylusButtonPattern

enum class EditorTool { PEN, HIGHLIGHT, ERASER, SELECT, STRAIGHT_LINE, SCRIBBLE_ERASE, LASSO, EYEDROPPER, SNIP }

data class EditorSnapshot(
    val note: Note,
    val strokes: List<List<Stroke>>,
    /** Pages whose strokes are loaded; null means every page is loaded. */
    val loadedPages: Set<Int>? = null,
)

/** Bridge between the Compose UI layer and [EditorView]. */
class EditorController {
    var onDocChanged: (() -> Unit)? = null
    var onPageChanged: (() -> Unit)? = null
    /** Fired when the view needs the strokes of a page that was not loaded eagerly. */
    var onPageStrokesNeeded: ((Int) -> Unit)? = null
    var onUndoRedoChanged: ((canUndo: Boolean, canRedo: Boolean) -> Unit)? = null
    var onToolChanged: ((EditorTool) -> Unit)? = null
    var onBoxSelected: ((kind: String, id: String) -> Unit)? = null
    var onSnipCaptured: ((String, Bitmap) -> Unit)? = null
    var onSnipError: ((String) -> Unit)? = null
    var loadAsset: ((String) -> Bitmap?)? = null

    fun updateSnips(noteId: String, transform: (List<com.stylusmemo.app.model.Snip>) -> List<com.stylusmemo.app.model.Snip>): Note? =
        view?.updateSnips(noteId, transform)

    fun cancelSnip() = view?.cancelSnip()
    /** Called when the view needs an asset that is not in memory yet (lazy background/PDF loading). */
    var onAssetNeeded: ((String) -> Unit)? = null

    private var view: EditorView? = null

    fun bind(v: EditorView) {
        view = v
        v.controller = this
    }

    fun load(snapshot: EditorSnapshot) = view?.load(snapshot)
    fun invalidateAsset(name: String) = view?.invalidateAsset(name)
    fun loadStrokesForPage(page: Int, strokes: List<Stroke>) = view?.applyLoadedStrokes(page, strokes)
    fun isPageLoaded(page: Int): Boolean = view?.let { it.isPageLoaded(page) } ?: true
    fun areAllPagesLoaded(): Boolean = view?.let { it.areAllPagesLoaded() } ?: true
    fun buildSnapshot(): EditorSnapshot? = view?.buildSnapshot()
    fun undo() = view?.undo()
    fun redo() = view?.redo()
    fun setTool(t: EditorTool) = view?.setTool(t)
    fun setHighlightSpec(spec: com.stylusmemo.app.plugin.tools.ToolSpec?) = view?.setHighlightSpec(spec)
    fun setPen(colorArgb: Int, sizeMm: Float) = view?.setPen(colorArgb, sizeMm)
    fun setShortcutActions(primary: ShortcutAction, secondary: ShortcutAction) =
        view?.setShortcutActions(primary, secondary)
    fun setLearnedStylusPatterns(primary: StylusButtonPattern?, secondary: StylusButtonPattern?) =
        view?.setLearnedStylusPatterns(primary, secondary)
    fun setFingerDrawEnabled(enabled: Boolean) = view?.setFingerDrawEnabled(enabled)
    fun setLayoutMode(mode: PageLayoutMode) = view?.setLayoutMode(mode)
    fun switchPage(i: Int) = view?.switchPage(i)
    fun addPage() = view?.addPage()
    fun deletePage() = view?.deletePage()
    fun movePage(from: Int, to: Int) = view?.movePage(from, to)
    fun setPageSize(widthMm: Float, heightMm: Float) = view?.setPageSize(widthMm, heightMm)
    fun setBackground(spec: BackgroundSpec) = view?.setBackground(spec)
    fun addTextBox(text: String, fontSizeMm: Float, colorArgb: Int) =
        view?.addTextBox(text, fontSizeMm, colorArgb)
    fun insertImageBox(assetName: String, widthMm: Float, heightMm: Float) =
        view?.insertImageBox(assetName, widthMm, heightMm)
    fun updateSelectedText(boxId: String, text: String, fontSizeMm: Float, colorArgb: Int) =
        view?.updateSelectedText(boxId, text, fontSizeMm, colorArgb)
    fun deleteSelectedBox() = view?.deleteSelectedBox()
    fun canUndo(): Boolean = view?.canUndo() ?: false
    fun canRedo(): Boolean = view?.canRedo() ?: false
    fun currentPageData(): PageData? = view?.currentPageData()
    fun currentNote(): Note? = view?.currentNote()
    fun currentPageIndex(): Int = view?.pageIndex ?: 0
    fun pageCount(): Int = view?.pageCountValue() ?: 0

    fun setMemorizeSession(session: com.stylusmemo.app.plugin.memorize.MemorizeSession?) =
        view?.setMemorizeSession(session)

    fun refreshMemorize(force: Boolean = true) = view?.refreshMemorize(force)

    /** Cheap redraw of the sheet overlay only; does not re-render the hidden-content bitmap. */
    fun invalidateMemorizeOverlay() = view?.invalidateMemorizeOverlay()

    fun setOnSheetMoved(listener: ((dxMm: Float, dyMm: Float) -> Unit)?) {
        view?.onSheetMoved = listener
    }

    fun setOnSheetRectChanged(listener: ((rectMm: com.stylusmemo.app.plugin.memorize.RectDataMm) -> Unit)?) {
        view?.onSheetRectChanged = listener
    }

    fun setOnSheetMoveEnd(listener: (() -> Unit)?) {
        view?.onSheetMoveEnd = listener
    }

    fun setOnEyedropperPick(listener: ((argb: Int) -> Unit)?) {
        view?.onEyedropperPick = listener
    }

    fun sampleColorAt(screenX: Float, screenY: Float): Int? = view?.sampleColorAt(screenX, screenY)
}
