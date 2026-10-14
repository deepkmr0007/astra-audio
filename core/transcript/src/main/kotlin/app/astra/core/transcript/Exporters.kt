package app.astra.core.transcript

import app.astra.core.common.Json
import app.astra.core.common.formatTimestamp
import app.astra.core.text.CleanTranscript

/** TXT / SRT / VTT / JSON exporters. Pure functions: they never write files or share anything. */
object Exporters {

    enum class Format(val extension: String, val mime: String, val label: String) {
        TXT("txt", "text/plain", "Plain text (.txt)"),
        SRT("srt", "application/x-subrip", "Subtitles (.srt)"),
        VTT("vtt", "text/vtt", "Web subtitles (.vtt)"),
        JSON("json", "application/json", "Structured data (.json)"),
    }

    data class Options(
        val layer: Transcript.Layer = Transcript.Layer.CORRECTED,
        val clean: Boolean = false,
        val includeTimestamps: Boolean = true,
        val includeSpeakers: Boolean = true,
    )

    fun export(t: Transcript, format: Format, o: Options = Options()): String = when (format) {
        Format.TXT -> txt(t, o)
        Format.SRT -> srt(t, o)
        Format.VTT -> vtt(t, o)
        Format.JSON -> json(t, o)
    }

    private fun segments(t: Transcript, o: Options): List<Segment> {
        val base = if (o.layer == Transcript.Layer.RAW) t.raw else t.corrected
        return if (!o.clean) base else base.map { it.copy(text = CleanTranscript.clean(it.text).first) }.filter { it.text.isNotBlank() }
    }

    private fun speakerLabel(t: Transcript, s: Segment): String? = s.speaker?.let { t.speakerNames[it] ?: it }

    fun txt(t: Transcript, o: Options = Options()): String {
        val sb = StringBuilder()
        var lastSpeaker: String? = null
        for (s in segments(t, o)) {
            val sp = if (o.includeSpeakers) speakerLabel(t, s) else null
            if (o.includeTimestamps) sb.append('[').append(formatTimestamp(s.startMs)).append("] ")
            if (sp != null && (sp != lastSpeaker || o.includeTimestamps)) sb.append(sp).append(": ")
            sb.append(s.text.trim()).append('\n')
            lastSpeaker = sp
        }
        return sb.toString()
    }

    fun srt(t: Transcript, o: Options = Options()): String {
        val sb = StringBuilder()
        segments(t, o).forEachIndexed { i, s ->
            sb.append(i + 1).append('\n')
            sb.append(formatTimestamp(s.startMs, ',')).append(" --> ").append(formatTimestamp(maxOf(s.endMs, s.startMs + 1), ',')).append('\n')
            val sp = if (o.includeSpeakers) speakerLabel(t, s) else null
            sb.append(if (sp != null) "$sp: " else "").append(s.text.trim().replace("\n", " ")).append("\n\n")
        }
        return sb.toString()
    }

    fun vtt(t: Transcript, o: Options = Options()): String {
        val sb = StringBuilder("WEBVTT\n\n")
        segments(t, o).forEach { s ->
            sb.append(formatTimestamp(s.startMs, '.')).append(" --> ").append(formatTimestamp(maxOf(s.endMs, s.startMs + 1), '.')).append('\n')
            val sp = if (o.includeSpeakers) speakerLabel(t, s) else null
            // "-->" is not allowed inside cue text.
            val text = s.text.trim().replace("-->", "→").replace("\n", " ")
            sb.append(if (sp != null) "<v ${sp.replace(">", "")}>" else "").append(text).append("\n\n")
        }
        return sb.toString()
    }

    /** Full-fidelity export: both layers, token probabilities as supplied by the engine, and provenance. */
    fun json(t: Transcript, o: Options = Options()): String {
        val m = t.toMap().toMutableMap()
        m["exportOptions"] = mapOf("layer" to o.layer.name, "clean" to o.clean)
        m["notes"] = listOf(
            "token probabilities are the engine's own outputs; null means the engine did not provide one",
            "raw = unedited engine output; corrected = user edits and approved glossary corrections",
        )
        return Json.write(m, pretty = true)
    }

    fun fileName(title: String, format: Format): String =
        app.astra.core.text.SafeFileName.sanitize(title, "transcript") + "." + format.extension
}
