package com.stylusmemo.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.ink.strokes.Stroke
import com.stylusmemo.app.model.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists notes to a local folder. The folder is either the app-private directory (default) or a
 * user-selected SAF tree (chosen in settings). Each note lives in its own sub-folder:
 *
 * ```
 * notes/<noteId>/
 *   note.json       - note metadata, page sizes/backgrounds and boxes (kotlinx JSON)
 *   page-<n>.bin    - serialized strokes for page n (see StrokeCodec)
 *   assets/<name>   - imported images / rendered PDF pages
 * ```
 */
class NoteRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var rootUri: String? = null
    private val mutex = Mutex()

    private companion object {
        const val TAG = "NoteRepository"
        const val FILE_MIME = "application/octet-stream"
        const val LEGACY_SUFFIX = ".bin"
    }

    /**
     * Session cache of the latest in-memory note state. SAF (and even the default directory) can
     * lag in reflecting freshly-written files, so reading back immediately after a write may miss
     * the file. This cache guarantees a just-created/just-saved note can always be opened.
     */
    private val noteCache = mutableMapOf<String, Note>()

    /** [uri] of "" or null selects the default app-private location. */
    fun setRootUri(uri: String?) {
        rootUri = uri?.takeIf { it.isNotBlank() }
    }

    fun currentRootLabel(): String =
        rootUri ?: "アプリ専用領域 (デフォルト)"

    private fun rootDir(): DocumentFile {
        val uri = rootUri
        return if (uri != null) {
            DocumentFile.fromTreeUri(context, Uri.parse(uri))
                ?: DocumentFile.fromFile(defaultDir())
        } else {
            DocumentFile.fromFile(defaultDir())
        }
    }

    private fun defaultDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "notes").apply { mkdirs() }
    }

    private fun notesDir(): DocumentFile {
        var dir = rootDir()
        if (rootUri != null && (!dir.canRead() || !dir.isDirectory)) {
            Log.w(TAG, "SAF root $rootUri unreachable; falling back to default dir ${defaultDir()}")
            dir = DocumentFile.fromFile(defaultDir())
        }
        var notes = dir.findFile("notes")
        if (notes == null || !notes.isDirectory) {
            notes = dir.createDirectory("notes")
        }
        return notes ?: dir
    }

    private fun noteDir(noteId: String): DocumentFile? =
        notesDir().findFile(noteId)

    private suspend fun openOutputStream(doc: DocumentFile): java.io.OutputStream =
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(doc.uri) ?: error("cannot open ${doc.uri}")
        }

    private suspend fun openInputStream(doc: DocumentFile): java.io.InputStream =
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(doc.uri) ?: error("cannot open ${doc.uri}")
        }

    suspend fun listNotes(): List<Note> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val dir = notesDir()
            val cached = noteCache.values.toList()
            if (!dir.canRead() || !dir.isDirectory) return@withLock cached
            val listed = dir.listFiles()
                .filter { it.isDirectory && resolveFile(it, "note.json") != null }
                .mapNotNull { folder ->
                    runCatching {
                        json.decodeFromString<Note>(readText(resolveFile(folder, "note.json")!!))
                    }.getOrNull()
                }
            (listed + cached)
                .distinctBy { it.id }
                .sortedByDescending { it.updatedAt }
        }
    }

    suspend fun createNote(
        title: String,
        widthMm: Float,
        heightMm: Float,
        background: com.stylusmemo.app.model.BackgroundSpec,
    ): Note = withContext(Dispatchers.IO) {
        mutex.withLock {
            val note = Note.new(title, widthMm, heightMm, background)
            noteCache[note.id] = note
            val dir = notesDir().createDirectory(note.id) ?: error("create directory failed")
            writeJson(dir, "note.json", note)
            writeBytes(dir, "page-0.bin", StrokeCodec.toBytes(emptyList()))
            note
        }
    }

    suspend fun loadNote(noteId: String): Note = withContext(Dispatchers.IO) {
        mutex.withLock {
            noteCache[noteId]?.let { return@withLock it }
            val dir = noteDir(noteId)
            if (dir != null) {
                val file = resolveFile(dir, "note.json")
                if (file != null) {
                    runCatching {
                        json.decodeFromString<Note>(readText(file))
                    }.getOrNull()?.let {
                        noteCache[noteId] = it
                        return@withLock it
                    }
                }
            }
            // Fallback: never crash the editor for a note we cannot locate.
            val fallback = Note(id = noteId, title = "読み込めないメモ")
            noteCache[noteId] = fallback
            fallback
        }
    }

    suspend fun loadStrokes(noteId: String, pageIndex: Int): List<Stroke> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val dir = noteDir(noteId) ?: return@withLock emptyList()
                val file = resolveFile(dir, "page-$pageIndex.bin")
                if (file == null) return@withLock emptyList()
                runCatching { StrokeCodec.fromBytes(readBytes(file)) }.getOrElse { emptyList() }
            }
        }

    suspend fun saveNote(note: Note, strokesByPage: List<List<Stroke>>) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val dir = noteDir(note.id) ?: return@withContext
                val saved = note.withUpdatedAt()
                noteCache[note.id] = saved
                writeJson(dir, "note.json", saved)
                strokesByPage.forEachIndexed { i, strokes ->
                    writeBytes(dir, "page-$i.bin", StrokeCodec.toBytes(strokes))
                }
            }
        }
    }

    suspend fun renameNote(noteId: String, newTitle: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val dir = noteDir(noteId) ?: return@withContext
                val jsonFile = resolveFile(dir, "note.json") ?: return@withContext
                val note = noteCache[noteId]
                    ?: json.decodeFromString<Note>(readText(jsonFile))
                val renamed = note.copy(title = newTitle).withUpdatedAt()
                noteCache[noteId] = renamed
                writeJson(dir, "note.json", renamed)
            }
        }
    }

    suspend fun deleteNote(noteId: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                noteCache.remove(noteId)
                noteDir(noteId)?.delete()
            }
        }
    }

    /** Copy the given stream into the note's assets folder; returns the stored asset name. */
    suspend fun importAsset(noteId: String, stream: java.io.InputStream, mime: String): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val dir = noteDir(noteId) ?: error("note not found")
                var assets = dir.findFile("assets")
                if (assets == null || !assets.isDirectory) assets = dir.createDirectory("assets")
                val name = "asset-${System.currentTimeMillis()}-${(Math.random() * 1e6).toInt()}"
                val ext = mime.substringAfter('/', "").takeIf { it.length in 2..5 }
                    ?: "bin"
                val fileName = "$name.$ext"
                val file = assets?.createFile(mime, fileName) ?: error("create asset failed")
                stream.use { input ->
                    openOutputStream(file).use { output -> input.copyTo(output) }
                }
                fileName
            }
        }

    suspend fun readAsset(noteId: String, assetName: String): ByteArray? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val dir = noteDir(noteId) ?: return@withLock null
                val assets = dir.findFile("assets") ?: return@withLock null
                val file = assets.findFile(assetName) ?: return@withLock null
                runCatching { readBytes(file) }.getOrNull()
            }
        }

    suspend fun importFile(noteId: String, stream: java.io.InputStream, name: String, mime: String): String =
        importAsset(noteId, stream, mime)

    private suspend inline fun <reified T> writeJson(dir: DocumentFile, fileName: String, value: T) {
        val bytes = json.encodeToString(value).encodeToByteArray()
        writeBytes(dir, fileName, bytes)
    }

    private suspend fun writeBytes(dir: DocumentFile, fileName: String, bytes: ByteArray) {
        val file = resolveFile(dir, fileName) ?: dir.createFile(FILE_MIME, fileName)
        if (file != null) {
            openOutputStream(file).use { it.write(bytes) }
        }
    }

    /**
     * Resolve [fileName] inside [dir], tolerating the ".bin" suffix that older builds appended to
     * every document (documentfile 1.1.0's [DocumentFile.createFile] appends the MIME-type
     * extension to the display name). Checking the suffixed name first keeps existing data
     * readable and lets writes update the already-created files instead of failing to create a
     * duplicate.
     */
    private fun resolveFile(dir: DocumentFile, fileName: String): DocumentFile? {
        dir.findFile("$fileName$LEGACY_SUFFIX")?.let { return it }
        return dir.findFile(fileName)
    }

    private suspend fun readText(file: DocumentFile): String =
        openInputStream(file).use { it.readBytes().decodeToString() }

    private suspend fun readBytes(file: DocumentFile): ByteArray =
        openInputStream(file).use { it.readBytes() }
}
