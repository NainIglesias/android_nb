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
import androidx.compose.runtime.remember
import io.github.nainiglesias.nb.ui.HomeScreen
import io.github.nainiglesias.nb.ui.NotebookScreen
import io.github.nainiglesias.nb.ui.NotebookViewModel
import io.github.nainiglesias.nb.ui.theme.NbTheme

class MainActivity : ComponentActivity() {
    private val vm: NotebookViewModel by viewModels()

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

                LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
                LaunchedEffect(Unit) { vm.saveAsRequests.collect { name -> saveAsLauncher.launch(name) } }

                val doc = vm.doc
                if (doc == null) {
                    HomeScreen(vm, snackbar, onOpen = { openLauncher.launch(arrayOf("*/*")) })
                } else {
                    BackHandler {
                        if (vm.editingId != null) vm.editingId = null else vm.close()
                    }
                    NotebookScreen(vm, doc, snackbar, onImportFiles = { importLauncher.launch(arrayOf("*/*")) })
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
