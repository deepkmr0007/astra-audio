package app.astra.core.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wraps/unwraps per-file data keys with a master key that never leaves secure hardware
 * (Android Keystore in the app; a software key in unit tests).
 */
interface KeyWrapper {
    /** Returns (iv, wrapped key). */
    fun wrap(dataKey: ByteArray): Pair<ByteArray, ByteArray>
    fun unwrap(iv: ByteArray, wrapped: ByteArray): ByteArray
}

/** AES-GCM key wrapping with a provided javax SecretKey (used by tests; the app uses a Keystore key). */
class AesGcmKeyWrapper(private val masterKey: javax.crypto.SecretKey) : KeyWrapper {
    override fun wrap(dataKey: ByteArray): Pair<ByteArray, ByteArray> {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, masterKey)
        return c.iv to c.doFinal(dataKey)
    }

    override fun unwrap(iv: ByteArray, wrapped: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(128, iv))
        return c.doFinal(wrapped)
    }
}

/**
 * Chunked authenticated encryption for files of any size (audio, transcripts, metadata).
 *
 * Construction (no custom primitives — only AES-GCM from the platform):
 *  - Envelope: a fresh random 256-bit data key per file, wrapped by the master key.
 *  - STREAM segmentation (Hoang–Reyhanitabar–Rogaway–Vizár): chunk nonce =
 *    7-byte random prefix ‖ 4-byte chunk counter ‖ 1-byte last-chunk flag.
 *  - The full header is authenticated as AAD of every chunk.
 * This detects modification, reordering, truncation and extension, and allows random-access
 * reads (needed to play or decode encrypted audio without writing plaintext to disk).
 */
object StreamingAead {

    private val MAGIC = "ASTRAEN1".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    const val DEFAULT_CHUNK = 64 * 1024
    private const val TAG = 16
    private const val PREFIX = 7
    private val random = SecureRandom()

    class Header(val chunkSize: Int, val wrapIv: ByteArray, val wrappedKey: ByteArray, val noncePrefix: ByteArray, val bytes: ByteArray)

    private fun buildHeader(chunkSize: Int, wrapIv: ByteArray, wrappedKey: ByteArray, prefix: ByteArray): Header {
        val bb = ByteBuffer.allocate(MAGIC.size + 1 + 4 + 1 + wrapIv.size + 2 + wrappedKey.size + PREFIX)
        bb.put(MAGIC).put(VERSION).putInt(chunkSize)
        bb.put(wrapIv.size.toByte()).put(wrapIv)
        bb.putShort(wrappedKey.size.toShort()).put(wrappedKey)
        bb.put(prefix)
        return Header(chunkSize, wrapIv, wrappedKey, prefix, bb.array())
    }

    fun readHeader(input: InputStream): Header {
        val din = DataInputStream(input)
        val magic = ByteArray(MAGIC.size); din.readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw IOException("Not an encrypted ASTRA file")
        if (din.readByte() != VERSION) throw IOException("Unsupported encryption version")
        val chunkSize = din.readInt()
        if (chunkSize !in 1024..(16 * 1024 * 1024)) throw IOException("Bad chunk size")
        val ivLen = din.readUnsignedByte()
        if (ivLen !in 12..16) throw IOException("Bad IV length")
        val iv = ByteArray(ivLen); din.readFully(iv)
        val wkLen = din.readUnsignedShort()
        if (wkLen !in 16..512) throw IOException("Bad wrapped key length")
        val wk = ByteArray(wkLen); din.readFully(wk)
        val prefix = ByteArray(PREFIX); din.readFully(prefix)
        return buildHeader(chunkSize, iv, wk, prefix)
    }

