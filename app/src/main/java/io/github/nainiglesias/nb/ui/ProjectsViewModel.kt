package io.github.nainiglesias.nb.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.nainiglesias.nb.NbApp
import io.github.nainiglesias.nb.data.Project
import io.github.nainiglesias.nb.notebook.NotebookIO
import io.github.nainiglesias.nb.sync.GitHubClient
import io.github.nainiglesias.nb.sync.Puller
import io.github.nainiglesias.nb.sync.RepoRef
import io.github.nainiglesias.nb.sync.asSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class FileKind { FOLDER, NOTEBOOK, MARKDOWN, OTHER }

data class Entry(val name: String, val file: File, val kind: FileKind, val size: Long)

/** A Markdown file open for reading or editing. */
data class MarkdownDoc(val file: File, val saved: String, val text: String, val editing: Boolean = false) {
    val dirty get() = text != saved
}

/** One visible line of the side panel's file tree. [path] is relative to the project root. */
data class TreeRow(val path: String, val name: String, val depth: Int, val kind: FileKind, val file: File)

/** Progress of a clone or pull. */
data class SyncProgress(val label: String, val done: Int = 0, val total: Int = 0)

class ProjectsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = (app as NbApp).projects
    private val resolver = app.contentResolver

    var projects by mutableStateOf(store.load())
        private set
    var current by mutableStateOf<Project?>(null)
        private set

    /** Folder shown inside the current project, relative to its root ("" is the root). */
    var folder by mutableStateOf("")
        private set
    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set
    var sync by mutableStateOf<SyncProgress?>(null)
        private set
    var markdown by mutableStateOf<MarkdownDoc?>(null)
        private set

    /** Folders open in the side panel's tree, relative to the project root. */
    var expanded by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Something to do once the open Markdown file is saved or discarded (asked in a dialog). */
    var pendingLeave by mutableStateOf<(() -> Unit)?>(null)
        private set

    val githubToken: String? get() = store.githubToken

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private fun message(text: String) {
        _messages.tryEmit(text)
    }

    fun dir(project: Project): File = store.dir(project)

    private val folderDir: File? get() = current?.let { File(dir(it), folder) }

    // ---------------------------------------------------------------- projects

    fun createProject(name: String) {
        val clean = name.trim().ifEmpty { "Project" }
        val project = store.create(clean)
        projects = store.load()
        openProject(project)
    }

    /** Create a project from a GitHub repo and download it. Returns an error message for bad input. */
    fun cloneProject(input: String, token: String?): String? {
        val ref = RepoRef.parse(input) ?: return "Use owner/repo or a github.com link."
        if (sync != null) return "Another download is running."
        store.githubToken = token
        val project = store.create(ref.repo, ref)
        projects = store.load()
        viewModelScope.launch {
            val ok = pull(project, overwriteLocal = false, label = "Downloading ${ref.slug}")
            if (ok) {
                openProject(store.load().first { it.id == project.id })
            } else {
                projects = store.delete(project)
            }
        }
        return null
    }

    fun deleteProject(project: Project) {
        if (current?.id == project.id) closeProject()
        projects = store.delete(project)
        message("Deleted ${project.name}")
    }

    fun renameProject(project: Project, name: String) {
        if (name.isBlank()) return
        val renamed = project.copy(name = name.trim())
        projects = store.update(renamed)
        if (current?.id == project.id) current = renamed
    }

    fun openProject(project: Project) {
        current = project
        folder = ""
        expanded = emptySet()
        refresh()
    }

    fun closeProject() {
        current = null
        folder = ""
        entries = emptyList()
    }

    // ---------------------------------------------------------------- pulling

    fun pull(overwriteLocal: Boolean = false) {
        val project = current ?: return
        if (sync != null) return
        viewModelScope.launch { pull(project, overwriteLocal, label = "Pulling from GitHub") }
    }

    private suspend fun pull(project: Project, overwriteLocal: Boolean, label: String): Boolean {
        val ref = project.github ?: return false
        sync = SyncProgress(label)
        return try {
            val result = withContext(Dispatchers.IO) {
                Puller(GitHubClient(store.githubToken).asSource()).pull(
                    ref, dir(project), store.syncStateFile(project), overwriteLocal,
                ) { done, total -> sync = SyncProgress(label, done, total) }
            }
            val updated = project.copy(lastPull = System.currentTimeMillis())
            projects = store.update(updated)
            if (current?.id == project.id) current = updated
            message(result.summary())
            true
        } catch (e: Exception) {
            message(e.message ?: "Couldn't reach GitHub (${e.javaClass.simpleName}).")
            false
        } finally {
            sync = null
            refresh()
        }
    }

    // ---------------------------------------------------------------- files

    fun refresh() {
        entries = folderDir?.let(::list) ?: emptyList()
    }

    private fun list(dir: File): List<Entry> =
        (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            .map { f ->
                val kind = when {
                    f.isDirectory -> FileKind.FOLDER
                    f.name.endsWith(".ipynb", ignoreCase = true) -> FileKind.NOTEBOOK
                    f.name.endsWith(".md", ignoreCase = true) || f.name.endsWith(".markdown", ignoreCase = true) ->
                        FileKind.MARKDOWN
                    else -> FileKind.OTHER
                }
                Entry(f.name, f, kind, if (f.isFile) f.length() else 0)
            }
            .sortedWith(compareBy<Entry> { it.kind != FileKind.FOLDER }.thenBy { it.name.lowercase() })

    fun enterFolder(name: String) {
        folder = if (folder.isEmpty()) name else "$folder/$name"
        refresh()
    }

    /** Go to the parent folder. Returns false at the project root. */
    fun up(): Boolean {
        if (folder.isEmpty()) return false
        folder = folder.substringBeforeLast('/', "")
        refresh()
        return true
    }

    fun goTo(path: String) {
        folder = path
        refresh()
    }

    /** Create an empty notebook, Markdown file or folder here. Returns the new file, or null. */
    fun create(name: String, kind: FileKind): File? {
        val dir = folderDir ?: return null
        val base = name.trim().replace('/', '_').ifEmpty { return null }
        val fileName = when (kind) {
            FileKind.NOTEBOOK -> if (base.endsWith(".ipynb")) base else "$base.ipynb"
            FileKind.MARKDOWN -> if (base.endsWith(".md")) base else "$base.md"
            else -> base
        }
        val file = File(dir, fileName)
        if (file.exists()) {
            message("$fileName already exists.")
            return null
        }
        val ok = runCatching {
            when (kind) {
                FileKind.FOLDER -> file.mkdirs()
                FileKind.NOTEBOOK -> file.writeText(NotebookIO.serialize(NotebookIO.empty()))
                FileKind.MARKDOWN -> file.writeText("# ${base.removeSuffix(".md")}\n")
                FileKind.OTHER -> file.createNewFile()
            }
        }.isSuccess
        refresh()
        if (!ok) message("Couldn't create $fileName.")
        return file.takeIf { ok }
    }

    fun deleteEntry(entry: Entry) {
        val ok = if (entry.file.isDirectory) entry.file.deleteRecursively() else entry.file.delete()
        message(if (ok) "Deleted ${entry.name}" else "Couldn't delete ${entry.name}")
        refresh()
    }

    /** Copy files picked from the phone into the folder shown. */
    fun importFiles(uris: List<Uri>) {
        val dir = folderDir ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val names = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        val name = displayName(uri).replace('/', '_')
                        resolver.openInputStream(uri)!!.use { input ->
                            File(dir, name).outputStream().use { input.copyTo(it) }
                        }
                        name
                    }.getOrNull()
                }
            }
            refresh()
            message(if (names.isEmpty()) "Nothing was copied." else "Added ${names.joinToString()}")
        }
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    // ---------------------------------------------------------------- side panel

    fun toggleExpanded(path: String) {
        expanded = if (path in expanded) expanded - path else expanded + path
    }

    /** Open the folders above [file] so it shows in the tree. */
    fun reveal(file: File) {
        val root = current?.let(::dir) ?: return
        val rel = file.relativeToOrNull(root)?.invariantSeparatorsPath ?: return
        val parts = rel.split('/').dropLast(1)
        if (parts.isEmpty() || rel.startsWith("..")) return
        expanded = expanded + parts.indices.map { parts.take(it + 1).joinToString("/") }
    }

    /** The project's files as a flat list, descending into the expanded folders. */
    fun treeRows(): List<TreeRow> {
        val root = current?.let(::dir) ?: return emptyList()
        val rows = mutableListOf<TreeRow>()
        fun walk(dir: File, prefix: String, depth: Int) {
            for (e in list(dir)) {
                val path = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
                rows += TreeRow(path, e.name, depth, e.kind, e.file)
                if (e.kind == FileKind.FOLDER && path in expanded) walk(e.file, path, depth + 1)
            }
        }
        walk(root, "", 0)
        return rows
    }

    // ---------------------------------------------------------------- markdown

    /** Close the Markdown file, asking first if it has unsaved changes, then run [action]. */
    fun leaveMarkdownThen(action: () -> Unit = {}) {
        if (markdown?.dirty == true) {
            pendingLeave = action
        } else {
            closeMarkdown()
            action()
        }
    }

    /** Answer to the unsaved-changes dialog: save, discard, or null to stay. */
    fun resolveLeave(save: Boolean?) {
        val action = pendingLeave ?: return
        pendingLeave = null
        if (save == null) return
        if (save) saveMarkdown()
        closeMarkdown()
        action()
    }

    fun openMarkdown(file: File, editing: Boolean = false) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { file.readText() } }
            text.onSuccess { markdown = MarkdownDoc(file, it, it, editing) }
                .onFailure { message("Couldn't open ${file.name}.") }
        }
    }

    fun editMarkdown(text: String) {
        markdown = markdown?.copy(text = text)
    }

    fun setMarkdownEditing(editing: Boolean) {
        markdown = markdown?.copy(editing = editing)
    }

    fun saveMarkdown() {
        val doc = markdown ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { doc.file.writeText(doc.text) }.isSuccess }
            if (ok) {
                markdown = markdown?.copy(saved = doc.text)
                message("Saved ${doc.file.name}")
            } else {
                message("Couldn't save ${doc.file.name}.")
            }
        }
    }

    fun closeMarkdown() {
        markdown = null
        refresh()
    }
}
