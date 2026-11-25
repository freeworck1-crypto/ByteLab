# ByteLab release shrinking rules.
# The app's own code is kept whole (it uses reflection, JNI and Gson models);
# only unused LIBRARY code and unused resources are removed.
-dontobfuscate
-ignorewarnings
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**

-keep class com.vectras.** { *; }
-keep class com.niaznix.** { *; }
-keep class com.termux.** { *; }
-keep class com.anbui.** { *; }
-keep class com.antlersoft.** { *; }
-keep class android.androidVNC.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-keep class android.media.LoudnessCodecController { *; }

# Gson / Retrofit models and annotations
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
