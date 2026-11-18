package io.github.nainiglesias.nb.sync

import java.security.MessageDigest

/** Git's object id for a file: SHA-1 of "blob <size>\0" + content. GitHub's tree API returns the same id. */
fun gitBlobSha(bytes: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-1")
    md.update("blob ${bytes.size}\u0000".encodeToByteArray())
    md.update(bytes)
    return md.digest().joinToString("") { "%02x".format(it) }
}

/**
 * What a pull does to the project folder. All maps go from relative path to blob sha.
 *
 * - [download]: write the GitHub version over the local file (or create it).
 * - [delete]: the file was removed on GitHub and wasn't changed here.
 * - [conflicts]: changed both here and on GitHub. The local file stays, and the GitHub version
 *   is saved next to it (see [conflictPath]).
 * - [keptLocal]: removed on GitHub but changed here, so it stays.
 * - [base]: the GitHub versions the folder now matches, for the next pull.
 */
data class SyncPlan(
    val download: List<String>,
    val delete: List<String>,
    val conflicts: List<String>,
    val keptLocal: List<String>,
    val base: Map<String, String>,
)

object SyncPlanner {
    /**
     * [remote] is the GitHub tree, [base] what the last pull wrote, [local] the files on disk now.
     * With [overwriteLocal], local changes are thrown away and the folder ends up like GitHub.
     */
    fun plan(
        remote: Map<String, String>,
        base: Map<String, String>,
        local: Map<String, String>,
        overwriteLocal: Boolean = false,
    ): SyncPlan {
        val download = mutableListOf<String>()
        val delete = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        val keptLocal = mutableListOf<String>()
        val newBase = mutableMapOf<String, String>()

        for ((path, r) in remote) {
            val b = base[path]
            val l = local[path]
            when {
                l == r -> newBase[path] = r
                // Missing here: pulling brings the GitHub version back.
                l == null || l == b || overwriteLocal -> {
                    download += path
                    newBase[path] = r
                }
                // Changed here, nothing new on GitHub: keep the local edit.
                r == b -> newBase[path] = r
                else -> {
                    conflicts += path
                    newBase[path] = r
                }
            }
        }
        for ((path, b) in base) {
            if (path in remote) continue
            val l = local[path] ?: continue
            if (l == b || overwriteLocal) delete += path else keptLocal += path
        }
        return SyncPlan(download.sorted(), delete.sorted(), conflicts.sorted(), keptLocal.sorted(), newBase)
    }

    /** Where the GitHub version of a conflicting file goes: "dir/name (GitHub).ext". */
    fun conflictPath(path: String): String {
        val dir = path.substringBeforeLast('/', "")
        val file = path.substringAfterLast('/')
        val dot = file.lastIndexOf('.')
        val named = if (dot > 0) "${file.substring(0, dot)} (GitHub)${file.substring(dot)}" else "$file (GitHub)"
        return if (dir.isEmpty()) named else "$dir/$named"
    }
}
