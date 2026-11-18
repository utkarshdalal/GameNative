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

# Needed for release build
-keep class in.dragonbra.javasteam.** { *; }

# UnifiedService subclasses are built via reflection in SteamUnifiedMessages.createService; keep their ctor.
-keep class * extends in.dragonbra.javasteam.steam.handlers.steamunifiedmessages.UnifiedService { *; }

-keep class org.spongycastle.**
-dontwarn org.spongycastle.jce.provider.X509LDAPCertStoreSpi
-dontwarn org.spongycastle.x509.util.LDAPStoreHelper

# From Winlator
-dontobfuscate

# Timber Logging
-keep class timber.log.Timber { *; }
-keep class app.gamenative.ReleaseTree { *; }

# gn-download JNI bridge (libgndownload.so): the Rust engine resolves these classes by their
# JNI-mangled names (Java_app_gamenative_service_download_Native*_native*) and calls the
# listener methods (onProgress/onComplete/onVerifying/onLog/onBytes/onPlan/onAssemblyProgress/
# getCdnAuthToken/refreshManifestRequestCode) by name from native threads. Nothing in bytecode
# invokes the overrides, so R8 shrinking removes/renames them in release builds (debug works
# because it isn't minified) — keep the whole bridge package.
-keep class app.gamenative.service.download.** { *; }

# gndrm display daemon (libgamenative_drm.so): the Rust daemon resolves the callback class by its
# JNI name — FindClass("app/gamenative/display/GameNativeDrm") — and calls two no-arg static
# methods on it by name from native threads (GetStaticMethodID + CallStaticVoidMethodA): the
# booting-splash upcall and the per-published-frame upcall. Nothing in bytecode invokes either
# method (the native daemon is the only caller), so R8 shrinking removes them in
# release builds (debug is unminified). The class itself is reached from Kotlin/Java through its
# native* entry points, so the callbacks need an explicit keep; the native members need their
# names pinned because the JVM derives the symbol it looks up in the daemon from class and method
# name, so a rename breaks the pairing silently at runtime. -dontobfuscate already holds both, and
# keepnames guards the FindClass string should that setting ever change.
-keepclassmembers class app.gamenative.display.GameNativeDrm {
    public static void clearBootingSplash();
    public static void onFramePublished();
    native <methods>;
}
-keepnames class app.gamenative.display.GameNativeDrm

-keep class horizon.** { *; }
-keep class com.meta.horizon.** { *; }
-dontwarn horizon.**
-dontwarn com.meta.horizon.**

# Samsung Performance SDK (bundled stub jar, referenced by powercontrol Samsung driver)
-keep class com.samsung.sdk.sperf.** { *; }
-dontwarn com.samsung.sdk.sperf.**
