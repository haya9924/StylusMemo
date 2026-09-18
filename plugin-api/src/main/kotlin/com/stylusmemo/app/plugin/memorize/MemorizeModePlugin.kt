package com.stylusmemo.app.plugin.memorize

import com.stylusmemo.app.plugin.Plugin

/**
 * Hosts-provided feature plugin for the red-sheet memorization mode.
 *
 * Implementations stay free of Android dependencies: they own the session state
 * machine only. The host app renders the split-pane UI, the sheet overlay and the
 * scratch surface from the session's [MemorizeState]. Discovered by
 * [com.stylusmemo.app.plugin.PluginRegistry] through `ServiceLoader`.
 */
interface MemorizeModePlugin : Plugin {
    fun createSession(host: MemorizeHost): MemorizeSession
}
