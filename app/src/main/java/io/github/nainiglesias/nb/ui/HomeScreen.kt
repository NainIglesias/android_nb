package io.github.nainiglesias.nb.ui

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nainiglesias.nb.data.Project

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: NotebookViewModel,
    pvm: ProjectsViewModel,
    snackbar: SnackbarHostState,
    onOpenFile: () -> Unit,
) {
    var dialog by rememberSaveable { mutableStateOf<String?>(null) } // "new" | "clone"
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Notebooks")
                        Text(
                            kernelLabel(vm.kernelState, busy = false),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Open a .ipynb file…") }, onClick = { menu = false; onOpenFile() })
                        DropdownMenuItem(text = { Text("Scratch notebook") }, onClick = { menu = false; vm.newNotebook() })
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item {
                if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                pvm.sync?.let { SyncBanner(it) }
                (vm.kernelState as? KernelState.Failed)?.let {
                    Text(
                        "Python couldn't start: ${it.message}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            if (pvm.projects.isEmpty()) {
                item { Welcome(onNew = { dialog = "new" }, onClone = { dialog = "clone" }, onOpenFile = onOpenFile) }
            } else {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Button(onClick = { dialog = "new" }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("New project")
                        }
                        OutlinedButton(
                            onClick = { dialog = "clone" },
                            enabled = pvm.sync == null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("From GitHub")
                        }
                    }
                }
                item { SectionTitle("Projects") }
                items(pvm.projects, key = { it.id }) { project ->
                    ProjectRow(project, pvm)
                }
            }

            if (vm.recent.isNotEmpty()) {
                item { SectionTitle("Recent files") }
                items(vm.recent, key = { it.uri }) { file ->
                    ListItem(
                        headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(DateUtils.getRelativeTimeSpanString(file.openedAt).toString())
                        },
                        trailingContent = {
                            IconButton(onClick = { vm.forgetRecent(file.uri) }) {
                                Icon(Icons.Default.Close, "Remove from recent")
                            }
                        },
                        modifier = Modifier.clickable { vm.open(Uri.parse(file.uri)) },
                    )
                }
            }
        }
    }

    when (dialog) {
        "new" -> NameDialog(
            title = "New project",
            label = "Name",
            confirm = "Create",
            onDismiss = { dialog = null },
        ) { name -> pvm.createProject(name); dialog = null }
        "clone" -> CloneDialog(pvm, onDismiss = { dialog = null })
    }
}

@Composable
private fun Welcome(onNew: () -> Unit, onClone: () -> Unit, onOpenFile: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Start with a project", style = MaterialTheme.typography.headlineSmall)
        Text(
            "A project is a folder of notebooks and Markdown notes. Notebooks run inside it, " +
                "so they can open the project's data files by name.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChoiceCard(
            title = "New project",
            body = "An empty folder. Add notebooks, Markdown files and data.",
            onClick = onNew,
        )
        ChoiceCard(
            title = "From GitHub",
            body = "Download a repository's notebooks and notes. Pull later to get updates; " +
                "your local edits are kept.",
            onClick = onClone,
        )
        TextButton(onClick = onOpenFile) { Text("Or open a single .ipynb file") }
    }
}

@Composable
private fun ChoiceCard(title: String, body: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ProjectRow(project: Project, pvm: ProjectsViewModel) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(projectSubtitle(project), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Project options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; rename = true })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; confirmDelete = true })
            }
        },
        modifier = Modifier.clickable { pvm.openProject(project) },
    )
    if (rename) {
        NameDialog("Rename project", "Name", "Rename", initial = project.name, onDismiss = { rename = false }) {
            pvm.renameProject(project, it); rename = false
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${project.name}?") },
            text = {
                Text(
                    "Its files are deleted from this phone." +
                        if (project.github != null) " The GitHub repository isn't touched." else ""
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; pvm.deleteProject(project) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

fun projectSubtitle(project: Project): String {
    val gh = project.github ?: return "Local project"
    val pulled = project.lastPull?.let { " · pulled " + DateUtils.getRelativeTimeSpanString(it) } ?: ""
    return "GitHub ${gh.slug}" + (gh.branch?.let { " ($it)" } ?: "") + pulled
}

@Composable
fun SyncBanner(progress: SyncProgress) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val counts = if (progress.total > 0) " · ${progress.done}/${progress.total} files" else "…"
        Text(progress.label + counts, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        if (progress.total > 0) {
            LinearProgressIndicator(
                progress = { progress.done.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun NameDialog(
    title: String,
    label: String,
    confirm: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CloneDialog(pvm: ProjectsViewModel, onDismiss: () -> Unit) {
    var repo by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf(pvm.githubToken ?: "") }
    var showToken by rememberSaveable { mutableStateOf(token.isNotEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val submit = {
        error = pvm.cloneProject(repo, token.ifBlank { null })
        if (error == null) onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("From GitHub") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it; error = null },
                    label = { Text("Repository") },
                    placeholder = { Text("owner/repo or github.com link") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showToken) {
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Access token (optional)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        supportingText = {
                            Text("For private repos. A fine-grained token with read access to Contents is enough.")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    TextButton(onClick = { showToken = true }, contentPadding = PaddingValues(0.dp)) {
                        Text("Private repository?")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = submit, enabled = repo.isNotBlank()) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
