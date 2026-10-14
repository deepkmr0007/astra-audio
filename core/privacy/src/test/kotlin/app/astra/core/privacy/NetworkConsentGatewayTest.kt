package app.astra.core.privacy

import app.astra.core.common.FakeClock
import app.astra.core.common.Hashing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

/** A transport that records every connection attempt instead of touching the network. */
class FakeTransport(
    private val script: (HopRequest) -> HopResult = { HopResult.Done(200) },
    private val throwOnCall: Exception? = null,
) : Transport {
    val calls = mutableListOf<HopRequest>()
    override fun exchange(ticket: ConnectionTicket, request: HopRequest, onResponse: (HopResponse) -> Unit, isCancelled: () -> Boolean): HopResult {
        ticket.claim(request.url, request.method) // must succeed, exactly like the real transport
        calls.add(request)
        throwOnCall?.let { throw it }
        val r = script(request)
        if (r is HopResult.Done) onResponse(HopResponse(r.status, 3, "application/octet-stream", ByteArrayInputStream(byteArrayOf(1, 2, 3))))
        return r
    }
}

class CountingPrompter(var answer: ConsentDecision) : ConsentPrompter {
    val asked = mutableListOf<ConsentRequest>()
    override suspend fun ask(request: ConsentRequest): ConsentDecision { asked.add(request); return answer }
}

class NetworkConsentGatewayTest {

    private val clock = FakeClock()
    private val audit = MemoryAuditSink()

    private fun request(op: String = "op-1", url: String = "https://huggingface.co/model.bin",
                        hosts: Set<String> = setOf("huggingface.co", "cdn-lfs.hf.co")) = ConsentRequest(
        operationId = op, task = "Download model", method = "GET", url = url, allowedHosts = hosts,
        payloadClass = PayloadClass.NONE, payloadDescription = "Nothing", reason = "Model not installed",
    )

    private fun gateway(transport: Transport, prompter: ConsentPrompter, offline: Boolean = false,
                        online: Boolean = true, foreground: Boolean = true) =
        NetworkConsentGateway(transport, prompter, audit, clock, { online }, { foreground }, strictOfflineInitially = offline)

    @Test fun constructingGatewayMakesNoNetworkCalls() {
        val t = FakeTransport()
        val g = gateway(t, CountingPrompter(ConsentDecision.ALLOW_ONCE))
        assertEquals(NetworkStatus.Offline, g.status.value)
        assertTrue(t.calls.isEmpty())
        assertTrue(audit.events.isEmpty())
    }

    @Test fun cancelledRequestMakesZeroCalls() = runBlocking {
        val t = FakeTransport()
        val p = CountingPrompter(ConsentDecision.CANCEL)
        val out = gateway(t, p).execute(request()) { }
        assertEquals(NetworkOutcome.Declined(ConsentDecision.CANCEL), out)
        assertEquals(1, p.asked.size)
        assertTrue("denied request must not connect", t.calls.isEmpty())
        assertTrue(AuditType.NETWORK_DECLINED in audit.types())
    }

    @Test fun offlineAlternativeMakesZeroCalls() = runBlocking {
        val t = FakeTransport()
        val out = gateway(t, CountingPrompter(ConsentDecision.USE_OFFLINE_ALTERNATIVE)).execute(request()) { }
        assertEquals(NetworkOutcome.Declined(ConsentDecision.USE_OFFLINE_ALTERNATIVE), out)
        assertTrue(t.calls.isEmpty())
    }

    @Test fun deniedRequestIsNeverRetried() = runBlocking {
        val t = FakeTransport()
        val p = CountingPrompter(ConsentDecision.CANCEL)
        val g = gateway(t, p)
        g.execute(request()) { }
        // Nothing happens afterwards on its own: no calls, no further prompts.
        assertEquals(1, p.asked.size)
        assertTrue(t.calls.isEmpty())
        assertEquals(0, g.tokens.liveTokenCount())
    }

    @Test fun strictOfflineBlocksWithoutPrompting() = runBlocking {
        val t = FakeTransport()
        val p = CountingPrompter(ConsentDecision.ALLOW_ONCE)
        val out = gateway(t, p, offline = true).execute(request()) { }
        assertEquals(NetworkOutcome.BlockedByOfflineMode, out)
        assertTrue(p.asked.isEmpty())
        assertTrue(t.calls.isEmpty())
    }

    @Test fun backgroundRequestsAreBlocked() = runBlocking {
        val t = FakeTransport()
        val p = CountingPrompter(ConsentDecision.ALLOW_ONCE)
        val out = gateway(t, p, foreground = false).execute(request()) { }
        assertEquals(NetworkOutcome.BlockedInBackground, out)
        assertTrue(p.asked.isEmpty())
        assertTrue(t.calls.isEmpty())
    }

