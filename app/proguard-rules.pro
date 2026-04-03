# Add project specific ProGuard rules here.
-keep class org.eastercon2026.prog.model.** { *; }
-keep class org.eastercon2026.prog.db.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**
