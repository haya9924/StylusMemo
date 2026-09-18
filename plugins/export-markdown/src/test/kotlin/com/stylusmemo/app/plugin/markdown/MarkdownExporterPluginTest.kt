package com.stylusmemo.app.plugin.markdown

import com.stylusmemo.app.plugin.DataSink
import com.stylusmemo.app.plugin.ExportContext
import com.stylusmemo.app.model.Note
import com.stylusmemo.app.model.PageData
import com.stylusmemo.app.model.TextBox
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownExporterPluginTest {

    @Test
    fun `writes title and text boxes as markdown`() {
        val note = Note(
            id = "n-abc",
            title = "テスト",
            pages = listOf(
                PageData(textBoxes = listOf(TextBox(id = "t1", text = "こんにちは"))),
            ),
        )
        var out: ByteArray? = null
        val ok = kotlinx.coroutines.runBlocking {
            MarkdownExporterPlugin().export(
                ExportContext(note = note),
                DataSink { _, bytes -> out = bytes; true },
            )
        }
        assertTrue(ok)
        val text = out!!.decodeToString()
        assertTrue(text.startsWith("# テスト"))
        assertTrue(text.contains("こんにちは"))
        assertTrue(text.contains("`n-abc`"))
    }

    @Test
    fun `produces a valid markdown file name`() {
        val note = Note(id = "n-1", title = "A/B:C")
        var name: String? = null
        kotlinx.coroutines.runBlocking {
            MarkdownExporterPlugin().export(
                ExportContext(note = note),
                DataSink { n, _ -> name = n; true },
            )
        }
        assertTrue(name!!.endsWith(".md"))
        assertTrue(name!!.none { it in "\\/:*?\"<>|" })
    }
}