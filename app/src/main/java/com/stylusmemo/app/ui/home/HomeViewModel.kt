package com.stylusmemo.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stylusmemo.app.StylusMemoApp
import com.stylusmemo.app.data.NoteRepository
import com.stylusmemo.app.data.SettingsRepository
import com.stylusmemo.app.model.BackgroundSpec
import com.stylusmemo.app.model.Note
import androidx.ink.strokes.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val noteRepo: NoteRepository = (app as StylusMemoApp).noteRepository
    private val settingsRepo: SettingsRepository = (app as StylusMemoApp).settingsRepository

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes

    private val _thumbnails = MutableStateFlow<Map<String, List<Stroke>>>(emptyMap())
    val thumbnails: StateFlow<Map<String, List<Stroke>>> = _thumbnails

    private val _createdNoteId = MutableStateFlow<String?>(null)
    val createdNoteId: StateFlow<String?> = _createdNoteId

    private val _currentFolder = MutableStateFlow("")
    val currentFolder: StateFlow<String> = _currentFolder

    /** Notes in the current folder (root = ""). */
    private val _visibleNotes = MutableStateFlow<List<Note>>(emptyList())
    val visibleNotes: StateFlow<List<Note>> = _visibleNotes

    /** True while the note list is (re)loading; used to show a loading state instead of "no notes". */
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    /** Immediate sub-folder names under the current folder. */
    private val _folders = MutableStateFlow<List<String>>(emptyList())
    val folders: StateFlow<List<String>> = _folders

    var defaultPageSizeMm: Pair<Float, Float> = 210f to 297f
        private set
    var defaultBackground: BackgroundSpec = BackgroundSpec.defaultGrid()
        private set

    private var storedFolders: List<String> = emptyList()

    init {
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                defaultPageSizeMm = s.defaultPageWidthMm to s.defaultPageHeightMm
                defaultBackground = s.defaultBackground
                // Migration (copying notes to a newly chosen location) is handled by SettingsViewModel;
                // here we only apply the current root and refresh.
                noteRepo.setRootUri(s.saveLocationUri)
                refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = noteRepo.listNotes()
            _notes.value = list
            storedFolders = runCatching { noteRepo.listFolders() }.getOrDefault(emptyList())
            recomputeFolder()
            _loading.value = false
            loadThumbnails(_visibleNotes.value)
        }
    }

    /**
     * Loads page-0 previews for the given notes in the background with bounded parallelism,
     * streaming each result into [thumbnails] so the grid fills in progressively.
     */
    private fun loadThumbnails(notes: List<Note>) {
        val pending = notes.filter { !_thumbnails.value.containsKey(it.id) }
        if (pending.isEmpty()) return
        val semaphore = Semaphore(4)
        viewModelScope.launch(Dispatchers.IO) {
            pending.map { n ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val strokes = runCatching { noteRepo.loadStrokes(n.id, 0) }
                            .getOrDefault(emptyList())
                        withContext(Dispatchers.Main) {
                            _thumbnails.value = _thumbnails.value + (n.id to strokes)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun recomputeFolder() {
        val folder = _currentFolder.value
        _visibleNotes.value = _notes.value
            .filter { it.folder == folder }
            .sortedByDescending { it.updatedAt }
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val fromNotes = _notes.value.mapNotNull { n ->
            when {
                n.folder == folder -> null
                folder.isEmpty() && n.folder.isNotEmpty() -> n.folder.substringBefore('/')
                n.folder.startsWith(prefix) -> {
                    val rest = n.folder.removePrefix(prefix)
                    rest.substringBefore('/').takeIf { it.isNotEmpty() }
                }
                else -> null
            }
        }
        val fromIndex = storedFolders.mapNotNull { f ->
            when {
                f == folder -> null
                folder.isEmpty() && f.isNotEmpty() -> f.substringBefore('/')
                f.startsWith(prefix) -> f.removePrefix(prefix).substringBefore('/').takeIf { it.isNotEmpty() }
                else -> null
            }
        }
        _folders.value = (fromNotes + fromIndex).distinct().sorted()
    }

    /** Creates a (possibly empty) folder under the current folder. */
    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val path = if (_currentFolder.value.isEmpty()) trimmed else "${_currentFolder.value}/$trimmed"
        viewModelScope.launch(Dispatchers.IO) {
            noteRepo.createFolder(path)
            refresh()
        }
    }

    fun setFolder(folder: String) {
        _currentFolder.value = folder
        recomputeFolder()
        loadThumbnails(_visibleNotes.value)
    }

    fun moveNote(noteId: String, folder: String) {
        viewModelScope.launch(Dispatchers.IO) {
            noteRepo.setNoteFolder(noteId, folder)
            refresh()
        }
    }

    fun createNote(title: String, widthMm: Float, heightMm: Float, background: BackgroundSpec) {
        viewModelScope.launch(Dispatchers.IO) {
            val note = noteRepo.createNote(title, widthMm, heightMm, background, _currentFolder.value)
            refresh()
            _createdNoteId.value = note.id
        }
    }

    fun renameNote(id: String, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            noteRepo.renameNote(id, title)
            refresh()
        }
    }

    fun deleteNote(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            noteRepo.deleteNote(id)
            refresh()
        }
    }

    fun consumeCreatedNoteId() {
        _createdNoteId.value = null
    }
}
