package app.astra.audiotext.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.astra.audiotext.AppContainer
import app.astra.audiotext.R
import app.astra.audiotext.ui.AstraScaffold
import app.astra.audiotext.ui.Banner
import app.astra.audiotext.ui.Screen
import app.astra.audiotext.ui.SectionCard

@Composable
fun HomeScreen(container: AppContainer) {
    val nav = container.navigator
    val items by container.library.items.collectAsState()
    val jobs by container.transcriptions.jobs.collectAsState()
    val strict by container.gateway.strictOffline.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    val models = remember(refresh, items) { container.installer.installed() }
    val resumable = remember(refresh, items, jobs) { container.transcriptions.resumable() }

    AstraScaffold(container, stringResource(R.string.app_name), onBack = null, actions = {}) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (models.isEmpty()) {
                item {
                    SectionCard(stringResource(R.string.home_no_model_title)) {
                        Text(stringResource(R.string.home_no_model_body))
                        Button(onClick = { nav.go(Screen.Models) }) { Text(stringResource(R.string.home_open_models)) }
                    }
                }
            }
            resumable.forEach { rq ->
                item {
                    val title = container.library.get(rq.itemId)?.title ?: ""
                    SectionCard(stringResource(R.string.home_resume_title)) {
                        Text(stringResource(R.string.home_resume_body, title))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { container.transcriptions.start(rq); nav.go(Screen.Item(rq.itemId)) }) { Text(stringResource(R.string.action_resume)) }
                            OutlinedButton(onClick = { container.transcriptions.discardResume(rq.itemId); refresh++ }) { Text(stringResource(R.string.action_discard)) }
                        }
                    }
                }
            }
            jobs.values.filter { it.running }.forEach { job ->
                item {
                    val title = container.library.get(job.itemId)?.title ?: ""
                    Surface(onClick = { nav.go(Screen.Item(job.itemId)) }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(stringResource(R.string.home_job_running, title, job.percent), modifier = Modifier.fillMaxWidth().padding(12.dp))
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile(stringResource(R.string.home_record), Icons.Filled.Create, Modifier.weight(1f), primary = true) { nav.go(Screen.Record) }
                        Tile(stringResource(R.string.home_import_audio), Icons.Filled.Add, Modifier.weight(1f)) { nav.go(Screen.Library) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile(stringResource(R.string.home_transcribe), Icons.Filled.List, Modifier.weight(1f)) { nav.go(Screen.Library) }
                        Tile(stringResource(R.string.home_text_tools), Icons.Filled.Edit, Modifier.weight(1f)) { nav.go(Screen.TextTools()) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile(stringResource(R.string.home_translate), Icons.Filled.Refresh, Modifier.weight(1f)) { nav.go(Screen.Translate) }
                        Tile(stringResource(R.string.home_speak), Icons.Filled.PlayArrow, Modifier.weight(1f)) { nav.go(Screen.Speak()) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile(stringResource(R.string.home_search), Icons.Filled.Search, Modifier.weight(1f)) { nav.go(Screen.Search) }
                        Tile(stringResource(R.string.home_settings), Icons.Filled.Settings, Modifier.weight(1f)) { nav.go(Screen.Settings) }
                    }
                }
            }
            item {
                SectionCard(stringResource(R.string.home_privacy_title)) {
                    Text(if (strict) stringResource(R.string.home_privacy_strict) else stringResource(R.string.home_privacy_ask))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { nav.go(Screen.Privacy) }) {
                            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.home_privacy_open))
                        }
                        OutlinedButton(onClick = { nav.go(Screen.Models) }) {
                            Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.home_models))
                        }
                    }
                }
            }
            item {
                Text(stringResource(R.string.home_library_count, items.size), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (items.isNotEmpty() && models.isNotEmpty() && items.none { it.hasTranscript }) {
                item { Banner(stringResource(R.string.home_hint_first_transcript), warning = false) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Tile(label: String, icon: ImageVector, modifier: Modifier, primary: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        tonalElevation = if (primary) 0.dp else 1.dp,
        modifier = modifier.height(92.dp).semantics { role = Role.Button },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}
