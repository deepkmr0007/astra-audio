// COMPILE-CHECK STUBS ONLY — never shipped.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.unit

@JvmInline value class Dp(val value: Float) {
    operator fun times(other: Float): Dp = Dp(value * other)
    operator fun plus(other: Dp): Dp = Dp(value + other.value)
    companion object { val Unspecified = Dp(Float.NaN) }
}
val Int.dp: Dp get() = Dp(toFloat())
val Double.dp: Dp get() = Dp(toFloat())
val Float.dp: Dp get() = Dp(this)
@JvmInline value class TextUnit(val raw: Long) { companion object { val Unspecified = TextUnit(0) } }
val Int.sp: TextUnit get() = TODO()
val Double.sp: TextUnit get() = TODO()
interface Density { val density: Float; val fontScale: Float }
fun Density(density: Float, fontScale: Float = 1f): Density = TODO()
@JvmInline value class IntSize(val packed: Long) { val width: Int get() = 0; val height: Int get() = 0 }
