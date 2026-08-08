package com.stylusmemo.app.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.stylusmemo.app.model.StylusButtonPattern

/**
 * Full-screen capture surface for the stylus learning mode. Listens for touch,
 * hover and hardware-key events and reports any non-trivial signature back.
 */
class StylusCaptureView(context: Context) : View(context) {

    var onPattern: ((StylusButtonPattern) -> Unit)? = null
    var onFeedback: ((String) -> Unit)? = null

    private var best: StylusButtonPattern? = null

    private val framePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.rgb(66, 165, 245)
    }
    private val textPaint = Paint().apply {
        isAntiAlias = true
        textSize = 34f
        color = Color.rgb(90, 90, 90)
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = 40f
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, 24f, 24f, framePaint)
        canvas.drawText(
            "ここにスタイラスで触れながら\nボタンを押してください",
            inset + 30f,
            inset + 70f,
            textPaint,
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val code = event.keyCode
            if (code != KeyEvent.KEYCODE_BACK && code != KeyEvent.KEYCODE_UNKNOWN) {
                report(StylusButtonPattern(source = event.source, keyCode = code))
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        captureMotion(event)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        captureMotion(event)
        return true
    }

    private fun captureMotion(event: MotionEvent) {
        val tool = if (event.pointerCount > 0) event.getToolType(0) else 0
        val p = StylusButtonPattern(
            source = event.source,
            toolType = tool,
            buttonState = event.buttonState,
        )
        if (p.isEmpty()) return
        if (!p.isMeaningful) return
        report(p)
    }

    private fun report(p: StylusButtonPattern) {
        val cur = best
        if (cur == null || p.strength() >= cur.strength()) {
            best = p
            onPattern?.invoke(p)
        }
        onFeedback?.invoke(p.describe())
    }

    private fun StylusButtonPattern.strength(): Int =
        when {
            keyCode != 0 -> 30
            buttonState != 0 -> 20
            isToolTypeDistinctive -> 10
            else -> 0
        }
}
