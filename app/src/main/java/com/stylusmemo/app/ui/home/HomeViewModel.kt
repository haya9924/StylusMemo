package com.stylusmemo.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stylusmemo.app.StylusMemoApp
import com.stylusmemo.app.data.NoteRepository
import com.stylusmemo.app.data.SettingsRepository
import com.stylusmemo.app.model.BackgroundSpec
import com.stylusmemo.app.model.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val noteRepo: NoteRepository = (app as StylusMemoApp).noteRepository
    private val settingsRepo: SettingsRepository = (app as StylusMemoApp).settingsRepository

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes

    private val _createdNoteId = MutableStateFlow<String?>(null)
    val createdNoteId: StateFlow<String?> = _createdNoteId

    var defaultPageSizeMm: Pair<Float, Float> = 210f to 297f
        private set
    var defaultBackground: BackgroundSpec = BackgroundSpec.defaultGrid()
        private set

    init {
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                defaultPageSizeMm = s.defaultPageWidthMm to s.defaultPageHeightMm
                defaultBackground = s.defaultBackground
                noteRepo.setRootUri(s.saveLocationUri)
                refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _notes.value = noteRepo.listNotes()
        }
    }

    fun createNote(title: String, widthMm: Float, heightMm: Float, background: BackgroundSpec) {
        viewModelScope.launch(Dispatchers.IO) {
            val note = noteRepo.createNote(title, widthMm, heightMm, background)
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
