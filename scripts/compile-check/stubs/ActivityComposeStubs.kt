// COMPILE-CHECK STUBS ONLY — never shipped.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.activity.compose

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable

fun ComponentActivity.setContent(parent: Any? = null, content: @Composable () -> Unit) {}

@Composable fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {}

class ManagedActivityResultLauncher<I, O> { fun launch(input: I) {} }

@Composable fun <I, O> rememberLauncherForActivityResult(contract: ActivityResultContract<I, O>, onResult: (O) -> Unit): ManagedActivityResultLauncher<I, O> = TODO()
