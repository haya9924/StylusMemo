package com.stylusmemo.app.model

/** Actions assignable to stylus side buttons from the settings screen. */
enum class ShortcutAction(val displayName: String) {
    NONE("なし"),
    TOGGLE_ERASER("消しゴムに切り替え"),
    UNDO("元に戻す"),
    REDO("進める"),
}
