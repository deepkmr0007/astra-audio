@file:OptIn(ExperimentalMaterial3Api::class)

package app.astra.audiotext.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.astra.audiotext.AppContainer
import app.astra.audiotext.R
import app.astra.audiotext.engine.JobRequest
import app.astra.audiotext.engine.JobState
import app.astra.audiotext.storage.LibraryItem
import app.astra.audiotext.ui.AstraScaffold
import app.astra.audiotext.ui.Banner
import app.astra.audiotext.ui.ConfirmDialog
import app.astra.audiotext.ui.InfoRow
import app.astra.audiotext.ui.Screen
import app.astra.audiotext.ui.SectionCard
import app.astra.audiotext.ui.StateColors
import app.astra.audiotext.ui.TextInputDialog
import app.astra.core.common.Language
import app.astra.core.common.formatTimestamp
import app.astra.core.text.CleanTranscript
import app.astra.core.text.Glossary
import app.astra.core.transcript.AccuracyProfile
import app.astra.core.transcript.CorrectionRecord
import app.astra.core.transcript.Segment
import app.astra.core.transcript.Transcript
import app.astra.core.transcript.TranscriptStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ItemScreen(container: AppContainer, itemId: String, onBack: () -> Unit) {
    val items by container.library.items.collectAsState()
    val item = items.firstOrNull { it.id == itemId }
    val jobs by container.transcriptions.jobs.collectAsState()
    val job = jobs[itemId]
    val scope = rememberCoroutineScope()
    var transcript by remember(itemId) { mutableStateOf<Transcript?>(null) }
    var text by remember(itemId) { mutableStateOf<String?>(null) }
    var showExport by remember { mutableStateOf(false) }

    LaunchedEffect(itemId, item?.hasTranscript, job?.phase) {
        withContext(Dispatchers.IO) {
            transcript = container.library.readTranscript(itemId)
            text = container.library.readText(itemId)
        }
    }

    if (item == null) {
        AstraScaffold(container, stringResource(R.string.item_title), onBack) { p -> Text(stringResource(R.string.item_not_found), Modifier.padding(p).padding(16.dp)) }
        return
    }

    val save: (Transcript) -> Unit = { t ->
        transcript = t
        scope.launch(Dispatchers.IO) { container.library.saveTranscript(t) }
    }

    AstraScaffold(container, item.title, onBack, actions = {
        if (transcript != null || text != null) TextButton(onClick = { showExport = true }) { Text(stringResource(R.string.action_export)) }
    }) { padding ->
        TranscriptList(container, item, transcript, text, job, padding, save, onTextChanged = { t ->
            text = t
            scope.launch(Dispatchers.IO) { container.library.saveText(itemId, t) }
        })
    }

    if (showExport) ExportDialog(container, item, transcript, text) { showExport = false }
}

