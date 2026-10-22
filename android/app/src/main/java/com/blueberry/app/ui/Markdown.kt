package com.blueberry.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Блоки Markdown, которые умеет показывать приложение. */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Item(val marker: String, val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
}

object Markdown {
    fun parse(src: String): List<MdBlock> {
        val blocks = ArrayList<MdBlock>()
        val para = StringBuilder()
        fun flush() { if (para.isNotEmpty()) { blocks += MdBlock.Paragraph(para.toString().trimEnd()); para.clear() } }
        val lines = src.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.startsWith("```") -> { // незакрытый блок (стрим ещё идёт) тоже показывается как код
                    flush()
                    val lang = t.removePrefix("```").trim()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        if (code.isNotEmpty()) code.append('\n'); code.append(lines[i]); i++
                    }
                    blocks += MdBlock.Code(lang, code.toString())
                }
                t.isEmpty() -> flush()
                HEADING.matches(t) -> {
                    flush()
                    val m = HEADING.matchEntire(t)!!
                    blocks += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
                }
                BULLET.matches(line) -> {
                    flush(); blocks += MdBlock.Item("•", BULLET.matchEntire(line)!!.groupValues[2])
                }
                NUMBERED.matches(line) -> {
                    flush()
                    val m = NUMBERED.matchEntire(line)!!
                    blocks += MdBlock.Item(m.groupValues[2] + ".", m.groupValues[3])
                }
                else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
            }
            i++
        }
        flush()
        return blocks
    }

    // Инлайн-разметка: `код`, **жирный**, *курсив* и _курсив_.
    fun inline(text: String, codeBackground: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    } else { append(c); i++ }
                }
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(text.substring(i + 2, end), codeBackground)) }
                        i = end + 2
                    } else { append("**"); i += 2 }
                }
                (c == '*' || c == '_') && i + 1 < n && !text[i + 1].isWhitespace() -> {
                    val end = text.indexOf(c, i + 1)
                    if (end > i + 1 && !text[end - 1].isWhitespace()) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(text.substring(i + 1, end), codeBackground)) }
                        i = end + 1
                    } else { append(c); i++ }
                }
                else -> { append(c); i++ }
            }
        }
    }

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { Markdown.parse(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> Text(Markdown.inline(block.text, codeBg), style = MaterialTheme.typography.bodyLarge)
                is MdBlock.Heading -> Text(
                    Markdown.inline(block.text, codeBg),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    fontWeight = FontWeight.Bold,
                )
                is MdBlock.Item -> Row {
                    Text(block.marker, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyLarge)
                    Text(Markdown.inline(block.text, codeBg), style = MaterialTheme.typography.bodyLarge)
                }
                is MdBlock.Code -> CodeBlock(block)
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(block.lang.ifBlank { "код" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { clipboard.setText(AnnotatedString(block.code)) }) { Text("Копировать") }
        }
        Text(
            block.code,
            Modifier.horizontalScroll(rememberScrollState()),
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, softWrap = false,
        )
    }
}
