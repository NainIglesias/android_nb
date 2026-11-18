package io.github.nainiglesias.nb.notebook

/** Text edits for the code editor, kept free of Android types so they can be unit tested. */
object Editing {
    const val INDENT = "    "

    data class Edit(val text: String, val cursor: Int)

    /**
     * If [new] is [old] with a single newline typed at [cursor] - 1, carry the
     * previous line's indentation over (one level more after a ':').
     */
    fun autoIndent(old: String, new: String, cursor: Int): Edit? {
        if (new.length != old.length + 1 || cursor <= 0 || cursor > new.length) return null
        if (new[cursor - 1] != '\n') return null
        if (new.removeRange(cursor - 1, cursor) != old) return null
        val lineStart = if (cursor >= 2) new.lastIndexOf('\n', cursor - 2) + 1 else 0
        val line = new.substring(lineStart, cursor - 1)
        var indent = line.takeWhile { it == ' ' || it == '\t' }
        val code = line.substringBefore('#').trimEnd()
        if (code.endsWith(':')) indent += INDENT
        if (indent.isEmpty()) return null
        return Edit(new.substring(0, cursor) + indent + new.substring(cursor), cursor + indent.length)
    }

    /** Replace the selection [start, end) with [insert]. */
    fun insert(text: String, start: Int, end: Int, insert: String): Edit {
        val s = minOf(start, end).coerceIn(0, text.length)
        val e = maxOf(start, end).coerceIn(0, text.length)
        return Edit(text.substring(0, s) + insert + text.substring(e), s + insert.length)
    }
}
