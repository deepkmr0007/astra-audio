package app.astra.core.models

import app.astra.core.common.FakeClock
import app.astra.core.privacy.ConnectionTicket
import app.astra.core.privacy.ConsentDecision
import app.astra.core.privacy.HopRequest
import app.astra.core.privacy.HopResponse
import app.astra.core.privacy.HopResult
import app.astra.core.privacy.MemoryAuditSink
import app.astra.core.privacy.NetworkConsentGateway
import app.astra.core.privacy.NetworkOutcome
import app.astra.core.privacy.Transport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModelInstallTest {

    private val dir: File = kotlin.io.path.createTempDirectory("models").toFile()
    @After fun cleanup() { dir.deleteRecursively() }

    /** A tiny fake "model": correct GGML magic + vocab, used to exercise every check without real weights. */
    private fun fakeModel(vocab: Int = 51865, size: Int = 4096): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).putInt(0x67676d6c).putInt(vocab).array()

    private fun specFor(bytes: ByteArray, vocab: Int = 51865): ModelSpec {
        val sha1 = app.astra.core.common.Hashing.hex(java.security.MessageDigest.getInstance("SHA-1").digest(bytes))
        return ModelRegistry.byId("whisper-base")!!.copy(sha1 = sha1, approxBytes = bytes.size.toLong(), expectedVocab = vocab)
    }

    @Test fun registryEntriesAreComplete() {
        assertTrue(ModelRegistry.ALL.size >= 8)
        for (m in ModelRegistry.ALL) {
            assertEquals(40, m.sha1.length)
            assertTrue(m.sourceUrl.startsWith("https://"))
            assertTrue(m.license.isNotBlank() && m.limitations.isNotEmpty())
            assertTrue(app.astra.core.privacy.UrlRules.violation(m.sourceUrl, m.sourceHosts) == null)
        }
        assertEquals(ModelRegistry.ALL.size, ModelRegistry.ALL.map { it.id }.toSet().size)
    }

    @Test fun importVerifiesAndInstalls() {
        val bytes = fakeModel()
        val spec = specFor(bytes)
        val d = ModelDownloader(NetworkConsentGateway(NoNetwork, { ConsentDecision.CANCEL }, MemoryAuditSink()), ModelInstaller(dir), MemoryAuditSink())
        val out = d.importFile(ByteArrayInputStream(bytes), spec)
        out as InstallOutcome.Installed
        assertEquals("imported file", out.model.source)
        val installer = ModelInstaller(dir)
        assertEquals(1, installer.installed().size)
        assertTrue(installer.recheck(out.model))
        installer.fileOf(out.model).appendBytes(byteArrayOf(1))
        assertFalse("tampering after install must be detected", installer.recheck(out.model))
    }

    @Test fun wrongChecksumIsRejectedAndDeleted() {
        val spec = specFor(fakeModel())
        val d = ModelDownloader(NetworkConsentGateway(NoNetwork, { ConsentDecision.CANCEL }, MemoryAuditSink()), ModelInstaller(dir), MemoryAuditSink())
        val out = d.importFile(ByteArrayInputStream(fakeModel(size = 5000)), spec)
        assertTrue(out is InstallOutcome.Rejected)
        assertTrue(dir.listFiles { f -> f.isFile }!!.isEmpty())
    }

    @Test fun nonGgmlFileIsRejected() {
        val bytes = ByteArray(4096) { 7 }
        val spec = specFor(bytes)
        val out = ModelDownloader(NetworkConsentGateway(NoNetwork, { ConsentDecision.CANCEL }, MemoryAuditSink()), ModelInstaller(dir), MemoryAuditSink())
            .importFile(ByteArrayInputStream(bytes), spec)
        assertTrue((out as InstallOutcome.Rejected).reason.contains("GGML"))
    }

    @Test fun unknownFileIsRejectedWhenNoSpecGiven() {
        val out = ModelDownloader(NetworkConsentGateway(NoNetwork, { ConsentDecision.CANCEL }, MemoryAuditSink()), ModelInstaller(dir), MemoryAuditSink())
            .importFile(ByteArrayInputStream(fakeModel()), null)
        assertTrue(out is InstallOutcome.Rejected)
    }

    @Test fun oversizedDownloadIsStopped() {
        val bytes = fakeModel(size = 4096)
        val spec = specFor(bytes).copy(approxBytes = 100) // max = 100 + 5 + 1 MiB > 4096, so shrink further via stage directly
        val installer = ModelInstaller(dir)
        try {
            installer.stage(spec, ByteArrayInputStream(bytes), 1000, installer.stagingFile(spec), { false }) {}
            org.junit.Assert.fail("expected size limit")
        } catch (e: java.io.IOException) { }
    }

    @Test fun downloadGoesThroughConsentAndVerification() = runBlocking {
        val bytes = fakeModel()
        val spec = specFor(bytes)
        val transport = ServingTransport(bytes)
        val audit = MemoryAuditSink()
        val gateway = NetworkConsentGateway(transport, { ConsentDecision.ALLOW_ONCE }, audit, FakeClock(), strictOfflineInitially = false)
        val out = ModelDownloader(gateway, ModelInstaller(dir), audit).download(spec)
        assertTrue(out is InstallOutcome.Installed)
        assertEquals(2, transport.calls) // initial + one approved CDN redirect
    }

    @Test fun declinedDownloadMakesNoCallsAndInstallsNothing() = runBlocking {
        val bytes = fakeModel()
        val transport = ServingTransport(bytes)
        val gateway = NetworkConsentGateway(transport, { ConsentDecision.USE_OFFLINE_ALTERNATIVE }, MemoryAuditSink(), FakeClock(), strictOfflineInitially = false)
        val out = ModelDownloader(gateway, ModelInstaller(dir), MemoryAuditSink()).download(specFor(bytes))
        assertEquals(InstallOutcome.NotDownloaded(NetworkOutcome.Declined(ConsentDecision.USE_OFFLINE_ALTERNATIVE)), out)
        assertEquals(0, transport.calls)
        assertTrue(ModelInstaller(dir).installed().isEmpty())
    }

    @Test fun strictOfflineByDefaultBlocksDownload() = runBlocking {
        val transport = ServingTransport(fakeModel())
        val gateway = NetworkConsentGateway(transport, { ConsentDecision.ALLOW_ONCE }, MemoryAuditSink(), FakeClock())
        val out = ModelDownloader(gateway, ModelInstaller(dir), MemoryAuditSink()).download(specFor(fakeModel()))
        assertEquals(InstallOutcome.NotDownloaded(NetworkOutcome.BlockedByOfflineMode), out)
        assertEquals(0, transport.calls)
    }

    @Test fun resourceAdvisorIsConservative() {
        val small = ResourceAdvisor.Device(3_000_000_000, 1_000_000_000, 10_000_000_000, 8, false)
        assertFalse(ResourceAdvisor.canRun(ModelRegistry.byId("whisper-medium")!!, small).fits)
        assertTrue(ResourceAdvisor.canRun(ModelRegistry.byId("whisper-base")!!, small).fits)
        assertEquals("whisper-base", ResourceAdvisor.recommendedModelId(small.copy(totalRamBytes = 4_000_000_000), wantsHindi = true))
        assertFalse(ResourceAdvisor.canInstall(ModelRegistry.byId("whisper-small")!!, small.copy(freeStorageBytes = 100_000_000)).fits)
    }

    private object NoNetwork : Transport {
        override fun exchange(ticket: ConnectionTicket, request: HopRequest, onResponse: (HopResponse) -> Unit, isCancelled: () -> Boolean): HopResult =
            error("no network in this test")
    }

    private class ServingTransport(private val bytes: ByteArray) : Transport {
        var calls = 0
        override fun exchange(ticket: ConnectionTicket, request: HopRequest, onResponse: (HopResponse) -> Unit, isCancelled: () -> Boolean): HopResult {
            ticket.claim(request.url, request.method)
            calls++
            if (request.url.startsWith("https://huggingface.co/")) return HopResult.Redirect("https://cas-bridge.xethub.hf.co/blob?sig=1")
            onResponse(HopResponse(200, bytes.size.toLong(), "application/octet-stream", ByteArrayInputStream(bytes)))
            return HopResult.Done(200)
        }
    }
}

