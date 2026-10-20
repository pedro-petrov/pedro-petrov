package com.pedropetrov.screenlens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Renders the small Markdown subset Claude is asked to use (headings, lists, bold, code). */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        var inCode = false
        val code = StringBuilder()
        for (line in markdown.lines()) {
            if (line.trimStart().startsWith("```")) {
                if (inCode) {
                    CodeBlock(code.toString().trimEnd())
                    code.clear()
                }
                inCode = !inCode
                continue
            }
            if (inCode) {
                code.appendLine(line)
                continue
            }
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#") -> Text(
                    inline(trimmed.trimStart('#').trim()),
                    style = if (trimmed.startsWith("###")) MaterialTheme.typography.titleSmall
                    else MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> Text(
                    inline("•  " + trimmed.drop(2)),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
                )
                trimmed.isEmpty() -> Text("", style = MaterialTheme.typography.bodySmall)
                else -> Text(inline(trimmed), style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (inCode && code.isNotEmpty()) CodeBlock(code.toString().trimEnd())
    }
}

@Composable
private fun CodeBlock(code: String) {
    Text(
        code,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(8.dp),
    )
}

/** Handles **bold** and `inline code`. */
private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end < 0) { append(text.substring(i)); break }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                i = end + 2
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end < 0) { append(text.substring(i)); break }
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(text.substring(i + 1, end)) }
                i = end + 1
            }
            else -> { append(text[i]); i++ }
        }
    }
}
