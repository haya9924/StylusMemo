package com.stylusmemo.app.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.stylusmemo.app.data.AppSettings
import com.stylusmemo.app.model.PageLayoutMode
import com.stylusmemo.app.model.PageOrientation
import com.stylusmemo.app.model.PagePreset
import com.stylusmemo.app.model.ShortcutAction
import com.stylusmemo.app.model.StylusButtonPattern
import com.stylusmemo.app.ui.components.BackgroundConfigDialog
import com.stylusmemo.app.ui.components.ColorPickerDialog
import com.stylusmemo.app.ui.components.StylusCaptureView
import com.stylusmemo.app.ui.components.toArgbLong

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val s = settings ?: return

    var showBackground by remember { mutableStateOf(false) }
    var showPenColor by remember { mutableStateOf(false) }
    var learningPrimary by remember { mutableStateOf(false) }
    var learningSecondary by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.setSaveLocationUri(it.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("デフォルトのページ") {
                PagePresetSelector(
                    preset = s.defaultPagePreset,
                    orientation = s.defaultPageOrientation,
                    onPreset = { p -> viewModel.update { it.copy(defaultPagePreset = p) } },
                    onOrientation = { o -> viewModel.update { it.copy(defaultPageOrientation = o) } },
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("表示形式", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PageLayoutMode.entries.forEach { m ->
                            FilterChip(
                                selected = s.defaultPageLayoutMode == m,
                                onClick = { viewModel.update { it.copy(defaultPageLayoutMode = m) } },
                                label = { Text(m.displayName) },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showBackground = true }) {
                        Text("背景テンプレート: ${s.defaultBackground.type.displayName}")
                    }
                }
            }

            Section("ペン") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("色", style = MaterialTheme.typography.bodyMedium)
                    Box(
                        Modifier.size(32.dp)
                            .background(Color(s.defaultPenColorArgb.toInt()), CircleShape)
                            .clickable { showPenColor = true },
                    )
                }
                Column {
                    Text(
                        "線の太さ ${"%.1f".format(s.defaultPenSizeMm)} mm",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = s.defaultPenSizeMm,
                        onValueChange = { viewModel.setPenSize(it) },
                        valueRange = 0.2f..4f,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = s.fingerDrawEnabled,
                        onClick = { viewModel.setFingerDraw(!s.fingerDrawEnabled) },
                        label = { Text("指でも描ける") },
                    )
                }
            }

            Section("スタイラスボタン") {
                ActionSelector(
                    label = "メインボタン",
                    action = s.stylusPrimaryAction,
                    onAction = { viewModel.setPrimaryAction(it) },
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { learningPrimary = true }) {
                        Text("このボタンを学習")
                    }
                    Text(
                        if (s.stylusPrimaryPattern != null) "学習済み: ${s.stylusPrimaryPattern!!.describe()}"
                        else "標準ボタンを使用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                ActionSelector(
                    label = "セカンダリボタン",
                    action = s.stylusSecondaryAction,
                    onAction = { viewModel.setSecondaryAction(it) },
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { learningSecondary = true }) {
                        Text("このボタンを学習")
                    }
                    Text(
                        if (s.stylusSecondaryPattern != null) "学習済み: ${s.stylusSecondaryPattern!!.describe()}"
                        else "標準ボタンを使用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Section("保存先") {
                Text(
                    viewModel.saveLocationUri().ifBlank { "アプリ専用領域 (デフォルト)" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { saveLauncher.launch(null) }) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Text("フォルダを選択")
                }
                Text(
                    "選択したフォルダ配下の notes/ にメモが保存されます。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (showBackground) {
        BackgroundConfigDialog(
            initial = s.defaultBackground,
            onApply = { newBg -> viewModel.update { it.copy(defaultBackground = newBg) } },
            onDismiss = { showBackground = false },
        )
    }
    if (showPenColor) {
        ColorPickerDialog(
            initialArgb = s.defaultPenColorArgb,
            onDismiss = { showPenColor = false },
            onPick = { viewModel.setPenColor(it) },
        )
    }
    if (learningPrimary) {
        StylusLearnDialog(
            onSave = { viewModel.setPrimaryPattern(it); learningPrimary = false },
            onClear = { viewModel.setPrimaryPattern(null); learningPrimary = false },
            onDismiss = { learningPrimary = false },
        )
    }
    if (learningSecondary) {
        StylusLearnDialog(
            onSave = { viewModel.setSecondaryPattern(it); learningSecondary = false },
            onClear = { viewModel.setSecondaryPattern(null); learningSecondary = false },
            onDismiss = { learningSecondary = false },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun PagePresetSelector(
    preset: PagePreset,
    orientation: PageOrientation,
    onPreset: (PagePreset) -> Unit,
    onOrientation: (PageOrientation) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PagePreset.entries.forEach { p ->
                FilterChip(selected = preset == p, onClick = { onPreset(p) }, label = { Text(p.displayName) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PageOrientation.entries.forEach { o ->
                FilterChip(
                    selected = orientation == o,
                    onClick = { onOrientation(o) },
                    label = { Text(o.displayName) },
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ActionSelector(
    label: String,
    action: ShortcutAction,
    onAction: (ShortcutAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = action.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ShortcutAction.entries.forEach { a ->
                    DropdownMenuItem(
                        text = { Text(a.displayName) },
                        onClick = { onAction(a); expanded = false },
                    )
                }
            }
        }
}

@Composable
private fun StylusLearnDialog(
    onSave: (StylusButtonPattern) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pattern by remember { mutableStateOf<StylusButtonPattern?>(null) }
    var feedback by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("スタイラスボタンを学習") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "スタイラスで下の枠に触れながら、学習させたいボタンを押してください。\n" +
                        "標準のボタンで認識されない場合に、この機能で検出します。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                AndroidView(
                    factory = { ctx ->
                        StylusCaptureView(ctx).apply {
                            onPattern = { pattern = it; feedback = it.describe() }
                            onFeedback = { feedback = it }
                        }
                    },
                    update = { it.requestFocus() },
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                )
                Text(
                    feedback.ifBlank { "検出待ち…" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { pattern?.let(onSave) },
                enabled = pattern?.isMeaningful == true,
            ) { Text("保存") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (pattern?.isMeaningful == true) {
                    TextButton(onClick = onClear) { Text("解除") }
                }
                TextButton(onClick = onDismiss) { Text("キャンセル") }
            }
        },
    )
}
