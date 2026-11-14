-keepattributes *Annotation*
-keep class javax.inject.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# JNI: viper_jni.cpp looks these up by name.
-keep class com.llsl.viper4android.rootless.ViperNative {
    native <methods>;
}

# Shizuku starts the user service by class name in its own process.
-keep class com.llsl.viper4android.rootless.ShellService { *; }
