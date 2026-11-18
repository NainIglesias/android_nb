package io.github.nainiglesias.nb.notebook

import io.github.nainiglesias.nb.notebook.PythonLexer.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextToolsTest {
    @Test
    fun ansiColorsAndReset() {
        val spans = Ansi.parse("\u001B[0;31mValueError\u001B[0m: bad\r")
        assertEquals(listOf("ValueError", ": bad"), spans.map { it.text })
        assertEquals(1, spans[0].style.fg)
        assertNull(spans[1].style.fg)
        assertEquals("ValueError: bad", Ansi.strip("\u001B[1;32mValueError\u001B[39m: bad"))
    }

    @Test
    fun ansiBrightAnd256() {
        val spans = Ansi.parse("\u001B[92ma\u001B[38;5;4mb\u001B[38;2;1;2;3mc")
        assertEquals(10, spans[0].style.fg)
        assertEquals(4, spans[1].style.fg)
        assertEquals("c", spans[2].text)
        assertNull(spans[2].style.fg)
    }

    private fun kinds(code: String) = PythonLexer.tokenize(code).map { code.substring(it.start, it.end) to it.kind }

    @Test
    fun lexer() {
        val tokens = kinds("def f(x):\n    return x + 1  # one\ns = f'{x}' + \"\"\"a\nb\"\"\"\n%time f(1)")
        assertEquals(
            listOf(
                "def" to Kind.KEYWORD, "f" to Kind.FUNCTION_NAME, "return" to Kind.KEYWORD,
                "1" to Kind.NUMBER, "# one" to Kind.COMMENT, "f'{x}'" to Kind.STRING,
                "\"\"\"a\nb\"\"\"" to Kind.STRING, "%time f(1)" to Kind.MAGIC,
            ),
            tokens,
        )
    }

    @Test
    fun lexerUnterminatedStringStopsAtLineEnd() {
        assertEquals(listOf("'abc" to Kind.STRING, "print" to Kind.BUILTIN), kinds("'abc\nprint"))
    }

    @Test
    fun autoIndent() {
        val old = "for i in range(3):"
        val e = Editing.autoIndent(old, "$old\n", old.length + 1)!!
        assertEquals("$old\n    ", e.text)
        assertEquals(e.text.length, e.cursor)

        val inner = "if x:\n    y = 1"
        val e2 = Editing.autoIndent(inner, "$inner\n", inner.length + 1)!!
        assertEquals("$inner\n    ", e2.text)

        assertNull(Editing.autoIndent("x = 1", "x = 1\n", 6))
        assertNull(Editing.autoIndent("ab", "abc", 3))
    }

    @Test
    fun insertReplacesSelection() {
        val e = Editing.insert("print(x)", 6, 7, "y")
        assertEquals("print(y)", e.text)
        assertEquals(7, e.cursor)
    }
}
