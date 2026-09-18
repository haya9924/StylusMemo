package com.stylusmemo.app.plugin.highlighter

import com.stylusmemo.app.plugin.tools.DrawingToolPlugin
import com.stylusmemo.app.plugin.tools.ToolSpec

/**
 * Translucent, wide, multiply-blended marker that highlights text without hiding it.
 * Plain class with a public no-arg constructor so [com.stylusmemo.app.plugin.PluginRegistry]
 * discovers it via `ServiceLoader`.
 */
class HighlighterPlugin : DrawingToolPlugin {

    override val id: String = "highlighter"

    override val displayName: String = "蛍光ペン"

    override val description: String = "文字を隠さない半透明の蛍光マーカー"

    override val tool: ToolSpec = ToolSpec(
        id = id,
        colorArgb = 0x66FFEB3B.toInt(),
        sizeMm = 3.0f,
        squareCap = true,
        drawBehind = true,
        blendMultiply = true,
    )
}
