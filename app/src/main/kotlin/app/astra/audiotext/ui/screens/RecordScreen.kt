@file:OptIn(ExperimentalMaterial3Api::class)

package app.astra.audiotext.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.astra.audiotext.AppContainer
import app.astra.audiotext.R
import app.astra.audiotext.audio.Recorder
import app.astra.audiotext.ui.AstraScaffold
import app.astra.audiotext.ui.Banner
import app.astra.audiotext.ui.ConfirmDialog
import app.astra.audiotext.ui.Screen
import app.astra.audiotext.ui.SectionCard
import app.astra.audiotext.ui.StateColors
import app.astra.audiotext.ui.screenPadding
import app.astra.core.common.formatDuration
import kotlinx.coroutines.launch

@Composable
fun RecordScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        denied = !granted
        if (granted) pendingAction?.invoke()
        pendingAction = null
    }
    var showRationale by remember { mutableStateOf(false) }
    val withMic: (() -> Unit) -> Unit = { action ->
        if (hasPermission) action() else { pendingAction = action; showRationale = true }
    }
    var tab by remember { mutableStateOf(0) }

    AstraScaffold(container, stringResource(R.string.record_title), onBack) { padding ->
        Column(Modifier.fillMaxSize().screenPadding(padding).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.record_tab_record)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.record_tab_dictate)) })
            }
            if (denied) Banner(stringResource(R.string.record_permission_denied))
            if (tab == 0) RecordPanel(container, withMic) else DictationPanel(container, withMic)
        }
    }

    if (showRationale) {
        ConfirmDialog(
            title = stringResource(R.string.record_permission_title),
            text = stringResource(R.string.record_permission_body),
            confirmLabel = stringResource(R.string.action_continue),
            onConfirm = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            onDismiss = { showRationale = false },
        )
    }
}

