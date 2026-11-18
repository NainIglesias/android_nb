package io.github.nainiglesias.nb.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RecentFile(val uri: String, val name: String, val openedAt: Long)

/** The list of recently opened notebooks, newest first. */
class RecentFiles(context: Context) {
    private val prefs = context.getSharedPreferences("recent", Context.MODE_PRIVATE)

    fun load(): List<RecentFile> = try {
        Json.decodeFromString<List<RecentFile>>(prefs.getString(KEY, "[]")!!)
    } catch (_: Exception) {
        emptyList()
    }

    fun add(uri: String, name: String): List<RecentFile> =
        save(listOf(RecentFile(uri, name, System.currentTimeMillis())) + load().filter { it.uri != uri })

    fun remove(uri: String): List<RecentFile> = save(load().filter { it.uri != uri })

    private fun save(list: List<RecentFile>): List<RecentFile> {
        val trimmed = list.take(MAX)
        prefs.edit().putString(KEY, Json.encodeToString(trimmed)).apply()
        return trimmed
    }

    private companion object {
        const val KEY = "files"
        const val MAX = 25
    }
}
