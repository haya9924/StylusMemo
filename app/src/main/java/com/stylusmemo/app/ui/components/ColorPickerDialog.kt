package com.stylusmemo.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.hsv as hsvColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Canvas

private val PresetColors = listOf(
    0xFF1A1A1A, 0xFFE53935, 0xFFEF6C00, 0xFFFDD835,
    0xFF43A047, 0xFF1E88E5, 0xFF8E24AA, 0xFFF06292,
    0xFF795548, 0xFF607D8B, 0xFFFFFFFF,
)

fun Long.toColor(): Color = Color(this.toInt())

fun Color.toArgbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL

/**
 * HSV colour picker. [allowAlpha] adds a translucency slider and keeps the alpha byte in the value
 * handed to [onPick]; it is off for opaque tools such as the pen. Without it a picked colour is
 * always fully opaque, which silently destroyed the highlighter's translucency.
 */
@Composable
fun ColorPickerDialog(
    initialArgb: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
    allowAlpha: Boolean = false,
) {
    var hue by remember(initialArgb) {
        mutableStateOf(Color(initialArgb.toInt()).let { c ->
            floatArrayOf(0f, 0f, 0f).also { android.graphics.Color.colorToHSV(c.toArgb(), it) }[0]
        })
    }
    var sat by remember(initialArgb) {
        mutableStateOf(Color(initialArgb.toInt()).let { c ->
            floatArrayOf(0f, 0f, 0f).also { android.graphics.Color.colorToHSV(c.toArgb(), it) }[1]
        })
    }
    var value by remember(initialArgb) {
        mutableStateOf(Color(initialArgb.toInt()).let { c ->
            floatArrayOf(0f, 0f, 0f).also { android.graphics.Color.colorToHSV(c.toArgb(), it) }[2]
        })
    }
    var alpha by remember(initialArgb) {
        mutableStateOf(if (allowAlpha) ((initialArgb.toInt() ushr 24) and 0xFF) / 255f else 1f)
    }

    val currentColor = Color.hsv(hue, sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
    val pickedArgb: Long = if (allowAlpha) {
        (((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toLong() shl 24) or
            (currentColor.toArgbLong() and 0x00FFFFFFL))
    } else {
        currentColor.toArgbLong()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (allowAlpha) "蛍光ペンの色" else "ペンの色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SVSquare(
                    hue = hue, sat = sat, value = value,
                    onSelect = { s, v -> sat = s; value = v },
                )
                HueBar(hue = hue) { hue = it }
                if (allowAlpha) {
                    Column {
                        Text(
                            "不透明度 ${(alpha * 100).toInt()} %",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = alpha,
                            onValueChange = { alpha = it },
                            valueRange = 0.1f..1f,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(32.dp).background(currentColor.copy(alpha = alpha), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    )
                    Text("現在の色", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PresetColors.forEach { preset ->
                        Box(
                            Modifier.size(28.dp)
                                .background(
                                    if (allowAlpha) {
                                        Color(preset.toInt()).copy(alpha = alpha)
                                    } else {
                                        Color(preset.toInt())
                                    },
                                    CircleShape,
                                )
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable {
                                    // Keep the chosen translucency so a preset never makes the
                                    // highlighter opaque.
                                    onPick(
                                        if (allowAlpha) {
                                            ((pickedArgb.toInt() and 0xFF000000.toInt()) or
                                                (preset.toInt() and 0x00FFFFFF)).toLong()
                                        } else {
                                            preset
                                        },
                                    )
                                },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(pickedArgb) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

@Composable
private fun SVSquare(
    hue: Float,
    sat: Float,
    value: Float,
    onSelect: (sat: Float, value: Float) -> Unit,
) {
    var viewSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    Box(Modifier.size(180.dp).onSizeChanged {
        viewSize = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat())
    }) {
        Canvas(Modifier.matchParentSize()) {
            val base = Color.hsv(hue, 1f, 1f)
            drawRect(base)
            val whiteOverlay = androidx.compose.ui.graphics.Brush.horizontalGradient(
                listOf(Color.White, Color.Transparent),
            )
            drawRect(whiteOverlay)
            val blackOverlay = androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black),
            )
            drawRect(blackOverlay)
            val px = sat * size.width
            val py = (1f - value) * size.height
            drawCircle(Color.White, radius = 7f, center = Offset(px, py), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        }
        if (viewSize.width > 0f) {
            Canvas(Modifier.matchParentSize().pointerInput(viewSize) {
                detectDragGestures { change, _ ->
                    val x = change.position.x / viewSize.width
                    val y = change.position.y / viewSize.height
                    onSelect(x.coerceIn(0f, 1f), (1f - y).coerceIn(0f, 1f))
                }
            }) {}
        }
    }
}

@Composable
private fun HueBar(hue: Float, onHue: (Float) -> Unit) {
    var barWidth by remember { mutableStateOf(0f) }
    Box(Modifier.fillMaxWidth().height(28.dp).onSizeChanged { barWidth = it.width.toFloat() }) {
        Canvas(Modifier.matchParentSize()) {
            val step = 1f / 12f
            var h = 0f
            val band = size.width * step
            while (h < 1f) {
                drawRect(Color.hsv(h * 360f, 1f, 1f), topLeft = Offset(h * size.width, 0f), size = androidx.compose.ui.geometry.Size(band + 1f, size.height))
                h += step
            }
            val cx = (hue / 360f) * size.width
            drawCircle(Color.Black, radius = 9f, center = Offset(cx, size.height / 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
        }
        if (barWidth > 0f) {
            Canvas(Modifier.matchParentSize().pointerInput(barWidth) {
                detectDragGestures { change, _ ->
                    onHue((change.position.x / barWidth * 360f).coerceIn(0f, 360f))
                }
            }) {}
        }
    }
}
