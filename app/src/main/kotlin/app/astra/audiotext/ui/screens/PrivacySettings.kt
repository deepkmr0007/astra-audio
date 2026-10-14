@file:OptIn(ExperimentalMaterial3Api::class)

package app.astra.audiotext.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.astra.audiotext.AppContainer
import app.astra.audiotext.MainActivity
import app.astra.audiotext.R
import app.astra.audiotext.settings.ThemeMode
import app.astra.audiotext.settings.UiLanguage
import app.astra.audiotext.ui.AstraScaffold
import app.astra.audiotext.ui.Banner
import app.astra.audiotext.ui.ConfirmDialog
import app.astra.audiotext.ui.InfoRow
import app.astra.audiotext.ui.Screen
import app.astra.audiotext.ui.SectionCard
import app.astra.audiotext.ui.TextInputDialog
import app.astra.audiotext.ui.screenPadding
import app.astra.core.common.formatBytes
import app.astra.core.models.ModelRegistry
import app.astra.core.privacy.NetworkStatus
import app.astra.core.transcript.AccuracyProfile
import app.astra.core.transcript.TranscriptStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

// ------------------------------------------------------------------ Privacy dashboard

@Composable
fun PrivacyScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val strict by container.gateway.strictOffline.collectAsState()
    val status by container.gateway.status.collectAsState()
    val last by container.gateway.lastOutcome.collectAsState()
    val events by container.audit.events.collectAsState()
    val items by container.library.items.collectAsState()
    val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val active = remember(items) { container.engines.activeModel() }
    var storage by remember { mutableStateOf<Triple<Long, Long, Long>?>(null) }
    var protection by remember { mutableStateOf("…") }
    androidx.compose.runtime.LaunchedEffect(items) {
        withContext(Dispatchers.IO) {
            container.audit.load()
            val models = java.io.File(container.appContext.filesDir, "models").walkTopDown().filter { it.isFile }.sumOf { it.length() }
            storage = Triple(container.library.storageBytes(), models, container.library.cacheBytes())
            protection = container.keys.describeProtection()
        }
    }
    val lastNetwork = last ?: events.lastOrNull { it.type.name.startsWith("NETWORK_") }?.let { it.timeMillis to (it.summary + ": " + it.type.name.removePrefix("NETWORK_").lowercase().replace('_', ' ')) }

    AstraScaffold(container, stringResource(R.string.privacy_title), onBack) { padding ->
        Column(Modifier.fillMaxSize().screenPadding(padding).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(stringResource(R.string.privacy_kill_switch)) {
                ToggleRow(stringResource(R.string.privacy_strict_offline), stringResource(R.string.privacy_strict_offline_note), strict) { container.setStrictOffline(it) }
                Text(stringResource(R.string.privacy_internet_permission_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionCard(stringResource(R.string.privacy_status)) {
                InfoRow(stringResource(R.string.privacy_network), when (val s = status) {
                    is NetworkStatus.Offline -> if (strict) stringResource(R.string.net_strict_offline) else stringResource(R.string.net_offline)
                    is NetworkStatus.RequestPending -> stringResource(R.string.net_pending) + ": " + s.task
                    is NetworkStatus.ApprovedForTask -> stringResource(R.string.net_approved) + ": " + s.task
                })
                InfoRow(stringResource(R.string.privacy_last_request), lastNetwork?.let { (t, d) ->
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t)) + " — " + d
                } ?: stringResource(R.string.privacy_never))
                InfoRow(stringResource(R.string.privacy_microphone), if (mic) stringResource(R.string.privacy_mic_granted) else stringResource(R.string.privacy_mic_not_granted))
                InfoRow(stringResource(R.string.privacy_model), active?.let { "${it.second.displayName} — ${it.second.license}" } ?: stringResource(R.string.privacy_none))
                InfoRow(stringResource(R.string.privacy_encryption), stringResource(R.string.privacy_encryption_value, protection))
                storage?.let { (lib, models, cache) ->
                    InfoRow(stringResource(R.string.privacy_storage_library), formatBytes(lib))
                    InfoRow(stringResource(R.string.privacy_storage_models), formatBytes(models))
                    InfoRow(stringResource(R.string.privacy_storage_cache), formatBytes(cache))
                }
                InfoRow(stringResource(R.string.privacy_analytics), stringResource(R.string.privacy_analytics_none))
                InfoRow(stringResource(R.string.privacy_backup), stringResource(R.string.privacy_backup_off))
            }
            SectionCard(stringResource(R.string.privacy_limits_title)) {
                Text(stringResource(R.string.privacy_limits_body), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { container.navigator.go(Screen.Audit) }) { Text(stringResource(R.string.audit_title)) }
                OutlinedButton(onClick = { container.navigator.go(Screen.Settings) }) { Text(stringResource(R.string.privacy_data_controls)) }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

// ------------------------------------------------------------------ Settings

@Composable
fun SettingsScreen(container: AppContainer, activity: MainActivity, onBack: () -> Unit) {
    val s by container.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<String?>(null) }
    var lockError by remember { mutableStateOf<String?>(null) }
    var retentionDraft by remember { mutableStateOf<Int?>(null) }
    var wipeText by remember { mutableStateOf("") }
    var askWipe by remember { mutableStateOf(false) }
    val done = stringResource(R.string.settings_done)

    AstraScaffold(container, stringResource(R.string.settings_title), onBack) { padding ->
        Column(Modifier.fillMaxSize().screenPadding(padding).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(stringResource(R.string.settings_appearance)) {
                Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    UiLanguage.values().forEach { l ->
                        FilterChip(selected = s.uiLanguage == l, onClick = {
                            container.settings.update { it.copy(uiLanguage = l) }
                            activity.recreate()
                        }, label = { Text(when (l) { UiLanguage.SYSTEM -> stringResource(R.string.settings_system); UiLanguage.ENGLISH -> "English"; UiLanguage.HINDI -> "हिन्दी" }) })
                    }
                }
                Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeMode.values().forEach { m ->
                        FilterChip(selected = s.theme == m, onClick = { container.settings.update { it.copy(theme = m) } }, label = {
                            Text(when (m) { ThemeMode.SYSTEM -> stringResource(R.string.settings_system); ThemeMode.LIGHT -> stringResource(R.string.settings_light); ThemeMode.DARK -> stringResource(R.string.settings_dark) })
                        })
                    }
                }
                Text(stringResource(R.string.settings_text_size, (s.textScale * 100).toInt()), style = MaterialTheme.typography.labelLarge)
                Slider(value = s.textScale, onValueChange = { v -> container.settings.update { it.copy(textScale = (v * 20).toInt() / 20f) } }, valueRange = 0.85f..1.6f)
            }

            SectionCard(stringResource(R.string.settings_transcription)) {
                Text(stringResource(R.string.transcribe_profile), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccuracyProfile.values().forEach { p -> FilterChip(selected = s.profile == p, onClick = { container.settings.update { it.copy(profile = p) } }, label = { Text(profileLabel(p)) }) }
                }
                Text(profileTradeoff(s.profile), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.transcribe_style), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = s.style == TranscriptStyle.STANDARD, onClick = { container.settings.update { it.copy(style = TranscriptStyle.STANDARD) } }, label = { Text(stringResource(R.string.style_standard)) })
                    FilterChip(selected = s.style == TranscriptStyle.VERBATIM, onClick = { container.settings.update { it.copy(style = TranscriptStyle.VERBATIM) } }, label = { Text(stringResource(R.string.style_verbatim)) })
                }
                Text(stringResource(R.string.settings_default_language), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("auto", "hi", "en").forEach { code ->
                        FilterChip(selected = s.defaultLanguage == code, onClick = { container.settings.update { it.copy(defaultLanguage = code) } },
                            label = { Text(when (code) { "auto" -> stringResource(R.string.lang_auto); "hi" -> "हिन्दी"; else -> "English" }) })
                    }
                }
                Text(stringResource(R.string.settings_threads, if (s.threads == 0) stringResource(R.string.settings_auto) else s.threads.toString()), style = MaterialTheme.typography.labelLarge)
                Slider(value = s.threads.toFloat(), onValueChange = { v -> container.settings.update { it.copy(threads = v.toInt()) } }, valueRange = 0f..8f, steps = 7)
                Text(stringResource(R.string.settings_chunk, s.chunkSeconds), style = MaterialTheme.typography.labelLarge)
                Slider(value = s.chunkSeconds.toFloat(), onValueChange = { v -> container.settings.update { it.copy(chunkSeconds = (v / 30).toInt() * 30) } }, valueRange = 30f..600f)
                Text(stringResource(R.string.settings_chunk_note), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.settings_max_import, s.maxImportMb), style = MaterialTheme.typography.labelLarge)
                Slider(value = s.maxImportMb.toFloat(), onValueChange = { v -> container.settings.update { it.copy(maxImportMb = (v / 50).toInt() * 50) } }, valueRange = 50f..2000f)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { container.navigator.go(Screen.Glossary) }) { Text(stringResource(R.string.glossary_title)) }
                    OutlinedButton(onClick = { container.navigator.go(Screen.Models) }) { Text(stringResource(R.string.home_models)) }
                    OutlinedButton(onClick = { container.navigator.go(Screen.Benchmark) }) { Text(stringResource(R.string.bench_title)) }
                }
            }

            SectionCard(stringResource(R.string.settings_security)) {
                ToggleRow(stringResource(R.string.settings_app_lock), stringResource(R.string.settings_app_lock_note), s.appLock) { on ->
                    if (on) {
                        val reason = container.appLock.unavailableReason(activity)
                        if (reason != null) lockError = reason else container.settings.update { it.copy(appLock = true) }
                    } else container.settings.update { it.copy(appLock = false) }
                }
                lockError?.let { Banner(it) }
                ToggleRow(stringResource(R.string.settings_block_screenshots), stringResource(R.string.settings_block_screenshots_note), s.blockScreenshots) { on ->
                    container.settings.update { it.copy(blockScreenshots = on) }
                }
                ToggleRow(stringResource(R.string.settings_audit), stringResource(R.string.settings_audit_note), s.auditLog) { on ->
                    container.settings.update { it.copy(auditLog = on) }
                }
                ToggleRow(stringResource(R.string.privacy_strict_offline), stringResource(R.string.privacy_strict_offline_note), s.strictOffline) { container.setStrictOffline(it) }
            }

            SectionCard(stringResource(R.string.settings_data)) {
                Text(stringResource(R.string.settings_retention, if (s.retentionDays == 0) stringResource(R.string.settings_retention_off) else stringResource(R.string.settings_retention_days, s.retentionDays)),
                    style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 30, 90, 365).forEach { d ->
                        FilterChip(selected = s.retentionDays == d, onClick = { if (d == 0) container.settings.update { it.copy(retentionDays = 0) } else retentionDraft = d },
                            label = { Text(if (d == 0) stringResource(R.string.settings_retention_off) else stringResource(R.string.settings_retention_days, d)) })
                    }
                }
                HorizontalDivider()
                DataButton(stringResource(R.string.data_delete_transcripts)) { confirm = "transcripts" }
                DataButton(stringResource(R.string.data_delete_cache)) { confirm = "cache" }
                DataButton(stringResource(R.string.data_rebuild_index)) { confirm = "index" }
                DataButton(stringResource(R.string.data_delete_models)) { confirm = "models" }
                DataButton(stringResource(R.string.data_clear_audit)) { confirm = "audit" }
                Button(onClick = { askWipe = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.data_wipe_all))
                }
                Text(stringResource(R.string.data_delete_note), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { container.navigator.go(Screen.About) }) { Text(stringResource(R.string.about_title)) }
            Spacer(Modifier.size(24.dp))
        }
    }

    retentionDraft?.let { d ->
        val count = container.library.items.value.count { it.createdMillis < System.currentTimeMillis() - d * 86_400_000L }
        ConfirmDialog(stringResource(R.string.settings_retention_confirm_title), stringResource(R.string.settings_retention_confirm_body, d, count),
            stringResource(R.string.action_enable), onConfirm = { container.settings.update { it.copy(retentionDays = d) } },
            onDismiss = { retentionDraft = null }, destructive = true)
    }
    confirm?.let { what ->
        ConfirmDialog(stringResource(R.string.data_confirm_title), stringResource(R.string.data_confirm_body), stringResource(R.string.action_delete),
            onConfirm = {
                scope.launch(Dispatchers.IO) {
                    when (what) {
                        "transcripts" -> container.library.deleteAllTranscripts()
                        "cache" -> { container.library.clearCaches(); container.exports.clearShared(); container.installer.deleteStaging() }
                        "index" -> container.library.rebuildIndex()
                        "models" -> {
                            container.engines.unload()
                            ModelRegistry.ALL.forEach { container.installer.delete(it.id) }
                            container.settings.update { it.copy(activeModelId = null) }
                        }
                        "audit" -> container.audit.clear()
                    }
                    container.notify(done)
                }
            }, onDismiss = { confirm = null }, destructive = true)
    }
    if (askWipe) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askWipe = false },
            title = { Text(stringResource(R.string.data_wipe_all)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.data_wipe_body))
                    OutlinedTextField(value = wipeText, onValueChange = { wipeText = it }, singleLine = true, label = { Text("DELETE") })
                }
            },
            confirmButton = {
                TextButton(enabled = wipeText.trim() == "DELETE", onClick = {
                    askWipe = false
                    scope.launch(Dispatchers.IO) { container.wipeEverything(); withContext(Dispatchers.Main) { container.navigator.home(); activity.recreate() } }
                }) { Text(stringResource(R.string.data_wipe_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askWipe = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun DataButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, color = MaterialTheme.colorScheme.error) }
}

// ------------------------------------------------------------------ Audit log

@Composable
fun AuditScreen(container: AppContainer, onBack: () -> Unit) {
    val events by container.audit.events.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { withContext(Dispatchers.IO) { container.audit.load() } }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }
    AstraScaffold(container, stringResource(R.string.audit_title), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(stringResource(R.string.audit_note), style = MaterialTheme.typography.bodySmall) }
            if (events.isEmpty()) item { Text(stringResource(R.string.audit_empty)) }
            items(events.asReversed()) { e ->
                Column(Modifier.fillMaxWidth()) {
                    Text(fmt.format(Date(e.timeMillis)) + "  ·  " + e.type.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(e.summary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    listOfNotNull(e.host, e.detail).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall) }
                }
                HorizontalDivider()
            }
        }
    }
}
