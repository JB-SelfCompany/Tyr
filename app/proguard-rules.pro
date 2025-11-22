# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Yggmail library classes
-keep class mobile.** { *; }
-keep interface mobile.** { *; }
-keepclassmembers class mobile.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep custom model classes
-keepclassmembers class com.jbselfcompany.tyr.data.** { *; }

# Suppress warnings for missing JSR-305 annotations used by Tink
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
