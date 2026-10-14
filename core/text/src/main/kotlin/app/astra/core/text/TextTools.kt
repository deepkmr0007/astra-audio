package app.astra.core.text

/**
 * Deterministic, fully offline text tools. Every result is either
 *  - EXTRACTED: verbatim source text with its location, or
 *  - SUGGESTED: a proposed change the user must review.
 * Nothing here generates new facts.
 */

enum class Provenance { EXTRACTED, SUGGESTED }

// ------------------------------------------------------------------ action items

object ActionItemExtractor {

    data class Item(val text: String, val start: Int, val end: Int, val trigger: String) {
        val provenance = Provenance.EXTRACTED
    }

    private val EN = listOf(
        "need to", "needs to", "have to", "has to", "must", "should", "will send", "will share", "will call",
        "will check", "will prepare", "will submit", "i'll", "we'll", "let's", "let us", "todo", "to-do", "to do:",
        "action item", "action:", "follow up", "follow-up", "deadline", "due by", "by tomorrow", "by monday",
        "by tuesday", "by wednesday", "by thursday", "by friday", "by end of", "eod", "remind", "make sure",
        "please send", "please share", "please check", "assign", "assigned to",
    )
    private val HI = listOf(
        "करना है", "करनी है", "करने हैं", "करना होगा", "करनी होगी", "करना चाहिए", "करें", "कीजिए", "भेजना है",
        "भेज देना", "भेजें", "जमा करना", "याद दिला", "कल तक", "सोमवार तक", "शुक्रवार तक", "ज़रूर", "जरूर",
        "जल्दी", "अंतिम तिथि", "डेडलाइन", "फॉलो अप",
    )

    fun extract(text: String): List<Item> {
        val out = ArrayList<Item>()
        for (s in TextBasics.sentences(text)) {
            val low = s.text.lowercase()
            val trigger = (EN + HI).firstOrNull { low.contains(it) } ?: continue
            out.add(Item(s.text, s.start, s.end, trigger))
        }
        return out
    }
}

// ------------------------------------------------------------------ proofreading

object Proofreader {

    data class Issue(val start: Int, val end: Int, val message: String, val replacement: String?) {
        val provenance = Provenance.SUGGESTED
    }

    private val COMMON_MISSPELLINGS = mapOf(
        "teh" to "the", "recieve" to "receive", "recieved" to "received", "seperate" to "separate",
        "definately" to "definitely", "occured" to "occurred", "untill" to "until", "wich" to "which",
        "thier" to "their", "alot" to "a lot", "goverment" to "government", "enviroment" to "environment",
        "accomodate" to "accommodate", "acheive" to "achieve", "beleive" to "believe", "calender" to "calendar",
        "commitee" to "committee", "existance" to "existence", "occassion" to "occasion", "publically" to "publicly",
        "tommorow" to "tomorrow", "tommorrow" to "tomorrow", "wierd" to "weird", "begining" to "beginning",
    )

    fun check(text: String): List<Issue> {
        val issues = ArrayList<Issue>()
        Regex(" {2,}").findAll(text).forEach { issues.add(Issue(it.range.first, it.range.last + 1, "Multiple spaces", " ")) }
        Regex(" +([,.;:!?।])").findAll(text).forEach {
            val p = it.groupValues[1]
            val next = text.getOrNull(it.range.last + 1)
            val rep = if (p in ",;:" && next != null && Character.isLetter(next)) "$p " else p
            issues.add(Issue(it.range.first, it.range.last + 1, "Space before punctuation", rep))
        }
        Regex("([,;:])(?=[\\p{L}])").findAll(text).forEach {
            issues.add(Issue(it.range.first, it.range.last + 1, "Missing space after punctuation", it.groupValues[1] + " "))
        }
        // Repeated words ("the the"), Unicode-aware.
        Regex("(?i)(?<![\\p{L}\\p{M}])([\\p{L}\\p{M}]+)(\\s+)\\1(?![\\p{L}\\p{M}])").findAll(text).forEach {
            issues.add(Issue(it.range.first, it.range.last + 1, "Repeated word \"${it.groupValues[1]}\"", it.groupValues[1]))
        }
        for (w in TextBasics.words(text)) {
            val fix = COMMON_MISSPELLINGS[w.text.lowercase()] ?: continue
            val rep = if (w.text.first().isUpperCase()) fix.replaceFirstChar { it.uppercaseChar() } else fix
            issues.add(Issue(w.start, w.end, "Possible misspelling", rep))
        }
        for (s in TextBasics.sentences(text)) {
            val first = s.text.first()
            if (TextBasics.scriptOf(first) == TextBasics.Script.LATIN && first.isLowerCase()) {
                issues.add(Issue(s.start, s.start + 1, "Sentence starts with a lowercase letter", first.uppercaseChar().toString()))
            }
            val wc = TextBasics.words(s.text).size
            if (wc > 40) issues.add(Issue(s.start, s.end, "Long sentence ($wc words) — consider splitting", null))
        }
        checkBalance(text, '(', ')', issues)
        checkBalance(text, '[', ']', issues)
        if (text.count { it == '"' } % 2 != 0) issues.add(Issue(0, 0, "Unbalanced double quotes", null))
        return issues.sortedBy { it.start }
    }

