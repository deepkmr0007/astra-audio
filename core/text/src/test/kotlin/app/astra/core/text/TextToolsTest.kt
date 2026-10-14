package app.astra.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ErrorRateTest {
    @Test fun perfectMatchIsZero() {
        val r = ErrorRate.wer("Hello, world!", "hello world")
        assertEquals(0.0, r.rate, 1e-9)
    }

    @Test fun countsSubstitutionDeletionInsertion() {
        val r = ErrorRate.wer("the cat sat on the mat", "the bat sat on mat today")
        // Minimum edit distance is 3 (several equally short alignments exist).
        assertEquals(3, r.errors)
        assertEquals(6, r.referenceLength)
        assertEquals(3.0 / 6, r.rate, 1e-9)
        val d = ErrorRate.wer("a b c", "a c")
        assertEquals(1, d.deletions)
        val i = ErrorRate.wer("a c", "a b c")
        assertEquals(1, i.insertions)
    }

    @Test fun hindiWordsKeepCombiningMarks() {
        // "नमस्ते दुनिया" vs "नमस्ते दुनिया" with a different vowel sign must count as a substitution, not be stripped.
        val r = ErrorRate.wer("नमस्ते दुनिया", "नमस्ते दूनिया")
        assertEquals(1, r.substitutions)
        assertEquals(2, r.referenceLength)
        val c = ErrorRate.cer("नमस्ते", "नमस्ते")
        assertEquals(0.0, c.rate, 1e-9)
    }

    @Test fun corpusRateIsWeightedByLength() {
        val a = ErrorRate.wer("a b c d", "a b c d")
        val b = ErrorRate.wer("x y", "x z")
        assertEquals(1.0 / 6, ErrorRate.corpus(listOf(a, b)), 1e-9)
    }

    @Test fun emptyReferenceIsNaN() {
        assertTrue(ErrorRate.wer("", "something").rate.isNaN())
    }
}

class TextBasicsTest {
    @Test fun splitsLatinAndDevanagariSentences() {
        val s = TextBasics.sentences("Hello there. मेरा नाम दीपक है। How are you?")
        assertEquals(listOf("Hello there.", "मेरा नाम दीपक है।", "How are you?"), s.map { it.text })
    }

    @Test fun wordsIncludeCombiningMarks() {
        assertEquals(listOf("किताब", "पढ़ो"), TextBasics.wordList("किताब पढ़ो"))
    }

    @Test fun describesCodeSwitching() {
        assertEquals("Mixed Devanagari + Latin (possible code-switching)", TextBasics.describeScript("कल meeting है at 5 बजे"))
    }
}

class SummaryAndToolsTest {
    private val text = "The project review is on Monday. The budget for the project was approved by the committee. " +
        "Lunch was good. We need to send the revised project budget to finance by Friday. " +
        "The weather was pleasant. The committee asked for a project timeline."

    @Test fun extractiveSummaryUsesVerbatimSentencesInOrder() {
        val s = ExtractiveSummarizer.summarize(text, maxSentences = 2)
        assertEquals(2, s.sentences.size)
        s.sentences.forEach { p -> assertEquals(p.text, text.substring(p.start, p.end)) }
        assertTrue(s.sentences[0].sentenceIndex < s.sentences[1].sentenceIndex)
        assertFalse(s.text.contains("Lunch"))
    }

    @Test fun actionItemsAreExtractedVerbatim() {
        val items = ActionItemExtractor.extract(text + " रिपोर्ट कल तक जमा करना है।")
        assertEquals(2, items.size)
        assertTrue(items[0].text.startsWith("We need to send"))
        assertTrue(items[1].text.contains("कल तक"))
    }

    @Test fun proofreaderFindsCommonIssuesAndApplies() {
        val src = "this is is a test ,with teh errors."
        val issues = Proofreader.check(src)
        val fixed = Proofreader.applyAll(src, issues)
        assertEquals("This is a test, with the errors.", fixed)
    }

    @Test fun classifierExplainsItsScore() {
        val r = Classifier.classify("The court issued a notice; the affidavit is due.")
        assertEquals("Legal / official", r.first().category)
        assertTrue(r.first().matched.contains("affidavit"))
    }

    @Test fun diffFindsChanges() {
        val d = TextCompare.diff("the quick brown fox", "the slow brown fox jumps")
        val kinds = d.pieces.map { it.kind to it.text }
        assertTrue(kinds.contains(TextCompare.Kind.REMOVED to "quick"))
        assertTrue(kinds.contains(TextCompare.Kind.ADDED to "slow"))
        assertTrue(kinds.contains(TextCompare.Kind.ADDED to "jumps"))
    }

    @Test fun cleanTranscriptRemovesFillersButKeepsMeaning() {
        val (clean, removed) = CleanTranscript.clean("Um, I I think we should, uh, start. अं ठीक है।")
        assertEquals("I think we should, start. ठीक है।", clean)
        assertTrue(removed >= 3)
    }