    private fun nonce(prefix: ByteArray, counter: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(counter).put(if (last) 1 else 0).array()

    private fun cipher(mode: Int, key: ByteArray, h: Header, counter: Int, last: Boolean): Cipher {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, nonce(h.noncePrefix, counter, last)))
        c.updateAAD(h.bytes)
        return c
    }

    /** Returns an OutputStream that encrypts everything written to [out]. Close it to finalise. */
    fun encryptingStream(out: OutputStream, wrapper: KeyWrapper, chunkSize: Int = DEFAULT_CHUNK): OutputStream {
        val dataKey = ByteArray(32).also { random.nextBytes(it) }
        val (iv, wrapped) = wrapper.wrap(dataKey)
        val prefix = ByteArray(PREFIX).also { random.nextBytes(it) }
        val header = buildHeader(chunkSize, iv, wrapped, prefix)
        out.write(header.bytes)
        return EncryptingOutputStream(out, dataKey, header)
    }

    private class EncryptingOutputStream(out: OutputStream, private val key: ByteArray, private val h: Header) : FilterOutputStream(out) {
        private val buf = ByteArray(h.chunkSize)
        private var len = 0
        private var counter = 0
        private var closed = false

        override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }

        override fun write(b: ByteArray, off: Int, n: Int) {
            check(!closed) { "Stream closed" }
            var o = off; var r = n
            while (r > 0) {
                // Flush a full chunk only when more data follows, so the final chunk is always marked last.
                if (len == buf.size) flushChunk(last = false)
                val k = minOf(r, buf.size - len)
                System.arraycopy(b, o, buf, len, k)
                len += k; o += k; r -= k
            }
        }

        private fun flushChunk(last: Boolean) {
            if (counter == Int.MAX_VALUE) throw IOException("File too large")
            val c = cipher(Cipher.ENCRYPT_MODE, key, h, counter, last)
            out.write(c.doFinal(buf, 0, len))
            counter++
            len = 0
        }

        override fun flush() { out.flush() }

        override fun close() {
            if (closed) return
            closed = true
            try {
                flushChunk(last = true)
                out.flush()
            } finally {
                key.fill(0)
                out.close()
            }
        }
    }

    /** Sequential decrypting stream. Throws IOException on any tampering or truncation. */
    fun decryptingStream(input: InputStream, wrapper: KeyWrapper): InputStream {
        val h = readHeader(input)
        val key = try { wrapper.unwrap(h.wrapIv, h.wrappedKey) } catch (e: GeneralSecurityException) {
            throw IOException("Cannot unlock file key (wrong device or reset keystore)", e)
        }
        return DecryptingInputStream(input, key, h)
    }

    private class DecryptingInputStream(private val input: InputStream, private val key: ByteArray, private val h: Header) : InputStream() {
        private val cipherChunk = h.chunkSize + TAG
        private var plain = ByteArray(0)
        private var pos = 0
        private var counter = 0
        private var finished = false
        private var pending: ByteArray? = null

        private fun readChunk(): ByteArray? {
            val b = ByteArray(cipherChunk)
            var n = 0
            while (n < cipherChunk) {
                val r = input.read(b, n, cipherChunk - n)
                if (r < 0) break
                n += r
            }
            return if (n == 0) null else b.copyOf(n)
        }

        private fun fill(): Boolean {
            if (finished) return false
            val current = pending ?: readChunk() ?: throw IOException("Encrypted file is truncated")
            val next = if (current.size == cipherChunk) readChunk() else null
            val last = next == null
            pending = next
            if (current.size < TAG) throw IOException("Encrypted file is truncated")
            plain = try {
                cipher(Cipher.DECRYPT_MODE, key, h, counter, last).doFinal(current)
            } catch (e: GeneralSecurityException) {
                throw IOException("Encrypted file is damaged or was modified", e)
            }
            counter++
            pos = 0
            if (last) finished = true
            return true
        }

        override fun read(): Int {
            while (pos >= plain.size) { if (!fill()) return -1 }
            return plain[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= plain.size) { if (!fill()) return -1 }
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() { key.fill(0); input.close() }
    }

    // ---------------------------------------------------------------- convenience

    fun encryptBytes(data: ByteArray, wrapper: KeyWrapper): ByteArray {
        val bos = ByteArrayOutputStream()
        encryptingStream(bos, wrapper).use { it.write(data) }
        return bos.toByteArray()
    }

    fun decryptBytes(data: ByteArray, wrapper: KeyWrapper): ByteArray =
        decryptingStream(ByteArrayInputStream(data), wrapper).use { it.readBytes() }

    fun writeFile(file: File, data: ByteArray, wrapper: KeyWrapper) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { fos -> encryptingStream(fos, wrapper).use { it.write(data) } }
        if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw IOException("Could not save file") }
    }

    fun readFile(file: File, wrapper: KeyWrapper): ByteArray = file.inputStream().use { decryptingStream(it, wrapper).use { s -> s.readBytes() } }

    /**
     * Random-access reader over an encrypted file (for MediaDataSource-style playback/decoding).
     * Every chunk read is authenticated; plaintext exists only in memory.
     */
    class RandomAccessReader(private val file: File, wrapper: KeyWrapper) : AutoCloseable {
        private val raf = RandomAccessFile(file, "r")
        private val header: Header = file.inputStream().use { readHeader(it) }
        private val key: ByteArray = wrapper.unwrap(header.wrapIv, header.wrappedKey)
        private val cipherChunk = header.chunkSize + TAG
        private val body = raf.length() - header.bytes.size
        private val chunkCount: Int = if (body <= 0) 0 else ((body + cipherChunk - 1) / cipherChunk).toInt()
        val plainSize: Long = if (chunkCount == 0) 0 else (chunkCount - 1).toLong() * header.chunkSize + (body - (chunkCount - 1).toLong() * cipherChunk - TAG)

        private var cachedIndex = -1
        private var cached = ByteArray(0)

        init {
            if (chunkCount == 0) throw IOException("Encrypted file is truncated")
        }

        private fun chunk(i: Int): ByteArray {
            if (i == cachedIndex) return cached
            val offset = header.bytes.size + i.toLong() * cipherChunk
            val len = minOf(cipherChunk.toLong(), raf.length() - offset).toInt()
            val ct = ByteArray(len)
            raf.seek(offset); raf.readFully(ct)
            cached = try {
                cipher(Cipher.DECRYPT_MODE, key, header, i, i == chunkCount - 1).doFinal(ct)
            } catch (e: GeneralSecurityException) {
                throw IOException("Encrypted file is damaged or was modified", e)
            }
            cachedIndex = i
            return cached
        }

        /** Reads up to [size] bytes at plaintext [position]; returns -1 at end of file. */
        @Synchronized
        fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= plainSize) return -1
            var p = position; var o = offset; var remaining = minOf(size.toLong(), plainSize - position).toInt()
            var total = 0
            while (remaining > 0) {
                val idx = (p / header.chunkSize).toInt()
                val inChunk = (p % header.chunkSize).toInt()
                val data = chunk(idx)
                val n = minOf(remaining, data.size - inChunk)
                if (n <= 0) break
                System.arraycopy(data, inChunk, buffer, o, n)
                p += n; o += n; remaining -= n; total += n
            }
            return total
        }

        override fun close() { key.fill(0); cached.fill(0); raf.close() }
    }
}
