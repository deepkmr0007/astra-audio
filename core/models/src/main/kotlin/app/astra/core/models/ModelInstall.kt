package app.astra.core.models

import app.astra.core.common.Hashing
import app.astra.core.common.Json
import app.astra.core.common.bool
import app.astra.core.common.long
import app.astra.core.common.str
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** A model that passed verification and is installed in app-private storage. */
data class InstalledModel(
    val specId: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha1: String,
    val sha256: String,
    val installedMillis: Long,
    val source: String, // "download" or "imported file"
) {
    fun toJson(): String = Json.write(mapOf("spec" to specId, "file" to fileName, "size" to sizeBytes, "sha1" to sha1,
        "sha256" to sha256, "installed" to installedMillis, "source" to source))

    companion object {
        fun fromJson(s: String): InstalledModel? = try {
            val m = Json.parseObject(s)
            InstalledModel(m.str("spec")!!, m.str("file")!!, m.long("size")!!, m.str("sha1")!!, m.str("sha256")!!,
                m.long("installed") ?: 0, m.str("source") ?: "")
        } catch (e: Exception) { null }
    }
}

sealed class VerifyResult {
    data class Ok(val spec: ModelSpec, val sha1: String, val sha256: String, val size: Long) : VerifyResult()
    data class Rejected(val reason: String) : VerifyResult()
}

/**
 * Streams a model into a staging file while hashing it, then verifies everything before the file
 * is moved into place. Rejected files are deleted. No model code is executed during verification:
 * GGML files are data (weights), loaded by the native engine only after they pass.
 */
class ModelInstaller(private val modelsDir: File, private val clock: () -> Long = System::currentTimeMillis) {

    private val manifestDir = File(modelsDir, "manifest")

    init { modelsDir.mkdirs(); manifestDir.mkdirs() }

    fun stagingFile(spec: ModelSpec): File = File(modelsDir, spec.fileName + ".part")
    fun finalFile(spec: ModelSpec): File = File(modelsDir, spec.fileName)

    /**
     * Copies [input] to the staging file with hard size limits, hashing as it goes.
     * Returns the hashes; the caller then calls [verifyAndInstall].
     */
    fun stage(spec: ModelSpec?, input: InputStream, maxBytes: Long, staging: File,
              isCancelled: () -> Boolean, onProgress: (Long) -> Unit): Triple<Long, String, String> {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buf = ByteArray(256 * 1024)
        staging.outputStream().use { out: OutputStream ->
            while (true) {
                if (isCancelled()) throw java.util.concurrent.CancellationException("Cancelled")
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw IOException("File is larger than expected for ${spec?.displayName ?: "a model"}; stopped.")
                sha1.update(buf, 0, n); sha256.update(buf, 0, n)
                out.write(buf, 0, n)
                onProgress(total)
            }
        }
        return Triple(total, Hashing.hex(sha1.digest()), Hashing.hex(sha256.digest()))
    }

    /** Checks checksum, GGML magic and vocabulary size against [expected] (or identifies it by SHA-1 if null). */
    fun verify(expected: ModelSpec?, staging: File, size: Long, sha1: String, sha256: String): VerifyResult {
        val spec = expected ?: ModelRegistry.bySha1(sha1)
            ?: return VerifyResult.Rejected("This file does not match any model in the registry (SHA-1 $sha1). For safety only known models can be installed.")
        if (!Hashing.digestEquals(spec.sha1, sha1)) {
            return VerifyResult.Rejected("Checksum mismatch: expected SHA-1 ${spec.sha1}, got $sha1. The file is corrupted or not the official model.")
        }
        val header = readGgmlHeader(staging) ?: return VerifyResult.Rejected("Not a GGML Whisper model file (bad header).")
        if (header.second != spec.expectedVocab) {
            return VerifyResult.Rejected("Unexpected vocabulary size ${header.second} (expected ${spec.expectedVocab}).")
        }
        return VerifyResult.Ok(spec, sha1, sha256, size)
    }