    @Test fun glossaryPromptsAndCorrectsOnlyUserDefinedVariants() {
        val g = Glossary.parse("NHAI = n h a i | enhai\nKotputli\n# comment")
        assertEquals("Glossary: NHAI, Kotputli.", g.initialPrompt())
        val text = "The enhai office in Kotputli and n h a i staff."
        val c = g.findCorrections(text)
        assertEquals(2, c.size)
        assertEquals("The NHAI office in Kotputli and NHAI staff.", g.apply(text, c))
        assertEquals(g.entries, Glossary.parse(g.toLines()).entries)
    }
}

class SearchIndexTest {
    private val idx = SearchIndex().apply {
        put(SearchIndex.Doc("1", "Site visit", "We inspected the median plantation near Behror on the highway.", 1000, setOf("work")))
        put(SearchIndex.Doc("2", "Family call", "Talked about the dhani road and the plantation of neem trees.", 2000, setOf("home")))
        put(SearchIndex.Doc("3", "हिंदी नोट", "कल सुबह बैठक है और सड़क की बात होगी।", 3000))
    }

    @Test fun termAndPhraseSearch() {
        assertEquals(setOf("1", "2"), idx.search(SearchIndex.Query("plantation")).map { it.docId }.toSet())
        assertEquals(listOf("1"), idx.search(SearchIndex.Query("\"median plantation\"")).map { it.docId })
        assertTrue(idx.search(SearchIndex.Query("\"plantation median\"")).isEmpty())
    }

    @Test fun devanagariSearchWorks() {
        assertEquals(listOf("3"), idx.search(SearchIndex.Query("बैठक")).map { it.docId })
    }

    @Test fun filtersByTagAndDate() {
        assertEquals(listOf("2"), idx.search(SearchIndex.Query("plantation", tag = "home")).map { it.docId })
        assertEquals(listOf("1"), idx.search(SearchIndex.Query("plantation", toMillis = 1500)).map { it.docId })
    }

    @Test fun prefixAndSnippet() {
        val hits = idx.search(SearchIndex.Query("plant*"))
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.snippet.contains("plantation") })
    }

    @Test fun removeDropsDocument() {
        idx.remove("1")
        assertEquals(listOf("2"), idx.search(SearchIndex.Query("plantation")).map { it.docId })
    }
}

class DocumentImportTest {
    private fun docx(xml: String, extra: Int = 0): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("[Content_Types].xml")); z.write("<Types/>".toByteArray()); z.closeEntry()
            repeat(extra) { z.putNextEntry(ZipEntry("x/$it.xml")); z.closeEntry() }
            z.putNextEntry(ZipEntry("word/document.xml")); z.write(xml.toByteArray()); z.closeEntry()
        }
        return bos.toByteArray()
    }

    @Test fun extractsDocxParagraphsAndEntities() {
        val xml = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><w:document><w:body>
            <w:p><w:r><w:t>Hello &amp; welcome</w:t></w:r></w:p><w:p><w:r><w:t xml:space="preserve">नमस्ते </w:t><w:tab/><w:t>&xxe;</w:t></w:r></w:p>
            </w:body></w:document>"""
        val r = DocumentImport.import(ByteArrayInputStream(docx(xml)), DocumentImport.Kind.DOCX)
        assertEquals("Hello & welcome\nनमस्ते \t&xxe;", r.text)
    }

    @Test(expected = DocumentImport.ImportException::class)
    fun rejectsTooManyZipEntries() {
        DocumentImport.import(ByteArrayInputStream(docx("<w:p/>", extra = 50)), DocumentImport.Kind.DOCX, DocumentImport.Limits(maxZipEntries = 10))
    }

    @Test(expected = DocumentImport.ImportException::class)
    fun rejectsOversizedText() {
        DocumentImport.import(ByteArrayInputStream(ByteArray(2048) { 'a'.code.toByte() }), DocumentImport.Kind.TXT, DocumentImport.Limits(maxInputBytes = 1024))
    }

    @Test fun decodesUtf8BomAndFallsBack() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "हिन्दी".toByteArray()
        assertEquals("हिन्दी", DocumentImport.decodeText(bom).first)
        val latin1 = byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())
        val (t, warn) = DocumentImport.decodeText(latin1)
        assertEquals("café", t)
        assertTrue(warn != null)
    }

    @Test(expected = DocumentImport.ImportException::class)
    fun pdfIsHonestlyUnsupported() {
        DocumentImport.import(ByteArrayInputStream(ByteArray(10)), DocumentImport.Kind.PDF)
    }

    @Test fun sanitizesFileNames() {
        assertEquals("_.._etc_passwd".replace("..", "."), SafeFileName.sanitize("/../etc/passwd"))
        assertEquals("untitled", SafeFileName.sanitize("CON"))
        assertEquals("meeting_ notes", SafeFileName.sanitize("meeting: notes"))
    }
}

class AutoTitleTest {
    @Test fun usesFirstContentfulSentence() {
        assertEquals("Budget review for the Behror plantation project", AutoTitle.suggest("Hello. Um, okay. Budget review for the Behror plantation project. Then lunch."))
        assertEquals("We need to send the revised project budget to…", AutoTitle.suggest("We need to send the revised project budget to finance by Friday."))
        assertEquals("कल सुबह बैठक में सड़क की बात होगी", AutoTitle.suggest("नमस्ते। कल सुबह बैठक में सड़क की बात होगी।"))
        assertEquals(null, AutoTitle.suggest("Hi. Okay. Yes."))
    }
}
