package app.astra.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Random
import javax.crypto.KeyGenerator

class StreamingAeadTest {

    private val wrapper = AesGcmKeyWrapper(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())

    private fun data(n: Int) = ByteArray(n).also { Random(n.toLong()).nextBytes(it) }

    @Test fun roundTripsVariousSizes() {
        for (n in listOf(0, 1, 1023, 4096, 4097, 3 * 4096, 100_000)) {
            val plain = data(n)
            val bos = ByteArrayOutputStream()
            StreamingAead.encryptingStream(bos, wrapper, chunkSize = 4096).use { it.write(plain) }
            val back = StreamingAead.decryptingStream(bos.toByteArray().inputStream(), wrapper).readBytes()
            assertArrayEquals("size $n", plain, back)
        }
    }

    @Test fun ciphertextDoesNotContainPlaintext() {
        val plain = "my private transcript about the bank meeting".repeat(50).toByteArray()
        val ct = StreamingAead.encryptBytes(plain, wrapper)
        assertFalse(String(ct, Charsets.ISO_8859_1).contains("bank meeting"))
    }

    @Test fun detectsBitFlip() {
        val ct = StreamingAead.encryptBytes(data(10_000), wrapper)
        ct[ct.size / 2] = (ct[ct.size / 2].toInt() xor 1).toByte()
        expectIo { StreamingAead.decryptBytes(ct, wrapper) }
    }

    @Test fun detectsTruncationAtChunkBoundary() {
        val bos = ByteArrayOutputStream()
        StreamingAead.encryptingStream(bos, wrapper, chunkSize = 4096).use { it.write(data(3 * 4096 + 10)) }
        val full = bos.toByteArray()
        val headerLen = full.size - (3 * (4096 + 16) + (10 + 16))
        // Drop the final chunk entirely: the remaining last chunk was not sealed as "last".
        val truncated = full.copyOf(headerLen + 3 * (4096 + 16))
        expectIo { StreamingAead.decryptBytes(truncated, wrapper) }
    }

    @Test fun detectsHeaderTampering() {
        val ct = StreamingAead.encryptBytes(data(500), wrapper)
        ct[ct.indexOfFirst { true } + 12] = (ct[12].toInt() xor 0x01).toByte() // chunk size field
        expectIo { StreamingAead.decryptBytes(ct, wrapper) }
    }

    @Test fun wrongMasterKeyFails() {
        val other = AesGcmKeyWrapper(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        val ct = StreamingAead.encryptBytes(data(100), wrapper)
        expectIo { StreamingAead.decryptBytes(ct, other) }
    }

    @Test fun randomAccessReadsMatch() {
        val plain = data(50_000)
        val f = File.createTempFile("astra", ".enc")
        try {
            f.outputStream().use { fos -> StreamingAead.encryptingStream(fos, wrapper, chunkSize = 4096).use { it.write(plain) } }
            StreamingAead.RandomAccessReader(f, wrapper).use { r ->
                assertEquals(plain.size.toLong(), r.plainSize)
                for (pos in listOf(0L, 1L, 4095L, 4096L, 12345L, 49_990L)) {
                    val buf = ByteArray(100)
                    val n = r.readAt(pos, buf, 0, 100)
                    assertArrayEquals(plain.copyOfRange(pos.toInt(), pos.toInt() + n), buf.copyOf(n))
                }
                assertEquals(-1, r.readAt(50_000L, ByteArray(10), 0, 10))
            }
        } finally { f.delete() }
    }

    @Test fun writeFileIsAtomic() {
        val dir = kotlin.io.path.createTempDirectory("astra").toFile()
        try {
            val f = File(dir, "a.enc")
            StreamingAead.writeFile(f, "one".toByteArray(), wrapper)
            StreamingAead.writeFile(f, "two".toByteArray(), wrapper)
            assertEquals("two", String(StreamingAead.readFile(f, wrapper)))
            assertEquals(listOf("a.enc"), dir.list()!!.toList())
        } finally { dir.deleteRecursively() }
    }

    private fun expectIo(block: () -> Unit) {
        try { block(); fail("expected IOException") } catch (e: IOException) { }
    }
}