    private fun checkBalance(text: String, open: Char, close: Char, issues: MutableList<Issue>) {
        var depth = 0
        for ((i, c) in text.withIndex()) {
            if (c == open) depth++
            if (c == close) { depth--; if (depth < 0) { issues.add(Issue(i, i + 1, "Unmatched '$close'", null)); depth = 0 } }
        }
        if (depth > 0) issues.add(Issue(text.length, text.length, "Unclosed '$open'", null))
    }

    /** Applies all non-overlapping issues that have a replacement. Returns the suggested text. */
    fun applyAll(text: String, issues: List<Issue>): String {
        val sb = StringBuilder()
        var cursor = 0
        for (i in issues.filter { it.replacement != null && it.end > it.start }.sortedBy { it.start }) {
            if (i.start < cursor) continue
            sb.append(text, cursor, i.start).append(i.replacement)
            cursor = i.end
        }
        sb.append(text.substring(cursor))
        return sb.toString()
    }
}

// ------------------------------------------------------------------ formatting

object Formatter {
    fun tidyWhitespace(text: String): String =
        text.lines().joinToString("\n") { it.replace(Regex("[ \\t]+"), " ").trim() }.replace(Regex("\\n{3,}"), "\n\n").trim()

    fun paragraphs(text: String, sentencesPerParagraph: Int = 4): String =
        TextBasics.sentences(text).chunked(sentencesPerParagraph.coerceAtLeast(1))
            .joinToString("\n\n") { group -> group.joinToString(" ") { it.text } }

    fun bullets(text: String): String = TextBasics.sentences(text).joinToString("\n") { "• " + it.text }

    fun numbered(text: String): String = TextBasics.sentences(text).mapIndexed { i, s -> "${i + 1}. ${s.text}" }.joinToString("\n")

    /** Capitalises the first Latin letter of each sentence; other scripts are left untouched. */
    fun sentenceCase(text: String): String {
        val sb = StringBuilder(text)
        for (s in TextBasics.sentences(text)) {
            val c = sb[s.start]
            if (TextBasics.scriptOf(c) == TextBasics.Script.LATIN) sb.setCharAt(s.start, c.uppercaseChar())
        }
        return sb.toString()
    }
}

// ------------------------------------------------------------------ classification

object Classifier {
    data class Category(val name: String, val keywords: List<String>)
    data class Score(val category: String, val score: Int, val matched: List<String>)

    val DEFAULTS = listOf(
        Category("Meeting / discussion", listOf("meeting", "agenda", "minutes", "discussed", "attendees", "बैठक", "चर्चा")),
        Category("Tasks / follow-ups", listOf("deadline", "follow up", "todo", "action", "assign", "due", "करना है", "जमा")),
        Category("Finance", listOf("payment", "invoice", "budget", "amount", "rupees", "₹", "loan", "account", "भुगतान", "राशि", "बजट")),
        Category("Legal / official", listOf("court", "petition", "affidavit", "order", "notice", "section", "act", "हलफनामा", "न्यायालय", "आदेश")),
        Category("Technical", listOf("server", "app", "code", "bug", "release", "model", "api", "database", "android")),
        Category("Personal", listOf("family", "birthday", "friend", "home", "परिवार", "घर", "दोस्त")),
        Category("Health / medical", listOf("doctor", "hospital", "medicine", "appointment", "डॉक्टर", "अस्पताल", "दवा")),
        Category("Education / study", listOf("exam", "syllabus", "lecture", "chapter", "study", "परीक्षा", "पाठ्यक्रम")),
    )

