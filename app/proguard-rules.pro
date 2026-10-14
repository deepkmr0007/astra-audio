# Keep JNI bridge (also provided by engine-whisper consumer rules).
-keep class app.astra.engine.whisper.WhisperNative { *; }
# Strip all android.util.Log calls from release builds: the app never logs content, and this
# guarantees no debug logging survives into production.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
