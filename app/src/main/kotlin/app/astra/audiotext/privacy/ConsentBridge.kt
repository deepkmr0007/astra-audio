package app.astra.audiotext.privacy

import app.astra.core.privacy.ConsentDecision
import app.astra.core.privacy.ConsentPrompter
import app.astra.core.privacy.ConsentRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Connects the gateway's suspending consent question to the Compose consent dialog.
 * One question at a time; if the UI goes away, the pending question resolves to CANCEL.
 */
class ConsentBridge : ConsentPrompter {

    data class Pending(val request: ConsentRequest, internal val answer: CompletableDeferred<ConsentDecision>)

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()
    private val oneAtATime = Mutex()

    override suspend fun ask(request: ConsentRequest): ConsentDecision = oneAtATime.withLock {
        val deferred = CompletableDeferred<ConsentDecision>()
        _pending.value = Pending(request, deferred)
        try {
            deferred.await()
        } finally {
            _pending.value = null
        }
    }

    /** Called by the dialog buttons. */
    fun answer(decision: ConsentDecision) {
        _pending.value?.answer?.complete(decision)
    }

    /** Called when the activity is destroyed or the app goes to the background. */
    fun cancelPending() {
        _pending.value?.answer?.complete(ConsentDecision.CANCEL)
    }
}
