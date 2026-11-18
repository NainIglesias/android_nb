package io.github.nainiglesias.nb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nainiglesias.nb.data.Project
import kotlinx.coroutines.launch
import java.io.File

/**
 * A side panel with the project's file tree, for moving between files without going back to the
 * project screen. [content] gets a function that opens the panel (for a button in its top bar).
 */
@Composable
fun ProjectDrawer(
    pvm: ProjectsViewModel,
    project: Project,
    current: File?,
    onOpen: (File, FileKind) -> Unit,
    onShowProject: () -> Unit,
    content: @Composable (openPanel: () -> Unit) -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    LaunchedEffect(current) { current?.let(pvm::reveal) }

    ModalNavigationDrawer(
        drawerState = drawer,
        // Swiping only closes it: an edge swipe to open would fight the code cells' horizontal scroll.
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(Modifier.widthIn(max = 320.dp)) {
                // Listed again each time the panel opens, so new and saved files show up.
                val rows = remember(project.id, pvm.expanded, drawer.targetValue) { pvm.treeRows() }
                Column(Modifier.fillMaxHeight()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                projectSubtitle(project),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { scope.launch { drawer.close() } }) { Icon(Icons.Default.Close, "Hide files") }
                    }
                    TextButton(
                        onClick = { scope.launch { drawer.close() }; onShowProject() },
                        modifier = Modifier.padding(start = 4.dp),
                    ) { Text("Project folder") }
                    HorizontalDivider()
                    val list = rememberLazyListState(
                        initialFirstVisibleItemIndex = rows.indexOfFirst { it.file == current }.coerceAtLeast(0),
                    )
                    LazyColumn(state = list, modifier = Modifier.weight(1f)) {
                        items(rows, key = { it.path }) { row ->
                            TreeLine(
                                row = row,
                                open = row.path in pvm.expanded,
                                selected = row.file == current,
                                onClick = {
                                    when (row.kind) {
                                        FileKind.FOLDER -> pvm.toggleExpanded(row.path)
                                        FileKind.OTHER -> Unit
                                        else -> {
                                            scope.launch { drawer.close() }
                                            if (row.file != current) onOpen(row.file, row.kind)
                                        }
                                    }
                                },
                            )
                        }
                        if (rows.isEmpty()) {
                            item { Text("No files.", modifier = Modifier.padding(16.dp)) }
                        }
                    }
                }
            }
        },
    ) {
        content { scope.launch { drawer.open() } }
    }
}

@Composable
private fun TreeLine(row: TreeRow, open: Boolean, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .then(if (selected) Modifier.background(colors.secondaryContainer) else Modifier)
            .clickable(enabled = row.kind != FileKind.OTHER, onClick = onClick)
            .padding(start = 8.dp + 16.dp * row.depth, end = 12.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (row.kind == FileKind.FOLDER) {
                Icon(
                    if (open) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    if (open) "Collapse" else "Expand",
                    Modifier.size(20.dp),
                )
            }
        }
        KindBadge(row.kind, row.name, size = 26.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            row.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else null,
            color = if (row.kind == FileKind.OTHER) colors.onSurfaceVariant else colors.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
