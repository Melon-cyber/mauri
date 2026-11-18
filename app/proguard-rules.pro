# Mauri release rules.
# Open-source app: keep names so a crash report from a user points to real classes and lines.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# App models are read and written with Gson by reflection (library, settings, caches, backups).
-keep class app.melo.music.** { *; }

# NewPipe Extractor and its JavaScript engine use reflection and generated code at run time.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class com.grack.nanojson.** { *; }
-keep class org.jsoup.** { *; }
-keep class com.google.protobuf.** { *; }

# Optional classes referenced by these libraries but not present on Android.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.lang.model.**
-dontwarn org.slf4j.**
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.annotations.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
