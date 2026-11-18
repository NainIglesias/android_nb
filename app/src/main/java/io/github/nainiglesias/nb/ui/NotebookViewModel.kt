package io.github.nainiglesias.nb.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.nainiglesias.nb.NbApp
import io.github.nainiglesias.nb.kernel.ExecResult
import io.github.nainiglesias.nb.kernel.ExecutionListener
import io.github.nainiglesias.nb.notebook.Cell
import io.github.nainiglesias.nb.notebook.CellType
import io.github.nainiglesias.nb.notebook.Changes
import io.github.nainiglesias.nb.notebook.Notebook
import io.github.nainiglesias.nb.notebook.NotebookFormatException
import io.github.nainiglesias.nb.notebook.NotebookIO
import io.github.nainiglesias.nb.notebook.Outputs
import io.github.nainiglesias.nb.notebook.newCellId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CompletableFuture

data class OpenDoc(
    val uri: Uri?,
    val name: String,
    val notebook: Notebook,
    /** The notebook as it is in the file (or as it was created), to tell whether there are changes. */
    val saved: Notebook = notebook,
    /** Anything differs from [saved], outputs included: offer to save. */
    val dirty: Boolean = false,
    /** Cells were edited (not just run): ask before closing. */
    val edited: Boolean = false,
    /** Folder cells run in: the notebook's own folder for project files, else the shared workspace. */
    val workdir: File? = null,
)

sealed interface KernelState {
    data object Starting : KernelState
    data class Ready(val version: String) : KernelState
    data class Failed(val message: String) : KernelState
}

enum class RunState { QUEUED, RUNNING }

class InputRequest(val prompt: String, val password: Boolean, val answer: CompletableFuture<String?>)

class NotebookViewModel(app: Application) : AndroidViewModel(app) {
    private val nbApp = app as NbApp
    private val kernel = nbApp.kernel
    private val recentStore = nbApp.recentFiles
    private val resolver = app.contentResolver
    private val main = Handler(Looper.getMainLooper())

    var kernelState by mutableStateOf<KernelState>(KernelState.Starting)
        private set
    var recent by mutableStateOf(recentStore.load())
        private set
    var doc by mutableStateOf<OpenDoc?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    /** Cell whose source is being edited, if any. */
    var editingId by mutableStateOf<String?>(null)

    val runStates = mutableStateMapOf<String, RunState>()
    var inputRequest by mutableStateOf<InputRequest?>(null)
        private set

    /** Something to do once unsaved changes are saved or discarded (asked in a dialog). */
    var pendingDiscard by mutableStateOf<(() -> Unit)?>(null)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    /** Emits a suggested file name when the UI should show the "save as" picker. */
    private val _saveAsRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val saveAsRequests: SharedFlow<String> = _saveAsRequests
    private var afterSaveAs: (() -> Unit)? = null

    private val queue = ArrayDeque<String>()
    private var runner: Job? = null

    val busy: Boolean get() = runStates.isNotEmpty()

    init {
        viewModelScope.launch {
            kernelState = try {
                KernelState.Ready(kernel.start())
            } catch (e: Throwable) {
                KernelState.Failed(e.message ?: e.toString())
            }
        }
    }

    private fun message(text: String) {
        _messages.tryEmit(text)
    }

    // ---------------------------------------------------------------- files

    /**
     * Run [action] now, or after the user saves or discards unsaved edits. New outputs alone
     * don't ask: they can be made again by running the notebook.
     */
    fun guardUnsaved(action: () -> Unit) {
        if (doc?.edited == true) pendingDiscard = action else action()
    }

    fun resolveDiscard(save: Boolean?) {
        val action = pendingDiscard ?: return
        pendingDiscard = null
        when (save) {
            true -> save(then = action)
            false -> action()
            null -> Unit // cancelled
        }
    }

    fun newNotebook() = guardUnsaved {
        switchTo(OpenDoc(uri = null, name = "Untitled.ipynb", notebook = NotebookIO.empty()))
    }

    fun close() = guardUnsaved { switchTo(null) }

    /** Close the notebook (asking first if it has unsaved changes), then run [action]. */
    fun closeThen(action: () -> Unit) = guardUnsaved {
        switchTo(null)
        action()
    }

    fun open(uri: Uri) = guardUnsaved { load(uri) }

    /** Open a notebook inside a project folder. Its cells run in that folder. */
    fun openFile(file: File) = guardUnsaved { load(Uri.fromFile(file), workdir = file.parentFile) }

