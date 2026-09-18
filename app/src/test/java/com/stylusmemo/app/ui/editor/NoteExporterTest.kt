package com.stylusmemo.app.ui.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteExporterTest {

    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())

    private fun jpegWithDensity0(): ByteArray {
        // SOI + APP0 (JFIF, units=0) + SOS-ish data
        val app0 = byteArrayOf(
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0,
            1, 1,
            0, // units
            0, 0, // X density
            0, 0, // Y density
            0, 0, // thumbnail
        )
        val tail = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x01, 0x02, 0x03)
        return soi + app0 + tail
    }

    @Test
    fun `jpegWithDensity patches existing JFIF density`() {
        val out = NoteExporter.jpegWithDensity(jpegWithDensity0(), 300)
        assertArrayEquals(soi, out.copyOf(2))
        // JFIF payload: SOI(2) + marker(2) + len(2) → payload starts at 6
        assertEquals(1, out[6 + 7].toInt()) // units
        assertEquals(300, ((out[6 + 8].toInt() and 0xFF) shl 8) or (out[6 + 9].toInt() and 0xFF))
        assertEquals(300, ((out[6 + 10].toInt() and 0xFF) shl 8) or (out[6 + 11].toInt() and 0xFF))
        assertEquals(out.size, jpegWithDensity0().size)
    }

    @Test
    fun `jpegWithDensity injects JFIF segment when absent`() {
        val bare = soi + byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x01)
        val out = NoteExporter.jpegWithDensity(bare, 300)
        assertTrue(out.size > bare.size)
        assertArrayEquals(soi, out.copyOf(2))
        // marker E0 と JFIF 識別子
        assertEquals(0xFF.toByte(), out[2]); assertEquals(0xE0.toByte(), out[3])
        assertEquals('J'.code, out[6].toInt())
        assertEquals('F'.code, out[7].toInt())
        assertEquals(1, out[13].toInt()) // units @ payload offset 7 → file index 6+7=13
        assertEquals(300, ((out[14].toInt() and 0xFF) shl 8) or (out[15].toInt() and 0xFF))
        assertEquals(300, ((out[16].toInt() and 0xFF) shl 8) or (out[17].toInt() and 0xFF))
        // 末尾に元のデータが続く
        assertEquals(0xFF.toByte(), out[out.size - 3])
        assertEquals(0xDA.toByte(), out[out.size - 2])
        assertEquals(0x01, out[out.size - 1].toInt())
    }

    @Test
    fun `jpegWithDensity leaves invalid data untouched`() {
        val junk = byteArrayOf(0x01, 0x02, 0x03)
        assertArrayEquals(junk, NoteExporter.jpegWithDensity(junk, 300))
    }
}