package com.stylusmemo.app

import android.app.Application
import com.stylusmemo.app.data.NoteRepository
import com.stylusmemo.app.data.SettingsRepository

class StylusMemoApp : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var noteRepository: NoteRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        noteRepository = NoteRepository(this)
    }
}
