package app.astra.core.common

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Streaming hash helpers built on the platform MessageDigest (no custom crypto). */
object Hashing {

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    /** Computes several digests in one pass. Algorithms: "SHA-1", "SHA-256". */
    fun digests(input: InputStream, algorithms: List<String>, onBytes: ((Long) -> Unit)? = null,
                isCancelled: () -> Boolean = { false }): Map<String, String> {
        val mds = algorithms.associateWith { MessageDigest.getInstance(it) }
        val buf = ByteArray(256 * 1024)
        var total = 0L
        while (true) {
            if (isCancelled()) throw java.util.concurrent.CancellationException("Hashing cancelled")
            val n = input.read(buf)
            if (n < 0) break
            for (md in mds.values) md.update(buf, 0, n)
            total += n
            onBytes?.invoke(total)
        }
        return mds.mapValues { hex(it.value.digest()) }
    }

    fun fileDigests(file: File, algorithms: List<String> = listOf("SHA-1", "SHA-256")): Map<String, String> =
        file.inputStream().use { digests(it, algorithms) }

    /** Constant-time comparison of two hex digests (case-insensitive). */
    fun digestEquals(a: String, b: String): Boolean {
        val x = a.lowercase().toByteArray()
        val y = b.lowercase().toByteArray()
        return MessageDigest.isEqual(x, y)
    }
}
