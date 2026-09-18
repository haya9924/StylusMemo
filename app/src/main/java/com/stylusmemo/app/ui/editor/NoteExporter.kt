package com.stylusmemo.app.ui.editor

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.ink.strokes.Stroke
import com.stylusmemo.app.model.BackgroundType
import com.stylusmemo.app.model.ImageBox
import com.stylusmemo.app.model.Note
import com.stylusmemo.app.model.PageData
import com.stylusmemo.app.model.TextBox
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Renders note pages and exports them as JPEG or PDF.
 *
 * Each page is rasterized at [EXPORT_DPI] with the true Ink renderer (same fidelity as the
 * thumbnails), then written out. The PDF embeds those page rasters as JPEG image XObjects, so
 * output quality is far better than the ~72 dpi produced by android.graphics.pdf.PdfDocument.
 */
object NoteExporter {

    private const val MM_PER_INCH = 25.4f
    private const val POINTS_PER_INCH = 72f
    private const val EXPORT_DPI = 300f
    private const val JPEG_QUALITY = 92
    private const val MAX_BITMAP_DIM = 8192
    private const val MAX_PAGE_PIXELS = 24L * 1024 * 1024

    /** Exports the page at [pageIndex] of [note] as a JPEG written to [uri]. */
    suspend fun exportJpeg(
        resolver: ContentResolver,
        uri: Uri,
        note: Note,
        pageIndex: Int,
        strokes: List<List<Stroke>>,
        assetLoader: suspend (String) -> Bitmap?,
    ): Boolean {
        val page = note.pages.getOrNull(pageIndex) ?: return false
        val bmp = renderPage(page, strokes.getOrNull(pageIndex).orEmpty(), assetLoader) ?: return false
        val out = ByteArrayOutputStream()
        val ok = bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bmp.recycle()
        if (!ok) return false
        return writeBytes(resolver, uri, jpegWithDensity(out.toByteArray(), EXPORT_DPI.toInt()))
    }

    /** Exports all pages of [note] as a single PDF written to [uri]. */
    suspend fun exportPdf(
        resolver: ContentResolver,
        uri: Uri,
        note: Note,
        strokes: List<List<Stroke>>,
        assetLoader: suspend (String) -> Bitmap?,
    ): Boolean {
        val pages = note.pages
        if (pages.isEmpty()) return false

        val baos = ByteArrayOutputStream()
        // PDF header: readers reject the document when these bytes are missing.
        baos.write("%PDF-1.4\n".toByteArray())
        baos.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
        val objOffsets = mutableMapOf<Int, Long>()
        fun obj(num: Int, body: String) {
            objOffsets[num] = baos.size().toLong()
            baos.write("$num 0 obj\n".toByteArray())
            baos.write(body.toByteArray())
            baos.write("\nendobj\n".toByteArray())
        }

        val n = pages.size
        val kids = (0 until n).joinToString(" ") { "${3 + it * 3} 0 R" }
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Kids [$kids] /Count $n >>")

        for (i in 0 until n) {
            val pageObj = 3 + i * 3
            val imgObj = 4 + i * 3
            val contentObj = 5 + i * 3
            val page = pages[i]
            val wPts = page.widthMm / MM_PER_INCH * POINTS_PER_INCH
            val hPts = page.heightMm / MM_PER_INCH * POINTS_PER_INCH

            obj(
                pageObj,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(wPts)} ${f(hPts)}] " +
                    "/Resources << /XObject << /Im$i $imgObj 0 R >> >> /Contents $contentObj 0 R >>",
            )

