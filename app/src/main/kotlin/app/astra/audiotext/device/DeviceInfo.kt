package app.astra.audiotext.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import app.astra.core.models.ResourceAdvisor

/**
 * Reads only coarse capability information through public APIs (RAM, free storage, CPU cores,
 * thermal state, battery). No device identifiers are read.
 */
class DeviceInfo(private val context: Context) {

    fun snapshot(): ResourceAdvisor.Device {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        return ResourceAdvisor.Device(
            totalRamBytes = mi.totalMem,
            availRamBytes = mi.availMem,
            freeStorageBytes = StatFs(context.filesDir.absolutePath).availableBytes,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            lowRamDevice = am?.isLowRamDevice ?: false,
        )
    }

    /**
     * Thread count for speech recognition: the phone's performance cores only (see
     * ResourceAdvisor.performanceThreads). Reads each core's max frequency from sysfs (world-readable,
     * no permission, no identifiers).
     */
    fun recommendedThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val freqs = (0 until cores).map { i ->
            runCatching { java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrDefault(0L)
        }
        return ResourceAdvisor.performanceThreads(freqs, ResourceAdvisor.threads(snapshot()))
    }

    enum class Thermal { NORMAL, WARM, HOT, UNKNOWN }

    fun thermal(): Thermal {
        if (Build.VERSION.SDK_INT < 29) return Thermal.UNKNOWN
        val pm = context.getSystemService(PowerManager::class.java) ?: return Thermal.UNKNOWN
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> Thermal.NORMAL
            PowerManager.THERMAL_STATUS_MODERATE -> Thermal.WARM
            else -> Thermal.HOT
        }
    }

    data class Battery(val percent: Int, val charging: Boolean, val powerSave: Boolean)

    fun battery(): Battery {
        // Reads the sticky battery broadcast (no receiver is registered).
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent: Intent? = if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(null, filter)
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pm = context.getSystemService(PowerManager::class.java)
        return Battery(
            percent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            powerSave = pm?.isPowerSaveMode ?: false,
        )
    }

    /** A warning to show before sustained heavy processing, or null. */
    fun heavyWorkWarning(): String? {
        val t = thermal()
        val b = battery()
        return when {
            t == Thermal.HOT -> "The phone is hot. Transcription will be slow and may be throttled; consider waiting or using the Fast profile."
            b.percent in 0..15 && !b.charging -> "Battery is at ${b.percent}%. Long transcriptions use a lot of power; consider charging first."
            b.powerSave -> "Battery saver is on; transcription will be slower."
            t == Thermal.WARM -> "The phone is warm; long jobs may slow down."
            else -> null
        }
    }
}
