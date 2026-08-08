package com.stylusmemo.app.data

import android.graphics.Matrix
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke

/** Helpers for building brushes and transforming strokes between coordinate spaces. */
object InkUtil {

    /** Marker-family brush. [size] is in the same unit as stroke coordinates. */
    fun penBrush(colorArgb: Int, size: Float, epsilon: Float = size / 8f): Brush =
        Brush.createWithColorIntArgb(StockBrushes.marker(), colorArgb, size, epsilon)

    /**
     * Rebuild a stroke authored in screen-pixel space into page-millimeter space using
     * [screenToPage] (a uniform scale + translation matrix). Brush width is rescaled so the
     * stroke keeps its physical size on the page.
     */
    fun transformStrokeToMm(stroke: Stroke, screenToPage: Matrix): Stroke {
        val scale = screenToPage.mapRadius(1f).coerceAtLeast(1e-3f)
        val batch = stroke.inputs
        // Output coordinates are page millimeters: one stroke unit is one millimeter.
        val strokeUnitCm = 0.1f
        val out = MutableStrokeInputBatch()
        val pts = FloatArray(2)
        for (i in 0 until batch.size) {
            val p = batch.get(i)
            pts[0] = p.x
            pts[1] = p.y
            screenToPage.mapPoints(pts)
            out.add(
                InputToolType.STYLUS,
                pts[0],
                pts[1],
                p.elapsedTimeMillis,
                strokeUnitCm,
                p.pressure,
                p.tiltRadians,
                p.orientationRadians,
            )
        }
        val sizeMm = stroke.brush.size * scale
        return Stroke(penBrush(stroke.brush.colorIntArgb, sizeMm, sizeMm / 8f), out.toImmutable())
    }
}
