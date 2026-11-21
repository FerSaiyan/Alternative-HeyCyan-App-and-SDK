# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Shizuku creates this Binder user service by its class name outside the normal Android manifest.
-keep class com.fersaiyan.cyanbridge.localagent.shizuku.LocalAgentShizukuUserService { <init>(...); *; }

# Optional LiteRT-LM fork API is looked up by name; stock SDK has no such method.
-keepclassmembers class com.google.ai.edge.litertlm.Engine {
    public float[] scoreDecision(byte[], java.lang.String[]);
}

# HeyCyanThumbnailReceiver reuses the vendor's exact FD framing/queue implementation.
-keepclassmembers class com.oudmon.ble.base.communication.LargeDataHandler {
    private void syncPictureThumbnails(int);
}

# ---------------------------------------------------------------------------------------------
# Release shrinking, phase 1 (conservative): R8 only removes unreachable code and resources.
# No renaming and no optimization, so reflection, JNI callbacks and Play stack traces behave
# exactly as in the unminified builds.
# ---------------------------------------------------------------------------------------------
-dontobfuscate
-dontoptimize
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,SourceFile,LineNumberTable
-keepattributes *Annotation*,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

# App and shared KMP code: kept whole (Room, Gson models, Compose resources, JNI bridges,
# reflection in PlatformFilePaths/BatteryResponseParser, workers and services by name).
-keep class com.fersaiyan.** { *; }

# Vendor glasses SDKs and native-backed libraries. Most ship no consumer ProGuard rules and use
# reflection, JNI callbacks or obfuscated internals, so keep them whole.
-keep class com.oudmon.** { *; }
-keep class com.heycyan.** { *; }
-keep class com.glasses.** { *; }
-keep class com.glasssutdio.** { *; }
-keep class com.moyoung.** { *; }
-keep class org.a.** { *; }
-keep class org.jvcompress.** { *; }
-keep class com.myvu.** { *; }
-keep class com.mentra.** { *; }
-keep class mentraos.** { *; }
-keep class com.meta.** { *; }
-keep class com.facebook.** { *; }
-keep class com.oculus.** { *; }
-keep class org.videolan.** { *; }
-keep class com.google.ai.edge.** { *; }
-keep class org.tensorflow.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class com.dokar.** { *; }
-keep class io.github.jaredmdobson.** { *; }
-keep class com.chad.library.** { *; }
-keep class com.hjq.** { *; }
-keep class com.xiasuhuei321.** { *; }

# Native methods anywhere keep their names.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# EventBus (vendor SDKs and the app subscribe by annotation).
-keepclassmembers class * { @org.greenrobot.eventbus.Subscribe <methods>; }
-keep enum org.greenrobot.eventbus.ThreadMode { *; }

# WebView bridges (miniapps, local agent pages).
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }

# Optional dependencies that vendor SDKs reference but CyanBridge never shipped (the unminified
# builds lacked them too, so runtime behaviour is unchanged).
#   Mentra SDK: Expo modules, sherpa-onnx STT/TTS, Vuzix Ultralite, VAD, NanoHTTPD, Picasso, AVIF.
#   MoYoung SDK: JieLi audio decode + OTA, mltcode pay certification.
-dontwarn expo.modules.**
-dontwarn com.k2fsa.sherpa.**
-dontwarn com.vuzix.ultralite.**
-dontwarn com.konovalov.vad.**
-dontwarn fi.iki.elonen.**
-dontwarn com.squareup.picasso.**
-dontwarn com.radzivon.bartoshyk.**
-dontwarn com.jieli.**
-dontwarn com.android.mltcode.**
-dontwarn com.facebook.common.build.BuildConstants
