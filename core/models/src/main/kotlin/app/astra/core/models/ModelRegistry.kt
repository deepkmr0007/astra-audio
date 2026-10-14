package app.astra.core.models

/**
 * Registry of speech models the app knows how to install. Every entry records where it comes
 * from, its licence, the upstream-published checksum, approximate size and memory, language
 * coverage and known limitations. Nothing is bundled in the APK; models are either imported
 * from a file the user already has, or downloaded after explicit, per-request consent.
 *
 * Checksums: SHA-1 values are copied from whisper.cpp v1.9.5 `models/README.md`
 * (commit d1be6fde11ac6e0407606b4e42fe72d34add8037). SHA-1 is what upstream publishes; on install
 * the app also computes and pins a SHA-256 for later integrity checks.
 */
enum class ModelFamily { WHISPER_GGML }

data class ModelSpec(
    val id: String,
    val displayName: String,
    val family: ModelFamily,
    val fileName: String,
    val sourceUrl: String,
    val sourceHosts: Set<String>,
    val publisher: String,
    val license: String,
    val licenseUrl: String,
    val sha1: String,
    val approxBytes: Long,
    val approxRamBytes: Long,
    val multilingual: Boolean,
    val translateToEnglish: Boolean,
    val speakerTurns: Boolean,
    /** Expected n_vocab in the GGML header — a cheap sanity check that the file is the right model. */
    val expectedVocab: Int,
    val quantization: String,
    val limitations: List<String>,
) {
    val languagesLabel: String get() = if (multilingual) "~99 languages incl. Hindi & English (quality varies by language)" else "English only"
}

object ModelRegistry {

    private const val MiB = 1024L * 1024
    private const val MB = 1000L * 1000
    private const val HF = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    /** Hosts Hugging Face is known to redirect downloads to; anything else triggers a fresh consent prompt. */
    val HF_HOSTS = setOf("huggingface.co", "cdn-lfs.hf.co", "cdn-lfs-us-1.hf.co", "cas-bridge.xethub.hf.co")

    private const val WHISPER_LICENSE = "MIT (OpenAI Whisper weights; whisper.cpp GGML conversion)"
    private const val WHISPER_LICENSE_URL = "https://github.com/openai/whisper/blob/main/LICENSE"

    private val COMMON_LIMITS = listOf(
        "Can hallucinate text during long silences or music.",
        "Accuracy drops with heavy noise, overlapping speakers, strong accents and very quiet audio.",
        "Automatic language detection can be wrong on short or mixed-language clips — choose the language when you know it.",
    )