            val bmp = renderPage(page, strokes.getOrNull(i).orEmpty(), assetLoader) ?: return false
            val bmpW = bmp.width
            val bmpH = bmp.height
            val jpeg = ByteArrayOutputStream()
            val ok = bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg)
            val jpegBytes = jpeg.toByteArray()
            bmp.recycle()
            if (!ok) return false

            objOffsets[imgObj] = baos.size().toLong()
            baos.write("$imgObj 0 obj\n".toByteArray())
            baos.write(
                ("<< /Type /XObject /Subtype /Image /Width $bmpW /Height $bmpH " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode " +
                    "/Length ${jpegBytes.size} >>\nstream\n").toByteArray(),
            )
            baos.write(jpegBytes)
            baos.write("\nendstream\nendobj\n".toByteArray())

            val content = "q\n${f(wPts)} 0 0 ${f(hPts)} 0 0 cm\n/Im$i Do\nQ\n"
            obj(contentObj, "<< /Length ${content.toByteArray().size} >>\nstream\n${content}endstream")
        }

        val xrefPos = baos.size().toLong()
        val total = 2 + n * 3
        val sb = StringBuilder()
        sb.append("xref\n0 ${total + 1}\n")
        sb.append("0000000000 65535 f \n")
        for (num in 1..total) {
            val off = objOffsets[num] ?: 0L
            sb.append(String.format(Locale.US, "%010d 00000 n \n", off))
        }
        sb.append("trailer\n<< /Size ${total + 1} /Root 1 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")
        baos.write(sb.toString().toByteArray())

        return writeBytes(resolver, uri, baos.toByteArray())
    }

    private fun f(v: Float) = String.format(Locale.US, "%.2f", v)

    /**
     * Sets the JFIF density (units = dots per inch) of a JPEG produced by
     * `Bitmap.compress`, which writes no density by default. Viewers honour this so the
     * image renders at its true physical size (e.g. A4 at 300 dpi) instead of being treated
     * as ~96 dpi and displayed several times too large / small.
     */
    internal fun jpegWithDensity(jpeg: ByteArray, dpi: Int): ByteArray {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return jpeg

        var pos = 2
        var app0Pos = -1
        var app0Len = 0
        while (pos + 3 < jpeg.size) {
            if (jpeg[pos] != 0xFF.toByte()) return jpeg
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break // SOS / EOI
            if (marker == 0x01 || (marker in 0xD0..0xD7)) { pos += 2; continue }
            val len = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            if (len < 2) return jpeg
            if (marker == 0xE0 && app0Pos < 0) { app0Pos = pos; app0Len = len }
            pos += 2 + len
        }

        val patchable = app0Pos >= 0 &&
            app0Len - 2 >= 12 &&
            jpeg[app0Pos + 4].toInt() == 'J'.code &&
            jpeg[app0Pos + 5].toInt() == 'F'.code &&
            jpeg[app0Pos + 6].toInt() == 'I'.code &&
            jpeg[app0Pos + 7].toInt() == 'F'.code

        if (patchable) {
            val out = jpeg.copyOf()
            out[app0Pos + 4 + 7] = 1 // units: dots per inch
            out[app0Pos + 4 + 8] = (dpi shr 8).toByte()
            out[app0Pos + 4 + 9] = (dpi and 0xFF).toByte()
            out[app0Pos + 4 + 10] = (dpi shr 8).toByte()
            out[app0Pos + 4 + 11] = (dpi and 0xFF).toByte()
            return out
        }

        // No usable APP0: inject a JFIF segment directly after the SOI marker.
        val payload = byteArrayOf(
            'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0,
            1, 1, // JFIF version 1.01
            1, // units: dots per inch
            (dpi shr 8).toByte(), (dpi and 0xFF).toByte(),
            (dpi shr 8).toByte(), (dpi and 0xFF).toByte(),
            0, 0, // no thumbnail
        )
        val segLen = payload.size + 2
        val seg = byteArrayOf(
            0xFF.toByte(), 0xE0.toByte(),
            (segLen shr 8).toByte(), (segLen and 0xFF).toByte(),
        )
        val out = ByteArray(jpeg.size + seg.size + payload.size)
        System.arraycopy(jpeg, 0, out, 0, 2) // SOI
        System.arraycopy(seg, 0, out, 2, seg.size)
        System.arraycopy(payload, 0, out, 2 + seg.size, payload.size)
        System.arraycopy(jpeg, 2, out, 2 + seg.size + payload.size, jpeg.size - 2)
        return out
    }

    /**
     * Renders page [pageIndex] as PNG bytes for plugin consumers (e.g. OCR / AI
     * transcription), scaled so the larger side is at most [maxDimPx] pixels.
     */
    suspend fun renderPagePng(
        page: PageData,
        strokes: List<Stroke>,
        assetLoader: suspend (String) -> Bitmap?,
        maxDimPx: Int = 1600,
    ): ByteArray? {
        val (wPx, hPx) = pagePixelDims(page.widthMm, page.heightMm)
        val bmp = renderPage(page, strokes, assetLoader) ?: return null
        val scale = if (maxDimPx > 0 && max(wPx, hPx) > maxDimPx) {
            maxDimPx.toFloat() / max(wPx, hPx)
        } else 1f
        val out = ByteArrayOutputStream()
        if (scale >= 1f) {
            val ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            return if (ok) out.toByteArray() else null
        }
        val sw = (wPx * scale).toInt().coerceAtLeast(1)
        val sh = (hPx * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        bmp.recycle()
        val ok = scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        scaled.recycle()
        return if (ok) out.toByteArray() else null
    }

    private suspend fun renderPage(
        page: PageData,
        strokes: List<Stroke>,
        assetLoader: suspend (String) -> Bitmap?,
    ): Bitmap? {
        val (wPx, hPx) = pagePixelDims(page.widthMm, page.heightMm)
        val bmp = allocateBitmap(wPx, hPx) ?: return null
        val canvas = Canvas(bmp)
        PageRenderer(assetLoader).draw(canvas, page, strokes, wPx, hPx)
        return bmp
    }

    private fun pagePixelDims(wMm: Float, hMm: Float): Pair<Int, Int> {
        var w = (wMm / MM_PER_INCH * EXPORT_DPI).toInt().coerceIn(32, MAX_BITMAP_DIM)
        var h = (hMm / MM_PER_INCH * EXPORT_DPI).toInt().coerceIn(32, MAX_BITMAP_DIM)
        val px = w.toLong() * h
        if (px > MAX_PAGE_PIXELS) {
            val s = sqrt(MAX_PAGE_PIXELS.toDouble() / px)
            w = (w * s).toInt().coerceAtLeast(32)
            h = (h * s).toInt().coerceAtLeast(32)
        }
        return w to h
    }

    /** Allocates a bitmap, halving dimensions on OOM so large pages never crash export. */
    private fun allocateBitmap(wPx: Int, hPx: Int): Bitmap? {
        var w = wPx
        var h = hPx
        while (w >= 128 && h >= 128) {
            try {
                return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                w = (w / 2).coerceAtLeast(32)
                h = (h / 2).coerceAtLeast(32)
            }
        }
        return null
    }

    private suspend fun writeBytes(resolver: ContentResolver, uri: Uri, bytes: ByteArray): Boolean =
        try {
            val os = resolver.openOutputStream(uri) ?: return false
            os.use { it.write(bytes) }
            true
        } catch (t: Throwable) {
            false
        }
}

