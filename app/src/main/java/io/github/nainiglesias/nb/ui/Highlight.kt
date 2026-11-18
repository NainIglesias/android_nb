package io.github.nainiglesias.nb.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import io.github.nainiglesias.nb.notebook.Ansi
import io.github.nainiglesias.nb.notebook.PythonLexer
import io.github.nainiglesias.nb.notebook.PythonLexer.Kind
import io.github.nainiglesias.nb.ui.theme.CodeColors

fun highlightPython(code: String, colors: CodeColors): AnnotatedString {
    val builder = AnnotatedString.Builder(code)
    for (t in PythonLexer.tokenize(code)) {
        val style = when (t.kind) {
            Kind.KEYWORD -> SpanStyle(color = colors.keyword, fontWeight = FontWeight.SemiBold)
            Kind.BUILTIN -> SpanStyle(color = colors.builtin)
            Kind.STRING -> SpanStyle(color = colors.string)
            Kind.COMMENT -> SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
            Kind.NUMBER -> SpanStyle(color = colors.number)
            Kind.DECORATOR -> SpanStyle(color = colors.decorator)
            Kind.FUNCTION_NAME -> SpanStyle(color = colors.function)
            Kind.MAGIC -> SpanStyle(color = colors.magic)
        }
        builder.addStyle(style, t.start, t.end)
    }
    return builder.toAnnotatedString()
}

class PythonHighlighting(private val colors: CodeColors) : VisualTransformation {
    override fun filter(text: AnnotatedString) =
        TransformedText(highlightPython(text.text, colors), OffsetMapping.Identity)

    override fun equals(other: Any?) = other is PythonHighlighting && other.colors == colors
    override fun hashCode() = colors.hashCode()
}

fun ansiToAnnotated(text: String, colors: CodeColors, default: Color): AnnotatedString = buildAnnotatedString {
    for (span in Ansi.parse(text)) {
        val s = span.style
        if (s.fg == null && s.bg == null && !s.bold) {
            append(span.text)
        } else {
            pushStyle(
                SpanStyle(
                    color = s.fg?.let { colors.ansi[it] } ?: default,
                    background = s.bg?.let { colors.ansi[it].copy(alpha = 0.35f) } ?: Color.Unspecified,
                    fontWeight = if (s.bold) FontWeight.Bold else null,
                )
            )
            append(span.text)
            pop()
        }
    }
}
