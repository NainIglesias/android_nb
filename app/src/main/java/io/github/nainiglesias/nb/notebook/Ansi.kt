package io.github.nainiglesias.nb.notebook

/**
 * Minimal ANSI SGR parser for tracebacks and colored output saved by Jupyter.
 * Colors are indexes 0-15 into the standard terminal palette; other escape
 * sequences are dropped.
 */
object Ansi {
    data class Style(val fg: Int? = null, val bg: Int? = null, val bold: Boolean = false)
    data class Span(val text: String, val style: Style)

    private val ESCAPE = Regex("\u001B\\[([0-9;?]*)([A-Za-z])")

    fun parse(input: String): List<Span> {
        val text = input.replace("\r", "")
        val spans = ArrayList<Span>()
        var style = Style()
        var pos = 0
        for (m in ESCAPE.findAll(text)) {
            if (m.range.first > pos) spans.add(Span(text.substring(pos, m.range.first), style))
            pos = m.range.last + 1
            if (m.groupValues[2] == "m") style = apply(style, m.groupValues[1])
        }
        if (pos < text.length) spans.add(Span(text.substring(pos), style))
        return mergeAdjacent(spans)
    }

    fun strip(input: String): String = parse(input).joinToString("") { it.text }

    private fun apply(start: Style, params: String): Style {
        var s = start
        val codes = if (params.isEmpty()) listOf(0) else params.split(';').map { it.toIntOrNull() ?: 0 }
        var i = 0
        while (i < codes.size) {
            when (val c = codes[i]) {
                0 -> s = Style()
                1 -> s = s.copy(bold = true)
                22 -> s = s.copy(bold = false)
                in 30..37 -> s = s.copy(fg = c - 30)
                in 90..97 -> s = s.copy(fg = c - 90 + 8)
                39 -> s = s.copy(fg = null)
                in 40..47 -> s = s.copy(bg = c - 40)
                in 100..107 -> s = s.copy(bg = c - 100 + 8)
                49 -> s = s.copy(bg = null)
                38, 48 -> {
                    // 256-color (5;n) or truecolor (2;r;g;b): map 256-color basics, skip the rest.
                    val mode = codes.getOrNull(i + 1)
                    if (mode == 5) {
                        val n = codes.getOrNull(i + 2)
                        val idx = n?.takeIf { it < 16 }
                        s = if (c == 38) s.copy(fg = idx) else s.copy(bg = idx)
                        i += 2
                    } else if (mode == 2) {
                        // Truecolor can't be mapped to the palette: use the default color.
                        s = if (c == 38) s.copy(fg = null) else s.copy(bg = null)
                        i += 4
                    }
                }
            }
            i++
        }
        return s
    }

    private fun mergeAdjacent(spans: List<Span>): List<Span> {
        val out = ArrayList<Span>(spans.size)
        for (s in spans) {
            if (s.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.style == s.style) out[out.size - 1] = Span(last.text + s.text, s.style)
            else out.add(s)
        }
        return out
    }
}
