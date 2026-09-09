# QuickTemp — reglas ProGuard/R8 para release

# WebView: si en el futuro se añade un puente JS (@JavascriptInterface),
# hay que mantenerlo visible para que R8 no lo ofusque/elimine.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# AdMob / Google Play Services Ads
-keep class com.google.android.gms.ads.** { *; }
-keep class com.google.ads.** { *; }

# Widget de escritorio (AppWidgetProvider): Android lo instancia por reflexión
# a partir del manifest, hay que mantener el nombre de la clase intacto.
-keep class com.diegohg.quicktemp.TemperaturaWidget { *; }
-keep class com.diegohg.quicktemp.MainActivity { *; }

# Atributos estándar recomendados por Android para depurar stack traces
# incluso con el código ofuscado.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
