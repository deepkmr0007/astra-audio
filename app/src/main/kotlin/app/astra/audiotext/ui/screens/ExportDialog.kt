package app.astra.audiotext.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.astra.audiotext.AppContainer
import app.astra.audiotext.R
import app.astra.audiotext.storage.LibraryItem
import app.astra.core.text.SafeFileName
import app.astra.core.transcript.Exporters
import app.astra.core.transcript.Transcript

/**
 * Export / share with an explicit preview. Saving uses the system "Save as" picker (the user chooses
 * the destination); sharing uses the system share sheet after a warning that data leaves ASTRA.
 */
@Composable
fun ExportDialog(container: AppContainer, item: LibraryItem, transcript: Transcript?, text: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var format by remember { mutableStateOf(Exporters.Format.TXT) }
    var layer by remember { mutableStateOf(Transcript.Layer.CORRECTED) }
    var clean by remember { mutableStateOf(false) }
    var timestamps by remember { mutableStateOf(true) }
    var confirmShare by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val content: String = if (transcript != null) {
        Exporters.export(transcript, format, Exporters.Options(layer = layer, clean = clean, includeTimestamps = timestamps))
    } else text.orEmpty()
    val effectiveFormat = if (transcript == null) Exporters.Format.TXT else format
    val fileName = if (transcript != null) Exporters.fileName(item.title, effectiveFormat) else SafeFileName.sanitize(item.title, "note") + ".txt"

    val saveTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let { write(container, it, content) { e -> error = e } } }
    val saveSrt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri -> uri?.let { write(container, it, content) { e -> error = e } } }
    val saveVtt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/vtt")) { uri -> uri?.let { write(container, it, content) { e -> error = e } } }
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { write(container, it, content) { e -> error = e } } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (transcript != null) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Exporters.Format.values().forEach { f -> FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.extension.uppercase()) }) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = layer == Transcript.Layer.CORRECTED, onClick = { layer = Transcript.Layer.CORRECTED }, label = { Text(stringResource(R.string.item_layer_corrected)) })
                        FilterChip(selected = layer == Transcript.Layer.RAW, onClick = { layer = Transcript.Layer.RAW }, label = { Text(stringResource(R.string.item_layer_raw)) })
                        FilterChip(selected = clean, onClick = { clean = !clean }, label = { Text(stringResource(R.string.item_view_clean)) })
                        if (format == Exporters.Format.TXT) FilterChip(selected = timestamps, onClick = { timestamps = !timestamps }, label = { Text(stringResource(R.string.export_timestamps)) })
                    }
                }
                Text(stringResource(R.string.export_file_name, fileName), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.export_preview), style = MaterialTheme.typography.labelLarge)
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                    Text(content.take(800) + if (content.length > 800) "\n…" else "", fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth()) {
                Button(onClick = {
                    when (effectiveFormat) {
                        Exporters.Format.TXT -> saveTxt.launch(fileName)
                        Exporters.Format.SRT -> saveSrt.launch(fileName)
                        Exporters.Format.VTT -> saveVtt.launch(fileName)
                        Exporters.Format.JSON -> saveJson.launch(fileName)
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_save_as)) }
                OutlinedButton(onClick = { confirmShare = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_share)) }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_close)) }
            }
        },
    )

    if (confirmShare) {
        AlertDialog(
            onDismissRequest = { confirmShare = false },
            title = { Text(stringResource(R.string.share_warning_title)) },
            text = { Text(stringResource(R.string.share_warning_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmShare = false
                    try {
                        context.startActivity(container.exports.shareIntent(fileName, effectiveFormat.mime, content.toByteArray(), "a transcript"))
                    } catch (e: Exception) { error = e.message }
                }) { Text(stringResource(R.string.export_share)) }
            },
            dismissButton = { TextButton(onClick = { confirmShare = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun write(container: AppContainer, uri: android.net.Uri, content: String, onError: (String) -> Unit) {
    try { container.exports.writeToUri(uri, content.toByteArray(), "a transcript") } catch (e: Exception) { onError(e.message ?: "Export failed") }
}
