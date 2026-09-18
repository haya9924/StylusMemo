package com.stylusmemo.app.model

import kotlinx.serialization.Serializable

/** Serialized on-disk representation of a note. Strokes are stored in per-page binary files. */
@Serializable
data class Note(
    val id: String = newId("n"),
    val title: String = "新しいメモ",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val folder: String = "",
    val lastPageIndex: Int = 0,
    val pages: List<PageData> = listOf(PageData()),
    val snips: List<Snip> = emptyList(),
) {
    fun withUpdatedAt(): Note = copy(updatedAt = System.currentTimeMillis())

    companion object {
        fun new(
            title: String,
            widthMm: Float,
            heightMm: Float,
            background: BackgroundSpec,
            folder: String = "",
        ): Note =
            Note(
                title = title,
                folder = folder,
                pages = listOf(PageData(widthMm, heightMm, background)),
            )
    }
}

@Serializable
data class PageData(
    val widthMm: Float = 210f,
    val heightMm: Float = 297f,
    val background: BackgroundSpec = BackgroundSpec(),
    val textBoxes: List<TextBox> = emptyList(),
    val imageBoxes: List<ImageBox> = emptyList(),
    val id: String = newId("pg"),
)
