package app.astra.audiotext.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class Screen {
    data object Home : Screen()
    data object Record : Screen()
    data object Library : Screen()
    data class Item(val id: String) : Screen()
    data class TextTools(val itemId: String? = null) : Screen()
    data object Translate : Screen()
    data class Speak(val text: String = "") : Screen()
    data object Search : Screen()
    data object Models : Screen()
    data object Privacy : Screen()
    data object Settings : Screen()
    data object Audit : Screen()
    data object Benchmark : Screen()
    data object Glossary : Screen()
    data object About : Screen()
}

/** Tiny back-stack. App-scoped, so it survives configuration changes. */
class Navigator {
    private val _stack = MutableStateFlow<List<Screen>>(listOf(Screen.Home))
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()
    val current: Screen get() = _stack.value.last()

    fun go(screen: Screen) {
        if (current == screen) return
        _stack.value = _stack.value + screen
    }

    fun replace(screen: Screen) { _stack.value = _stack.value.dropLast(1) + screen }

    fun back(): Boolean {
        if (_stack.value.size <= 1) return false
        _stack.value = _stack.value.dropLast(1)
        return true
    }

    fun home() { _stack.value = listOf(Screen.Home) }
}
