package io.github.nainiglesias.nb.notebook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

enum class CellType(val json: String) {
    CODE("code"), MARKDOWN("markdown"), RAW("raw");

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.json == s } ?: RAW
    }
}

/**
 * One notebook cell. [outputs] are kept as raw nbformat output objects, with
 * multi-line strings already joined, so nothing is lost on save.
 * [extra] holds keys this app doesn't use (attachments, ...), written back as-is.
 */
data class Cell(
    val id: String,
    val type: CellType,
    val source: String,
    val outputs: List<JsonObject> = emptyList(),
    val executionCount: Int? = null,
    val metadata: JsonObject = EMPTY,
    val extra: Map<String, JsonElement> = emptyMap(),
)

data class Notebook(
    val cells: List<Cell>,
    val metadata: JsonObject,
    val nbformat: Int = 4,
    val nbformatMinor: Int = 5,
    val extra: Map<String, JsonElement> = emptyMap(),
)

val EMPTY = JsonObject(emptyMap())

class NotebookFormatException(message: String) : Exception(message)

fun newCellId(): String = buildString {
    repeat(8) { append("0123456789abcdef"[Random.nextInt(16)]) }
}

object NotebookIO {
    private val json = Json { isLenient = false }

    fun empty(): Notebook = Notebook(
        cells = listOf(Cell(newCellId(), CellType.CODE, "")),
        metadata = JsonObject(
            mapOf(
                "kernelspec" to JsonObject(
                    mapOf(
                        "display_name" to JsonPrimitive("Python 3"),
                        "language" to JsonPrimitive("python"),
                        "name" to JsonPrimitive("python3"),
                    )
                ),
                "language_info" to JsonObject(mapOf("name" to JsonPrimitive("python"))),
            )
        ),
    )

    fun parse(text: String): Notebook {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            throw NotebookFormatException("This file isn't valid JSON: ${e.message?.lineSequence()?.firstOrNull()}")
        } ?: throw NotebookFormatException("This file isn't a notebook (expected a JSON object).")

