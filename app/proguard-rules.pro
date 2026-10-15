# Release rules. Room, OkHttp, AndroidX and kotlinx ship their own consumer rules.

# No debug/info/verbose logging in release builds (SMS-derived data must not reach logcat).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Keep line numbers for readable crash stack traces; hide the original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Ktor's IntelliJ debugger detector references JVM-only management classes; never called on Android.
-dontwarn java.lang.management.**