class ThreadAdvisorTest {
    @org.junit.Test fun picksPerformanceCores() {
        // 4 × 1.8 GHz little + 3 × 2.85 GHz big + 1 × 3.2 GHz prime  (Snapdragon-style 4+3+1)
        val f = listOf(1_800_000L, 1_800_000, 1_800_000, 1_800_000, 2_850_000, 2_850_000, 2_850_000, 3_200_000)
        org.junit.Assert.assertEquals(4, ResourceAdvisor.performanceThreads(f, 6))
        // 6 little + 2 big (Helio-style): use the 2 big cores
        org.junit.Assert.assertEquals(2, ResourceAdvisor.performanceThreads(List(6) { 1_800_000L } + listOf(2_200_000L, 2_200_000L), 6))
        // all equal (emulator / old phone): use them all, capped at 8
        org.junit.Assert.assertEquals(4, ResourceAdvisor.performanceThreads(List(4) { 2_000_000L }, 2))
        // unknown → fallback
        org.junit.Assert.assertEquals(6, ResourceAdvisor.performanceThreads(emptyList(), 6))
        // 1 prime + 3 big + 4 little where big = 0.7 × prime → prime alone is not enough
        org.junit.Assert.assertEquals(4, ResourceAdvisor.performanceThreads(listOf(3_000_000L, 2_100_000, 2_100_000, 2_100_000, 1_500_000, 1_500_000, 1_500_000, 1_500_000), 6))
    }
}
