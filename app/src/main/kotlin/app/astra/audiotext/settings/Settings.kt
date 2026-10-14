package app.astra.audiotext.settings

import android.content.Context
import android.content.SharedPreferences
import app.astra.core.transcript.AccuracyProfile
import app.astra.core.transcript.TranscriptStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class UiLanguage(val tag: String?) { SYSTEM(null), ENGLISH("en"), HINDI("hi") }

/**
 * User preferences. Nothing sensitive is stored here (no content, no keys) — only choices.
 * SharedPreferences live in app-private storage and are excluded from every backup.
 */
data class AppSettings(
    val strictOffline: Boolean = true,
    val activeModelId: String? = null,
    val defaultLanguage: String = "auto",
    val profile: AccuracyProfile = AccuracyProfile.BALANCED,
    val style: TranscriptStyle = TranscriptStyle.STANDARD,
    val threads: Int = 0, // 0 = automatic
    val chunkSeconds: Int = 120,
    val maxImportMb: Int = 500,
    val glossary: String = "",
    val useGlossaryPrompt: Boolean = true,
    val appLock: Boolean = false,
    val blockScreenshots: Boolean = false,
    val auditLog: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val uiLanguage: UiLanguage = UiLanguage.SYSTEM,
    val textScale: Float = 1.0f,
    val retentionDays: Int = 0, // 0 = keep forever (never auto-delete)
    val onboarded: Boolean = false,
)

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("astra_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val value: AppSettings get() = _state.value

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            strictOffline = prefs.getBoolean("strictOffline", d.strictOffline),
            activeModelId = prefs.getString("activeModelId", null),
            defaultLanguage = prefs.getString("defaultLanguage", d.defaultLanguage) ?: d.defaultLanguage,
            profile = enumOr(prefs.getString("profile", null), d.profile),
            style = enumOr(prefs.getString("style", null), d.style),
            threads = prefs.getInt("threads", d.threads),
            chunkSeconds = prefs.getInt("chunkSeconds", d.chunkSeconds),
            maxImportMb = prefs.getInt("maxImportMb", d.maxImportMb),
            glossary = prefs.getString("glossary", "") ?: "",
            useGlossaryPrompt = prefs.getBoolean("useGlossaryPrompt", d.useGlossaryPrompt),
            appLock = prefs.getBoolean("appLock", d.appLock),
            blockScreenshots = prefs.getBoolean("blockScreenshots", d.blockScreenshots),
            auditLog = prefs.getBoolean("auditLog", d.auditLog),
            theme = enumOr(prefs.getString("theme", null), d.theme),
            uiLanguage = enumOr(prefs.getString("uiLanguage", null), d.uiLanguage),
            textScale = prefs.getFloat("textScale", d.textScale),
            retentionDays = prefs.getInt("retentionDays", d.retentionDays),
            onboarded = prefs.getBoolean("onboarded", d.onboarded),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_state.value)
        prefs.edit()
            .putBoolean("strictOffline", s.strictOffline)
            .putString("activeModelId", s.activeModelId)
            .putString("defaultLanguage", s.defaultLanguage)
            .putString("profile", s.profile.name)
            .putString("style", s.style.name)
            .putInt("threads", s.threads)
            .putInt("chunkSeconds", s.chunkSeconds)
            .putInt("maxImportMb", s.maxImportMb)
            .putString("glossary", s.glossary)
            .putBoolean("useGlossaryPrompt", s.useGlossaryPrompt)
            .putBoolean("appLock", s.appLock)
            .putBoolean("blockScreenshots", s.blockScreenshots)
            .putBoolean("auditLog", s.auditLog)
            .putString("theme", s.theme.name)
            .putString("uiLanguage", s.uiLanguage.name)
            .putFloat("textScale", s.textScale)
            .putInt("retentionDays", s.retentionDays)
            .putBoolean("onboarded", s.onboarded)
            .apply()
        _state.value = s
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        _state.value = AppSettings()
    }
}
