package app.astra.core.text

/**
 * On-device inverted index over user-selected transcripts and notes only.
 *
 * The index lives in memory and is rebuilt from the encrypted library after unlock, so no
 * plaintext index is ever written to disk. Supports:
 *  - plain terms (all must match),
 *  - "quoted phrases" (exact consecutive words),
 *  - prefix terms ending in `*`,
 *  - date range and tag filters.
 * Matching is case-insensitive and NFC-normalised; scripts are never transliterated.
 */
class SearchIndex {

    data class Doc(val id: String, val title: String, val text: String, val dateMillis: Long, val tags: Set<String> = emptySet())

    data class Hit(val docId: String, val title: String, val score: Double, val snippet: String, val firstMatchOffset: Int, val matchCount: Int)

    data class Query(
        val text: String,
        val fromMillis: Long? = null,
        val toMillis: Long? = null,
        val tag: String? = null,
    )

    private class Posting(val positions: MutableList<Int> = ArrayList(), val offsets: MutableList<Int> = ArrayList())

    private val docs = HashMap<String, Doc>()
    private val index = HashMap<String, HashMap<String, Posting>>() // term -> docId -> posting
    private val docLengths = HashMap<String, Int>()

    val size: Int get() = docs.size

    @Synchronized
    fun put(doc: Doc) {
        remove(doc.id)
        docs[doc.id] = doc
        val words = TextBasics.words(TextBasics.nfc(doc.title + "\n" + doc.text))
        docLengths[doc.id] = words.size
        // Offsets are relative to doc.text (title words get negative offsets and are not snippeted).
        val titleLen = TextBasics.nfc(doc.title).length + 1
        words.forEachIndexed { pos, w ->
            val term = w.text.lowercase()
            val p = index.getOrPut(term) { HashMap() }.getOrPut(doc.id) { Posting() }
            p.positions.add(pos)
            p.offsets.add(w.start - titleLen)
        }
    }

    @Synchronized
    fun remove(id: String) {
        if (docs.remove(id) == null) return
        docLengths.remove(id)
        val empty = ArrayList<String>()
        for ((term, postings) in index) { postings.remove(id); if (postings.isEmpty()) empty.add(term) }
        empty.forEach { index.remove(it) }
    }

    @Synchronized
    fun clear() { docs.clear(); index.clear(); docLengths.clear() }

    @Synchronized
    fun search(q: Query, limit: Int = 50): List<Hit> {
        val (phrases, terms) = parse(q.text)
        val candidates = docs.values.filter { d ->
            (q.fromMillis == null || d.dateMillis >= q.fromMillis) &&
                (q.toMillis == null || d.dateMillis <= q.toMillis) &&
                (q.tag == null || q.tag in d.tags)
        }.map { it.id }.toMutableSet()

        if (phrases.isEmpty() && terms.isEmpty()) {
            // Filter-only query: list matching docs newest first.
            return candidates.mapNotNull { docs[it] }.sortedByDescending { it.dateMillis }.take(limit)
                .map { Hit(it.id, it.title, 0.0, it.text.take(160), -1, 0) }
        }

        val matchOffsets = HashMap<String, MutableList<Int>>()
        val matchCounts = HashMap<String, Int>()

        for (t in terms) {
            val postingsByDoc = postingsFor(t)
            candidates.retainAll(postingsByDoc.keys)
            for ((docId, posting) in postingsByDoc) if (docId in candidates) {
                matchCounts[docId] = (matchCounts[docId] ?: 0) + posting.positions.size
                matchOffsets.getOrPut(docId) { ArrayList() }.addAll(posting.offsets)
            }
        }
        for (phrase in phrases) {
            val found = HashMap<String, MutableList<Int>>()
            val first = postingsFor(phrase[0])
            for ((docId, p0) in first) {
                if (docId !in candidates) continue
                val rest = phrase.drop(1).map { index[it]?.get(docId) }
                if (rest.any { it == null }) continue
                p0.positions.forEachIndexed { k, pos ->
                    val ok = rest.withIndex().all { (i, p) -> p!!.positions.contains(pos + i + 1) }
                    if (ok) found.getOrPut(docId) { ArrayList() }.add(p0.offsets[k])
                }
            }
            candidates.retainAll(found.keys)
            for ((docId, offs) in found) {
                matchCounts[docId] = (matchCounts[docId] ?: 0) + offs.size * phrase.size
                matchOffsets.getOrPut(docId) { ArrayList() }.addAll(offs)
            }
        }

        val totalDocs = docs.size.toDouble()
        return candidates.mapNotNull { id ->
            val d = docs[id] ?: return@mapNotNull null
            val count = matchCounts[id] ?: 0
            val len = (docLengths[id] ?: 1).coerceAtLeast(1)
            val score = count.toDouble() / Math.sqrt(len.toDouble()) * Math.log(1 + totalDocs)
            val firstOffset = matchOffsets[id]?.filter { it >= 0 }?.minOrNull() ?: -1
            Hit(id, d.title, score, snippet(d.text, firstOffset), firstOffset, count)
        }.sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { docs[it.docId]?.dateMillis ?: 0 }).take(limit)
    }

    private fun postingsFor(term: String): Map<String, Posting> {
        if (term.endsWith("*") && term.length > 2) {
            val prefix = term.dropLast(1)
            val merged = HashMap<String, Posting>()
            for ((t, postings) in index) if (t.startsWith(prefix)) {
                for ((docId, p) in postings) {
                    val m = merged.getOrPut(docId) { Posting() }
                    m.positions.addAll(p.positions); m.offsets.addAll(p.offsets)
                }
            }
            return merged
        }
        return index[term] ?: emptyMap()
    }

    private fun parse(text: String): Pair<List<List<String>>, List<String>> {
        val phrases = ArrayList<List<String>>()
        val norm = TextBasics.nfc(text).lowercase()
        val rest = Regex("\"([^\"]+)\"").replace(norm) { m ->
            val w = TextBasics.wordList(m.groupValues[1])
            if (w.size == 1) " ${w[0]} " else { if (w.isNotEmpty()) phrases.add(w); " " }
        }
        val terms = rest.split(Regex("\\s+")).filter { it.isNotBlank() }.flatMap { tok ->
            if (tok.endsWith("*")) listOf(TextBasics.wordList(tok).joinToString("") + "*").filter { it.length > 1 }
            else TextBasics.wordList(tok)
        }
        return phrases to terms
    }

    private fun snippet(text: String, offset: Int, radius: Int = 70): String {
        if (offset < 0) return text.take(radius * 2).replace('\n', ' ')
        val start = (offset - radius).coerceAtLeast(0)
        val end = (offset + radius).coerceAtMost(text.length)
        return (if (start > 0) "…" else "") + text.substring(start, end).replace('\n', ' ') + (if (end < text.length) "…" else "")
    }
}
