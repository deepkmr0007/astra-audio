// COMPILE-CHECK STUBS ONLY — never shipped.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.text.style

class TextDecoration { companion object { val None = TextDecoration(); val LineThrough = TextDecoration(); val Underline = TextDecoration() } }
@JvmInline value class TextOverflow(val v: Int) { companion object { val Clip = TextOverflow(0); val Ellipsis = TextOverflow(1) } }
@JvmInline value class TextAlign(val v: Int) { companion object { val Start = TextAlign(0); val Center = TextAlign(1) } }