    @Test fun deviceOfflineDoesNotPrompt() = runBlocking {
        val t = FakeTransport()
        val p = CountingPrompter(ConsentDecision.ALLOW_ONCE)
        val out = gateway(t, p, online = false).execute(request()) { }
        assertEquals(NetworkOutcome.DeviceOffline, out)
        assertTrue(p.asked.isEmpty())
        assertTrue(t.calls.isEmpty())
    }

    @Test fun allowOnceMakesExactlyOneCallAndRetiresToken() = runBlocking {
        val t = FakeTransport()
        val g = gateway(t, CountingPrompter(ConsentDecision.ALLOW_ONCE))
        var bytes = 0
        val out = g.execute(request()) { bytes = it.body.readBytes().size }
        assertEquals(NetworkOutcome.Completed(200, "huggingface.co"), out)
        assertEquals(1, t.calls.size)
        assertEquals(3, bytes)
        assertEquals(0, g.tokens.liveTokenCount())
        assertEquals(NetworkStatus.Offline, g.status.value)
    }

    @Test fun consentIsAskedAgainForEveryOperation() = runBlocking {
        val t = FakeTransport()
        val p = CountingPrompter(ConsentDecision.ALLOW_ONCE)
        val g = gateway(t, p)
        g.execute(request("op-1")) { }
        g.execute(request("op-1")) { } // even the identical request asks again
        g.execute(request("op-2")) { }
        assertEquals(3, p.asked.size)
        assertEquals(3, t.calls.size)
    }

    @Test fun tokenCannotBeReplayedForADifferentRequest() {
        val store = ConsentTokenStore(clock)
        val token = store.issue(request(op = "op-A"))
        assertEquals(TokenRejection.FINGERPRINT_MISMATCH, store.consume(token, request(op = "op-B")))
        assertEquals(TokenRejection.FINGERPRINT_MISMATCH,
            store.consume(token, request(op = "op-A", url = "https://huggingface.co/other.bin")))
        assertEquals(TokenRejection.FINGERPRINT_MISMATCH,
            store.consume(token, request(op = "op-A").copy(payloadClass = PayloadClass.TEXT_CONTENT)))
        assertEquals(TokenRejection.FINGERPRINT_MISMATCH,
            store.consume(token, request(op = "op-A", hosts = setOf("huggingface.co", "evil.example"))))
    }

    @Test fun tokenIsSingleUse() {
        val store = ConsentTokenStore(clock)
        val r = request()
        val token = store.issue(r)
        assertNull(store.consume(token, r))
        assertEquals(TokenRejection.CONSUMED, store.consume(token, r))
    }

    @Test fun tokenExpires() {
        val store = ConsentTokenStore(clock, ttlMillis = 1000)
        val r = request()
        val token = store.issue(r)
        clock.advance(1001)
        assertEquals(TokenRejection.EXPIRED, store.consume(token, r))
    }

    @Test fun retiredOrRevokedTokenIsUnusable() {
        val store = ConsentTokenStore(clock)
        val r = request()
        val t1 = store.issue(r)
        store.retire(t1)
        assertNotNull(store.consume(t1, r))
        val t2 = store.issue(r)
        store.revokeAll()
        assertNotNull(store.consume(t2, r))
    }

    @Test fun ticketCannotBeClaimedTwiceOrForAnotherUrl() {
        val store = ConsentTokenStore(clock)
        val r = request()
        val token = store.issue(r)
        store.consume(token, r)
        val ticket = ConnectionTicket(r.url, "GET", token, store)
        try { ticket.claim("https://huggingface.co/x", "GET"); fail("other URL must be refused") } catch (e: IllegalStateException) { }
        ticket.claim(r.url, "GET")
        try { ticket.claim(r.url, "GET"); fail("second claim must be refused") } catch (e: IllegalStateException) { }
    }

    @Test fun redirectToApprovedHostIsRevalidatedAndFollowed() = runBlocking {
        val t = FakeTransport({ req ->
            if (req.url.contains("huggingface.co")) HopResult.Redirect("https://cdn-lfs.hf.co/blob/123") else HopResult.Done(200)
        })
        val out = gateway(t, CountingPrompter(ConsentDecision.ALLOW_ONCE)).execute(request()) { }
        assertEquals(NetworkOutcome.Completed(200, "cdn-lfs.hf.co"), out)
        assertEquals(listOf("https://huggingface.co/model.bin", "https://cdn-lfs.hf.co/blob/123"), t.calls.map { it.url })
    }

    @Test fun redirectToUnapprovedHostIsBlockedBeforeConnecting() = runBlocking {
        val t = FakeTransport({ req ->
            if (req.url.contains("huggingface.co")) HopResult.Redirect("https://tracker.example.com/x") else HopResult.Done(200)
        })
        val out = gateway(t, CountingPrompter(ConsentDecision.ALLOW_ONCE)).execute(request()) { }
        assertEquals(NetworkOutcome.RedirectBlocked("tracker.example.com"), out)
        assertEquals(1, t.calls.size)
        assertFalse(t.calls.any { it.url.contains("tracker.example.com") })
        assertTrue(AuditType.NETWORK_REDIRECT_BLOCKED in audit.types())
    }

