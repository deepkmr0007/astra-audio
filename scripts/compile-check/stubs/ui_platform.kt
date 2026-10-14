// COMPILE-CHECK STUBS ONLY — never shipped.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.platform

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density

interface ClipboardManager {
    fun getText(): AnnotatedString?
    fun setText(annotatedString: AnnotatedString)
}
val LocalContext: ProvidableCompositionLocal<android.content.Context> get() = TODO()
val LocalClipboardManager: ProvidableCompositionLocal<ClipboardManager> get() = TODO()
val LocalDensity: ProvidableCompositionLocal<Density> get() = TODO()
