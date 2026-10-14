@file:OptIn(ExperimentalMaterial3Api::class)

package app.astra.audiotext.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.astra.audiotext.AppContainer
import app.astra.audiotext.R
import app.astra.audiotext.ui.AstraScaffold
import app.astra.audiotext.ui.Banner
import app.astra.audiotext.ui.InfoRow
import app.astra.audiotext.ui.ProvenanceTag
import app.astra.audiotext.ui.Screen
import app.astra.audiotext.ui.SectionCard
import app.astra.audiotext.ui.screenPadding
import app.astra.core.text.ActionItemExtractor
import app.astra.core.text.Classifier
import app.astra.core.text.ExtractiveSummarizer
import app.astra.core.text.Formatter
import app.astra.core.text.Glossary
import app.astra.core.text.Proofreader
import app.astra.core.text.Provenance
import app.astra.core.text.TextAnalysis
import app.astra.core.text.TextCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Tool { SUMMARY, ACTIONS, PROOFREAD, FORMAT, CLASSIFY, COMPARE, ANALYZE, GLOSSARY }

@Composable
fun TextToolsScreen(container: AppContainer, itemId: String?, onBack: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var tool by remember { mutableStateOf(Tool.SUMMARY) }
    var sourceMenu by remember { mutableStateOf(false) }
    val items by container.library.items.collectAsState()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(itemId) {
        if (itemId != null) input = withContext(Dispatchers.IO) { loadText(container, itemId) }
    }

    AstraScaffold(container, stringResource(R.string.tools_title), onBack) { padding ->
        Column(Modifier.fillMaxSize().screenPadding(padding).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { sourceMenu = true }) { Text(stringResource(R.string.tools_load_from_library)) }
                    DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                        items.filter { it.hasText || it.hasTranscript }.take(50).forEach { it2 ->
                            DropdownMenuItem(text = { Text(it2.title) }, onClick = {
                                sourceMenu = false
                                input = loadText(container, it2.id)
                            })
                        }
                    }
                }
                TextButton(onClick = { clipboard.getText()?.let { input = it.text } }) { Text(stringResource(R.string.tools_paste)) }
            }
            OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text(stringResource(R.string.tools_input)) },
                minLines = 5, maxLines = 12, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.tools_data_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tool.values().forEach { t ->
                    FilterChip(selected = tool == t, onClick = { tool = t }, label = { Text(toolLabel(t)) })
                }
            }
            if (tool == Tool.COMPARE) {
                OutlinedTextField(value = second, onValueChange = { second = it }, label = { Text(stringResource(R.string.tools_second_text)) },
                    minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth())
            }

            if (input.isBlank()) {
                Text(stringResource(R.string.tools_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                ToolOutput(container, tool, input, second, onReplaceInput = { input = it })
            }

            SectionCard(stringResource(R.string.tools_generative_title)) {
                Text(stringResource(R.string.tools_generative_body), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

private fun loadText(container: AppContainer, id: String): String =
    container.library.readTranscript(id)?.plainText() ?: container.library.readText(id).orEmpty()

@Composable
private fun toolLabel(t: Tool): String = when (t) {
    Tool.SUMMARY -> stringResource(R.string.tool_summary)
    Tool.ACTIONS -> stringResource(R.string.tool_actions)
    Tool.PROOFREAD -> stringResource(R.string.tool_proofread)
    Tool.FORMAT -> stringResource(R.string.tool_format)
    Tool.CLASSIFY -> stringResource(R.string.tool_classify)
    Tool.COMPARE -> stringResource(R.string.tool_compare)
    Tool.ANALYZE -> stringResource(R.string.tool_analyze)
    Tool.GLOSSARY -> stringResource(R.string.tool_glossary)
}

@Composable
private fun ToolOutput(container: AppContainer, tool: Tool, input: String, second: String, onReplaceInput: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val saveTitle = stringResource(R.string.tools_saved_title)
    @Composable
    fun Actions(result: String) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { clipboard.setText(AnnotatedString(result)) }, label = { Text(stringResource(R.string.action_copy)) })
            AssistChip(onClick = {
                val item = container.library.createNote(saveTitle, result)
                container.navigator.go(Screen.Item(item.id))
            }, label = { Text(stringResource(R.string.tools_save_note)) })
        }
    }
    when (tool) {
        Tool.SUMMARY -> {
            var count by remember { mutableStateOf(5) }
            val s = remember(input, count) { ExtractiveSummarizer.summarize(input, maxSentences = count) }
            SectionCard {
                Row { ProvenanceTag(Provenance.EXTRACTED) }
                Text(stringResource(R.string.tools_summary_note, s.sentences.size, s.totalSentences), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(3, 5, 8).forEach { n -> FilterChip(selected = count == n, onClick = { count = n }, label = { Text("$n") }) }
                }
                SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { s.sentences.forEach { Text("• " + it.text) } } }
                Actions(s.sentences.joinToString("\n") { "• " + it.text })
            }
        }
        Tool.ACTIONS -> {
            val list = remember(input) { ActionItemExtractor.extract(input) }
            SectionCard {
                Row { ProvenanceTag(Provenance.EXTRACTED) }
                Text(stringResource(R.string.tools_actions_note), style = MaterialTheme.typography.bodySmall)
                if (list.isEmpty()) Text(stringResource(R.string.tools_none_found))
                SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { list.forEach { Text("☐ " + it.text) } } }
                if (list.isNotEmpty()) Actions(list.joinToString("\n") { "☐ " + it.text })
            }
        }
        Tool.PROOFREAD -> {
            val issues = remember(input) { Proofreader.check(input) }
            SectionCard {
                Row { ProvenanceTag(Provenance.SUGGESTED) }
                if (issues.isEmpty()) Text(stringResource(R.string.tools_no_issues))
                issues.take(60).forEach { i ->
                    val excerpt = input.substring(i.start.coerceAtMost(input.length), i.end.coerceAtMost(input.length))
                    Text("• ${i.message}" + (if (excerpt.isNotBlank()) ": “${excerpt.take(40)}”" else "") + (i.replacement?.let { " → “$it”" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (issues.any { it.replacement != null }) {
                    val fixed = remember(input, issues) { Proofreader.applyAll(input, issues) }
                    Text(stringResource(R.string.tools_proof_preview), style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(fixed) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { onReplaceInput(fixed) }, label = { Text(stringResource(R.string.tools_accept_all)) })
                    }
                    Actions(fixed)
                }
            }
        }
        Tool.FORMAT -> {
            var mode by remember { mutableStateOf(0) }
            val out = remember(input, mode) {
                when (mode) {
                    0 -> Formatter.tidyWhitespace(input)
                    1 -> Formatter.paragraphs(input)
                    2 -> Formatter.bullets(input)
                    3 -> Formatter.numbered(input)
                    else -> Formatter.sentenceCase(input)
                }
            }
            SectionCard {
                Row { ProvenanceTag(Provenance.SUGGESTED) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(R.string.fmt_tidy, R.string.fmt_paragraphs, R.string.fmt_bullets, R.string.fmt_numbered, R.string.fmt_sentence_case).forEachIndexed { i, res ->
                        FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text(stringResource(res)) })
                    }
                }
                SelectionContainer { Text(out) }
                Actions(out)
            }
        }
        Tool.CLASSIFY -> {
            val scores = remember(input) { Classifier.classify(input) }
            SectionCard {
                Row { ProvenanceTag(Provenance.SUGGESTED) }
                Text(stringResource(R.string.tools_classify_note), style = MaterialTheme.typography.bodySmall)
                if (scores.isEmpty()) Text(stringResource(R.string.tools_none_found))
                scores.forEach { s -> InfoRow(s.category, "${s.score} · " + s.matched.joinToString(", ")) }
            }
        }
        Tool.COMPARE -> {
            val d = remember(input, second) { TextCompare.diff(input, second) }
            val added = MaterialTheme.colorScheme.tertiaryContainer
            val removed = MaterialTheme.colorScheme.errorContainer
            SectionCard {
                Row { ProvenanceTag(Provenance.EXTRACTED) }
                Text(stringResource(R.string.tools_similarity, (d.similarity * 100).toInt()))
                Text(buildAnnotatedString {
                    d.pieces.forEach { p ->
                        when (p.kind) {
                            TextCompare.Kind.SAME -> append(p.text + " ")
                            TextCompare.Kind.ADDED -> withStyle(SpanStyle(background = added)) { append(p.text) }.also { append(" ") }
                            TextCompare.Kind.REMOVED -> withStyle(SpanStyle(background = removed, textDecoration = TextDecoration.LineThrough)) { append(p.text) }.also { append(" ") }
                        }
                    }
                })
            }
        }
        Tool.ANALYZE -> {
            val s = remember(input) { TextAnalysis.analyze(input) }
            SectionCard {
                Row { ProvenanceTag(Provenance.EXTRACTED) }
                InfoRow(stringResource(R.string.stat_words), s.words.toString())
                InfoRow(stringResource(R.string.stat_sentences), s.sentences.toString())
                InfoRow(stringResource(R.string.stat_characters), s.characters.toString())
                InfoRow(stringResource(R.string.stat_avg_sentence), String.format(java.util.Locale.ROOT, "%.1f", s.avgWordsPerSentence))
                InfoRow(stringResource(R.string.stat_reading_time), String.format(java.util.Locale.ROOT, "%.1f min", s.readingMinutes))
                InfoRow(stringResource(R.string.stat_speaking_time), String.format(java.util.Locale.ROOT, "%.1f min", s.speakingMinutes))
                InfoRow(stringResource(R.string.stat_script), s.script)
                InfoRow(stringResource(R.string.stat_keywords), s.keywords.joinToString(", ") { "${it.first} (${it.second})" })
            }
        }
        Tool.GLOSSARY -> {
            val g = remember { Glossary.parse(container.settings.value.glossary) }
            val corrections = remember(input) { g.findCorrections(input) }
            SectionCard {
                Row { ProvenanceTag(Provenance.SUGGESTED) }
                if (g.entries.isEmpty()) {
                    Banner(stringResource(R.string.glossary_empty_hint), warning = false)
                    OutlinedButton(onClick = { container.navigator.go(Screen.Glossary) }) { Text(stringResource(R.string.glossary_title)) }
                } else {
                    if (corrections.isEmpty()) Text(stringResource(R.string.glossary_none_found))
                    corrections.forEach { c -> Text("“${c.original}” → “${c.replacement}”") }
                    if (corrections.isNotEmpty()) {
                        val fixed = g.apply(input, corrections)
                        AssistChip(onClick = { onReplaceInput(fixed) }, label = { Text(stringResource(R.string.tools_accept_all)) })
                        Actions(fixed)
                    }
                }
            }
        }
    }
}
