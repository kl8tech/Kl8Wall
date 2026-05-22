# KL8Wall ProGuard Rules

# Strip all Android log calls in release builds (security: prevents token leakage)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class cloud.kl8techgroup.kl8wall.** {
    *** Companion;
}
-keepclasseswithmembers class cloud.kl8techgroup.kl8wall.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Keep JmDNS
-keep class javax.jmdns.** { *; }

# Keep Argon2kt native bindings
-keep class com.lambdapioneer.argon2kt.** { *; }

# Suppress warnings for compile-only annotations from Tink / other libraries
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
