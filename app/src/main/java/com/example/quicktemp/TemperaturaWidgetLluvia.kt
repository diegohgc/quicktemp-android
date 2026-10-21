package com.diegohg.quicktemp

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.widget.RemoteViews
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread

// Widget "¿Va a llover?": un titular claro (sin lluvia / lloviendo ahora /
// lluvia a las HH:00) y las barras de mm de las proximas 12 horas. Usa la
// misma fuente y modelo que la app (Open-Meteo, ICON). Como en la app, una
// hora cuenta como lluvia a partir de 0,1 mm.
class TemperaturaWidgetLluvia : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetProgramador.asegurar(context)
        for (id in appWidgetIds) actualizar(context, appWidgetManager, id)
    }

    override fun onEnabled(context: Context) {
        WidgetProgramador.asegurar(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_ACTUALIZAR) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TemperaturaWidgetLluvia::class.java))
            for (id in ids) actualizar(context, manager, id)
        }
    }

    companion object {
        const val ACTION_ACTUALIZAR = "com.diegohg.quicktemp.WIDGET_LLUVIA_ACTUALIZAR"
        private const val CACHE_PREFS = "widget_lluvia_cache"
        private const val ARCHIVO_GRAFICA = "widget_lluvia_grafica.png"
        private const val HORAS = 12
        private const val UMBRAL_MM = 0.1

        fun crearViewsBase(context: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_lluvia_layout)
            WidgetComun.clicAbrirApp(context, views, R.id.wLlu_root, widgetId, 310000)
            WidgetComun.clicRefrescar(context, views, R.id.wLlu_botonActualizar, widgetId, TemperaturaWidgetLluvia::class.java, ACTION_ACTUALIZAR)
            return views
        }

        fun actualizar(context: Context, manager: AppWidgetManager, widgetId: Int) {
            try {
                val views = crearViewsBase(context, widgetId)
                manager.updateAppWidget(widgetId, views)
                thread {
                    try {
                        actualizarSync(context, manager, widgetId, views)
                    } catch (e: Exception) {
                        manager.updateAppWidget(widgetId, views)
                    }
                }
            } catch (e: Exception) {
                manager.updateAppWidget(widgetId, RemoteViews(context.packageName, R.layout.widget_lluvia_layout))
            }
        }

        fun actualizarSync(context: Context, manager: AppWidgetManager, widgetId: Int, views: RemoteViews) {
            val cache = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            val ubicacion = TemperaturaWidget.obtenerUbicacion(context)
            if (ubicacion == null) {
                aplicarCache(context, views, cache)
                manager.updateAppWidget(widgetId, views)
                return
            }
            val (lat, lon) = ubicacion
            try {
                val json = JSONObject(
                    WidgetComun.descargar(
                        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                            "&current=precipitation&hourly=precipitation&forecast_days=2&timezone=auto&models=icon_seamless"
                    )
                )
                val offset = json.optInt("utc_offset_seconds", 0)
                val zona = WidgetComun.zonaDe(offset)
                val hourly = json.getJSONObject("hourly")
                val ts = hourly.getJSONArray("time")
                val ps = hourly.getJSONArray("precipitation")
                val ahora = System.currentTimeMillis()

                // Hora actual (la que contiene "ahora") y las siguientes
                var inicio = 0
                for (i in 0 until ts.length()) {
                    if (WidgetComun.parsearIso(ts.getString(i), offset) <= ahora) inicio = i else break
                }
                val mm = ArrayList<Double>()
                val tiemposHora = ArrayList<Long>()
                val etiquetas = ArrayList<String>()
                var i = inicio
                while (i < ts.length() && mm.size < HORAS) {
                    mm.add(if (ps.isNull(i)) 0.0 else ps.getDouble(i))
                    val t = WidgetComun.parsearIso(ts.getString(i), offset)
                    tiemposHora.add(t)
                    etiquetas.add(ts.getString(i).substringAfter("T").substringBefore(":") + "h")
                    i++
                }
                if (mm.isEmpty()) throw IllegalStateException("sin datos")

                val precipActual = json.optJSONObject("current")?.optDouble("precipitation", 0.0) ?: 0.0
                val llueveAhora = precipActual >= UMBRAL_MM || mm[0] >= UMBRAL_MM
                val total = mm.sum()
                val fmt = { v: Double -> String.format(Locale.getDefault(), "%.1f", v) }

                val titular: String
                val detalle: String
                if (llueveAhora) {
                    titular = context.getString(R.string.w_lloviendo)
                    var finIdx = -1
                    for (k in 1 until mm.size) if (mm[k] < UMBRAL_MM) { finIdx = k; break }
                    detalle = if (finIdx < 0) context.getString(R.string.w_durante, HORAS)
                    else context.getString(R.string.w_hasta, WidgetComun.hhmm(tiemposHora[finIdx], zona))
                } else {
                    var iniIdx = -1
                    for (k in mm.indices) if (mm[k] >= UMBRAL_MM) { iniIdx = k; break }
                    if (iniIdx < 0) {
                        titular = context.getString(R.string.w_sin_lluvia)
                        detalle = context.getString(R.string.w_proximas, HORAS)
                    } else {
                        titular = context.getString(R.string.w_lluvia_a, WidgetComun.hhmm(tiemposHora[iniIdx], zona))
                        detalle = context.getString(R.string.w_empieza_en, WidgetComun.duracion(tiemposHora[iniIdx] - ahora)) +
                            " · " + fmt(mm[iniIdx]) + " mm/h"
                    }
                }
                val totalTxt = context.getString(R.string.w_total_horas, HORAS, fmt(total))
                val ciudad = WidgetComun.ciudad(lat, lon, cache)
                val hora = WidgetComun.horaActualizacion()
                val grafica = WidgetGraficos.barrasLluvia(780, 190, mm, etiquetas)

                cache.edit()
                    .putString("ciudad", ciudad).putString("titular", titular).putString("detalle", detalle)
                    .putString("total", totalTxt).putString("hora", hora).putBoolean("llueve", llueveAhora || total >= UMBRAL_MM)
                    .apply()
                WidgetComun.guardarBitmap(context, ARCHIVO_GRAFICA, grafica)
                pintar(views, ciudad, titular, detalle, totalTxt, hora, grafica, llueveAhora || total >= UMBRAL_MM)
            } catch (e: Exception) {
                aplicarCache(context, views, cache)
            }
            manager.updateAppWidget(widgetId, views)
        }

        private fun pintar(
            views: RemoteViews, ciudad: String, titular: String, detalle: String, total: String,
            hora: String, grafica: Bitmap?, hayLluvia: Boolean
        ) {
            if (ciudad.isNotBlank()) views.setTextViewText(R.id.wLlu_ciudad, ciudad)
            views.setTextViewText(R.id.wLlu_titular, titular)
            views.setTextViewText(R.id.wLlu_detalle, detalle)
            views.setTextViewText(R.id.wLlu_total, total)
            views.setTextViewText(R.id.wLlu_hora, hora)
            // Titular en azul claro cuando hay lluvia, en blanco si el dia esta seco
            views.setTextColor(R.id.wLlu_titular, if (hayLluvia) 0xFF4FC3F7.toInt() else 0xFFFFFFFF.toInt())
            if (grafica != null) views.setImageViewBitmap(R.id.wLlu_grafica, grafica)
        }

        private fun aplicarCache(context: Context, views: RemoteViews, cache: SharedPreferences) {
            val titular = cache.getString("titular", null) ?: return
            pintar(
                views, cache.getString("ciudad", "") ?: "", titular, cache.getString("detalle", "") ?: "",
                cache.getString("total", "") ?: "", cache.getString("hora", "--:--") ?: "--:--",
                WidgetComun.cargarBitmap(context, ARCHIVO_GRAFICA), cache.getBoolean("llueve", false)
            )
        }
    }
}
