# Proguard rules for VK Tracks
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}

# BuildConfig & Media3
-keep class com.vktracks.player.BuildConfig { *; }
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# OkHttp / Okio optional JVM platforms
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Security Crypto / Tink
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# WebView JS / AndroidX Lifecycle
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
