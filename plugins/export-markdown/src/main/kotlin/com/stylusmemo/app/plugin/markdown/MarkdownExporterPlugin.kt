package com.stylusmemo.app.plugin.markdown

import com.stylusmemo.app.model.PageData
import com.stylusmemo.app.plugin.DataSink
import com.stylusmemo.app.plugin.ExportContext
import com.stylusmemo.app.plugin.NoteExporterPlugin

/**
 * Example exporter plugin that proves the plugin SPI end-to-end.
 *
 * Writes a Markdown file with the note's metadata and its text boxes. Text in
 * text boxes is read directly (it is real text), so no recognition is needed.
 * Handwritten strokes are intentionally not transcribed here — an AI-based
 * transcription plugin (e.g. "AI Markdown 書き出し") can be built on the same
 * contract by rasterizing pages via [ExportContext.rasterizer].
 */
class MarkdownExporterPlugin : NoteExporterPlugin {

    override val id = "export-markdown"
    override val displayName = "Markdown 書き出し"
    override val description =
        "ノートのタイトルとテキストボックスを Markdown (.md) で書き出します。"
    override val fileExtensions = listOf("md")

    override suspend fun export(context: ExportContext, sink: DataSink): Boolean {
        val note = context.note
        val md = buildString {
            appendLine("# ${note.title.ifBlank { "(無題)" }}")
            appendLine()
            appendLine("> id: `${note.id}` ・ 更新: ${note.updatedAt}")
            appendLine()
            note.pages.forEachIndexed { index, page ->
                appendLine("## ${index + 1}ページ目")
                appendLine()
                val texts = page.textBoxes.map { it.text }.filter { it.isNotBlank() }
                if (texts.isEmpty()) {
                    appendLine("(テキストボックスはありません)")
                } else {
                    texts.forEach { appendLine(it) }
                    appendLine()
                }
                appendLine()
            }
            appendLine("---")
            appendLine("(手書きの書き込みは、AI トランスクライブ拡張機能で取り込めます)")
        }
        val fileName = sanitizeFileName(note.title.ifBlank { "note" }) + ".md"
        return sink.write(fileName, md.toByteArray(Charsets.UTF_8))
    }

    private fun sanitizeFileName(name: String): String =
        name.map { if (it in "\\/:*?\"<>|" || it.code < 32) '_' else it }
            .joinToString("")
            .trim()
            .ifBlank { "note" }
            .take(60)
}