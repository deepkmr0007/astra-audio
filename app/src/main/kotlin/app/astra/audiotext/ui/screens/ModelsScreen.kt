@file:OptIn(ExperimentalMaterial3Api::class)

package app.astra.audiotext.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.astra.audiotext.AppContainer
import app.astra.audiotext.R
import app.astra.audiotext.ui.AstraScaffold
import app.astra.audiotext.ui.Banner
import app.astra.audiotext.ui.ConfirmDialog
import app.astra.audiotext.ui.InfoRow
import app.astra.audiotext.ui.SectionCard
import app.astra.core.common.formatBytes
import app.astra.core.models.InstallOutcome
import app.astra.core.models.InstalledModel
import app.astra.core.models.ModelRegistry
import app.astra.core.models.ModelSpec
import app.astra.core.models.ResourceAdvisor
import app.astra.core.privacy.AuditEvent
import app.astra.core.privacy.AuditType
import app.astra.core.privacy.ConsentDecision
import app.astra.core.privacy.NetworkConsentGateway
import app.astra.core.privacy.NetworkOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ModelsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.state.collectAsState()
    val strict by container.gateway.strictOffline.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    val installed = remember(refresh) { container.installer.installed() }
    val device = remember(refresh) { container.device.snapshot() }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageWarn by remember { mutableStateOf(false) }
    var cancelFlag by remember { mutableStateOf(false) }
    var askStrict by remember { mutableStateOf<ModelSpec?>(null) }
    var redirectRetry by remember { mutableStateOf<Pair<ModelSpec, String>?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var importFor by remember { mutableStateOf<ModelSpec?>(null) }
    val recommended = ResourceAdvisor.recommendedModelId(device, wantsHindi = true)
    val importingText = stringResource(R.string.models_verifying)
    val downloadingText = stringResource(R.string.models_downloading)

    fun report(outcome: InstallOutcome, spec: ModelSpec?) {
        messageWarn = outcome !is InstallOutcome.Installed
        message = when (outcome) {
            is InstallOutcome.Installed -> {
                if (settings.activeModelId == null) container.settings.update { it.copy(activeModelId = outcome.model.specId) }
                context.getString(R.string.models_installed_ok, ModelRegistry.byId(outcome.model.specId)?.displayName ?: outcome.model.specId)
            }
            is InstallOutcome.Rejected -> context.getString(R.string.models_rejected, outcome.reason)
            is InstallOutcome.Failed -> context.getString(R.string.models_failed, outcome.reason)
            InstallOutcome.Cancelled -> context.getString(R.string.models_cancelled)
            is InstallOutcome.NotDownloaded -> {
                val n = outcome.network
                if (n is NetworkOutcome.RedirectBlocked && spec != null) { redirectRetry = spec to n.unapprovedHost; null }
                else if (n is NetworkOutcome.Declined && n.decision == ConsentDecision.USE_OFFLINE_ALTERNATIVE && spec != null) { importFor = spec; null }
                else context.getString(R.string.models_not_downloaded, NetworkConsentGateway.describe(n))
            }
        }
        refresh++
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val spec = importFor
        importFor = null
        if (uri == null) return@rememberLauncherForActivityResult
        busy = importingText; progress = null; message = null; cancelFlag = false
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    container.downloader.importFile(input, spec, { cancelFlag }) { }
                } ?: InstallOutcome.Failed("Could not open the file")
            }
            busy = null
            report(outcome, spec)
        }
    }
    if (importFor != null) {
        androidx.compose.runtime.LaunchedEffect(importFor) { importLauncher.launch(arrayOf("*/*")) }
    }

    fun download(spec: ModelSpec, extraHosts: Set<String> = emptySet()) {
        if (strict) { askStrict = spec; return }
        busy = downloadingText; progress = 0f; message = null; cancelFlag = false
        scope.launch {
            val outcome = container.downloader.download(spec, extraHosts, { cancelFlag }) { bytes, total ->
                progress = if (total != null && total > 0) bytes.toFloat() / total else null
            }
            busy = null; progress = null
            report(outcome, spec)
        }
    }

    AstraScaffold(container, stringResource(R.string.models_title), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(stringResource(R.string.models_device)) {
                    InfoRow(stringResource(R.string.models_ram), "${formatBytes(device.totalRamBytes)} (${formatBytes(device.availRamBytes)} free)")
                    InfoRow(stringResource(R.string.models_storage), formatBytes(device.freeStorageBytes))
                    InfoRow(stringResource(R.string.models_cores), device.cpuCores.toString())
                    InfoRow(stringResource(R.string.models_recommended), ModelRegistry.byId(recommended)?.displayName ?: recommended)
                    Text(stringResource(R.string.models_how_to_get), style = MaterialTheme.typography.bodySmall)
                }
            }
            busy?.let { b ->
                item {
                    SectionCard {
                        Text(b)
                        val p = progress
                        if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { cancelFlag = true }) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
            }
            message?.let { m -> item { Banner(m, warning = messageWarn) } }
            if (installed.isNotEmpty()) {
                item { Text(stringResource(R.string.models_capabilities), style = MaterialTheme.typography.titleMedium) }
                item { CapabilityTable(installed) }
            }
            items(ModelRegistry.ALL) { spec ->
                val inst = installed.firstOrNull { it.specId == spec.id }
                ModelCard(
                    spec = spec,
                    installed = inst,
                    active = settings.activeModelId == spec.id || (settings.activeModelId == null && inst != null && installed.first() == inst),
                    recommended = spec.id == recommended,
                    installAdvice = ResourceAdvisor.canInstall(spec, device),
                    runAdvice = ResourceAdvisor.canRun(spec, device),
                    enabled = busy == null,
                    onDownload = { download(spec) },
                    onImport = { importFor = spec },
                    onActivate = {
                        container.settings.update { it.copy(activeModelId = spec.id) }
                        scope.launch { container.engines.unload() }
                    },
                    onVerify = {
                        busy = importingText
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { container.installer.recheck(inst!!) }
                            busy = null
                            messageWarn = !ok
                            message = if (ok) context.getString(R.string.models_verify_ok) else context.getString(R.string.models_verify_bad)
                        }
                    },
                    onDelete = { confirmDelete = spec.id },
                )
            }
            item {
                OutlinedButton(onClick = { importFor = null; importLauncher.launch(arrayOf("*/*")) }, enabled = busy == null) {
                    Text(stringResource(R.string.models_import_any))
                }
            }
        }
    }

    askStrict?.let { spec ->
        ConfirmDialog(stringResource(R.string.models_strict_title), stringResource(R.string.models_strict_body), stringResource(R.string.models_strict_turn_off),
            onConfirm = { container.setStrictOffline(false); download(spec) }, onDismiss = { askStrict = null })
    }
    redirectRetry?.let { (spec, host) ->
        ConfirmDialog(stringResource(R.string.models_redirect_title), stringResource(R.string.models_redirect_body, host), stringResource(R.string.models_redirect_ask),
            onConfirm = { download(spec, setOf(host)) }, onDismiss = { redirectRetry = null })
    }
    confirmDelete?.let { id ->
        ConfirmDialog(stringResource(R.string.models_delete_title), stringResource(R.string.models_delete_body), stringResource(R.string.action_delete),
            onConfirm = {
                scope.launch {
                    container.engines.unload()
                    withContext(Dispatchers.IO) { container.installer.delete(id) }
                    container.audit.record(AuditEvent(System.currentTimeMillis(), AuditType.MODEL_DELETED, "Model deleted: $id"))
                    if (container.settings.value.activeModelId == id) container.settings.update { it.copy(activeModelId = null) }
                    refresh++
                }
            }, onDismiss = { confirmDelete = null }, destructive = true)
    }
}

