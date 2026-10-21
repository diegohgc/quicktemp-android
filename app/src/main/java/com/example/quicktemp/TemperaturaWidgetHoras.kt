package com.diegohg.quicktemp

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.RemoteViews
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

// Widget horizontal 4x1/4x2: temperatura actual a la izquierda y las
// próximas 6 horas a la derecha (hora, icono, grados). Todo el cuerpo es
// un único botón que abre la app -- no tiene botón de refresco propio,
// se actualiza solo (WorkManager + el ciclo normal del sistema), porque
// aquí el gesto natural es "toco para ver el detalle", no "toco para
// refrescar".
class TemperaturaWidgetHoras : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetProgramador.asegurar(context)
        for (id in appWidgetIds) {
            actualizarWidgetHoras(context, appWidgetManager, id)
        }
    }

    override fun onEnabled(context: Context) {
        WidgetProgramador.asegurar(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_ACTUALIZAR) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TemperaturaWidgetHoras::class.java))
            for (id in ids) actualizarWidgetHoras(context, manager, id)
        }
    }

    companion object {
        const val ACTION_ACTUALIZAR = "com.diegohg.quicktemp.WIDGET_HORAS_ACTUALIZAR"
        private const val CACHE_PREFS = "widget_horas_cache"

        private val IDS_HORA = intArrayOf(
            R.id.widgetHoras_hora0, R.id.widgetHoras_hora1, R.id.widgetHoras_hora2,
            R.id.widgetHoras_hora3, R.id.widgetHoras_hora4, R.id.widgetHoras_hora5
        )
        private val IDS_ICONO = intArrayOf(
            R.id.widgetHoras_icono0, R.id.widgetHoras_icono1, R.id.widgetHoras_icono2,
            R.id.widgetHoras_icono3, R.id.widgetHoras_icono4, R.id.widgetHoras_icono5
        )
        private val IDS_TEMP = intArrayOf(
            R.id.widgetHoras_temp0, R.id.widgetHoras_temp1, R.id.widgetHoras_temp2,
            R.id.widgetHoras_temp3, R.id.widgetHoras_temp4, R.id.widgetHoras_temp5
        )

        fun crearViewsBase(context: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_horas_layout)
            // Todo el widget es un único botón gigante: al ver "lluvia" a
            // las 18:00, el usuario toca por instinto para saber "cuánto"
            // va a llover, y eso ya abre la app directamente.
            val abrirAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (abrirAppIntent != null) {
                val abrirAppPending = PendingIntent.getActivity(
                    context, widgetId + 100000, abrirAppIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widgetHoras_root, abrirAppPending)
            }

            // Botón "Actualizar" propio en la esquina -- tiene su clic
            // independiente del resto del widget (que abre la app), y
            // gana prioridad sobre el clic del contenedor al estar en
            // una vista hija con su propio setOnClickPendingIntent.
            val refreshIntent = Intent(context, TemperaturaWidgetHoras::class.java).apply {
                action = ACTION_ACTUALIZAR
            }
            val refreshPending = PendingIntent.getBroadcast(
                context, widgetId, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetHoras_botonActualizar, refreshPending)

            return views
        }

        // IMPORTANTE: crearViewsBase()/updateAppWidget() del principio nunca
        // debe fallar sin llegar a pintar nada -- si lanza una excepción
        // antes de la primera actualización real, Android deja el widget
        // atascado en el placeholder de "cargando" (fondo blanco con el
        // circulito gris) para siempre. Por eso todo va envuelto en
        // try/catch con una vista de emergencia como último recurso.
        fun actualizarWidgetHoras(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
            try {
                val views = crearViewsBase(context, widgetId)
                appWidgetManager.updateAppWidget(widgetId, views)
                thread {
                    try {
                        actualizarWidgetHorasSync(context, appWidgetManager, widgetId, views)
                    } catch (e: Exception) {
                        appWidgetManager.updateAppWidget(widgetId, views)
                    }
                }
            } catch (e: Exception) {
                val emergencia = RemoteViews(context.packageName, R.layout.widget_horas_layout)
                appWidgetManager.updateAppWidget(widgetId, emergencia)
            }
        }

        fun actualizarWidgetHorasSync(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int,
            views: RemoteViews
        ) {
            val cache = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            val ubicacion = TemperaturaWidget.obtenerUbicacion(context)
            if (ubicacion == null) {
                aplicarCacheOPlaceholder(views, cache)
                appWidgetManager.updateAppWidget(widgetId, views)
                return
            }
            val (lat, lon) = ubicacion

            // Open-Meteo: una sola llamada trae actual + horas + max/min del
            // dia, misma fuente que usa ya la app. Si falla (sin red en ese
            // instante, Doze...) se conserva el ultimo dato bueno en cache en
            // vez de vaciar el widget a "--°".
            try {
                val json = JSONObject(
                    URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day,wind_speed_10m,relative_humidity_2m&hourly=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&forecast_days=2&timezone=auto&models=icon_seamless").readText()
                )
                val current = json.getJSONObject("current")
                val tempActual = current.getDouble("temperature_2m")
                val codigoActual = current.getInt("weather_code")
                val esDeDiaActual = current.getInt("is_day") == 1
                val vientoKmh = current.getDouble("wind_speed_10m").toInt()
                val humedad = current.getInt("relative_humidity_2m")

                val tempTexto = "${tempActual.toInt()}°"
                // Ya no se usa icono para el estado actual (solo se mantiene
                // el icono por hora en la franja de previsión), a peticion
                // del usuario -- se queda solo temperatura, ciudad, previsión
                // por horas, humedad y viento.

                val maxMinTexto = try {
                    val daily = json.getJSONObject("daily")
                    val maxT = daily.getJSONArray("temperature_2m_max").getDouble(0).toInt()
                    val minT = daily.getJSONArray("temperature_2m_min").getDouble(0).toInt()
                    "Máx ${maxT}° Mín ${minT}°"
                } catch (e: Exception) { cache.getString("maxmin", "") ?: "" }

                val horaFormateada = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

                // Todas las horas de hoy + mañana, quedarnos con las que
                // aún no han pasado y coger las 6 siguientes.
                val hourly = json.getJSONObject("hourly")
                val horasTime = hourly.getJSONArray("time")
                val horasTemp = hourly.getJSONArray("temperature_2m")
                val horasCodigo = hourly.getJSONArray("weather_code")
                val horasEsDia = hourly.getJSONArray("is_day")
                val ahoraMillis = System.currentTimeMillis()
                val sdfHora = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())

                data class HoraFutura(val texto: String, val temp: Int, val icono: Int)
                val futuras = mutableListOf<HoraFutura>()
                for (i in 0 until horasTime.length()) {
                    val tStr = horasTime.getString(i)
                    val tMillis = try { sdfHora.parse(tStr)?.time ?: 0L } catch (e: Exception) { 0L }
                    if (tMillis < ahoraMillis) continue
                    val horaTexto = tStr.substringAfter("T").substringBefore(":")
                    futuras.add(
                        HoraFutura(
                            "${horaTexto}h",
                            horasTemp.getDouble(i).toInt(),
                            TemperaturaWidget.iconoParaCodigo(horasCodigo.getInt(i), horasEsDia.getInt(i) == 1)
                        )
                    )
                    if (futuras.size >= 6) break
                }

                val lluviaVientoTexto = "💧 ${humedad}% 💨 ${vientoKmh} km/h"

                val ciudad = try {
                    val cj = JSONObject(URL("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon&localityLanguage=es").readText())
                    cj.optString("locality")
                        .ifBlank { cj.optString("city") }
                        .ifBlank { cj.optString("countryName") }
                        .ifBlank { cache.getString("ciudad", "") ?: "" }
                } catch (e: Exception) { cache.getString("ciudad", "") ?: "" }

                // Guardar en cache antes de pintar, para que el proximo fallo
                // pueda recuperar exactamente este mismo estado.
                val cacheEdit = cache.edit()
                cacheEdit.putString("temp", tempTexto)
                cacheEdit.putString("maxmin", maxMinTexto)
                cacheEdit.putString("lluviaViento", lluviaVientoTexto)
                cacheEdit.putString("ciudad", ciudad)
                cacheEdit.putInt("horasCount", futuras.size)
                futuras.forEachIndexed { i, h ->
                    cacheEdit.putString("hora$i", h.texto)
                    cacheEdit.putString("temp$i", "${h.temp}°")
                    cacheEdit.putInt("icono$i", h.icono)
                }
                cacheEdit.apply()

                views.setTextViewText(R.id.widgetHoras_temp, tempTexto)
                views.setTextViewText(R.id.widgetHoras_maxmin, maxMinTexto)
                views.setTextViewText(R.id.widgetHoras_horaActualizacion, horaFormateada)
                views.setTextViewText(R.id.widgetHoras_lluviaViento, lluviaVientoTexto)
                if (ciudad.isNotBlank()) views.setTextViewText(R.id.widgetHoras_ciudad, ciudad)

                for (i in 0 until 6) {
                    if (i >= futuras.size) {
                        views.setTextViewText(IDS_HORA[i], "")
                        views.setTextViewText(IDS_TEMP[i], "")
                        continue
                    }
                    views.setTextViewText(IDS_HORA[i], futuras[i].texto)
                    views.setTextViewText(IDS_TEMP[i], "${futuras[i].temp}°")
                    views.setImageViewResource(IDS_ICONO[i], futuras[i].icono)
                }
            } catch (e: Exception) {
                aplicarCacheOPlaceholder(views, cache)
            }

            appWidgetManager.updateAppWidget(widgetId, views)
        }

        // Rellena el widget con el ultimo dato bueno guardado; si nunca hubo
        // ninguno (primera vez, sin red desde la instalacion), deja el
        // placeholder por defecto del layout ("--°" ya viene en el XML).
        private fun aplicarCacheOPlaceholder(views: RemoteViews, cache: SharedPreferences) {
            val tempCache = cache.getString("temp", null) ?: return
            views.setTextViewText(R.id.widgetHoras_temp, tempCache)
            views.setTextViewText(R.id.widgetHoras_maxmin, cache.getString("maxmin", ""))
            views.setTextViewText(R.id.widgetHoras_lluviaViento, cache.getString("lluviaViento", ""))
            val ciudadCache = cache.getString("ciudad", "")
            if (!ciudadCache.isNullOrBlank()) views.setTextViewText(R.id.widgetHoras_ciudad, ciudadCache)

            val count = cache.getInt("horasCount", 0)
            for (i in 0 until 6) {
                if (i >= count) {
                    views.setTextViewText(IDS_HORA[i], "")
                    views.setTextViewText(IDS_TEMP[i], "")
                    continue
                }
                views.setTextViewText(IDS_HORA[i], cache.getString("hora$i", ""))
                views.setTextViewText(IDS_TEMP[i], cache.getString("temp$i", ""))
                views.setImageViewResource(IDS_ICONO[i], cache.getInt("icono$i", R.drawable.ic_w_cloudy))
            }
        }
    }
}
