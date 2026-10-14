@file:OptIn(ExperimentalMaterial3Api::class)

package app.astra.audiotext.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.astra.audiotext.AppContainer
import app.astra.audiotext.R
import app.astra.audiotext.storage.ItemKind
import app.astra.audiotext.storage.LibraryItem
import app.astra.audiotext.ui.AstraScaffold
import app.astra.audiotext.ui.Banner
import app.astra.audiotext.ui.ConfirmDialog
import app.astra.audiotext.ui.Screen
import app.astra.audiotext.ui.TextInputDialog
import app.astra.core.common.formatDuration
import app.astra.core.privacy.AuditEvent
import app.astra.core.privacy.AuditType
import app.astra.core.text.DocumentImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private enum class Sort { NEWEST, OLDEST, TITLE, LONGEST }

fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (e: Exception) { null }

@Composable
fun LibraryScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by container.library.items.collectAsState()
    var sort by remember { mutableStateOf(Sort.NEWEST) }
    var filter by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var newNote by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val importingLabel = stringResource(R.string.library_importing)

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = importingLabel; error = null
        scope.launch {
            try {
                val item = withContext(Dispatchers.IO) {
                    val name = displayName(context, uri)
                    val mime = context.contentResolver.getType(uri)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        container.library.importAudio(input, name, mime, container.settings.value.maxImportMb * 1024L * 1024L) { false }
                    } ?: throw IllegalStateException("Could not open the file")
                }
                container.audit.record(AuditEvent(System.currentTimeMillis(), AuditType.IMPORT, "Imported an audio file"))
                container.navigator.go(Screen.Item(item.id))
            } catch (e: Exception) {
                error = e.message
            } finally { busy = null }
        }
    }
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = importingLabel; error = null
        scope.launch {
            try {
                val item = withContext(Dispatchers.IO) {
                    val name = displayName(context, uri)
                    val kind = DocumentImport.kindOf(name, context.contentResolver.getType(uri))
                    val imported = context.contentResolver.openInputStream(uri)?.use { DocumentImport.import(it, kind) }
                        ?: throw IllegalStateException("Could not open the file")
                    container.library.createNote(name?.substringBeforeLast('.') ?: "Document", imported.text, ItemKind.DOCUMENT,
                        app.astra.core.text.SafeFileName.sanitize(name ?: "document"))
                }
                container.audit.record(AuditEvent(System.currentTimeMillis(), AuditType.IMPORT, "Imported a document"))
                container.navigator.go(Screen.Item(item.id))
            } catch (e: Exception) {
                error = e.message
            } finally { busy = null }
        }
    }

    val allTags = items.flatMap { it.tags }.toSortedSet()
    val shown = items.filter { (filter.isBlank() || it.title.contains(filter, ignoreCase = true)) && (tag == null || tag in it.tags) }
        .let { list ->
            when (sort) {
                Sort.NEWEST -> list.sortedByDescending { it.createdMillis }
                Sort.OLDEST -> list.sortedBy { it.createdMillis }
                Sort.TITLE -> list.sortedBy { it.title.lowercase() }
                Sort.LONGEST -> list.sortedByDescending { it.durationMs ?: 0 }
            }
        }

    AstraScaffold(container, stringResource(R.string.library_title), onBack, actions = {
        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more)) }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.library_import_audio)) }, onClick = { menuOpen = false; audioPicker.launch(arrayOf("audio/*", "video/mp4", "application/ogg")) })
            DropdownMenuItem(text = { Text(stringResource(R.string.library_import_doc)) }, onClick = {
                menuOpen = false
                docPicker.launch(arrayOf("text/plain", "text/markdown", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.library_new_note)) }, onClick = { menuOpen = false; newNote = true })
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { audioPicker.launch(arrayOf("audio/*", "video/mp4", "application/ogg")) }, label = { Text(stringResource(R.string.library_import_audio)) })
                    AssistChip(onClick = { newNote = true }, label = { Text(stringResource(R.string.library_new_note)) })
                }
                OutlinedTextField(value = filter, onValueChange = { filter = it }, label = { Text(stringResource(R.string.library_filter)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Sort.values().forEach { s ->
                        FilterChip(selected = sort == s, onClick = { sort = s }, label = {
                            Text(when (s) {
                                Sort.NEWEST -> stringResource(R.string.sort_newest)
                                Sort.OLDEST -> stringResource(R.string.sort_oldest)
                                Sort.TITLE -> stringResource(R.string.sort_title)
                                Sort.LONGEST -> stringResource(R.string.sort_longest)
                            })
                        })
                    }
                    allTags.forEach { t -> FilterChip(selected = tag == t, onClick = { tag = if (tag == t) null else t }, label = { Text("#$t") }) }
                }
                busy?.let { Text(it); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                error?.let { Banner(it) }
                Text(stringResource(R.string.library_import_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (shown.isEmpty()) {
                Text(stringResource(R.string.library_empty), modifier = Modifier.padding(16.dp))
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shown, key = { it.id }) { item ->
                    LibraryRow(container, item)
                    HorizontalDivider()
                }
            }
        }
    }

    if (newNote) {
        val defaultTitle = stringResource(R.string.library_note_default)
        TextInputDialog(stringResource(R.string.library_new_note), "", stringResource(R.string.library_note_text), singleLine = false,
            onConfirm = { text ->
                if (text.isNotBlank()) {
                    val title = text.lineSequence().first().take(60).ifBlank { defaultTitle }
                    val item = container.library.createNote(title, text)
                    container.navigator.go(Screen.Item(item.id))
                }
            }, onDismiss = { newNote = false })
    }
}

@Composable
private fun LibraryRow(container: AppContainer, item: LibraryItem) {
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var tags by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val date = remember(item.createdMillis) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.createdMillis)) }
    Row(
        Modifier.fillMaxWidth().clickable { container.navigator.go(Screen.Item(item.id)) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val kind = when (item.kind) {
                ItemKind.RECORDING -> stringResource(R.string.kind_recording)
                ItemKind.IMPORTED_AUDIO -> stringResource(R.string.kind_audio)
                ItemKind.NOTE -> stringResource(R.string.kind_note)
                ItemKind.DOCUMENT -> stringResource(R.string.kind_document)
            }
            val parts = listOfNotNull(kind, date, item.durationMs?.let { formatDuration(it) },
                if (item.hasTranscript) stringResource(R.string.library_has_transcript) else null)
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.tags.isNotEmpty()) Text(item.tags.joinToString(" ") { "#$it" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menu = false; rename = true })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_tags)) }, onClick = { menu = false; tags = true })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; delete = true })
        }
    }
    if (rename) TextInputDialog(stringResource(R.string.action_rename), item.title, stringResource(R.string.label_title),
        onConfirm = { t -> if (t.isNotBlank()) container.library.update(item.id) { it.copy(title = t.trim()) } }, onDismiss = { rename = false })
    if (tags) TextInputDialog(stringResource(R.string.action_tags), item.tags.joinToString(", "), stringResource(R.string.label_tags_hint),
        onConfirm = { t -> container.library.update(item.id) { it.copy(tags = t.split(',', ' ').map { s -> s.trim().removePrefix("#") }.filter { s -> s.isNotEmpty() }.toSet()) } },
        onDismiss = { tags = false })
    if (delete) ConfirmDialog(stringResource(R.string.delete_item_title), stringResource(R.string.delete_item_body), stringResource(R.string.action_delete_permanently),
        onConfirm = {
            container.library.delete(item.id)
            container.audit.record(AuditEvent(System.currentTimeMillis(), AuditType.ITEM_DELETED, "Deleted a library item"))
        }, onDismiss = { delete = false }, destructive = true)
}
