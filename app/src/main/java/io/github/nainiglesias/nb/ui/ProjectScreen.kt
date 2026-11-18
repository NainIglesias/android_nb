package io.github.nainiglesias.nb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nainiglesias.nb.data.Project
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    pvm: ProjectsViewModel,
    project: Project,
    snackbar: SnackbarHostState,
    onOpenNotebook: (File) -> Unit,
    onAddFiles: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var newKind by rememberSaveable { mutableStateOf<FileKind?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Entry?>(null) }

    // Coming back from a notebook or a Markdown file: show saved changes.
    LaunchedEffect(project.id) { pvm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (!pvm.up()) pvm.closeProject() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                title = {
                    androidx.compose.foundation.layout.Column {
                        Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            projectSubtitle(project),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (project.github != null) {
                        TextButton(onClick = { pvm.pull() }, enabled = pvm.sync == null) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                            Text(" Pull")
                        }
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Add files from phone…") }, onClick = { menu = false; onAddFiles() })
                        DropdownMenuItem(text = { Text("Refresh list") }, onClick = { menu = false; pvm.refresh() })
                        if (project.github != null) {
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Reset to GitHub…") },
                                enabled = pvm.sync == null,
                                onClick = { menu = false; confirmReset = true },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { addMenu = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("New") },
                )
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text("Notebook") }, onClick = { addMenu = false; newKind = FileKind.NOTEBOOK })
                    DropdownMenuItem(text = { Text("Markdown file") }, onClick = { addMenu = false; newKind = FileKind.MARKDOWN })
                    DropdownMenuItem(text = { Text("Folder") }, onClick = { addMenu = false; newKind = FileKind.FOLDER })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item {
                pvm.sync?.let { SyncBanner(it) }
                Breadcrumbs(project.name, pvm.folder, onGo = pvm::goTo)
            }
            if (pvm.entries.isEmpty()) {
                item {
                    Text(
                        if (pvm.folder.isEmpty()) "No files yet. Tap New to add a notebook or a Markdown file."
                        else "This folder is empty.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(pvm.entries, key = { it.name }) { entry ->
                var rowMenu by remember { mutableStateOf(false) }
                ListItem(
                    leadingContent = { KindBadge(entry.kind, entry.name) },
                    headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (entry.kind == FileKind.FOLDER) null else {
                        { Text(formatSize(entry.size)) }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (entry.kind == FileKind.FOLDER) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            }
                            IconButton(onClick = { rowMenu = true }) { Icon(Icons.Default.MoreVert, "Options") }
                            DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { rowMenu = false; deleting = entry })
                            }
                        }
                    },
                    modifier = Modifier.clickable(enabled = entry.kind != FileKind.OTHER) {
                        when (entry.kind) {
                            FileKind.FOLDER -> pvm.enterFolder(entry.name)
                            FileKind.NOTEBOOK -> onOpenNotebook(entry.file)
                            FileKind.MARKDOWN -> pvm.openMarkdown(entry.file)
                            FileKind.OTHER -> Unit
                        }
                    },
                )
            }
            item { Box(Modifier.padding(40.dp)) } // room for the button
        }
    }

    newKind?.let { kind ->
        val what = when (kind) {
            FileKind.NOTEBOOK -> "notebook"
            FileKind.MARKDOWN -> "Markdown file"
            else -> "folder"
        }
        NameDialog("New $what", "Name", "Create", onDismiss = { newKind = null }) { name ->
            newKind = null
            val file = pvm.create(name, kind)
            when {
                file == null -> Unit
                kind == FileKind.NOTEBOOK -> onOpenNotebook(file)
                kind == FileKind.MARKDOWN -> pvm.openMarkdown(file, editing = true)
            }
        }
    }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${entry.name}?") },
            text = {
                Text(
                    (if (entry.kind == FileKind.FOLDER) "The folder and everything in it is deleted from this phone."
                    else "The file is deleted from this phone.") +
                        if (project.github != null) " Pulling from GitHub brings it back." else ""
                )
            },
            confirmButton = { TextButton(onClick = { deleting = null; pvm.deleteEntry(entry) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset to GitHub?") },
            text = {
                Text(
                    "Every file that came from GitHub is replaced by the GitHub version, so your " +
                        "changes to them are lost. Files you added yourself stay."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; pvm.pull(overwriteLocal = true) }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Breadcrumbs(root: String, folder: String, onGo: (String) -> Unit) {
    if (folder.isEmpty()) return
    val parts = folder.split('/')
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
    ) {
        TextButton(onClick = { onGo("") }) { Text(root, maxLines = 1) }
        parts.forEachIndexed { i, name ->
            Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val path = parts.take(i + 1).joinToString("/")
            TextButton(onClick = { onGo(path) }, enabled = i < parts.lastIndex) { Text(name, maxLines = 1) }
        }
    }
}

@Composable
internal fun KindBadge(kind: FileKind, name: String, size: Dp = 40.dp) {
    val (label, color) = when (kind) {
        FileKind.FOLDER -> "DIR" to MaterialTheme.colorScheme.secondary
        FileKind.NOTEBOOK -> "NB" to Color(0xFFF37726) // Jupyter orange
        FileKind.MARKDOWN -> "MD" to MaterialTheme.colorScheme.primary
        FileKind.OTHER -> name.substringAfterLast('.', "").take(4).uppercase().ifEmpty { "FILE" } to
            MaterialTheme.colorScheme.outline
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 5))
            .background(color.copy(alpha = 0.16f)),
    ) {
        Text(
            label,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = if (size < 40.dp) 9.sp else 11.sp,
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024))
}
