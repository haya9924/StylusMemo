package com.stylusmemo.app.cli

import com.stylusmemo.app.model.BackgroundType
import com.stylusmemo.app.model.ImageBox
import com.stylusmemo.app.model.PageData
import com.stylusmemo.app.model.TextBox
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Renders a note page into a [BufferedImage] using Java2D, mirroring the app's editor output. */
object PageRenderer {

    const val MM_PER_INCH = 25.4f
    private const val MAX_BITMAP_DIM = 8192
    private const val MAX_PAGE_PIXELS = 24L * 1024 * 1024

    fun pagePixelDims(wMm: Float, hMm: Float, dpi: Float): Pair<Int, Int> {
        var w = (wMm / MM_PER_INCH * dpi).toInt().coerceIn(32, MAX_BITMAP_DIM)
        var h = (hMm / MM_PER_INCH * dpi).toInt().coerceIn(32, MAX_BITMAP_DIM)
        val px = w.toLong() * h
        if (px > MAX_PAGE_PIXELS) {
            val s = sqrt(MAX_PAGE_PIXELS.toDouble() / px)
            w = (w * s).toInt().coerceAtLeast(32)
            h = (h * s).toInt().coerceAtLeast(32)
        }
        return w to h
    }

    fun renderPage(
        page: PageData,
        strokes: List<DecodedStroke>,
        assetsDir: File?,
        dpi: Float,
    ): BufferedImage {
        val (wPx, hPx) = pagePixelDims(page.widthMm, page.heightMm, dpi)
        val img = BufferedImage(wPx, hPx, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color.WHITE
            g.fillRect(0, 0, wPx, hPx)
            val pxPerMm = wPx / page.widthMm
            drawBackground(g, page, wPx, hPx, pxPerMm, assetsDir)
            drawStrokes(g, strokes, pxPerMm)
            drawBoxes(g, page, pxPerMm, assetsDir)
        } finally {
            g.dispose()
        }
        return img
    }

    private fun drawBackground(
        g: Graphics2D,
        page: PageData,
        wPx: Int,
        hPx: Int,
        pxPerMm: Float,
        assetsDir: File?,
    ) {
        val bg = page.background
        val imgName = bg.backgroundImageName
        if (imgName != null && assetsDir != null) {
            val file = File(assetsDir, imgName)
            if (file.isFile) {
                val bmp = ImageIO.read(file)
                if (bmp != null) {
                    g.drawImage(bmp, 0, 0, wPx, hPx, null)
                    return
                }
            }
        }
        when (bg.type) {
            BackgroundType.BLANK -> {}
            BackgroundType.GRID -> {
                val minor = Color(bg.minorColorArgb.toInt(), true)
                val major = Color(bg.majorColorArgb.toInt(), true)
                val minorW = (bg.lineThicknessMm * pxPerMm).coerceAtLeast(1f)
                val majorW = (bg.lineThicknessMm * pxPerMm * 1.6f).coerceAtLeast(1.5f)
                val spacing = (bg.spacingMm * pxPerMm).coerceAtLeast(1f)
                var x = spacing
                var step = 1
                while (x < wPx) {
                    val p = if (bg.majorEvery > 1 && step % bg.majorEvery == 0) major else minor
                    val w = if (bg.majorEvery > 1 && step % bg.majorEvery == 0) majorW else minorW
                    g.stroke = BasicStroke(w)
                    g.color = p
                    g.drawLine(x.roundToInt(), 0, x.roundToInt(), hPx)
                    x += spacing; step++
                }
                x = spacing; step = 1
                while (x < hPx) {
                    val p = if (bg.majorEvery > 1 && step % bg.majorEvery == 0) major else minor
                    val w = if (bg.majorEvery > 1 && step % bg.majorEvery == 0) majorW else minorW
                    g.stroke = BasicStroke(w)
                    g.color = p
                    g.drawLine(0, x.roundToInt(), wPx, x.roundToInt())
                    x += spacing; step++
                }
            }
            BackgroundType.RULED -> {
                val line = Color(bg.ruledColorArgb.toInt(), true)
                val w = (bg.lineThicknessMm * pxPerMm).coerceAtLeast(1f)
                val spacing = (bg.spacingMm * pxPerMm).coerceAtLeast(1f)
                g.stroke = BasicStroke(w)
                g.color = line
                var y = spacing
                while (y < hPx) {
                    g.drawLine(0, y.roundToInt(), wPx, y.roundToInt())
                    y += spacing
                }
                g.color = Color(bg.marginColorArgb.toInt(), true)
                g.drawLine((bg.marginXMm * pxPerMm).roundToInt(), 0, (bg.marginXMm * pxPerMm).roundToInt(), hPx)
            }
            BackgroundType.DOT -> {
                g.color = Color(bg.dotColorArgb.toInt(), true)
                val spacing = (bg.spacingMm * pxPerMm).coerceAtLeast(2f)
                val radius = (0.15f * pxPerMm).coerceIn(1f, 2.5f)
                var y = spacing
                while (y < hPx) {
                    var x = spacing
                    while (x < wPx) {
                        g.fillOval(
                            (x - radius).roundToInt(),
                            (y - radius).roundToInt(),
                            (radius * 2).roundToInt(),
                            (radius * 2).roundToInt(),
                        )
                        x += spacing
                    }
                    y += spacing
                }
            }
        }
    }

