// COMPILE-CHECK STUBS ONLY — never shipped.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.semantics

import androidx.compose.ui.Modifier

interface SemanticsPropertyReceiver
var SemanticsPropertyReceiver.contentDescription: String
    get() = TODO()
    set(value) {}
enum class LiveRegionMode { Polite, Assertive }
var SemanticsPropertyReceiver.liveRegion: LiveRegionMode
    get() = TODO()
    set(value) {}
@JvmInline value class Role(val v: Int) { companion object { val Button = Role(0); val Switch = Role(1); val Checkbox = Role(2) } }
var SemanticsPropertyReceiver.role: Role
    get() = TODO()
    set(value) {}
fun Modifier.semantics(mergeDescendants: Boolean = false, properties: SemanticsPropertyReceiver.() -> Unit): Modifier = this
