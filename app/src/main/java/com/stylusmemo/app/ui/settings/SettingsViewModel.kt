package com.stylusmemo.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stylusmemo.app.StylusMemoApp
import com.stylusmemo.app.data.AppSettings
import com.stylusmemo.app.data.SettingsRepository
import com.stylusmemo.app.model.ShortcutAction
import com.stylusmemo.app.model.StylusButtonPattern
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: SettingsRepository = (app as StylusMemoApp).settingsRepository
    private val noteRepo: com.stylusmemo.app.data.NoteRepository = (app as StylusMemoApp).noteRepository

    private val _settings = MutableStateFlow<AppSettings?>(null)
    val settings: StateFlow<AppSettings?> = _settings

    init {
        viewModelScope.launch {
            repo.settings.collect { _settings.value = it }
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.updateSettings(transform) }
    }

    fun saveLocationUri(): String = _settings.value?.saveLocationUri.orEmpty()

    /**
     * Changes the save location, copying existing notes to the new location first so they do not
     * disappear (the originals are left in place). Runs here because the change happens while the
     * home screen (and its ViewModel) may not be active.
     */
    fun setSaveLocationUri(uri: String) {
        val old = _settings.value?.saveLocationUri.orEmpty()
        if (uri == old) return
        viewModelScope.launch {
            runCatching { noteRepo.migrate(old, uri) }
                .onFailure { android.util.Log.e("SaveLoc", "migrate failed", it) }
            repo.updateSettings { it.copy(saveLocationUri = uri) }
        }
    }
    fun setPenColor(argb: Long) = update { it.copy(defaultPenColorArgb = argb) }
    fun setPenSize(mm: Float) = update { it.copy(defaultPenSizeMm = mm) }
    fun setFingerDraw(enabled: Boolean) = update { it.copy(fingerDrawEnabled = enabled) }
    fun setPrimaryAction(a: ShortcutAction) = update { it.copy(stylusPrimaryAction = a) }
    fun setSecondaryAction(a: ShortcutAction) = update { it.copy(stylusSecondaryAction = a) }
    fun setPrimaryPattern(p: StylusButtonPattern?) = update { it.copy(stylusPrimaryPattern = p) }
    fun setSecondaryPattern(p: StylusButtonPattern?) = update { it.copy(stylusSecondaryPattern = p) }
    fun setAiMarkdownApiKey(key: String) = update { it.copy(aiMarkdownApiKey = key) }
    fun setAiMarkdownModel(model: String) = update { it.copy(aiMarkdownModel = model) }
}
