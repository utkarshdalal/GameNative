package app.gamenative.ui.screen.support

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.gamenative.ui.theme.PluviaTheme

internal sealed class MarkdownBlock {
    data class Paragraph(val text: AnnotatedString) : MarkdownBlock()
    data class Code(val code: String) : MarkdownBlock()
}

internal data class MarkdownDocument(val blocks: List<MarkdownBlock>, val links: List<String>)

private val headingRegex = Regex("^#{1,3}\\s+(.*)$")
private val bulletRegex = Regex("^(\\s*)[-*•]\\s+(.*)$")
private val numberedRegex = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
private val quoteRegex = Regex("^>\\s?(.*)$")

private class InlineStyles(
    val code: SpanStyle,
    val link: TextLinkStyles,
)

internal fun parseMarkdown(source: String, codeBackground: Color, linkColor: Color): MarkdownDocument {
    val styles = InlineStyles(
        code = SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground),
        link = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
    )
    val blocks = mutableListOf<MarkdownBlock>()
    val links = mutableListOf<String>()
    val paragraph = mutableListOf<String>()
    var code: StringBuilder? = null

    fun flushParagraph() {
        while (paragraph.isNotEmpty() && paragraph.last().isBlank()) paragraph.removeAt(paragraph.lastIndex)
        while (paragraph.isNotEmpty() && paragraph.first().isBlank()) paragraph.removeAt(0)
        if (paragraph.isEmpty()) return
        val text = buildAnnotatedString {
            paragraph.forEachIndexed { index, line ->
                if (index > 0) append('\n')
                appendMarkdownLine(line, styles, links)
            }
        }
        blocks.add(MarkdownBlock.Paragraph(text))
        paragraph.clear()
    }

    for (line in source.replace("\r\n", "\n").split('\n')) {
        val trimmed = line.trim()
        val open = code
        if (open != null) {
            if (trimmed.startsWith("```")) {
                blocks.add(MarkdownBlock.Code(open.toString().trimEnd('\n')))
                code = null
            } else {
                open.append(line).append('\n')
            }
            continue
        }
        if (trimmed.startsWith("```")) {
            flushParagraph()
            val rest = trimmed.removePrefix("```")
            if (rest.length >= 3 && rest.endsWith("```")) {
                blocks.add(MarkdownBlock.Code(rest.removeSuffix("```")))
            } else {
                code = StringBuilder()
            }
            continue
        }
        paragraph.add(line)
    }
    code?.let { blocks.add(MarkdownBlock.Code(it.toString().trimEnd('\n'))) }
    flushParagraph()
    return MarkdownDocument(blocks, links.distinct())
}

private fun AnnotatedString.Builder.appendMarkdownLine(line: String, styles: InlineStyles, links: MutableList<String>) {
    headingRegex.matchEntire(line)?.let { match ->
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(match.groupValues[1], styles, links) }
        return
    }
    bulletRegex.matchEntire(line)?.let { match ->
        append("  ".repeat(match.groupValues[1].length / 2))
        append("• ")
        appendInline(match.groupValues[2], styles, links)
        return
    }
    numberedRegex.matchEntire(line)?.let { match ->
        append("  ".repeat(match.groupValues[1].length / 2))
        append(match.groupValues[2])
        append(". ")
        appendInline(match.groupValues[3], styles, links)
        return
    }
    quoteRegex.matchEntire(line)?.let { match ->
        append("│ ")
        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(match.groupValues[1], styles, links) }
        return
    }
    appendInline(line, styles, links)
}

private fun isWordChar(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '_')

private fun AnnotatedString.Builder.appendLink(label: String, url: String, styles: InlineStyles, links: MutableList<String>) {
    links.add(url)
    withLink(LinkAnnotation.Url(url, styles.link)) { append(label) }
}

private fun AnnotatedString.Builder.appendInline(text: String, styles: InlineStyles, links: MutableList<String>) {
    var i = 0
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) {
            append(plain.toString())
            plain.clear()
        }
    }
    while (i < text.length) {
        val c = text[i]
        val prev = if (i > 0) text[i - 1] else null
        if (c == '\\' && i + 1 < text.length && !text[i + 1].isLetterOrDigit()) {
            plain.append(text[i + 1])
            i += 2
            continue
        }
        if (c == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i + 1) {
                flush()
                withStyle(styles.code) { append(text.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        if (text.startsWith("**", i)) {
            val end = text.indexOf("**", i + 2)
            if (end > i + 2) {
                flush()
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(text.substring(i + 2, end), styles, links) }
                i = end + 2
                continue
            }
        }
        if (text.startsWith("__", i) && !isWordChar(prev)) {
            val end = text.indexOf("__", i + 2)
            if (end > i + 2) {
                flush()
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { appendInline(text.substring(i + 2, end), styles, links) }
                i = end + 2
                continue
            }
        }
        if (text.startsWith("~~", i)) {
            val end = text.indexOf("~~", i + 2)
            if (end > i + 2) {
                flush()
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(text.substring(i + 2, end)) }
                i = end + 2
                continue
            }
        }
        if ((c == '*' || c == '_') && !isWordChar(prev) && i + 1 < text.length && !text[i + 1].isWhitespace() && text[i + 1] != c) {
            val end = text.indexOf(c, i + 1)
            if (end > i + 1 && !text[end - 1].isWhitespace() && !isWordChar(text.getOrNull(end + 1))) {
                flush()
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(text.substring(i + 1, end), styles, links) }
                i = end + 1
                continue
            }
        }
        if (c == '[') {
            val close = text.indexOf("](", i + 1)
            val end = if (close > i) text.indexOf(')', close + 2) else -1
            if (close > i + 1 && end > close + 2) {
                val url = text.substring(close + 2, end).trim().removePrefix("<").removeSuffix(">")
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    flush()
                    appendLink(text.substring(i + 1, close), url, styles, links)
                    i = end + 1
                    continue
                }
            }
        }
        if (c == '<' && (text.startsWith("<https://", i) || text.startsWith("<http://", i))) {
            val end = text.indexOf('>', i + 1)
            if (end > i) {
                val url = text.substring(i + 1, end)
                if (url.none { it.isWhitespace() }) {
                    flush()
                    appendLink(url, url, styles, links)
                    i = end + 1
                    continue
                }
            }
        }
        if (text.startsWith("https://", i) || text.startsWith("http://", i)) {
            var end = i
            while (end < text.length && !text[end].isWhitespace() && text[end] != '<' && text[end] != '>') end++
            while (end > i && text[end - 1] in ".,;:!?)'\"") end--
            val url = text.substring(i, end)
            flush()
            appendLink(url, url, styles, links)
            i = end
            continue
        }
        plain.append(c)
        i++
    }
    flush()
}

@Composable
internal fun MarkdownText(
    document: MarkdownDocument,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        document.blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> Text(
                    text = block.text,
                    color = color,
                    style = MaterialTheme.typography.bodyMedium,
                )
                is MarkdownBlock.Code -> Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = block.code,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun rememberMarkdown(text: String): MarkdownDocument {
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val linkColor = PluviaTheme.colors.accentCyan
    return remember(text, codeBackground, linkColor) { parseMarkdown(text, codeBackground, linkColor) }
}
