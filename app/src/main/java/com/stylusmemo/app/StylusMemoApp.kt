package com.stylusmemo.app

import android.app.Application
import com.stylusmemo.app.data.AssetBitmapCache
import com.stylusmemo.app.data.NoteRepository
import com.stylusmemo.app.data.SettingsRepository
import com.stylusmemo.app.plugin.PluginRegistry

class StylusMemoApp : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var noteRepository: NoteRepository
        private set

    /** Shared decoded-asset cache so reopening notes does not re-decode PNG backgrounds. */
    val assetCache: AssetBitmapCache by lazy { AssetBitmapCache() }

    /** Discovery of the available plugins (ServiceLoader based). */
    val pluginRegistry: PluginRegistry by lazy {
        PluginRegistry.discover()
    }

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        noteRepository = NoteRepository(this)
    }
}
