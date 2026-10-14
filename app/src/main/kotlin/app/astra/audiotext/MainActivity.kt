package app.astra.audiotext

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.astra.audiotext.i18n.LocaleHelper
import app.astra.audiotext.ui.AstraRoot
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Single activity hosting the Compose UI. FragmentActivity is required by BiometricPrompt.
 * Performs no network activity (checked by NetworkBypassScanTest).
 */
class MainActivity : FragmentActivity() {

    private val container: AppContainer get() = (application as AstraApp).container

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase, LocaleHelper.stored(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            container.settings.state.collectLatest { s ->
                if (s.blockScreenshots) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        setContent {
            AstraRoot(container = container, activity = this)
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            container.consent.cancelPending()
            container.player.release()
        }
        super.onDestroy()
    }
}
