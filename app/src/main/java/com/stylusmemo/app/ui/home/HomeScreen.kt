package com.stylusmemo.app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
    val notes by viewModel.notes.collectAsState()
    val createdId by viewModel.createdNoteId.collectAsState()
    var showNewDialog by remember { mutableStateOf(false) }

    LaunchedEffect(createdId) {
        createdId?.let {
            viewModel.consumeCreatedNoteId()
            onOpenNote(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("StylusMemo") },
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
            FloatingActionButton(onClick = { showNewDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "新規メモ")
            }
        },
    ) { padding ->
        if (notes.isEmpty()) {
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
                items(notes, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        onClick = { onOpenNote(note.id) },
                        onRename = { viewModel.renameNote(note.id, it) },
                        onDelete = { viewModel.deleteNote(note.id) },
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
}

@Composable
private fun NoteCard(
    note: Note,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }

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
                    update = { v -> v.setNote(note, emptyList(), 0) },
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
