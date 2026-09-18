package com.stylusmemo.app.plugin

import com.stylusmemo.app.plugin.memorize.MemorizeModePlugin
import com.stylusmemo.app.plugin.tools.DrawingToolPlugin
import java.util.ServiceLoader

/**
 * Discovers and holds the plugins available to the host.
 *
 * Plugins are discovered with [ServiceLoader] from `META-INF/services`. Because
 * the discovery uses the host's class loader, plugins that are packaged into the
 * app (as Gradle modules or jars) are found at startup with no registration
 * code in the host. A runtime loader for sideloaded plugin jars can be added
 * later behind the same API without touching SPI consumers.
 */
class PluginRegistry private constructor(
    private val loader: () -> Iterable<Plugin>,
) {

    @Volatile
    private var snapshot: List<Plugin>? = null

    /** All compatible plugins, discovered once and cached. */
    val plugins: List<Plugin>
        get() {
            snapshot?.let { return it }
            val found = loader().toList()
                .filter { it.apiVersion == PluginApiVersion.VERSION }
                .sortedBy { it.displayName }
            snapshot = found
            return found
        }

    /** Only the note-exporting plugins. */
    fun noteExporters(): List<NoteExporterPlugin> = plugins.filterIsInstance<NoteExporterPlugin>()

    /** First exporter registered under [id], or null. */
    fun noteExporter(id: String): NoteExporterPlugin? = noteExporters().firstOrNull { it.id == id }

    /** Only the memorization-mode feature plugins. */
    fun memorizePlugins(): List<MemorizeModePlugin> = plugins.filterIsInstance<MemorizeModePlugin>()

    /** First memorization plugin registered under [id], or null. */
    fun memorizePlugin(id: String): MemorizeModePlugin? = memorizePlugins().firstOrNull { it.id == id }

    /** Drawing-tool plugins (highlighters, etc.). */
    fun drawingToolPlugins(): List<DrawingToolPlugin> = plugins.filterIsInstance<DrawingToolPlugin>()

    /** First drawing-tool plugin registered under [id], or null. */
    fun drawingToolPlugin(id: String): DrawingToolPlugin? = drawingToolPlugins().firstOrNull { it.id == id }

    companion object {
        /** Builds a registry from [ServiceLoader] on the given class loader. */
        fun discover(classLoader: ClassLoader = PluginRegistry::class.java.classLoader): PluginRegistry =
            PluginRegistry(loader = { ServiceLoader.load(Plugin::class.java, classLoader) })

        /** Builds a registry backed by an explicit iterable (used by tests). */
        fun of(plugins: Iterable<Plugin>): PluginRegistry = PluginRegistry(loader = { plugins })
    }
}