    val ALL: List<ModelSpec> = listOf(
        whisper("whisper-tiny", "Whisper tiny (multilingual)", "ggml-tiny.bin", "bd577a113a864445d4c299885e0cb97d4ba92b5f",
            75 * MiB, 273 * MB, multilingual = true, translate = true, vocab = 51865,
            extra = listOf("Fastest, least accurate. Hindi output is often poor; use for quick English notes.")),
        whisper("whisper-tiny-en", "Whisper tiny.en (English)", "ggml-tiny.en.bin", "c78c86eb1a8faa21b369bcd33207cc90d64ae9df",
            75 * MiB, 273 * MB, multilingual = false, translate = false, vocab = 51864, extra = emptyList()),
        whisper("whisper-base", "Whisper base (multilingual)", "ggml-base.bin", "465707469ff3a37a2b9b8d8f89f2f99de7299dac",
            142 * MiB, 388 * MB, multilingual = true, translate = true, vocab = 51865,
            extra = listOf("Good default for phones with 3–4 GB RAM. Hindi is usable but expect errors.")),
        whisper("whisper-base-en", "Whisper base.en (English)", "ggml-base.en.bin", "137c40403d78fd54d454da0f9bd998f78703390c",
            142 * MiB, 388 * MB, multilingual = false, translate = false, vocab = 51864, extra = emptyList()),
        whisper("whisper-small", "Whisper small (multilingual)", "ggml-small.bin", "55356645c2b361a969dfd0ef2c5a50d530afd8d5",
            466 * MiB, 852 * MB, multilingual = true, translate = true, vocab = 51865,
            extra = listOf("Recommended for Hindi / Hinglish on phones with 6 GB+ RAM. Several times slower than base.")),
        whisper("whisper-small-en", "Whisper small.en (English)", "ggml-small.en.bin", "db8a495a91d927739e50b3fc1cc4c6b8f6c2d022",
            466 * MiB, 852 * MB, multilingual = false, translate = false, vocab = 51864, extra = emptyList()),
        ModelSpec(
            id = "whisper-small-en-tdrz", displayName = "Whisper small.en + tinydiarize (speaker turns, English)",
            family = ModelFamily.WHISPER_GGML, fileName = "ggml-small.en-tdrz.bin",
            sourceUrl = "https://huggingface.co/akashmjn/tinydiarize-whisper.cpp/resolve/main/ggml-small.en-tdrz.bin",
            sourceHosts = HF_HOSTS, publisher = "akashmjn / tinydiarize (fine-tune of OpenAI Whisper small.en)",
            license = "MIT per upstream repository — verify before redistributing",
            licenseUrl = "https://github.com/akashmjn/tinydiarize",
            sha1 = "b6c6e7e89af1a35c08e6de56b66ca6a02a2fdfa1", approxBytes = 465 * MiB, approxRamBytes = 852 * MB,
            multilingual = false, translateToEnglish = false, speakerTurns = true, expectedVocab = 51865, quantization = "F16",
            limitations = COMMON_LIMITS + listOf(
                "Experimental: marks probable speaker CHANGES only; it does not identify who is speaking.",
                "English only. Speaker labels are neutral (Speaker 1, Speaker 2) and should be checked by you.",
            ),
        ),
        whisper("whisper-medium", "Whisper medium (multilingual)", "ggml-medium.bin", "fd9727b6e1217c2f614f9b698455c4ffd82463b4",
            1536 * MiB, 2100 * MB, multilingual = true, translate = true, vocab = 51865,
            extra = listOf("Only for phones with 8 GB+ RAM. Slow; long recordings can take longer than real time and warm the phone.")),
        whisper("whisper-large-v3-turbo-q5_0", "Whisper large-v3-turbo q5_0 (multilingual)", "ggml-large-v3-turbo-q5_0.bin",
            "e050f7970618a659205450ad97eb95a18d69c9ee", 547 * MiB, 1100 * MB, multilingual = true, translate = false, vocab = 51866,
            quant = "Q5_0",
            extra = listOf(
                "Highest accuracy option here for most languages, at small-model size. Needs 8 GB+ RAM for comfortable use.",
                "Not trained for speech translation — use a non-turbo model for 'translate to English'.",
                "Memory figure is an estimate (not listed upstream).",
            )),
    )

    private fun whisper(id: String, name: String, file: String, sha1: String, bytes: Long, ram: Long, multilingual: Boolean,
                        translate: Boolean, vocab: Int, extra: List<String>, quant: String = "F16") = ModelSpec(
        id = id, displayName = name, family = ModelFamily.WHISPER_GGML, fileName = file, sourceUrl = HF + file,
        sourceHosts = HF_HOSTS, publisher = "OpenAI (weights) · ggerganov/whisper.cpp (GGML conversion)",
        license = WHISPER_LICENSE, licenseUrl = WHISPER_LICENSE_URL, sha1 = sha1, approxBytes = bytes, approxRamBytes = ram,
        multilingual = multilingual, translateToEnglish = translate, speakerTurns = false, expectedVocab = vocab,
        quantization = quant, limitations = COMMON_LIMITS + extra,
    )

    fun byId(id: String): ModelSpec? = ALL.firstOrNull { it.id == id }
    fun bySha1(sha1: String): ModelSpec? = ALL.firstOrNull { it.sha1.equals(sha1, ignoreCase = true) }
}
