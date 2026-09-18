package com.stylusmemo.app.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** An element placed on a page that can be freely moved/resized/rotated as a box. */
@Serializable
data class TextBox(
    val id: String = newId("t"),
    val text: String = "",
    val fontSizeMm: Float = 16f,
    val colorArgb: Long = 0xFF000000,
    val leftMm: Float = 20f,
    val topMm: Float = 20f,
    val widthMm: Float = 90f,
    val heightMm: Float = 28f,
    val rotationDeg: Float = 0f,
    val zIndex: Int = 0,
)

@Serializable
data class ImageBox(
    val id: String = newId("i"),
    val assetName: String = "",
    val leftMm: Float = 20f,
    val topMm: Float = 20f,
    val widthMm: Float = 60f,
    val heightMm: Float = 60f,
    val rotationDeg: Float = 0f,
    val zIndex: Int = 0,
)

fun newId(prefix: String): String =
    "$prefix-${UUID.randomUUID().toString().replace("-", "").take(12)}"
