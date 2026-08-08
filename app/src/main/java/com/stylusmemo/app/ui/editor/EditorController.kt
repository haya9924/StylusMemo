package com.stylusmemo.app.ui.editor

import android.graphics.Bitmap
import androidx.ink.strokes.Stroke
import com.stylusmemo.app.model.BackgroundSpec
import com.stylusmemo.app.model.Note
import com.stylusmemo.app.model.PageData
import com.stylusmemo.app.model.PageLayoutMode
import com.stylusmemo.app.model.ShortcutAction
import com.stylusmemo.app.model.StylusButtonPattern

enum class EditorTool { PEN, ERASER, SELECT, STRAIGHT_LINE, SCRIBBLE_ERASE, LASSO }

data class EditorSnapshot(val note: Note, val strokes: List<List<Stroke>>)

/** Bridge between the Compose UI layer and [EditorView]. */
class EditorController {
    var onDocChanged: (() -> Unit)? = null
    var onPageChanged: (() -> Unit)? = null
    var onUndoRedoChanged: ((canUndo: Boolean, canRedo: Boolean) -> Unit)? = null
    var onToolChanged: ((EditorTool) -> Unit)? = null
    var onBoxSelected: ((kind: String, id: String) -> Unit)? = null
    var loadAsset: ((String) -> Bitmap?)? = null

    private var view: EditorView? = null

    fun bind(v: EditorView) {
        view = v
        v.controller = this
    }

    fun load(snapshot: EditorSnapshot) = view?.load(snapshot)
    fun buildSnapshot(): EditorSnapshot? = view?.buildSnapshot()
    fun undo() = view?.undo()
    fun redo() = view?.redo()
    fun setTool(t: EditorTool) = view?.setTool(t)
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
    fun currentPageIndex(): Int = view?.pageIndex ?: 0
}
