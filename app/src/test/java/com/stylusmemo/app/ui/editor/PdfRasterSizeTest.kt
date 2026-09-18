package com.stylusmemo.app.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfRasterSizeTest {

    @Test
    fun `letter uses 300 dpi without downscaling`() {
        val size = pdfRasterSize(612, 792)
        assertEquals(2550, size.width)
        assertEquals(3300, size.height)
    }

    @Test
    fun `a4 keeps 300 dpi within pixel rounding`() {
        val size = pdfRasterSize(595, 842)
        assertEquals(2479, size.width)
        assertEquals(3508, size.height)
    }

    @Test
    fun `large square respects pixel cap`() {
        val size = pdfRasterSize(14400, 14400)
        assertEquals(3162, size.width)
        assertEquals(size.width, size.height)
        assertTrue(size.width.toLong() * size.height <= 10_000_000L)
    }

    @Test
    fun `wide page uses one scale for both dimensions`() {
        val size = pdfRasterSize(14400, 720)
        assertEquals(4096, size.width)
        assertEquals(204, size.height)
        assertTrue(kotlin.math.abs(size.width / 20.0 - size.height) < 1.0)
    }

    @Test
    fun `landscape transposes portrait dimensions`() {
        val portrait = pdfRasterSize(842, 1191)
        val landscape = pdfRasterSize(1191, 842)
        assertEquals(portrait.width, landscape.height)
        assertEquals(portrait.height, landscape.width)
    }

    @Test
    fun `extreme dimensions stay positive and bounded without overflow`() {
        for ((width, height) in listOf(
            Int.MAX_VALUE to Int.MAX_VALUE,
            Int.MAX_VALUE to 1,
            1 to Int.MAX_VALUE,
            1 to 1,
            842 to 1191,
        )) {
            val size = pdfRasterSize(width, height)
            assertTrue(size.width in 1..4096)
            assertTrue(size.height in 1..4096)
            assertTrue(size.width.toLong() * size.height <= 10_000_000L)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero width is rejected`() {
        pdfRasterSize(0, 792)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative height is rejected`() {
        pdfRasterSize(612, -1)
    }
}
