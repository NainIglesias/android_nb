package io.github.nainiglesias.nb

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.nainiglesias.nb.ui.HomeScreen
import io.github.nainiglesias.nb.ui.MarkdownScreen
import io.github.nainiglesias.nb.ui.ProjectScreen
import io.github.nainiglesias.nb.ui.ProjectsViewModel
import io.github.nainiglesias.nb.ui.NotebookScreen
import io.github.nainiglesias.nb.ui.NotebookViewModel
import io.github.nainiglesias.nb.ui.theme.NbTheme

class MainActivity : ComponentActivity() {
    private val vm: NotebookViewModel by viewModels()
    private val pvm: ProjectsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            NbTheme {
                val snackbar = remember { SnackbarHostState() }
                val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let(vm::open)
                }
                val saveAsLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/x-ipynb+json")
                ) { uri -> vm.saveAs(uri) }
                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments()
                ) { uris -> vm.importToWorkspace(uris) }
                val addToProjectLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments()
                ) { uris -> pvm.importFiles(uris) }
                var askMarkdownDiscard by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
                LaunchedEffect(Unit) { pvm.messages.collect { snackbar.showSnackbar(it) } }
                LaunchedEffect(Unit) { vm.saveAsRequests.collect { name -> saveAsLauncher.launch(name) } }

                // Screens stack up: home → project → notebook or Markdown file.
                val doc = vm.doc
                val markdown = pvm.markdown
                val project = pvm.current
                when {
                    doc != null -> {
                        BackHandler {
                            if (vm.editingId != null) vm.editingId = null else vm.close()
                        }
                        NotebookScreen(vm, doc, snackbar, onImportFiles = { importLauncher.launch(arrayOf("*/*")) })
                    }
                    markdown != null -> {
                        BackHandler {
                            if (markdown.dirty) askMarkdownDiscard = true else pvm.closeMarkdown()
                        }
                        MarkdownScreen(pvm, markdown, snackbar, askMarkdownDiscard) { askMarkdownDiscard = it }
                    }
                    project != null -> {
                        BackHandler { if (!pvm.up()) pvm.closeProject() }
                        ProjectScreen(
                            pvm, project, snackbar,
                            onOpenNotebook = vm::openFile,
                            onAddFiles = { addToProjectLauncher.launch(arrayOf("*/*")) },
                        )
                    }
                    else -> HomeScreen(vm, pvm, snackbar, onOpenFile = { openLauncher.launch(arrayOf("*/*")) })
                }

                if (vm.pendingDiscard != null) {
                    AlertDialog(
                        onDismissRequest = { vm.resolveDiscard(null) },
                        title = { Text("Save changes?") },
                        text = { Text("${doc?.name ?: "This notebook"} has unsaved changes.") },
                        confirmButton = { TextButton(onClick = { vm.resolveDiscard(true) }) { Text("Save") } },
                        dismissButton = {
                            TextButton(onClick = { vm.resolveDiscard(false) }) { Text("Discard") }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "Open with" (VIEW) or "Share" (SEND) from another app. */
    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        uri?.let(vm::open)
    }
}