/** Describes a text or image box placed on a page, in mm. */
private class Placed(
    val z: Int,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val rotation: Float,
    val textBox: TextBox?,
    val imageBox: ImageBox?,
)

/** Draws a single page onto [Canvas] at a given px-per-mm scale, mirroring the editor rendering. */
private class PageRenderer(private val assetLoader: suspend (String) -> Bitmap?) {

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val toBitmap = Matrix()
    private val path = Path()
    private val point = FloatArray(2)

    suspend fun draw(canvas: Canvas, page: PageData, strokes: List<Stroke>, wPx: Int, hPx: Int) {
        val pxPerMm = wPx / page.widthMm
        canvas.drawColor(Color.WHITE)
        drawBackground(canvas, page, wPx, hPx, pxPerMm)
        drawStrokes(canvas, strokes, pxPerMm)
        drawBoxes(canvas, page, pxPerMm)
    }

    private fun drawStrokes(canvas: Canvas, strokes: List<Stroke>, pxPerMm: Float) {
        toBitmap.setScale(pxPerMm, pxPerMm)
        for (s in strokes) {
            val inputs = s.inputs
            if (inputs.size < 1) continue
            strokePaint.color = s.brush.colorIntArgb
            strokePaint.strokeWidth = (s.brush.size * pxPerMm).coerceAtLeast(1f)
            path.reset()
            val p0 = inputs.get(0)
            point[0] = p0.x
            point[1] = p0.y
            toBitmap.mapPoints(point)
            path.moveTo(point[0], point[1])
            for (k in 1 until inputs.size) {
                val pi = inputs.get(k)
                point[0] = pi.x
                point[1] = pi.y
                toBitmap.mapPoints(point)
                path.lineTo(point[0], point[1])
            }
            canvas.drawPath(path, strokePaint)
        }
    }

