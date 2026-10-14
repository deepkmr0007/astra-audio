package app.astra.audiotext.storage

import app.astra.core.common.Ids
import app.astra.core.common.Json
import app.astra.core.common.bool
import app.astra.core.common.list
import app.astra.core.common.long
import app.astra.core.common.str
import app.astra.core.security.KeyWrapper
import app.astra.core.security.StreamingAead
import app.astra.core.text.SafeFileName
import app.astra.core.text.SearchIndex
import app.astra.core.transcript.Transcript
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

enum class ItemKind { RECORDING, IMPORTED_AUDIO, NOTE, DOCUMENT }

data class LibraryItem(
    val id: String,
    val title: String,
    val kind: ItemKind,
    val createdMillis: Long,
    val modifiedMillis: Long,
    val durationMs: Long? = null,
    val tags: Set<String> = emptySet(),
    /** Sanitised original file name, shown to the user only. Never logged. */
    val sourceName: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val hasAudio: Boolean = false,
    val hasCleanAudio: Boolean = false,
    val hasTranscript: Boolean = false,
    val hasText: Boolean = false,
    val qualityIssues: List<String> = emptyList(),
) {
    fun toJson(): String = Json.write(mapOf(
        "id" to id, "title" to title, "kind" to kind.name, "created" to createdMillis, "modified" to modifiedMillis,
        "duration" to durationMs, "tags" to tags.toList(), "sourceName" to sourceName, "mime" to mimeType, "size" to sizeBytes,
        "hasAudio" to hasAudio, "hasCleanAudio" to hasCleanAudio, "hasTranscript" to hasTranscript, "hasText" to hasText,
        "quality" to qualityIssues,
    ))

    companion object {
        fun fromJson(s: String): LibraryItem {
            val m = Json.parseObject(s)
            return LibraryItem(
                id = m.str("id")!!, title = m.str("title") ?: "Untitled",
                kind = ItemKind.values().firstOrNull { it.name == m.str("kind") } ?: ItemKind.NOTE,
                createdMillis = m.long("created") ?: 0, modifiedMillis = m.long("modified") ?: 0,
                durationMs = m.long("duration"), tags = m.list("tags")?.mapNotNull { it as? String }?.toSet() ?: emptySet(),
                sourceName = m.str("sourceName"), mimeType = m.str("mime"), sizeBytes = m.long("size"),
                hasAudio = m.bool("hasAudio") ?: false, hasCleanAudio = m.bool("hasCleanAudio") ?: false,
                hasTranscript = m.bool("hasTranscript") ?: false, hasText = m.bool("hasText") ?: false,
                qualityIssues = m.list("quality")?.mapNotNull { it as? String } ?: emptyList(),
            )
        }
    }
}

/**
 * Encrypted, app-private library. Each item is a folder; every file in it is encrypted with its
 * own data key (see StreamingAead). Nothing in here is visible to other apps or backed up.
 *
 * Original media is preserved: an imported file's bytes are stored unchanged (only encrypted at
 * rest); cleaned audio is a separate derivative file; transcripts keep raw and corrected layers.
 */
class LibraryRepository(private val root: File, private val keys: KeyWrapper, private val clock: () -> Long = System::currentTimeMillis) {

    private val libDir = File(root, "library").apply { mkdirs() }
    private val _items = MutableStateFlow<List<LibraryItem>>(emptyList())
    val items: StateFlow<List<LibraryItem>> = _items.asStateFlow()
    val searchIndex = SearchIndex()
    private val _indexReady = MutableStateFlow(false)
    val indexReady: StateFlow<Boolean> = _indexReady.asStateFlow()

    fun dir(id: String): File = File(libDir, SafeFileName.sanitize(id, "x"))
    fun audioFile(id: String) = File(dir(id), "audio.enc")
    fun cleanAudioFile(id: String) = File(dir(id), "audio_clean.enc")
    fun pcmCacheFile(id: String) = File(dir(id), "pcm16k.enc")
    private fun metaFile(id: String) = File(dir(id), "meta.enc")
    private fun transcriptFile(id: String) = File(dir(id), "transcript.enc")
    private fun textFile(id: String) = File(dir(id), "text.enc")
    fun checkpointFile(id: String) = File(dir(id), "checkpoint.enc")

    /** Decrypts all metadata and rebuilds the in-memory search index. Call off the main thread. */
    @Synchronized
    fun load() {
        val loaded = libDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { d ->
            try { LibraryItem.fromJson(String(StreamingAead.readFile(File(d, "meta.enc"), keys))) } catch (e: Exception) { null }
        } ?: emptyList()
        _items.value = loaded.sortedByDescending { it.createdMillis }
        rebuildIndex()
    }

    @Synchronized
    fun rebuildIndex() {
        _indexReady.value = false
        searchIndex.clear()
        for (item in _items.value) index(item)
        _indexReady.value = true
    }

    private fun index(item: LibraryItem) {
        val text = buildString {
            if (item.hasTranscript) readTranscript(item.id)?.let { append(it.plainText()) }
            if (item.hasText) readText(item.id)?.let { append('\n').append(it) }
        }
        searchIndex.put(SearchIndex.Doc(item.id, item.title, text, item.createdMillis, item.tags))
    }

    private fun saveMeta(item: LibraryItem) {
        dir(item.id).mkdirs()
        StreamingAead.writeFile(metaFile(item.id), item.toJson().toByteArray(), keys)
        _items.value = (listOf(item) + _items.value.filter { it.id != item.id }).sortedByDescending { it.createdMillis }
    }

    fun get(id: String): LibraryItem? = _items.value.firstOrNull { it.id == id }

