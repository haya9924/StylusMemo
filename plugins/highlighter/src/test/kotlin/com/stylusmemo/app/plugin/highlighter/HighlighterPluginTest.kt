package com.stylusmemo.app.plugin.highlighter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlighterPluginTest {

    @Test
    fun toolSpecIsTranslucentAndBehind() {
        val plugin = HighlighterPlugin()
        assertEquals("highlighter", plugin.id)
        assertTrue(plugin.tool.isTranslucent)
        assertTrue(plugin.tool.drawBehind)
        assertTrue(plugin.tool.sizeMm > 0f)
    }
}
