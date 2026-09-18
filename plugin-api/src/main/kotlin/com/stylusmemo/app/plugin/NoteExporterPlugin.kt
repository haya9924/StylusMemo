package com.stylusmemo.app.plugin

/**
 * Turns a note into an export file (PDF, JPEG, Markdown, SVG, ...).
 *
 * The plugin builds its output from [ExportContext] and hands the bytes to
 * [DataSink]. It must not talk directly to storage, network or the display —
 * everything the plugin needs arrives through the context.
 *
 * Implementations should be plain Kotlin classes with a public no-argument
 * constructor so [PluginRegistry] can instantiate them via `ServiceLoader`.
 */
interface NoteExporterPlugin : Plugin {

    /** File extensions this exporter produces (e.g. `"pdf"`, `"md"`). */
    val fileExtensions: List<String>
        get() = emptyList()

    /**
     * Performs the export.
     *
     * @return true when the output was written successfully.
     *         The host shows an error toast when false or when [exception] is thrown.
     */
    suspend fun export(context: ExportContext, sink: DataSink): Boolean
}