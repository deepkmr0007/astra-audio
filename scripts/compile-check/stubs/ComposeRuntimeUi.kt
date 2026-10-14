// COMPILE-CHECK STUBS ONLY — never shipped. Mirrors the Jetpack Compose runtime/ui APIs used by the app.
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.compose.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlin.reflect.KProperty

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER, AnnotationTarget.PROPERTY_GETTER)
annotation class Composable

interface State<out T> { val value: T }
interface MutableState<T> : State<T> { override var value: T }
fun <T> mutableStateOf(value: T): MutableState<T> = TODO()
operator fun <T> State<T>.getValue(thisObj: Any?, property: KProperty<*>): T = value
operator fun <T> MutableState<T>.setValue(thisObj: Any?, property: KProperty<*>, value: T) { this.value = value }

@Composable inline fun <T> remember(crossinline calculation: () -> T): T = calculation()
@Composable inline fun <T> remember(key1: Any?, crossinline calculation: () -> T): T = calculation()
@Composable inline fun <T> remember(key1: Any?, key2: Any?, crossinline calculation: () -> T): T = calculation()
@Composable inline fun <T> remember(key1: Any?, key2: Any?, key3: Any?, crossinline calculation: () -> T): T = calculation()
@Composable inline fun <T> remember(vararg keys: Any?, crossinline calculation: () -> T): T = calculation()

@Composable fun <T> StateFlow<T>.collectAsState(context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext): State<T> = TODO()

@Composable fun LaunchedEffect(key1: Any?, block: suspend CoroutineScope.() -> Unit) {}
@Composable fun LaunchedEffect(key1: Any?, key2: Any?, block: suspend CoroutineScope.() -> Unit) {}
@Composable fun LaunchedEffect(key1: Any?, key2: Any?, key3: Any?, block: suspend CoroutineScope.() -> Unit) {}

class DisposableEffectResult
class DisposableEffectScope { inline fun onDispose(crossinline onDisposeEffect: () -> Unit): DisposableEffectResult = TODO() }
@Composable fun DisposableEffect(key1: Any?, effect: DisposableEffectScope.() -> DisposableEffectResult) {}

@Composable fun rememberCoroutineScope(): CoroutineScope = TODO()
@Composable fun <T> rememberUpdatedState(newValue: T): State<T> = TODO()

class SnapshotStateMap<K, V> : MutableMap<K, V> by HashMap()
fun <K, V> mutableStateMapOf(): SnapshotStateMap<K, V> = SnapshotStateMap()

class ProvidedValue<T>
abstract class CompositionLocal<T> { val current: T @Composable get() = TODO() }
abstract class ProvidableCompositionLocal<T> : CompositionLocal<T>() { infix fun provides(value: T): ProvidedValue<T> = TODO() }
@Composable fun CompositionLocalProvider(vararg values: ProvidedValue<*>, content: @Composable () -> Unit) {}
