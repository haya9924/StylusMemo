package com.stylusmemo.app.plugin

/**
 * Version of the plugin contract that the host app provides.
 *
 * Plugin jars must check this value and refuse to load (or degrade) when the host
 * version is not one they support. Bump this only when the SPI changes in a way
 * that breaks existing plugins.
 */
object PluginApiVersion {
    const val VERSION = 1
}

/**
 * Base contract for every stylus-memo plugin.
 *
 * Concrete implementations are discovered by [PluginRegistry] through
 * `java.util.ServiceLoader`. A plugin jar registers each public implementation
 * class under:
 *
 * ```
 * META-INF/services/com.stylusmemo.app.plugin.Plugin
 * ```
 *
 * (the file contains the fully-qualified class name of the implementation).
 */
interface Plugin {
    /** Machine-readable, globally unique id (e.g. `"export-markdown"`). */
    val id: String

    /** Human-readable name shown in the UI (e.g. `"Markdown 書き出し"`). */
    val displayName: String

    /** Short description shown in the plugin list. */
    val description: String
        get() = ""

    /** Plugin contract version compatibility, defaults to the current API version. */
    val apiVersion: Int
        get() = PluginApiVersion.VERSION
}