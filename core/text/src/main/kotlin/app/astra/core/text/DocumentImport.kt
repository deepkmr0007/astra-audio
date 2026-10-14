package app.astra.core.text

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/**
 * Safe importers for user-selected documents. Imported content is treated strictly as
 * DATA: it is displayed and processed, never interpreted as instructions to the app.
 *
 * Limits defend against oversized files and decompression bombs.
 */
object DocumentImport {

    data class Limits(
        val maxInputBytes: Long = 25L * 1024 * 1024,
        val maxExtractedChars: Int = 5_000_000,
        val maxZipEntries: Int = 2_000,
        val maxZipEntryBytes: Long = 50L * 1024 * 1024,
        val maxCompressionRatio: Long = 200,
    )

    class ImportException(message: String) : Exception(message)

    enum class Kind(val label: String) { TXT("Plain text"), MARKDOWN("Markdown"), DOCX("Word document (.docx)"), PDF("PDF"), UNKNOWN("Unknown") }

    data class Imported(val kind: Kind, val text: String, val warnings: List<String>)

    fun kindOf(fileName: String?, mime: String?): Kind {
        val n = (fileName ?: "").lowercase()
        return when {
            n.endsWith(".docx") || mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> Kind.DOCX
            n.endsWith(".md") || n.endsWith(".markdown") || mime == "text/markdown" -> Kind.MARKDOWN
            n.endsWith(".pdf") || mime == "application/pdf" -> Kind.PDF
            n.endsWith(".txt") || mime?.startsWith("text/") == true -> Kind.TXT
            else -> Kind.UNKNOWN
        }
    }

    fun import(input: InputStream, kind: Kind, limits: Limits = Limits()): Imported = when (kind) {
        Kind.TXT, Kind.MARKDOWN -> {
            val bytes = readCapped(input, limits.maxInputBytes)
            val (text, warn) = decodeText(bytes)
            Imported(kind, capChars(text, limits), listOfNotNull(warn))
        }
        Kind.DOCX -> docx(input, limits)
        Kind.PDF -> throw ImportException("PDF text extraction is not available in this build. Export the PDF as .txt or .docx and import that instead.")
        Kind.UNKNOWN -> throw ImportException("Unsupported file type. Supported: .txt, .md, .docx")
    }

    fun readCapped(input: InputStream, max: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw ImportException("File is larger than the ${max / (1024 * 1024)} MB limit")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** UTF-8 (with/without BOM) or UTF-16 with BOM; otherwise Windows-1252 fallback with a warning. */
    fun decodeText(bytes: ByteArray): Pair<String, String?> {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8) to null
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE) to null
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE) to null
        return try {
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            dec.decode(ByteBuffer.wrap(bytes)).toString() to null
        } catch (e: CharacterCodingException) {
            String(bytes, charset("windows-1252")) to "File is not valid UTF-8; decoded as Windows-1252. Some characters may be wrong."
        }
    }

    private fun capChars(text: String, limits: Limits): String {
        if (text.length > limits.maxExtractedChars) throw ImportException("Document text is longer than ${limits.maxExtractedChars} characters")
        return text.replace("\u0000", "")
    }

    private fun docx(input: InputStream, limits: Limits): Imported {
        val counting = CountingInputStream(input, limits.maxInputBytes)
        var documentXml: String? = null
        var entries = 0
        ZipInputStream(counting).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > limits.maxZipEntries) throw ImportException("Too many entries in .docx")
                val name = entry.name
                if (name.contains("..") || name.startsWith("/")) throw ImportException("Suspicious path inside .docx")
                if (name == "word/document.xml") {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > limits.maxZipEntryBytes) throw ImportException("document.xml is too large")
                        val ratioBase = counting.count.coerceAtLeast(1)
                        if (total > 10L * 1024 * 1024 && total / ratioBase > limits.maxCompressionRatio)
                            throw ImportException("Suspicious compression ratio (possible zip bomb)")
                        out.write(buf, 0, n)
                    }
                    documentXml = String(out.toByteArray(), Charsets.UTF_8)
                }
                zip.closeEntry()
            }
        }
        val xml = documentXml ?: throw ImportException("Not a Word document (word/document.xml missing)")
        return Imported(Kind.DOCX, capChars(docxXmlToText(xml), limits), emptyList())
    }

    /**
     * Extracts visible text from WordprocessingML without an XML library and without resolving
     * entities or DTDs (so XXE is impossible): <w:t> runs, <w:tab/>, <w:br/> and paragraph ends.
     */
    fun docxXmlToText(xml: String): String {
        val sb = StringBuilder()
        val tag = Regex("<(/?)(w:t|w:p|w:tab|w:br|w:cr)(\\s[^>]*)?(/?)>")
        var i = 0
        var inText = false
        var m = tag.find(xml, 0)
        while (m != null) {
            if (inText) sb.append(unescapeXml(xml.substring(i, m.range.first)))
            val closing = m.groupValues[1] == "/"
            val selfClosing = m.groupValues[4] == "/"
            when (m.groupValues[2]) {
                "w:t" -> inText = !closing && !selfClosing
                "w:p" -> if (closing) sb.append('\n')
                "w:tab" -> sb.append('\t')
                "w:br", "w:cr" -> sb.append('\n')
            }
            i = m.range.last + 1
            m = tag.find(xml, i)
        }
        return sb.toString().replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun unescapeXml(s: String): String {
        if (!s.contains('&')) return s
        return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);").replace(s) { m ->
            val v = m.groupValues[1]
            when {
                v == "amp" -> "&"; v == "lt" -> "<"; v == "gt" -> ">"; v == "quot" -> "\""; v == "apos" -> "'"
                v.startsWith("#x") -> v.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: ""
                else -> v.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: ""
            }
        }
    }

    private class CountingInputStream(private val inner: InputStream, private val max: Long) : InputStream() {
        var count = 0L
        override fun read(): Int {
            val b = inner.read()
            if (b >= 0) { count++; check() }
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(b, off, len)
            if (n > 0) { count += n; check() }
            return n
        }
        private fun check() { if (count > max) throw ImportException("File is larger than the ${max / (1024 * 1024)} MB limit") }
        override fun close() = inner.close()
    }
}

/** Removes path separators, control characters and reserved names from user/derived file names. */
object SafeFileName {
    private val RESERVED = setOf("con", "prn", "aux", "nul", "com1", "com2", "lpt1", "lpt2")

    fun sanitize(name: String, fallback: String = "untitled", maxLength: Int = 80): String {
        var n = name.replace(Regex("[\\u0000-\\u001F\\u007F/\\\\:*?\"<>|]"), "_")
            .replace(Regex("\\.{2,}"), ".")
            .trim().trim('.', ' ')
        if (n.isEmpty() || n.lowercase().substringBefore('.') in RESERVED) n = fallback
        if (n.length > maxLength) n = n.take(maxLength).trim()
        return n
    }
}