@Composable
private fun TranscriptList(
    container: AppContainer,
    item: LibraryItem,
    transcript: Transcript?,
    text: String?,
    job: JobState?,
    padding: PaddingValues,
    save: (Transcript) -> Unit,
    onTextChanged: (String) -> Unit,
) {
    val player = container.player
    val playerState by player.state.collectAsState()
    var position by remember { mutableStateOf(0L) }
    var layer by remember { mutableStateOf(Transcript.Layer.CORRECTED) }
    var cleanView by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var speakerIndex by remember { mutableStateOf<Int?>(null) }
    var glossaryPreview by remember { mutableStateOf<List<Pair<Int, Glossary.Correction>>?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmRetranscribe by remember { mutableStateOf(false) }
    var confirmDeleteTranscript by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(false) }
    var cleanBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (item.hasAudio && transcript == null) {
        LaunchedEffect(item.id) { container.engines.prewarm(container.scope) }
    }
    if (item.hasAudio) {
        DisposableEffect(item.id) {
            player.open(item.id)
            onDispose { player.pause() }
        }
        LaunchedEffect(playerState.playing) {
            while (playerState.playing) { position = player.positionMs(); delay(200) }
            position = player.positionMs()
        }
    }

    val segments: List<Segment> = when {
        job?.running == true -> job.partial
        transcript == null -> emptyList()
        layer == Transcript.Layer.RAW -> transcript.raw
        else -> transcript.corrected
    }
    val currentIndex = if (playerState.playing || position > 0) segments.indexOfLast { it.startMs <= position && position < it.endMs + 300 } else -1

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (item.hasAudio) item { PlayerCard(container, item, position) { position = it } }

        if (item.qualityIssues.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { item.qualityIssues.forEach { Banner(qualityText(it)) } }
        }

        if (text != null) item {
            SectionCard(stringResource(R.string.item_text)) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { editText = true }, label = { Text(stringResource(R.string.action_edit)) })
                    AssistChip(onClick = { container.navigator.go(Screen.TextTools(item.id)) }, label = { Text(stringResource(R.string.home_text_tools)) })
                    AssistChip(onClick = { container.navigator.go(Screen.Speak(text)) }, label = { Text(stringResource(R.string.home_speak)) })
                }
            }
        }

        when {
            job?.running == true -> item { JobCard(container, job) }
            item.hasAudio && transcript == null -> item {
                if (job != null && (job.phase == JobState.Phase.FAILED || job.phase == JobState.Phase.CANCELLED)) {
                    Banner(job.message ?: "")
                    Spacer(Modifier.size(8.dp))
                }
                TranscribeOptionsCard(container, item, translate = false)
            }
        }

        if (transcript != null && job?.running != true) {
            item {
                SectionCard {
                    transcript.warnings.forEach { w -> Banner(warningText(w), warning = !(w.startsWith("speed:") || w.startsWith("live:"))) }
                    transcript.engine?.let { e ->
                        Text(stringResource(R.string.item_engine_line, e.modelId, e.profile, if (e.ranOffline) stringResource(R.string.item_on_device) else "?"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = layer == Transcript.Layer.CORRECTED, onClick = { layer = Transcript.Layer.CORRECTED }, label = { Text(stringResource(R.string.item_layer_corrected)) })
                        FilterChip(selected = layer == Transcript.Layer.RAW, onClick = { layer = Transcript.Layer.RAW }, label = { Text(stringResource(R.string.item_layer_raw)) })
                        FilterChip(selected = !cleanView, onClick = { cleanView = false }, label = { Text(stringResource(R.string.item_view_verbatim)) })
                        FilterChip(selected = cleanView, onClick = { cleanView = true }, label = { Text(stringResource(R.string.item_view_clean)) })
                    }
                    Text(stringResource(if (layer == Transcript.Layer.RAW) R.string.item_raw_note else R.string.item_corrected_note),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {
                            val g = Glossary.parse(container.settings.value.glossary)
                            glossaryPreview = transcript.corrected.flatMapIndexed { i, s -> g.findCorrections(s.text).map { i to it } }
                        }, label = { Text(stringResource(R.string.item_apply_glossary)) })
                        AssistChip(onClick = { container.navigator.go(Screen.TextTools(item.id)) }, label = { Text(stringResource(R.string.home_text_tools)) })
                        AssistChip(onClick = { container.navigator.go(Screen.Speak(transcript.plainText())) }, label = { Text(stringResource(R.string.home_speak)) })
                        if (item.hasAudio && !item.hasCleanAudio) AssistChip(enabled = !cleanBusy, onClick = {
                            cleanBusy = true
                            scope.launch(Dispatchers.IO) {
                                try { container.decoder.createCleanCopy(item.id) { false } } catch (e: Exception) { container.notify(e.message ?: "") }
                                cleanBusy = false
                            }
                        }, label = { Text(stringResource(R.string.item_make_clean_copy)) })
                        AssistChip(onClick = { confirmRetranscribe = true }, label = { Text(stringResource(R.string.item_retranscribe)) })
                        AssistChip(onClick = { confirmReset = true }, label = { Text(stringResource(R.string.item_reset_raw)) })
                        AssistChip(onClick = { confirmDeleteTranscript = true }, label = { Text(stringResource(R.string.item_delete_transcript)) })
                    }
                    if (cleanBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (segments.isEmpty()) Text(stringResource(R.string.item_no_speech))
                }
            }
        }

        if (segments.isNotEmpty()) {
            itemsIndexed(segments) { i, s ->
                SegmentRow(
                    container = container,
                    transcript = transcript,
                    segment = s,
                    clean = cleanView,
                    current = i == currentIndex,
                    editable = transcript != null && job?.running != true && layer == Transcript.Layer.CORRECTED,
                    hasAudio = item.hasAudio,
                    onSeek = { player.playFrom(s.startMs); position = s.startMs },
                    onEdit = { editIndex = i },
                    onSpeaker = { speakerIndex = i },
                )
            }
        }
        item { Spacer(Modifier.size(32.dp)) }
    }

    val t = transcript
    editIndex?.let { i ->
        if (t != null && i in t.corrected.indices) {
            TextInputDialog(stringResource(R.string.item_edit_segment), t.corrected[i].text.trim(), stringResource(R.string.item_segment_text), singleLine = false,
                onConfirm = { new -> save(t.withEditedSegment(i, new, System.currentTimeMillis())) }, onDismiss = { editIndex = null })
        }
    }
    speakerIndex?.let { i ->
        if (t != null && i in t.corrected.indices) {
            val old = t.corrected[i].speaker ?: ""
            TextInputDialog(stringResource(R.string.item_speaker_rename), old, stringResource(R.string.item_speaker_label),
                onConfirm = { new ->
                    val name = new.trim().ifEmpty { null }
                    save(if (old.isNotEmpty() && name != null) t.renameSpeaker(old, name) else t.withSpeaker(i, name))
                }, onDismiss = { speakerIndex = null })
        }
    }
    glossaryPreview?.let { list ->
        GlossaryPreviewDialog(list, t, onApply = {
            if (t != null) {
                val now = System.currentTimeMillis()
                val bySeg = list.groupBy({ it.first }, { it.second })
                val g = Glossary.parse(container.settings.value.glossary)
                var corrected = t.corrected
                val records = ArrayList<CorrectionRecord>()
                for ((idx, cs) in bySeg) {
                    val before = corrected[idx].text
                    val after = g.apply(before, cs)
                    corrected = corrected.toMutableList().also { it[idx] = it[idx].copy(text = after) }
                    records.add(CorrectionRecord(idx, "glossary", before, after, now))
                }
                save(t.copy(corrected = corrected, corrections = t.corrections + records))
            }
        }, onDismiss = { glossaryPreview = null })
    }
    if (confirmReset && t != null) ConfirmDialog(stringResource(R.string.item_reset_raw), stringResource(R.string.item_reset_body), stringResource(R.string.action_reset),
        onConfirm = { save(t.resetToRaw(System.currentTimeMillis())) }, onDismiss = { confirmReset = false }, destructive = true)
    if (confirmRetranscribe) ConfirmDialog(stringResource(R.string.item_retranscribe), stringResource(R.string.item_retranscribe_body), stringResource(R.string.action_continue),
        onConfirm = { container.library.deleteTranscript(item.id) }, onDismiss = { confirmRetranscribe = false }, destructive = true)
    if (confirmDeleteTranscript) ConfirmDialog(stringResource(R.string.item_delete_transcript), stringResource(R.string.item_delete_transcript_body), stringResource(R.string.action_delete),
        onConfirm = { container.library.deleteTranscript(item.id) }, onDismiss = { confirmDeleteTranscript = false }, destructive = true)
    if (editText && text != null) TextInputDialog(stringResource(R.string.action_edit), text, stringResource(R.string.item_text), singleLine = false,
        onConfirm = onTextChanged, onDismiss = { editText = false })
}

