// COMPILE-CHECK STUBS ONLY — never shipped.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.foundation.shape

import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp

open class CornerBasedShape : Shape
class RoundedCornerShape : CornerBasedShape()
fun RoundedCornerShape(size: Dp): RoundedCornerShape = TODO()
fun RoundedCornerShape(percent: Int): RoundedCornerShape = TODO()
val CircleShape: RoundedCornerShape get() = TODO()
