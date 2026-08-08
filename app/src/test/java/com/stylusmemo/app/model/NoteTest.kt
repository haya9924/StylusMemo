package com.stylusmemo.app.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `note serializes and deserializes preserving fields`() {
        val bg = BackgroundSpec(
            type = BackgroundType.GRID,
            spacingMm = 4f,
            majorEvery = 5,
            minorColorArgb = 0xFFB0BEC5,
        )
        val note = Note(
            id = "n-test-1",
            title = "会議メモ",
            pages = listOf(
                PageData(widthMm = 210f, heightMm = 297f, background = bg),
                PageData(widthMm = 148f, heightMm = 210f, background = BackgroundSpec()),
            ),
        )
        val text = json.encodeToString(Note.serializer(), note)
        val decoded = json.decodeFromString(Note.serializer(), text)

        assertEquals(note.id, decoded.id)
        assertEquals(note.title, decoded.title)
        assertEquals(2, decoded.pages.size)
        assertEquals(210f, decoded.pages[0].widthMm, 0.001f)
        assertEquals(BackgroundType.GRID, decoded.pages[0].background.type)
        assertEquals(4f, decoded.pages[0].background.spacingMm, 0.001f)
        assertEquals(0xFFB0BEC5, decoded.pages[0].background.minorColorArgb)
        assertEquals(148f, decoded.pages[1].widthMm, 0.001f)
    }

    @Test
    fun `note with text and image boxes round trips`() {
        val page = PageData(
            textBoxes = listOf(
                TextBox(id = "t-1", text = "タイトル", fontSizeMm = 24f, colorArgb = 0xFFE53935),
            ),
            imageBoxes = listOf(
                ImageBox(id = "i-1", assetName = "asset-123.png", widthMm = 80f, heightMm = 60f),
            ),
        )
        val text = json.encodeToString(PageData.serializer(), page)
        val decoded = json.decodeFromString(PageData.serializer(), text)
        assertEquals("t-1", decoded.textBoxes[0].id)
        assertEquals("タイトル", decoded.textBoxes[0].text)
        assertEquals(0xFFE53935, decoded.textBoxes[0].colorArgb)
        assertEquals("asset-123.png", decoded.imageBoxes[0].assetName)
    }

    @Test
    fun `updatedAt changes after withUpdatedAt`() {
        val note = Note.new("A", 210f, 297f, BackgroundSpec())
        val updated = note.withUpdatedAt()
        assertTrue(updated.updatedAt >= note.updatedAt)
    }

    @Test
    fun `ids are unique`() {
        assertNotEquals(Note.new("a", 1f, 1f, BackgroundSpec()).id, Note.new("a", 1f, 1f, BackgroundSpec()).id)
    }
}
