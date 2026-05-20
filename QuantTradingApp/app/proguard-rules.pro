# ProGuard rules for QuantTradingApp
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep local model and DTO field names for Gson/Room-facing payloads.
-keep class io.github.leonarddon.quanttrading.model.** { *; }
-keep class io.github.leonarddon.quanttrading.data.*Entity { *; }
-keep class io.github.leonarddon.quanttrading.network.*Backend* { *; }

# Retrofit annotations and generic signatures.
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations

# Gson
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
