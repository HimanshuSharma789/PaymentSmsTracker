# Release rules. Room, OkHttp, AndroidX and kotlinx ship their own consumer rules.

# No debug/info/verbose logging in release builds (SMS-derived data must not reach logcat).
# "return 0" lets R8 drop calls whose Int result is consumed (e.g. a Log.d as a `when` branch value);
# without it those calls, and their message strings, survived into the release dex.
-assumenosideeffects class android.util.Log {
    public static int v(...) return 0;
    public static int d(...) return 0;
    public static int i(...) return 0;
}

# Keep line numbers for readable crash stack traces; hide the original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Ktor's IntelliJ debugger detector references JVM-only management classes; never called on Android.
-dontwarn java.lang.management.**
