// COMPILE-CHECK STUBS ONLY — never shipped.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.biometric

import androidx.fragment.app.FragmentActivity
import java.util.concurrent.Executor

class BiometricPrompt(activity: FragmentActivity, executor: Executor, callback: AuthenticationCallback) {
    abstract class AuthenticationCallback {
        open fun onAuthenticationError(errorCode: Int, errString: CharSequence) {}
        open fun onAuthenticationSucceeded(result: AuthenticationResult) {}
        open fun onAuthenticationFailed() {}
    }
    class AuthenticationResult
    class PromptInfo {
        class Builder {
            fun setTitle(title: CharSequence): Builder = this
            fun setSubtitle(subtitle: CharSequence?): Builder = this
            fun setAllowedAuthenticators(a: Int): Builder = this
            fun build(): PromptInfo = TODO()
        }
    }
    fun authenticate(info: PromptInfo) {}
}
