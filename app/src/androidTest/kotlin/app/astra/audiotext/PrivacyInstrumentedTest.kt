package app.astra.audiotext

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.astra.audiotext.security.KeystoreKeyWrapper
import app.astra.audiotext.storage.LibraryRepository
import app.astra.core.privacy.AuditType
import app.astra.core.security.StreamingAead
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random

/**
 * On-device acceptance checks (run with: ./gradlew connectedDebugAndroidTest, ideally with
 * Wi-Fi and mobile data OFF). Not executable in the environment this project was authored in.
 */
@RunWith(AndroidJUnit4::class)
class PrivacyInstrumentedTest {

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AstraApp

    @Test fun launchMakesNoNetworkAttempt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(3000) // let startup work (library decryption, UI) finish
            val c = app.container
            assertNull("no network operation may run at startup", c.gateway.lastOutcome.value)
            assertEquals(0, c.gateway.tokens.liveTokenCount())
            assertTrue(c.audit.load().none { e -> e.type.name.startsWith("NETWORK_") && e.type != AuditType.NETWORK_BLOCKED_OFFLINE_MODE })
        }
    }

    @Test fun strictOfflineIsOnByDefaultOnFreshInstall() {
        // Fresh installs start in Strict Offline Mode (the setting may have been changed by the tester).
        val c = app.container
        if (!c.settings.value.onboarded && c.settings.value.activeModelId == null) assertTrue(c.gateway.strictOffline.value)
    }

    @Test fun keystoreEncryptionRoundTripAndNoPlaintextAtRest() {
        val keys = KeystoreKeyWrapper("astra.test.key")
        try {
            val secret = "मेरी निजी रिकॉर्डिंग — private transcript".repeat(200).toByteArray()
            val ct = StreamingAead.encryptBytes(secret, keys)
            assertFalse(String(ct, Charsets.ISO_8859_1).contains("private transcript"))
            assertArrayEquals(secret, StreamingAead.decryptBytes(ct, keys))
        } finally { keys.destroy() }
    }

    @Test fun importedAudioIsStoredByteForByte() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.cacheDir, "lib-test").apply { deleteRecursively(); mkdirs() }
        val keys = KeystoreKeyWrapper("astra.test.key2")
        try {
            val repo = LibraryRepository(dir, keys)
            val original = ByteArray(300_000).also { Random(7).nextBytes(it) }
            val item = repo.importAudio(original.inputStream(), "meeting.m4a", "audio/mp4", 10_000_000) { false }
            val back = repo.openAudioInput(item.id).use { it.readBytes() }
            assertArrayEquals("original media must be preserved exactly", original, back)
            assertTrue(dir.walkTopDown().filter { it.isFile }.none { f -> f.readBytes().let { b -> b.size > 1000 && b.copyOfRange(0, 1000).contentEquals(original.copyOfRange(0, 1000)) } })
        } finally { keys.destroy(); dir.deleteRecursively() }
    }
}
