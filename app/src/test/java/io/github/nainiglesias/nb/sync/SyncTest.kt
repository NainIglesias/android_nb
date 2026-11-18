package io.github.nainiglesias.nb.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SyncTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun blobShaMatchesGit() {
        // `git hash-object` of an empty file and of "hello\n".
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", gitBlobSha(ByteArray(0)))
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", gitBlobSha("hello\n".encodeToByteArray()))
    }

    @Test
    fun parsesRepoReferences() {
        val plain = RepoRef("octo", "my.repo")
        assertEquals(plain, RepoRef.parse("octo/my.repo"))
        assertEquals(plain, RepoRef.parse("  https://github.com/octo/my.repo.git/ "))
        assertEquals(plain, RepoRef.parse("github.com/octo/my.repo"))
        assertEquals(plain, RepoRef.parse("git@github.com:octo/my.repo.git"))
        assertEquals(RepoRef("octo", "nb", "dev"), RepoRef.parse("https://github.com/octo/nb/tree/dev"))
        assertNull(RepoRef.parse("octo"))
        assertNull(RepoRef.parse("https://gitlab.com/octo/nb"))
        assertNull(RepoRef.parse("octo/nb/blob/main/x.ipynb"))
        assertNull(RepoRef.parse("octo/.."))
    }

    @Test
    fun planKeepsLocalEditsAndReportsConflicts() {
        val plan = SyncPlanner.plan(
            remote = mapOf("same" to "1", "updated" to "2b", "edited" to "3", "both" to "4b", "new" to "5", "gone" to "6"),
            base = mapOf("same" to "1", "updated" to "2", "edited" to "3", "both" to "4", "removed" to "7",
                "removedEdited" to "8", "gone" to "6"),
            local = mapOf("same" to "1", "updated" to "2", "edited" to "3x", "both" to "4x", "removed" to "7",
                "removedEdited" to "8x", "mine" to "9"),
        )
        assertEquals(listOf("gone", "new", "updated"), plan.download)
        assertEquals(listOf("removed"), plan.delete)
        assertEquals(listOf("both"), plan.conflicts)
        assertEquals(listOf("removedEdited"), plan.keptLocal)
        assertEquals(
            mapOf("same" to "1", "updated" to "2b", "edited" to "3", "both" to "4b", "new" to "5", "gone" to "6"),
            plan.base,
        )
    }

    @Test
    fun overwriteLocalMakesTheFolderMatchGitHub() {
        val plan = SyncPlanner.plan(
            remote = mapOf("edited" to "1", "both" to "2b"),
            base = mapOf("edited" to "1", "both" to "2", "removedEdited" to "3"),
            local = mapOf("edited" to "1x", "both" to "2x", "removedEdited" to "3x", "mine" to "4"),
            overwriteLocal = true,
        )
        assertEquals(listOf("both", "edited"), plan.download)
        assertEquals(listOf("removedEdited"), plan.delete)
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun conflictPathKeepsTheExtension() {
        assertEquals("a/b/lab (GitHub).ipynb", SyncPlanner.conflictPath("a/b/lab.ipynb"))
        assertEquals("README (GitHub)", SyncPlanner.conflictPath("README"))
        assertEquals(".env (GitHub)", SyncPlanner.conflictPath(".env"))
    }

    /** A repo whose files can be changed between pulls. */
    private class FakeRepo : RepoSource {
        val files = mutableMapOf<String, String>()
        var commit = 0
        val downloads = mutableListOf<String>()

        override fun snapshot(ref: RepoRef) = Snapshot(
            "main", "c${++commit}",
            files.map { (p, text) -> RemoteFile(p, gitBlobSha(text.encodeToByteArray()), text.length.toLong()) },
        )

        override fun download(ref: RepoRef, commit: String, path: String): ByteArray {
            downloads += path
            return files.getValue(path).encodeToByteArray()
        }
    }

    @Test
    fun pullsIncrementallyWithoutLosingEdits() {
        val repo = FakeRepo()
        val dir = tmp.newFolder("project")
        val state = File(tmp.root, "project.sync.json")
        val puller = Puller(repo)
        val ref = RepoRef("o", "r")
        fun read(p: String) = File(dir, p).readText()

        repo.files += mapOf("README.md" to "# hi\n", "nb/lab.ipynb" to "{}", "nb/old.md" to "old", "../evil" to "x")
        val first = puller.pull(ref, dir, state)
        assertEquals(listOf("README.md", "nb/lab.ipynb", "nb/old.md"), first.updated)
        assertFalse(File(tmp.root, "evil").exists())
        assertEquals("{}", read("nb/lab.ipynb"))

        // Nothing changed: nothing is downloaded.
        repo.downloads.clear()
        assertEquals("Already up to date.", puller.pull(ref, dir, state).summary())
        assertTrue(repo.downloads.isEmpty())

        // Edit lab.ipynb here and on GitHub, change README on GitHub, delete old.md on GitHub.
        File(dir, "nb/lab.ipynb").writeText("{\"mine\": 1}")
        File(dir, "notes.md").writeText("my notes")
        repo.files["nb/lab.ipynb"] = "{\"theirs\": 1}"
        repo.files["README.md"] = "# hello\n"
        repo.files.remove("nb/old.md")
        val second = puller.pull(ref, dir, state)
        assertEquals(listOf("README.md"), second.updated)
        assertEquals(listOf("nb/old.md"), second.deleted)
        assertEquals(listOf("nb/lab.ipynb"), second.conflicts)
        assertEquals("# hello\n", read("README.md"))
        assertEquals("{\"mine\": 1}", read("nb/lab.ipynb"))
        assertEquals("{\"theirs\": 1}", read("nb/lab (GitHub).ipynb"))
        assertEquals("my notes", read("notes.md"))
        assertFalse(File(dir, "nb/old.md").exists())

        // The conflict was reported once; the local edit stays on later pulls.
        assertEquals("Already up to date.", puller.pull(ref, dir, state).summary())
        assertEquals("{\"mine\": 1}", read("nb/lab.ipynb"))

        // Reset to GitHub.
        puller.pull(ref, dir, state, overwriteLocal = true)
        assertEquals("{\"theirs\": 1}", read("nb/lab.ipynb"))
        assertEquals("my notes", read("notes.md"))
    }
}
