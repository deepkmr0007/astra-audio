// COMPILE-CHECK STUBS ONLY — never shipped.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.foundation.lazy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

interface LazyItemScope
interface LazyListScope {
    fun item(key: Any? = null, contentType: Any? = null, content: @Composable LazyItemScope.() -> Unit)
}
inline fun <T> LazyListScope.items(items: List<T>, noinline key: ((item: T) -> Any)? = null, noinline contentType: (item: T) -> Any? = { null },
                                    crossinline itemContent: @Composable LazyItemScope.(item: T) -> Unit) {}
inline fun <T> LazyListScope.itemsIndexed(items: List<T>, noinline key: ((index: Int, item: T) -> Any)? = null,
                                           crossinline contentType: (index: Int, item: T) -> Any? = { _, _ -> null },
                                           crossinline itemContent: @Composable LazyItemScope.(index: Int, item: T) -> Unit) {}
@Composable fun LazyColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = TODO(),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: LazyListScope.() -> Unit,
) {}
