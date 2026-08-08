package com.stylusmemo.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.stylusmemo.app.model.PageSize

/** Standard paper size presets, mm dimensions for the given orientation. */
enum class PaperPreset(val label: String, val widthMm: Float, val heightMm: Float) {
    A4("A4", 210f, 297f),
    A5("A5", 148f, 210f),
    B5("B5", 182f, 257f),
    B4("B4", 257f, 364f),
    LETTER("Letter", 215.9f, 279.4f),
}

enum class Orientation(val label: String) {
    PORTRAIT("縦"), LANDSCAPE("横");
}

data class PageSizeSelection(
    val widthMm: Float,
    val heightMm: Float,
)

@Composable
fun PageSizeDialog(
    initial: PageSizeSelection,
    title: String = "ページサイズ",
    onApply: (PageSizeSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    var preset by remember { mutableStateOf<PaperPreset?>(null) }
    var orientation by remember { mutableStateOf(Orientation.PORTRAIT) }
    var customWidth by remember(initial) { mutableStateOf(initial.widthMm.toString()) }
    var customHeight by remember(initial) { mutableStateOf(initial.heightMm.toString()) }
    var mode by remember { mutableStateOf("preset") }

    fun toSelection(): PageSizeSelection {
        val p = preset ?: PaperPreset.A4
        val w = if (mode == "custom") customWidth.toFloatOrNull() ?: initial.widthMm else p.widthMm
        val h = if (mode == "custom") customHeight.toFloatOrNull() ?: initial.heightMm else p.heightMm
        return if (orientation == Orientation.PORTRAIT) PageSizeSelection(w, h) else PageSizeSelection(h, w)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == "preset", onClick = { mode = "preset" })
                    Text("プリセット", style = MaterialTheme.typography.bodyMedium)
                }
                if (mode == "preset") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PaperPreset.entries.forEach { p ->
                            FilterChip(
                                selected = preset == p,
                                onClick = { preset = p },
                                label = { Text(p.label) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Orientation.entries.forEach { o ->
                            FilterChip(
                                selected = orientation == o,
                                onClick = { orientation = o },
                                label = { Text(o.label) },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == "custom", onClick = { mode = "custom" })
                    Text("カスタム", style = MaterialTheme.typography.bodyMedium)
                }
                if (mode == "custom") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = customWidth,
                            onValueChange = { customWidth = it },
                            label = { Text("幅 (mm)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = customHeight,
                            onValueChange = { customHeight = it },
                            label = { Text("高さ (mm)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onApply(toSelection()) }) { Text("適用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
