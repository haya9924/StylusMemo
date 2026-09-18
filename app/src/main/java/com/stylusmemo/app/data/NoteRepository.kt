package com.stylusmemo.app.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.ink.strokes.Stroke
import com.stylusmemo.app.model.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Persists notes to a local folder. The folder is either the app-private directory (default) or a
 * user-selected SAF tree (chosen in settings).
 *
 * The on-disk layout mirrors what the user sees, so it is browsable in a file manager:
 *
 * ```
 * notes/
 *   index.json                 - noteId -> relative path (kept in sync, rebuildable by scanning)
 *   folders.json               - folder paths (allows empty folders)
 *   <フォルダ>/<サブフォルダ>/<ノート名>/
 *       note.json              - metadata (id, title, folder, pages, boxes)
 *       page-<n>.bin           - serialized strokes for page n (see StrokeCodec)
 *       assets/<name>          - imported images / rendered PDF pages
 * ```
 *
 * Folder and note names are derived from the user-facing values (sanitized for the filesystem).
 */
class NoteRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var rootUri: String? = null
    private val mutex = Mutex()
    private val childDocuments = ChildDocuments()
    private val noteDirectories = object : LinkedHashMap<String, ResolvedDirectory>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedDirectory>): Boolean =
            size > 64
    }

    private data class ResolvedDirectory(val root: Uri, val path: String, val directory: DocumentFile)

    internal class ChildDocuments(
        private val capacity: Int = 64,
        private val listFiles: (DocumentFile) -> Array<DocumentFile> = { it.listFiles() },
    ) {
        private val directories = object : LinkedHashMap<Uri, MutableMap<String, DocumentFile>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, MutableMap<String, DocumentFile>>): Boolean =
                size > capacity
        }

        fun children(dir: DocumentFile): MutableMap<String, DocumentFile> =
            directories.getOrPut(dir.uri) {
                linkedMapOf<String, DocumentFile>().apply {
                    for (child in listFiles(dir)) {
                        child.name?.let { putIfAbsent(it, child) }
                    }
                }
            }

        fun created(parent: DocumentFile, child: DocumentFile): DocumentFile {
            child.name?.let { directories[parent.uri]?.put(it, child) }
            return child
        }

        fun clear() = directories.clear()
    }

    private fun invalidateDirectories() {
        childDocuments.clear()
        noteDirectories.clear()
    }

    private fun createDirectory(parent: DocumentFile, name: String): DocumentFile? =
        parent.createDirectory(name)?.let { childDocuments.created(parent, it) }

    private suspend inline fun <T> withStorageLock(block: () -> T): T = mutex.withLock {
        childDocuments.clear()
        try {
            block()
        } finally {
            childDocuments.clear()
        }
    }

    /** noteId -> relative path (under the notes directory). Best-effort cache of index.json. */
    private val pathIndex = mutableMapOf<String, String>()

    private companion object {
        const val TAG = "NoteRepository"
        const val FILE_MIME = "application/octet-stream"
        const val LEGACY_SUFFIX = ".bin"
        const val INDEX_FILE = "index.json"
        const val FOLDERS_FILE = "folders.json"
        const val MAX_DEPTH = 8
    }

    /** Session cache of the latest in-memory note state, so a just-saved note always reopens. */
    private val noteCache = mutableMapOf<String, Note>()

    /**
     * Session cache of parsed page strokes, keyed by "noteId:pageIndex". Avoids re-reading and
     * re-decoding page-<n>.bin files when a note is reopened. Cleared when the root changes.
     */
    private val strokeCache = mutableMapOf<String, List<Stroke>>()

    private data class PersistedPage(val uri: Uri, val strokes: List<Stroke>)

    private data class PersistedNote(val directoryUri: Uri, val pages: List<PersistedPage?>)

    @Volatile
    private var persistedStrokes = ConcurrentHashMap<String, PersistedNote>()

    private fun strokeKey(noteId: String, pageIndex: Int) = "$noteId:$pageIndex"

    /** [uri] of "" or null selects the default app-private location. */
    fun setRootUri(uri: String?) {
        val next = uri?.takeIf { it.isNotBlank() }
        if (next == rootUri) return
        rootUri = next
        persistedStrokes = ConcurrentHashMap()
        noteCache.clear()
        strokeCache.clear()
        pathIndex.clear()
        invalidateDirectories()
    }

    fun currentRootLabel(): String = rootUri ?: "アプリ専用領域 (デフォルト)"

    // ------------------------------------------------------------------ roots / dirs

    private fun rootDirFor(uri: String?): DocumentFile {
        return if (!uri.isNullOrBlank()) {
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

    private fun notesDir(): DocumentFile = notesDirFor(rootUri)

    private fun accessibleRootFor(uri: String?): DocumentFile {
        val dir = rootDirFor(uri)
        if (!uri.isNullOrBlank() && (!dir.canRead() || !dir.isDirectory)) {
            Log.w(TAG, "SAF root $uri unreachable; falling back to default dir")
            return DocumentFile.fromFile(defaultDir())
        }
        return dir
    }

    private fun notesDirFor(uri: String?): DocumentFile {
        val dir = accessibleRootFor(uri)
        var notes = childDocuments.children(dir)["notes"]
        if (notes == null || !notes.isDirectory) {
            notes = createDirectory(dir, "notes")
        }
        return notes ?: dir
    }

    private fun sanitize(name: String): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim()
            .trim('.')
        return cleaned.ifBlank { "無題" }
    }

    private fun folderSegments(folder: String): List<String> =
        folder.split('/').map { it.trim() }.filter { it.isNotEmpty() }.map { sanitize(it) }

    /** Navigates to (and optionally creates) the directory at [segments] under [base]. */
    private fun navigate(base: DocumentFile, segments: List<String>, create: Boolean): DocumentFile? {
        var cur = base
        for (seg in segments) {
            val existing = childDocuments.children(cur)[seg]
            cur = when {
                existing != null && existing.isDirectory -> existing
                existing != null && !existing.isDirectory -> return null
                create -> createDirectory(cur, seg) ?: return null
                else -> return null
            }
        }
        return cur
    }

    private fun uniqueChildName(parent: DocumentFile, desired: String): String {
        val children = childDocuments.children(parent)
        var name = desired
        var i = 2
        while (children[name] != null) {
            name = "$desired ($i)"
            i++
        }
        return name
    }

    // ------------------------------------------------------------------ index

    private suspend fun rebuildIndex(notes: DocumentFile): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        scanIndex(notes, "", 0, map)
        return map
    }

    private suspend fun scanIndex(
        dir: DocumentFile,
        prefix: String,
        depth: Int,
        out: MutableMap<String, String>,
    ) {
        for (child in childDocuments.children(dir).values.toList()) {
            if (!child.isDirectory) continue
            val name = child.name ?: continue
            val rel = if (prefix.isEmpty()) name else "$prefix/$name"
            val nf = resolveFile(child, "note.json")
            if (nf != null) {
                runCatching { json.decodeFromString<Note>(readText(nf)).id }
                    .getOrNull()?.let { id ->
                        // If the same note id exists more than once (e.g. a stray duplicated
                        // sub-folder), prefer the shallowest path so resolution is deterministic.
                        val existing = out[id]
                        if (existing == null || rel.count { it == '/' } < existing.count { it == '/' }) {
                            out[id] = rel
                        }
                    }
            } else if (depth < MAX_DEPTH) {
                scanIndex(child, rel, depth + 1, out)
            }
        }
    }

    private suspend fun persistIndex(notes: DocumentFile) {
        runCatching { writeJson(notes, INDEX_FILE, pathIndex.toMap()) }
    }

    private suspend fun resolveNoteDir(noteId: String): DocumentFile? {
        val notes = notesDir()
        val cached = noteDirectories.remove(noteId)
        if (cached != null && cached.root == notes.uri && cached.path == pathIndex[noteId] &&
            cached.directory.isDirectory && cached.directory.name == cached.path.substringAfterLast('/') &&
            containsNote(cached.directory, noteId)
        ) {
            noteDirectories[noteId] = cached
            return cached.directory
        }
        pathIndex[noteId]?.let { rel ->
            navigate(notes, rel.split('/'), create = false)?.let { dir ->
                if (containsNote(dir, noteId)) {
                    noteDirectories[noteId] = ResolvedDirectory(notes.uri, rel, dir)
                    return dir
                }
            }
        }
        noteDirectories.clear()
        pathIndex.clear()
        pathIndex.putAll(rebuildIndex(notes))
        val rel = pathIndex[noteId] ?: return null
        return navigate(notes, rel.split('/'), create = false)?.also {
            noteDirectories[noteId] = ResolvedDirectory(notes.uri, rel, it)
        }
    }

    private suspend fun containsNote(dir: DocumentFile, noteId: String): Boolean =
        resolveFile(dir, "note.json")?.let { file ->
            runCatching { json.decodeFromString<Note>(readText(file)).id == noteId }.getOrDefault(false)
        } ?: false

    // ------------------------------------------------------------------ list / create

    suspend fun listNotes(): List<Note> = withContext(Dispatchers.IO) {
        withStorageLock {
            invalidateDirectories()
            strokeCache.clear()
            val notes = notesDir()
            val out = mutableListOf<Note>()
            val index = mutableMapOf<String, String>()
            if (notes.canRead() && notes.isDirectory) {
                scanNotes(notes, "", 0, out, index)
            }
            pathIndex.clear()
            pathIndex.putAll(index)
            (out + noteCache.values.toList())
                .distinctBy { it.id }
                .sortedByDescending { it.updatedAt }
        }
    }

    private suspend fun scanNotes(
        dir: DocumentFile,
        prefix: String,
        depth: Int,
        out: MutableList<Note>,
        index: MutableMap<String, String>,
    ) {
        for (child in childDocuments.children(dir).values.toList()) {
            if (!child.isDirectory) continue
            val name = child.name ?: continue
            val rel = if (prefix.isEmpty()) name else "$prefix/$name"
            val nf = resolveFile(child, "note.json")
            if (nf != null) {
                val decoded = runCatching { json.decodeFromString<Note>(readText(nf)) }
                decoded.getOrNull()?.let {
                    // If the same note id appears more than once (e.g. a stray duplicated
                    // sub-folder), keep the shallowest copy so the list is deterministic.
                    val existingRel = index[it.id]
                    if (existingRel == null || rel.count { c -> c == '/' } < existingRel.count { c -> c == '/' }) {
                        index[it.id] = rel
                        out.removeAll { existing -> existing.id == it.id }
                        out.add(it)
                    }
                }
            } else if (depth < MAX_DEPTH) {
                scanNotes(child, rel, depth + 1, out, index)
            }
        }
    }

    suspend fun createNote(
        title: String,
        widthMm: Float,
        heightMm: Float,
        background: com.stylusmemo.app.model.BackgroundSpec,
        folder: String = "",
    ): Note = withContext(Dispatchers.IO) {
        withStorageLock {
            val note = Note.new(title, widthMm, heightMm, background, folder)
            val notes = notesDir()
            val segs = folderSegments(folder)
            val parent = navigate(notes, segs, create = true) ?: error("create folder failed")
            val name = uniqueChildName(parent, sanitize(title))
            val dir = createDirectory(parent, name) ?: error("create directory failed")
            val rel = (segs + name).joinToString("/")
            noteCache[note.id] = note
            pathIndex[note.id] = rel
            writeJson(dir, "note.json", note)
            writeBytes(dir, "page-0.bin", StrokeCodec.toBytes(emptyList()))
            persistIndex(notes)
            note
        }
    }

    suspend fun loadNote(noteId: String): Note = withContext(Dispatchers.IO) {
        withStorageLock {
            noteCache[noteId]?.let { return@withStorageLock it }
            val dir = resolveNoteDir(noteId)
            if (dir != null) {
                val file = resolveFile(dir, "note.json")
                if (file != null) {
                    runCatching {
                        json.decodeFromString<Note>(readText(file))
                    }.getOrNull()?.let {
                        noteCache[noteId] = it
                        return@withStorageLock it
                    }
                }
            }
            val fallback = Note(id = noteId, title = "読み込めないメモ")
            noteCache[noteId] = fallback
            fallback
        }
    }

    suspend fun loadStrokes(noteId: String, pageIndex: Int): List<Stroke> =
        withContext(Dispatchers.IO) {
            withStorageLock {
                strokeCache[strokeKey(noteId, pageIndex)]?.let { return@withStorageLock it }
                val dir = resolveNoteDir(noteId) ?: return@withStorageLock emptyList()
                val file = resolveFile(dir, "page-$pageIndex.bin") ?: return@withStorageLock emptyList()
                val strokes = runCatching { StrokeCodec.fromBytes(readBytes(file)) }
                    .getOrElse { emptyList() }
                strokeCache[strokeKey(noteId, pageIndex)] = strokes
                strokes
            }
        }

    /**
     * Loads the strokes for all pages of a note in one pass (single directory resolution, single
     * lock). Missing pages are read in parallel to cut wall-clock time on large notes.
     */
    suspend fun loadAllStrokes(noteId: String, pageCount: Int): List<List<Stroke>> =
        withContext(Dispatchers.IO) {
            if (pageCount <= 0) return@withContext emptyList()
            withStorageLock {
                val dir = resolveNoteDir(noteId)
                val files = arrayOfNulls<DocumentFile?>(pageCount)
                val need = ArrayList<Int>()
                for (i in 0 until pageCount) {
                    if (strokeCache[strokeKey(noteId, i)] != null) continue
                    files[i] = dir?.let { resolveFile(it, "page-$i.bin") }
                    need.add(i)
                }
                val loaded = java.util.concurrent.ConcurrentHashMap<Int, List<Stroke>>()
                if (need.isNotEmpty()) {
                    coroutineScope {
                        need.map { i ->
                            async(Dispatchers.IO) {
                                val file = files[i]
                                loaded[i] = if (file == null) emptyList()
                                else runCatching { StrokeCodec.fromBytes(readBytes(file)) }
                                    .getOrElse { emptyList() }
                            }
                        }.awaitAll()
                    }
                }
                val result = ArrayList<List<Stroke>>(pageCount)
                for (i in 0 until pageCount) {
                    val strokes = strokeCache[strokeKey(noteId, i)] ?: loaded[i] ?: emptyList()
                    strokeCache[strokeKey(noteId, i)] = strokes
                    result.add(strokes)
                }
                result
            }
        }

    suspend fun saveNote(
        note: Note,
        strokesByPage: List<List<Stroke>>,
        unloadedPages: Set<Int> = emptySet(),
    ) {
        val snapshot = strokesByPage.map { Collections.unmodifiableList(ArrayList(it)) }
        withContext(Dispatchers.IO) {
            withStorageLock {
                val baselines = persistedStrokes
                var previous = baselines.remove(note.id)
                val notes = notesDir()
                val dir = resolveNoteDir(note.id) ?: run {
                    previous = null
                    // Note not on disk yet (rare): create it in its folder.
                    val segs = folderSegments(note.folder)
                    val parent = navigate(notes, segs, create = true) ?: return@withContext
                    val name = uniqueChildName(parent, sanitize(note.title))
                    pathIndex[note.id] = (segs + name).joinToString("/")
                    createDirectory(parent, name) ?: return@withContext
                }
                val saved = note.withUpdatedAt()
                noteCache[note.id] = saved
                writeJson(dir, "note.json", saved)
                val previousPages = previous?.takeIf { it.directoryUri == dir.uri }?.pages
                val pages = arrayOfNulls<PersistedPage>(snapshot.size)
                snapshot.forEachIndexed { i, strokes ->
                    if (i in unloadedPages) {
                        // The page's strokes were never loaded; keep whatever is on disk and keep
                        // the persisted baseline so a later save can still diff it.
                        pages[i] = previousPages?.getOrNull(i)
                        return@forEachIndexed
                    }
                    val fileName = "page-$i.bin"
                    val file = resolveFile(dir, fileName)
                    val persisted = previousPages?.getOrNull(i)
                    val written = if (
                        file != null && file.isFile && persisted != null &&
                        persisted.uri == file.uri && persisted.strokes == strokes
                    ) {
                        file
                    } else {
                        writeBytes(dir, fileName, StrokeCodec.toBytes(strokes))
                    }
                    pages[i] = PersistedPage(written.uri, strokes)
                    strokeCache[strokeKey(note.id, i)] = strokes
                }
                persistIndex(notes)
                baselines[note.id] = PersistedNote(dir.uri, pages.toList())
            }
        }
    }

    suspend fun renameNote(noteId: String, newTitle: String) {
        withContext(Dispatchers.IO) {
            withStorageLock {
                val dir = resolveNoteDir(noteId) ?: return@withContext
                val jsonFile = resolveFile(dir, "note.json") ?: return@withContext
                val note = noteCache[noteId] ?: json.decodeFromString<Note>(readText(jsonFile))
                val renamed = note.copy(title = newTitle).withUpdatedAt()
                noteCache[noteId] = renamed
                writeJson(dir, "note.json", renamed)
                // Rename the directory to the new human-readable title (unique within its parent).
                val parentPath = pathIndex[noteId]?.substringBeforeLast('/', "")
                val notes = notesDir()
                val segs = if (parentPath.isNullOrEmpty()) emptyList()
                    else parentPath.split('/')
                val parent = navigate(notes, segs, create = true)
                val desired = sanitize(newTitle)
                if (parent != null && dir.name != desired) {
                    persistedStrokes.remove(noteId)
                    val unique = uniqueChildName(parent, desired)
                    invalidateDirectories()
                    if (dir.renameTo(unique)) {
                        pathIndex[noteId] = if (parentPath.isNullOrEmpty()) unique else "$parentPath/$unique"
                        persistIndex(notes)
                    }
                }
            }
        }
    }

    /** Moves a note into [folder] (empty string = root), moving its directory and updating meta. */
    suspend fun setNoteFolder(noteId: String, folder: String) {
        if (folder.isNotEmpty()) createFolder(folder)
        withContext(Dispatchers.IO) {
            withStorageLock {
                val notes = notesDir()
                val dir = resolveNoteDir(noteId) ?: return@withContext
                val jsonFile = resolveFile(dir, "note.json") ?: return@withContext
                val note = noteCache[noteId] ?: json.decodeFromString<Note>(readText(jsonFile))
                val updated = note.copy(folder = folder).withUpdatedAt()
                noteCache[noteId] = updated
                writeJson(dir, "note.json", updated)

                val segs = folderSegments(folder)
                val destParent = navigate(notes, segs, create = true) ?: return@withContext
                val currentName = dir.name ?: sanitize(note.title)
                if (destParent.uri != dir.parentFile?.uri) {
                    persistedStrokes.remove(noteId)
                    val unique = uniqueChildName(destParent, currentName)
                    invalidateDirectories()
                    if (dir.renameTo(unique)) {
                        val rel = (segs + unique).joinToString("/")
                        pathIndex[noteId] = rel
                        persistIndex(notes)
                    }
                }
            }
        }
    }

    /** All folder paths recorded in the folder index (may include empty folders). */
    suspend fun listFolders(): List<String> = withContext(Dispatchers.IO) {
        withStorageLock {
            val notes = notesDir()
            val file = resolveFile(notes, FOLDERS_FILE) ?: return@withStorageLock emptyList()
            runCatching { json.decodeFromString<List<String>>(readText(file)) }.getOrDefault(emptyList())
        }
    }

    /** Creates the (possibly empty) folder at [path] on disk and records it in folders.json. */
    suspend fun createFolder(path: String) {
        if (path.isBlank()) return
        withContext(Dispatchers.IO) {
            withStorageLock {
                val notes = notesDir()
                navigate(notes, folderSegments(path), create = true)
                val existing = runCatching {
                    resolveFile(notes, FOLDERS_FILE)?.let {
                        json.decodeFromString<List<String>>(readText(it))
                    }
                }.getOrNull().orEmpty().toMutableSet()
                if (existing.add(path)) {
                    writeJson(notes, FOLDERS_FILE, existing.sorted())
                }
            }
        }
    }

    suspend fun deleteNote(noteId: String) {
        withContext(Dispatchers.IO) {
            withStorageLock {
                noteCache.remove(noteId)
                persistedStrokes.remove(noteId)
                val prefix = "$noteId:"
                strokeCache.keys.removeAll { it.startsWith(prefix) }
                val dir = resolveNoteDir(noteId)
                invalidateDirectories()
                dir?.delete()
                pathIndex.remove(noteId)
                runCatching { persistIndex(notesDir()) }
            }
        }
    }

    // ------------------------------------------------------------------ assets

    suspend fun importAsset(noteId: String, stream: java.io.InputStream, mime: String): String =
        withContext(Dispatchers.IO) {
            withStorageLock {
                val dir = resolveNoteDir(noteId) ?: error("note not found")
                var assets = childDocuments.children(dir)["assets"]
                if (assets == null || !assets.isDirectory) assets = createDirectory(dir, "assets")
                val name = "asset-${System.currentTimeMillis()}-${(Math.random() * 1e6).toInt()}"
                val ext = mime.substringAfter('/', "").takeIf { it.length in 2..5 } ?: "bin"
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
            withStorageLock {
                val dir = resolveNoteDir(noteId) ?: return@withStorageLock null
                val assets = childDocuments.children(dir)["assets"] ?: return@withStorageLock null
                val file = childDocuments.children(assets)[assetName] ?: return@withStorageLock null
                runCatching { readBytes(file) }.getOrNull()
            }
        }

    suspend fun importFile(noteId: String, stream: java.io.InputStream, name: String, mime: String): String =
        importAsset(noteId, stream, mime)

    // ------------------------------------------------------------------ migration

    /**
     * Copies the whole notes tree (preserving the human-readable folder/note structure, plus the
     * index and folder list) from [fromUri] into [toUri] so changing the save location does not
     * strand existing notes. Non-destructive: originals are left in place.
     */
    suspend fun migrate(fromUri: String?, toUri: String?) {
        withContext(Dispatchers.IO) {
            withStorageLock {
                invalidateDirectories()
                persistedStrokes = ConcurrentHashMap()
                strokeCache.clear()
                try {
                    val from = notesDirFor(fromUri)
                    val destinationRoot = accessibleRootFor(toUri)
                    requireSafeMigrationDestination(from.uri, destinationRoot.uri)
                    val to = notesDirFor(toUri)
                    requireSafeMigrationDestination(from.uri, to.uri)
                    if (!to.canWrite()) {
                        Log.w(TAG, "migrate: destination $toUri not writable")
                        return@withStorageLock
                    }
                    copyTree(from, to, 0)
                } finally {
                    pathIndex.clear()
                    invalidateDirectories()
                }
            }
        }
    }

    internal fun requireSafeMigrationDestination(source: Uri, destination: Uri) {
        if (source.scheme == "file" && destination.scheme == "file") {
            val sourcePath = File(requireNotNull(source.path)).canonicalFile.toPath()
            val destinationPath = File(requireNotNull(destination.path)).canonicalFile.toPath()
            require(!destinationPath.startsWith(sourcePath)) { "Migration destination is inside the source" }
            return
        }
        require(source != destination) { "Migration destination equals the source" }
        if (source.scheme != destination.scheme || source.authority != destination.authority) return
        require(source.scheme == "content") { "Cannot establish migration destination ancestry" }
        val sourceId = runCatching { DocumentsContract.getDocumentId(source) }.getOrNull()
        val destinationId = runCatching { DocumentsContract.getDocumentId(destination) }.getOrNull()
        if (sourceId == null || destinationId == null) return
        require(sourceId != destinationId) { "Migration destination equals the source" }
        val resolver = context.contentResolver
        val child = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { DocumentsContract.isChildDocument(resolver, source, destination) }.getOrNull()
        } else {
            null
        }
        val path = runCatching { DocumentsContract.findDocumentPath(resolver, destination)?.path }.getOrNull()
        require(child != true && path?.contains(sourceId) != true) { "Migration destination is inside the source" }
        require(child == false || path?.firstOrNull() == DocumentsContract.getTreeDocumentId(source)) {
            "Cannot establish migration destination ancestry"
        }
    }

    private suspend fun copyTree(src: DocumentFile, dest: DocumentFile, depth: Int) {
        if (depth > MAX_DEPTH) return
        for (child in childDocuments.children(src).values.toList()) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                val destChild = createDirectory(dest, name) ?: continue
                copyTree(child, destChild, depth + 1)
            } else {
                if (childDocuments.children(dest)[name] != null) continue
                runCatching {
                    val bytes = openInputStream(child).use { it.readBytes() }
                    val target = name.removeSuffix(LEGACY_SUFFIX)
                    writeBytes(dest, target, bytes)
                }
            }
        }
    }

    // ------------------------------------------------------------------ low level

    private suspend fun openOutputStream(doc: DocumentFile): java.io.OutputStream =
        withContext(Dispatchers.IO) {
            // "wt" = write + truncate. Using plain "w" leaves trailing bytes when the new content
            // is shorter (e.g. a note that shrank after undo), which corrupted note.json/patches.
            context.contentResolver.openOutputStream(doc.uri, "wt")
                ?: context.contentResolver.openOutputStream(doc.uri)
                ?: error("cannot open ${doc.uri}")
        }

    private suspend fun openInputStream(doc: DocumentFile): java.io.InputStream =
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(doc.uri) ?: error("cannot open ${doc.uri}")
        }

    private suspend inline fun <reified T> writeJson(dir: DocumentFile, fileName: String, value: T) {
        val bytes = json.encodeToString(value).encodeToByteArray()
        writeBytes(dir, fileName, bytes)
    }

    private suspend fun writeBytes(dir: DocumentFile, fileName: String, bytes: ByteArray): DocumentFile {
        val file = resolveFile(dir, fileName) ?: dir.createFile(FILE_MIME, fileName)
            ?.let { childDocuments.created(dir, it) }
            ?: error("cannot create $fileName in ${dir.uri}")
        openOutputStream(file).use { it.write(bytes) }
        return file
    }

    /**
     * Resolve [fileName] inside [dir], tolerating the ".bin" suffix that documentfile appends to
     * every [DocumentFile.createFile] display name. The suffixed name is checked first so existing
     * data stays readable and writes update the same file instead of creating a duplicate.
     */
    private fun resolveFile(dir: DocumentFile, fileName: String): DocumentFile? {
        val children = childDocuments.children(dir)
        return children["$fileName$LEGACY_SUFFIX"] ?: children[fileName]
    }

    private suspend fun readText(file: DocumentFile): String =
        openInputStream(file).use { it.readBytes().decodeToString() }

    private suspend fun readBytes(file: DocumentFile): ByteArray =
        openInputStream(file).use { it.readBytes() }
}
