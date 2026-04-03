# Add project specific ProGuard rules here.
-keep class org.eastercon2026.prog.model.** { *; }
-keep class org.eastercon2026.prog.db.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**

# Gson
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# OkHttp
-keepnames class okhttp3.** { *; }

# Room
-keep class androidx.room.** { *; }
