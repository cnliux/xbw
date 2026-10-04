# ══════════════════════════════════════════════════════════════
#  小霸王 TV —— Release 混淆规则
# ══════════════════════════════════════════════════════════════

# JNI：xbw_core.c 按 Java_包_类_方法 静态符号查找，native 方法名不能被混淆
-keepclasseswithmembernames class com.xbw.tv.core.RetroCore { native <methods>; }

# Room 实体/DAO
-keep @androidx.room.Entity class *
-keepclassmembers class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Kotlin 协程
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Jsoup
-keep public class org.jsoup.** { public *; }
-dontwarn org.jspecify.annotations.**

-dontwarn com.bumptech.glide.**

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# commons-compress：ExtraFieldUtils 静态初始化用 Class.forName 反射创建
# UnrecognizedExtraField / *ExtraField 实现，R8 改名/裁剪后 release 一点
# GBA/MD（java 解 zip）就 ExceptionInInitializerError 闪退（2026-10-04 实测）
-keep class org.apache.commons.compress.** { *; }

-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
