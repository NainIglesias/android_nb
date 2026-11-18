package io.github.nainiglesias.nb.notebook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookTest {
    private val sample = """
    {
     "cells": [
      {
       "cell_type": "markdown",
       "id": "intro",
       "metadata": {},
       "source": ["# Title\n", "Some text"]
      },
      {
       "cell_type": "code",
       "execution_count": 3,
       "id": "c1",
       "metadata": {"tags": ["x"]},
       "outputs": [
        {"name": "stdout", "output_type": "stream", "text": ["a\n", "b\n"]},
        {"data": {"image/png": "iVBOR", "text/plain": ["<Figure>"]}, "metadata": {}, "output_type": "display_data"}
       ],
       "source": ["import numpy as np\n", "np.arange(3)"]
      }
     ],
     "metadata": {"kernelspec": {"name": "python3"}},
     "nbformat": 4,
     "nbformat_minor": 5,
     "custom": true
    }
    """.trimIndent()

    @Test
    fun parsesCellsAndJoinsMultilineStrings() {
        val nb = NotebookIO.parse(sample)
        assertEquals(2, nb.cells.size)
        assertEquals(CellType.MARKDOWN, nb.cells[0].type)
        assertEquals("# Title\nSome text", nb.cells[0].source)
        val code = nb.cells[1]
        assertEquals("import numpy as np\nnp.arange(3)", code.source)
        assertEquals(3, code.executionCount)
        assertEquals("a\nb\n", code.outputs[0]["text"]!!.jsonPrimitive.content)
        assertEquals("<Figure>", code.outputs[1]["data"]!!.jsonObject["text/plain"]!!.jsonPrimitive.content)
    }

    @Test
    fun roundTripKeepsContentAndUnknownKeys() {
        val nb = NotebookIO.parse(sample)
        val text = NotebookIO.serialize(nb)
        assertEquals(nb, NotebookIO.parse(text))
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals(JsonPrimitive(true), root["custom"])
        val cell = root["cells"]!!.jsonArray[1].jsonObject
        assertEquals(listOf("import numpy as np\n", "np.arange(3)"), cell["source"]!!.jsonArray.map { it.jsonPrimitive.content })
        // Images stay single strings, text is split into lines, like nbformat.
        val data = cell["outputs"]!!.jsonArray[1].jsonObject["data"]!!.jsonObject
        assertTrue(data["image/png"] is JsonPrimitive)
        assertEquals(1, data["text/plain"]!!.jsonArray.size)
    }

    @Test
    fun serializesLikeJupyter() {
        val nb = Notebook(
            cells = listOf(Cell("abc", CellType.CODE, "x = 1\n")),
            metadata = EMPTY,
        )
        val expected = """
        {
         "cells": [
          {
           "cell_type": "code",
           "execution_count": null,
           "id": "abc",
           "metadata": {},
           "outputs": [],
           "source": [
            "x = 1\n"
           ]
          }
         ],
         "metadata": {},
         "nbformat": 4,
         "nbformat_minor": 5
        }
        """.trimIndent() + "\n"
        assertEquals(expected, NotebookIO.serialize(nb))
    }

    @Test
    fun oldNotebooksGetIdsAndUnique() {
        val text = """{"cells": [{"cell_type": "code", "source": "1", "metadata": {}, "outputs": []},
            {"cell_type": "code", "id": "same", "source": "", "metadata": {}, "outputs": []},
            {"cell_type": "code", "id": "same", "source": "", "metadata": {}, "outputs": []}],
            "metadata": {}, "nbformat": 4, "nbformat_minor": 2}"""
        val nb = NotebookIO.parse(text)
        assertEquals(3, nb.cells.map { it.id }.toSet().size)
        assertNull(nb.cells[0].executionCount)
        assertTrue(NotebookIO.serialize(nb).contains("\"nbformat_minor\": 5"))
    }

    @Test
    fun rejectsNonNotebooks() {
        assertThrows(NotebookFormatException::class.java) { NotebookIO.parse("not json") }
        assertThrows(NotebookFormatException::class.java) { NotebookIO.parse("[]") }
        assertThrows(NotebookFormatException::class.java) { NotebookIO.parse("""{"nbformat": 3, "worksheets": []}""") }
    }

    private fun stream(text: String, name: String = "stdout") = JsonObject(
        mapOf("output_type" to JsonPrimitive("stream"), "name" to JsonPrimitive(name), "text" to JsonPrimitive(text))
    )

    @Test
    fun mergesStreamsAndHandlesCarriageReturns() {
        var outs = Outputs.append(emptyList(), stream("a"))
        outs = Outputs.append(outs, stream("b\n"))
        outs = Outputs.append(outs, stream("err\n", "stderr"))
        assertEquals(2, outs.size)
        assertEquals("ab\n", outs[0]["text"]!!.jsonPrimitive.content)

        var bar = Outputs.append(emptyList(), stream("10%\r"))
        bar = Outputs.append(bar, stream("50%\r"))
        bar = Outputs.append(bar, stream("100%\n"))
        assertEquals("100%\n", bar[0]["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun rerunWithSameOutputIsNotAChange() {
        val saved = NotebookIO.parse(sample)
        val code = saved.cells[1]
        fun rerun(outputs: List<JsonObject>) =
            saved.copy(cells = saved.cells.toMutableList().also { it[1] = code.copy(outputs = outputs, executionCount = 1) })
        fun out(json: String) = Json.parseToJsonElement(json).jsonObject

        // Same text, image with a line break removed, metadata dropped, new execution count.
        val same = rerun(
            listOf(
                out("""{"output_type": "stream", "name": "stdout", "text": "a\nb\n"}"""),
                out("""{"output_type": "display_data", "data": {"image/png": "iV\nBOR", "text/plain": "<Figure>"}, "metadata": {"x": 1}}"""),
            )
        )
        assertTrue(Changes.same(same, saved))

        val different = rerun(listOf(out("""{"output_type": "stream", "name": "stdout", "text": "a\n"}""")))
        assertTrue(!Changes.same(different, saved))
        val edited = saved.copy(cells = saved.cells.toMutableList().also { it[1] = code.copy(source = "1 + 1") })
        assertTrue(!Changes.same(edited, saved))

        // Tracebacks name the cell by its execution count.
        fun error(n: Int) = out("""{"output_type": "error", "ename": "E", "evalue": "", "traceback": ["File <cell [$n]>, line 1"]}""")
        assertTrue(Changes.same(rerun(listOf(error(3))), rerun(listOf(error(7)))))
    }
}
