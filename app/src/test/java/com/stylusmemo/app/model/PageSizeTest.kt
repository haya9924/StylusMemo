package com.stylusmemo.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PageSizeTest {

    @Test
    fun `standard A4 portrait is 210x297`() {
        val s = PageSize.standard(PagePreset.A4, PageOrientation.PORTRAIT)
        assertNotNull(s)
        assertEquals(210f, s!!.widthMm, 0.1f)
        assertEquals(297f, s.heightMm, 0.1f)
    }

    @Test
    fun `A4 landscape swaps dimensions`() {
        val s = PageSize.standard(PagePreset.A4, PageOrientation.LANDSCAPE)
        assertNotNull(s)
        assertEquals(297f, s!!.widthMm, 0.1f)
        assertEquals(210f, s.heightMm, 0.1f)
    }

    @Test
    fun `screen fit and custom have no standard preset`() {
        assertNull(PageSize.standard(PagePreset.SCREEN_FIT, PageOrientation.PORTRAIT))
        assertNull(PageSize.standard(PagePreset.CUSTOM, PageOrientation.PORTRAIT))
    }

    @Test
    fun `custom clamps size to valid range`() {
        assertEquals(50f, PageSize.custom(1f, 297f).widthMm, 0.1f)
        assertEquals(2000f, PageSize.custom(9999f, 297f).widthMm, 0.1f)
    }

    @Test
    fun `pdf points convert to mm`() {
        val s = PageSize.fromPdfPointSize(595f, 842f)
        assertEquals(595f / 72f * 25.4f, s.widthMm, 0.1f)
        assertEquals(842f / 72f * 25.4f, s.heightMm, 0.1f)
    }
}
