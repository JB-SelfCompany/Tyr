# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# UniFFI + JNA keep rules
-keep class com.sun.jna.** { *; }
-keep class uniffi.yggmail_mobile.** { *; }
-keep class * implements com.sun.jna.Library { *; }

# JNA references java.awt.* (desktop GUI, absent on Android). Without this R8 aborts
# minifyReleaseWithR8 on the missing classes (Native$AWT → java.awt.Component/Window/...).
-dontwarn java.awt.**

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep custom model classes
-keepclassmembers class com.jbselfcompany.tyr.data.** { *; }

# Suppress warnings for missing JSR-305 annotations used by Tink
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
