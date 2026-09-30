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
        private const val CACHE_PREFS = "widget_cache"

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
            val cache = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)

            val ubicacion = obtenerUbicacion(context)
            if (ubicacion == null) {
                // Sin ubicación ninguna vez: no hay nada que mostrar todavía.
                // Si ya hubo una actualización buena antes, se queda con ella
                // en vez de vaciar el widget.
                aplicarCacheOPlaceholder(views, cache)
                appWidgetManager.updateAppWidget(widgetId, views)
                return
            }
            val (lat, lon) = ubicacion

            // Open-Meteo: misma fuente que usa ya la app para la previsión
            // (temperatura, icono, viento). Si esta actualización falla por
            // lo que sea (sin red en ese instante, Doze, DNS...), NO se
            // sobrescribe con "--°C" -- se conserva el ultimo dato bueno
            // guardado en cache, para que el widget no se quede "en blanco"
            // cada vez que una actualizacion puntual en segundo plano falla.
            try {
                val json = JSONObject(
                    URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m&timezone=auto").readText()
                )
                val current = json.getJSONObject("current")
                val temp = current.getDouble("temperature_2m")
                val tempTexto = "${temp.toInt()}°C"

                val ciudad = try {
                    val cj = JSONObject(URL("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon&localityLanguage=es").readText())
                    cj.optString("locality")
                        .ifBlank { cj.optString("city") }
                        .ifBlank { cj.optString("countryName") }
                        .ifBlank { cache.getString("ciudad", "") ?: "" }
                } catch (e: Exception) { cache.getString("ciudad", "") ?: "" }

                cache.edit()
                    .putString("temp", tempTexto)
                    .putString("ciudad", ciudad)
                    .apply()

                views.setTextViewText(R.id.widget_temp, tempTexto)
                views.setTextViewText(R.id.widget_ciudad, ciudad)
            } catch (e: Exception) {
                aplicarCacheOPlaceholder(views, cache)
            }
            appWidgetManager.updateAppWidget(widgetId, views)
        }

        // Rellena el widget con el ultimo dato bueno guardado; si nunca hubo
        // ninguno (primera vez, sin red desde la instalacion), usa el
        // placeholder de siempre.
        private fun aplicarCacheOPlaceholder(views: RemoteViews, cache: android.content.SharedPreferences) {
            val tempCache = cache.getString("temp", null)
            if (tempCache != null) {
                views.setTextViewText(R.id.widget_temp, tempCache)
                views.setTextViewText(R.id.widget_ciudad, cache.getString("ciudad", ""))
            } else {
                views.setTextViewText(R.id.widget_temp, "--°C")
                views.setTextViewText(R.id.widget_ciudad, "Abre la app primero")
            }
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

        // Traduce el codigo de condicion WMO de Open-Meteo a un drawable del
        // widget -- ya no hace falta ninguna tabla de traduccion ni cruce con
        // una segunda fuente (Open-Meteo ya es la unica fuente, y su codigo
        // ya viene en la escala WMO estandar que usa toda la app).
        // No privada: la reutiliza también TemperaturaWidgetHoras.
        fun iconoParaCodigo(codigo: Int, esDeDia: Boolean): Int {
            return when {
                codigo == 0 || codigo == 1 -> if (esDeDia) R.drawable.ic_w_sun else R.drawable.ic_w_moon
                codigo == 2 -> if (esDeDia) R.drawable.ic_w_partly_cloudy else R.drawable.ic_w_partly_cloudy_night
                codigo == 3 -> R.drawable.ic_w_cloudy
                codigo == 45 || codigo == 48 -> R.drawable.ic_w_fog
                codigo in 51..67 || codigo in 80..82 -> R.drawable.ic_w_rain
                codigo in 71..77 || codigo in 85..86 -> R.drawable.ic_w_snow
                codigo >= 95 -> R.drawable.ic_w_storm
                else -> R.drawable.ic_w_cloudy
            }
        }
    }
}
