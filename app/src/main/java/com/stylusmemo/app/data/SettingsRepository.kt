package com.stylusmemo.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stylusmemo.app.model.BackgroundSpec
import com.stylusmemo.app.model.BackgroundType
import com.stylusmemo.app.model.PageOrientation
import com.stylusmemo.app.model.PageLayoutMode
import com.stylusmemo.app.model.PagePreset
import com.stylusmemo.app.model.ShortcutAction
import com.stylusmemo.app.model.StylusButtonPattern
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** User-configurable application settings. */
data class AppSettings(
    val defaultPagePreset: PagePreset = PagePreset.A4,
    val defaultPageOrientation: PageOrientation = PageOrientation.PORTRAIT,
    val defaultPageWidthMm: Float = 210f,
    val defaultPageHeightMm: Float = 297f,
    val defaultBackground: BackgroundSpec = BackgroundSpec(type = BackgroundType.GRID),
    val defaultPageLayoutMode: PageLayoutMode = PageLayoutMode.SINGLE,
    val defaultPenColorArgb: Long = 0xFF1A1A1A,
    val defaultPenSizeMm: Float = 0.5f,
    val saveLocationUri: String = "",
    val stylusPrimaryAction: ShortcutAction = ShortcutAction.TOGGLE_ERASER,
    val stylusSecondaryAction: ShortcutAction = ShortcutAction.UNDO,
    val stylusPrimaryPattern: StylusButtonPattern? = null,
    val stylusSecondaryPattern: StylusButtonPattern? = null,
    val fingerDrawEnabled: Boolean = false,
) {
    fun pageSizeFromDefault(context: Context): Pair<Float, Float> {
        val size = when (defaultPagePreset) {
            PagePreset.SCREEN_FIT ->
                com.stylusmemo.app.model.PageSize.screenFit(context, defaultPageOrientation)
            PagePreset.CUSTOM ->
                com.stylusmemo.app.model.PageSize.custom(defaultPageWidthMm, defaultPageHeightMm)
            else -> com.stylusmemo.app.model.PageSize.standard(defaultPagePreset, defaultPageOrientation)
                ?: com.stylusmemo.app.model.PageSize.standard(PagePreset.A4, defaultPageOrientation)!!
        }
        return size.widthMm to size.heightMm
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val PAGE_PRESET = stringPreferencesKey("default_page_preset")
        val PAGE_ORIENTATION = stringPreferencesKey("default_page_orientation")
        val PAGE_LAYOUT_MODE = stringPreferencesKey("default_page_layout_mode")
        val PAGE_WIDTH = floatPreferencesKey("default_page_width_mm")
        val PAGE_HEIGHT = floatPreferencesKey("default_page_height_mm")
        val BG_TYPE = stringPreferencesKey("default_bg_type")
        val BG_SPACING = floatPreferencesKey("default_bg_spacing")
        val BG_MINOR_COLOR = longPreferencesKey("default_bg_minor_color")
        val BG_MAJOR_COLOR = longPreferencesKey("default_bg_major_color")
        val BG_MAJOR_EVERY = intPreferencesKey("default_bg_major_every")
        val BG_RULED_COLOR = longPreferencesKey("default_bg_ruled_color")
        val BG_LINE_THICKNESS = floatPreferencesKey("default_bg_line_thickness")
        val BG_MARGIN_COLOR = longPreferencesKey("default_bg_margin_color")
        val BG_MARGIN_X = floatPreferencesKey("default_bg_margin_x")
        val BG_DOT_COLOR = longPreferencesKey("default_bg_dot_color")
        val PEN_COLOR = longPreferencesKey("default_pen_color")
        val PEN_SIZE = floatPreferencesKey("default_pen_size_mm")
        val SAVE_LOCATION = stringPreferencesKey("save_location_uri")
        val STYLUS_PRIMARY = stringPreferencesKey("stylus_primary_action")
        val STYLUS_SECONDARY = stringPreferencesKey("stylus_secondary_action")
        val STYLUS_PRIMARY_PATTERN = stringPreferencesKey("stylus_primary_pattern")
        val STYLUS_SECONDARY_PATTERN = stringPreferencesKey("stylus_secondary_pattern")
        val FINGER_DRAW = booleanPreferencesKey("finger_draw_enabled")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val current = context.dataStore.data.map { it.toSettings() }
        val snapshot = current.first()
        val next = transform(snapshot)
        context.dataStore.edit { prefs -> applySettings(prefs, next) }
    }

    private fun applySettings(prefs: androidx.datastore.preferences.core.MutablePreferences, s: AppSettings) {
        with(Keys) {
            prefs[PAGE_PRESET] = s.defaultPagePreset.name
            prefs[PAGE_ORIENTATION] = s.defaultPageOrientation.name
            prefs[PAGE_LAYOUT_MODE] = s.defaultPageLayoutMode.name
            prefs[PAGE_WIDTH] = s.defaultPageWidthMm
            prefs[PAGE_HEIGHT] = s.defaultPageHeightMm
            prefs[BG_TYPE] = s.defaultBackground.type.name
            prefs[BG_SPACING] = s.defaultBackground.spacingMm
            prefs[BG_MINOR_COLOR] = s.defaultBackground.minorColorArgb
            prefs[BG_MAJOR_COLOR] = s.defaultBackground.majorColorArgb
            prefs[BG_MAJOR_EVERY] = s.defaultBackground.majorEvery
            prefs[BG_RULED_COLOR] = s.defaultBackground.ruledColorArgb
            prefs[BG_LINE_THICKNESS] = s.defaultBackground.lineThicknessMm
            prefs[BG_MARGIN_COLOR] = s.defaultBackground.marginColorArgb
            prefs[BG_MARGIN_X] = s.defaultBackground.marginXMm
            prefs[BG_DOT_COLOR] = s.defaultBackground.dotColorArgb
            prefs[PEN_COLOR] = s.defaultPenColorArgb
            prefs[PEN_SIZE] = s.defaultPenSizeMm
            prefs[SAVE_LOCATION] = s.saveLocationUri
            prefs[STYLUS_PRIMARY] = s.stylusPrimaryAction.name
            prefs[STYLUS_SECONDARY] = s.stylusSecondaryAction.name
            prefs[STYLUS_PRIMARY_PATTERN] = s.stylusPrimaryPattern?.encode() ?: ""
            prefs[STYLUS_SECONDARY_PATTERN] = s.stylusSecondaryPattern?.encode() ?: ""
            prefs[FINGER_DRAW] = s.fingerDrawEnabled
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val bg = BackgroundSpec(
            type = runCatching { BackgroundType.valueOf(this[Keys.BG_TYPE] ?: BackgroundType.GRID.name) }
                .getOrDefault(BackgroundType.GRID),
            spacingMm = this[Keys.BG_SPACING] ?: 5f,
            minorColorArgb = this[Keys.BG_MINOR_COLOR] ?: 0xFFB0BEC5,
            majorColorArgb = this[Keys.BG_MAJOR_COLOR] ?: 0xFF78909C,
            majorEvery = this[Keys.BG_MAJOR_EVERY] ?: 5,
            ruledColorArgb = this[Keys.BG_RULED_COLOR] ?: 0xFF90A4AE,
            lineThicknessMm = this[Keys.BG_LINE_THICKNESS] ?: 0.3f,
            marginColorArgb = this[Keys.BG_MARGIN_COLOR] ?: 0xFFE57373,
            marginXMm = this[Keys.BG_MARGIN_X] ?: 25f,
            dotColorArgb = this[Keys.BG_DOT_COLOR] ?: 0xFF90A4AE,
        )
        return AppSettings(
            defaultPagePreset = runCatching {
                PagePreset.valueOf(this[Keys.PAGE_PRESET] ?: PagePreset.A4.name)
            }.getOrDefault(PagePreset.A4),
            defaultPageOrientation = runCatching {
                PageOrientation.valueOf(this[Keys.PAGE_ORIENTATION] ?: PageOrientation.PORTRAIT.name)
            }.getOrDefault(PageOrientation.PORTRAIT),
            defaultPageWidthMm = this[Keys.PAGE_WIDTH] ?: 210f,
            defaultPageHeightMm = this[Keys.PAGE_HEIGHT] ?: 297f,
            defaultBackground = bg,
            defaultPageLayoutMode = runCatching {
                PageLayoutMode.valueOf(
                    this[Keys.PAGE_LAYOUT_MODE] ?: PageLayoutMode.SINGLE.name,
                )
            }.getOrDefault(PageLayoutMode.SINGLE),
            defaultPenColorArgb = this[Keys.PEN_COLOR] ?: 0xFF1A1A1A,
            defaultPenSizeMm = this[Keys.PEN_SIZE] ?: 0.5f,
            saveLocationUri = this[Keys.SAVE_LOCATION] ?: "",
            stylusPrimaryAction = runCatching {
                ShortcutAction.valueOf(
                    this[Keys.STYLUS_PRIMARY] ?: ShortcutAction.TOGGLE_ERASER.name,
                )
            }.getOrDefault(ShortcutAction.TOGGLE_ERASER),
            stylusSecondaryAction = runCatching {
                ShortcutAction.valueOf(
                    this[Keys.STYLUS_SECONDARY] ?: ShortcutAction.UNDO.name,
                )
            }.getOrDefault(ShortcutAction.UNDO),
            stylusPrimaryPattern = StylusButtonPattern.decode(this[Keys.STYLUS_PRIMARY_PATTERN]),
            stylusSecondaryPattern = StylusButtonPattern.decode(this[Keys.STYLUS_SECONDARY_PATTERN]),
            fingerDrawEnabled = this[Keys.FINGER_DRAW] ?: false,
        )
    }
}
