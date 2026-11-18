package io.github.nainiglesias.nb.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LinksTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun resolvesLinksInsideTheProject() {
        val root = tmp.newFolder("project")
        val docs = File(root, "docs").apply { mkdirs() }
        File(root, "practica 2.ipynb").writeText("{}")
        File(docs, "notas.md").writeText("# n")
        File(root, "a+b.md").writeText("")
        fun local(f: File) = LinkTarget.Local(f.normalize())

        // From a file in docs/.
        assertEquals(local(File(root, "practica 2.ipynb")), Links.resolve("../practica%202.ipynb", docs, root))
        assertEquals(local(File(docs, "notas.md")), Links.resolve("./notas.md#intro", docs, root))
        assertEquals(local(File(docs, "notas.md")), Links.resolve("/docs/notas.md", docs, root))
        assertEquals(local(docs), Links.resolve("../docs/", docs, root))
        assertEquals(local(File(root, "a+b.md")), Links.resolve("a+b.md", root, root))

        assertEquals(LinkTarget.Missing("otro.md"), Links.resolve("otro.md", docs, root))
        // Can't leave the project, even if the file exists.
        tmp.newFile("secret.md")
        assertEquals(LinkTarget.Missing("../../secret.md"), Links.resolve("../../secret.md", docs, root))
        // A notebook opened from outside a project has nothing to resolve against.
        assertEquals(LinkTarget.Missing("notas.md"), Links.resolve("notas.md", null, null))
    }

    @Test
    fun externalAndSamePageLinks() {
        assertEquals(LinkTarget.External("https://numpy.org"), Links.resolve(" https://numpy.org ", null, null))
        assertEquals(LinkTarget.External("mailto:a@b.c"), Links.resolve("mailto:a@b.c", null, null))
        assertEquals(LinkTarget.SamePage, Links.resolve("#section-1", null, null))
        assertEquals("a b/ñ", Links.decode("a%20b/%C3%B1"))
        assertEquals("100%", Links.decode("100%"))
    }
}
