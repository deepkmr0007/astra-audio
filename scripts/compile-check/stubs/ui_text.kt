// COMPILE-CHECK STUBS ONLY — never shipped.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit

class AnnotatedString(val text: String) {
    class Builder {
        fun append(text: String) {}
        fun toAnnotatedString(): AnnotatedString = TODO()
    }
}
inline fun buildAnnotatedString(builder: AnnotatedString.Builder.() -> Unit): AnnotatedString = TODO()
inline fun <R : Any> AnnotatedString.Builder.withStyle(style: SpanStyle, block: AnnotatedString.Builder.() -> R): R = block()
class SpanStyle(
    val color: Color = Color.Unspecified,
    val fontSize: TextUnit = TextUnit.Unspecified,
    val fontWeight: FontWeight? = null,
    val background: Color = Color.Unspecified,
    val textDecoration: TextDecoration? = null,
)
class TextStyle(
    val color: Color = Color.Unspecified,
    val fontSize: TextUnit = TextUnit.Unspecified,
    val fontWeight: FontWeight? = null,
    val fontFamily: FontFamily? = null,
    val lineHeight: TextUnit = TextUnit.Unspecified,
)
