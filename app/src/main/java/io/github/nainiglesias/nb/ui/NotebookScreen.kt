package io.github.nainiglesias.nb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import android.content.ClipData
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import io.github.nainiglesias.nb.notebook.Cell
import io.github.nainiglesias.nb.notebook.CellType
import io.github.nainiglesias.nb.notebook.Editing
import io.github.nainiglesias.nb.ui.theme.LocalCodeColors

private val NoText = TextFieldValue()
private val NoChange: (TextFieldValue) -> Unit = {}
private val CodeStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.5.sp, lineHeight = 19.sp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookScreen(
    vm: NotebookViewModel,
    doc: OpenDoc,
    snackbar: SnackbarHostState,
    onImportFiles: () -> Unit,
    onShowFiles: (() -> Unit)? = null,
) {
    val cells = doc.notebook.cells
    val editingId = vm.editingId
    val editingCell = editingId?.let { id -> cells.firstOrNull { it.id == id } }
    var showWorkspace by remember { mutableStateOf(false) }

    // The text being edited lives here so the symbol bar can insert at the cursor.
    var editor by remember(editingId) {
        val source = editingCell?.source ?: ""
        mutableStateOf(TextFieldValue(source, TextRange(source.length)))
    }
    val onEditorChange: (TextFieldValue) -> Unit = { new ->
        val indented = if (editingCell?.type == CellType.CODE) {
            Editing.autoIndent(editor.text, new.text, new.selection.start)
        } else null
        editor = indented?.let { TextFieldValue(it.text, TextRange(it.cursor)) } ?: new
        editingId?.let { vm.setSource(it, editor.text) }
    }

    Scaffold(
        topBar = { NotebookTopBar(vm, doc, onImportFiles, onShowWorkspace = { showWorkspace = true }, onShowFiles) },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        bottomBar = {
            if (editingCell != null) {
                SymbolBar(
                    onInsert = { s ->
                        val e = Editing.insert(editor.text, editor.selection.start, editor.selection.end, s)
                        onEditorChange(TextFieldValue(e.text, TextRange(e.cursor)))
                    },
                    onRun = {
                        vm.editingId = null
                        if (editingCell.type == CellType.CODE) vm.run(editingCell.id)
                    },
                    runLabel = if (editingCell.type == CellType.CODE) "Run" else "Done",
                    modifier = Modifier.imePadding(),
                )
            }
        },
    ) { padding ->
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 8.dp, end = 8.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(cells, key = { _, c -> c.id }) { index, cell ->
                // Only the edited cell gets the live editor state, so typing doesn't recompose every cell.
                val editing = cell.id == editingId
                CellCard(
                    cell = cell,
                    index = index,
                    count = cells.size,
                    runState = vm.runStates[cell.id],
                    editing = editing,
                    editorValue = if (editing) editor else NoText,
                    onEditorChange = if (editing) onEditorChange else NoChange,
                    vm = vm,
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "add") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    OutlinedButton(onClick = { vm.insertCell(cells.size, CellType.CODE) }) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Code")
                    }
                    OutlinedButton(onClick = { vm.insertCell(cells.size, CellType.MARKDOWN) }) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Text")
                    }
                }
            }
        }
    }

    vm.inputRequest?.let { req -> InputDialog(req, onAnswer = vm::answerInput) }
    if (showWorkspace) WorkspaceDialog(vm.workspaceFiles(), onImport = onImportFiles) { showWorkspace = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotebookTopBar(
    vm: NotebookViewModel,
    doc: OpenDoc,
    onImportFiles: () -> Unit,
    onShowWorkspace: () -> Unit,
    onShowFiles: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = {
            Row {
                IconButton(onClick = vm::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close notebook") }
                if (onShowFiles != null) IconButton(onClick = onShowFiles) { Icon(Icons.Default.Menu, "Project files") }
            }
        },
        title = {
            Column {
                Text(
                    (if (doc.edited) "• " else "") + doc.name,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    kernelLabel(vm.kernelState, vm.busy),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = {
            if (vm.busy) {
                IconButton(onClick = vm::interrupt) { Icon(Icons.Default.Close, "Interrupt") }
            } else {
                IconButton(onClick = vm::runAll) { Icon(Icons.Default.PlayArrow, "Run all") }
            }
            if (doc.dirty) TextButton(onClick = { vm.save() }) { Text("Save") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                @Composable
                fun item(label: String, action: () -> Unit) =
                    DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() })
                item("Save") { vm.save() }
                item("Save a copy as…") { vm.requestSaveAs() }
                HorizontalDivider()
                item("Run all") { vm.runAll() }
                item("Restart Python") { vm.restart() }
                item("Restart and run all") { vm.restart(runAllAfter = true) }
                item("Clear all outputs") { vm.clearAllOutputs() }
                HorizontalDivider()
                item("Add data files…") { onImportFiles() }
                item("Workspace files") { onShowWorkspace() }
            }
        },
    )
}

fun kernelLabel(state: KernelState, busy: Boolean): String = when (state) {
    KernelState.Starting -> "Starting Python…"
    is KernelState.Failed -> "Python failed to start"
    is KernelState.Ready -> "Python ${state.version} · " + if (busy) "running" else "idle"
}

@Composable
private fun CellCard(
    cell: Cell,
    index: Int,
    count: Int,
    runState: RunState?,
    editing: Boolean,
    editorValue: TextFieldValue,
    onEditorChange: (TextFieldValue) -> Unit,
    vm: NotebookViewModel,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCodeColors.current
    val isCode = cell.type == CellType.CODE
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = if (editing) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        else androidx.compose.material3.CardDefaults.outlinedCardBorder(),
    ) {
        // Header: prompt, run button, menu.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
            Text(
                when {
                    !isCode -> if (cell.type == CellType.MARKDOWN) "Text" else "Raw"
                    runState == RunState.RUNNING -> "[*]"
                    runState == RunState.QUEUED -> "[…]"
                    else -> "[${cell.executionCount ?: " "}]"
                },
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (runState == RunState.RUNNING) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.weight(1f))
            if (isCode) {
                IconButton(onClick = { vm.run(cell.id) }, enabled = runState == null, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.PlayArrow, "Run cell", tint = MaterialTheme.colorScheme.primary)
                }
            }
            CellMenu(cell, index, count, vm)
        }

        // Body.
        val bodyModifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
        when {
            editing -> CellEditor(
                value = editorValue,
                onValueChange = onEditorChange,
                code = isCode,
                modifier = bodyModifier,
            )
            isCode || cell.type == CellType.RAW -> {
                val text = remember(cell.source, colors) {
                    if (isCode) highlightPython(cell.source, colors) else AnnotatedString(cell.source)
                }
                Box(
                    bodyModifier
                        .clip6()
                        .background(colors.codeBackground)
                        .clickable { vm.editingId = cell.id }
                        .heightIn(min = 36.dp)
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp)
                ) {
                    Text(text, style = CodeStyle, softWrap = false)
                }
            }
            else -> Box(bodyModifier.clickable { vm.editingId = cell.id }.heightIn(min = 28.dp)) {
                if (cell.source.isBlank()) {
                    Text(
                        "Empty text cell. Tap to write Markdown.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Markdown(cell.source, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (isCode && cell.outputs.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 8.dp),
            ) {
                cell.outputs.forEach { OutputView(it) }
            }
        }
    }
}

