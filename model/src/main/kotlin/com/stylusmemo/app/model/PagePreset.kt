package com.stylusmemo.app.model

enum class PagePreset(val displayName: String) {
    SCREEN_FIT("画面ぴったり"),
    A4("A4"),
    A5("A5"),
    B5("B5"),
    B4("B4"),
    LETTER("Letter"),
    CUSTOM("カスタム"),
}

enum class PageOrientation(val displayName: String) {
    PORTRAIT("縦"),
    LANDSCAPE("横"),
}