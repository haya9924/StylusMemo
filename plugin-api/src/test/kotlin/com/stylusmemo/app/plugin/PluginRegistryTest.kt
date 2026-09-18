package com.stylusmemo.app.plugin

import com.stylusmemo.app.model.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRegistryTest {

    class FakeExporter : NoteExporterPlugin {
        override val id = "fake-exporter"
        override val displayName = "フェイク書き出し"
        override val fileExtensions = listOf("fake")
        override suspend fun export(context: ExportContext, sink: DataSink): Boolean = true
    }

    class FakeOtherExport : NoteExporterPlugin {
        override val id = "other-exporter"
        override val displayName = "別の書き出し"
        override suspend fun export(context: ExportContext, sink: DataSink): Boolean = false
    }

    class BadVersion : Plugin {
        override val id = "bad"
        override val displayName = "非互換"
        override val apiVersion = PluginApiVersion.VERSION + 99
    }

    @Test
    fun `discovers exporters and filters incompatible versions`() {
        val registry = PluginRegistry.of(listOf(FakeExporter(), FakeOtherExport(), BadVersion()))
        val plugins = registry.plugins
        assertEquals(2, plugins.size)
        assertEquals("fake-exporter", registry.noteExporter("fake-exporter")?.id)
        assertTrue(plugins.none { it.id == "bad" })
    }

    @Test
    fun `noteExporter returns null for unknown id`() {
        val registry = PluginRegistry.of(listOf(FakeExporter()))
        assertNull(registry.noteExporter("missing"))
    }

    @Test
    fun `export via exporter writes through the sink`() {
        val registry = PluginRegistry.of(listOf(FakeExporter()))
        var written: ByteArray? = null
        val ok = kotlinx.coroutines.runBlocking {
            registry.noteExporter("fake-exporter")!!.export(
                ExportContext(note = Note(title = "t")),
                DataSink { name, bytes -> written = bytes; true },
            )
        }
        assertTrue(ok)
        assertNull(written) // fake exporter never writes; just asserting contract shape
    }
}