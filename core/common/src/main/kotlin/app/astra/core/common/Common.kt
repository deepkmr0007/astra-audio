package app.astra.core.common

import java.util.UUID

/** Injectable clock so time-based rules (consent expiry, audit timestamps) are testable. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/** A mutable clock for tests. */
class FakeClock(var now: Long = 1_000_000L) : Clock {
    override fun nowMillis(): Long = now
    fun advance(ms: Long) { now += ms }
}

object Ids {
    fun newId(): String = UUID.randomUUID().toString()
}

/**
 * Language descriptor. `code` is the ISO-639-1 code used by whisper.cpp ("en", "hi", ...).
 * "auto" means "let the engine detect"; the UI must label that as automatic and fallible.
 */
data class Language(val code: String, val englishName: String, val nativeName: String) {
    companion object {
        val AUTO = Language("auto", "Automatic (may be wrong)", "स्वचालित (गलत हो सकता है)")
        val ENGLISH = Language("en", "English", "English")
        val HINDI = Language("hi", "Hindi", "हिन्दी")

        /** Priority languages first, then a selection of languages Whisper models were trained on. */
        val COMMON: List<Language> = listOf(
            AUTO, ENGLISH, HINDI,
            Language("bn", "Bengali", "বাংলা"),
            Language("mr", "Marathi", "मराठी"),
            Language("ta", "Tamil", "தமிழ்"),
            Language("te", "Telugu", "తెలుగు"),
            Language("gu", "Gujarati", "ગુજરાતી"),
            Language("kn", "Kannada", "ಕನ್ನಡ"),
            Language("ml", "Malayalam", "മലയാളം"),
            Language("pa", "Punjabi", "ਪੰਜਾਬੀ"),
            Language("ur", "Urdu", "اردو"),
            Language("ne", "Nepali", "नेपाली"),
            Language("sa", "Sanskrit", "संस्कृतम्"),
            Language("es", "Spanish", "Español"),
            Language("fr", "French", "Français"),
            Language("de", "German", "Deutsch"),
            Language("ar", "Arabic", "العربية"),
            Language("zh", "Chinese", "中文"),
            Language("ja", "Japanese", "日本語"),
            Language("ru", "Russian", "Русский"),
        )

        fun byCode(code: String): Language = COMMON.firstOrNull { it.code == code } ?: Language(code, code, code)
    }
}

/** Simple outcome type that keeps the failure reason human-readable for the UI. */
sealed class Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>()
    data class Failed(val reason: String, val cause: Throwable? = null) : Outcome<Nothing>()
    data object Cancelled : Outcome<Nothing>()
}

/** Formats milliseconds as HH:MM:SS.mmm with a configurable fraction separator (SRT uses ',', VTT uses '.'). */
fun formatTimestamp(ms: Long, fractionSeparator: Char = '.'): String {
    val clamped = if (ms < 0) 0L else ms
    val h = clamped / 3_600_000
    val m = (clamped / 60_000) % 60
    val s = (clamped / 1000) % 60
    val f = clamped % 1000
    return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d%c%03d", h, m, s, fractionSeparator, f)
}

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec / 60) % 60
    val s = totalSec % 60
    return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(java.util.Locale.ROOT, "%d:%02d", m, s)
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format(java.util.Locale.ROOT, "%.1f %s", v, units[i])
}
