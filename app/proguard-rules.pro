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

-keep class org.spongycastle.**
-dontwarn org.spongycastle.jce.provider.X509LDAPCertStoreSpi
-dontwarn org.spongycastle.x509.util.LDAPStoreHelper

# From Winlator
-dontobfuscate

# Timber Logging
-keep class timber.log.Timber { *; }
-keep class app.gamenative.ReleaseTree { *; }

-keep class horizon.** { *; }
-keep class com.meta.horizon.** { *; }
-dontwarn horizon.**
-dontwarn com.meta.horizon.**