@Composable
private fun SegmentRow(
    container: AppContainer,
    transcript: Transcript?,
    segment: Segment,
    clean: Boolean,
    current: Boolean,
    editable: Boolean,
    hasAudio: Boolean,
    onSeek: () -> Unit,
    onEdit: () -> Unit,
    onSpeaker: () -> Unit,
) {
    val uncertain = segment.isUncertain()
    val bg = when {
        current -> MaterialTheme.colorScheme.primaryContainer
        uncertain -> StateColors.uncertain
        else -> MaterialTheme.colorScheme.surface
    }
    val shown = if (clean) CleanTranscript.clean(segment.text).first else segment.text.trim()
    val lowConfidence = stringResource(R.string.item_low_confidence)
    Surface(color = bg, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()
        .semantics { if (uncertain) contentDescription = "$lowConfidence. $shown" }) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTimestamp(segment.startMs).substring(0, 8), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(enabled = hasAudio, onClick = onSeek).padding(end = 8.dp))
                val speaker = segment.speaker?.let { transcript?.speakerNames?.get(it) ?: it }
                if (speaker != null || editable) {
                    Text(speaker ?: stringResource(R.string.item_add_speaker), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(enabled = editable, onClick = onSpeaker).padding(end = 8.dp))
                }
                Spacer(Modifier.weight(1f))
                if (uncertain) {
                    Text(lowConfidence, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                    if (hasAudio) TextButton(onClick = onSeek) { Text(stringResource(R.string.item_play_part)) }
                }
            }
            Text(shown, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clickable(enabled = editable, onClick = onEdit))
            if (segment.speakerTurnNext) Text(stringResource(R.string.item_speaker_change), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlayerCard(container: AppContainer, item: LibraryItem, position: Long, onPosition: (Long) -> Unit) {
    val player = container.player
    val st by player.state.collectAsState()
    val waveform = remember(item.id) { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(item.id, item.durationMs) {
        waveform.value = withContext(Dispatchers.IO) {
            try {
                val f = container.library.pcmCacheFile(item.id)
                if (!f.exists()) null else {
                    app.astra.audiotext.audio.PcmCache(f, container.library).peaks(120)
                }
            } catch (e: Exception) { null }
        }
    }
    SectionCard {
        st.error?.let { Banner(it) }
        val duration = st.durationMs.coerceAtLeast(1)
        Waveform(waveform.value, position.toFloat() / duration) { frac ->
            val ms = (frac * duration).toLong(); player.seekTo(ms); onPosition(ms)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatTimestamp(position).substring(0, 8) + " / " + formatTimestamp(st.durationMs).substring(0, 8), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { sp ->
                Text("${sp}×", style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (st.speed == sp) FontWeight.Bold else FontWeight.Normal,
                    color = if (st.speed == sp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { player.setSpeed(sp) }.padding(horizontal = 6.dp, vertical = 4.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { player.toggle() }) { Text(if (st.playing) stringResource(R.string.player_pause) else stringResource(R.string.player_play)) }
            OutlinedButton(onClick = { val ms = (position - 5000).coerceAtLeast(0); player.seekTo(ms); onPosition(ms) }) { Text("−5 s") }
            OutlinedButton(onClick = { val ms = (position + 5000).coerceAtMost(st.durationMs); player.seekTo(ms); onPosition(ms) }) { Text("+5 s") }
            if (item.hasCleanAudio) {
                FilterChip(selected = st.clean, onClick = { player.open(item.id, clean = !st.clean) }, label = { Text(stringResource(R.string.player_clean_copy)) })
            }
        }
    }
}

@Composable
private fun Waveform(peaks: FloatArray?, progress: Float, onSeek: (Float) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outline
    val seek by androidx.compose.runtime.rememberUpdatedState(onSeek)
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxWidth().height(56.dp).pointerInput(Unit) {
            detectTapGestures { off -> seek((off.x / size.width.toFloat()).coerceIn(0f, 1f)) }
        },
    ) {
        val n = peaks?.size ?: 60
        val w = size.width / n
        val maxPeak = peaks?.maxOrNull()?.coerceAtLeast(0.01f) ?: 1f
        for (i in 0 until n) {
            val p = peaks?.get(i)?.div(maxPeak) ?: 0.15f
            val h = (p.coerceIn(0.04f, 1f)) * size.height
            val x = i * w + w / 2
            drawLine(
                color = if (i.toFloat() / n <= progress) primary else muted,
                start = androidx.compose.ui.geometry.Offset(x, (size.height - h) / 2),
                end = androidx.compose.ui.geometry.Offset(x, (size.height + h) / 2),
                strokeWidth = (w * 0.6f).coerceAtLeast(1f),
            )
        }
    }
}

@Composable
private fun JobCard(container: AppContainer, job: JobState) {
    SectionCard(stringResource(R.string.job_title)) {
        val phase = when (job.phase) {
            JobState.Phase.PREPARING -> stringResource(R.string.job_preparing)
            JobState.Phase.DECODING -> stringResource(R.string.job_decoding)
            JobState.Phase.TRANSCRIBING -> stringResource(R.string.job_transcribing, job.chunk.coerceAtLeast(1), job.chunks.coerceAtLeast(1))
            else -> ""
        }
        Text(phase)
        LinearProgressIndicator(progress = { job.percent / 100f }, modifier = Modifier.fillMaxWidth())
        Text("${job.percent}%", style = MaterialTheme.typography.labelMedium)
        Text(stringResource(R.string.job_local_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { container.transcriptions.cancel(job.itemId) }) { Text(stringResource(R.string.action_cancel)) }
    }
}

/** Shared by the item screen and the Translate screen. */
@Composable
fun TranscribeOptionsCard(container: AppContainer, item: LibraryItem, translate: Boolean) {
    val settings by container.settings.state.collectAsState()
    val active = remember(settings.activeModelId) { container.engines.activeModel() }
    var language by remember { mutableStateOf(settings.defaultLanguage) }
    var profile by remember { mutableStateOf(settings.profile) }
    var style by remember { mutableStateOf(settings.style) }
    var turns by remember { mutableStateOf(false) }
    var glossary by remember { mutableStateOf(settings.glossary.isNotBlank()) }
    var clean by remember { mutableStateOf(false) }
    var skipSilence by remember { mutableStateOf(true) }
    var langMenu by remember { mutableStateOf(false) }
    val warning = remember { container.device.heavyWorkWarning() }

    SectionCard(if (translate) stringResource(R.string.translate_run_title) else stringResource(R.string.transcribe_title)) {
        if (active == null) {
            Banner(stringResource(R.string.home_no_model_body))
            Button(onClick = { container.navigator.go(Screen.Models) }) { Text(stringResource(R.string.home_open_models)) }
        } else {
        val (model, spec) = active
        InfoRow(stringResource(R.string.transcribe_model), spec.displayName)
        InfoRow(stringResource(R.string.transcribe_runs), stringResource(R.string.transcribe_runs_local))
        if (translate && !spec.translateToEnglish) Banner(stringResource(R.string.translate_model_cannot))

        Text(stringResource(R.string.transcribe_language), style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { langMenu = true }) {
                val l = Language.byCode(language)
                Text(if (l.code == "auto") stringResource(R.string.lang_auto) else "${l.englishName} · ${l.nativeName}")
            }
            androidx.compose.material3.DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                Language.COMMON.filter { spec.multilingual || it.code == "en" || it.code == "auto" }.forEach { l ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(if (l.code == "auto") stringResource(R.string.lang_auto) else "${l.englishName} · ${l.nativeName}") },
                        onClick = { language = l.code; langMenu = false })
                }
            }
        }
        if (language == "auto") Text(stringResource(R.string.lang_auto_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text(stringResource(R.string.transcribe_profile), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AccuracyProfile.values().forEach { p ->
                FilterChip(selected = profile == p, onClick = { profile = p }, label = { Text(profileLabel(p)) })
            }
        }
        Text(profileTradeoff(profile), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (!translate) {
            Text(stringResource(R.string.transcribe_style), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = style == TranscriptStyle.STANDARD, onClick = { style = TranscriptStyle.STANDARD }, label = { Text(stringResource(R.string.style_standard)) })
                FilterChip(selected = style == TranscriptStyle.VERBATIM, onClick = { style = TranscriptStyle.VERBATIM }, label = { Text(stringResource(R.string.style_verbatim)) })
            }
            Text(stringResource(R.string.style_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ToggleRow(stringResource(R.string.transcribe_speaker_turns), if (spec.speakerTurns) stringResource(R.string.transcribe_speaker_turns_note) else stringResource(R.string.transcribe_speaker_turns_unsupported),
            turns && spec.speakerTurns, enabled = spec.speakerTurns) { turns = it }
        ToggleRow(stringResource(R.string.transcribe_use_glossary), stringResource(R.string.transcribe_use_glossary_note), glossary, enabled = settings.glossary.isNotBlank()) { glossary = it }
        ToggleRow(stringResource(R.string.transcribe_skip_silence), stringResource(R.string.transcribe_skip_silence_note), skipSilence) { skipSilence = it }
        if (item.hasCleanAudio) ToggleRow(stringResource(R.string.transcribe_use_clean), stringResource(R.string.transcribe_use_clean_note), clean) { clean = it }
        warning?.let { Banner(it) }
        Button(
            enabled = !translate || spec.translateToEnglish,
            onClick = {
                container.transcriptions.start(JobRequest(item.id, language, translate, profile, style, turns && spec.speakerTurns, glossary, clean, skipSilence))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (translate) stringResource(R.string.translate_start) else stringResource(R.string.transcribe_start)) }
        Text(stringResource(R.string.transcribe_model_sha, model.sha256.take(12)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ToggleRow(title: String, note: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun profileLabel(p: AccuracyProfile): String = when (p) {
    AccuracyProfile.FAST -> stringResource(R.string.profile_fast)
    AccuracyProfile.BALANCED -> stringResource(R.string.profile_balanced)
    AccuracyProfile.HIGHEST -> stringResource(R.string.profile_highest)
}

@Composable
fun profileTradeoff(p: AccuracyProfile): String = when (p) {
    AccuracyProfile.FAST -> stringResource(R.string.profile_fast_note)
    AccuracyProfile.BALANCED -> stringResource(R.string.profile_balanced_note)
    AccuracyProfile.HIGHEST -> stringResource(R.string.profile_highest_note)
}

@Composable
fun qualityText(key: String): String = when (key) {
    "CLIPPED" -> stringResource(R.string.quality_clipped)
    "VERY_QUIET" -> stringResource(R.string.quality_quiet)
    "MOSTLY_SILENT" -> stringResource(R.string.quality_silent)
    "TOO_SHORT" -> stringResource(R.string.quality_short)
    "NOISY" -> stringResource(R.string.quality_noisy)
    else -> key
}

@Composable
fun warningText(key: String): String = when {
    key.startsWith("quality:") -> qualityText(key.removePrefix("quality:"))
    key.startsWith("language:auto-detected:") -> {
        val code = key.substringAfterLast(':')
        stringResource(R.string.warn_auto_language, Language.byCode(code).englishName)
    }
    key == "translation:machine" -> stringResource(R.string.warn_machine_translation)
    key == "speakers:turns-only" -> stringResource(R.string.warn_speaker_turns)
    key == "empty:no-speech" -> stringResource(R.string.item_no_speech)
    key.startsWith("speed:") -> {
        val parts = key.split(':')
        val audio = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val took = parts.getOrNull(2)?.toLongOrNull()?.coerceAtLeast(1) ?: 1L
        stringResource(R.string.warn_speed, app.astra.core.common.formatDuration(audio), app.astra.core.common.formatDuration(took),
            String.format(java.util.Locale.ROOT, "%.1f", audio.toDouble() / took))
    }
    key.startsWith("live:") -> stringResource(R.string.warn_live)
    else -> key
}

@Composable
private fun GlossaryPreviewDialog(list: List<Pair<Int, Glossary.Correction>>, t: Transcript?, onApply: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.item_apply_glossary)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (list.isEmpty()) Text(stringResource(R.string.glossary_none_found))
                list.take(100).forEach { (i, c) -> Text("${formatTimestamp(t?.corrected?.getOrNull(i)?.startMs ?: 0).substring(0, 8)}  “${c.original}” → “${c.replacement}”") }
                Text(stringResource(R.string.glossary_preview_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = list.isNotEmpty(), onClick = { onApply(); onDismiss() }) { Text(stringResource(R.string.action_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
