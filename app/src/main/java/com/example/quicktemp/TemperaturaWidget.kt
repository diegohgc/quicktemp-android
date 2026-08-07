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

        fun actualizarWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // Clic actualiza los datos del widget
            val refreshIntent = Intent(context, TemperaturaWidget::class.java).apply {
                action = ACTION_ACTUALIZAR
            }
            val refreshPending = PendingIntent.getBroadcast(
                context, widgetId, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, refreshPending)
            views.setOnClickPendingIntent(R.id.widget_temp, refreshPending)
            views.setOnClickPendingIntent(R.id.widget_altitud, refreshPending)
            views.setOnClickPendingIntent(R.id.widget_ciudad, refreshPending)

            views.setTextViewText(R.id.widget_ciudad, "Actualizando...")
            appWidgetManager.updateAppWidget(widgetId, views)

            thread {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val tienePermiso = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (!tienePermiso) {
                    views.setTextViewText(R.id.widget_temp, "--°C")
                    views.setTextViewText(R.id.widget_altitud, "-- m")
                    views.setTextViewText(R.id.widget_ciudad, "Abre la app primero")
                    appWidgetManager.updateAppWidget(widgetId, views)
                    return@thread
                }

                val location = try {
                    locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                } catch (e: Exception) { null }

                // Fallback: usar última ubicación guardada por la app
                val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
                val lat: Double
                val lon: Double
                if (location != null) {
                    lat = location.latitude
                    lon = location.longitude
                    prefs.edit()
                        .putFloat("lat", lat.toFloat())
                        .putFloat("lon", lon.toFloat())
                        .apply()
                } else {
                    val savedLat = prefs.getFloat("lat", Float.MIN_VALUE)
                    val savedLon = prefs.getFloat("lon", Float.MIN_VALUE)
                    if (savedLat == Float.MIN_VALUE) {
                        views.setTextViewText(R.id.widget_temp, "--°C")
                        views.setTextViewText(R.id.widget_altitud, "-- m")
                        views.setTextViewText(R.id.widget_ciudad, "Abre la app primero")
                        appWidgetManager.updateAppWidget(widgetId, views)
                        return@thread
                    }
                    lat = savedLat.toDouble()
                    lon = savedLon.toDouble()
                }

                val tempTexto = try {
                    val json = JSONObject(URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m&timezone=auto").readText())
                    val temp = json.getJSONObject("current").getDouble("temperature_2m")
                    "${temp.toInt()}°C"
                } catch (e: Exception) { "--°C" }

                val altTexto = try {
                    val json = JSONObject(URL("https://api.open-meteo.com/v1/elevation?latitude=$lat&longitude=$lon").readText())
                    val alt = json.getJSONArray("elevation").getDouble(0).toInt()
                    "$alt m"
                } catch (e: Exception) { "-- m" }

                val ciudad = try {
                    val json = JSONObject(URL("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon&localityLanguage=es").readText())
                    json.optString("locality")
                        .ifBlank { json.optString("city") }
                        .ifBlank { json.optString("countryName") }
                        .ifBlank { "" }
                } catch (e: Exception) { "" }

                views.setTextViewText(R.id.widget_temp, tempTexto)
                views.setTextViewText(R.id.widget_altitud, altTexto)
                views.setTextViewText(R.id.widget_ciudad, ciudad)
                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }
    }
}