    private fun drawStrokes(g: Graphics2D, strokes: List<DecodedStroke>, pxPerMm: Float) {
        for (s in strokes) {
            if (s.points.size < 1) continue
            val path = Path2D.Float()
            path.moveTo(s.points[0].x * pxPerMm, s.points[0].y * pxPerMm)
            for (i in 1 until s.points.size) {
                path.lineTo(s.points[i].x * pxPerMm, s.points[i].y * pxPerMm)
            }
            g.color = Color(s.colorArgb, true)
            g.stroke = BasicStroke((s.sizeMm * pxPerMm).coerceAtLeast(0.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(path)
        }
    }

    private fun drawBoxes(g: Graphics2D, page: PageData, pxPerMm: Float, assetsDir: File?) {
        val all = page.textBoxes.map { Box(it.zIndex, it.leftMm, it.topMm, it.widthMm, it.heightMm, it.rotationDeg, textBox = it, imageBox = null) } +
            page.imageBoxes.map { Box(it.zIndex, it.leftMm, it.topMm, it.widthMm, it.heightMm, it.rotationDeg, textBox = null, imageBox = it) }
        for (box in all.sortedBy { it.z }) {
            val g2 = g.create() as Graphics2D
            try {
                g2.translate((box.left * pxPerMm).toDouble(), (box.top * pxPerMm).toDouble())
                g2.rotate(Math.toRadians(box.rotation.toDouble()))
                val wPx = box.width * pxPerMm
                val hPx = box.height * pxPerMm
                val tb = box.textBox
                if (tb != null) {
                    drawTextBox(g2, tb, wPx, hPx, pxPerMm)
                } else {
                    box.imageBox?.let { drawImageBox(g2, it, wPx, hPx, assetsDir) }
                }
            } finally {
                g2.dispose()
            }
        }
    }

    private fun drawTextBox(g: Graphics2D, box: TextBox, wPx: Float, hPx: Float, pxPerMm: Float) {
        if (box.text.isBlank()) return
        val fontSize = (box.fontSizeMm * pxPerMm).toFloat().roundToInt().coerceAtLeast(1)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, fontSize)
        g.color = Color(box.colorArgb.toInt(), true)
        val fm = g.fontMetrics
        val maxWidth = wPx.toInt().coerceAtLeast(1)
        val maxLines = (hPx / fontSize).toInt().coerceAtLeast(1)
        val lines = wrapLines(box.text, fm, maxWidth)
        val shown = when {
            lines.size <= maxLines -> lines
            else -> lines.take(maxLines).toMutableList().also { list ->
                val last = list.removeAt(list.size - 1)
                list.add(ellipsize(last, fm, maxWidth))
            }
        }
        var y = fm.ascent
        for (line in shown) {
            g.drawString(line, 0, y)
            y += fm.height
        }
    }

    private fun drawImageBox(g: Graphics2D, box: ImageBox, wPx: Float, hPx: Float, assetsDir: File?) {
        if (assetsDir == null) return
        val file = File(assetsDir, box.assetName)
        if (!file.isFile) return
        val bmp = ImageIO.read(file) ?: return
        if (wPx <= 0 || hPx <= 0) return
        val scale = max(wPx / bmp.width, hPx / bmp.height)
        val sw = bmp.width * scale
        val sh = bmp.height * scale
        val sx = (wPx - sw) / 2f
        val sy = (hPx - sh) / 2f
        g.drawImage(
            bmp,
            sx.roundToInt(), sy.roundToInt(),
            (sx + sw).roundToInt(), (sy + sh).roundToInt(),
            0, 0, bmp.width, bmp.height,
            null,
        )
    }

    private fun wrapLines(text: String, fm: java.awt.FontMetrics, maxWidth: Int): List<String> {
        val out = ArrayList<String>()
        for (paragraph in text.split("\n")) {
            if (paragraph.isEmpty()) {
                out.add("")
                continue
            }
            var line = StringBuilder()
            for (word in paragraph.split(" ")) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && fm.stringWidth(candidate) > maxWidth) {
                    out.add(line.toString())
                    line = StringBuilder(word)
                } else {
                    line.append(if (line.isEmpty()) "" else " ").append(word)
                }
            }
            if (line.isNotEmpty()) out.add(line.toString())
        }
        return out
    }

    private fun ellipsize(line: String, fm: java.awt.FontMetrics, maxWidth: Int): String {
        var s = line
        while (s.isNotEmpty() && fm.stringWidth(s + "…") > maxWidth) {
            s = s.dropLast(1)
        }
        return s + "…"
    }

    private class Box(
        val z: Int,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val rotation: Float,
        val textBox: TextBox?,
        val imageBox: ImageBox?,
    )
}
