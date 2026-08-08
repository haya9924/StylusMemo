package com.stylusmemo.app.model

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

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

/** A page size expressed in millimeters (device-independent logical units). */
data class PageSize(
    val widthMm: Float,
    val heightMm: Float,
    val preset: PagePreset,
) {
    companion object {
        const val MM_PER_INCH = 25.4f
        private const val POINTS_PER_INCH = 72f

        /** Standard note sizes, returned as (widthMm, heightMm). */
        private fun sizeFor(preset: PagePreset): Pair<Float, Float> = when (preset) {
            PagePreset.A4 -> 210f to 297f
            PagePreset.A5 -> 148f to 210f
            PagePreset.B5 -> 176f to 250f
            PagePreset.B4 -> 257f to 364f
            PagePreset.LETTER -> 216f to 279f
            else -> 210f to 297f
        }

        /** Size for a standard preset, applying the requested orientation. */
        fun standard(preset: PagePreset, orientation: PageOrientation): PageSize? {
            if (preset == PagePreset.SCREEN_FIT || preset == PagePreset.CUSTOM) return null
            val (w, h) = sizeFor(preset)
            return if (orientation == PageOrientation.PORTRAIT) {
                PageSize(w, h, preset)
            } else {
                PageSize(h, w, preset)
            }
        }

        /** Size that exactly matches the physical screen of the current device. */
        fun screenFit(context: Context, orientation: PageOrientation): PageSize {
            val size = screenSizePx(context)
            val dpi = screenDpi(context)
            var w = size.x / dpi.toFloat() * MM_PER_INCH
            var h = size.y / dpi.toFloat() * MM_PER_INCH
            if (orientation == PageOrientation.LANDSCAPE && h > w) {
                val t = w; w = h; h = t
            }
            if (orientation == PageOrientation.PORTRAIT && w > h) {
                val t = w; w = h; h = t
            }
            return PageSize(w, h, PagePreset.SCREEN_FIT)
        }

        fun custom(widthMm: Float, heightMm: Float): PageSize =
            PageSize(widthMm.coerceIn(50f, 2000f), heightMm.coerceIn(50f, 2000f), PagePreset.CUSTOM)

        /** Convert a PDF page size in points to millimeters. */
        fun fromPdfPointSize(widthPt: Float, heightPt: Float): PageSize = PageSize(
            widthPt / POINTS_PER_INCH * MM_PER_INCH,
            heightPt / POINTS_PER_INCH * MM_PER_INCH,
            PagePreset.CUSTOM,
        )

        private fun screenSizePx(context: Context): Point {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds: Rect = wm.currentWindowMetrics.bounds
                Point(bounds.width(), bounds.height())
            } else {
                @Suppress("DEPRECATION")
                val p = Point()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealSize(p)
                p
            }
        }

        private fun screenDpi(context: Context): Int {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.resources.displayMetrics.densityDpi
            } else {
                @Suppress("DEPRECATION")
                val out = DisplayMetrics()
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .defaultDisplay.getRealMetrics(out)
                out.densityDpi
            }
        }
    }
}
