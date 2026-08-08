package com.stylusmemo.app.model

import android.view.KeyEvent
import android.view.MotionEvent

/**
 * A captured stylus-button event signature recorded by the learning mode.
 *
 * Only the fields that were actually observed while the button was pressed
 * are non-zero. Used to detect non-standard styli that do not report the
 * standard [MotionEvent.BUTTON_STYLUS_PRIMARY]/[MotionEvent.BUTTON_STYLUS_SECONDARY].
 */
data class StylusButtonPattern(
    val source: Int = 0,
    val toolType: Int = 0,
    val buttonState: Int = 0,
    val keyCode: Int = 0,
) {
    fun isEmpty(): Boolean =
        source == 0 && toolType == 0 && buttonState == 0 && keyCode == 0

    /**
     * Whether this pattern carries a signal worth matching: a pressed button,
     * a distinctive tool change, or a hardware key. A plain stylus/finger
     * touch (buttonState == 0) is not a button signature.
     */
    val isMeaningful: Boolean
        get() = buttonState != 0 || isToolTypeDistinctive || keyCode != 0

    /** Whether the captured tool type is distinctive enough to match against. */
    val isToolTypeDistinctive: Boolean
        get() = toolType != 0 &&
            toolType != MotionEvent.TOOL_TYPE_STYLUS &&
            toolType != MotionEvent.TOOL_TYPE_FINGER

    fun describe(): String = buildString {
        if (keyCode != 0) append("キー[${KeyEvent.keyCodeToString(keyCode)}] ")
        if (buttonState != 0) append("ボタン[0x${buttonState.toString(16)}] ")
        if (isToolTypeDistinctive) append("ツール[$toolType] ")
        if (source != 0) append("ソース[0x${source.toString(16)}] ")
        if (!isMeaningful) append("未検出")
    }.trim()

    fun encode(): String = listOf(source, toolType, buttonState, keyCode).joinToString(",")

    companion object {
        fun decode(s: String?): StylusButtonPattern? {
            if (s.isNullOrBlank()) return null
            val parts = s.split(",")
            if (parts.size != 4) return null
            val (source, tool, btn, key) = parts.map { it.toIntOrNull() ?: 0 }
            return StylusButtonPattern(source, tool, btn, key)
        }
    }
}
