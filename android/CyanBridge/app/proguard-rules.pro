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
