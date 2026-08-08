package com.stylusmemo.app.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import com.stylusmemo.app.model.BackgroundSpec
import com.stylusmemo.app.model.BackgroundType
import com.stylusmemo.app.model.Note

/**
 * Lightweight preview of a note page: white page, background template and committed strokes.
 * Used on the home screen list. Thumbnails intentionally omit image/PDF backgrounds.
 */
class NoteThumbnailView @JvmOverloads constructor(
    context: Context,
) : View(context) {

    private var note: Note? = null
    private var strokes: List<Stroke> = emptyList()
    private var pageIndex: Int = 0
    private val renderer = CanvasStrokeRenderer.create()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint().apply { color = Color.argb(40, 0, 0, 0) }
    private var thumbnail: Bitmap? = null

    fun setNote(note: Note, strokes: List<Stroke>, pageIndex: Int) {
        this.note = note
        this.strokes = strokes
        this.pageIndex = pageIndex
        thumbnail = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = note ?: return
        val page = n.pages.getOrNull(pageIndex) ?: return
        val pad = 4f * resources.displayMetrics.density
        val availW = (width - pad * 2f).coerceAtLeast(1f)
        val availH = (height - pad * 2f).coerceAtLeast(1f)
        val scale = minOf(availW / page.widthMm, availH / page.heightMm)
        val w = page.widthMm * scale
        val h = page.heightMm * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f

        canvas.drawRect(left + 2f, top + 3f, left + w + 2f, top + h + 3f, shadowPaint)
        canvas.drawRect(left, top, left + w, top + h, paint.apply { color = Color.WHITE })

        if (thumbnail == null) {
            renderThumbnail(page.widthMm, page.heightMm, page.background, (w * 2f).toInt().coerceAtLeast(64))
        }
        thumbnail?.let { canvas.drawBitmap(it, null, Rect(left.toInt(), top.toInt(), (left + w).toInt(), (top + h).toInt()), paint) }

        val m = Matrix().apply {
            setTranslate(left, top)
            preScale(scale, scale)
        }
        for (s in strokes) renderer.draw(canvas, s, m)
    }

    private fun renderThumbnail(wMm: Float, hMm: Float, bg: BackgroundSpec, maxDim: Int) {
        val scale = maxDim / maxOf(wMm, hMm)
        val w = (wMm * scale).toInt().coerceIn(32, 4096)
        val h = (hMm * scale).toInt().coerceIn(32, 4096)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        drawBackground(c, bg, wMm, hMm, w, h)
        thumbnail = bmp
    }

    private fun drawBackground(c: Canvas, bg: BackgroundSpec, wMm: Float, hMm: Float, wPx: Int, hPx: Int) {
        val scale = wPx / wMm
        when (bg.type) {
            BackgroundType.BLANK -> {}
            BackgroundType.GRID -> {
                val spacing = (bg.spacingMm * scale).coerceAtLeast(1f)
                val minor = Paint().apply { color = bg.minorColorArgb.toInt(); strokeWidth = 1f }
                val major = Paint().apply { color = bg.majorColorArgb.toInt(); strokeWidth = 1.5f }
                var x = spacing
                var step = 1
                while (x < wPx) {
                    c.drawLine(x, 0f, x, hPx.toFloat(), if (bg.majorEvery > 1 && step % bg.majorEvery == 0) major else minor)
                    x += spacing; step++
                }
                x = spacing; step = 1
                while (x < hPx) {
                    c.drawLine(0f, x, wPx.toFloat(), x, if (bg.majorEvery > 1 && step % bg.majorEvery == 0) major else minor)
                    x += spacing; step++
                }
            }
            BackgroundType.RULED -> {
                val spacing = (bg.spacingMm * scale).coerceAtLeast(1f)
                val line = Paint().apply { color = bg.ruledColorArgb.toInt(); strokeWidth = 1f }
                var y = spacing
                while (y < hPx) {
                    c.drawLine(0f, y, wPx.toFloat(), y, line)
                    y += spacing
                }
                c.drawLine(bg.marginXMm * scale, 0f, bg.marginXMm * scale, hPx.toFloat(), line)
            }
            BackgroundType.DOT -> {
                val spacing = (bg.spacingMm * scale).coerceAtLeast(2f)
                val dot = Paint().apply { color = bg.dotColorArgb.toInt() }
                var y = spacing
                while (y < hPx) {
                    var x = spacing
                    while (x < wPx) {
                        c.drawCircle(x, y, 0.8f, dot)
                        x += spacing
                    }
                    y += spacing
                }
            }
        }
    }
}
