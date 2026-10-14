package app.astra.core.privacy

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Static security review, run with every build: proves there is no network path that bypasses
 * the Network Consent Gateway, and that the manifest and dependencies stay privacy-safe.
 */
class NetworkBypassScanTest {

    private val root: File = run {
        System.getProperty("astra.repo.root")?.let { return@run File(it) }
        System.getenv("ASTRA_REPO_ROOT")?.let { return@run File(it) }
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        d ?: error("Cannot locate repository root")
    }

    /** The one production file allowed to open sockets. */
    private val allowed = setOf("app/src/main/kotlin/app/astra/audiotext/net/HttpsTransport.kt")

    private val forbidden = listOf(
        Regex("""java\.net\.URL\b(?!Decoder|Encoder)"""),
        Regex("""\bURL\s*\("""),
        Regex("""HttpURLConnection|HttpsURLConnection|URLConnection"""),
        Regex("""java\.net\.(Socket|ServerSocket|DatagramSocket|InetAddress|InetSocketAddress)"""),
        Regex("""\bSocketFactory\b|\bSSLSocket\b"""),
        Regex("""okhttp3|retrofit2|ktor\.client|com\.android\.volley|cronet""", RegexOption.IGNORE_CASE),
        Regex("""\bDownloadManager\b"""),
        Regex("""\bWebView\b"""),
        Regex("""\.openConnection\("""),
        Regex("""\bopenStream\("""),
        Regex("""androidx\.work\.|WorkManager"""),
        Regex("""firebase|crashlytics|google-analytics|play-services-measurement|sentry|bugsnag|appcenter""", RegexOption.IGNORE_CASE),
    )

    private val forbiddenNative = listOf(
        Regex("""#include\s*<(sys/socket|netdb|curl/curl|arpa/inet)\.h>"""),
        Regex("""\b(getaddrinfo|gethostbyname|connect)\s*\("""),
    )

    private fun productionSources(): List<File> = root.walkTopDown()
        .onEnter { it.name !in setOf("build", ".gradle", ".git", "whisper.cpp", ".idea", "test", "androidTest") }
        .filter { it.isFile && (it.extension in setOf("kt", "java", "cpp", "c", "h")) && "/src/main/" in it.invariantSeparatorsPath }
        .toList()

    private fun rel(f: File) = f.relativeTo(root).invariantSeparatorsPath

    @Test fun onlyTheTransportOpensNetworkConnections() {
        val sources = productionSources()
        assertTrue("expected to find production sources under $root", sources.size > 10)
        val violations = ArrayList<String>()
        for (f in sources) {
            val path = rel(f)
            if (path in allowed) continue
            val patterns = if (f.extension in setOf("cpp", "c", "h")) forbiddenNative else forbidden
            f.readLines().forEachIndexed { i, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) return@forEachIndexed
                val code = line.substringBefore("//")
                patterns.firstOrNull { it.containsMatchIn(code) }?.let { violations.add("$path:${i + 1}: matches ${it.pattern}") }
            }
        }
        if (violations.isNotEmpty()) fail("Network access outside the consent gateway:\n" + violations.joinToString("\n"))
    }

    @Test fun transportIsOnlyConstructedInsideTheGatewayWiring() {
        val users = productionSources().filter { f ->
            rel(f) !in allowed && f.readText().contains(Regex("""\bHttpsTransport\s*\("""))
        }.map { rel(it) }
        assertTrue("HttpsTransport may only be constructed in AppContainer: $users",
            users.all { it.endsWith("AppContainer.kt") } && users.size <= 1)
    }

    @Test fun manifestIsPrivacySafe() {
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val dangerous = listOf(
            "READ_CONTACTS", "READ_SMS", "RECEIVE_SMS", "READ_CALL_LOG", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
            "MANAGE_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "READ_PHONE_STATE",
            "QUERY_ALL_PACKAGES", "BIND_ACCESSIBILITY_SERVICE", "BIND_NOTIFICATION_LISTENER_SERVICE", "BIND_DEVICE_ADMIN",
            "RECEIVE_BOOT_COMPLETED", "ACCESS_BACKGROUND_LOCATION", "FOREGROUND_SERVICE", "READ_MEDIA_AUDIO",
        )
        dangerous.forEach { p -> assertTrue("Manifest must not request $p", !manifest.contains("android.permission.$p")) }
        assertTrue("backups must be disabled", manifest.contains("android:allowBackup=\"false\""))
        assertTrue("data extraction rules required", manifest.contains("android:dataExtractionRules="))
        assertTrue("network security config required", manifest.contains("android:networkSecurityConfig="))
        assertTrue("cleartext must not be enabled", !manifest.contains("usesCleartextTraffic=\"true\""))
        val nsc = File(root, "app/src/main/res/xml/network_security_config.xml").readText()
        assertTrue("cleartext must be disabled globally", nsc.contains("cleartextTrafficPermitted=\"false\""))
    }

    @Test fun buildHasNoTelemetryDependencies() {
        val gradleFiles = root.walkTopDown().onEnter { it.name !in setOf("build", ".gradle", ".git", "whisper.cpp") }
            .filter { it.isFile && (it.name.endsWith(".gradle.kts") || it.name == "libs.versions.toml") }.toList()
        val bad = Regex("""firebase|crashlytics|analytics|play-services|sentry|bugsnag|appcenter|okhttp|retrofit|work-runtime|mlkit""", RegexOption.IGNORE_CASE)
        val hits = gradleFiles.flatMap { f -> f.readLines().filter { l -> !l.trim().startsWith("//") && bad.containsMatchIn(l) }.map { "${rel(f)}: $it" } }
        if (hits.isNotEmpty()) fail("Disallowed dependencies:\n" + hits.joinToString("\n"))
    }

    @Test fun nothingNetworksAtStartup() {
        // Application/Activity entry points must not touch the gateway's execute() or the model downloader.
        val entryPoints = productionSources().filter { f ->
            val t = f.readText(); t.contains(": Application()") || t.contains(": ComponentActivity()") || t.contains(": FragmentActivity()")
        }
        assertTrue(entryPoints.isNotEmpty())
        for (f in entryPoints) {
            val t = f.readText()
            assertTrue("${rel(f)} must not start network operations", !t.contains(".execute(") && !t.contains("downloader.download("))
        }
    }
}
