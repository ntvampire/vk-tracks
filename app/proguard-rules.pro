# Proguard rules for VK Tracks
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}
