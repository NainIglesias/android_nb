package io.github.nainiglesias.nb.data

/**
 * The Markdown renderer skips raw HTML, but READMEs often use `<table>` for what pipe tables
 * can't do (colspan, alignment, line breaks). This turns each HTML table into a GFM pipe table,
 * which the renderer draws, with its links still clickable.
 */
object HtmlTables {
    private val TAG = Regex("""<(/?)([A-Za-z][A-Za-z0-9]*)((?:[^>"']|"[^"]*"|'[^']*')*)>|<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val ATTR = Regex("""([A-Za-z-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
    private val FENCE = Regex("""^\s{0,3}(`{3,}|~{3,})""")
    private val ENTITY = Regex("""&(#\d+|#[xX][0-9a-fA-F]+|[A-Za-z]+);""")
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "emsp" to " ", "ensp" to " ", "thinsp" to " ", "ndash" to "–", "mdash" to "—",
        "hellip" to "…", "middot" to "·", "rarr" to "→", "larr" to "←", "times" to "×", "copy" to "©",
    )

    /** [markdown] with every HTML table outside code fences replaced by a pipe table. */
    fun convert(markdown: String): String {
        if (!markdown.contains("<table", ignoreCase = true)) return markdown
        val lines = markdown.lines()
        val out = StringBuilder()
        var fence: String? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val marker = FENCE.find(line)?.groupValues?.get(1)
            if (fence != null) {
                if (marker != null && marker[0] == fence[0] && marker.length >= fence.length && line.trim() == marker) fence = null
            } else if (marker != null) {
                fence = marker
            } else if (line.trimStart().startsWith("<table", ignoreCase = true)) {
                // GitHub passes the whole table to the browser, blank lines inside included.
                val end = (i until lines.size).firstOrNull { lines[it].contains("</table>", ignoreCase = true) }
                if (end != null) {
                    out.append('\n').append(toMarkdown(lines.subList(i, end + 1).joinToString("\n"))).append("\n\n")
                    i = end + 1
                    continue
                }
            }
            out.append(line)
            if (i < lines.lastIndex) out.append('\n')
            i++
        }
        return out.toString()
    }

    private class Cell(val header: Boolean, val span: Int, val align: String?) {
        val text = StringBuilder()
    }

    private class Row(val inHead: Boolean) {
        val cells = mutableListOf<Cell>()
    }

    /** One `<table>…</table>` as text before the table (full-width title rows) and the pipe table. */
    fun toMarkdown(html: String): String {
        val rows = mutableListOf<Row>()
        var inHead = false
        var row: Row? = null
        var cell: Cell? = null
        var pos = 0
        val linkStack = ArrayDeque<String?>()
        val styles = ArrayDeque<Int>() // where the text of each open <b>, <em>, <code> starts
        fun text(s: String) {
            cell?.text?.append(decode(s))
        }
        for (m in TAG.findAll(html)) {
            text(html.substring(pos, m.range.first))
            pos = m.range.last + 1
            if (m.value.startsWith("<!--")) continue
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            val attrs = attrs(m.groupValues[3])
            when (name) {
                "thead" -> inHead = !closing
                "tr" -> if (!closing) row = Row(inHead).also { rows += it } else { row = null; cell = null }
                "td", "th" -> if (closing) cell = null else {
                    styles.clear()
                    val r = row ?: Row(inHead).also { rows += it; row = it }
                    cell = Cell(name == "th", attrs["colspan"]?.toIntOrNull()?.coerceIn(1, 50) ?: 1, attrs["align"]?.lowercase())
                        .also { r.cells += it }
                }
                "a" -> if (!closing) {
                    // Links become [text](href); the closing tag finds the opening "[" by a marker.
                    attrs["href"]?.let { cell?.text?.append(LINK_OPEN) }
                    linkStack += attrs["href"]
                } else {
                    val href = linkStack.removeLastOrNull()
                    val c = cell
                    if (href != null && c != null) {
                        val start = c.text.lastIndexOf(LINK_OPEN)
                        if (start >= 0) {
                            val label = c.text.substring(start + 1).trim()
                            c.text.setLength(start)
                            c.text.append(if (label.isEmpty()) "" else "[$label](${href.replace(" ", "%20")})")
                        }
                    }
                }
                "b", "strong", "i", "em", "code" -> {
                    val marker = when (name) { "code" -> "`"; "i", "em" -> "*"; else -> "**" }
                    val c = cell
                    if (c != null && !closing) {
                        c.text.append(marker)
                        styles += c.text.length
                    } else if (c != null) {
                        val start = styles.removeLastOrNull()
                        val inner = start?.let { c.text.substring(it.coerceAtMost(c.text.length)) }
                        when {
                            // An empty <b></b> leaves nothing behind.
                            start != null && inner!!.isBlank() -> c.text.setLength(start - marker.length)
                            start != null -> {
                                // "**text **" isn't bold in Markdown: move the space outside.
                                val trailing = inner!!.length - inner.trimEnd().length
                                c.text.setLength(c.text.length - trailing)
                                c.text.append(marker).append(" ".repeat(trailing.coerceAtMost(1)))
                            }
                        }
                    }
                }
                "br" -> text(" · ")
                "img" -> if (!closing) attrs["alt"]?.let { text(it) }
            }
        }
        if (rows.isEmpty()) return ""

        val width = rows.maxOf { r -> r.cells.sumOf { it.span } }
        fun clean(c: Cell): String {
            val s = c.text.toString().replace(LINK_OPEN.toString(), "").replace(Regex("\\s+"), " ").trim()
                .replace("|", "\\|")
            return if (s.startsWith("· ")) s.removePrefix("· ") else s.removeSuffix(" ·")
        }
        // A row whose only cell spans the whole table is a title ("Term 1") or a spacer.
        fun banner(r: Row) = width > 1 && r.cells.size == 1 && r.cells[0].span >= width

        val headIndex = rows.indexOfLast { it.inHead && !banner(it) }.takeIf { it >= 0 }
            ?: rows.indexOfFirst { !banner(it) }.takeIf { it >= 0 }
        val before = StringBuilder()
        val table = mutableListOf<String>()
        fun line(cells: List<String>) = "| " + cells.joinToString(" | ") + " |"
        fun expand(r: Row, bold: Boolean): List<String> {
            val cells = r.cells.flatMap { c ->
                val t = clean(c)
                listOf(if (bold && c.header && t.isNotEmpty() && !t.startsWith("**")) "**$t**" else t) + List(c.span - 1) { "" }
            }
            return cells + List(width - cells.size) { "" }
        }
        rows.forEachIndexed { index, r ->
            when {
                headIndex == null || index < headIndex -> {
                    val t = r.cells.joinToString(" ") { clean(it) }.trim()
                    if (t.isNotEmpty()) before.append(t).append("\n\n")
                }
                index == headIndex -> {
                    table += line(expand(r, bold = false))
                    var col = 0
                    val aligns = MutableList(width) { "---" }
                    for (c in r.cells) {
                        aligns[col] = when (c.align) { "right" -> "---:"; "center" -> ":---:"; "left" -> ":---"; else -> "---" }
                        col += c.span
                    }
                    table += line(aligns)
                }
                banner(r) -> {
                    val t = clean(r.cells[0])
                    if (t.isNotEmpty()) table += line(listOf(if (t.startsWith("**")) t else "**$t**") + List(width - 1) { "" })
                }
                else -> table += line(expand(r, bold = true))
            }
        }
        return before.toString() + table.joinToString("\n")
    }

    // Marks where a link's text starts inside a cell; never appears in real text.
    private const val LINK_OPEN = '\u0000'

    private fun attrs(s: String): Map<String, String> =
        ATTR.findAll(s).associate { m ->
            m.groupValues[1].lowercase() to (m.groups[2]?.value ?: m.groups[3]?.value ?: m.groupValues[4]).let(::decode)
        }

    private fun decode(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        val code = when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)
            e.startsWith("#") -> e.substring(1).toIntOrNull()
            else -> null
        }
        when {
            code != null -> if (Character.isValidCodePoint(code)) String(Character.toChars(code)) else m.value
            else -> NAMED[e] ?: m.value
        }
    }
}
