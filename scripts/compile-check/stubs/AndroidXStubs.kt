// COMPILE-CHECK STUBS ONLY — never shipped. Signatures mirror the real AndroidX APIs used by the app
// (names, parameter names and defaults), so the app's Kotlin can be type-checked on a machine that
// has android.jar but no access to Google's Maven repository. See scripts/compile-check/README.md.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.lifecycle

import kotlinx.coroutines.CoroutineScope

interface LifecycleObserver
interface LifecycleOwner { val lifecycle: Lifecycle }
abstract class Lifecycle {
    abstract val currentState: State
    abstract fun addObserver(observer: LifecycleObserver)
    enum class State { DESTROYED, INITIALIZED, CREATED, STARTED, RESUMED; fun isAtLeast(state: State): Boolean = ordinal >= state.ordinal }
}
interface DefaultLifecycleObserver : LifecycleObserver {
    fun onCreate(owner: LifecycleOwner) {}
    fun onStart(owner: LifecycleOwner) {}
    fun onResume(owner: LifecycleOwner) {}
    fun onPause(owner: LifecycleOwner) {}
    fun onStop(owner: LifecycleOwner) {}
    fun onDestroy(owner: LifecycleOwner) {}
}
class ProcessLifecycleOwner private constructor() : LifecycleOwner {
    override val lifecycle: Lifecycle get() = TODO()
    companion object { @JvmStatic fun get(): LifecycleOwner = TODO() }
}
abstract class LifecycleCoroutineScope : CoroutineScope
val LifecycleOwner.lifecycleScope: LifecycleCoroutineScope get() = TODO()
