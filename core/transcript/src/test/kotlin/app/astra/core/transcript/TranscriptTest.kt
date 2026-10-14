package app.astra.core.transcript

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun sample(): Transcript {
    val segs = listOf(
        Segment(0, 2500, " Um, hello everyone.", listOf(TokenInfo(" Um", 0, 300, 0.4f), TokenInfo(" hello", 300, 900, 0.95f)), speaker = "Speaker 1"),
        Segment(2500, 61_234, " Today we discuss the --> budget.", listOf(TokenInfo(" budget", 2500, 3000, 0.2f)), speaker = "Speaker 2"),
        Segment(61_234, 3_723_004, " नमस्ते।", emptyList(), speaker = null),
    )
    return Transcript("t1", "Team: call", 1, "item1", "auto", "en", false,
        EngineInfo("whisper.cpp", "whisper.cpp", "whisper-base", "abc", "BALANCED", "x", true), segs, segs)
}

class TranscriptTest {

    @Test fun srtFormatIsCorrect() {
        val srt = Exporters.srt(sample())
        assertTrue(srt.startsWith("1\n00:00:00,000 --> 00:00:02,500\nSpeaker 1: Um, hello everyone.\n\n2\n"))
        assertTrue(srt.contains("01:02:03,004"))
    }

    @Test fun vttEscapesArrowsAndUsesVoiceTags() {
        val vtt = Exporters.vtt(sample())
        assertTrue(vtt.startsWith("WEBVTT\n\n00:00:00.000 --> 00:00:02.500\n<v Speaker 1>Um, hello everyone."))
        assertFalse(vtt.substringAfter("WEBVTT").lines().filter { !it.contains(" --> ") }.any { it.contains("-->") })
    }

    @Test fun cleanExportDoesNotTouchStoredText() {
        val t = sample()
        val txt = Exporters.txt(t, Exporters.Options(clean = true, includeTimestamps = false))
        assertTrue(txt.startsWith("Speaker 1: hello everyone."))
        assertEquals(" Um, hello everyone.", t.corrected[0].text)
    }

    @Test fun jsonRoundTripPreservesBothLayers() {
        val t = sample().withEditedSegment(1, "Today we discuss the budget.", 99).renameSpeaker("Speaker 2", "Asha")
        val back = Transcript.fromJson(t.toJson())
        assertEquals(t, back)
        assertEquals(" Today we discuss the --> budget.", back.raw[1].text)
        assertEquals("Today we discuss the budget.", back.corrected[1].text)
        assertEquals("manual edit", back.corrections.single().kind)
        assertEquals("Asha", back.corrected[1].speaker)
    }

    @Test fun uncertaintyOnlyFromEngineProbabilities() {
        val t = sample()
        assertTrue(t.raw[1].isUncertain())
        assertNull(t.raw[2].meanProbability) // no probabilities supplied -> no claim either way
        assertFalse(t.raw[2].isUncertain())
    }

    @Test fun exportFileNamesAreSafe() {
        assertEquals("Team_ call.srt", Exporters.fileName("Team: call", Exporters.Format.SRT))
    }
}

/** Engine double that records what it was asked and can cancel mid-way. */
class ScriptedEngine(private val cancelOnChunk: Int? = null, private val signal: CancelSignal? = null) : TranscriptionEngine {
    val seenOptions = mutableListOf<TranscribeOptions>()
    val seenOffsets = mutableListOf<Long>()
    override val id = "fake"
    override val displayName = "Fake"
    override val verifiedOffline = true
    override val capabilities = EngineCapabilities(setOf("*"), true, true, true, true, false)
    override suspend fun transcribe(pcm: FloatArray, offsetMs: Long, options: TranscribeOptions, listener: TranscribeListener, cancel: CancelSignal): EngineResult {
        val n = seenOffsets.size
        if (cancelOnChunk == n) { signal?.cancel(); return EngineResult.Cancelled }
        seenOptions.add(options); seenOffsets.add(offsetMs)
        listener.onProgress(100)
        return EngineResult.Success(listOf(Segment(offsetMs, offsetMs + pcm.size, "chunk$n")), if (options.language == "auto") "hi" else null)
    }
}

class PipelineTest {
    private val chunks = listOf(ChunkSpec(0, 0, 1000), ChunkSpec(1, 1000, 2000), ChunkSpec(2, 2000, 3000))

    @Test fun runsAllChunksLocksLanguageAndCarriesContext() = runBlocking {
        val engine = ScriptedEngine()
        val store = MemoryCheckpointStore()
        val r = TranscriptionPipeline(engine, store).run("job", "item", chunks, { FloatArray(10) },
            TranscribeOptions(initialPrompt = "Glossary: NHAI."), object : PipelineListener {}, CancelSignal())
        r as PipelineResult.Done
        assertEquals(listOf("chunk0", "chunk1", "chunk2"), r.segments.map { it.text })
        assertEquals("hi", r.detectedLanguage)
        assertEquals(listOf(0L, 1000L, 2000L), engine.seenOffsets)
        assertEquals("auto", engine.seenOptions[0].language)
        assertEquals("hi", engine.seenOptions[1].language) // locked after first detection
        assertEquals("Glossary: NHAI. chunk0", engine.seenOptions[1].initialPrompt)
        assertNull(store.load("job")) // checkpoint removed when done
    }

    @Test fun resumesAfterCancellationWithoutRedoingChunks() = runBlocking {
        val store = MemoryCheckpointStore()
        val signal = CancelSignal()
        val first = TranscriptionPipeline(ScriptedEngine(cancelOnChunk = 2, signal = signal), store)
            .run("job", "item", chunks, { FloatArray(10) }, TranscribeOptions(), object : PipelineListener {}, signal)
        assertEquals(PipelineResult.Cancelled(2, 3), first)
        // Simulate process death: checkpoint survives via JSON.
        val restored = Checkpoint.fromJson(store.load("job")!!.toJson())
        assertEquals(2, restored.completed.size)
        store.save(restored)
        val engine2 = ScriptedEngine()
        val second = TranscriptionPipeline(engine2, store).run("job", "item", chunks, { FloatArray(10) }, TranscribeOptions(), object : PipelineListener {}, CancelSignal())
        second as PipelineResult.Done
        assertEquals(listOf(2000L), engine2.seenOffsets) // only the remaining chunk was processed
        assertEquals(3, second.segments.size)
    }

    @Test fun changedOptionsInvalidateCheckpoint() = runBlocking {
        val store = MemoryCheckpointStore()
        store.save(Checkpoint("job", "item", "something else", 3, listOf(ChunkResult(0, emptyList(), null)), null))
        val engine = ScriptedEngine()
        TranscriptionPipeline(engine, store).run("job", "item", chunks, { FloatArray(10) }, TranscribeOptions(), object : PipelineListener {}, CancelSignal())
        assertEquals(3, engine.seenOffsets.size)
    }
}
