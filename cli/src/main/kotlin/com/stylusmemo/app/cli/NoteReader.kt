package com.stylusmemo.app.cli

import com.stylusmemo.app.model.Note
import com.stylusmemo.app.model.PageData
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Reads notes stored by the app. Layout (see the app's NoteRepository):
 *
 * ```
 * <notes-dir>/<noteId>/
 *   note.json            - serialized Note (kotlinx JSON)
 *   page-<n>.bin         - strokes for page n (StrokeCodec)
 *   assets/<assetName>   - imported images / rendered PDF pages
 * ```
 *
 * The path passed on the command line may be the directory that directly contains the note
 * folders, or a directory containing a `notes/` sub-folder with them (both occur in practice).
 */
object NoteReader {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Finds the directory that holds note sub-folders, or null if [root] contains no notes. */
    fun locateNotesDir(root: File): File? {
        fun containsNotes(dir: File): Boolean = dir.isDirectory &&
            (dir.listFiles { f -> f.isDirectory && resolveNoteJson(f) != null }?.isNotEmpty() == true)
        if (containsNotes(root)) return root
        val sub = File(root, "notes")
        if (containsNotes(sub)) return sub
        return null
    }

    fun listNotes(notesDir: File): List<Note> {
        val dirs = notesDir.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { d ->
            val f = resolveNoteJson(d) ?: return@mapNotNull null
            runCatching { json.decodeFromString<Note>(f.readText()) }.getOrNull()
        }.sortedByDescending { it.updatedAt }
    }

    fun loadNote(notesDir: File, noteId: String): Note? {
        val dir = File(notesDir, noteId)
        val f = resolveNoteJson(dir) ?: return null
        return runCatching { json.decodeFromString<Note>(f.readText()) }.getOrNull()
    }

    /** Lists the on-disk page indices (0-based) that have a `page-<n>.bin` file. */
    fun pageIndices(notesDir: File, noteId: String): List<Int> {
        val dir = File(notesDir, noteId)
        val files = dir.listFiles() ?: return emptyList()
        return files.mapNotNull { f ->
            val m = Regex("^page-(\\d+)\\.bin(\\.bin)?$").find(f.name)
            m?.groupValues?.get(1)?.toIntOrNull()
        }.sorted()
    }

    fun loadPageStrokes(notesDir: File, noteId: String, pageIndex: Int): List<DecodedStroke> {
        val dir = File(notesDir, noteId)
        val file = dir.listFiles()?.firstOrNull { f ->
            Regex("^page-$pageIndex\\.bin(\\.bin)?$").matches(f.name)
        } ?: return emptyList()
        return runCatching { StrokeCodec.decodePage(file) }.getOrElse { emptyList() }
    }

    fun loadPages(notesDir: File, note: Note): List<Pair<PageData, List<DecodedStroke>>> {
        val available = pageIndices(notesDir, note.id).toSet()
        return note.pages.mapIndexed { i, page ->
            page to if (i in available) loadPageStrokes(notesDir, note.id, i) else emptyList()
        }
    }

    /** Resolves the asset file inside `<noteId>/assets/`. */
    fun assetFile(notesDir: File, noteId: String, assetName: String): File? {
        val dir = File(File(notesDir, noteId), "assets")
        return File(dir, assetName).takeIf { it.isFile }
    }

    private fun resolveNoteJson(dir: File): File? {
        listOf("note.json", "note.json.bin").forEach { name ->
            val f = File(dir, name)
            if (f.isFile) return f
        }
        return null
    }
}
