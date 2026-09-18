package com.stylusmemo.app.plugin.memorize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorizeSessionTest {

    private val host = object : MemorizeHost {
        override val pageWidthMm: Float = 210f
        override val pageHeightMm: Float = 297f
    }

    @Test
    fun antiAliasedFringeMatchesTargetInk() {
        // Pink is far from pure red by raw distance but lies on the red->white blend line.
        val red = 0xFFE53935.toInt()
        val pink = 0xFFF6C1BE.toInt()
        assertTrue(ColorMatcher.matches(pink, red, 40f))
        assertTrue(ColorMatcher.fringeDistanceArgb(pink, red) <= 40f)
        assertFalse(ColorMatcher.matches(0xFF43A047.toInt(), red, 40f))
    }

    @Test
    fun toleranceWidensColorMatch() {
        val session = MemorizeSessionImpl(host)
        val orange = 0xFFEF6C00.toInt()
        session.setColor(0xFFE53935.toInt())
        session.setTolerance(10f)
        assertFalse(session.colorMatches(orange))
        session.setTolerance(120f)
        assertTrue(session.colorMatches(orange))
    }

    @Test
    fun colorMatcherDistanceAndTolerance() {
        assertEquals(0f, ColorMatcher.distanceArgb(0xFFE53935.toInt(), 0xFFE53935.toInt()), 0.001f)
        assertTrue(ColorMatcher.matches(0xFFE53935.toInt(), 0xFFE53935.toInt(), 90f))
        assertFalse(ColorMatcher.matches(0xFF000000.toInt(), 0xFFE53935.toInt(), 90f))
        assertTrue(ColorMatcher.matches(0xFFE57373.toInt(), 0xFFE53935.toInt(), 90f))
    }

    @Test
    fun sessionHidesMatchingColorUnderSheetOnly() {
        val session = MemorizeSessionImpl(host)
        session.setColor(0xFFE53935.toInt())
        val sheet = session.state.sheetRectMm
        val inside = RectDataMm(
            sheet.leftMm + 1f, sheet.topMm + 1f, 10f, 10f,
        )
        val outside = RectDataMm(
            sheet.leftMm + sheet.widthMm + 5f, sheet.topMm + 5f, 10f, 10f,
        )
        assertTrue(session.contentHidden(inside, 0xFFE53935.toInt()))
        assertFalse(session.contentHidden(inside, 0xFF000000.toInt()))
        assertFalse(session.contentHidden(outside, 0xFFE53935.toInt()))
    }

    @Test
    fun moveSheetIsClampedToPage() {
        val session = MemorizeSessionImpl(host)
        session.moveSheet(-1000f, -1000f)
        val top = session.state.sheetRectMm
        assertEquals(0f, top.leftMm, 0.001f)
        assertEquals(0f, top.topMm, 0.001f)
        session.moveSheet(10000f, 10000f)
        val bottom = session.state.sheetRectMm
        assertEquals(210f, bottom.leftMm + bottom.widthMm, 0.01f)
        assertEquals(297f, bottom.topMm + bottom.heightMm, 0.01f)
    }

    @Test
    fun layoutRatioIsClampedAndListenerNotified() {
        val session = MemorizeSessionImpl(host)
        val seen = mutableListOf<MemorizeState>()
        session.listener = { seen.add(it) }
        session.setLayout(MemorizeLayout(MemorizePaneDirection.NOTE_RIGHT, 0.99f))
        val layout = session.state.layout
        assertEquals(MemorizePaneDirection.NOTE_RIGHT, layout.direction)
        assertEquals(MemorizeLayout.MAX_RATIO, layout.notePaneRatio, 0.001f)
        assertEquals(1, seen.size)
        session.end()
        session.setColor(0xFF000000.toInt())
        assertEquals(1, seen.size)
    }
}
