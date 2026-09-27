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

    private data class ResolvedDirectory(
        val root: Uri,
        val path: String,
        val directory: DocumentFile,
        val noteJsonStamp: NoteJsonStamp?,
    )

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

    /**
     * Access-ordered LRU cache with an optional weight budget. Eviction is always safe here because
     * these caches only memoise values that can be re-read from storage.
     */
    internal class BoundedCache<K : Any, V : Any>(
        private val maxEntries: Int = Int.MAX_VALUE,
        private val maxBytes: Long = Long.MAX_VALUE,
        private val weigh: (V) -> Long = { 0L },
    ) {
        private data class Entry<V : Any>(val value: V, val weight: Long)

        private val entries = object : LinkedHashMap<K, Entry<V>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>): Boolean {
                if (size <= 1) return false
                totalWeight -= eldest.value.weight
                return size > maxEntries || totalWeight > maxBytes
            }
        }

        private var totalWeight = 0L

        val values: Collection<V> get() = entries.values.map { it.value }

        operator fun get(key: K): V? = entries[key]?.value

        operator fun set(key: K, value: V) {
            // Replace in place without double-counting the evicted copy's weight.
            entries[key]?.let { totalWeight -= it.weight }
            val weight = weigh(value)
            entries[key] = Entry(value, weight)
            totalWeight += weight
        }

        fun remove(key: K): V? {
            val hit = entries.remove(key) ?: return null
            totalWeight -= hit.weight
            return hit.value
        }

        fun removeKeysIf(predicate: (K) -> Boolean) {
            val doomed = entries.keys.filter(predicate)
            for (key in doomed) entries.remove(key)?.let { totalWeight -= it.weight }
        }

        fun clear() {
            entries.clear()
            totalWeight = 0
        }
    }

    private fun invalidateDirectories() {
        childDocuments.clear()
        noteDirectories.clear()
    }

    private fun createDirectory(parent: DocumentFile, name: String): DocumentFile? =
        parent.createDirectory(name)?.let { childDocuments.created(parent, it) }

    /**
     * Runs [block] under the storage mutex. Pass `mutates = true` for anything that writes or
     * deletes so the child-directory cache is dropped on both sides of the critical section: a
     * write can be made stale by an external change, and a read that follows must not reuse the
     * pre-write listing. Read-only callers leave the default so the cache survives, which is what
     * makes repeated lookups cheap (a `listFiles()` per call is a ContentResolver round trip on SAF).
     */
    private suspend inline fun <T> withStorageLock(
        mutates: Boolean = false,
        block: () -> T,
    ): T = mutex.withLock {
        if (mutates) childDocuments.clear()
        try {
            block()
        } finally {
            if (mutates) childDocuments.clear()
        }
    }

    /** noteId -> relative path (under the notes directory). Best-effort cache of index.json. */
    private val pathIndex = mutableMapOf<String, String>()

    /** Set whenever [pathIndex] changes so [persistIndex] knows the file is stale. */
    private var pathIndexDirty = false

    private fun setPathIndex(noteId: String, path: String) {
        if (pathIndex[noteId] != path) pathIndexDirty = true
        pathIndex[noteId] = path
    }

    private fun clearPathIndex() {
        if (pathIndex.isNotEmpty()) pathIndexDirty = true
        pathIndex.clear()
    }

    companion object {
        private const val TAG = "NoteRepository"
        private const val FILE_MIME = "application/octet-stream"
        private const val LEGACY_SUFFIX = ".bin"
        private const val INDEX_FILE = "index.json"
        private const val FOLDERS_FILE = "folders.json"
        private const val MAX_DEPTH = 8

        /**
         * Title used by the in-memory placeholder when note.json cannot be read.
         * Never persisted: saving it would overwrite a real note with a blank stub.
         */
        const val UNREADABLE_TITLE = "読み進めないメモ"
    }

    /**
     * Session cache of the latest in-memory note state, so a just-saved note always reopens.
     * Bounded because it used to grow for the whole session; evicting only costs a re-read.
     */
    private val noteCache = BoundedCache<String, Note>(maxEntries = 64)

    /**
     * Session cache of parsed page strokes, keyed by "noteId:pageIndex". Avoids re-reading and
     * re-decoding page-<n>.bin files when a note is reopened. Bounded by an approximate decoded
     * size: decoded strokes are far larger than their on-disk form, so an unbounded cache turned
     * into sustained GC pressure that slowed down frame times.
     */
    private val strokeCache = BoundedCache<String, List<Stroke>>(maxBytes = 48L * 1024 * 1024) {
        strokes ->
        var total = 0L
        for (s in strokes) total += 96L + s.inputs.size * 8L
        total
    }

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
        clearPathIndex()
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

    /**
     * Rescans the tree from scratch. This is the recovery path taken when a note cannot be resolved
     * through the cached path, so it must observe the real directory contents: the child-document
     * cache can predate an external change (a note added, renamed or restored outside this
     * repository) and would otherwise hide it. Dropping the cache here costs one extra listing per
     * recovery instead of one per every read.
     */
    private suspend fun rebuildIndex(notes: DocumentFile): MutableMap<String, String> {
        childDocuments.clear()
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

    /**
     * Writes index.json only when the id -> path map actually changed. Autosave calls this on every
     * save, and the map is untouched by ordinary page writes, so this removes a full re-serialisation
     * of the whole library per autosave. index.json is a cache and is rebuilt by [rebuildIndex].
     */
    private suspend fun persistIndex(notes: DocumentFile) {
        if (!pathIndexDirty) return
        pathIndexDirty = false
        runCatching { writeJson(notes, INDEX_FILE, pathIndex.toMap()) }
            .onFailure { pathIndexDirty = true }
    }

    private suspend fun resolveNoteDir(noteId: String): DocumentFile? {
        val notes = notesDir()
        val cached = noteDirectories.remove(noteId)
        if (cached != null && cached.root == notes.uri && cached.path == pathIndex[noteId] &&
            cached.directory.isDirectory && cached.directory.name == cached.path.substringAfterLast('/')
        ) {
            val check = checkNote(cached.directory, noteId, cached.noteJsonStamp)
            if (check.ok) {
                noteDirectories[noteId] =
                    if (check.stamp != null) cached.copy(noteJsonStamp = check.stamp) else cached
                return cached.directory
            }
        }
        pathIndex[noteId]?.let { rel ->
            navigate(notes, rel.split('/'), create = false)?.let { dir ->
                val check = checkNote(dir, noteId, null)
                if (check.ok) {
                    noteDirectories[noteId] = ResolvedDirectory(notes.uri, rel, dir, check.stamp)
                    return dir
                }
            }
        }
        noteDirectories.clear()
        clearPathIndex()
        pathIndex.putAll(rebuildIndex(notes)); pathIndexDirty = true
        val rel = pathIndex[noteId] ?: return null
        return navigate(notes, rel.split('/'), create = false)?.also {
            noteDirectories[noteId] = ResolvedDirectory(notes.uri, rel, it, null)
        }
    }

    /**
     * Identity of a note.json on disk. Reading and deserialising a note can mean hundreds of
     * kilobytes, which used to happen on *every* repository call because the note directory was
     * re-validated each time. Comparing this cheap fingerprint instead lets repeat lookups skip the
     * parse while still noticing an external edit or replacement.
     */
    private data class NoteJsonStamp(val modified: Long, val length: Long) {
        /**
         * Providers that do not report a modification time would make every file look identical, so
         * a zero timestamp disqualifies the fingerprint and forces a real read.
         */
        val usable: Boolean get() = modified > 0L
    }

    private class NoteCheck(val ok: Boolean, val stamp: NoteJsonStamp?)

    private fun noteJsonStamp(file: DocumentFile): NoteJsonStamp? = runCatching {
        NoteJsonStamp(file.lastModified(), file.length())
    }.getOrNull()?.takeIf { it.usable }

    /**
     * True when [dir] still holds the note.json of [noteId]. [known] is the fingerprint already
     * validated for this id; when it still matches, the file is unchanged and the read is skipped.
     */
    private suspend fun checkNote(dir: DocumentFile, noteId: String, known: NoteJsonStamp?): NoteCheck {
        val file = resolveFile(dir, "note.json") ?: return NoteCheck(false, null)
        val current = noteJsonStamp(file)
        if (known != null && current != null && known == current) return NoteCheck(true, current)
        val ok = runCatching { json.decodeFromString<Note>(readText(file)).id == noteId }
            .getOrDefault(false)
        return NoteCheck(ok, if (ok) current else null)
    }

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
            clearPathIndex()
            pathIndex.putAll(index); pathIndexDirty = true
            (out + noteCache.values)
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
        withStorageLock(mutates = true) {
            val note = Note.new(title, widthMm, heightMm, background, folder)
            val notes = notesDir()
            val segs = folderSegments(folder)
            val parent = navigate(notes, segs, create = true) ?: error("create folder failed")
            val name = uniqueChildName(parent, sanitize(title))
            val dir = createDirectory(parent, name) ?: error("create directory failed")
            val rel = (segs + name).joinToString("/")
            noteCache[note.id] = note
            setPathIndex(note.id, rel)
            writeJson(dir, "note.json", note)
            writeBytes(dir, "page-0.bin", StrokeCodec.toBytes(emptyList()))
            persistIndex(notes)
            note
        }
    }

    /**
     * Reads note metadata, or returns null when note.json is missing/unreadable.
     *
     * Failures are never cached as a fake note: the next open retries disk, and a later
     * save cannot replace a real note.json with a placeholder (see [saveNote]).
     */
    suspend fun loadNoteOrNull(noteId: String): Note? = withContext(Dispatchers.IO) {
        withStorageLock {
            noteCache[noteId]?.let { return@withStorageLock it }
            val loaded = runCatching {
                val dir = resolveNoteDir(noteId)
                    ?: error("directory for $noteId not found")
                val file = resolveFile(dir, "note.json")
                    ?: error("note.json missing in ${dir.uri}")
                json.decodeFromString<Note>(readText(file))
            }.onFailure { e ->
                Log.w(TAG, "loadNote failed for $noteId", e)
            }.getOrNull()
            loaded?.let { noteCache[noteId] = it }
            loaded
        }
    }

    /**
     * Like [loadNoteOrNull], but returns a placeholder on failure so existing callers keep
     * working. The placeholder is not cached and must not be saved.
     */
    suspend fun loadNote(noteId: String): Note =
        loadNoteOrNull(noteId) ?: Note(id = noteId, title = UNREADABLE_TITLE)

    suspend fun loadStrokes(noteId: String, pageIndex: Int): List<Stroke> =
        loadStrokesForPages(noteId, listOf(pageIndex))[pageIndex] ?: emptyList()

    /**
     * Loads the strokes of the requested pages of a note in one pass: a single directory resolution
     * and a single lock acquisition, with the page files read in parallel. Callers that need several
     * pages (opening a note, filling gaps before an export, a structural page change) should use this
     * instead of calling [loadStrokes] in a loop, which re-resolves the directory and re-reads
     * note.json once per page.
     *
     * Pages that are missing or unreadable map to an empty list, and every requested index is
     * present in the result.
     */
    suspend fun loadStrokesForPages(
        noteId: String,
        pageIndices: List<Int>,
    ): Map<Int, List<Stroke>> = withContext(Dispatchers.IO) {
        if (pageIndices.isEmpty()) return@withContext emptyMap()
        withStorageLock {
            val unique = pageIndices.distinct()
            val cached = HashMap<Int, List<Stroke>>(unique.size)
            val need = ArrayList<Int>()
            val files = HashMap<Int, DocumentFile?>()
            val dir = if (unique.isEmpty()) null else resolveNoteDir(noteId)
            for (i in unique) {
                val hit = strokeCache[strokeKey(noteId, i)]
                if (hit != null) {
                    cached[i] = hit
                } else {
                    need.add(i)
                    files[i] = dir?.let { resolveFile(it, "page-$i.bin") }
                }
            }
            if (need.isNotEmpty()) {
                val loaded = java.util.concurrent.ConcurrentHashMap<Int, List<Stroke>>()
                coroutineScope {
                    need.map { i ->
                        async(Dispatchers.IO) {
                            val file = files[i]
                            loaded[i] = if (file == null) {
                                emptyList()
                            } else {
                                runCatching { StrokeCodec.fromBytes(readBytes(file)) }
                                    .getOrElse { emptyList() }
                            }
                        }
                    }.awaitAll()
                }
                for (i in need) {
                    val strokes = loaded[i] ?: emptyList()
                    strokeCache[strokeKey(noteId, i)] = strokes
                    cached[i] = strokes
                }
            }
            cached
        }
    }

    /**
     * Loads the strokes for all pages of a note in one pass (single directory resolution, single
     * lock). Missing pages are read in parallel to cut wall-clock time on large notes.
     */
    suspend fun loadAllStrokes(noteId: String, pageCount: Int): List<List<Stroke>> {
        if (pageCount <= 0) return emptyList()
        val byPage = loadStrokesForPages(noteId, (0 until pageCount).toList())
        return List(pageCount) { byPage[it] ?: emptyList() }
    }

    suspend fun saveNote(
        note: Note,
        strokesByPage: List<List<Stroke>>,
        unloadedPages: Set<Int> = emptySet(),
    ) {
        val snapshot = strokesByPage.map { Collections.unmodifiableList(ArrayList(it)) }
        withContext(Dispatchers.IO) {
            withStorageLock(mutates = true) {
                // A placeholder from a failed load must never replace a real note.json.
                // Allow only when the session already treats this id as that exact title
                // (a user-named note), not when cache has different/missing metadata.
                if (note.title == UNREADABLE_TITLE && noteCache[note.id]?.title != UNREADABLE_TITLE) {
                    Log.w(TAG, "refusing to overwrite note.json for ${note.id} with unreadable placeholder")
                    return@withContext
                }
                val baselines = persistedStrokes
                var previous = baselines.remove(note.id)
                val notes = notesDir()
                val dir = resolveNoteDir(note.id) ?: run {
                    previous = null
                    // Note not on disk yet (rare): create it in its folder.
                    val segs = folderSegments(note.folder)
                    val parent = navigate(notes, segs, create = true) ?: return@withContext
                    val name = uniqueChildName(parent, sanitize(note.title))
                    setPathIndex(note.id, (segs + name).joinToString("/"))
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
            withStorageLock(mutates = true) {
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
                        setPathIndex(noteId, if (parentPath.isNullOrEmpty()) unique else "$parentPath/$unique")
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
            withStorageLock(mutates = true) {
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
                        setPathIndex(noteId, rel)
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
            withStorageLock(mutates = true) {
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
            withStorageLock(mutates = true) {
                noteCache.remove(noteId)
                persistedStrokes.remove(noteId)
                val prefix = "$noteId:"
                strokeCache.removeKeysIf { it.startsWith(prefix) }
                val dir = resolveNoteDir(noteId)
                invalidateDirectories()
                dir?.delete()
                if (pathIndex.remove(noteId) != null) pathIndexDirty = true
                runCatching { persistIndex(notesDir()) }
            }
        }
    }

    // ------------------------------------------------------------------ assets

    suspend fun importAsset(noteId: String, stream: java.io.InputStream, mime: String): String =
        withContext(Dispatchers.IO) {
            withStorageLock(mutates = true) {
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
            withStorageLock(mutates = true) {
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
                    clearPathIndex()
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

    /**
     * Writes [bytes] to [fileName]. Existing files are truncated in one open+write ("wt")
     * so a shorter payload cannot leave trailing garbage from the previous content.
     */
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
