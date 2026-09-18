package com.stylusmemo.app.cli

import com.stylusmemo.app.model.BackgroundSpec
import com.stylusmemo.app.model.BackgroundType
import com.stylusmemo.app.model.ImageBox
import com.stylusmemo.app.model.Note
import com.stylusmemo.app.model.TextBox
import kotlinx.serialization.json.Json
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generates a synthetic note in the exact on-disk format the app writes (note.json +
 * page-<n>.bin + assets), so the CLI can be exercised without pulling data off a device.
 */
object SampleGenerator {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun generate(targetDir: File, title: String, dirName: String?) {
        val noteId = dirName ?: "n-sample-${System.currentTimeMillis().toString().takeLast(8)}"
        val noteDir = File(targetDir, noteId)
        noteDir.mkdirs()

        val page1 = com.stylusmemo.app.model.PageData(
            widthMm = 210f,
            heightMm = 297f,
            background = BackgroundSpec(type = BackgroundType.GRID, spacingMm = 5f),
            textBoxes = listOf(
                TextBox(
                    text = "Sample note\n手書きメモのサンプルです",
                    fontSizeMm = 16f,
                    colorArgb = 0xFF222222,
                    leftMm = 30f,
                    topMm = 40f,
                    widthMm = 120f,
                    heightMm = 40f,
                ),
            ),
            imageBoxes = listOf(
                ImageBox(
                    assetName = "asset-sample.png",
                    leftMm = 60f,
                    topMm = 150f,
                    widthMm = 70f,
                    heightMm = 70f,
                    zIndex = 1,
                ),
            ),
        )
        val page2 = com.stylusmemo.app.model.PageData(
            widthMm = 210f,
            heightMm = 297f,
            background = BackgroundSpec(type = BackgroundType.RULED, spacingMm = 8f),
        )
        val note = Note(id = noteId, title = title, pages = listOf(page1, page2))

        noteDir.resolve("note.json").writeText(json.encodeToString(Note.serializer(), note))

        val assets = File(noteDir, "assets")
        assets.mkdirs()
        ImageIO.write(sampleImage(), "png", File(assets, "asset-sample.png"))

        noteDir.resolve("page-0.bin").writeBytes(StrokeCodec.encodePage(sampleStrokes1()))
        noteDir.resolve("page-1.bin").writeBytes(StrokeCodec.encodePage(sampleStrokes2()))

        println("Generated sample note in $noteDir")
        println("  id    : $noteId")
        println("  title : $title")
    }

    private fun sampleStrokes1(): List<DecodedStroke> {
        val ink = 0xFF1A1A1A.toInt()
        val strokes = ArrayList<DecodedStroke>()
        // Sine wave
        strokes += DecodedStroke(ink, 0.6f, 0.08f, (0..160).map { i ->
            val x = 25f + i * 0.9f
            val y = 70f + 18f * sin(x / 12.0).toFloat()
            InkPoint(x, y, i * 0.008f, 0.5f)
        })
        // Circle
        strokes += DecodedStroke(0xFF2E7D32.toInt(), 0.8f, 0.1f, (0..90).map { i ->
            val a = i / 90.0 * 2.0 * Math.PI
            InkPoint(
                (125f + 40f * cos(a)).toFloat(),
                (120f + 40f * sin(a)).toFloat(),
                i * 0.008f,
                0.6f,
            )
        })
        return strokes
    }

    private fun sampleStrokes2(): List<DecodedStroke> {
        // Zigzag underline strokes spanning the page width on a ruled background.
        val ink = 0xFF1565C0.toInt()
        return listOf(
            DecodedStroke(ink, 0.5f, 0.06f, (0..140).map { i ->
                val t = i / 140f
                val x = 30f + t * 150f
                val y = 90f + 6f * sin(t * 30.0).toFloat()
                InkPoint(x, y, i * 0.008f, 0.5f)
            }),
        )
    }

    private fun sampleImage(): BufferedImage {
        val img = BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics() as Graphics2D
        g.paint = GradientPaint(0f, 0f, Color(0xFF90CAF9.toInt()), 600f, 600f, Color(0xFFE3F2FD.toInt()))
        g.fillRect(0, 0, 600, 600)
        g.color = Color(0xFF1565C0.toInt())
        g.fillOval(150, 150, 300, 300)
        g.dispose()
        return img
    }
}