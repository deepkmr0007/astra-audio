// COMPILE-CHECK STUBS ONLY — never shipped.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.foundation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role

class ScrollState
@Composable fun rememberScrollState(initial: Int = 0): ScrollState = TODO()
fun Modifier.verticalScroll(state: ScrollState, enabled: Boolean = true, reverseScrolling: Boolean = false): Modifier = this
fun Modifier.horizontalScroll(state: ScrollState, enabled: Boolean = true, reverseScrolling: Boolean = false): Modifier = this
fun Modifier.clickable(enabled: Boolean = true, onClickLabel: String? = null, role: Role? = null, onClick: () -> Unit): Modifier = this
fun Modifier.background(color: Color, shape: Shape = RectangleShape): Modifier = this
@Composable fun isSystemInDarkTheme(): Boolean = false
@Composable fun Canvas(modifier: Modifier, onDraw: DrawScope.() -> Unit) {}
class BorderStroke
