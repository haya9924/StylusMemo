package com.stylusmemo.app.plugin.aimarkdown

import com.stylusmemo.app.model.Note
import com.stylusmemo.app.model.PageData
import com.stylusmemo.app.plugin.DataSink
import com.stylusmemo.app.plugin.ExportContext
import com.stylusmemo.app.plugin.PageRasterizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiMarkdownExporterPluginTest {

    private class RecordingClient : TextVisionClient {
        var prompt: String? = null
        var image: ByteArray? = null
        var apiKey: String? = null
        var model: String? = null
        override suspend fun completeTextAndImage(prompt: String, imagePng: ByteArray): String {
            this.prompt = prompt
            this.image = imagePng
            return "# 文字起こし結果"
        }
    }

    private fun pluginWith(
        client: RecordingClient,
    ): AiMarkdownExporterPlugin = AiMarkdownExporterPlugin().withClientFactory { key, model ->
        client.apiKey = key
        client.model = model
        client
    }

    @Test
    fun `transcribes each page and assembles markdown`() {
        val client = RecordingClient()
        val note = Note(
            id = "n-ai",
            title = "会議メモ",
            pages = listOf(PageData(), PageData(), PageData()),
        )
        var written: ByteArray? = null
        val readIndexes = mutableListOf<Int>()

        val ok = kotlinx.coroutines.runBlocking {
            pluginWith(client).export(
                ExportContext(
                    note = note,
                    config = mapOf(AiMarkdownExporterPlugin.CONFIG_API_KEY to "test-key"),
                    rasterizer = PageRasterizer { pageIndex, _ ->
                        readIndexes.add(pageIndex)
                        "page-$pageIndex".toByteArray()
                    },
                ),
                DataSink { _, bytes -> written = bytes; true },
            )
        }

        assertTrue(ok)
        val text = written!!.decodeToString()
        assertTrue(text.startsWith("# 会議メモ"))
        assertTrue(text.contains("## 1ページ目"))
        assertTrue(text.contains("## 3ページ目"))
        assertEquals(listOf(0, 1, 2), readIndexes)
        assertTrue(text.contains("# 文字起こし結果"))
        // クライアントへは既定プロンプトと画像が渡る
        assertEquals(AiMarkdownExporterPlugin.PROMPT, client.prompt)
        assertTrue(client.image != null)
        // 既定モデルと API キー
        assertEquals(AiMarkdownExporterPlugin.DEFAULT_MODEL, client.model)
        assertEquals("test-key", client.apiKey)
    }

    @Test
    fun `config model and key are forwarded to the client`() {
        val client = RecordingClient()
        kotlinx.coroutines.runBlocking {
            pluginWith(client).export(
                ExportContext(
                    note = Note(id = "n", title = "t"),
                    config = mapOf(
                        AiMarkdownExporterPlugin.CONFIG_API_KEY to "custom-key",
                        AiMarkdownExporterPlugin.CONFIG_MODEL to "gemini-x",
                    ),
                    rasterizer = PageRasterizer { _, _ -> byteArrayOf(1) },
                ),
                DataSink { _, _ -> true },
            )
        }
        assertEquals("custom-key", client.apiKey)
        assertEquals("gemini-x", client.model)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `throws when api key is missing`() {
        kotlinx.coroutines.runBlocking {
            pluginWith(RecordingClient()).export(
                ExportContext(note = Note(id = "n", title = "t")),
                DataSink { _, _ -> true },
            )
        }
    }
}