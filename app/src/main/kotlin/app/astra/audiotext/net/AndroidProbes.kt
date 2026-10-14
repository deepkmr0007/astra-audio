package app.astra.audiotext.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.astra.core.privacy.ConnectivityProbe
import app.astra.core.privacy.ForegroundState

/** Asks the OS whether any network is up. Generates no traffic. */
class AndroidConnectivityProbe(context: Context) : ConnectivityProbe {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    override fun hasConnectivity(): Boolean {
        val network = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/** True while any of the app's activities is at least STARTED (visible). */
class ProcessForegroundState : ForegroundState {
    override fun isInForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
