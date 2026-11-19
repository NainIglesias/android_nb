package io.github.nainiglesias.nb.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.m3.Markdown
import io.github.nainiglesias.nb.data.HtmlTables

/** Rendered Markdown, with HTML tables turned into tables the renderer can draw. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    val content = remember(text) { HtmlTables.convert(text) }
    Markdown(content, modifier = modifier)
}