        val major = root["nbformat"]?.jsonPrimitive?.intOrNull
            ?: throw NotebookFormatException("This file isn't a notebook (no nbformat version).")
        if (major != 4) {
            throw NotebookFormatException("Only nbformat 4 notebooks are supported (this one is version $major).")
        }
        val minor = root["nbformat_minor"]?.jsonPrimitive?.intOrNull ?: 0
        val seen = HashSet<String>()
        val cells = (root["cells"] as? JsonArray ?: JsonArray(emptyList())).map { el ->
            val c = el.jsonObject
            var id = c["id"]?.jsonPrimitive?.contentOrNull ?: newCellId()
            while (!seen.add(id)) id = newCellId()
            val type = CellType.of(c["cell_type"]?.jsonPrimitive?.contentOrNull)
            Cell(
                id = id,
                type = type,
                source = joinMultiline(c["source"]),
                outputs = (c["outputs"] as? JsonArray)?.map { normalizeOutput(it.jsonObject) } ?: emptyList(),
                executionCount = (c["execution_count"] as? JsonPrimitive)?.intOrNull,
                metadata = c["metadata"] as? JsonObject ?: EMPTY,
                extra = c.filterKeys { it !in CELL_KEYS },
            )
        }
        return Notebook(
            cells = cells,
            metadata = root["metadata"] as? JsonObject ?: EMPTY,
            nbformat = 4,
            nbformatMinor = minor,
            extra = root.filterKeys { it !in NOTEBOOK_KEYS },
        )
    }

    /** Serialize like Jupyter does: 1-space indent, sorted keys, multi-line strings as line lists. */
    fun serialize(nb: Notebook): String {
        val cells = nb.cells.map { cell ->
            val m = LinkedHashMap<String, JsonElement>(cell.extra)
            m["cell_type"] = JsonPrimitive(cell.type.json)
            m["id"] = JsonPrimitive(cell.id)
            m["metadata"] = cell.metadata
            m["source"] = splitLines(cell.source)
            if (cell.type == CellType.CODE) {
                m["execution_count"] = cell.executionCount?.let { JsonPrimitive(it) } ?: JsonNull
                m["outputs"] = JsonArray(cell.outputs.map { denormalizeOutput(it) })
            }
            JsonObject(m)
        }
        val root = LinkedHashMap<String, JsonElement>(nb.extra)
        root["cells"] = JsonArray(cells)
        root["metadata"] = nb.metadata
        root["nbformat"] = JsonPrimitive(4)
        // Cell ids need nbformat 4.5.
        root["nbformat_minor"] = JsonPrimitive(maxOf(nb.nbformatMinor, 5))
        return buildString { writeJson(JsonObject(root), 0) }.plus("\n")
    }

    private val CELL_KEYS = setOf("id", "cell_type", "source", "outputs", "execution_count", "metadata")
    private val NOTEBOOK_KEYS = setOf("cells", "metadata", "nbformat", "nbformat_minor")

    /** Output with "text" and text-like mime data joined into single strings. */
    fun normalizeOutput(o: JsonObject): JsonObject {
        val m = LinkedHashMap(o)
        o["text"]?.let { m["text"] = JsonPrimitive(joinMultiline(it)) }
        (o["data"] as? JsonObject)?.let { data ->
            m["data"] = JsonObject(data.mapValues { (mime, v) ->
                if (v is JsonArray && !isJsonMime(mime)) JsonPrimitive(joinMultiline(v)) else v
            })
        }
        (o["traceback"] as? JsonArray)?.let { tb ->
            m["traceback"] = JsonArray(tb.map { JsonPrimitive(joinMultiline(it)) })
        }
        return JsonObject(m)
    }

    private fun denormalizeOutput(o: JsonObject): JsonObject {
        val m = LinkedHashMap(o)
        (o["text"] as? JsonPrimitive)?.contentOrNull?.let { m["text"] = splitLines(it) }
        (o["data"] as? JsonObject)?.let { data ->
            m["data"] = JsonObject(data.mapValues { (mime, v) ->
                val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (s != null && isSplitMime(mime)) splitLines(s) else v
            })
        }
        return JsonObject(m)
    }

    private fun isJsonMime(mime: String) = mime == "application/json" || mime.endsWith("+json")

    // Same rule as nbformat: only text/* and a few others are stored as line lists.
    private fun isSplitMime(mime: String) =
        mime.startsWith("text/") || mime == "application/javascript" || mime == "image/svg+xml"

    fun joinMultiline(el: JsonElement?): String = when (el) {
        null, JsonNull -> ""
        is JsonArray -> el.joinToString("") { (it as? JsonPrimitive)?.contentOrNull ?: "" }
        is JsonPrimitive -> el.contentOrNull ?: ""
        else -> el.toString()
    }

    fun splitLines(s: String): JsonArray {
        if (s.isEmpty()) return JsonArray(emptyList())
        val out = ArrayList<JsonElement>()
        var start = 0
        while (start < s.length) {
            val nl = s.indexOf('\n', start)
            val end = if (nl < 0) s.length else nl + 1
            out.add(JsonPrimitive(s.substring(start, end)))
            start = end
        }
        return JsonArray(out)
    }

    private fun StringBuilder.writeJson(el: JsonElement, depth: Int) {
        when (el) {
            is JsonObject -> {
                if (el.isEmpty()) { append("{}"); return }
                append("{\n")
                el.keys.sorted().forEachIndexed { i, k ->
                    indent(depth + 1)
                    append(JsonPrimitive(k).toString()).append(": ")
                    writeJson(el.getValue(k), depth + 1)
                    if (i < el.size - 1) append(',')
                    append('\n')
                }
                indent(depth); append('}')
            }
            is JsonArray -> {
                if (el.isEmpty()) { append("[]"); return }
                append("[\n")
                el.forEachIndexed { i, v ->
                    indent(depth + 1)
                    writeJson(v, depth + 1)
                    if (i < el.size - 1) append(',')
                    append('\n')
                }
                indent(depth); append(']')
            }
            else -> append(el.toString())
        }
    }

    private fun StringBuilder.indent(depth: Int) {
        repeat(depth) { append(' ') }
    }
}