    @Test fun redirectDowngradeToHttpIsBlocked() = runBlocking {
        val t = FakeTransport({ HopResult.Redirect("http://cdn-lfs.hf.co/blob") })
        val out = gateway(t, CountingPrompter(ConsentDecision.ALLOW_ONCE)).execute(request()) { }
        assertTrue(out is NetworkOutcome.RedirectBlocked)
        assertEquals(1, t.calls.size)
    }

    @Test fun redirectLoopsAreBounded() = runBlocking {
        val t = FakeTransport({ HopResult.Redirect("https://huggingface.co/again") })
        val out = gateway(t, CountingPrompter(ConsentDecision.ALLOW_ONCE)).execute(request()) { }
        assertEquals(NetworkOutcome.Failed("Too many redirects"), out)
        assertEquals(6, t.calls.size)
    }

    @Test fun transportFailureIsNotRetried() = runBlocking {
        val t = FakeTransport(throwOnCall = java.io.IOException("connection reset"))
        val p = CountingPrompter(ConsentDecision.ALLOW_ONCE)
        val out = gateway(t, p).execute(request()) { }
        assertTrue(out is NetworkOutcome.Failed)
        assertEquals(1, t.calls.size)
        assertEquals(1, p.asked.size)
    }

    @Test fun insecureOrUnlistedDestinationsAreRejectedWithoutPrompt() = runBlocking {
        val bad = listOf(
            "http://huggingface.co/model.bin",
            "https://user:pw@huggingface.co/model.bin",
            "https://huggingface.co:8443/model.bin",
            "https://93.184.216.34/model.bin",
            "https://example.com/model.bin",
            "ftp://huggingface.co/model.bin",
        )
        for (url in bad) {
            val t = FakeTransport()
            val p = CountingPrompter(ConsentDecision.ALLOW_ONCE)
            val out = gateway(t, p).execute(request(url = url)) { }
            assertTrue("$url should be a policy violation, was $out", out is NetworkOutcome.PolicyViolation)
            assertTrue(p.asked.isEmpty())
            assertTrue(t.calls.isEmpty())
        }
    }

    @Test fun bodyMustMatchApprovedPayload() = runBlocking {
        val approved = "hello".toByteArray()
        val r = request().copy(method = "POST", payloadClass = PayloadClass.TEXT_CONTENT,
            payloadSha256 = Hashing.sha256(approved), payloadPreview = "hello")
        val t = FakeTransport()
        val g = gateway(t, CountingPrompter(ConsentDecision.ALLOW_ONCE))
        assertTrue(g.execute(r, body = "goodbye".toByteArray()) { } is NetworkOutcome.PolicyViolation)
        assertTrue(t.calls.isEmpty())
        assertTrue(g.execute(r, body = approved) { } is NetworkOutcome.Completed)
        assertEquals(1, t.calls.size)
        // A body without a declared payload class is refused.
        assertTrue(g.execute(request(), body = approved) { } is NetworkOutcome.PolicyViolation)
        assertEquals(1, t.calls.size)
    }

    @Test fun killSwitchDuringPromptBlocksTheRequest() = runBlocking {
        val t = FakeTransport()
        lateinit var g: NetworkConsentGateway
        val prompter = ConsentPrompter { g.setStrictOffline(true); ConsentDecision.ALLOW_ONCE }
        g = gateway(t, prompter)
        assertEquals(NetworkOutcome.BlockedByOfflineMode, g.execute(request()) { })
        assertTrue(t.calls.isEmpty())
    }

    @Test fun cancellationStopsBeforeConnecting() = runBlocking {
        val t = FakeTransport()
        val out = gateway(t, CountingPrompter(ConsentDecision.ALLOW_ONCE)).execute(request(), isCancelled = { true }) { }
        assertEquals(NetworkOutcome.Cancelled, out)
        assertTrue(t.calls.isEmpty())
    }

    @Test fun auditNeverContainsPayloadContent() = runBlocking {
        val secret = "my private transcript about the bank meeting"
        val body = secret.toByteArray()
        val r = request().copy(method = "POST", payloadClass = PayloadClass.TEXT_CONTENT,
            payloadSha256 = Hashing.sha256(body), payloadPreview = secret)
        gateway(FakeTransport(), CountingPrompter(ConsentDecision.ALLOW_ONCE)).execute(r, body = body) { }
        assertTrue(audit.events.isNotEmpty())
        for (e in audit.events) assertFalse(e.toJson().contains("bank meeting"))
    }

    @Test fun sendingDeviceIdentifiersIsRefused() = runBlocking {
        val t = FakeTransport()
        val out = gateway(t, CountingPrompter(ConsentDecision.ALLOW_ONCE)).execute(request().copy(sendsDeviceIdentifiers = true)) { }
        assertTrue(out is NetworkOutcome.PolicyViolation)
        assertTrue(t.calls.isEmpty())
    }
}
