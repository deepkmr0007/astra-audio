// COMPILE-CHECK STUBS ONLY — never shipped.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.graphics.drawscope

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

interface DrawScope {
    val size: Size
    fun drawLine(color: Color, start: Offset, end: Offset, strokeWidth: Float = 0f, alpha: Float = 1f)
}
