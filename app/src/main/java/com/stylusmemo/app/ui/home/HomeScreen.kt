package com.stylusmemo.app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.stylusmemo.app.model.Note
import androidx.ink.strokes.Stroke
import com.stylusmemo.app.ui.components.BackgroundConfigDialog
import com.stylusmemo.app.ui.components.PageSizeDialog
import com.stylusmemo.app.ui.components.PageSizeSelection
import com.stylusmemo.app.ui.common.NoteThumbnailView
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenNote: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val visibleNotes by viewModel.visibleNotes.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val currentFolder by viewModel.currentFolder.collectAsState()
    val thumbnails by viewModel.thumbnails.collectAsState()
    val createdId by viewModel.createdNoteId.collectAsState()
    val loading by viewModel.loading.collectAsState()
    var showNewDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(createdId) {
        createdId?.let {
            viewModel.consumeCreatedNoteId()
            onOpenNote(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (currentFolder.isEmpty()) "StylusMemo" else currentFolder) },
                navigationIcon = {
                    if (currentFolder.isNotEmpty()) {
                        IconButton(onClick = {
                            viewModel.setFolder(currentFolder.substringBeforeLast('/', ""))
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上の階層")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "設定")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (fabExpanded) {
                    ExtendedFloatingActionButton(
                        text = { Text("新規メモ") },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        onClick = { fabExpanded = false; showNewDialog = true },
                    )
                    Spacer(Modifier.height(12.dp))
                    ExtendedFloatingActionButton(
                        text = { Text("新規フォルダ") },
                        icon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                        onClick = { fabExpanded = false; showNewFolderDialog = true },
                    )
                    Spacer(Modifier.height(12.dp))
                }
                FloatingActionButton(onClick = { fabExpanded = !fabExpanded }) {
                    Icon(
                        if (fabExpanded) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = if (fabExpanded) "閉じる" else "追加",
                    )
                }
            }
        },
    ) { padding ->
        if (loading) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Text(
                    "読み込み中…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        } else if (visibleNotes.isEmpty() && folders.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("メモがありません", style = MaterialTheme.typography.titleMedium)
                Text(
                    "右下の＋ボタンから新しいメモを作成できます",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(folders, key = { "f:$it" }) { folder ->
                    FolderCard(name = folder.substringAfterLast('/')) {
                        viewModel.setFolder(
                            if (currentFolder.isEmpty()) folder else "$currentFolder/$folder",
                        )
                    }
                }
                items(visibleNotes, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        strokes = thumbnails[note.id] ?: emptyList(),
                        folders = folders,
                        currentFolder = currentFolder,
                        onClick = { onOpenNote(note.id) },
                        onRename = { viewModel.renameNote(note.id, it) },
                        onDelete = { viewModel.deleteNote(note.id) },
                        onMove = { viewModel.moveNote(note.id, it) },
                    )
                }
            }
        }
    }

    if (showNewDialog) {
        NewNoteDialog(
            defaultWidthMm = viewModel.defaultPageSizeMm.first,
            defaultHeightMm = viewModel.defaultPageSizeMm.second,
            defaultBackground = viewModel.defaultBackground,
            onCreate = { title, w, h, bg ->
                showNewDialog = false
                viewModel.createNote(title, w, h, bg)
            },
            onDismiss = { showNewDialog = false },
        )
    }

    if (showNewFolderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("新しいフォルダ") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("フォルダ名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.createFolder(name)
                        showNewFolderDialog = false
                    },
                    enabled = name.isNotBlank(),
                ) { Text("作成") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text("キャンセル") }
            },
        )
    }
}

@Composable
private fun FolderCard(name: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NoteCard(
    note: Note,
    strokes: List<Stroke>,
    folders: List<String>,
    currentFolder: String,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onMove: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var moveOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                val ctx = LocalContext.current
                AndroidView(
                    factory = { ctx -> NoteThumbnailView(ctx) },
                    update = { v -> v.setNote(note, strokes, 0) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                note.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${note.pages.size} ページ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(
            text = { Text("名前を変更") },
            onClick = {
                menuOpen = false
                renameOpen = true
            },
        )
        DropdownMenuItem(
            text = { Text("フォルダへ移動") },
            leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
            onClick = {
                menuOpen = false
                moveOpen = true
            },
        )
        DropdownMenuItem(
            text = { Text("削除", color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
            onClick = {
                menuOpen = false
                onDelete()
            },
        )
    }

    if (renameOpen) {
        var title by remember { mutableStateOf(note.title) }
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("名前を変更") },
            text = {
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    renameOpen = false
                    onRename(title)
                }) { Text("OK") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { renameOpen = false }) { Text("キャンセル") }
            },
        )
    }

    if (moveOpen) {
        var newFolder by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { moveOpen = false },
            title = { Text("フォルダへ移動") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (folders.isNotEmpty()) {
                        folders.forEach { f ->
                            val target = if (currentFolder.isEmpty()) f else "$currentFolder/$f"
                            OutlinedButton(
                                onClick = { moveOpen = false; onMove(target) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(f.substringAfterLast('/')) }
                        }
                    }
                    OutlinedButton(
                        onClick = { moveOpen = false; onMove("") },
                        enabled = currentFolder.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("（ルート）") }
                    OutlinedTextField(
                        value = newFolder,
                        onValueChange = { newFolder = it },
                        label = { Text("新しいフォルダ名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            val name = newFolder.trim()
                            if (name.isNotEmpty()) {
                                val target = if (currentFolder.isEmpty()) name else "$currentFolder/$name"
                                moveOpen = false
                                onMove(target)
                            }
                        },
                        enabled = newFolder.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("新規フォルダとして移動") }
                }
            },
            confirmButton = {
                TextButton(onClick = { moveOpen = false }) { Text("閉じる") }
            },
        )
    }
}

@Composable
private fun NewNoteDialog(
    defaultWidthMm: Float,
    defaultHeightMm: Float,
    defaultBackground: com.stylusmemo.app.model.BackgroundSpec,
    onCreate: (String, Float, Float, com.stylusmemo.app.model.BackgroundSpec) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var size by remember { mutableStateOf(PageSizeSelection(defaultWidthMm, defaultHeightMm)) }
    var background by remember { mutableStateOf(defaultBackground) }
    var showPageSize by remember { mutableStateOf(false) }
    var showBackground by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新しいメモ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("タイトル") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showPageSize = true }) {
                        Text("${size.widthMm.toInt()}×${size.heightMm.toInt()} mm")
                    }
                    OutlinedButton(onClick = { showBackground = true }) {
                        Text(background.type.displayName)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(title.ifBlank { "無題のメモ" }, size.widthMm, size.heightMm, background) }) {
                Text("作成")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )

    if (showPageSize) {
        PageSizeDialog(
            initial = size,
            onApply = { size = it; showPageSize = false },
            onDismiss = { showPageSize = false },
        )
    }
    if (showBackground) {
        BackgroundConfigDialog(
            initial = background,
            onApply = { background = it; showBackground = false },
            onDismiss = { showBackground = false },
        )
    }
}
