package io.github.nainiglesias.nb.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Where a pull gets files from. [GitHubClient] in the app, a fake in tests. */
interface RepoSource {
    fun snapshot(ref: RepoRef): Snapshot
    fun download(ref: RepoRef, commit: String, path: String): ByteArray
}

fun GitHubClient.asSource() = object : RepoSource {
    override fun snapshot(ref: RepoRef) = this@asSource.snapshot(ref)
    override fun download(ref: RepoRef, commit: String, path: String) = this@asSource.download(ref, commit, path)
}

/** What the last pull wrote: the commit and each file's blob sha. */
@Serializable
data class SyncState(val commit: String? = null, val files: Map<String, String> = emptyMap())

data class PullResult(
    val commit: String,
    val updated: List<String>,
    val deleted: List<String>,
    val conflicts: List<String>,
    val keptLocal: List<String>,
    val skipped: List<String>,
) {
    fun summary(): String {
        val parts = buildList {
            if (updated.isNotEmpty()) add("${updated.size} updated")
            if (deleted.isNotEmpty()) add("${deleted.size} deleted")
            if (conflicts.isNotEmpty()) {
                add("${conflicts.size} changed in both places (GitHub copy saved as \"… (GitHub)\")")
            }
            if (keptLocal.isNotEmpty()) add("${keptLocal.size} deleted on GitHub but kept (changed here)")
            if (skipped.isNotEmpty()) add("${skipped.size} too big, skipped")
        }
        return if (parts.isEmpty()) "Already up to date." else parts.joinToString(", ") + "."
    }
}

/** Brings a project folder up to date with GitHub without losing local edits. Blocking. */
class Puller(private val source: RepoSource, private val maxFileBytes: Long = 50L * 1024 * 1024) {
    private val json = Json { ignoreUnknownKeys = true }

    fun pull(
        ref: RepoRef,
        dir: File,
        stateFile: File,
        overwriteLocal: Boolean = false,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): PullResult {
        val snapshot = source.snapshot(ref)
        val (fits, tooBig) = snapshot.files.partition { it.size <= maxFileBytes }
        val remote = fits.associate { it.path to it.sha }.filterKeys { safe(it) }
        val state = readState(stateFile)

        val local = (remote.keys + state.files.keys).mapNotNull { path ->
            File(dir, path).takeIf { it.isFile }?.let { path to gitBlobSha(it.readBytes()) }
        }.toMap()
        val plan = SyncPlanner.plan(remote, state.files, local, overwriteLocal)

        val fetch = plan.download + plan.conflicts
        fetch.forEachIndexed { i, path ->
            progress(i, fetch.size)
            val bytes = source.download(ref, snapshot.commit, path)
            val target = File(dir, if (path in plan.conflicts) SyncPlanner.conflictPath(path) else path)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, ".${target.name}.part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) error("Couldn't write $path")
            }
        }
        progress(fetch.size, fetch.size)
        for (path in plan.delete) {
            File(dir, path).delete()
            pruneEmptyParents(File(dir, path).parentFile, dir)
        }

        writeState(stateFile, SyncState(snapshot.commit, plan.base))
        return PullResult(
            snapshot.commit, plan.download, plan.delete, plan.conflicts, plan.keptLocal,
            tooBig.map { it.path },
        )
    }

    private fun readState(file: File): SyncState =
        if (!file.isFile) SyncState() else runCatching { json.decodeFromString<SyncState>(file.readText()) }
            .getOrDefault(SyncState())

    private fun writeState(file: File, state: SyncState) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(state))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun pruneEmptyParents(start: File?, root: File) {
        var d = start
        while (d != null && d != root && d.list()?.isEmpty() == true) {
            d.delete()
            d = d.parentFile
        }
    }

    /** Paths from the network must stay inside the project folder. */
    private fun safe(path: String) =
        path.isNotEmpty() && !path.startsWith("/") && path.split('/').none { it == ".." || it == "." || it.isEmpty() }
}
