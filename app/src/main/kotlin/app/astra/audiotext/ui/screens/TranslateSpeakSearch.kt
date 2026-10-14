@file:OptIn(ExperimentalMaterial3Api::class)

package app.astra.audiotext.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.astra.audiotext.AppContainer
import app.astra.audiotext.R
import app.astra.audiotext.engine.JobState
import app.astra.audiotext.ui.AstraScaffold
import app.astra.audiotext.ui.Banner
import app.astra.audiotext.ui.Screen
import app.astra.audiotext.ui.SectionCard
import app.astra.audiotext.ui.screenPadding
import app.astra.core.common.Language
import app.astra.core.text.SearchIndex
import app.astra.core.transcript.Transcript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ Translate

@Composable
fun TranslateScreen(container: AppContainer, onBack: () -> Unit) {
    val items by container.library.items.collectAsState()
    val audioItems = items.filter { it.hasAudio }
    var selectedId by remember { mutableStateOf(audioItems.firstOrNull()?.id) }
    var menu by remember { mutableStateOf(false) }
    val jobs by container.transcriptions.jobs.collectAsState()
    val job = selectedId?.let { jobs[it] }
    var translation by remember { mutableStateOf<Transcript?>(null) }
    var original by remember { mutableStateOf<Transcript?>(null) }

    LaunchedEffect(selectedId, job?.phase) {
        val id = selectedId ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            translation = container.transcriptions.readTranslation(id)
            original = container.library.readTranscript(id)
        }
    }

    AstraScaffold(container, stringResource(R.string.translate_title), onBack) { padding ->
        Column(Modifier.fillMaxSize().screenPadding(padding).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Banner(stringResource(R.string.translate_explain), warning = false)
            Banner(stringResource(R.string.translate_text_unavailable))
            if (audioItems.isEmpty()) {
                Text(stringResource(R.string.translate_no_audio))
                Button(onClick = { container.navigator.go(Screen.Record) }) { Text(stringResource(R.string.home_record)) }
            } else {
            Box {
                OutlinedButton(onClick = { menu = true }) {
                    Text(audioItems.firstOrNull { it.id == selectedId }?.title ?: stringResource(R.string.translate_pick))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    audioItems.forEach { i -> DropdownMenuItem(text = { Text(i.title) }, onClick = { selectedId = i.id; menu = false }) }
                }
            }
            val item = audioItems.firstOrNull { it.id == selectedId }
            if (item != null) {
                when {
                    job?.running == true -> SectionCard(stringResource(R.string.job_title)) {
                        Text("${job.percent}%")
                        androidx.compose.material3.LinearProgressIndicator(progress = { job.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { container.transcriptions.cancel(item.id) }) { Text(stringResource(R.string.action_cancel)) }
                    }
                    translation == null -> {
                        if (job?.phase == JobState.Phase.FAILED) Banner(job.message ?: "")
                        TranscribeOptionsCard(container, item, translate = true)
                    }
                    else -> {
                        val tr = translation!!
                        SectionCard(stringResource(R.string.translate_result)) {
                            Text(stringResource(R.string.warn_machine_translation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            tr.detectedLanguage?.let { Text(stringResource(R.string.translate_source_lang, Language.byCode(it).englishName) + " → English") }
                            HorizontalDivider()
                            val orig = original
                            if (orig != null) Text(stringResource(R.string.translate_side_by_side), style = MaterialTheme.typography.labelLarge)
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    tr.corrected.forEach { seg ->
                                        val o = orig?.corrected?.firstOrNull { it.startMs <= seg.startMs && seg.startMs < it.endMs }
                                        if (o != null) Text(o.text.trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(seg.text.trim(), style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val title = stringResource(R.string.translate_note_title, item.title)
                                OutlinedButton(onClick = {
                                    val note = container.library.createNote(title, tr.plainText())
                                    container.navigator.go(Screen.Item(note.id))
                                }) { Text(stringResource(R.string.tools_save_note)) }
                                OutlinedButton(onClick = { container.transcriptions.translationFile(item.id).delete(); translation = null }) {
                                    Text(stringResource(R.string.action_delete))
                                }
                            }
                        }
                    }
                }
            }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

// ------------------------------------------------------------------ Speak (TTS)

@Composable
fun SpeakScreen(container: AppContainer, initialText: String, onBack: () -> Unit) {
    val tts = container.tts
    val state by tts.state.collectAsState()
    var text by remember { mutableStateOf(initialText) }
    var voice by remember { mutableStateOf<String?>(null) }
    var rate by remember { mutableStateOf(1f) }
    var pitch by remember { mutableStateOf(1f) }
    var voiceMenu by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        tts.init()
        onDispose { tts.stop() }
    }
    LaunchedEffect(state.voices) { if (voice == null) voice = state.voices.firstOrNull()?.name }

    AstraScaffold(container, stringResource(R.string.speak_title), onBack) { padding ->
        Column(Modifier.fillMaxSize().screenPadding(padding).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(stringResource(R.string.speak_text)) },
                minLines = 5, maxLines = 14, modifier = Modifier.fillMaxWidth())
            if (!state.ready && state.error == null) Text(stringResource(R.string.speak_connecting))
            state.error?.let { Banner(it) }
            if (state.voices.isNotEmpty()) {
                Box {
                    OutlinedButton(onClick = { voiceMenu = true }) {
                        val v = state.voices.firstOrNull { it.name == voice }
                        Text(v?.let { "${it.locale.displayName} · ${it.name}" } ?: stringResource(R.string.speak_voice))
                    }
                    DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                        state.voices.forEach { v -> DropdownMenuItem(text = { Text("${v.locale.displayName} · ${v.name}") }, onClick = { voice = v.name; voiceMenu = false }) }
                    }
                }
            }
            Text(stringResource(R.string.speak_rate, String.format(java.util.Locale.ROOT, "%.2f", rate)))
            Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.5f..2f)
            Text(stringResource(R.string.speak_pitch, String.format(java.util.Locale.ROOT, "%.2f", pitch)))
            Slider(value = pitch, onValueChange = { pitch = it }, valueRange = 0.5f..1.5f)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled = state.ready && text.isNotBlank() && !state.speaking, onClick = { tts.speak(text, voice, rate, pitch) }) {
                    Text(stringResource(R.string.speak_play))
                }
                OutlinedButton(enabled = state.speaking, onClick = { tts.stop() }) { Text(stringResource(R.string.speak_stop)) }
            }
            SectionCard {
                Text(stringResource(R.string.speak_engine_note, state.engineName ?: "?"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ------------------------------------------------------------------ Search

@Composable
fun SearchScreen(container: AppContainer, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var range by remember { mutableStateOf(0) }
    var tag by remember { mutableStateOf<String?>(null) }
    val items by container.library.items.collectAsState()
    val ready by container.library.indexReady.collectAsState()
    val tags = items.flatMap { it.tags }.toSortedSet()
    val now = System.currentTimeMillis()
    val from = when (range) { 1 -> now - 7L * 86_400_000; 2 -> now - 30L * 86_400_000; 3 -> now - 365L * 86_400_000; else -> null }
    val hits = remember(query, range, tag, items, ready) {
        if (query.isBlank() && tag == null && from == null) emptyList()
        else container.library.searchIndex.search(SearchIndex.Query(query, fromMillis = from, tag = tag))
    }
    AstraScaffold(container, stringResource(R.string.search_title), onBack) { padding ->
        Column(Modifier.fillMaxSize().screenPadding(padding).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(stringResource(R.string.search_hint)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.search_syntax), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(R.string.range_all, R.string.range_week, R.string.range_month, R.string.range_year).forEachIndexed { i, r ->
                    FilterChip(selected = range == i, onClick = { range = i }, label = { Text(stringResource(r)) })
                }
                tags.forEach { t -> FilterChip(selected = tag == t, onClick = { tag = if (tag == t) null else t }, label = { Text("#$t") }) }
            }
            if (!ready) Text(stringResource(R.string.search_indexing))
            Text(stringResource(R.string.search_scope_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hits.isEmpty() && (query.isNotBlank() || tag != null)) Text(stringResource(R.string.search_no_results))
            hits.forEach { h ->
                Column(Modifier.fillMaxWidth().clickable { container.navigator.go(Screen.Item(h.docId)) }.padding(vertical = 8.dp)) {
                    Text(h.title, style = MaterialTheme.typography.titleMedium)
                    Text(h.snippet, style = MaterialTheme.typography.bodyMedium)
                    if (h.matchCount > 0) Text(stringResource(R.string.search_matches, h.matchCount), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider()
            }
        }
    }
}
