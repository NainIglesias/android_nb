package io.github.nainiglesias.nb.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlTablesTest {
    @Test
    fun convertsAReadmeTable() {
        // Shaped like the roadmap in github.com/avidaldo/ai: blank lines inside, colspan, <br>, entities.
        val md = """
            Intro text.

            <table>
            <thead>
            <tr><th colspan="4" align="left">🟦 SAA &emsp; 🟧 PIA</th></tr>
            <tr>
                <th align="right">#</th><th align="left">Stage</th><th align="left">Topic</th><th align="left">Dates</th>
            </tr>
            </thead>
            <tbody>

            <tr><th colspan="4"></th></tr>
            <tr><th colspan="4" align="left">Term 1</th></tr>
            <tr><td align="right">1</td><td>🟧 Getting started</td><td><a href="pia/setup/README.md">Setting up</a>: your tools | more</td><td nowrap>14–25 Oct</td></tr>
            <tr><td></td><td colspan="2"><em>Christmas</em></td><td nowrap><em>22 Dec – 7 Jan</em></td></tr>
            <tr><td></td><td><b>February exams</b></td><td>Term 1</td><td nowrap>🟦 SAA <b>Tue 2 Feb</b><br>🟧 PIA <b>Thu 4 Feb</b></td></tr>
            </tbody>
            </table>

            ```html
            <table><tr><td>code</td></tr></table>
            ```
        """.trimIndent()
        val expected = """
            Intro text.


            🟦 SAA 🟧 PIA

            | # | Stage | Topic | Dates |
            | ---: | :--- | :--- | :--- |
            | **Term 1** |  |  |  |
            | 1 | 🟧 Getting started | [Setting up](pia/setup/README.md): your tools \| more | 14–25 Oct |
            |  | *Christmas* |  | *22 Dec – 7 Jan* |
            |  | **February exams** | Term 1 | 🟦 SAA **Tue 2 Feb** · 🟧 PIA **Thu 4 Feb** |


            ```html
            <table><tr><td>code</td></tr></table>
            ```
        """.trimIndent()
        assertEquals(expected, HtmlTables.convert(md))
    }

    @Test
    fun tablesWithoutAHeaderUseTheFirstRow() {
        val html = """<table><tr><td>a</td><td><a href="x y.md"><code>b</code></a> <b>c </b>d<em></em></td></tr><tr><td>&lt;1&gt;</td></tr></table>"""
        assertEquals("| a | [`b`](x%20y.md) **c** d |\n| --- | --- |\n| <1> |  |", HtmlTables.toMarkdown(html))
        assertEquals("No tables here", HtmlTables.convert("No tables here"))
    }
}
