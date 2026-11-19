package io.github.nainiglesias.nb.ui

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.nainiglesias.nb.notebook.Ansi
import io.github.nainiglesias.nb.notebook.Outputs.str
import io.github.nainiglesias.nb.notebook.asStringList
import io.github.nainiglesias.nb.ui.theme.LocalCodeColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val MAX_LINES = 400
private val PrettyJson = Json { prettyPrint = true }

@Composable
fun OutputView(output: JsonObject) {
    when (output.str("output_type")) {
        "stream" -> TextOutput(output.str("text"), error = output.str("name") == "stderr")
        "error" -> {
            val tb = output["traceback"]?.asStringList().orEmpty()
            val text = if (tb.isEmpty()) "${output.str("ename")}: ${output.str("evalue")}" else tb.joinToString("\n")
            TextOutput(text, error = true)
        }
        "execute_result", "display_data" -> (output["data"] as? JsonObject)?.let { RichOutput(it) }
    }
}

@Composable
private fun RichOutput(data: JsonObject) {
    fun s(mime: String) = (data[mime] as? JsonPrimitive)?.contentOrNull
    val html = s("text/html")
    val plain = s("text/plain")
    when {
        s("image/png") != null -> ImageOutput(s("image/png")!!)
        s("image/jpeg") != null -> ImageOutput(s("image/jpeg")!!)
        // JavaScript is disabled, so script-driven HTML (plotly, widgets) would be blank.
        html != null && !(plain != null && html.contains("<script", ignoreCase = true)) -> HtmlOutput(html)
        s("image/svg+xml") != null -> HtmlOutput(s("image/svg+xml")!!)
        s("text/markdown") != null -> MarkdownText(s("text/markdown")!!)
        s("text/latex") != null -> TextOutput(s("text/latex")!!)
        data["application/json"] != null ->
            TextOutput(PrettyJson.encodeToString(data["application/json"]!!))
        plain != null -> TextOutput(plain)
        else -> TextOutput("[output types not supported: ${data.keys.joinToString()}]", error = true)
    }
}

@Composable
fun TextOutput(text: String, error: Boolean = false) {
    val colors = LocalCodeColors.current
    val default = MaterialTheme.colorScheme.onSurface
    var expanded by rememberSaveable(text.length) { mutableStateOf(false) }
    val lineCount = remember(text) { text.count { it == '\n' } + 1 }
    val shown = if (expanded || lineCount <= MAX_LINES) text else text.lineSequence().take(MAX_LINES).joinToString("\n")
    val annotated = remember(shown, colors, default) {
        if ('\u001B' in shown) ansiToAnnotated(shown, colors, default)
        else androidx.compose.ui.text.AnnotatedString(shown.replace("\r", ""))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (error) Modifier.clip(RoundedCornerShape(6.dp)).background(colors.stderrBackground) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        SelectionContainer(Modifier.horizontalScroll(rememberScrollState())) {
            Text(
                annotated,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                softWrap = false,
            )
        }
        if (lineCount > MAX_LINES && !expanded) {
            TextButton(onClick = { expanded = true }) { Text("Show all $lineCount lines") }
        }
    }
}

@Composable
private fun ImageOutput(base64: String) {
    val bitmap = remember(base64) {
        runCatching {
            val bytes = Base64.decode(base64.replace("\n", ""), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap == null) {
        TextOutput("[image couldn't be decoded]", error = true)
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Fit the width, but don't blow tiny images up more than 3x.
        val natural = with(LocalDensity.current) { (bitmap.width * 3).toDp() }
        Image(
            bitmap = bitmap,
            contentDescription = "Output image",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .width(minOf(maxWidth, natural))
                .aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))
                .background(Color.White),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlOutput(html: String) {
    val fg = MaterialTheme.colorScheme.onSurface.toCss()
    val border = MaterialTheme.colorScheme.outlineVariant.toCss()
    val stripe = MaterialTheme.colorScheme.surfaceVariant.toCss()
    val page = remember(html, fg, border, stripe) {
        """
        <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          html, body { margin: 0; padding: 0; background: transparent; color: $fg;
                       font: 13px/1.4 sans-serif; overflow-x: auto; }
          table { border-collapse: collapse; font-size: 12px; }
          th, td { border: 1px solid $border; padding: 3px 7px; text-align: right; white-space: nowrap; }
          thead th, tbody th { font-weight: 600; }
          tbody tr:nth-child(odd) { background: $stripe; }
          img, svg { max-width: 100%; height: auto; }
          pre { white-space: pre-wrap; }
        </style></head><body>$html</body></html>
        """.trimIndent()
    }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
            }
        },
        update = { view ->
            if (view.tag != page) {
                view.tag = page
                view.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun Color.toCss(): String = String.format("#%06X", toArgb() and 0xFFFFFF)

/** Plain text of an output, for "copy". */
fun outputPlainText(output: JsonObject): String = when (output.str("output_type")) {
    "stream" -> output.str("text")
    "error" -> Ansi.strip(output["traceback"]?.asStringList().orEmpty().joinToString("\n"))
    else -> ((output["data"] as? JsonObject)?.get("text/plain") as? JsonPrimitive)?.contentOrNull ?: ""
}