private fun Modifier.clip6() = this.then(Modifier.clip(RoundedCornerShape(6.dp)))

@Composable
private fun CellEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    code: Boolean,
    modifier: Modifier,
) {
    val colors = LocalCodeColors.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = CodeStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = if (code) PythonHighlighting(colors) else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = !code,
            keyboardType = if (code) KeyboardType.Ascii else KeyboardType.Text,
        ),
        modifier = modifier
            .clip6()
            .background(colors.codeBackground)
            .heightIn(min = 48.dp)
            .padding(8.dp)
            .focusRequester(focus),
    )
}

@Composable
private fun CellMenu(cell: Cell, index: Int, count: Int, vm: NotebookViewModel) {
    var open by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    fun copy(text: String) = scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("notebook", text))) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.MoreVert, "Cell actions")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable
            fun item(label: String, enabled: Boolean = true, action: () -> Unit) =
                DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = { open = false; action() })
            item("Edit") { vm.editingId = cell.id }
            if (cell.type == CellType.CODE) {
                item("Run from here") { vm.runFrom(cell.id) }
                item("Clear output", enabled = cell.outputs.isNotEmpty()) { vm.clearOutputs(cell.id) }
                item("Copy output", enabled = cell.outputs.isNotEmpty()) {
                    copy(cell.outputs.joinToString("\n") { outputPlainText(it) })
                }
            }
            item("Copy source") { copy(cell.source) }
            HorizontalDivider()
            item("Insert code above") { vm.insertCell(index, CellType.CODE) }
            item("Insert code below") { vm.insertCell(index + 1, CellType.CODE) }
            item("Insert text below") { vm.insertCell(index + 1, CellType.MARKDOWN) }
            HorizontalDivider()
            item("Move up", enabled = index > 0) { vm.moveCell(cell.id, -1) }
            item("Move down", enabled = index < count - 1) { vm.moveCell(cell.id, +1) }
            if (cell.type == CellType.CODE) item("Change to text") { vm.setType(cell.id, CellType.MARKDOWN) }
            else item("Change to code") { vm.setType(cell.id, CellType.CODE) }
            HorizontalDivider()
            item("Delete cell") { vm.deleteCell(cell.id) }
        }
    }
}

private val SYMBOLS = listOf("⇥", "(", ")", "[", "]", "{", "}", ":", "=", "\"", "'", ".", ",", "_", "#", "+", "-", "*", "/", "<", ">", "!", "%")

@Composable
private fun SymbolBar(onInsert: (String) -> Unit, onRun: () -> Unit, runLabel: String, modifier: Modifier = Modifier) {
    Surface(tonalElevation = 3.dp, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp),
        ) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                SYMBOLS.forEach { s ->
                    TextButton(
                        onClick = { onInsert(if (s == "⇥") Editing.INDENT else s) },
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        modifier = Modifier.width(if (s == "⇥") 48.dp else 38.dp),
                    ) { Text(s, fontFamily = FontFamily.Monospace, fontSize = 17.sp) }
                }
            }
            TextButton(onClick = onRun) {
                if (runLabel == "Run") Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                Text(runLabel)
            }
        }
    }
}

@Composable
private fun InputDialog(req: InputRequest, onAnswer: (String?) -> Unit) {
    var text by rememberSaveable(req) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("input()") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(req.prompt.ifBlank { "Value" }) },
                singleLine = true,
                visualTransformation = if (req.password) PasswordVisualTransformation() else VisualTransformation.None,
            )
        },
        confirmButton = { TextButton(onClick = { onAnswer(text) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text("Interrupt") } },
    )
}

@Composable
private fun WorkspaceDialog(files: List<String>, onImport: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Workspace files") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Notebooks run in this folder, so code can open these files by name, " +
                        "for example pd.read_csv(\"data.csv\").",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (files.isEmpty()) Text("No files yet.", style = MaterialTheme.typography.bodyMedium)
                files.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onImport() }) { Text("Add files…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