@Composable
private fun RecordPanel(container: AppContainer, withMic: (() -> Unit) -> Unit) {
    val state by container.recorder.state.collectAsState()
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }
    val defaultTitle = stringResource(R.string.record_default_title)

    val recording = state.phase == Recorder.Phase.RECORDING
    val paused = state.phase == Recorder.Phase.PAUSED
    val active = recording || paused
    val settings by container.settings.state.collectAsState()
    val live by container.live.state.collectAsState()
    val liveAvailable = remember { container.live.available() }
    // Load the speech model while the user gets ready, so the first words are transcribed at once.
    LaunchedEffect(Unit) { if (settings.liveTranscribe) container.engines.prewarm(container.scope) }

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(if (recording) StateColors.recording else MaterialTheme.colorScheme.outline))
            Spacer(Modifier.size(8.dp))
            val status = when (state.phase) {
                Recorder.Phase.IDLE -> stringResource(R.string.record_state_idle)
                Recorder.Phase.RECORDING -> stringResource(R.string.record_state_recording)
                Recorder.Phase.PAUSED -> if (state.autoPaused) stringResource(R.string.record_state_autopaused) else stringResource(R.string.record_state_paused)
                Recorder.Phase.SAVING -> stringResource(R.string.record_state_saving)
            }
            Text(status, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Text(formatDuration(state.elapsedMs), style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.semantics { contentDescription = formatDuration(state.elapsedMs) })
        LinearProgressIndicator(progress = { state.level.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp))
        Text(stringResource(R.string.record_mic_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    state.error?.let { Banner(it) }

    if (!active && liveAvailable) {
        ToggleRow(stringResource(R.string.live_toggle), stringResource(R.string.live_toggle_note), settings.liveTranscribe) { on ->
            container.settings.update { it.copy(liveTranscribe = on) }
        }
    }
    if (live.active || live.finishing) LiveTranscriptCard(live, state.elapsedMs)
    live.error?.let { Banner(it) }

    if (active) {
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.record_name_label)) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        when {
            !active -> Button(
                onClick = {
                    withMic {
                        container.recorder.clearError()
                        if (container.recorder.start() && settings.liveTranscribe && liveAvailable) container.live.start()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = StateColors.recording),
                modifier = Modifier.weight(1f).height(56.dp),
            ) { Text(stringResource(R.string.record_start)) }
            else -> {
                OutlinedButton(onClick = { if (recording) container.recorder.pause() else container.recorder.resume() }, modifier = Modifier.weight(1f).height(56.dp)) {
                    Text(if (recording) stringResource(R.string.record_pause) else stringResource(R.string.record_resume))
                }
                Button(enabled = !live.finishing, onClick = {
                    scope.launch {
                        val autoTitle = title.isBlank()
                        val item = container.recorder.stop(title.ifBlank { defaultTitle })
                        title = ""
                        if (item != null) {
                            if (live.active) container.live.finish(item, autoTitle) else container.live.cancel()
                            container.navigator.replace(Screen.Item(item.id))
                        } else container.live.cancel()
                    }
                }, modifier = Modifier.weight(1f).height(56.dp)) { Text(stringResource(R.string.record_stop_save)) }
            }
        }
    }
    if (active) {
        TextButton(onClick = { confirmDiscard = true }) { Text(stringResource(R.string.record_discard), color = MaterialTheme.colorScheme.error) }
    }
    if (confirmDiscard) {
        ConfirmDialog(stringResource(R.string.record_discard_title), stringResource(R.string.record_discard_body), stringResource(R.string.action_delete),
            onConfirm = { scope.launch { container.live.cancel(); container.recorder.cancel() } }, onDismiss = { confirmDiscard = false }, destructive = true)
    }
}

@Composable
private fun LiveTranscriptCard(live: app.astra.audiotext.engine.LiveTranscriber.State, recordedMs: Long) {
    SectionCard(stringResource(R.string.live_title)) {
        val lag = (recordedMs - live.transcribedUntilMs).coerceAtLeast(0)
        Text(
            when {
                live.finishing -> stringResource(R.string.live_finishing)
                live.queuedWindows > 1 -> stringResource(R.string.live_catching_up, formatDuration(lag))
                else -> stringResource(R.string.live_status, formatDuration(live.transcribedUntilMs))
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (live.finishing) LinearProgressIndicator(Modifier.fillMaxWidth())
        val text = live.segments.takeLast(12).joinToString(" ") { it.text.trim() }
        Text(text.ifBlank { stringResource(R.string.live_waiting) }, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun DictationPanel(container: AppContainer, withMic: (() -> Unit) -> Unit) {
    val dictation = container.dictation
    val state by dictation.state.collectAsState()
    val unavailable = remember { dictation.unavailableReason() }
    var lang by remember { mutableStateOf("hi-IN") }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.listening) {
        while (state.listening) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(500) }
    }

    Text(stringResource(R.string.dictate_explain), style = MaterialTheme.typography.bodyMedium)
    if (unavailable != null) Banner(unavailable)
    else DictationControls(container, state, lang, now, withMic) { lang = it }
}

@Composable
private fun DictationControls(
    container: AppContainer,
    state: app.astra.audiotext.engine.OnDeviceDictation.State,
    lang: String,
    now: Long,
    withMic: (() -> Unit) -> Unit,
    onLang: (String) -> Unit,
) {
    val dictation = container.dictation

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("hi-IN" to "हिन्दी", "en-IN" to "English (India)", "en-US" to "English (US)").forEach { (tag, label) ->
            androidx.compose.material3.FilterChip(selected = lang == tag, onClick = { if (!state.listening) onLang(tag) }, label = { Text(label) })
        }
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(if (state.listening) StateColors.recording else MaterialTheme.colorScheme.outline))
            Spacer(Modifier.size(8.dp))
            Text(if (state.listening) stringResource(R.string.dictate_listening) else stringResource(R.string.record_state_idle), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            if (state.listening) Text(formatDuration(now - state.startedMillis))
        }
        LinearProgressIndicator(progress = { state.level }, modifier = Modifier.fillMaxWidth())
        Text((state.finalText + " " + state.partial).trim().ifEmpty { stringResource(R.string.dictate_placeholder) },
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    state.error?.let { Banner(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!state.listening) {
            Button(onClick = { withMic { dictation.start(lang) } }, colors = ButtonDefaults.buttonColors(containerColor = StateColors.recording),
                modifier = Modifier.weight(1f)) { Text(stringResource(R.string.dictate_start)) }
        } else {
            Button(onClick = { dictation.stop() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.dictate_stop)) }
        }
    }
    if (!state.listening && state.finalText.isNotBlank()) {
        val noteTitle = stringResource(R.string.dictate_note_title)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                val item = container.library.createNote(noteTitle, state.finalText)
                dictation.reset()
                container.navigator.replace(Screen.Item(item.id))
            }) { Text(stringResource(R.string.dictate_save_note)) }
            TextButton(onClick = { dictation.reset() }) { Text(stringResource(R.string.action_discard), color = MaterialTheme.colorScheme.error) }
        }
    }
}
