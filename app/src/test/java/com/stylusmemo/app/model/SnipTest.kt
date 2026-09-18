package com.stylusmemo.app.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnipTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `snip serializes round trip with defaults`() {
        val snip = Snip(assetName = "a-1.png", widthPx = 1400, heightPx = 900)
        val text = json.encodeToString(Snip.serializer(), snip)
        val decoded = json.decodeFromString(Snip.serializer(), text)
        assertEquals("a-1.png", decoded.assetName)
        assertEquals(1400, decoded.widthPx)
        assertEquals(900, decoded.heightPx)
        assertEquals(160f, decoded.displayHeightDp, 0.001f)
        assertFalse(decoded.collapsed)
        assertTrue(decoded.id.isNotEmpty())
    }

    @Test
    fun `note with snips round trips and default notes keep empty snips`() {
        val note = Note.new("s", 100f, 100f, BackgroundSpec()).copy(
            snips = listOf(Snip(assetName = "a-2.png", widthPx = 640, heightPx = 480, collapsed = true)),
        )
        val text = json.encodeToString(Note.serializer(), note)
        val decoded = json.decodeFromString(Note.serializer(), text)
        assertEquals(1, decoded.snips.size)
        assertTrue(decoded.snips[0].collapsed)
        assertEquals(0, json.decodeFromString(Note.serializer(), "{\"id\":\"n-x\"}").snips.size)
    }

    @Test
    fun `crop normalizes corners and clamps to page`() {
        val crop = SnipCrop.create(200f, 300f, 190f, -10f, 110f, 120f)!!
        assertEquals(110f, crop.leftMm, 0.001f)
        assertEquals(0f, crop.topMm, 0.001f)
        assertEquals(190f, crop.rightMm, 0.001f)
        assertEquals(120f, crop.bottomMm, 0.001f)
        assertTrue(crop.widthPx * crop.heightPx.toLong() <= SnipCrop.MAX_PIXELS)
    }

    @Test
    fun `crop bounds pixel budget at 300dpi`() {
        val crop = SnipCrop.create(10000f, 10000f, 0f, 0f, 10000f, 10000f)!!
        assertTrue(crop.widthPx.toLong() * crop.heightPx <= SnipCrop.MAX_PIXELS)
        val wPerMm = crop.widthPx / (crop.rightMm - crop.leftMm)
        val hPerMm = crop.heightPx / (crop.bottomMm - crop.topMm)
        assertEquals(wPerMm, hPerMm, 0.001f)
        assertEquals(kotlin.math.sqrt(SnipCrop.MAX_PIXELS.toDouble() / (10000.0 * 10000.0)), wPerMm.toDouble(), 0.01)
        val small = SnipCrop.create(50f, 50f, 0f, 0f, 25f, 25f)!!
        assertEquals(300f / 25.4f, small.widthPx / (small.rightMm - small.leftMm), 0.5f)
    }

    @Test
    fun `elongated crops apply dimension cap uniformly before rounding`() {
        val wide = SnipCrop.create(10000f, 10f, 0f, 0f, 10000f, 10f)!!
        val tall = SnipCrop.create(10f, 10000f, 0f, 0f, 10f, 10000f)!!
        assertEquals(8192, wide.widthPx)
        assertEquals(8, wide.heightPx)
        assertEquals(8, tall.widthPx)
        assertEquals(8192, tall.heightPx)
        assertEquals(0.8192, wide.pixelsPerMm, 0.000001)
        assertEquals(wide.pixelsPerMm, tall.pixelsPerMm, 0.000001)
    }

    @Test
    fun `crop dimensions and density respect all limits`() {
        for ((w, h) in listOf(25f to 25f, 10000f to 10000f, 10000f to 10f, 10f to 10000f, Float.MAX_VALUE to 1f)) {
            val crop = SnipCrop.create(w, h, 0f, 0f, w, h)!!
            assertTrue(crop.widthPx in 1..SnipCrop.MAX_DIMENSION)
            assertTrue(crop.heightPx in 1..SnipCrop.MAX_DIMENSION)
            assertTrue(crop.widthPx.toLong() * crop.heightPx <= SnipCrop.MAX_PIXELS)
            assertTrue(crop.pixelsPerMm > 0.0 && crop.pixelsPerMm <= 300.0 / 25.4)
            assertEquals(kotlin.math.round(w.toDouble() * crop.pixelsPerMm).toInt().coerceAtLeast(1), crop.widthPx)
            assertEquals(kotlin.math.round(h.toDouble() * crop.pixelsPerMm).toInt().coerceAtLeast(1), crop.heightPx)
        }
    }

    @Test
    fun `crop density is independent of position and corner order`() {
        val origin = SnipCrop.create(20000f, 100f, 0f, 0f, 10000f, 10f)!!
        val moved = SnipCrop.create(20000f, 100f, 15000f, 30f, 5000f, 20f)!!
        assertEquals(origin.widthPx, moved.widthPx)
        assertEquals(origin.heightPx, moved.heightPx)
        assertEquals(origin.pixelsPerMm, moved.pixelsPerMm, 0.0)
    }

    @Test
    fun `tiny crop under one mm is rejected`() {
        assertNull(SnipCrop.create(100f, 100f, 10f, 10f, 10.9f, 10.9f))
        assertNull(SnipCrop.create(100f, 100f, 10f, 10f, 10f, 11f))
        assertNull(SnipCrop.create(100f, 100f, 110f, 10f, 120f, 20f))
        assertNull(SnipCrop.create(-1f, 100f, 0f, 0f, 5f, 5f))
        assertNull(SnipCrop.create(100f, 100f, Float.NaN, 0f, 5f, 5f))
    }

    @Test
    fun `valid small crop is accepted`() {
        val crop = SnipCrop.create(100f, 100f, 5f, 5f, 25f, 15f)
        assertTrue(crop != null)
        crop!!
        assertEquals(20f, crop.rightMm - crop.leftMm, 0.001f)
        assertTrue(crop.widthPx >= 1 && crop.heightPx >= 1)
    }
}
