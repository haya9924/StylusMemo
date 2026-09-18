package com.stylusmemo.app

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stylusmemo.app.data.StrokeCodec
import com.stylusmemo.app.model.Note
import com.stylusmemo.app.ui.editor.NoteExporter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders every page of the notes pulled under `files/debug/notes/` through the real
 * export pipeline and writes PNGs to `files/debug/render/` so the output can be inspected.
 *
 * Run adb with the working note data present:
 *   adb push /tmp/opencode/stylus-notes/notes \
 *     /sdcard/Android/data/com.stylusmemo.app/files/debug/notes
 */
@RunWith(AndroidJUnit4::class)
class NoteExporterRenderTest {

    @Test
    fun renderPulledNotes() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val src = File(ctx.getExternalFilesDir("debug"), "notes")
        val dst = File(ctx.getExternalFilesDir("debug"), "render").apply { mkdirs() }
        val json = Json { ignoreUnknownKeys = true }
        val noteDirs = src.listFiles()?.filter { it.isDirectory } ?: emptyList()
        Log.i("RenderTest", "note dirs: ${noteDirs.size}")
        for (dir in noteDirs) {
            val jsonFile = File(dir, "note.json.bin").takeIf { it.isFile }
                ?: File(dir, "note.json").takeIf { it.isFile }
                ?: continue
            val note = runCatching { json.decodeFromString(Note.serializer(), jsonFile.readText()) }
                .getOrNull() ?: continue
            runBlocking {
                note.pages.forEachIndexed { i, page ->
                    val strokeFile = File(dir, "page-$i.bin.bin").takeIf { it.isFile }
                        ?: File(dir, "page-$i.bin").takeIf { it.isFile }
                        ?: return@forEachIndexed
                    val strokes = runCatching { StrokeCodec.fromBytes(strokeFile.readBytes()) }.getOrDefault(emptyList())
                    val png = NoteExporter.renderPagePng(page, strokes, { null }, 800)
                    if (png != null) {
                        File(dst, "${note.id}-p$i.png").writeBytes(png)
                        Log.i("RenderTest", "rendered ${note.id} p$i (${strokes.size} strokes, " +
                            "${page.widthMm}x${page.heightMm}mm)")
                    }
                }
            }
        }
    }
}