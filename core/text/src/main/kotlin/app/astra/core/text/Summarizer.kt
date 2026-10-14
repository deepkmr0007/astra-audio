package app.astra.core.text

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Extractive summarisation: picks existing sentences verbatim, ranks them with a
 * TextRank-style centrality over TF-IDF vectors, and returns them in original order
 * with their source offsets. It never generates new wording, so it cannot invent facts.
 */
object ExtractiveSummarizer {

    data class Picked(val sentenceIndex: Int, val text: String, val start: Int, val end: Int, val score: Double)

    data class Summary(val sentences: List<Picked>, val totalSentences: Int) {
        val text: String get() = sentences.joinToString(" ") { it.text }
    }

    fun summarize(text: String, maxSentences: Int = 5, ratio: Double = 0.25): Summary {
        val sents = TextBasics.sentences(text)
        if (sents.isEmpty()) return Summary(emptyList(), 0)
        val target = maxOf(1, minOf(maxSentences, Math.ceil(sents.size * ratio).toInt()))
        if (sents.size <= target) {
            return Summary(sents.mapIndexed { i, s -> Picked(i, s.text, s.start, s.end, 1.0) }, sents.size)
        }
        val docs = sents.map { s -> TextBasics.wordList(s.text).map { it.lowercase() }.filter { !TextBasics.isStopword(it) && it.length > 1 } }
        val df = HashMap<String, Int>()
        docs.forEach { d -> d.toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = docs.size.toDouble()
        val vectors = docs.map { d ->
            val tf = HashMap<String, Double>()
            d.forEach { tf[it] = (tf[it] ?: 0.0) + 1.0 }
            tf.mapValues { (w, c) -> c * (ln((1 + n) / (1 + (df[w] ?: 0))) + 1.0) }
        }
        val norms = vectors.map { v -> sqrt(v.values.sumOf { it * it }) }
        val size = sents.size
        val sim = Array(size) { DoubleArray(size) }
        for (i in 0 until size) for (j in i + 1 until size) {
            if (norms[i] == 0.0 || norms[j] == 0.0) continue
            var dot = 0.0
            val (a, b) = if (vectors[i].size < vectors[j].size) vectors[i] to vectors[j] else vectors[j] to vectors[i]
            for ((w, x) in a) dot += x * (b[w] ?: 0.0)
            val s = dot / (norms[i] * norms[j])
            sim[i][j] = s; sim[j][i] = s
        }
        // PageRank power iteration.
        val d = 0.85
        var rank = DoubleArray(size) { 1.0 / size }
        val outSum = DoubleArray(size) { i -> sim[i].sum() }
        repeat(50) {
            val next = DoubleArray(size) { (1 - d) / size }
            for (i in 0 until size) {
                if (outSum[i] == 0.0) continue
                for (j in 0 until size) if (sim[i][j] > 0) next[j] += d * rank[i] * sim[i][j] / outSum[i]
            }
            rank = next
        }
        // Slight lead bias: opening sentences often set context in speech and documents.
        val scored = sents.indices.map { i -> i to rank[i] * (1.0 + 0.15 / (1 + i)) }
        val chosen = scored.sortedByDescending { it.second }.take(target).map { it.first }.sorted()
        return Summary(chosen.map { i -> Picked(i, sents[i].text, sents[i].start, sents[i].end, rank[i]) }, sents.size)
    }

    /** Most characteristic keywords (TF weighted by inverse sentence frequency). */
    fun keywords(text: String, limit: Int = 10): List<Pair<String, Int>> {
        val counts = HashMap<String, Int>()
        for (w in TextBasics.wordList(text)) {
            val l = w.lowercase()
            if (l.length < 3 || TextBasics.isStopword(l) || l.all { it.isDigit() }) continue
            counts[l] = (counts[l] ?: 0) + 1
        }
        return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit).map { it.key to it.value }
    }
}
