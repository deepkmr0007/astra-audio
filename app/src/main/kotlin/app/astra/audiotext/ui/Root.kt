package app.astra.audiotext.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.astra.audiotext.AppContainer
import app.astra.audiotext.MainActivity
import app.astra.audiotext.R
import app.astra.audiotext.ui.screens.AboutScreen
import app.astra.audiotext.ui.screens.AuditScreen
import app.astra.audiotext.ui.screens.BenchmarkScreen
import app.astra.audiotext.ui.screens.GlossaryScreen
import app.astra.audiotext.ui.screens.HomeScreen
import app.astra.audiotext.ui.screens.ItemScreen
import app.astra.audiotext.ui.screens.LibraryScreen
import app.astra.audiotext.ui.screens.ModelsScreen
import app.astra.audiotext.ui.screens.PrivacyScreen
import app.astra.audiotext.ui.screens.RecordScreen
import app.astra.audiotext.ui.screens.SearchScreen
import app.astra.audiotext.ui.screens.SettingsScreen
import app.astra.audiotext.ui.screens.SpeakScreen
import app.astra.audiotext.ui.screens.TextToolsScreen
import app.astra.audiotext.ui.screens.TranslateScreen
import app.astra.core.common.formatBytes
import app.astra.core.privacy.ConsentDecision
import app.astra.core.privacy.ConsentRequest
import app.astra.core.privacy.PayloadClass

@Composable
fun AstraRoot(container: AppContainer, activity: MainActivity) {
    val settings by container.settings.state.collectAsState()
    AstraTheme(settings.theme, settings.textScale) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val locked by container.appLock.locked.collectAsState()
            val load by container.loadState.collectAsState()
            when {
                locked -> LockScreen(container, activity)
                load is AppContainer.LoadState.Loading -> CenteredProgress(stringResource(R.string.loading_library))
                load is AppContainer.LoadState.Failed -> LoadFailed((load as AppContainer.LoadState.Failed).reason)
                else -> Screens(container, activity)
            }
            val pending by container.consent.pending.collectAsState()
            pending?.let { ConsentDialog(it.request) { d -> container.consent.answer(d) } }
            val notice by container.notice.collectAsState()
            notice?.let { msg ->
                AlertDialog(
                    onDismissRequest = { container.clearNotice() },
                    text = { Text(msg) },
                    confirmButton = { TextButton(onClick = { container.clearNotice() }) { Text(stringResource(R.string.action_ok)) } },
                )
            }
        }
    }
}

@Composable
private fun Screens(container: AppContainer, activity: MainActivity) {
    val stack by container.navigator.stack.collectAsState()
    BackHandler(enabled = stack.size > 1) { container.navigator.back() }
    val back: () -> Unit = { container.navigator.back() }
    when (val s = stack.last()) {
        Screen.Home -> HomeScreen(container)
        Screen.Record -> RecordScreen(container, back)
        Screen.Library -> LibraryScreen(container, back)
        is Screen.Item -> ItemScreen(container, s.id, back)
        is Screen.TextTools -> TextToolsScreen(container, s.itemId, back)
        Screen.Translate -> TranslateScreen(container, back)
        is Screen.Speak -> SpeakScreen(container, s.text, back)
        Screen.Search -> SearchScreen(container, back)
        Screen.Models -> ModelsScreen(container, back)
        Screen.Privacy -> PrivacyScreen(container, back)
        Screen.Settings -> SettingsScreen(container, activity, back)
        Screen.Audit -> AuditScreen(container, back)
        Screen.Benchmark -> BenchmarkScreen(container, back)
        Screen.Glossary -> GlossaryScreen(container, back)
        Screen.About -> AboutScreen(container, back)
    }
}

@Composable
private fun LockScreen(container: AppContainer, activity: MainActivity) {
    var error by remember { mutableStateOf<String?>(null) }
    val title = stringResource(R.string.lock_title)
    val subtitle = stringResource(R.string.lock_subtitle)
    val prompt = { container.appLock.authenticate(activity, title, subtitle) { ok, err -> if (!ok) error = err } }
    LaunchedEffect(Unit) { prompt() }
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.size(8.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        error?.let { Spacer(Modifier.size(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.size(24.dp))
        Button(onClick = { prompt() }) { Text(stringResource(R.string.lock_unlock)) }
    }
}

@Composable
private fun LoadFailed(reason: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.load_failed_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.size(8.dp))
        Text(reason)
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.load_failed_hint))
    }
}

/**
 * The per-request consent screen. Shows exactly what will happen; there is no "always allow".
 */
@Composable
fun ConsentDialog(request: ConsentRequest, onDecision: (ConsentDecision) -> Unit) {
    AlertDialog(
        onDismissRequest = { onDecision(ConsentDecision.CANCEL) },
        title = { Text(stringResource(R.string.consent_title)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ConsentField(stringResource(R.string.consent_task), request.task)
                ConsentField(stringResource(R.string.consent_destination), request.destinationHost)
                ConsentField(stringResource(R.string.consent_redirect_hosts), request.allowedHosts.sorted().joinToString(", "))
                ConsentField(stringResource(R.string.consent_data_sent), request.payloadDescription)
                ConsentField(stringResource(R.string.consent_leaves_phone),
                    when (request.payloadClass) {
                        PayloadClass.NONE -> stringResource(R.string.consent_no_content)
                        PayloadClass.TEXT_CONTENT -> stringResource(R.string.consent_text_content)
                        PayloadClass.AUDIO_CONTENT -> stringResource(R.string.consent_audio_content)
                        PayloadClass.FILE_METADATA -> stringResource(R.string.consent_metadata)
                    })
                request.payloadPreview?.let {
                    Text(stringResource(R.string.consent_preview), style = MaterialTheme.typography.labelLarge)
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                        Text(it.take(2000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                    }
                }
                ConsentField(stringResource(R.string.consent_reason), request.reason)
                ConsentField(stringResource(R.string.consent_data_use), request.estimatedBytes?.let { "≈ " + formatBytes(it) } ?: stringResource(R.string.consent_unknown))
                ConsentField(stringResource(R.string.consent_privacy_note), request.providerPrivacyNote ?: stringResource(R.string.consent_not_verified))
                ConsentField(stringResource(R.string.consent_device_ids), stringResource(R.string.consent_device_ids_none))
                HorizontalDivider()
                Text(stringResource(R.string.consent_once_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { onDecision(ConsentDecision.ALLOW_ONCE) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.consent_allow_once))
                }
                if (request.offlineAlternative != null) {
                    OutlinedButton(onClick = { onDecision(ConsentDecision.USE_OFFLINE_ALTERNATIVE) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.consent_offline_alternative) + ": " + request.offlineAlternative)
                    }
                }
                TextButton(onClick = { onDecision(ConsentDecision.CANCEL) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}

@Composable
private fun ConsentField(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
