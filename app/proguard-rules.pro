# QuickTemp — reglas ProGuard/R8 para release

# WebView: si en el futuro se añade un puente JS (@JavascriptInterface),
# hay que mantenerlo visible para que R8 no lo ofusque/elimine.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# AdMob / Google Play Services Ads
-keep class com.google.android.gms.ads.** { *; }
-keep class com.google.ads.** { *; }

# WorkManager + Room: dependencia transitiva interna de Play Services Ads
# (tareas en segundo plano). La ofuscacion rompia la generacion de su base
# de datos interna en tiempo de ejecucion:
#   "Failed to create an instance of androidx.work.impl.WorkDatabase"
-keep class androidx.work.** { *; }
-keep class androidx.room.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.paging.**

# Widget de escritorio (AppWidgetProvider): Android lo instancia por reflexión
# a partir del manifest, hay que mantener el nombre de la clase intacto.
-keep class com.diegohg.quicktemp.TemperaturaWidget { *; }
-keep class com.diegohg.quicktemp.MainActivity { *; }

# Atributos estándar recomendados por Android para depurar stack traces
# incluso con el código ofuscado.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
