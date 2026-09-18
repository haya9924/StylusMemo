package com.stylusmemo.app.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeCodecTest {

    @Test
    fun `encode then decode a batch reconstructs points`() {
        val points = listOf(
            InkPoint(12.34f, 5.67f, 0f, 0.5f),
            InkPoint(13.00f, 5.90f, 0.016f, 0.6f),
            InkPoint(14.12f, 6.55f, 0.033f, 0.7f),
            InkPoint(15.50f, 7.20f, 0.050f, 0.8f),
        )
        val payload = StrokeCodec.encodeBatch(points)
        val decoded = StrokeCodec.decodeBatch(payload)
        assertEquals(points.size, decoded.size)
        points.zip(decoded).forEach { (expected, actual) ->
            assertEquals(expected.x, actual.x, 0.02f)
            assertEquals(expected.y, actual.y, 0.02f)
            assertEquals(expected.tSeconds, actual.tSeconds, 0.002f)
            assertEquals(expected.pressure, actual.pressure, 0.02f)
        }
    }

    @Test
    fun `page file round-trip preserves brush properties`() {
        val strokes = listOf(
            DecodedStroke(
                colorArgb = 0xFF2E7D32.toInt(),
                sizeMm = 0.8f,
                epsilon = 0.1f,
                points = listOf(InkPoint(1f, 2f, 0f, 0f), InkPoint(3f, 4f, 0.01f, 0f)),
            ),
            DecodedStroke(
                colorArgb = 0xFF1A1A1A.toInt(),
                sizeMm = 0.5f,
                epsilon = 0.06f,
                points = listOf(InkPoint(10f, 20f, 0f, 0f)),
            ),
        )
        val bytes = StrokeCodec.encodePage(strokes)
        val decoded = StrokeCodec.decodePage(java.io.ByteArrayInputStream(bytes))
        assertEquals(2, decoded.size)
        assertEquals(0xFF2E7D32.toInt(), decoded[0].colorArgb)
        assertEquals(0.8f, decoded[0].sizeMm, 1e-6f)
        assertEquals(0.1f, decoded[0].epsilon, 1e-6f)
        assertEquals(2, decoded[0].points.size)
        assertEquals(0xFF1A1A1A.toInt(), decoded[1].colorArgb)
        assertEquals(0.5f, decoded[1].sizeMm, 1e-6f)
    }

    @Test
    fun `empty batch is empty after round-trip`() {
        val payload = StrokeCodec.encodeBatch(emptyList())
        assertTrue(StrokeCodec.decodeBatch(payload).isEmpty())
    }

    @Test
    fun `decoder handles unpacked deltas wire format`() {
        // Hand-build a CodedStrokeInputBatch with x (absolute scaled values: 100,102,105)
        // as UNPACKED sint32 deltas (deltas of quantized counts: 100, 2, 3), scale float 0.01.
        val body = java.io.ByteArrayOutputStream()
        // x run: field1 (numeric run) wiretype 2
        val run = java.io.ByteArrayOutputStream()
        run.write(0x08); run.write(0xC8); run.write(0x01) // tag,varint for delta 100 -> zigzag 200
        run.write(0x08); run.write(0x04)                  // delta 2 -> zigzag 4
        run.write(0x08); run.write(0x06)                  // delta 3 -> zigzag 6
        run.write(0x15)                                   // tag field2 wiretype5
        run.write(0x0A); run.write(0xD7); run.write(0x23); run.write(0x3C) // float 0.01
        body.write(0x0A)                                  // field1 wiretype2
        val runBytes = run.toByteArray()
        writeVarint(body, runBytes.size)
        body.write(runBytes)
        // y run empty, no fields -> just include to ensure decode uses x only? decoder returns min(nx,ny)? 
        // We only set x, so ys empty -> decodeBatch returns emptyList. To get points we need both.
        // Add y run: deltas 50, 1, 1 (values 0.50,0.51,0.52)
        val yrun = java.io.ByteArrayOutputStream()
        yrun.write(0x08); yrun.write(0x64) // 50 -> zigzag 100
        yrun.write(0x08); yrun.write(0x02) // 1 -> zigzag 2
        yrun.write(0x08); yrun.write(0x02) // 1 -> zigzag 2
        yrun.write(0x15)
        yrun.write(0x0A); yrun.write(0xD7); yrun.write(0x23); yrun.write(0x3C)
        body.write(0x12) // field2 wiretype2
        val yrunBytes = yrun.toByteArray()
        writeVarint(body, yrunBytes.size)
        body.write(yrunBytes)

        val raw = body.toByteArray()
        val gz = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(gz).use { it.write(raw) }
        val points = StrokeCodec.decodeBatch(gz.toByteArray())
        assertEquals(3, points.size)
        assertEquals(1.00f, points[0].x, 0.01f)
        assertEquals(1.02f, points[1].x, 0.01f)
        assertEquals(1.05f, points[2].x, 0.01f)
        assertEquals(0.50f, points[0].y, 0.01f)
        assertEquals(0.51f, points[1].y, 0.01f)
        assertEquals(0.52f, points[2].y, 0.01f)
    }

    private fun writeVarint(out: java.io.ByteArrayOutputStream, value: Int) {
        var v = value.toLong() and 0xFFFFFFFFL
        while (v and 0xFFFFFF80L != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}