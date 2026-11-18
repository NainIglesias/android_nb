package io.github.nainiglesias.nb.data

import android.content.Context
import io.github.nainiglesias.nb.sync.RepoRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    /** Set for projects cloned from GitHub. */
    val github: RepoRef? = null,
    val lastPull: Long? = null,
)

/**
 * Projects are folders in the app's private storage (files/projects/<id>/). The list and the
 * GitHub token live in shared preferences; each GitHub project's sync state is in
 * files/projects/<id>.sync.json, outside the folder so it never shows up as a project file.
 */
class ProjectStore(context: Context) {
    private val prefs = context.getSharedPreferences("projects", Context.MODE_PRIVATE)
    val root: File = File(context.filesDir, "projects").apply { mkdirs() }

    fun load(): List<Project> = try {
        Json.decodeFromString<List<Project>>(prefs.getString(KEY, "[]")!!)
    } catch (_: Exception) {
        emptyList()
    }

    fun dir(project: Project): File = File(root, project.id)

    fun syncStateFile(project: Project): File = File(root, "${project.id}.sync.json")

    fun create(name: String, github: RepoRef? = null): Project {
        val project = Project(newId(), name.trim(), System.currentTimeMillis(), github)
        dir(project).mkdirs()
        save(load() + project)
        return project
    }

    fun update(project: Project): List<Project> = save(load().map { if (it.id == project.id) project else it })

    fun delete(project: Project): List<Project> {
        dir(project).deleteRecursively()
        syncStateFile(project).delete()
        return save(load().filter { it.id != project.id })
    }

    var githubToken: String?
        get() = prefs.getString(TOKEN, null)
        set(value) {
            prefs.edit().apply { if (value.isNullOrBlank()) remove(TOKEN) else putString(TOKEN, value.trim()) }.apply()
        }

    private fun save(list: List<Project>): List<Project> {
        prefs.edit().putString(KEY, Json.encodeToString(list)).apply()
        return list
    }

    private fun newId(): String {
        val ids = load().map { it.id }.toSet()
        var id: String
        do {
            id = java.lang.Long.toString(System.nanoTime() xor (Math.random() * Long.MAX_VALUE).toLong(), 36)
                .removePrefix("-")
        } while (id in ids || File(root, id).exists())
        return id
    }

    private companion object {
        const val KEY = "list"
        const val TOKEN = "github_token"
    }
}