    /** Transparent keyword scoring — every score lists the words that produced it. */
    fun classify(text: String, categories: List<Category> = DEFAULTS): List<Score> {
        val low = text.lowercase()
        return categories.map { c ->
            val matched = c.keywords.filter { k -> Regex("(?<![\\p{L}\\p{M}])" + Regex.escape(k.lowercase()) + "(?![\\p{L}\\p{M}])").containsMatchIn(low) }
            val score = matched.sumOf { k -> Regex(Regex.escape(k.lowercase())).findAll(low).count() }
            Score(c.name, score, matched)
        }.filter { it.score > 0 }.sortedByDescending { it.score }
    }
}

// ------------------------------------------------------------------ comparison

object TextCompare {
    enum class Kind { SAME, ADDED, REMOVED }
    data class Piece(val kind: Kind, val text: String)
    data class Diff(val pieces: List<Piece>, val similarity: Double)

    /** Word-level diff using LCS. Inputs are capped to keep memory bounded on phones. */
    fun diff(a: String, b: String, maxWords: Int = 4000): Diff {
        val x = TextBasics.wordList(a).take(maxWords)
        val y = TextBasics.wordList(b).take(maxWords)
        val n = x.size; val m = y.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            lcs[i][j] = if (x[i] == y[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        val pieces = ArrayList<Piece>()
        var i = 0; var j = 0
        fun push(k: Kind, w: String) {
            val last = pieces.lastOrNull()
            if (last != null && last.kind == k) pieces[pieces.size - 1] = Piece(k, last.text + " " + w) else pieces.add(Piece(k, w))
        }
        while (i < n && j < m) {
            when {
                x[i] == y[j] -> { push(Kind.SAME, x[i]); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { push(Kind.REMOVED, x[i]); i++ }
                else -> { push(Kind.ADDED, y[j]); j++ }
            }
        }
        while (i < n) { push(Kind.REMOVED, x[i]); i++ }
        while (j < m) { push(Kind.ADDED, y[j]); j++ }
        val sim = if (n + m == 0) 1.0 else 2.0 * lcs[0][0] / (n + m)
        return Diff(pieces, sim)
    }
}

// ------------------------------------------------------------------ analysis

object TextAnalysis {
    data class Stats(
        val characters: Int, val words: Int, val sentences: Int, val avgWordsPerSentence: Double,
        val readingMinutes: Double, val speakingMinutes: Double, val script: String,
        val keywords: List<Pair<String, Int>>,
    )

    fun analyze(text: String): Stats {
        val words = TextBasics.words(text).size
        val sentences = TextBasics.sentences(text).size
        return Stats(
            characters = text.length,
            words = words,
            sentences = sentences,
            avgWordsPerSentence = if (sentences == 0) 0.0 else words.toDouble() / sentences,
            readingMinutes = words / 200.0,
            speakingMinutes = words / 130.0,
            script = TextBasics.describeScript(text),
            keywords = ExtractiveSummarizer.keywords(text, 8),
        )
    }
}

// ------------------------------------------------------------------ auto title

/**
 * Names an untitled recording from its own words: the first sentence that carries content (not a
 * greeting or filler), shortened at a word boundary. Purely extractive — it never invents a title.
 */
object AutoTitle {
    private val GREETING = Regex("(?i)^(hi|hello|hey|ok|okay|so|um+|uh+|good (morning|afternoon|evening)|namaste|नमस्ते|हेलो|अच्छा|तो)\\b[ ,.!]*")

    fun suggest(text: String, maxChars: Int = 48): String? {
        for (s in TextBasics.sentences(text)) {
            val cleaned = CleanTranscript.clean(s.text).first.replace(GREETING, "").trim().trimEnd('.', '।', '!', '?', ',')
            val words = TextBasics.wordList(cleaned)
            if (words.size < 3 || words.count { !TextBasics.isStopword(it) } < 2) continue
            if (cleaned.length <= maxChars) return cleaned.replaceFirstChar { it.uppercaseChar() }
            val cut = cleaned.substring(0, maxChars).substringBeforeLast(' ').trimEnd(',', ';', ':')
            return cut.replaceFirstChar { it.uppercaseChar() } + "…"
        }
        return null
    }
}
