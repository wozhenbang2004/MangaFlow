-keepattributes Signature,InnerClasses,EnclosingMethod
-dontwarn org.slf4j.impl.**

# JNI creates these tensor/result wrappers by their original names and constructors.
-keep class ai.onnxruntime.** { *; }
-keep class org.tensorflow.lite.** { *; }

# Room instantiates WorkManager's generated database through its no-arg constructor.
# AndroidX fix: e4765e38e27a3f4d86188b996df97a52403dff46.
-keep class * extends androidx.room.RoomDatabase {
    void <init>();
}

# 0.1.173 / R8 map 16edb0c16aa7604f127833a5aec326d50d056213527bc3cf027510051c911680:
# ReaderScreen was rewritten/relocated into org.jsoup.helper.Validate (fn5.q),
# which Android 16 rejected with a conflicting register type at if-eqz/if-nez.
# Keep this required composable's signature/holder and skip its optimization;
# all other application shrinking, resource shrinking and obfuscation stay enabled.
-keep,allowobfuscation class dev.mangaflow.reader.ReaderScreenKt {
    public static void ReaderScreen(...);
}

# JNI exports bind to this exact class and method names after an explicit component load.
-keep class dev.mangaflow.storage.SteamNative { *; }
