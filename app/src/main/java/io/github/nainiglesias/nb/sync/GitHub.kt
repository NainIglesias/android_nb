package io.github.nainiglesias.nb.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@Serializable
data class RepoRef(val owner: String, val repo: String, val branch: String? = null) {
    val slug: String get() = "$owner/$repo"

    companion object {
        private val OWNER = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")
        private val REPO = Regex("[A-Za-z0-9._-]+")

        /**
         * Accepts "owner/repo", "github.com/owner/repo", "https://github.com/owner/repo(.git)",
         * "git@github.com:owner/repo.git" and ".../tree/<branch>". Returns null for anything else.
         */
        fun parse(input: String): RepoRef? {
            var s = input.trim().removeSuffix("/")
            s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            s = when {
                s.startsWith("git@github.com:") -> s.removePrefix("git@github.com:")
                s.startsWith("github.com/") -> s.removePrefix("github.com/")
                else -> s
            }
            val parts = s.split('/')
            if (parts.size < 2) return null
            val owner = parts[0]
            val repo = parts[1].removeSuffix(".git")
            if (!OWNER.matches(owner) || !REPO.matches(repo) || repo == "." || repo == "..") return null
            val branch = if (parts.size >= 4 && parts[2] == "tree" && parts[3].isNotEmpty()) parts[3] else null
            if (parts.size > 2 && branch == null) return null
            return RepoRef(owner, repo, branch)
        }
    }
}

class GitHubException(message: String) : IOException(message)

data class RemoteFile(val path: String, val sha: String, val size: Long)

data class Snapshot(val branch: String, val commit: String, val files: List<RemoteFile>)

/** The few GitHub calls a pull needs. Blocking: call from Dispatchers.IO. */
class GitHubClient(private val token: String?) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class RepoInfo(@SerialName("default_branch") val defaultBranch: String)

    @Serializable
    private class CommitInfo(val sha: String, val commit: CommitBody)

    @Serializable
    private class CommitBody(val tree: ShaRef)

    @Serializable
    private class ShaRef(val sha: String)

    @Serializable
    private class Tree(val tree: List<TreeEntry>, val truncated: Boolean = false)

    @Serializable
    private class TreeEntry(val path: String, val mode: String, val type: String, val sha: String, val size: Long = 0)

    /** The latest commit of the branch (or the default branch) and every file in it. */
    fun snapshot(ref: RepoRef): Snapshot {
        val base = "https://api.github.com/repos/${ref.owner}/${ref.repo}"
        val branch = ref.branch ?: json.decodeFromString<RepoInfo>(api(base, ref)).defaultBranch
        val commit = json.decodeFromString<CommitInfo>(api("$base/commits/${enc(branch)}", ref, branch))
        val tree = json.decodeFromString<Tree>(api("$base/git/trees/${commit.commit.tree.sha}?recursive=1", ref))
        if (tree.truncated) throw GitHubException("${ref.slug} has too many files to download.")
        val files = tree.tree
            // Regular files only: no folders, submodules ("commit") or symlinks (120000).
            .filter { it.type == "blob" && it.mode != "120000" }
            .map { RemoteFile(it.path, it.sha, it.size) }
        return Snapshot(branch, commit.sha, files)
    }

    /** One file's content at [commit]. raw.githubusercontent.com doesn't count against the API rate limit. */
    fun download(ref: RepoRef, commit: String, path: String): ByteArray {
        val encoded = path.split('/').joinToString("/") { enc(it) }
        val conn = open("https://raw.githubusercontent.com/${ref.owner}/${ref.repo}/$commit/$encoded")
        return try {
            if (conn.responseCode != 200) throw GitHubException("Couldn't download $path (HTTP ${conn.responseCode}).")
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun api(url: String, ref: RepoRef, branch: String? = null): String {
        val conn = open(url)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        try {
            val code = conn.responseCode
            if (code == 200) return conn.inputStream.use { it.readBytes().decodeToString() }
            val limited = conn.getHeaderField("X-RateLimit-Remaining") == "0"
            throw GitHubException(
                when {
                    code == 401 -> "GitHub rejected the token. Check it or remove it."
                    (code == 403 || code == 429) && limited ->
                        "GitHub's limit of 60 requests an hour without a token was reached. " +
                            "Try later, or add a token."
                    code == 404 && branch != null -> "Branch \"$branch\" wasn't found in ${ref.slug}."
                    code == 404 || code == 403 ->
                        if (token.isNullOrBlank()) "${ref.slug} wasn't found. If it's private, add a token."
                        else "${ref.slug} wasn't found, or the token can't read it."
                    else -> "GitHub answered HTTP $code."
                }
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "android-nb")
        if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer ${token.trim()}")
        return conn
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