    fun update(id: String, transform: (LibraryItem) -> LibraryItem): LibraryItem? {
        val old = get(id) ?: return null
        val new = transform(old).copy(modifiedMillis = clock())
        saveMeta(new)
        if (old.title != new.title || old.tags != new.tags) index(new)
        return new
    }

    // ------------------------------------------------------------------ creating items

    fun newItemId(): String = Ids.newId()

    /** Opens an encrypting stream for an item's audio file (used by the recorder and importer). */
    fun openAudioOutput(id: String, clean: Boolean = false): OutputStream {
        dir(id).mkdirs()
        val f = if (clean) cleanAudioFile(id) else audioFile(id)
        return StreamingAead.encryptingStream(f.outputStream(), keys)
    }

    fun register(item: LibraryItem): LibraryItem { saveMeta(item); index(item); return item }

    /**
     * Copies a user-selected file into the library byte-for-byte (encrypted at rest).
     * The source file itself is only read, never modified.
     */
    fun importAudio(input: InputStream, displayName: String?, mime: String?, maxBytes: Long, isCancelled: () -> Boolean): LibraryItem {
        val id = newItemId()
        var total = 0L
        try {
            openAudioOutput(id).use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    if (isCancelled()) throw java.util.concurrent.CancellationException()
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IOException("File is larger than the ${maxBytes / (1024 * 1024)} MB import limit (change it in Settings).")
                    out.write(buf, 0, n)
                }
            }
        } catch (e: Exception) {
            dir(id).deleteRecursively()
            throw e
        }
        val now = clock()
        val name = displayName?.let { SafeFileName.sanitize(it) }
        return register(LibraryItem(id, name?.substringBeforeLast('.') ?: "Imported audio", ItemKind.IMPORTED_AUDIO, now, now,
            sourceName = name, mimeType = mime, sizeBytes = total, hasAudio = true))
    }

    fun createNote(title: String, text: String, kind: ItemKind = ItemKind.NOTE, sourceName: String? = null): LibraryItem {
        val id = newItemId()
        dir(id).mkdirs()
        StreamingAead.writeFile(textFile(id), text.toByteArray(), keys)
        val now = clock()
        return register(LibraryItem(id, title.ifBlank { "Untitled note" }, kind, now, now, sourceName = sourceName, hasText = true))
    }

    // ------------------------------------------------------------------ content

    fun readTranscript(id: String): Transcript? {
        val f = transcriptFile(id)
        if (!f.exists()) return null
        return try { Transcript.fromJson(String(StreamingAead.readFile(f, keys))) } catch (e: Exception) { null }
    }

    fun saveTranscript(t: Transcript) {
        val id = t.sourceItemId ?: error("transcript without item")
        StreamingAead.writeFile(transcriptFile(id), t.toJson().toByteArray(), keys)
        update(id) { it.copy(hasTranscript = true) }
        get(id)?.let { index(it) }
    }

    fun readText(id: String): String? {
        val f = textFile(id)
        if (!f.exists()) return null
        return try { String(StreamingAead.readFile(f, keys)) } catch (e: Exception) { null }
    }

    fun saveText(id: String, text: String) {
        StreamingAead.writeFile(textFile(id), text.toByteArray(), keys)
        update(id) { it.copy(hasText = true) }
        get(id)?.let { index(it) }
    }

    fun audioReader(id: String, clean: Boolean = false): StreamingAead.RandomAccessReader =
        StreamingAead.RandomAccessReader(if (clean) cleanAudioFile(id) else audioFile(id), keys)

    fun openAudioInput(id: String, clean: Boolean = false): InputStream =
        StreamingAead.decryptingStream((if (clean) cleanAudioFile(id) else audioFile(id)).inputStream(), keys)

    fun writeEncrypted(file: File, bytes: ByteArray) = StreamingAead.writeFile(file, bytes, keys)
    fun readEncrypted(file: File): ByteArray = StreamingAead.readFile(file, keys)
    fun encryptTo(file: File): OutputStream { file.parentFile?.mkdirs(); return StreamingAead.encryptingStream(file.outputStream(), keys) }
    fun decryptFrom(file: File): InputStream = StreamingAead.decryptingStream(file.inputStream(), keys)
    fun randomAccess(file: File) = StreamingAead.RandomAccessReader(file, keys)

    // ------------------------------------------------------------------ deletion

    fun delete(id: String) {
        dir(id).deleteRecursively()
        _items.value = _items.value.filter { it.id != id }
        searchIndex.remove(id)
    }

    fun deleteTranscript(id: String) {
        transcriptFile(id).delete()
        checkpointFile(id).delete()
        update(id) { it.copy(hasTranscript = false) }
        get(id)?.let { index(it) }
    }

    fun deleteAllTranscripts() { _items.value.filter { it.hasTranscript }.forEach { deleteTranscript(it.id) } }

    /** Removes decoded-audio caches and pending job checkpoints (they are rebuilt on demand). */
    fun clearCaches(): Long {
        var freed = 0L
        libDir.listFiles()?.forEach { d ->
            listOf(File(d, "pcm16k.enc"), File(d, "checkpoint.enc")).forEach { f -> if (f.exists()) { freed += f.length(); f.delete() } }
        }
        return freed
    }

    fun wipeAll() {
        libDir.deleteRecursively()
        libDir.mkdirs()
        _items.value = emptyList()
        searchIndex.clear()
    }

    fun storageBytes(): Long = libDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun cacheBytes(): Long = libDir.walkTopDown().filter { it.isFile && (it.name == "pcm16k.enc" || it.name == "checkpoint.enc") }.sumOf { it.length() }
}
