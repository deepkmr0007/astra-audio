// COMPILE-CHECK STUBS ONLY — never shipped.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.input.pointer

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize

interface PointerInputScope { val size: IntSize }
fun Modifier.pointerInput(key1: Any?, block: suspend PointerInputScope.() -> Unit): Modifier = this
