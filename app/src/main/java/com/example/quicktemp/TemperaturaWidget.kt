package com.diegohg.quicktemp

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.text.SpannableString
import android.text.style.UnderlineSpan
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.diegohg.quicktemp.R
import org.json.JSONObject
import java.net.URL
import kotlin.concurrent.thread

class TemperaturaWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            actualizarWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_ACTUALIZAR || intent.action == Intent.ACTION_USER_PRESENT) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TemperaturaWidget::class.java))
            for (id in ids) actualizarWidget(context, manager, id)
        }
    }

    companion object {
        const val ACTION_ACTUALIZAR = "com.diegohg.quicktemp.WIDGET_ACTUALIZAR"

        // Construye el RemoteViews base con los PendingIntent de clic ya
        // enganchados -- hace falta rehacer esto en cada actualización
        // (también las del Worker en segundo plano) porque RemoteViews
        // sustituye el estado entero del widget, clics incluidos.
        // Tocar el widget (temperatura/icono/ciudad/fondo) abre la app;
        // solo el botón "Actualizar" refresca los datos in situ.
        fun crearViewsBase(context: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            val abrirAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val abrirAppPending = PendingIntent.getActivity(
                context, widgetId, abrirAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, abrirAppPending)
            views.setOnClickPendingIntent(R.id.widget_temp, abrirAppPending)
            views.setOnClickPendingIntent(R.id.widget_icono, abrirAppPending)
            views.setOnClickPendingIntent(R.id.widget_ciudad, abrirAppPending)

            val refreshIntent = Intent(context, TemperaturaWidget::class.java).apply {
                action = ACTION_ACTUALIZAR
            }
            val refreshPending = PendingIntent.getBroadcast(
                context, widgetId, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_actualizar, refreshPending)

            val textoActualizar = SpannableString("Actualizar")
            textoActualizar.setSpan(UnderlineSpan(), 0, textoActualizar.length, 0)
            views.setTextViewText(R.id.widget_actualizar, textoActualizar)

            return views
        }

        fun actualizarWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
            val views = crearViewsBase(context, widgetId)
            views.setTextViewText(R.id.widget_ciudad, "Actualizando...")
            appWidgetManager.updateAppWidget(widgetId, views)

            thread { actualizarWidgetSync(context, appWidgetManager, widgetId, views) }
        }

        // Cuerpo síncrono de la actualización (peticiones de red incluidas).
        // Se ejecuta en el hilo propio que crea actualizarWidget() cuando la
        // llama el sistema/el propio widget, y directamente en doWork() de
        // WidgetUpdateWorker (que ya corre en un hilo de fondo de WorkManager,
        // no hace falta crear otro).
        fun actualizarWidgetSync(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int,
            views: RemoteViews
        ) {
            val ubicacion = obtenerUbicacion(context)
            if (ubicacion == null) {
                views.setTextViewText(R.id.widget_temp, "--°C")
                views.setImageViewResource(R.id.widget_icono, R.drawable.ic_w_cloudy)
                views.setTextViewText(R.id.widget_ciudad, "Abre la app primero")
                appWidgetManager.updateAppWidget(widgetId, views)
                return
            }
            val (lat, lon) = ubicacion

            // Misma fuente que usa la app (WeatherAPI) para que la temperatura
            // del widget coincida con la que ve el usuario al abrir QuickTemp
            // -- antes usaba Open-Meteo con el modelo por defecto, que no es
            // el mismo dato ni la misma fuente que la app, y podían discrepar
            // varios grados entre uno y otro.
            var tempTexto = "--°C"
            var iconoRes = R.drawable.ic_w_cloudy
            try {
                val json = JSONObject(URL("https://api.weatherapi.com/v1/current.json?key=1273625cfc2746fcaa760211260309&q=$lat,$lon").readText())
                val current = json.getJSONObject("current")
                val temp = current.getDouble("temp_c")
                tempTexto = "${temp.toInt()}°C"
                val codigo = current.getJSONObject("condition").getInt("code")
                val esDeDia = current.getInt("is_day") == 1

                // Corrección con nubosidad/visibilidad real (Open-Meteo), igual
                // que hace la web -- WeatherAPI a veces dice "nublado"/"niebla"
                // con el cielo despejado de verdad.
                var omCloud: Int? = null
                var omVis: Int? = null
                try {
                    val omJson = JSONObject(URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=cloud_cover,visibility").readText())
                    val omCurrent = omJson.getJSONObject("current")
                    omCloud = omCurrent.optInt("cloud_cover", -1).takeIf { it >= 0 }
                    omVis = omCurrent.optDouble("visibility", -1.0).takeIf { it >= 0 }?.toInt()
                } catch (e: Exception) {}

                iconoRes = iconoParaCodigo(codigo, esDeDia, omCloud, omVis)
            } catch (e: Exception) {}

            val ciudad = try {
                val json = JSONObject(URL("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon&localityLanguage=es").readText())
                json.optString("locality")
                    .ifBlank { json.optString("city") }
                    .ifBlank { json.optString("countryName") }
                    .ifBlank { "" }
            } catch (e: Exception) { "" }

            views.setTextViewText(R.id.widget_temp, tempTexto)
            views.setImageViewResource(R.id.widget_icono, iconoRes)
            views.setTextViewText(R.id.widget_ciudad, ciudad)
            appWidgetManager.updateAppWidget(widgetId, views)
        }

        // Resuelve lat/lon para los widgets: permiso + última ubicación
        // conocida del sistema, con fallback a la última guardada por la
        // app (widget_prefs). Devuelve null si no hay ninguna disponible
        // (caso "abre la app primero"). Compartida por los dos widgets.
        fun obtenerUbicacion(context: Context): Pair<Double, Double>? {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val tienePermiso = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!tienePermiso) return null

            val location = try {
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } catch (e: Exception) { null }

            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            if (location != null) {
                prefs.edit()
                    .putFloat("lat", location.latitude.toFloat())
                    .putFloat("lon", location.longitude.toFloat())
                    .apply()
                return Pair(location.latitude, location.longitude)
            }

            val savedLat = prefs.getFloat("lat", Float.MIN_VALUE)
            val savedLon = prefs.getFloat("lon", Float.MIN_VALUE)
            if (savedLat == Float.MIN_VALUE) return null
            return Pair(savedLat.toDouble(), savedLon.toDouble())
        }

        // Traduce el código de condición de WeatherAPI a un drawable del
        // widget, corrigiendo con la nubosidad/visibilidad real de
        // Open-Meteo (mismo criterio anti-optimismo/anti-pesimismo que usa
        // la web en renderDatos(), simplificado a 4 categorías: despejado,
        // mayormente despejado, parcial, nublado).
        // No privada: la reutiliza también TemperaturaWidgetHoras.
        fun iconoParaCodigo(codigo: Int, esDeDia: Boolean, omCloud: Int?, omVis: Int?): Int {
            // Lluvia / nieve / tormenta: no dependen de la nubosidad, van directos.
            if (codigo in intArrayOf(1063, 1150, 1153, 1168, 1171, 1180, 1183, 1186, 1189,
                    1192, 1195, 1198, 1201, 1204, 1207, 1240, 1243, 1246, 1249, 1252)) {
                return R.drawable.ic_w_rain
            }
            if (codigo in intArrayOf(1066, 1069, 1072, 1114, 1117, 1210, 1213, 1216, 1219,
                    1222, 1225, 1237, 1255, 1258, 1261, 1264)) {
                return R.drawable.ic_w_snow
            }
            if (codigo in intArrayOf(1087, 1273, 1276, 1279, 1282)) {
                return R.drawable.ic_w_storm
            }

            // Niebla/bruma: solo se confirma con visibilidad real baja (<2 km);
            // si no, se trata como una condición de nubosidad normal.
            var categoria = when (codigo) {
                1000 -> 0 // despejado
                1003 -> 1 // mayormente despejado
                1006 -> 2 // parcial
                1009 -> 3 // nublado
                1030, 1135, 1147 -> {
                    if (omVis != null && omVis < 2000) return R.drawable.ic_w_fog
                    2
                }
                else -> 2
            }

            if (categoria <= 2 && omCloud != null) {
                if (omCloud >= 80) categoria = 3
                else if (omCloud >= 50) categoria = maxOf(categoria, 2)
            }
            if (codigo == 1009 && omCloud != null) {
                if (omCloud < 20) categoria = 0
                else if (omCloud < 50) categoria = 1
                else if (omCloud < 80) categoria = minOf(categoria, 2)
            }

            return when (categoria) {
                0, 1 -> if (esDeDia) R.drawable.ic_w_sun else R.drawable.ic_w_moon
                2 -> if (esDeDia) R.drawable.ic_w_partly_cloudy else R.drawable.ic_w_partly_cloudy_night
                else -> R.drawable.ic_w_cloudy
            }
        }
    }
}
