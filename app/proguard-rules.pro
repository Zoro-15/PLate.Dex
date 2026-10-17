# ====================================================================
# General Android & Kotlin Keep Rules
# ====================================================================
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod,Exceptions

# Keep all Parcelable and Serializable implementations for IPC/Shizuku
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep enum valueOf/values (used by many libraries via reflection)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ====================================================================
# App Entry Points referenced by name from AndroidManifest.xml
# (Activities, Services, Receivers, Providers, and their metadata
# targets) — R8 does not know these are entry points unless told, and
# renaming/removing them causes ClassNotFoundException at runtime.
# ====================================================================
-keep public class * extends android.app.Activity
-keep public class * extends androidx.appcompat.app.AppCompatActivity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.admin.DeviceAdminReceiver
-keep public class * extends android.accessibilityservice.AccessibilityService
-keep public class * extends android.service.wallpaper.WallpaperService

# The Application subclass (android:name=".App" in the manifest) — same
# reasoning as above; the framework instantiates it by class name at
# process start, so it must survive with its original name.
-keep public class * extends android.app.Application

# ====================================================================
# Fragments/Activities referenced only by class name (string), not by
# compile-time Kotlin/Java reference. Two separate call sites do this:
#
#  1. res/xml/preferences_*.xml — android:fragment="com.plate.launcher.
#     fragments.X" — PreferenceScreen resolves these via reflection
#     using the literal string in the XML.
#  2. QaTargetRegistry.kt — QA self-test builds "$PKG.activities.$it"
#     / "$PKG.fragments.$it" strings and loads them with
#     Class.forName(...).getDeclaredConstructor().newInstance() in
#     QaTestOrchestrator/QaVirtualDisplayHost.
#
# Neither path has a compile-time reference R8 can trace, so without
# this rule R8 renames or strips the class/constructor and either the
# settings screen or (in debug/QA builds only) the self-test throws
# ClassNotFoundException / NoSuchMethodException — never seen in a
# debug run where nothing is renamed.
# Kept broad (whole package) rather than one line per class: this list
# is hand-maintained in QaTargetRegistry.kt and grows independently of
# this file — a per-class allowlist here would silently go stale.
# ====================================================================
-keep class com.plate.launcher.fragments.** { <init>(); *; }
-keep class com.plate.launcher.activities.** { <init>(); *; }

# ====================================================================
# JNI & Native Rust Engine Support
# ====================================================================
# Prevent obfuscation of native methods called by Rust/C code
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
# The JNI bridge itself must keep its exact class name and member
# signatures — Rust calls back into these by fully-qualified name/sig,
# so renaming (even though it's "only Kotlin-side") breaks the JNI
# lookup on the native side, which has no visibility into R8 renaming.
-keep class com.plate.launcher.livewallpaper.NativeBridge {
    *;
}
-keepclassmembers class com.plate.launcher.livewallpaper.NativeBridge {
    *;
}

# ====================================================================
# Google Cast Framework
# ====================================================================
# CastOptionsProvider is looked up by fully-qualified class name from
# an AndroidManifest <meta-data> value, purely via reflection — Cast
# SDK has no compile-time reference to it, so R8 will strip or rename
# it unless explicitly kept, causing a crash the moment cast options
# are requested.
-keep class com.plate.launcher.cast.CastOptionsProvider { *; }
-keep class com.plate.launcher.cast.** { *; }
-keep class * implements com.google.android.gms.cast.framework.OptionsProvider { *; }
-keep class * implements com.google.android.gms.cast.framework.SessionProvider { *; }
-dontwarn com.google.android.gms.cast.**

# ====================================================================
# ColorPicker (jaredrummler)
# DialogFragment restored by the FragmentManager across config changes
# (rotation, dialog re-attach) using its saved class name — same failure
# mode as any Fragment whose name is persisted/looked-up dynamically
# rather than referenced with a compile-time `new` call every time.
# ====================================================================
-keep class com.jaredrummler.android.colorpicker.** { *; }
-dontwarn com.jaredrummler.android.colorpicker.**

# ====================================================================
# ChickenHook RestrictionBypass
# This library registers native (JNI) methods against its own classes
# at runtime via libnrb.so. If R8 renames/strips those classes or their
# method signatures, the native side can't find what it's looking for
# and JNI_OnLoad fails with JNI_ERR — release-only crash on startup.
# ====================================================================
-keep class org.chickenhook.restrictionbypass.** { *; }
-keepclassmembers class org.chickenhook.restrictionbypass.** { *; }
-dontwarn org.chickenhook.restrictionbypass.**

# ====================================================================
# Shizuku & Root Commands
# ====================================================================
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# ====================================================================
# Dynamic UI & Material Components
# ====================================================================
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ====================================================================
# Kotlin Coroutines
# ====================================================================
-dontwarn kotlinx.coroutines.**
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keepclassmembers class kotlinx.coroutines.internal.MainDispatcherFactory {
    <init>(...);
}

# ====================================================================
# Kotlinx Serialization
# (kept broadly even though no @Serializable classes were found at
# audit time — safe no-op if unused, and prevents a silent crash the
# moment a serializable model is added later without updating this file)
# ====================================================================
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class kotlin.Metadata { *; }
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# ====================================================================
# Room (kept defensively — project currently uses plain SQLiteOpenHelper,
# but this is cheap insurance if Room is reintroduced)
# ====================================================================
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# ====================================================================
# OkHttp / Jsoup / Media3 / Coil — standard suppressions for their
# optional/reflective dependency paths that R8 otherwise flags as
# missing classes (these libraries handle the absence gracefully at
# runtime, but R8 without -dontwarn can fail the build or, worse,
# silently strip the fallback path along with the warning)
# ====================================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }
-dontwarn coil.**
-keep class coil.** { *; }

# ====================================================================
# Play Services / Google Play Core (Cast framework transitively pulls
# these in; missing classes here are a very common source of
# "release-only crash on startup" reports)
# ====================================================================
-dontwarn com.google.android.gms.**
-dontwarn com.google.android.play.core.**

# ====================================================================
# Line numbers for readable crash reports
# (keeps LineNumberTable meaningful even with source-name stripping)
# ====================================================================
-renamesourcefileattribute SourceFile
