package com.stylusmemo.app.plugin.aimarkdown

import com.stylusmemo.app.plugin.DataSink
import com.stylusmemo.app.plugin.ExportContext
import com.stylusmemo.app.plugin.NoteExporterPlugin

/**
 * AI-based Markdown exporter.
 *
 * Rasterizes each page through [ExportContext.rasterizer], sends the image to a
 * vision model (Google Gemini by default) and assembles the transcriptions into
 * a single Markdown file.
 *
 * Configuration is passed by the host through [ExportContext.config]:
 * - `export-ai-markdown.api_key` (required) — Gemini API key
 * - `export-ai-markdown.model`     (optional, default gemini-2.0-flash)
 */
class AiMarkdownExporterPlugin : NoteExporterPlugin {

    @Volatile
    private var clientFactory: ((apiKey: String, model: String) -> TextVisionClient)? = null

    /** Test hook: overrides how the vision client is created. */
    fun withClientFactory(factory: (apiKey: String, model: String) -> TextVisionClient): AiMarkdownExporterPlugin {
        clientFactory = factory
        return this
    }

    override val id = "export-ai-markdown"
    override val displayName = "AI で Markdown 書き出し（Gemini）"
    override val description =
        "ノートの各ページを Gemini に送り、手書きを文字起こしして Markdown (.md) で書き出します。API キーが必要です。手書き内容が外部送信されます。"
    override val fileExtensions = listOf("md")

    override suspend fun export(context: ExportContext, sink: DataSink): Boolean {
        val apiKey = context.config[CONFIG_API_KEY]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("AI書き出しのAPIキーが未設定です（設定画面で入力してください）")
        val model = context.config[CONFIG_MODEL]?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
        val rasterizer = context.rasterizer
            ?: throw IllegalStateException("AI書き出しにはページ画像の生成が必要です")
        val client = clientFactory?.invoke(apiKey, model) ?: GeminiClient(apiKey, model)

        val note = context.note
        val md = buildString {
            appendLine("# ${note.title.ifBlank { "(無題)" }}")
            appendLine()
            appendLine("> id: `${note.id}` ・ 更新: ${note.updatedAt}")
            appendLine()
            note.pages.indices.forEach { index ->
                val png = rasterizer.rasterize(index, MAX_DIM_PX)
                appendLine("## ${index + 1}ページ目")
                appendLine()
                if (png == null) {
                    appendLine("（ページ画像を生成できなかったため文字起こしを省略）")
                } else {
                    val transcribed = client.completeTextAndImage(PROMPT, png)
                    appendLine(transcribed.ifBlank { "（文字起こし結果は空でした）" })
                }
                appendLine()
            }
            appendLine("---")
            appendLine("（このファイルは AI プラグイン `export-ai-markdown` で生成されました）")
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

    companion object {
        const val CONFIG_API_KEY = "export-ai-markdown.api_key"
        const val CONFIG_MODEL = "export-ai-markdown.model"
        const val DEFAULT_MODEL = "gemini-2.0-flash"
        const val MAX_DIM_PX = 1600
        const val PROMPT = "以下は手書きノートの1ページの画像です。画像内の内容（手書き文字・図・テキストボックス）を正確にMarkdown形式で書き出してください。説明や注釈は付けず、冗長なコードフェンスも使わずに Markdown 本文のみを出力してください。"
    }
}