package io.github.nainiglesias.nb

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import java.io.File
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import io.github.nainiglesias.nb.data.LinkTarget
import io.github.nainiglesias.nb.data.Links
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import io.github.nainiglesias.nb.ui.FileKind
import io.github.nainiglesias.nb.ui.HomeScreen
import io.github.nainiglesias.nb.ui.ProjectDrawer
import io.github.nainiglesias.nb.ui.MarkdownScreen
import io.github.nainiglesias.nb.ui.ProjectScreen
import io.github.nainiglesias.nb.ui.ProjectsViewModel
import io.github.nainiglesias.nb.ui.fileKind
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

                LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
                LaunchedEffect(Unit) { pvm.messages.collect { snackbar.showSnackbar(it) } }
                LaunchedEffect(Unit) { vm.saveAsRequests.collect { name -> saveAsLauncher.launch(name) } }

                // Screens stack up: home → project → notebook or Markdown file.
                val doc = vm.doc
                val markdown = pvm.markdown
                val project = pvm.current
                // A project file open: the side panel lists the project's files to jump between them.
                val projectFile: File? = when {
                    project == null -> null
                    doc != null -> doc.uri?.takeIf { doc.workdir != null && it.scheme == "file" }?.path?.let(::File)
                    else -> markdown?.file
                }
                val openFromPanel: (File, FileKind) -> Unit = { file, kind ->
                    when {
                        vm.doc != null && kind == FileKind.NOTEBOOK -> vm.openFile(file)
                        vm.doc != null -> vm.closeThen { pvm.openMarkdown(file) }
                        kind == FileKind.NOTEBOOK -> pvm.leaveMarkdownThen { vm.openFile(file) }
                        else -> pvm.leaveMarkdownThen { pvm.openMarkdown(file) }
                    }
                }
                val showProject: () -> Unit = {
                    if (vm.doc != null) vm.close() else pvm.leaveMarkdownThen()
                }

                // Links in Markdown: files of the project open here, web links in other apps.
                val systemUris = LocalUriHandler.current
                val root = project?.let(pvm::dir)
                val linkHandler = remember(root, projectFile, systemUris) {
                    object : UriHandler {
                        override fun openUri(uri: String) {
                            when (val target = Links.resolve(uri, projectFile?.parentFile, root)) {
                                is LinkTarget.External -> runCatching { systemUris.openUri(target.url) }
                                    .onFailure { pvm.message("No app can open ${target.url}") }
                                LinkTarget.SamePage -> pvm.message("Links to a heading aren't supported yet.")
                                is LinkTarget.Missing -> pvm.message(
                                    if (root == null) "Links to other files only work inside a project."
                                    else "${target.path} isn't in this project."
                                )
                                is LinkTarget.Local -> {
                                    val file = target.file
                                    val kind = fileKind(file)
                                    when {
                                        file == projectFile -> Unit
                                        kind == FileKind.FOLDER -> {
                                            val path = file.relativeTo(root!!).invariantSeparatorsPath
                                            if (vm.doc != null) vm.closeThen { pvm.goTo(path) }
                                            else pvm.leaveMarkdownThen { pvm.goTo(path) }
                                        }
                                        kind == FileKind.OTHER -> pvm.message("${file.name} can't be opened here.")
                                        else -> openFromPanel(file, kind)
                                    }
                                }
                            }
                        }
                    }
                }
                CompositionLocalProvider(LocalUriHandler provides linkHandler) {
                    when {
                        doc != null -> {
                            BackHandler {
                                if (vm.editingId != null) vm.editingId = null else vm.close()
                            }
                            val importFiles = { importLauncher.launch(arrayOf("*/*")) }
                            if (project != null && projectFile != null) {
                                ProjectDrawer(pvm, project, projectFile, openFromPanel, showProject) { openPanel ->
                                    NotebookScreen(vm, doc, snackbar, onImportFiles = importFiles, onShowFiles = openPanel)
                                }
                            } else {
                                NotebookScreen(vm, doc, snackbar, onImportFiles = importFiles)
                            }
                        }
                        markdown != null && project != null -> {
                            BackHandler { pvm.leaveMarkdownThen() }
                            ProjectDrawer(pvm, project, markdown.file, openFromPanel, showProject) { openPanel ->
                                MarkdownScreen(pvm, markdown, snackbar, onShowFiles = openPanel)
                            }
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
