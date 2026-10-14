@file:OptIn(ExperimentalMaterial3Api::class)

package app.astra.audiotext.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.astra.audiotext.AppContainer
import app.astra.audiotext.BuildConfig
import app.astra.audiotext.R
import app.astra.audiotext.benchmark.BenchmarkRunner
import app.astra.audiotext.ui.AstraScaffold
import app.astra.audiotext.ui.Banner
import app.astra.audiotext.ui.InfoRow
import app.astra.audiotext.ui.SectionCard
import app.astra.audiotext.ui.screenPadding
import app.astra.core.models.ResourceAdvisor
import app.astra.core.text.DocumentImport
import app.astra.core.text.Glossary
import app.astra.core.transcript.AccuracyProfile
import app.astra.core.transcript.CancelSignal
import app.astra.engine.whisper.WhisperEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ Benchmark

@Composable
fun BenchmarkScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by container.library.items.collectAsState()
    val audio = items.filter { it.hasAudio }
    val references = remember { mutableStateMapOf<String, String>() }
    val languages = remember { mutableStateMapOf<String, String>() }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var profile by remember { mutableStateOf(AccuracyProfile.BALANCED) }
    var report by remember { mutableStateOf<BenchmarkRunner.Report?>(null) }
    var running by remember { mutableStateOf(false) }
    val progress by container.benchmark.progress.collectAsState()
    var signal by remember { mutableStateOf(CancelSignal()) }
    var refFor by remember { mutableStateOf<String?>(null) }

    val refPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = refFor ?: return@rememberLauncherForActivityResult
        refFor = null
        if (uri != null) scope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { references[id] = DocumentImport.import(it, DocumentImport.Kind.TXT).text }
            } catch (e: Exception) { container.notify(e.message ?: "") }
        }
    }

    AstraScaffold(container, stringResource(R.string.bench_title), onBack) { padding ->
        Column(Modifier.fillMaxSize().screenPadding(padding).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Banner(stringResource(R.string.bench_explain), warning = false)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AccuracyProfile.values().forEach { p -> FilterChip(selected = profile == p, onClick = { profile = p }, label = { Text(profileLabel(p)) }) }
            }
            if (audio.isEmpty()) Text(stringResource(R.string.translate_no_audio))
            audio.forEach { item ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = selected[item.id] == true, onCheckedChange = { selected[item.id] = it })
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                    }
                    if (selected[item.id] == true) {
                        OutlinedTextField(value = references[item.id] ?: "", onValueChange = { references[item.id] = it },
                            label = { Text(stringResource(R.string.bench_reference)) }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { refFor = item.id; refPicker.launch(arrayOf("text/plain")) }) { Text(stringResource(R.string.bench_load_reference)) }
                            listOf("auto", "hi", "en").forEach { code ->
                                FilterChip(selected = (languages[item.id] ?: "auto") == code, onClick = { languages[item.id] = code }, label = { Text(code) })
                            }
                        }
                    }
                }
            }
            val cases = audio.filter { selected[it.id] == true && !references[it.id].isNullOrBlank() }
                .map { BenchmarkRunner.Case(it.id, it.title, references[it.id]!!, languages[it.id] ?: "auto") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !running && cases.isNotEmpty(), onClick = {
                    running = true; report = null
                    val s = CancelSignal(); signal = s
                    scope.launch {
                        val threads = container.settings.value.threads.takeIf { it > 0 } ?: container.device.recommendedThreads()
                        report = container.benchmark.run(cases, profile, threads, s)
                        running = false
                    }
                }) { Text(stringResource(R.string.bench_run, cases.size)) }
                if (running) OutlinedButton(onClick = { signal.cancel() }) { Text(stringResource(R.string.action_cancel)) }
            }
            progress?.let { Text(it) }
            report?.let { r ->
                SectionCard(stringResource(R.string.bench_results)) {
                    InfoRow(stringResource(R.string.bench_model), r.modelId)
                    InfoRow(stringResource(R.string.bench_device), r.device)
                    InfoRow(stringResource(R.string.bench_normalization), r.normalization)
                    InfoRow("WER (corpus)", pct(r.corpusWer))
                    InfoRow("CER (corpus)", pct(r.corpusCer))
                    HorizontalDivider()
                    r.cases.forEach { c ->
                        Text(c.title, style = MaterialTheme.typography.titleSmall)
                        if (c.error != null) Text(c.error, color = MaterialTheme.colorScheme.error)
                        else {
                            InfoRow("WER", pct(c.wer?.rate ?: Double.NaN) + "  (S ${c.wer?.substitutions} · D ${c.wer?.deletions} · I ${c.wer?.insertions})")
                            InfoRow("CER", pct(c.cer?.rate ?: Double.NaN))
                            InfoRow(stringResource(R.string.bench_rtf), String.format(java.util.Locale.ROOT, "%.2f×", c.realTimeFactor))
                        }
                    }
                    Text(stringResource(R.string.bench_caveats), style = MaterialTheme.typography.bodySmall)
                    val json = r.toJson()
                    OutlinedButton(onClick = {
                        container.library.createNote("Benchmark " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(r.createdMillis)), json)
                        container.notify(context.getString(R.string.bench_saved))
                    }) { Text(stringResource(R.string.bench_save_report)) }
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

private fun pct(x: Double): String = if (x.isNaN()) "—" else String.format(java.util.Locale.ROOT, "%.1f %%", x * 100)

// ------------------------------------------------------------------ Glossary

@Composable
fun GlossaryScreen(container: AppContainer, onBack: () -> Unit) {
    val s by container.settings.state.collectAsState()
    var text by remember { mutableStateOf(s.glossary) }
    val parsed = remember(text) { Glossary.parse(text) }
    AstraScaffold(container, stringResource(R.string.glossary_title), onBack) { padding ->
        Column(Modifier.fillMaxSize().screenPadding(padding).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.glossary_explain), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(stringResource(R.string.glossary_entries)) },
                minLines = 8, maxLines = 20, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.glossary_count, parsed.entries.size), style = MaterialTheme.typography.bodySmall)
            parsed.initialPrompt()?.let { Text(stringResource(R.string.glossary_prompt_preview, it), style = MaterialTheme.typography.bodySmall) }
            ToggleRow(stringResource(R.string.glossary_use_prompt), stringResource(R.string.glossary_use_prompt_note), s.useGlossaryPrompt) { on ->
                container.settings.update { it.copy(useGlossaryPrompt = on) }
            }
            Button(onClick = { container.settings.update { it.copy(glossary = text) }; onBack() }) { Text(stringResource(R.string.action_save)) }
        }
    }
}

// ------------------------------------------------------------------ About

@Composable
fun AboutScreen(container: AppContainer, onBack: () -> Unit) {
    var engineInfo by remember { mutableStateOf("…") }
    androidx.compose.runtime.LaunchedEffect(Unit) { engineInfo = withContext(Dispatchers.Default) { container.engines.backendInfo() } }
    AstraScaffold(container, stringResource(R.string.about_title), onBack) { padding ->
        Column(Modifier.fillMaxSize().screenPadding(padding).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard {
                InfoRow(stringResource(R.string.about_version), BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
                InfoRow(stringResource(R.string.about_engine), "whisper.cpp v1.9.5 (MIT)")
                InfoRow(stringResource(R.string.about_cpu), engineInfo)
            }
            SectionCard(stringResource(R.string.about_principles)) { Text(stringResource(R.string.about_principles_body)) }
            SectionCard(stringResource(R.string.about_licenses)) { Text(stringResource(R.string.about_licenses_body), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
