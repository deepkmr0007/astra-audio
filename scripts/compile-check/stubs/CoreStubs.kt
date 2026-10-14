// COMPILE-CHECK STUBS ONLY — never shipped.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.core.content

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.concurrent.Executor

object ContextCompat {
    @JvmStatic fun checkSelfPermission(context: Context, permission: String): Int = TODO()
    @JvmStatic fun getMainExecutor(context: Context): Executor = TODO()
}

open class FileProvider {
    companion object { @JvmStatic fun getUriForFile(context: Context, authority: String, file: File): Uri = TODO() }
}
