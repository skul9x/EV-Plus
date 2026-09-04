# ==============================================================================
# ProGuard & R8 Optimization Rules for EV Plus
# ==============================================================================

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses, EnclosingMethod

-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

-keep @kotlinx.serialization.Serializable class * {
    <init>(...);
    static ** Companion;
}

-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclassmembers class **$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep class * implements kotlinx.serialization.KSerializer {
    <init>(...);
}

-dontwarn kotlinx.serialization.**

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keepclassmembers class okhttp3.** {
    *;
}

# AndroidX Security Crypto
-keepclassmembers class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# Google Play Services Location
-keep class com.google.android.gms.location.** { *; }
-keep interface com.google.android.gms.location.** { *; }

# Jetpack Compose
-keepattributes *Annotation*
-keepclassmembers class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# Coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}

# General / Android
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
