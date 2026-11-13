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
