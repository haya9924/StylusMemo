package com.stylusmemo.app.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditOff
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardOptions
import com.stylusmemo.app.model.PageLayoutMode
import com.stylusmemo.app.ui.components.BackgroundConfigDialog
import com.stylusmemo.app.ui.components.ColorPickerDialog
import com.stylusmemo.app.ui.components.PageSizeDialog
import com.stylusmemo.app.ui.components.PageSizeSelection
import com.stylusmemo.app.ui.components.toArgbLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    noteId: String,
    viewModel: EditorViewModel,
    onBack: () -> Unit,
) {
    val note by viewModel.note.collectAsState()
    val tool by viewModel.tool.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val penColor by viewModel.penColorArgb.collectAsState()
    val penSize by viewModel.penSizeMm.collectAsState()
    val fingerDraw by viewModel.fingerDraw.collectAsState()
    val currentPage by viewModel.currentPageData.collectAsState()
    val selectedBox by viewModel.selectedBox.collectAsState()
    val pageLayoutMode by viewModel.pageLayoutMode.collectAsState()

    var showBackgroundDialog by remember { mutableStateOf(false) }
    var showPageSizeDialog by remember { mutableStateOf(false) }
    var showPenDialog by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }
    var showImageWidthDialog by remember { mutableStateOf(false) }
    var showPageMenu by remember { mutableStateOf(false) }

    val context = LocalContext.current
    var pendingImageUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.importPdf(context, it) }
    }
    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { pendingImageUri = it; showImageWidthDialog = true }
    }

    LaunchedEffect(noteId) { viewModel.openNote(noteId) }

    Scaffold(
        topBar = {
            EditorTopBar(
                title = note?.title ?: "",
                pageIndex = viewModel.currentPageIndex(),
                pageCount = viewModel.pageCount(),
                canUndo = canUndo,
                canRedo = canRedo,
                onBack = onBack,
                onPrevPage = { viewModel.switchPage(viewModel.currentPageIndex() - 1) },
                onNextPage = { viewModel.switchPage(viewModel.currentPageIndex() + 1) },
                onAddPage = { viewModel.addPage() },
                onDeletePage = { viewModel.deletePage() },
                onUndo = { viewModel.undo() },
                onRedo = { viewModel.redo() },
            )
        },
        bottomBar = {
            EditorToolBar(
                tool = tool,
                penColor = penColor,
                layoutMode = pageLayoutMode,
                onTool = { viewModel.setTool(it) },
                onPenDialog = { showPenDialog = true },
                onBackground = { showBackgroundDialog = true },
                onPageMenu = { showPageMenu = true },
                onAddText = { showTextDialog = true },
                onLayoutMode = {
                    val next = PageLayoutMode.entries[(pageLayoutMode.ordinal + 1) % PageLayoutMode.entries.size]
                    viewModel.setPageLayoutMode(next)
                },
                onImportImage = {
                    imageLauncher.launch(arrayOf("image/*"))
                },
                onImportPdf = { pdfLauncher.launch(arrayOf("application/pdf")) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { ctx -> EditorView(ctx) },
                update = { v -> viewModel.bindView(v) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    if (showBackgroundDialog) {
        val page = currentPage
        if (page != null) {
            BackgroundConfigDialog(
                initial = page.background,
                onApply = { viewModel.setBackground(it); showBackgroundDialog = false },
                onDismiss = { showBackgroundDialog = false },
            )
        }
    }

    if (showPageSizeDialog) {
        val page = currentPage
        if (page != null) {
            PageSizeDialog(
                initial = PageSizeSelection(page.widthMm, page.heightMm),
                onApply = {
                    viewModel.setPageSize(it.widthMm, it.heightMm)
                    showPageSizeDialog = false
                },
                onDismiss = { showPageSizeDialog = false },
            )
        }
    }

    if (showPenDialog) {
        PenSettingsDialog(
            colorArgb = penColor,
            sizeMm = penSize,
            fingerDraw = fingerDraw,
            onColor = { viewModel.setPenColor(it) },
            onSize = { viewModel.setPenSize(it) },
            onFingerDraw = { viewModel.setFingerDraw(it) },
            onDismiss = { showPenDialog = false },
        )
    }

    if (showTextDialog) {
        val editingBox = selectedBox?.takeIf { it.first == "text" }
            ?.let { (_, id) -> currentPage?.textBoxes?.firstOrNull { b -> b.id == id } }
        TextInsertDialog(
            onAdd = { text, sizeMm, colorArgb ->
                viewModel.addText(text, sizeMm, colorArgb)
                showTextDialog = false
            },
            onUpdate = { text, sizeMm, colorArgb ->
                viewModel.updateSelectedText(editingBox!!.id, text, sizeMm, colorArgb)
                showTextDialog = false
            },
            editing = editingBox,
            onDismiss = { showTextDialog = false },
        )
    }

    if (showPageMenu) {
        PageMenuDialog(
            pageIndex = viewModel.currentPageIndex(),
            pageCount = viewModel.pageCount(),
            onAdd = { viewModel.addPage(); showPageMenu = false },
            onDelete = { viewModel.deletePage(); showPageMenu = false },
            onSwitch = { viewModel.switchPage(it); showPageMenu = false },
            onPageSize = { showPageMenu = false; showPageSizeDialog = true },
            onDismiss = { showPageMenu = false },
        )
    }

    if (showImageWidthDialog) {
        val uri = pendingImageUri
        if (uri != null) {
            ImageWidthDialog(
                pageWidthMm = currentPage?.widthMm ?: 210f,
                onInsert = { w ->
                    viewModel.importImage(context, uri, w)
                    pendingImageUri = null
                    showImageWidthDialog = false
                },
                onDismiss = {
                    pendingImageUri = null
                    showImageWidthDialog = false
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(
    title: String,
    pageIndex: Int,
    pageCount: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    onBack: () -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onAddPage: () -> Unit,
    onDeletePage: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, maxLines = 1)
                    Text(
                        "ページ ${pageIndex + 1} / $pageCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onPrevPage, enabled = pageIndex > 0) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "前のページ")
                }
                IconButton(onClick = onNextPage, enabled = pageIndex < pageCount - 1) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "次のページ")
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
            }
        },
        actions = {
            IconButton(onClick = onAddPage) {
                Icon(Icons.Default.Add, contentDescription = "ページを追加")
            }
            IconButton(onClick = onDeletePage, enabled = pageCount > 1) {
                Icon(Icons.Default.Delete, contentDescription = "ページを削除")
            }
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(Icons.Default.Undo, contentDescription = "元に戻す")
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(Icons.Default.Redo, contentDescription = "やり直し")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun EditorToolBar(
    tool: EditorTool,
    penColor: Long,
    layoutMode: PageLayoutMode,
    onTool: (EditorTool) -> Unit,
    onPenDialog: () -> Unit,
    onBackground: () -> Unit,
    onPageMenu: () -> Unit,
    onAddText: () -> Unit,
    onLayoutMode: () -> Unit,
    onImportImage: () -> Unit,
    onImportPdf: () -> Unit,
) {
    Surface(shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ToolIconButton(Icons.Default.Edit, "ペン", tool == EditorTool.PEN) { onTool(EditorTool.PEN) }
            ToolIconButton(Icons.Default.Remove, "消しゴム", tool == EditorTool.ERASER) { onTool(EditorTool.ERASER) }
            ToolIconButton(Icons.Default.SelectAll, "選択", tool == EditorTool.SELECT) { onTool(EditorTool.SELECT) }
            ToolIconButton(Icons.Default.ShowChart, "直線", tool == EditorTool.STRAIGHT_LINE) { onTool(EditorTool.STRAIGHT_LINE) }
            ToolIconButton(Icons.Default.EditOff, "擦って消去", tool == EditorTool.SCRIBBLE_ERASE) { onTool(EditorTool.SCRIBBLE_ERASE) }
            ToolIconButton(Icons.Default.Timeline, "なげわ", tool == EditorTool.LASSO) { onTool(EditorTool.LASSO) }

            Box(
                Modifier.size(30.dp).padding(4.dp).clickable(onClick = onPenDialog)
                    .background(Color(penColor.toInt()), CircleShape),
            )

            ToolIconButton(Icons.Default.TextFields, "テキスト", false) { onAddText() }
            ToolIconButton(Icons.Default.Image, "画像を挿入", false) { onImportImage() }
            ToolIconButton(Icons.Default.PictureAsPdf, "PDFを挿入", false) { onImportPdf() }

            Divider(Modifier.height(28.dp).width(1.dp))

            ToolIconButton(Icons.Default.Settings, "背景", false) { onBackground() }
            ToolIconButton(Icons.Default.SelectAll, "ページ管理", false) { onPageMenu() }
            ToolIconButton(
                when (layoutMode) {
                    PageLayoutMode.SINGLE -> Icons.Default.CropSquare
                    PageLayoutMode.VERTICAL -> Icons.Default.ViewStream
                    PageLayoutMode.HORIZONTAL -> Icons.Default.ViewColumn
                },
                layoutMode.displayName,
                false,
            ) { onLayoutMode() }
        }
    }
}

@Composable
private fun ToolIconButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Surface(
        shape = CircleShape,
        color = bg,
        modifier = Modifier.size(40.dp),
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PenSettingsDialog(
    colorArgb: Long,
    sizeMm: Float,
    fingerDraw: Boolean,
    onColor: (Long) -> Unit,
    onSize: (Float) -> Unit,
    onFingerDraw: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    var size by remember(sizeMm) { mutableStateOf(sizeMm) }
    var finger by remember(fingerDraw) { mutableStateOf(fingerDraw) }

    if (showPicker) {
        ColorPickerDialog(
            initialArgb = colorArgb,
            onDismiss = { showPicker = false },
            onPick = { onColor(it); showPicker = false },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ペンの設定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("色", style = MaterialTheme.typography.bodyMedium)
                    Box(
                        Modifier.size(32.dp).background(Color(colorArgb.toInt()), CircleShape)
                            .clickable { showPicker = true },
                    )
                }
                Column {
                    Text("線の太さ ${"%.1f".format(size)} mm", style = MaterialTheme.typography.bodyMedium)
                    Slider(value = size, onValueChange = { size = it; onSize(it) }, valueRange = 0.2f..4f)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = finger,
                        onClick = {
                            val next = !finger
                            finger = next
                            onFingerDraw(next)
                        },
                        label = { Text("指でも描ける") },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
    )
}

@Composable
private fun TextInsertDialog(
    onAdd: (String, Float, Long) -> Unit,
    onUpdate: (String, Float, Long) -> Unit,
    editing: com.stylusmemo.app.model.TextBox?,
    onDismiss: () -> Unit,
) {
    var text by remember(editing) { mutableStateOf(editing?.text ?: "") }
    var sizeMm by remember(editing) { mutableStateOf(editing?.fontSizeMm ?: 18f) }
    var colorArgb by remember(editing) { mutableStateOf(editing?.colorArgb ?: 0xFF1A1A1AL) }
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        ColorPickerDialog(
            initialArgb = colorArgb,
            onDismiss = { showPicker = false },
            onPick = { colorArgb = it; showPicker = false },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing != null) "テキストを編集" else "テキストを追加") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("テキスト") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Column {
                    Text("文字サイズ ${sizeMm.toInt()} mm", style = MaterialTheme.typography.bodyMedium)
                    Slider(value = sizeMm, onValueChange = { sizeMm = it }, valueRange = 8f..72f)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("色", style = MaterialTheme.typography.bodyMedium)
                    Box(
                        Modifier.size(32.dp).background(Color(colorArgb.toInt()), CircleShape)
                            .clickable { showPicker = true },
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (editing != null) onUpdate(text, sizeMm, colorArgb)
                else onAdd(text, sizeMm, colorArgb)
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

@Composable
private fun PageMenuDialog(
    pageIndex: Int,
    pageCount: Int,
    onAdd: () -> Unit,
    onDelete: () -> Unit,
    onSwitch: (Int) -> Unit,
    onPageSize: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ページ管理") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0 until pageCount).forEach { i ->
                        FilterChip(
                            selected = i == pageIndex,
                            onClick = { onSwitch(i) },
                            label = { Text("${i + 1}") },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAdd) { Text("ページを追加") }
                    OutlinedButton(onClick = onPageSize) { Text("ページサイズ") }
                    OutlinedButton(onClick = onDelete, enabled = pageCount > 1) { Text("削除") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        },
    )
}

@Composable
private fun ImageWidthDialog(
    pageWidthMm: Float,
    onInsert: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var width by remember { mutableStateOf(pageWidthMm * 0.8f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("画像を挿入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("挿入する幅 (mm)", style = MaterialTheme.typography.bodyMedium)
                Slider(value = width, onValueChange = { width = it }, valueRange = 20f..pageWidthMm)
                OutlinedTextField(
                    value = width.toInt().toString(),
                    onValueChange = { width = it.toFloatOrNull() ?: width },
                    label = { Text("幅 (mm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onInsert(width) }) { Text("挿入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
