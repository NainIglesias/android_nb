package io.github.nainiglesias.nb.data

import java.io.File

/** Where a link in Markdown points. */
sealed interface LinkTarget {
    /** http(s), mailto, …: open in another app. */
    data class External(val url: String) : LinkTarget

    /** "#heading": a place in the same document. */
    data object SamePage : LinkTarget

    /** A file or folder inside the project. */
    data class Local(val file: File) : LinkTarget

    /** A relative link that doesn't lead to an existing file in the project. */
    data class Missing(val path: String) : LinkTarget
}

object Links {
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /**
     * Resolve [href] like GitHub does: relative to [baseDir] (the folder of the file holding the
     * link), or to [root] when it starts with "/". Links can't leave [root].
     */
    fun resolve(href: String, baseDir: File?, root: File?): LinkTarget {
        val link = href.trim()
        if (link.isEmpty() || link.startsWith("#")) return LinkTarget.SamePage
        if (SCHEME.containsMatchIn(link) && !link.startsWith("file:")) return LinkTarget.External(link)
        val path = decode(link.removePrefix("file://").substringBefore('#').substringBefore('?'))
        if (path.isEmpty()) return LinkTarget.SamePage
        if (baseDir == null || root == null) return LinkTarget.Missing(path)

        val target = (if (path.startsWith("/")) File(root, path.trimStart('/')) else File(baseDir, path)).normalize()
        val inside = target.toPath().startsWith(root.normalize().toPath())
        return if (inside && target.exists()) LinkTarget.Local(target) else LinkTarget.Missing(path)
    }

    /** Percent-decoding for paths ("+" stays a plus, unlike URLDecoder). */
    fun decode(s: String): String {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 <= s.lastIndex) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().encodeToByteArray())
            i++
        }
        return out.toByteArray().decodeToString()
    }
}
