# ══════════════════════════════════════════════════════════════
#  小霸王 TV —— Release 混淆规则
# ══════════════════════════════════════════════════════════════

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
