package app.astra.core.text

/**
 * Word Error Rate and Character Error Rate with a visible alignment.
 *
 * Caveats (also shown in the benchmark report):
 *  - Scores depend heavily on normalisation; the options used are recorded with every result.
 *  - CER is computed over Unicode code points after NFC; Devanagari conjuncts span several code points.
 *  - Tokenisation is whitespace/punctuation based; scripts without spaces (e.g. Chinese) need CER.
 */
object ErrorRate {

    data class Normalization(
        val lowercase: Boolean = true,
        val stripPunctuation: Boolean = true,
        val collapseWhitespace: Boolean = true,
        val nfc: Boolean = true,
    ) {
        fun describe(): String = buildList {
            if (nfc) add("NFC")
            if (lowercase) add("lowercase")
            if (stripPunctuation) add("punctuation removed")
            if (collapseWhitespace) add("whitespace collapsed")
        }.joinToString(", ").ifEmpty { "none" }
    }

    enum class Op { HIT, SUB, DEL, INS }

    data class AlignedPair(val op: Op, val ref: String?, val hyp: String?)

    data class Result(
        val substitutions: Int,
        val deletions: Int,
        val insertions: Int,
        val hits: Int,
        val referenceLength: Int,
        val alignment: List<AlignedPair>,
    ) {
        val errors: Int get() = substitutions + deletions + insertions
        /** Error rate; can exceed 1.0 when there are many insertions. NaN when the reference is empty. */
        val rate: Double get() = if (referenceLength == 0) Double.NaN else errors.toDouble() / referenceLength
    }

    fun normalize(text: String, n: Normalization): String {
        var t = text
        if (n.nfc) t = TextBasics.nfc(t)
        if (n.lowercase) t = t.lowercase()
        // Keep combining marks (\p{M}) — they are part of Devanagari letters, not punctuation.
        if (n.stripPunctuation) t = t.replace(Regex("[\\p{P}\\p{S}]"), " ")
        if (n.collapseWhitespace) t = t.replace(Regex("\\s+"), " ").trim()
        return t
    }

    fun wer(reference: String, hypothesis: String, n: Normalization = Normalization()): Result {
        val r = normalize(reference, n).split(' ').filter { it.isNotEmpty() }
        val h = normalize(hypothesis, n).split(' ').filter { it.isNotEmpty() }
        return align(r, h)
    }

    fun cer(reference: String, hypothesis: String, n: Normalization = Normalization()): Result {
        // CER ignores spaces between words so spacing differences are not double counted.
        val r = codePoints(normalize(reference, n).replace(" ", ""))
        val h = codePoints(normalize(hypothesis, n).replace(" ", ""))
        return align(r, h)
    }

    private fun codePoints(s: String): List<String> {
        val out = ArrayList<String>(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            out.add(String(Character.toChars(cp)))
            i += Character.charCount(cp)
        }
        return out
    }

    /** Levenshtein alignment with back-trace. Memory O(n*m) bytes; inputs are capped for safety. */
    fun align(ref: List<String>, hyp: List<String>): Result {
        require(ref.size.toLong() * hyp.size.toLong() <= 60_000_000L) { "Input too long to align; split it into smaller parts" }
        val n = ref.size
        val m = hyp.size
        val dist = Array(n + 1) { IntArray(m + 1) }
        val back = Array(n + 1) { ByteArray(m + 1) } // 0 diag, 1 up (del), 2 left (ins)
        for (i in 1..n) { dist[i][0] = i; back[i][0] = 1 }
        for (j in 1..m) { dist[0][j] = j; back[0][j] = 2 }
        for (i in 1..n) {
            for (j in 1..m) {
                val cost = if (ref[i - 1] == hyp[j - 1]) 0 else 1
                var best = dist[i - 1][j - 1] + cost
                var dir: Byte = 0
                if (dist[i - 1][j] + 1 < best) { best = dist[i - 1][j] + 1; dir = 1 }
                if (dist[i][j - 1] + 1 < best) { best = dist[i][j - 1] + 1; dir = 2 }
                dist[i][j] = best
                back[i][j] = dir
            }
        }
        val pairs = ArrayList<AlignedPair>()
        var i = n; var j = m
        var s = 0; var d = 0; var ins = 0; var hits = 0
        while (i > 0 || j > 0) {
            when (if (i == 0) 2.toByte() else if (j == 0) 1.toByte() else back[i][j]) {
                0.toByte() -> {
                    if (ref[i - 1] == hyp[j - 1]) { hits++; pairs.add(AlignedPair(Op.HIT, ref[i - 1], hyp[j - 1])) }
                    else { s++; pairs.add(AlignedPair(Op.SUB, ref[i - 1], hyp[j - 1])) }
                    i--; j--
                }
                1.toByte() -> { d++; pairs.add(AlignedPair(Op.DEL, ref[i - 1], null)); i-- }
                else -> { ins++; pairs.add(AlignedPair(Op.INS, null, hyp[j - 1])); j-- }
            }
        }
        pairs.reverse()
        return Result(s, d, ins, hits, n, pairs)
    }

    /** Aggregates several utterances: total errors / total reference length (not a mean of rates). */
    fun corpus(results: List<Result>): Double {
        val ref = results.sumOf { it.referenceLength }
        if (ref == 0) return Double.NaN
        return results.sumOf { it.errors }.toDouble() / ref
    }
}