    private fun load(uri: Uri, workdir: File? = null) {
        viewModelScope.launch {
            loading = true
            try {
                val (name, notebook) = withContext(Dispatchers.IO) {
                    val text = if (uri.scheme == "file") {
                        File(uri.path!!).readText()
                    } else {
                        keepPermission(uri)
                        resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                            ?: error("The file couldn't be read.")
                    }
                    displayName(uri) to NotebookIO.parse(text)
                }
                // Project files are listed in their project, not in "Recent".
                if (workdir == null) recent = recentStore.add(uri.toString(), name)
                switchTo(OpenDoc(uri, name, notebook, workdir = workdir))
            } catch (e: NotebookFormatException) {
                message(e.message ?: "Not a notebook.")
            } catch (e: Exception) {
                message("Couldn't open the file: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                loading = false
            }
        }
    }

    fun forgetRecent(uri: String) {
        recent = recentStore.remove(uri)
    }

    private fun switchTo(newDoc: OpenDoc?) {
        stopAll()
        editingId = null
        doc = newDoc
        viewModelScope.launch {
            runner?.join()
            try {
                kernel.newSession(newDoc?.workdir ?: kernel.workspace)
            } catch (e: Throwable) {
                kernelState = KernelState.Failed(e.message ?: e.toString())
            }
        }
    }

    /** Folder the current notebook runs in. */
    val workdir: File get() = doc?.workdir ?: kernel.workspace

    /** Save to the current file, or ask where to save if there isn't a writable one. */
    fun save(then: (() -> Unit)? = null) {
        val d = doc ?: return
        val uri = d.uri
        if (uri == null) {
            requestSaveAs(then)
            return
        }
        viewModelScope.launch {
            val text = NotebookIO.serialize(d.notebook)
            val error = withContext(Dispatchers.IO) { runCatching { write(uri, text) }.exceptionOrNull() }
            if (error == null) {
                markSaved(d, uri, d.name)
                then?.invoke()
            } else {
                message("Can't write to this file (${error.javaClass.simpleName}). Save a copy instead.")
                requestSaveAs(then)
            }
        }
    }

    fun requestSaveAs(then: (() -> Unit)? = null) {
        afterSaveAs = then
        _saveAsRequests.tryEmit(doc?.name ?: "Untitled.ipynb")
    }

    /** Result of the "save as" picker; null if the user cancelled. */
    fun saveAs(uri: Uri?) {
        val then = afterSaveAs
        afterSaveAs = null
        val d = doc ?: return
        if (uri == null) return
        viewModelScope.launch {
            val text = NotebookIO.serialize(d.notebook)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    keepPermission(uri)
                    write(uri, text)
                    displayName(uri)
                }
            }
            result.onSuccess { name ->
                recent = recentStore.add(uri.toString(), name)
                markSaved(d, uri, name)
                then?.invoke()
            }.onFailure { message("Couldn't save: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    private fun markSaved(saved: OpenDoc, uri: Uri, name: String) {
        val current = doc ?: return
        // Edits made while writing keep the document dirty.
        doc = current.copy(uri = uri, name = name, saved = saved.notebook).withChanges(current.notebook)
        message("Saved $name")
    }

    private fun write(uri: Uri, text: String) {
        if (uri.scheme == "file") {
            val file = File(uri.path!!)
            val tmp = File(file.parentFile, ".${file.name}.saving")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) error("couldn't replace ${file.name}")
            return
        }
        val out = resolver.openOutputStream(uri, "wt") ?: error("no output stream")
        out.use { it.write(text.encodeToByteArray()) }
    }

    private fun keepPermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // A one-off grant (e.g. "open with" from another app): fine for now.
            }
        }
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path!!).name
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "notebook.ipynb"
    }

    /** Copy files into the folder notebooks run in, so code can open them by name. */
    fun importToWorkspace(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val names = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        val name = displayName(uri).replace('/', '_')
                        resolver.openInputStream(uri)!!.use { input ->
                            File(workdir, name).outputStream().use { input.copyTo(it) }
                        }
                        name
                    }.getOrNull()
                }
            }
            message(
                if (names.isEmpty()) "Nothing was copied."
                else "Copied ${names.joinToString()} — open with open(\"${names.first()}\")"
            )
        }
    }

    fun workspaceFiles(): List<String> =
        workdir.list()?.filter { !it.startsWith(".") }?.sorted() ?: emptyList()

    // ---------------------------------------------------------------- editing

    private fun edit(f: (List<Cell>) -> List<Cell>) {
        val d = doc ?: return
        val cells = f(d.notebook.cells)
        if (cells === d.notebook.cells) return
        doc = d.withChanges(d.notebook.copy(cells = cells))
    }

    private fun OpenDoc.withChanges(notebook: Notebook): OpenDoc {
        val edited = !Changes.sameSources(notebook, saved)
        return copy(notebook = notebook, edited = edited, dirty = edited || !Changes.same(notebook, saved))
    }

    private fun updateCell(id: String, f: (Cell) -> Cell) = edit { cells ->
        val i = cells.indexOfFirst { it.id == id }
        if (i < 0) cells else cells.toMutableList().also { it[i] = f(it[i]) }
    }

    fun cell(id: String): Cell? = doc?.notebook?.cells?.firstOrNull { it.id == id }

    fun setSource(id: String, source: String) = updateCell(id) {
        if (it.source == source) it else it.copy(source = source)
    }

    fun insertCell(index: Int, type: CellType): String {
        val cell = Cell(newCellId(), type, "")
        edit { cells -> cells.toMutableList().also { it.add(index.coerceIn(0, cells.size), cell) } }
        editingId = cell.id
        return cell.id
    }

    fun deleteCell(id: String) {
        if (editingId == id) editingId = null
        edit { cells -> cells.filter { it.id != id } }
    }

    fun moveCell(id: String, delta: Int) = edit { cells ->
        val i = cells.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in cells.indices) cells
        else cells.toMutableList().also { val c = it.removeAt(i); it.add(j, c) }
    }

    fun setType(id: String, type: CellType) = updateCell(id) {
        if (it.type == type) it
        else it.copy(type = type, outputs = emptyList(), executionCount = null)
    }

    fun clearOutputs(id: String) = updateCell(id) { it.copy(outputs = emptyList(), executionCount = null) }

    fun clearAllOutputs() = edit { cells ->
        cells.map { if (it.type == CellType.CODE) it.copy(outputs = emptyList(), executionCount = null) else it }
    }

    // ---------------------------------------------------------------- running

    fun run(id: String) = enqueue(listOf(id))

    fun runAll() = enqueue(doc?.notebook?.cells?.map { it.id } ?: emptyList())

    fun runFrom(id: String) {
        val cells = doc?.notebook?.cells ?: return
        val i = cells.indexOfFirst { it.id == id }
        if (i >= 0) enqueue(cells.drop(i).map { it.id })
    }

    private fun enqueue(ids: List<String>) {
        if (kernelState is KernelState.Failed) {
            message("Python failed to start: ${(kernelState as KernelState.Failed).message}")
            return
        }
        for (id in ids) {
            val c = cell(id) ?: continue
            if (c.type != CellType.CODE || id in runStates) continue
            queue.addLast(id)
            runStates[id] = RunState.QUEUED
        }
        if (runner?.isActive != true) runner = viewModelScope.launch { drain() }
    }

    private suspend fun drain() {
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val c = cell(id)
            if (c == null) {
                runStates.remove(id)
                continue
            }
            runStates[id] = RunState.RUNNING
            updateCell(id) { it.copy(outputs = emptyList(), executionCount = null) }
            val result = try {
                kernel.execute(c.source, listenerFor(id))
            } catch (e: Throwable) {
                appendOutput(id, errorOutput(e))
                ExecResult(ok = false, executionCount = 0)
            }
            if (result.executionCount > 0) updateCell(id) { it.copy(executionCount = result.executionCount) }
            runStates.remove(id)
            if (!result.ok) cancelQueued() // like Jupyter's "run all": stop at the first error
        }
    }

    private fun listenerFor(id: String) = object : ExecutionListener {
        override fun onOutput(output: JsonObject) {
            main.post { appendOutput(id, output) }
        }

        override fun onInputRequest(prompt: String, password: Boolean, answer: CompletableFuture<String?>) {
            main.post { inputRequest = InputRequest(prompt, password, answer) }
        }
    }

    private val clearOnNextOutput = mutableSetOf<String>()

    private fun appendOutput(id: String, output: JsonObject) {
        val type = output["output_type"]?.jsonPrimitive?.content
        if (type == "clear_output") {
            val wait = output["wait"]?.jsonPrimitive?.booleanOrNull == true
            if (wait) clearOnNextOutput += id else updateCell(id) { it.copy(outputs = emptyList()) }
            return
        }
        updateCell(id) {
            val base = if (clearOnNextOutput.remove(id)) emptyList() else it.outputs
            it.copy(outputs = Outputs.append(base, output))
        }
    }

    fun answerInput(text: String?) {
        inputRequest?.answer?.complete(text)
        inputRequest = null
    }

    private fun cancelQueued() {
        queue.forEach { runStates.remove(it) }
        queue.clear()
    }

    fun interrupt() {
        cancelQueued()
        answerInput(null)
        viewModelScope.launch { runCatching { kernel.interrupt() } }
    }

    private fun stopAll() {
        if (busy) interrupt()
    }

    fun restart(runAllAfter: Boolean = false) {
        interrupt()
        viewModelScope.launch {
            runner?.join()
            try {
                kernel.newSession(workdir)
                message("Restarted Python. Variables were cleared.")
                if (runAllAfter) runAll()
            } catch (e: Throwable) {
                kernelState = KernelState.Failed(e.message ?: e.toString())
            }
        }
    }

    private fun errorOutput(e: Throwable) = JsonObject(
        mapOf(
            "output_type" to JsonPrimitive("error"),
            "ename" to JsonPrimitive(e.javaClass.simpleName),
            "evalue" to JsonPrimitive(e.message ?: ""),
            "traceback" to JsonArray(listOf(JsonPrimitive("${e.javaClass.simpleName}: ${e.message}"))),
        )
    )

    override fun onCleared() {
        interrupt()
    }
}
