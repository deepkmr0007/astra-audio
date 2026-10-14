package app.astra.audiotext

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.launch

/**
 * Application entry point. Deliberately does almost nothing at startup: no network, no analytics,
 * no crash reporters, no background work. (NetworkBypassScanTest checks this file.)
 */
class AstraApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // App left the foreground: pause any recording, cancel pending consent prompts,
                // and re-arm the app lock.
                container.recorder.pause(auto = true)
                container.consent.cancelPending()
                container.dictation.stop()
                container.player.pause()
                if (container.settings.value.appLock) container.appLock.lock()
            }
        })
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            container.scope.launch { container.engines.unload() }
        }
    }
}
