package com.stylusmemo.app.ui.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.AutoFixOff
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditOff
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardOptions
import com.stylusmemo.app.StylusMemoApp
import com.stylusmemo.app.model.PageLayoutMode
import com.stylusmemo.app.plugin.NoteExporterPlugin
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
    val highlightColor by viewModel.highlightColorArgb.collectAsState()
    val highlightSize by viewModel.highlightSizeMm.collectAsState()
    val fingerDraw by viewModel.fingerDraw.collectAsState()
    val currentPage by viewModel.currentPageData.collectAsState()
    val currentPageIndex by viewModel.currentPageIndex.collectAsState()
    val selectedBox by viewModel.selectedBox.collectAsState()
    val pageLayoutMode by viewModel.pageLayoutMode.collectAsState()
    val memorizeActive by viewModel.memorizeActive.collectAsState()
    val memorizePluginState by viewModel.memorizeState.collectAsState()
    val eyedropperArmed by viewModel.memorizeEyedropperArmed.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val pageLoading by viewModel.pageLoading.collectAsState()
    val selectedSnipId by viewModel.selectedSnipId.collectAsState()
    val snipBusy by viewModel.snipBusy.collectAsState()
    val snipError by viewModel.snipError.collectAsState()

    var showBackgroundDialog by remember { mutableStateOf(false) }
    var showPageSizeDialog by remember { mutableStateOf(false) }
    var showPenDialog by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }
    var showImageWidthDialog by remember { mutableStateOf(false) }
    var showPageMenu by remember { mutableStateOf(false) }
    var showMemorizeColorPicker by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val pluginExporters = remember {
        (context.applicationContext as StylusMemoApp).pluginRegistry.noteExporters()
    }
    val memorizePluginList = remember {
        (context.applicationContext as StylusMemoApp).pluginRegistry.memorizePlugins()
    }
    val drawingToolList = remember {
        (context.applicationContext as StylusMemoApp).pluginRegistry.drawingToolPlugins()
    }
    var pendingImageUri by remember { mutableStateOf<android.net.Uri?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.flushSave()
            viewModel.unbindScratchView()
        }
    }

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

    val exportBase = remember(note) {
        (note?.title ?: "").trim().ifBlank { "note" }.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
    }
    val pdfExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        uri?.let { viewModel.exportPdf(it) }
    }
    val jpegExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/jpeg"),
    ) { uri ->
        uri?.let { viewModel.exportJpeg(it) }
    }

    LaunchedEffect(noteId) { viewModel.openNote(noteId) }
    BackHandler {
        if (!viewModel.snipBusy.value) onBack()
    }

    Scaffold(
        topBar = {
            EditorTopBar(
                title = note?.title ?: "",
                pageIndex = currentPageIndex,
                pageCount = viewModel.pageCount(),
                canUndo = canUndo,
                canRedo = canRedo,
                backEnabled = !snipBusy,
                onBack = { if (!viewModel.snipBusy.value) onBack() },
                onPrevPage = { viewModel.switchPage(currentPageIndex - 1) },
                onNextPage = { viewModel.switchPage(currentPageIndex + 1) },
                onAddPage = { viewModel.addPage() },
                onDeletePage = { viewModel.deletePage() },
                onUndo = { viewModel.undo() },
                onRedo = { viewModel.redo() },
                onExportPdf = { pdfExportLauncher.launch("$exportBase.pdf") },
                onExportJpeg = {
                    jpegExportLauncher.launch("$exportBase-p${currentPageIndex + 1}.jpg")
                },
                pluginExporters = pluginExporters,
                onExportPlugin = { id -> viewModel.exportPlugin(id) },
            )
        },
        bottomBar = {
            Column {
                if (memorizeActive) {
                    val memState = memorizePluginState
                    MemorizeBar(
                        eyedropperArmed = eyedropperArmed,
                        sheetColorArgb = (memState?.memorizeColorArgb ?: 0xFFE53935.toInt()).toLong(),
                        tolerance = memState?.tolerance ?: 90f,
                        onExit = { viewModel.endMemorize() },
                        onEyedropper = { viewModel.setMemorizeEyedropper(!eyedropperArmed) },
                        onColor = { showMemorizeColorPicker = true },
                        onCycleDirection = { viewModel.cycleMemorizeDirection() },
                        onClearScratch = { viewModel.clearScratch() },
                        onTolerance = { viewModel.setMemorizeTolerance(it) },
                        onToleranceCommit = { viewModel.persistMemorizeTolerance(it) },
                    )
                }
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
                    memorizeAvailable = memorizePluginList.isNotEmpty(),
                    memorizeActive = memorizeActive,
                    onMemorize = {
                        if (memorizeActive) viewModel.endMemorize()
                        else memorizePluginList.firstOrNull()?.let { viewModel.startMemorize(it) }
                    },
                    highlightAvailable = drawingToolList.isNotEmpty(),
                    onHighlight = {
                        drawingToolList.firstOrNull()?.let { viewModel.setHighlightTool(it) }
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxSize()) {
            // Both panes live in a single Box with absolute offsets so the note EditorView's
            // call site never moves between modes/directions. Compose therefore keeps the same
            // Android view instance (and its rendered surface) instead of recreating it.
            val memState = memorizePluginState
            val ratio = memState?.layout?.notePaneRatio ?: 0.5f
            val direction = memState?.layout?.direction
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val totalWPx = with(density) { maxWidth.toPx() }
                val totalHPx = with(density) { maxHeight.toPx() }
                val divider = 20.dp
                val horizontal = !memorizeActive ||
                    direction == null ||
                    direction == com.stylusmemo.app.plugin.memorize.MemorizePaneDirection.NOTE_LEFT ||
                    direction == com.stylusmemo.app.plugin.memorize.MemorizePaneDirection.NOTE_RIGHT
                val noteFirst = !memorizeActive ||
                    direction == null ||
                    direction == com.stylusmemo.app.plugin.memorize.MemorizePaneDirection.NOTE_LEFT ||
                    direction == com.stylusmemo.app.plugin.memorize.MemorizePaneDirection.NOTE_TOP

                val noteOffset: DpOffset
                val noteSize: DpSize
                val scratchOffset: DpOffset
                val scratchSize: DpSize
                if (!memorizeActive) {
                    noteOffset = DpOffset(0.dp, 0.dp)
                    noteSize = DpSize(maxWidth, maxHeight)
                    scratchOffset = DpOffset(0.dp, 0.dp)
                    scratchSize = DpSize(0.dp, 0.dp)
                } else if (horizontal) {
                    val noteW = (maxWidth - divider) * ratio
                    val scratchW = (maxWidth - divider) - noteW
                    noteSize = DpSize(noteW, maxHeight)
                    scratchSize = DpSize(scratchW, maxHeight)
                    noteOffset = if (noteFirst) DpOffset(0.dp, 0.dp) else DpOffset(scratchW + divider, 0.dp)
                    scratchOffset = if (noteFirst) DpOffset(noteW + divider, 0.dp) else DpOffset(0.dp, 0.dp)
                } else {
                    val noteH = (maxHeight - divider) * ratio
                    val scratchH = (maxHeight - divider) - noteH
                    noteSize = DpSize(maxWidth, noteH)
                    scratchSize = DpSize(maxWidth, scratchH)
                    noteOffset = if (noteFirst) DpOffset(0.dp, 0.dp) else DpOffset(0.dp, scratchH + divider)
                    scratchOffset = if (noteFirst) DpOffset(0.dp, noteH + divider) else DpOffset(0.dp, 0.dp)
                }

                AndroidView(
                    factory = { ctx -> EditorView(ctx) },
                    update = { v -> viewModel.bindView(v) },
                    modifier = Modifier
                        .offset(noteOffset.x, noteOffset.y)
                        .size(noteSize)
                        .clipToBounds()
                        .zIndex(if (memorizeActive) 2f else 0f),
                )

                if (memorizeActive) {
                    val dividerModifier = if (horizontal) {
                        Modifier
                            .offset(
                                x = if (noteFirst) noteSize.width else scratchSize.width,
                                y = 0.dp,
                            )
                            .size(divider, maxHeight)
                            .zIndex(3f)
                    } else {
                        Modifier
                            .offset(
                                x = 0.dp,
                                y = if (noteFirst) noteSize.height else scratchSize.height,
                            )
                            .size(maxWidth, divider)
                            .zIndex(3f)
                    }
                    SplitDivider(
                        isRow = horizontal,
                        modifier = dividerModifier,
                        onDrag = { deltaPx ->
                            if (horizontal) {
                                val delta = if (noteFirst) deltaPx / totalWPx else -deltaPx / totalWPx
                                viewModel.nudgeMemorizeRatio(delta)
                            } else {
                                val delta = if (noteFirst) deltaPx / totalHPx else -deltaPx / totalHPx
                                viewModel.nudgeMemorizeRatio(delta)
                            }
                        },
                    )
                    if (memorizeActive) AndroidView(
                        factory = { ctx -> EditorView(ctx) },
                        update = { v -> viewModel.bindScratchView(v) },
                        modifier = Modifier.offset(scratchOffset.x, scratchOffset.y).size(scratchSize).clipToBounds(),
                    )
                }
            }
            if (loading) {
                Box(
                    Modifier.fillMaxSize().zIndex(10f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (pageLoading) {
                Box(
                    Modifier.fillMaxSize().zIndex(10f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 3.dp)
                }
            }
            }
            if (!loading && note?.id == noteId) {
                SnipShelf(
                    noteId = noteId,
                    snips = note?.snips.orEmpty(),
                    selectedId = selectedSnipId,
                    busy = snipBusy,
                    error = snipError,
                    viewModel = viewModel,
                )
            }
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
            highlightColorArgb = highlightColor,
            highlightSizeMm = highlightSize,
            highlightAvailable = drawingToolList.isNotEmpty(),
            fingerDraw = fingerDraw,
            onColor = { viewModel.setPenColor(it) },
            onSize = { viewModel.setPenSize(it) },
            onHighlightColor = { viewModel.setHighlightColor(it) },
            onHighlightSize = { viewModel.setHighlightSize(it) },
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
            pageIndex = currentPageIndex,
            pageCount = viewModel.pageCount(),
            onAdd = { viewModel.addPage(); showPageMenu = false },
            onDelete = { viewModel.deletePage(); showPageMenu = false },
            onSwitch = { viewModel.switchPage(it); showPageMenu = false },
            onPageSize = { showPageMenu = false; showPageSizeDialog = true },
            onMove = { delta ->
                val from = currentPageIndex
                viewModel.movePage(from, from + delta)
                showPageMenu = false
            },
            onDismiss = { showPageMenu = false },
        )
    }

    if (showMemorizeColorPicker) {
        val memState = memorizePluginState
        if (memState != null) {
            ColorPickerDialog(
                initialArgb = memState.memorizeColorArgb.toLong(),
                onDismiss = { showMemorizeColorPicker = false },
                onPick = { viewModel.setMemorizeColor(it.toInt()); showMemorizeColorPicker = false },
            )
        } else {
            showMemorizeColorPicker = false
        }
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
    backEnabled: Boolean,
    onBack: () -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onAddPage: () -> Unit,
    onDeletePage: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExportPdf: () -> Unit,
    onExportJpeg: () -> Unit,
    pluginExporters: List<NoteExporterPlugin>,
    onExportPlugin: (id: String) -> Unit,
) {
    var showExportMenu by remember { mutableStateOf(false) }
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
            IconButton(onClick = onBack, enabled = backEnabled) {
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
            IconButton(onClick = { showExportMenu = true }) {
                Icon(Icons.Default.Share, contentDescription = "書き出し")
            }
            DropdownMenu(expanded = showExportMenu, onDismissRequest = { showExportMenu = false }) {
                DropdownMenuItem(
                    text = { Text("PDF で書き出し") },
                    onClick = { showExportMenu = false; onExportPdf() },
                )
                DropdownMenuItem(
                    text = { Text("JPEG で書き出し（現在ページ）") },
                    onClick = { showExportMenu = false; onExportJpeg() },
                )
                if (pluginExporters.isNotEmpty()) {
                    DropdownMenuItem(
                        onClick = {},
                        enabled = false,
                        text = {
                            Text(
                                "拡張プラグイン",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                    pluginExporters.forEach { exporter ->
                        DropdownMenuItem(
                            text = { Text(exporter.displayName) },
                            onClick = { showExportMenu = false; onExportPlugin(exporter.id) },
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun SnipShelf(
    noteId: String,
    snips: List<com.stylusmemo.app.model.Snip>,
    selectedId: String?,
    busy: Boolean,
    error: String?,
    viewModel: EditorViewModel,
) {
    if (snips.isEmpty() && error == null && !busy) return
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth()) {
            if (error != null) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        error,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    IconButton(onClick = { viewModel.dismissSnipError() }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "SNIPエラーを閉じる", modifier = Modifier.size(14.dp))
                    }
                }
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
            if (snips.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    snips.forEachIndexed { index, snip ->
                        key(noteId, snip.id) {
                            FilterChip(
                                selected = selectedId == snip.id,
                                onClick = { viewModel.selectSnip(snip.id) },
                                label = { Text("SNIP ${index + 1} · ${snip.widthPx}x${snip.heightPx}") },
                            )
                        }
                    }
                }
                snips.firstOrNull { it.id == selectedId }?.let { snip ->
                    key(noteId, snip.id) {
                        SnipCard(noteId, snip, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun SnipCard(
    noteId: String,
    snip: com.stylusmemo.app.model.Snip,
    viewModel: EditorViewModel,
) {
    var bitmap by remember(noteId, snip.assetName, snip.collapsed) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(noteId, snip.assetName, snip.collapsed) { mutableStateOf(false) }
    var reloadKey by remember(noteId, snip.assetName) { mutableStateOf(0) }
    LaunchedEffect(noteId, snip.assetName, snip.collapsed, reloadKey) {
        if (snip.collapsed) return@LaunchedEffect
        failed = false
        var loaded: android.graphics.Bitmap? = null
        try {
            loaded = viewModel.loadSnipBitmap(noteId, snip.assetName)
            bitmap = loaded
            failed = loaded == null
            kotlinx.coroutines.awaitCancellation()
        } finally {
            bitmap = null
            loaded?.recycle()
        }
    }
    val borderColor = MaterialTheme.colorScheme.primary
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(2.dp, borderColor),
        modifier = Modifier.clickable { viewModel.selectSnip(snip.id) },
    ) {
        Column(Modifier.widthIn(min = 120.dp, max = 220.dp)) {
            if (snip.collapsed) {
                Row(Modifier.fillMaxWidth().clickable { viewModel.collapseSnip(snip.id) }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Crop, contentDescription = "スニップを展開", modifier = Modifier.size(16.dp))
                    Text(
                        "${snip.widthPx}x${snip.heightPx}",
                        Modifier.padding(start = 4.dp).weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            } else {
                when {
                    bitmap != null -> Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = "SNIP ${snip.widthPx}x${snip.heightPx}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(snip.displayHeightDp.dp),
                    )
                    failed -> Box(
                        Modifier.fillMaxWidth().height(snip.displayHeightDp.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        IconButton(onClick = { bitmap = null; failed = false; reloadKey++ }) {
                            Icon(Icons.Default.Refresh, contentDescription = "SNIP画像を再読み込み")
                        }
                    }
                    else -> Box(
                        Modifier.fillMaxWidth().height(snip.displayHeightDp.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.collapseSnip(snip.id) }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        if (snip.collapsed) Icons.Default.OpenInFull else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (snip.collapsed) "スニップを展開" else "スニップを折りたたむ",
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { viewModel.deleteSnip(snip.id) }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "スニップを削除", modifier = Modifier.size(14.dp))
                }
            }
            androidx.compose.material3.Slider(
                value = snip.displayHeightDp,
                onValueChange = { viewModel.resizeSnip(snip.id, it) },
                valueRange = 80f..320f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(24.dp),
            )
        }
    }
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
    memorizeAvailable: Boolean = false,
    memorizeActive: Boolean = false,
    onMemorize: () -> Unit = {},
    highlightAvailable: Boolean = false,
    onHighlight: () -> Unit = {},
) {
    Surface(shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ToolIconButton(Icons.Default.Draw, "ペン", tool == EditorTool.PEN) { onTool(EditorTool.PEN) }
            if (highlightAvailable) {
                ToolIconButton(Icons.Default.Highlight, "蛍光ペン", tool == EditorTool.HIGHLIGHT) { onHighlight() }
            }
            ToolIconButton(Icons.Default.CleaningServices, "消しゴム", tool == EditorTool.ERASER) { onTool(EditorTool.ERASER) }
            ToolIconButton(Icons.Default.SelectAll, "選択", tool == EditorTool.SELECT) { onTool(EditorTool.SELECT) }
            ToolIconButton(Icons.Default.HorizontalRule, "直線", tool == EditorTool.STRAIGHT_LINE) { onTool(EditorTool.STRAIGHT_LINE) }
            ToolIconButton(Icons.Default.AutoFixOff, "擦って消去", tool == EditorTool.SCRIBBLE_ERASE) { onTool(EditorTool.SCRIBBLE_ERASE) }
            ToolIconButton(Icons.Default.Gesture, "なげなわ", tool == EditorTool.LASSO) { onTool(EditorTool.LASSO) }
            ToolIconButton(Icons.Default.CropSquare, "SNIP", tool == EditorTool.SNIP) { onTool(EditorTool.SNIP) }

            Box(
                Modifier.size(30.dp).padding(4.dp).clickable(onClick = onPenDialog)
                    .background(Color(penColor.toInt()), CircleShape),
            )

            ToolIconButton(Icons.Default.TextFields, "テキスト", false) { onAddText() }
            ToolIconButton(Icons.Default.Image, "画像を挿入", false) { onImportImage() }
            ToolIconButton(Icons.Default.PictureAsPdf, "PDFを挿入", false) { onImportPdf() }

            Divider(Modifier.height(28.dp).width(1.dp))

            ToolIconButton(Icons.Default.GridOn, "背景", false) { onBackground() }
            ToolIconButton(Icons.Default.Description, "ページ管理", false) { onPageMenu() }
            ToolIconButton(
                when (layoutMode) {
                    PageLayoutMode.SINGLE -> Icons.Default.CropSquare
                    PageLayoutMode.VERTICAL -> Icons.Default.ViewStream
                    PageLayoutMode.HORIZONTAL -> Icons.Default.ViewColumn
                },
                layoutMode.displayName,
                false,
            ) { onLayoutMode() }
            if (memorizeAvailable) {
                ToolIconButton(Icons.Default.School, "暗記モード", memorizeActive) { onMemorize() }
            }
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

/** Extra controls shown above the shared toolbar while memorization mode is active. */
@Composable
private fun MemorizeBar(
    eyedropperArmed: Boolean,
    sheetColorArgb: Long,
    tolerance: Float,
    onExit: () -> Unit,
    onEyedropper: () -> Unit,
    onColor: () -> Unit,
    onCycleDirection: () -> Unit,
    onClearScratch: () -> Unit,
    onTolerance: (Float) -> Unit,
    onToleranceCommit: (Float) -> Unit,
) {
    Surface(shadowElevation = 4.dp) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onExit) { Text("終了") }
            Divider(Modifier.height(28.dp).width(1.dp))
            ToolIconButton(Icons.Default.Colorize, "スポイト", eyedropperArmed) { onEyedropper() }
            Box(
                Modifier.size(30.dp).padding(4.dp).clickable(onClick = onColor)
                    .background(Color(sheetColorArgb.toInt()), CircleShape),
            )
            Column(Modifier.width(200.dp)) {
                Text(
                    "色の幅 ${tolerance.toInt()}",
                    style = MaterialTheme.typography.labelSmall,
                )
                androidx.compose.material3.Slider(
                    value = tolerance,
                    onValueChange = onTolerance,
                    onValueChangeFinished = { onToleranceCommit(tolerance) },
                    valueRange = 0f..250f,
                    modifier = Modifier.height(28.dp),
                )
            }
            ToolIconButton(Icons.Default.Refresh, "レイアウト向き", false) { onCycleDirection() }
            ToolIconButton(Icons.Default.DeleteSweep, "なぐり書きを消す", false) { onClearScratch() }
        }
    }
}

@Composable
private fun SplitDivider(
    isRow: Boolean,
    modifier: Modifier,
    onDrag: (Float) -> Unit,
) {
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(isRow) {
                if (isRow) {
                    detectHorizontalDragGestures { _, dx -> onDrag(dx) }
                } else {
                    detectVerticalDragGestures { _, dy -> onDrag(dy) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .then(if (isRow) Modifier.width(4.dp).fillMaxHeight(0.35f) else Modifier.height(4.dp).fillMaxWidth(0.35f))
                .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
        )
    }
}

@Composable
private fun PenSettingsDialog(
    colorArgb: Long,
    sizeMm: Float,
    highlightColorArgb: Long,
    highlightSizeMm: Float,
    highlightAvailable: Boolean,
    fingerDraw: Boolean,
    onColor: (Long) -> Unit,
    onSize: (Float) -> Unit,
    onHighlightColor: (Long) -> Unit,
    onHighlightSize: (Float) -> Unit,
    onFingerDraw: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // "pen" or "highlight" while a colour picker is open.
    var picking by remember { mutableStateOf<String?>(null) }
    var size by remember(sizeMm) { mutableStateOf(sizeMm) }
    var hlSize by remember(highlightSizeMm) { mutableStateOf(highlightSizeMm) }
    var finger by remember(fingerDraw) { mutableStateOf(fingerDraw) }

    if (picking != null) {
        val isHighlight = picking == "highlight"
        ColorPickerDialog(
            initialArgb = if (isHighlight) highlightColorArgb else colorArgb,
            onDismiss = { picking = null },
            onPick = {
                if (isHighlight) onHighlightColor(it) else onColor(it)
                picking = null
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ペンの設定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ペン", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("色", style = MaterialTheme.typography.bodyMedium)
                    Box(
                        Modifier.size(32.dp).background(Color(colorArgb.toInt()), CircleShape)
                            .clickable { picking = "pen" },
                    )
                }
                Column {
                    Text("線の太さ ${"%.1f".format(size)} mm", style = MaterialTheme.typography.bodyMedium)
                    Slider(value = size, onValueChange = { size = it; onSize(it) }, valueRange = 0.2f..4f)
                }
                if (highlightAvailable) {
                    Divider()
                    Text("蛍光ペン", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("色", style = MaterialTheme.typography.bodyMedium)
                        Box(
                            Modifier.size(32.dp).background(Color(highlightColorArgb.toInt()), CircleShape)
                                .clickable { picking = "highlight" },
                        )
                    }
                    Column {
                        Text("太さ ${"%.1f".format(hlSize)} mm", style = MaterialTheme.typography.bodyMedium)
                        Slider(value = hlSize, onValueChange = { hlSize = it; onHighlightSize(it) }, valueRange = 1f..10f)
                    }
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
    onMove: (Int) -> Unit,
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
                    OutlinedButton(onClick = { onMove(-1) }, enabled = pageIndex > 0) { Text("前へ移動") }
                    OutlinedButton(onClick = { onMove(1) }, enabled = pageIndex < pageCount - 1) { Text("後へ移動") }
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
