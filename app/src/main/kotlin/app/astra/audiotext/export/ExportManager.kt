package app.astra.audiotext.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.astra.core.privacy.AuditEvent
import app.astra.core.privacy.AuditSink
import app.astra.core.privacy.AuditType
import app.astra.core.text.SafeFileName
import java.io.File

/**
 * Export and sharing — only ever as a direct result of a user action, to a destination the user
 * chose (Storage Access Framework "Save as…" or the system share sheet). Shared files are written to
 * a short-lived cache folder that is emptied on the next app start and by "Delete temporary files".
 */
class ExportManager(private val context: Context, private val audit: AuditSink) {

    private val exportDir: File get() = File(context.cacheDir, "exports").apply { mkdirs() }

    /** Writes [bytes] to a document the user created with ACTION_CREATE_DOCUMENT. */
    fun writeToUri(uri: Uri, bytes: ByteArray, what: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw IllegalStateException("Could not open the chosen destination")
        audit.record(AuditEvent(System.currentTimeMillis(), AuditType.EXPORT, "Exported $what to a location you chose"))
    }

    /**
     * Prepares a share intent for the system share sheet. The caller shows the chooser; the user picks
     * the target app. Sharing sends data outside ASTRA — the UI warns before this is called.
     */
    fun shareIntent(fileName: String, mime: String, bytes: ByteArray, what: String): Intent {
        val f = File(exportDir, SafeFileName.sanitize(fileName, "export"))
        f.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".exports", f)
        audit.record(AuditEvent(System.currentTimeMillis(), AuditType.SHARE, "Shared $what via the share sheet"))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(f.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null)
    }

    fun clearShared(): Long {
        val size = exportDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        exportDir.deleteRecursively()
        return size
    }
}