/**
 * Whether two notebooks show the same thing. Execution counts and output metadata are ignored,
 * and so is the cell number in traceback file names, so running a notebook again with the same
 * results doesn't count as a change.
 */
object Changes {
    fun same(a: Notebook, b: Notebook): Boolean {
        if (a === b) return true
        if (a.metadata != b.metadata || a.extra != b.extra || a.cells.size != b.cells.size) return false
        return a.cells.indices.all { sameCell(a.cells[it], b.cells[it]) }
    }

    private fun sameCell(a: Cell, b: Cell): Boolean {
        if (a === b) return true
        if (a.id != b.id || a.type != b.type || a.source != b.source) return false
        if (a.metadata != b.metadata || a.extra != b.extra || a.outputs.size != b.outputs.size) return false
        return a.outputs.indices.all { sameOutput(a.outputs[it], b.outputs[it]) }
    }

    private fun sameOutput(a: JsonObject, b: JsonObject) = a === b || comparable(a) == comparable(b)

    private val IGNORED = setOf("execution_count", "metadata")
    private val CELL_NAME = Regex("""<cell \[\d+\]>""")
    private val SPACE = Regex("""\s+""")

    private fun comparable(o: JsonObject): Map<String, JsonElement> = o.filterKeys { it !in IGNORED }.mapValues { (k, v) ->
        when {
            k == "traceback" && v is JsonArray -> JsonArray(v.map { line ->
                (line as? JsonPrimitive)?.contentOrNull?.let { JsonPrimitive(it.replace(CELL_NAME, "<cell>")) } ?: line
            })
            k == "data" && v is JsonObject -> JsonObject(v.mapValues { (mime, d) ->
                // Base64 images may be saved with line breaks.
                val text = (d as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (text != null && mime.startsWith("image/") && mime != "image/svg+xml") {
                    JsonPrimitive(text.replace(SPACE, ""))
                } else d
            })
            else -> v
        }
    }
}

/** Pure operations on a cell's outputs, used while a cell runs. */
object Outputs {
    /**
     * Append [new] to [outputs] like Jupyter: consecutive stream outputs with the
     * same name are merged, and carriage returns overwrite the current line
     * (so progress bars don't print one line per update).
     */
    fun append(outputs: List<JsonObject>, new: JsonObject): List<JsonObject> {
        val type = new.str("output_type")
        if (type == "stream") {
            val last = outputs.lastOrNull()
            val name = new.str("name")
            if (last != null && last.str("output_type") == "stream" && last.str("name") == name) {
                val merged = applyCarriageReturns(last.str("text") + new.str("text"))
                return outputs.dropLast(1) + JsonObject(last + ("text" to JsonPrimitive(merged)))
            }
            return outputs + JsonObject(new + ("text" to JsonPrimitive(applyCarriageReturns(new.str("text")))))
        }
        return outputs + new
    }

    fun applyCarriageReturns(text: String): String {
        if ('\r' !in text) return text
        val lines = text.split('\n')
        return lines.joinToString("\n") { line ->
            if ('\r' !in line) return@joinToString line
            val trailing = line.endsWith('\r')
            val parts = line.split('\r')
            val visible = parts.lastOrNull { it.isNotEmpty() } ?: ""
            // Keep a trailing \r so the next write still overwrites this line.
            if (trailing) "$visible\r" else visible
        }.replace("\r\n", "\n")
    }

    fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""
}

fun JsonElement.asStringList(): List<String> = (this as? JsonArray)?.map { NotebookIO.joinMultiline(it) } ?: emptyList()
