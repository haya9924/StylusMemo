package com.stylusmemo.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stylusmemo.app.model.BackgroundSpec
import com.stylusmemo.app.model.BackgroundType

/** Full background template configuration dialog (grid / ruled / dot / blank). */
@Composable
fun BackgroundConfigDialog(
    initial: BackgroundSpec,
    onApply: (BackgroundSpec) -> Unit,
    onDismiss: () -> Unit,
) {
    var spec by remember(initial) { mutableStateOf(initial) }
    var pickingColor by remember { mutableStateOf<String?>(null) }

    val currentColor = when (pickingColor) {
        "minor" -> spec.minorColorArgb
        "major" -> spec.majorColorArgb
        "ruled" -> spec.ruledColorArgb
        "margin" -> spec.marginColorArgb
        "dot" -> spec.dotColorArgb
        else -> null
    }

    if (currentColor != null && pickingColor != null) {
        val key = pickingColor!!
        ColorPickerDialog(
            initialArgb = currentColor,
            onDismiss = { pickingColor = null },
            onPick = { c ->
                spec = when (key) {
                    "minor" -> spec.copy(minorColorArgb = c)
                    "major" -> spec.copy(majorColorArgb = c)
                    "ruled" -> spec.copy(ruledColorArgb = c)
                    "margin" -> spec.copy(marginColorArgb = c)
                    else -> spec.copy(dotColorArgb = c)
                }
                pickingColor = null
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("背景テンプレート") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackgroundType.entries.forEach { type ->
                        FilterChip(
                            selected = spec.type == type,
                            onClick = { spec = spec.copy(type = type) },
                            label = { Text(type.displayName) },
                        )
                    }
                }
                if (spec.type != BackgroundType.BLANK) {
                    SliderRow("間隔 (mm)", spec.spacingMm, 1f..15f) {
                        spec = spec.copy(spacingMm = it)
                    }
                    SliderRow("線の太さ (mm)", spec.lineThicknessMm, 0.1f..1.5f) {
                        spec = spec.copy(lineThicknessMm = it)
                    }
                }
                when (spec.type) {
                    BackgroundType.GRID -> {
                        SliderRow("太線の間隔 (本数)", spec.majorEvery.toFloat(), 0f..10f) {
                            spec = spec.copy(majorEvery = it.toInt())
                        }
                        ColorRow("細線の色", spec.minorColorArgb) { pickingColor = "minor" }
                        ColorRow("太線の色", spec.majorColorArgb) { pickingColor = "major" }
                    }
                    BackgroundType.RULED -> {
                        ColorRow("罫線の色", spec.ruledColorArgb) { pickingColor = "ruled" }
                        SliderRow("赤マージンの位置 (mm)", spec.marginXMm, 10f..60f) {
                            spec = spec.copy(marginXMm = it)
                        }
                        ColorRow("赤マージンの色", spec.marginColorArgb) { pickingColor = "margin" }
                    }
                    BackgroundType.DOT -> {
                        ColorRow("ドットの色", spec.dotColorArgb) { pickingColor = "dot" }
                    }
                    BackgroundType.BLANK -> {}
                }
            }
        },
        confirmButton = {
            Button(onClick = { onApply(spec) }) { Text("適用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ColorRow(label: String, argb: Long, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .size(26.dp)
                .background(Color(argb.toInt()), CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clickable(onClick = onClick),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onClick) { Text("変更") }
    }
}
