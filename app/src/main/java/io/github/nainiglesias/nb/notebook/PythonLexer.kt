package io.github.nainiglesias.nb.notebook

/** A small Python tokenizer, good enough for syntax highlighting. */
object PythonLexer {
    enum class Kind { KEYWORD, BUILTIN, STRING, COMMENT, NUMBER, DECORATOR, FUNCTION_NAME, MAGIC }

    data class Token(val start: Int, val end: Int, val kind: Kind)

    private val KEYWORDS = setOf(
        "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
        "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
        "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise",
        "return", "try", "while", "with", "yield", "match", "case",
    )

    private val BUILTINS = setOf(
        "abs", "all", "any", "bool", "dict", "dir", "enumerate", "filter", "float", "format",
        "getattr", "hasattr", "id", "input", "int", "isinstance", "issubclass", "iter", "len",
        "list", "map", "max", "min", "next", "object", "open", "print", "range", "repr",
        "reversed", "round", "set", "setattr", "slice", "sorted", "str", "sum", "super",
        "tuple", "type", "vars", "zip", "self", "Exception", "ValueError", "TypeError",
        "KeyError", "IndexError", "display",
    )

    fun tokenize(code: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        val n = code.length
        var lineStart = true
        var afterDefOrClass = false
        while (i < n) {
            val c = code[i]
            when {
                c == '\n' -> { lineStart = true; i++; continue }
                c == ' ' || c == '\t' -> { i++; continue }
                c == '#' -> {
                    val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                    out.add(Token(i, end, Kind.COMMENT)); i = end
                }
                lineStart && (c == '%' || c == '!') -> {
                    val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                    out.add(Token(i, end, Kind.MAGIC)); i = end
                }
                c == '@' && lineStart -> {
                    var j = i + 1
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '_' || code[j] == '.')) j++
                    out.add(Token(i, j, Kind.DECORATOR)); i = j
                }
                c == '"' || c == '\'' -> {
                    val end = stringEnd(code, i)
                    out.add(Token(prefixStart(code, i), end, Kind.STRING)); i = end
                }
                c.isDigit() || (c == '.' && i + 1 < n && code[i + 1].isDigit()) -> {
                    var j = i + 1
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '_' || code[j] == '.')) j++
                    out.add(Token(i, j, Kind.NUMBER)); i = j
                }
                c.isLetter() || c == '_' -> {
                    var j = i + 1
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                    // String prefixes such as f"", rb'' are handled when the quote is reached.
                    if (j < n && (code[j] == '"' || code[j] == '\'') && j - i <= 2 &&
                        code.substring(i, j).all { it in "rRbBfFuU" }
                    ) {
                        i = j; lineStart = false; continue
                    }
                    val word = code.substring(i, j)
                    when {
                        afterDefOrClass -> { out.add(Token(i, j, Kind.FUNCTION_NAME)); afterDefOrClass = false }
                        word in KEYWORDS -> {
                            out.add(Token(i, j, Kind.KEYWORD))
                            afterDefOrClass = word == "def" || word == "class"
                        }
                        word in BUILTINS -> out.add(Token(i, j, Kind.BUILTIN))
                    }
                    i = j
                }
                else -> i++
            }
            lineStart = false
        }
        return out
    }

    private fun prefixStart(code: String, quote: Int): Int {
        var s = quote
        while (s > 0 && quote - s < 2 && code[s - 1] in "rRbBfFuU") s--
        if (s > 0 && (code[s - 1].isLetterOrDigit() || code[s - 1] == '_')) return quote
        return s
    }

    private fun stringEnd(code: String, start: Int): Int {
        val q = code[start]
        val triple = code.startsWith("$q$q$q", start)
        var i = start + if (triple) 3 else 1
        while (i < code.length) {
            val c = code[i]
            when {
                c == '\\' -> i += 2
                triple && code.startsWith("$q$q$q", i) -> return i + 3
                !triple && c == q -> return i + 1
                !triple && c == '\n' -> return i
                else -> i++
            }
        }
        return code.length
    }
}
