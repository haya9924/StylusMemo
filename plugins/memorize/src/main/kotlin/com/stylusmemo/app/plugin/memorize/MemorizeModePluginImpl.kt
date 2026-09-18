package com.stylusmemo.app.plugin.memorize

/**
 * Built-in red-sheet memorization plugin. Plain class with a public no-arg
 * constructor so [com.stylusmemo.app.plugin.PluginRegistry] finds it via `ServiceLoader`.
 */
class MemorizeModePluginImpl : MemorizeModePlugin {

    override val id: String = "memorize-mode"

    override val displayName: String = "暗記モード"

    override val description: String = "赤シートで色を隠しながら暗記する学習モード"

    override fun createSession(host: MemorizeHost): MemorizeSession = MemorizeSessionImpl(host)
}
