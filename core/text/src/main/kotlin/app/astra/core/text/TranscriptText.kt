package app.astra.core.text

/**
 * Transcript-specific text processing:
 *  - [CleanTranscript] derives a "clean" reading view (fillers and stutters removed) WITHOUT
 *    touching the verbatim text it was derived from.
 *  - [Glossary] holds the user's custom vocabulary. It biases decoding through the engine's
 *    initial prompt and offers exact, user-defined "heard as → write as" corrections, each
 *    recorded with provenance. It never rewrites anything that the user did not define.
 */
object CleanTranscript {

    private val FILLERS = setOf(
        "um", "umm", "uh", "uhh", "uhm", "er", "erm", "ah", "hmm", "hm", "mm", "mhm",
        "अं", "अम्म", "उम्म", "हम्म", "आं", "एं",
    )

    /** Returns the clean version and how many filler / repetition tokens were removed. */
    fun clean(text: String): Pair<String, Int> {
        var removed = 0
        // Fillers, optionally followed by a comma.
        val fillerRegex = Regex("(?i)(?<![\\p{L}\\p{M}])(" + FILLERS.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{M}])[,]?\\s*")
        var t = fillerRegex.replace(text) { removed++; "" }
        // Immediate stutter repetitions: "I I think" -> "I think" (up to 3 repeats).
        val rep = Regex("(?i)(?<![\\p{L}\\p{M}])([\\p{L}\\p{M}']+)(?:[\\s,]+\\1){1,3}(?![\\p{L}\\p{M}])")
        t = rep.replace(t) { m ->
            removed += m.value.trim().split(Regex("[\\s,]+")).size - 1
            m.groupValues[1]
        }
        t = t.replace(Regex(" {2,}"), " ").replace(Regex(" +([,.!?।])"), "$1").trim()
        return t to removed
    }
}

class Glossary(entries: List<Entry> = emptyList()) {

    /** [term] is how the word should be written; [heardAs] are variants the engine tends to produce. */
    data class Entry(val term: String, val heardAs: List<String> = emptyList())

    data class Correction(val start: Int, val end: Int, val original: String, val replacement: String, val entryTerm: String)

    val entries: List<Entry> = entries.filter { it.term.isNotBlank() }

    /**
     * Text for the engine's initial prompt. Whisper uses it as preceding context, which biases
     * spelling of names and jargon. Limited to ~200 characters to stay well inside the model's
     * prompt budget (whisper.cpp uses at most n_text_ctx/2 tokens).
     */
    fun initialPrompt(maxChars: Int = 200): String? {
        if (entries.isEmpty()) return null
        val sb = StringBuilder("Glossary: ")
        for (e in entries) {
            if (sb.length + e.term.length + 2 > maxChars) break
            if (sb.length > 10) sb.append(", ")
            sb.append(e.term)
        }
        return sb.append('.').toString()
    }

    /** Finds user-defined variants in [text]; the caller applies them to the corrected layer only. */
    fun findCorrections(text: String): List<Correction> {
        val out = ArrayList<Correction>()
        for (e in entries) for (variant in e.heardAs.filter { it.isNotBlank() }) {
            val re = Regex("(?i)(?<![\\p{L}\\p{M}\\p{N}])" + Regex.escape(variant.trim()) + "(?![\\p{L}\\p{M}\\p{N}])")
            for (m in re.findAll(text)) {
                if (m.value == e.term) continue
                out.add(Correction(m.range.first, m.range.last + 1, m.value, e.term, e.term))
            }
        }
        // Drop overlaps, keeping the earliest/longest.
        val sorted = out.sortedWith(compareBy<Correction> { it.start }.thenByDescending { it.end - it.start })
        val result = ArrayList<Correction>()
        var cursor = -1
        for (c in sorted) if (c.start >= cursor) { result.add(c); cursor = c.end }
        return result
    }

    fun apply(text: String, corrections: List<Correction>): String {
        val sb = StringBuilder()
        var cursor = 0
        for (c in corrections.sortedBy { it.start }) {
            if (c.start < cursor) continue
            sb.append(text, cursor, c.start).append(c.replacement)
            cursor = c.end
        }
        return sb.append(text.substring(cursor)).toString()
    }

    fun toLines(): String = entries.joinToString("\n") { e ->
        if (e.heardAs.isEmpty()) e.term else e.term + " = " + e.heardAs.joinToString(" | ")
    }

    companion object {
        /** One entry per line: `Term` or `Term = variant one | variant two`. */
        fun parse(lines: String): Glossary = Glossary(lines.lines().mapNotNull { line ->
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#")) return@mapNotNull null
            val parts = l.split("=", limit = 2)
            val term = parts[0].trim()
            val variants = if (parts.size > 1) parts[1].split("|").map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
            if (term.isEmpty()) null else Entry(term, variants)
        })
    }
}
