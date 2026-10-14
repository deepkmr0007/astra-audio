// COMPILE-CHECK STUBS ONLY — never shipped.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.geometry

@JvmInline value class Offset(val packed: Long) { val x: Float get() = 0f; val y: Float get() = 0f }
fun Offset(x: Float, y: Float): Offset = TODO()
@JvmInline value class Size(val packed: Long) { val width: Float get() = 0f; val height: Float get() = 0f }
