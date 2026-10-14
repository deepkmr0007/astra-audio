package app.astra.engine.whisper

/**
 * Raw JNI surface of libastra_whisper.so (see src/main/cpp/astra_whisper_jni.cpp).
 * Text is returned as UTF-8 bytes and decoded on the Kotlin side so partial multi-byte
 * characters inside Whisper tokens can never crash the VM.
 */
object WhisperNative {

    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("astra_whisper")
            loaded = true
        }
    }

    /** Loads the best CPU kernel variant for this phone from [nativeLibDir]; returns device count. */
    @JvmStatic external fun nativeLoadBackends(nativeLibDir: String): Int
    @JvmStatic external fun nativeBackendInfo(): String
    @JvmStatic external fun nativeVersion(): String
    @JvmStatic external fun nativeSystemInfo(): String
    @JvmStatic external fun nativeInit(modelPath: String): Long
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeIsMultilingual(handle: Long): Boolean
    @JvmStatic external fun nativeRequestCancel(handle: Long)
    @JvmStatic external fun nativeProgress(handle: Long): Int
    @JvmStatic external fun nativeDrainSegments(handle: Long): Array<Any>
    @JvmStatic external fun nativeTranscribe(
        handle: Long, samples: FloatArray, nThreads: Int, language: String, translate: Boolean,
        beamSize: Int, bestOf: Int, temperatureFallback: Boolean, initialPrompt: String?,
        tokenTimestamps: Boolean, speakerTurns: Boolean,
    ): Int
    @JvmStatic external fun nativeDetectedLanguage(handle: Long): String?
    @JvmStatic external fun nativeSegmentCount(handle: Long): Int
    @JvmStatic external fun nativeSegmentInfo(handle: Long, i: Int): LongArray
    @JvmStatic external fun nativeSegmentText(handle: Long, i: Int): ByteArray
    @JvmStatic external fun nativeTokenCount(handle: Long, i: Int): Int
    @JvmStatic external fun nativeTokenText(handle: Long, i: Int, j: Int): ByteArray?
    @JvmStatic external fun nativeTokenData(handle: Long, i: Int, j: Int, withTimes: Boolean): FloatArray
}
