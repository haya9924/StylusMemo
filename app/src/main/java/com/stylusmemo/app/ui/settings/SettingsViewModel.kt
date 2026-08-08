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
    fun setSaveLocationUri(uri: String) = update { it.copy(saveLocationUri = uri) }
    fun setPenColor(argb: Long) = update { it.copy(defaultPenColorArgb = argb) }
    fun setPenSize(mm: Float) = update { it.copy(defaultPenSizeMm = mm) }
    fun setFingerDraw(enabled: Boolean) = update { it.copy(fingerDrawEnabled = enabled) }
    fun setPrimaryAction(a: ShortcutAction) = update { it.copy(stylusPrimaryAction = a) }
    fun setSecondaryAction(a: ShortcutAction) = update { it.copy(stylusSecondaryAction = a) }
    fun setPrimaryPattern(p: StylusButtonPattern?) = update { it.copy(stylusPrimaryPattern = p) }
    fun setSecondaryPattern(p: StylusButtonPattern?) = update { it.copy(stylusSecondaryPattern = p) }
}
