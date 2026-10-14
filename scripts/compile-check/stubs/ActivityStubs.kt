// COMPILE-CHECK STUBS ONLY — never shipped.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.activity

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

open class ComponentActivity : android.app.Activity(), LifecycleOwner {
    override val lifecycle: Lifecycle get() = TODO()
}

class SystemBarStyle
fun ComponentActivity.enableEdgeToEdge(statusBarStyle: SystemBarStyle? = null, navigationBarStyle: SystemBarStyle? = null) {}