    fun install(ok: VerifyResult.Ok, staging: File, source: String): InstalledModel {
        val target = finalFile(ok.spec)
        if (target.exists()) target.delete()
        if (!staging.renameTo(target)) throw IOException("Could not move model into place")
        val installed = InstalledModel(ok.spec.id, target.name, ok.size, ok.sha1, ok.sha256, clock(), source)
        File(manifestDir, ok.spec.id + ".json").writeText(installed.toJson())
        return installed
    }

    fun installed(): List<InstalledModel> = manifestDir.listFiles { f -> f.name.endsWith(".json") }
        ?.mapNotNull { InstalledModel.fromJson(it.readText()) }
        ?.filter { File(modelsDir, it.fileName).exists() }
        ?.sortedBy { it.specId } ?: emptyList()

    fun fileOf(model: InstalledModel): File = File(modelsDir, model.fileName)

    /** Re-hashes an installed model and compares with the SHA-256 pinned at install time. */
    fun recheck(model: InstalledModel): Boolean {
        val f = fileOf(model)
        if (!f.exists() || f.length() != model.sizeBytes) return false
        val d = Hashing.fileDigests(f, listOf("SHA-256"))
        return Hashing.digestEquals(d["SHA-256"]!!, model.sha256)
    }

    fun delete(specId: String): Boolean {
        val m = installed().firstOrNull { it.specId == specId } ?: return false
        File(manifestDir, "$specId.json").delete()
        return fileOf(m).delete()
    }

    fun deleteStaging() { modelsDir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() } }

    companion object {
        /** whisper.cpp writes GGML_FILE_MAGIC 0x67676d6c little-endian ("lmgg"), then n_vocab. */
        fun readGgmlHeader(file: File): Pair<Int, Int>? = try {
            file.inputStream().use { s ->
                val b = ByteArray(8)
                if (s.read(b) != 8) return null
                val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                val magic = bb.int
                val vocab = bb.int
                if (magic != 0x67676d6c) null else magic to vocab
            }
        } catch (e: Exception) { null }
    }
}

/** Picks sensible defaults from device RAM and explains trade-offs. Never promises a model will run. */
object ResourceAdvisor {

    data class Device(val totalRamBytes: Long, val availRamBytes: Long, val freeStorageBytes: Long, val cpuCores: Int, val lowRamDevice: Boolean)

    data class Advice(val fits: Boolean, val warning: String?)

    fun canInstall(spec: ModelSpec, d: Device): Advice {
        val need = spec.approxBytes + 200L * 1024 * 1024 // staging headroom
        return if (d.freeStorageBytes < need) Advice(false, "Needs about ${need / (1024 * 1024)} MB free storage; only ${d.freeStorageBytes / (1024 * 1024)} MB available.")
        else Advice(true, null)
    }

    fun canRun(spec: ModelSpec, d: Device): Advice = when {
        d.lowRamDevice && spec.approxRamBytes > 400L * 1000 * 1000 ->
            Advice(false, "This phone reports itself as a low-RAM device. Use tiny or base.")
        spec.approxRamBytes > d.totalRamBytes * 0.45 ->
            Advice(false, "Needs ~${spec.approxRamBytes / 1_000_000} MB RAM; this phone has ${d.totalRamBytes / 1_000_000} MB in total. It would likely be killed by Android. Choose a smaller model.")
        spec.approxRamBytes > d.availRamBytes ->
            Advice(true, "Only ${d.availRamBytes / 1_000_000} MB RAM is free right now; close other apps or expect the system to stop this task.")
        else -> Advice(true, null)
    }

    fun recommendedModelId(d: Device, wantsHindi: Boolean): String {
        val gb = d.totalRamBytes / 1_000_000_000.0
        return when {
            d.lowRamDevice || gb < 3 -> if (wantsHindi) "whisper-tiny" else "whisper-tiny-en"
            gb < 6 -> if (wantsHindi) "whisper-base" else "whisper-base-en"
            gb < 8 -> if (wantsHindi) "whisper-small" else "whisper-small-en"
            else -> "whisper-large-v3-turbo-q5_0"
        }
    }

    fun threads(d: Device): Int = (d.cpuCores - 2).coerceIn(2, 8)
}
