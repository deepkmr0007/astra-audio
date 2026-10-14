package app.astra.core.text

import java.text.Normalizer

/** Unicode-aware tokenisation shared by every text tool. Devanagari combining marks (\p{M}) stay inside words. */
object TextBasics {

    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’][\\p{L}\\p{M}]+)*")

    fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    data class Span(val text: String, val start: Int, val end: Int)

    fun words(text: String): List<Span> = WORD.findAll(text).map { Span(it.value, it.range.first, it.range.last + 1) }.toList()

    fun wordList(text: String): List<String> = words(text).map { it.text }

    /**
     * Splits into sentences on Latin terminators, Devanagari danda (।) / double danda (॥),
     * blank lines and bullet starts, keeping exact source offsets so results can point back to the original. */
    fun sentences(text: String): List<Span> {
        val out = ArrayList<Span>()
        var start = 0
        val boundaries = Regex("[.!?।॥]+[\"'”’)]*(?=\\s|$)|\\n\\s*\\n|\\n(?=\\s*[-•*]\\s)")
        for (m in boundaries.findAll(text)) {
            val end = m.range.last + 1
            addSentence(text, start, end, out)
            start = end
        }
        addSentence(text, start, text.length, out)
        return out
    }

    private fun addSentence(text: String, start: Int, end: Int, out: MutableList<Span>) {
        if (start >= end) return
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (e > s && words(text.substring(s, e)).isNotEmpty()) out.add(Span(text.substring(s, e), s, e))
    }

    enum class Script { LATIN, DEVANAGARI, OTHER }

    fun scriptOf(c: Char): Script = when (Character.UnicodeBlock.of(c)) {
        Character.UnicodeBlock.DEVANAGARI, Character.UnicodeBlock.DEVANAGARI_EXTENDED -> Script.DEVANAGARI
        Character.UnicodeBlock.BASIC_LATIN, Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
        Character.UnicodeBlock.LATIN_EXTENDED_A, Character.UnicodeBlock.LATIN_EXTENDED_B -> Script.LATIN
        else -> Script.OTHER
    }

    /** Fraction of letters in each script. Used to describe (never change) the script of a text. */
    fun scriptMix(text: String): Map<Script, Double> {
        val counts = HashMap<Script, Int>()
        var total = 0
        for (c in text) if (Character.isLetter(c)) {
            val sc = scriptOf(c)
            counts[sc] = (counts[sc] ?: 0) + 1
            total++
        }
        if (total == 0) return emptyMap()
        return counts.mapValues { it.value.toDouble() / total }
    }

    fun describeScript(text: String): String {
        val mix = scriptMix(text)
        if (mix.isEmpty()) return "No letters"
        val dev = mix[Script.DEVANAGARI] ?: 0.0
        val lat = mix[Script.LATIN] ?: 0.0
        return when {
            dev > 0.15 && lat > 0.15 -> "Mixed Devanagari + Latin (possible code-switching)"
            dev >= 0.85 -> "Devanagari"
            lat >= 0.85 -> "Latin"
            else -> "Mixed scripts"
        }
    }

    val STOPWORDS_EN = setOf(
        "a", "an", "the", "and", "or", "but", "if", "of", "to", "in", "on", "at", "for", "with", "by", "from",
        "is", "are", "was", "were", "be", "been", "being", "it", "its", "this", "that", "these", "those",
        "i", "you", "he", "she", "we", "they", "me", "him", "her", "us", "them", "my", "your", "our", "their",
        "as", "so", "not", "no", "do", "does", "did", "have", "has", "had", "will", "would", "can", "could",
        "should", "shall", "may", "might", "must", "about", "into", "than", "then", "there", "here", "what",
        "which", "who", "whom", "when", "where", "why", "how", "all", "any", "some", "just", "also", "very",
        "up", "down", "out", "over", "again", "more", "most", "such", "only", "own", "same", "too", "s", "t",
        "um", "uh", "like", "okay", "ok", "yeah", "yes",
    )

    val STOPWORDS_HI = setOf(
        "का", "की", "के", "को", "में", "से", "पर", "है", "हैं", "था", "थी", "थे", "और", "या", "भी", "तो",
        "ही", "यह", "वह", "ये", "वे", "इस", "उस", "इन", "उन", "एक", "कि", "जो", "ने", "लिए", "कर", "करना",
        "हो", "होता", "होती", "होते", "रहा", "रही", "रहे", "गया", "गई", "गए", "नहीं", "न", "हम", "आप", "मैं",
        "तुम", "अपना", "अपनी", "अपने", "जब", "तब", "कुछ", "सब", "बहुत", "अब", "क्या", "कैसे", "क्यों",
    )

    fun isStopword(w: String): Boolean {
        val l = w.lowercase()
        return l in STOPWORDS_EN || l in STOPWORDS_HI
    }
}
