// COMPILE-CHECK STUBS ONLY — never shipped.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.graphics

class Color(val v: ULong) {
    companion object {
        val White: Color = Color(0uL)
        val Black: Color = Color(0uL)
        val Transparent: Color = Color(0uL)
        val Unspecified: Color = Color(0uL)
        val Red: Color = Color(0uL)
    }
    fun copy(alpha: Float = 1f): Color = this
}
fun Color(color: Long): Color = TODO()
fun Color(color: Int): Color = TODO()
interface Shape
val RectangleShape: Shape get() = TODO()
