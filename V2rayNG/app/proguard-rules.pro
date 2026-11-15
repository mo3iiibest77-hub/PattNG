# NovaPulse R8 Obfuscation, Shrinking & Anti-Reverse-Engineering Rules

-optimizationpasses 5
-allowaccessmodification
-repackageclasses 'np.o'
-renamesourcefileattribute NP
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# Strip verbose/debug/info logs from release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
-assumenosideeffects class com.v2ray.ang.util.LogUtil {
    public static void d(...);
    public static void i(...);
}

# Preserve JNI & native bridges cleanly (libv2ray / gomobile / hev-socks5-tunnel / mmkv)
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class go.** { *; }
-keep class libv2ray.** { *; }
-keep class com.v2ray.ang.service.TProxyService { *; }
-keep class com.tencent.mmkv.** { *; }

# Preserve Gson models & TypeTokens used in reflection serialization/deserialization
-keep class com.v2ray.ang.dto.** { *; }
-keep class com.v2ray.ang.senpai.LicenseRedeemResponse { *; }
-keep class com.v2ray.ang.senpai.PersistedProviderRanges { *; }
-keep class com.v2ray.ang.senpai.SubscriptionQuotaInfo { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Preserve AndroidManifest entry points & custom Application
-keep class com.v2ray.ang.AngApplication { *; }
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.appwidget.AppWidgetProvider
-keep public class * extends androidx.work.Worker
-keep public class * extends androidx.work.CoroutineWorker

# Suppress harmless warnings from optional JVM/reflection libraries
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn java.lang.invoke.**
-dontwarn sun.misc.Unsafe
-dontwarn org.codehaus.mojo.animal_sniffer.**