@Composable
private fun ModelCard(
    spec: ModelSpec, installed: InstalledModel?, active: Boolean, recommended: Boolean,
    installAdvice: ResourceAdvisor.Advice, runAdvice: ResourceAdvisor.Advice, enabled: Boolean,
    onDownload: () -> Unit, onImport: () -> Unit, onActivate: () -> Unit, onVerify: () -> Unit, onDelete: () -> Unit,
) {
    var details by remember { mutableStateOf(false) }
    SectionCard(spec.displayName + if (recommended) "  ★" else "") {
        Text(spec.languagesLabel, style = MaterialTheme.typography.bodySmall)
        InfoRow(stringResource(R.string.models_size), "≈ " + formatBytes(spec.approxBytes))
        InfoRow(stringResource(R.string.models_memory), "≈ " + formatBytes(spec.approxRamBytes))
        InfoRow(stringResource(R.string.models_license), spec.license)
        if (installed != null) {
            InfoRow(stringResource(R.string.models_status), if (active) stringResource(R.string.models_active) else stringResource(R.string.models_installed))
        }
        runAdvice.warning?.let { Banner(it) }
        if (installed == null) installAdvice.warning?.let { Banner(it) }
        if (details) {
            InfoRow(stringResource(R.string.models_publisher), spec.publisher)
            InfoRow(stringResource(R.string.models_source), spec.sourceUrl)
            InfoRow(stringResource(R.string.models_quant), spec.quantization)
            InfoRow("SHA-1", spec.sha1)
            installed?.let { InfoRow("SHA-256", it.sha256) }
            InfoRow(stringResource(R.string.models_translate), if (spec.translateToEnglish) "✓" else "—")
            InfoRow(stringResource(R.string.models_turns), if (spec.speakerTurns) "✓" else "—")
            Text(stringResource(R.string.models_limitations), style = MaterialTheme.typography.labelLarge)
            spec.limitations.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
        TextButton(onClick = { details = !details }) { Text(if (details) stringResource(R.string.action_less) else stringResource(R.string.action_details)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (installed == null) {
                Button(enabled = enabled && installAdvice.fits, onClick = onDownload) { Text(stringResource(R.string.models_download)) }
                OutlinedButton(enabled = enabled, onClick = onImport) { Text(stringResource(R.string.models_import)) }
            } else {
                if (!active) Button(enabled = enabled && runAdvice.fits, onClick = onActivate) { Text(stringResource(R.string.models_use)) }
                OutlinedButton(enabled = enabled, onClick = onVerify) { Text(stringResource(R.string.models_verify)) }
                TextButton(enabled = enabled, onClick = onDelete) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun CapabilityTable(installed: List<InstalledModel>) {
    SectionCard {
        val header = listOf(stringResource(R.string.cap_model), stringResource(R.string.cap_transcribe), stringResource(R.string.cap_lang_id),
            stringResource(R.string.cap_translate), stringResource(R.string.cap_turns), stringResource(R.string.cap_offline))
        Text(header.joinToString(" | "), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
        installed.forEach { m ->
            val s = ModelRegistry.byId(m.specId) ?: return@forEach
            Column(Modifier.fillMaxWidth()) {
                Text(s.displayName, style = MaterialTheme.typography.bodyMedium)
                Text(listOf(
                    if (s.multilingual) "multi" else "en",
                    if (s.multilingual) "✓" else "—",
                    if (s.translateToEnglish) "→en" else "—",
                    if (s.speakerTurns) "✓" else "—",
                    "✓",
                ).joinToString(" | "), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
        Text(stringResource(R.string.cap_tts_note), style = MaterialTheme.typography.bodySmall)
    }
}
