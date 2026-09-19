package com.diegohg.quicktemp

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
        for (id in appWidgetIds) {
            actualizarWidgetHoras(context, appWidgetManager, id)
        }
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
            val ubicacion = TemperaturaWidget.obtenerUbicacion(context)
            if (ubicacion == null) {
                views.setTextViewText(R.id.widgetHoras_temp, "--°")
                appWidgetManager.updateAppWidget(widgetId, views)
                return
            }
            val (lat, lon) = ubicacion

            try {
                val json = JSONObject(
                    URL("https://api.weatherapi.com/v1/forecast.json?key=1273625cfc2746fcaa760211260309&q=$lat,$lon&days=2&aqi=no&alerts=no").readText()
                )
                val current = json.getJSONObject("current")
                val tempActual = current.getDouble("temp_c")
                val codigoActual = current.getJSONObject("condition").getInt("code")
                val esDeDiaActual = current.getInt("is_day") == 1
                val vientoKmh = current.getDouble("wind_kph").toInt()

                var omCloud: Int? = null
                var omVis: Int? = null
                try {
                    val omJson = JSONObject(URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=cloud_cover,visibility").readText())
                    val omCurrent = omJson.getJSONObject("current")
                    omCloud = omCurrent.optInt("cloud_cover", -1).takeIf { it >= 0 }
                    omVis = omCurrent.optDouble("visibility", -1.0).takeIf { it >= 0 }?.toInt()
                } catch (e: Exception) {}

                views.setTextViewText(R.id.widgetHoras_temp, "${tempActual.toInt()}°")
                views.setImageViewResource(
                    R.id.widgetHoras_icono,
                    TemperaturaWidget.iconoParaCodigo(codigoActual, esDeDiaActual, omCloud, omVis)
                )

                // Máx/mín del día de hoy (primer forecastday) y hora de esta actualización.
                try {
                    val dia0 = json.getJSONObject("forecast").getJSONArray("forecastday").getJSONObject(0).getJSONObject("day")
                    val maxT = dia0.getDouble("maxtemp_c").toInt()
                    val minT = dia0.getDouble("mintemp_c").toInt()
                    views.setTextViewText(R.id.widgetHoras_maxmin, "Máx ${maxT}° Mín ${minT}°")
                } catch (e: Exception) {}

                val horaFormateada = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                views.setTextViewText(R.id.widgetHoras_horaActualizacion, horaFormateada)

                // Todas las horas de hoy + mañana, quedarnos con las que
                // aún no han pasado y coger las 6 siguientes.
                val ahoraEpoch = System.currentTimeMillis() / 1000
                val dias = json.getJSONObject("forecast").getJSONArray("forecastday")
                val horasFuturas = mutableListOf<JSONObject>()
                for (i in 0 until dias.length()) {
                    val horas = dias.getJSONObject(i).getJSONArray("hour")
                    for (h in 0 until horas.length()) {
                        val hora = horas.getJSONObject(h)
                        if (hora.getLong("time_epoch") >= ahoraEpoch) horasFuturas.add(hora)
                    }
                }

                // Probabilidad de lluvia: mismo criterio que la app (hora
                // actual del pronóstico horario, WeatherAPI no la da en "current").
                val probLluvia = horasFuturas.getOrNull(0)?.optInt("chance_of_rain", -1) ?: -1
                views.setTextViewText(
                    R.id.widgetHoras_lluviaViento,
                    "💧 ${if (probLluvia >= 0) "$probLluvia%" else "--%"} 💨 ${vientoKmh} km/h"
                )

                for (i in 0 until 6) {
                    if (i >= horasFuturas.size) {
                        views.setTextViewText(IDS_HORA[i], "")
                        views.setTextViewText(IDS_TEMP[i], "")
                        continue
                    }
                    val hora = horasFuturas[i]
                    val horaTexto = hora.getString("time").substringAfter(" ").substringBefore(":")
                    val tempHora = hora.getDouble("temp_c")
                    val codigoHora = hora.getJSONObject("condition").getInt("code")
                    val esDeDiaHora = hora.getInt("is_day") == 1

                    views.setTextViewText(IDS_HORA[i], "${horaTexto}h")
                    views.setTextViewText(IDS_TEMP[i], "${tempHora.toInt()}°")
                    views.setImageViewResource(
                        IDS_ICONO[i],
                        TemperaturaWidget.iconoParaCodigo(codigoHora, esDeDiaHora, null, null)
                    )
                }
            } catch (e: Exception) {}

            val ciudad = try {
                val json = JSONObject(URL("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon&localityLanguage=es").readText())
                json.optString("locality")
                    .ifBlank { json.optString("city") }
                    .ifBlank { json.optString("countryName") }
                    .ifBlank { "" }
            } catch (e: Exception) { "" }
            if (ciudad.isNotBlank()) views.setTextViewText(R.id.widgetHoras_ciudad, ciudad)

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
