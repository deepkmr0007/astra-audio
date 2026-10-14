// COMPILE-CHECK STUBS ONLY — never shipped.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui

interface Modifier {
    fun then(other: Modifier): Modifier = this
    companion object : Modifier
}

interface Alignment {
    interface Horizontal
    interface Vertical
    companion object {
        val TopStart: Alignment = TODO()
        val Center: Alignment = TODO()
        val Top: Vertical = TODO()
        val CenterVertically: Vertical = TODO()
        val Bottom: Vertical = TODO()
        val Start: Horizontal = TODO()
        val CenterHorizontally: Horizontal = TODO()
        val End: Horizontal = TODO()
    }
}
