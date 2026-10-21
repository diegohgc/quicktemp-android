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
import kotlin.concurrent.thread

// Widget de sol y luna: tiempo que queda de luz (o que falta para amanecer),
// arco del recorrido del sol con las horas de amanecer y atardecer, y la
// luna con su fase real, % iluminado y dias hasta la proxima luna llena o
// nueva. Amanecer/atardecer vienen de Open-Meteo; la fase lunar se calcula
// en el propio widget (mes sinodico), asi que funciona aunque falle la red.
class TemperaturaWidgetSolLuna : AppWidgetProvider() {

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
            val ids = manager.getAppWidgetIds(ComponentName(context, TemperaturaWidgetSolLuna::class.java))
            for (id in ids) actualizar(context, manager, id)
        }
    }

    companion object {
        const val ACTION_ACTUALIZAR = "com.diegohg.quicktemp.WIDGET_SOLLUNA_ACTUALIZAR"
        private const val CACHE_PREFS = "widget_solluna_cache"
        private const val ARCHIVO_ARCO = "widget_solluna_arco.png"

        private val FASES = intArrayOf(
            R.string.w_fase_0, R.string.w_fase_1, R.string.w_fase_2, R.string.w_fase_3,
            R.string.w_fase_4, R.string.w_fase_5, R.string.w_fase_6, R.string.w_fase_7
        )

        fun crearViewsBase(context: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_sol_luna_layout)
            WidgetComun.clicAbrirApp(context, views, R.id.wSol_root, widgetId, 320000)
            WidgetComun.clicRefrescar(context, views, R.id.wSol_botonActualizar, widgetId, TemperaturaWidgetSolLuna::class.java, ACTION_ACTUALIZAR)
            return views
        }

        fun actualizar(context: Context, manager: AppWidgetManager, widgetId: Int) {
            try {
                val views = crearViewsBase(context, widgetId)
                // La luna no depende de la red: se pinta ya, antes de descargar nada
                pintarLuna(context, views)
                manager.updateAppWidget(widgetId, views)
                thread {
                    try {
                        actualizarSync(context, manager, widgetId, views)
                    } catch (e: Exception) {
                        manager.updateAppWidget(widgetId, views)
                    }
                }
            } catch (e: Exception) {
                manager.updateAppWidget(widgetId, RemoteViews(context.packageName, R.layout.widget_sol_luna_layout))
            }
        }

        private fun pintarLuna(context: Context, views: RemoteViews) {
            val ahora = System.currentTimeMillis()
            val fase = WidgetGraficos.faseLunar(ahora)
            val nombre = context.getString(FASES[((fase * 8) + 0.5).toInt() % 8])
            val ilum = WidgetGraficos.iluminacion(fase)
            val proxima = if (fase < 0.5)
                context.getString(R.string.w_llena_en, WidgetGraficos.diasHasta(fase, 0.5))
            else
                context.getString(R.string.w_nueva_en, WidgetGraficos.diasHasta(fase, 1.0))
            views.setImageViewBitmap(R.id.wSol_luna, WidgetGraficos.luna(220, fase))
            views.setTextViewText(R.id.wSol_fase, nombre)
            views.setTextViewText(R.id.wSol_ilum, context.getString(R.string.w_ilum, ilum))
            views.setTextViewText(R.id.wSol_proxima, proxima)
        }

        fun actualizarSync(context: Context, manager: AppWidgetManager, widgetId: Int, views: RemoteViews) {
            val cache = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            pintarLuna(context, views)
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
                            "&daily=sunrise,sunset&forecast_days=2&timezone=auto"
                    )
                )
                val offset = json.optInt("utc_offset_seconds", 0)
                val daily = json.getJSONObject("daily")
                val orto = daily.getJSONArray("sunrise")
                val ocaso = daily.getJSONArray("sunset")
                val orto0 = WidgetComun.parsearIso(orto.getString(0), offset)
                val ocaso0 = WidgetComun.parsearIso(ocaso.getString(0), offset)
                val orto1 = WidgetComun.parsearIso(orto.getString(1), offset)
                val ocaso1 = WidgetComun.parsearIso(ocaso.getString(1), offset)
                val ahora = System.currentTimeMillis()

                val ciudad = WidgetComun.ciudad(lat, lon, cache)
                cache.edit().putString("ciudad", ciudad).putInt("offset", offset)
                    .putLong("orto0", orto0).putLong("ocaso0", ocaso0)
                    .putLong("orto1", orto1).putLong("ocaso1", ocaso1).apply()
                recalcularSol(context, views, cache)
            } catch (e: Exception) {
                android.util.Log.e("QTWidget", "solluna fallo", e)
                aplicarCache(context, views, cache)
            }
            android.util.Log.i("QTWidget", "solluna actualizado")
            manager.updateAppWidget(widgetId, views)
        }

        private fun pintarSol(views: RemoteViews, ciudad: String, titular: String, hora: String, arco: Bitmap?) {
            if (ciudad.isNotBlank()) views.setTextViewText(R.id.wSol_ciudad, ciudad)
            views.setTextViewText(R.id.wSol_titular, titular)
            views.setTextViewText(R.id.wSol_hora, hora)
            if (arco != null) views.setImageViewBitmap(R.id.wSol_arco, arco)
        }

        // Pinta titular y arco con la hora de AHORA a partir de los horarios de
        // sol guardados: asi el tiempo restante se actualiza en cada refresco
        // aunque no haya red (antes se repintaba el texto viejo).
        private fun recalcularSol(context: Context, views: RemoteViews, cache: SharedPreferences) {
            if (!cache.contains("orto0")) return
            val ahora = System.currentTimeMillis()
            val orto0 = cache.getLong("orto0", 0); val ocaso0 = cache.getLong("ocaso0", 0)
            val orto1 = cache.getLong("orto1", 0); val ocaso1 = cache.getLong("ocaso1", 0)
            val zona = WidgetComun.zonaDe(cache.getInt("offset", 0))
            val titular: String
            val arcoOrto: Long
            val arcoOcaso: Long
            when {
                ahora < orto0 -> {
                    titular = context.getString(R.string.w_falta_amanecer, WidgetComun.duracion(orto0 - ahora))
                    arcoOrto = orto0; arcoOcaso = ocaso0
                }
                ahora <= ocaso0 -> {
                    titular = context.getString(R.string.w_quedan_luz, WidgetComun.duracion(ocaso0 - ahora))
                    arcoOrto = orto0; arcoOcaso = ocaso0
                }
                else -> {
                    titular = context.getString(R.string.w_falta_amanecer, WidgetComun.duracion(orto1 - ahora))
                    arcoOrto = orto1; arcoOcaso = ocaso1
                }
            }
            val hora = WidgetComun.horaActualizacion()
            val arco = WidgetGraficos.arcoSolar(700, 190, ahora, arcoOrto, arcoOcaso, zona)
            WidgetComun.guardarBitmap(context, ARCHIVO_ARCO, arco)
            pintarSol(views, cache.getString("ciudad", "") ?: "", titular, hora, arco)
        }

        private fun aplicarCache(context: Context, views: RemoteViews, cache: SharedPreferences) {
            recalcularSol(context, views, cache)
        }
    }
}