    private suspend fun drawBackground(canvas: Canvas, page: PageData, wPx: Int, hPx: Int, pxPerMm: Float) {
        val bg = page.background
        val imgName = bg.backgroundImageName
        if (imgName != null) {
            val bmp = assetLoader(imgName)
            if (bmp != null) {
                canvas.drawBitmap(bmp, null, Rect(0, 0, wPx, hPx), imagePaint)
                return
            }
        }
        when (bg.type) {
            BackgroundType.BLANK -> {}
            BackgroundType.GRID -> {
                val minor = Paint().apply {
                    color = bg.minorColorArgb.toInt()
                    strokeWidth = (bg.lineThicknessMm * pxPerMm).coerceAtLeast(1f)
                }
                val major = Paint().apply {
                    color = bg.majorColorArgb.toInt()
                    strokeWidth = (bg.lineThicknessMm * pxPerMm * 1.6f).coerceAtLeast(1.5f)
                }
                val spacing = (bg.spacingMm * pxPerMm).coerceAtLeast(1f)
                var x = spacing
                var step = 1
                while (x < wPx) {
                    val p = if (bg.majorEvery > 1 && step % bg.majorEvery == 0) major else minor
                    canvas.drawLine(x, 0f, x, hPx.toFloat(), p)
                    x += spacing; step++
                }
                x = spacing; step = 1
                while (x < hPx) {
                    val p = if (bg.majorEvery > 1 && step % bg.majorEvery == 0) major else minor
                    canvas.drawLine(0f, x, wPx.toFloat(), x, p)
                    x += spacing; step++
                }
            }
            BackgroundType.RULED -> {
                val line = Paint().apply {
                    color = bg.ruledColorArgb.toInt()
                    strokeWidth = (bg.lineThicknessMm * pxPerMm).coerceAtLeast(1f)
                }
                val spacing = (bg.spacingMm * pxPerMm).coerceAtLeast(1f)
                var y = spacing
                while (y < hPx) {
                    canvas.drawLine(0f, y, wPx.toFloat(), y, line)
                    y += spacing
                }
                val margin = Paint().apply {
                    color = bg.marginColorArgb.toInt()
                    strokeWidth = (bg.lineThicknessMm * pxPerMm).coerceAtLeast(1f)
                }
                if (bg.showMargin) {
                    canvas.drawLine(bg.marginXMm * pxPerMm, 0f, bg.marginXMm * pxPerMm, hPx.toFloat(), margin)
                }
            }
            BackgroundType.DOT -> {
                val dot = Paint().apply {
                    color = bg.dotColorArgb.toInt()
                    style = Paint.Style.FILL
                }
                val spacing = (bg.spacingMm * pxPerMm).coerceAtLeast(2f)
                val radius = (0.15f * pxPerMm).coerceIn(1f, 2.5f)
                var y = spacing
                while (y < hPx) {
                    var x = spacing
                    while (x < wPx) {
                        canvas.drawCircle(x, y, radius, dot)
                        x += spacing
                    }
                    y += spacing
                }
            }
        }
    }

    private suspend fun drawBoxes(canvas: Canvas, page: PageData, pxPerMm: Float) {
        val all = page.textBoxes.map {
            Placed(it.zIndex, it.leftMm, it.topMm, it.widthMm, it.heightMm, it.rotationDeg, it, null)
        } + page.imageBoxes.map {
            Placed(it.zIndex, it.leftMm, it.topMm, it.widthMm, it.heightMm, it.rotationDeg, null, it)
        }
        for (sel in all.sortedBy { it.z }) {
            canvas.save()
            canvas.translate(sel.left * pxPerMm, sel.top * pxPerMm)
            canvas.rotate(sel.rotation)
            val wPx = sel.width * pxPerMm
            val hPx = sel.height * pxPerMm
            val tb = sel.textBox
            if (tb != null) {
                drawTextBox(canvas, tb, wPx, hPx, pxPerMm)
            } else {
                sel.imageBox?.let { drawImageBox(canvas, it, wPx, hPx) }
            }
            canvas.restore()
        }
    }

    private fun drawTextBox(canvas: Canvas, box: TextBox, wPx: Float, hPx: Float, pxPerMm: Float) {
        if (box.text.isBlank()) return
        textPaint.color = box.colorArgb.toInt()
        textPaint.textSize = box.fontSizeMm * pxPerMm
        val layout = StaticLayout.Builder
            .obtain(box.text, 0, box.text.length, textPaint, wPx.toInt().coerceAtLeast(1))
            .setMaxLines((hPx / textPaint.textSize).toInt().coerceAtLeast(1))
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        layout.draw(canvas)
    }

    private suspend fun drawImageBox(canvas: Canvas, box: ImageBox, wPx: Float, hPx: Float) {
        val bmp = assetLoader(box.assetName) ?: return
        if (wPx <= 0 || hPx <= 0) return
        val scale = max(wPx / bmp.width, hPx / bmp.height)
        val sw = bmp.width * scale
        val sh = bmp.height * scale
        val sx = (wPx - sw) / 2f
        val sy = (hPx - sh) / 2f
        canvas.drawBitmap(bmp, null, RectF(sx, sy, sx + sw, sy + sh), imagePaint)
    }
}
