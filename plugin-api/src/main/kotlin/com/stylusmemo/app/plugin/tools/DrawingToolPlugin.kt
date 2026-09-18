package com.stylusmemo.app.plugin.tools

import com.stylusmemo.app.plugin.Plugin

/**
 * Contributes a drawing tool (e.g. a highlighter) to the editor toolbar.
 *
 * Discovered by [com.stylusmemo.app.plugin.PluginRegistry]; the host provides the UI
 * (toolbar button) and the ink pipeline (brush + rendering) from [tool].
 */
interface DrawingToolPlugin : Plugin {
    val tool: ToolSpec
}
