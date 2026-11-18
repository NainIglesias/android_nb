package io.github.nainiglesias.nb.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownScreen(
    pvm: ProjectsViewModel,
    doc: MarkdownDoc,
    snackbar: SnackbarHostState,
    onShowFiles: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    Row {
                        IconButton(onClick = { pvm.leaveMarkdownThen() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close")
                        }
                        IconButton(onClick = onShowFiles) { Icon(Icons.Default.Menu, "Project files") }
                    }
                },
                title = {
                    Text(
                        (if (doc.dirty) "• " else "") + doc.file.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    if (doc.dirty) TextButton(onClick = pvm::saveMarkdown) { Text("Save") }
                    if (doc.editing) {
                        IconButton(onClick = { pvm.setMarkdownEditing(false) }) { Icon(Icons.Default.Check, "Preview") }
                    } else {
                        IconButton(onClick = { pvm.setMarkdownEditing(true) }) { Icon(Icons.Default.Edit, "Edit") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (doc.editing) {
                OutlinedTextField(
                    value = doc.text,
                    onValueChange = pvm::editMarkdown,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxSize().padding(8.dp).imePadding(),
                )
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    if (doc.text.isBlank()) {
                        Text(
                            "Empty file. Tap the pencil to write.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Markdown(doc.text, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    if (pvm.pendingLeave != null) {
        AlertDialog(
            onDismissRequest = { pvm.resolveLeave(null) },
            title = { Text("Save changes?") },
            text = { Text("${doc.file.name} has unsaved changes.") },
            confirmButton = { TextButton(onClick = { pvm.resolveLeave(true) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { pvm.resolveLeave(false) }) { Text("Discard") } },
        )
    }
}
