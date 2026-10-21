package com.diegohg.quicktemp

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

// Widget de mareas: curva de las proximas horas con la hora de la pleamar y
// la bajamar, alturas, y coeficiente de marea. Mismos datos y mismo calculo
// que la tarjeta de mareas de la app (Open-Meteo Marine, 15 dias pasados
// para poder estimar el coeficiente). En ubicaciones sin datos marinos
// (interior) enseña un aviso en vez de una curva vacia.
class TemperaturaWidgetMareas : AppWidgetProvider() {

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
            val ids = manager.getAppWidgetIds(ComponentName(context, TemperaturaWidgetMareas::class.java))
            for (id in ids) actualizar(context, manager, id)
        }
    }

    companion object {
        const val ACTION_ACTUALIZAR = "com.diegohg.quicktemp.WIDGET_MAREAS_ACTUALIZAR"
        private const val CACHE_PREFS = "widget_mareas_cache"
        private const val ARCHIVO_GRAFICA = "widget_mareas_grafica.png"

        fun crearViewsBase(context: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_mareas_layout)
            WidgetComun.clicAbrirApp(context, views, R.id.wMar_root, widgetId, 300000)
            WidgetComun.clicRefrescar(context, views, R.id.wMar_botonActualizar, widgetId, TemperaturaWidgetMareas::class.java, ACTION_ACTUALIZAR)
            return views
        }

        // Nunca debe lanzar sin pintar nada (el widget se quedaria en el
        // placeholder de carga): todo va envuelto, con vista de emergencia.
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
                manager.updateAppWidget(widgetId, RemoteViews(context.packageName, R.layout.widget_mareas_layout))
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
                        "https://marine-api.open-meteo.com/v1/marine?latitude=$lat&longitude=$lon" +
                            "&hourly=sea_level_height_msl&forecast_days=2&past_days=15&timezone=auto"
                    )
                )
                val offset = json.optInt("utc_offset_seconds", 0)
                val zona = WidgetComun.zonaDe(offset)
                val hourly = json.getJSONObject("hourly")
                val ts = hourly.getJSONArray("time")
                val hs = hourly.getJSONArray("sea_level_height_msl")
                val tiempos = ArrayList<Long>()
                val alturas = ArrayList<Double?>()
                for (i in 0 until ts.length()) {
                    tiempos.add(WidgetComun.parsearIso(ts.getString(i), offset))
                    alturas.add(if (hs.isNull(i)) null else hs.getDouble(i))
                }

                val ciudad = WidgetComun.ciudad(lat, lon, cache)

                if (alturas.none { it != null }) {
                    // Sin datos marinos (interior o mar no cubierto)
                    cache.edit().putBoolean("sinDatos", true).putString("ciudad", ciudad).apply()
                    pintarSinMareas(context, views, ciudad)
                    manager.updateAppWidget(widgetId, views)
                    return
                }

                // ── Extremos (pleamares/bajamares) con interpolacion parabolica:
                // mismo calculo que obtenerMarea() de la app.
                val extremos = ArrayList<ExtremoMarea>()
                for (i in 1 until alturas.size - 1) {
                    val a0 = alturas[i - 1]; val a1 = alturas[i]; val a2 = alturas[i + 1]
                    if (a0 == null || a1 == null || a2 == null) continue
                    val subeAntes = a1 > a0
                    val subeDespues = a2 > a1
                    if (subeAntes == subeDespues) continue
                    var horaMs = tiempos[i].toDouble()
                    var altura = a1
                    val den = a0 - 2 * a1 + a2
                    if (den != 0.0) {
                        val off = 0.5 * (a0 - a2) / den
                        if (off > -1 && off < 1) {
                            horaMs += off * 3600000.0
                            altura = a1 - (a0 - a2) * (a0 - a2) / (8 * den)
                        }
                    }
                    extremos.add(ExtremoMarea(horaMs.toLong(), max(0.0, altura), subeAntes))
                }

                val ahora = System.currentTimeMillis()

                // ── Coeficiente aproximado (20-120): amplitud del ciclo mas
                // cercano a ahora frente al rango de amplitudes de los ~17 dias.
                var coef: Int? = null
                if (extremos.size >= 4) {
                    val mids = ArrayList<Double>()
                    val amps = ArrayList<Double>()
                    for (i in 1 until extremos.size) {
                        mids.add((extremos[i].horaMs + extremos[i - 1].horaMs) / 2.0)
                        amps.add(abs(extremos[i].altura - extremos[i - 1].altura))
                    }
                    val ampMin = amps.min()
                    val ampMax = amps.max()
                    var mejor = 0
                    var mejorDist = Double.MAX_VALUE
                    for (i in mids.indices) {
                        val d = abs(mids[i] - ahora)
                        if (d < mejorDist) { mejorDist = d; mejor = i }
                    }
                    val rango = if (ampMax - ampMin == 0.0) 1.0 else ampMax - ampMin
                    coef = Math.round(20 + ((amps[mejor] - ampMin) / rango) * 100).toInt()
                }

                // ── Proximas pleamar y bajamar
                val proximos = extremos.filter { it.horaMs > ahora }
                val siguiente = proximos.firstOrNull()
                val pleamar = proximos.firstOrNull { it.esPleamar }
                val bajamar = proximos.firstOrNull { !it.esPleamar }

                // ── Grafica: 3 h antes y 21 h despues de ahora
                val t0 = ahora - 3 * 3600000L
                val t1 = ahora + 21 * 3600000L
                val tV = ArrayList<Long>()
                val aV = ArrayList<Double>()
                for (i in tiempos.indices) {
                    val a = alturas[i] ?: continue
                    if (tiempos[i] >= t0 - 3600000L && tiempos[i] <= t1 + 3600000L) { tV.add(tiempos[i]); aV.add(a) }
                }
                val grafica = WidgetGraficos.curvaMarea(840, 200, tV, aV, ahora, extremos, zona)

                // ── Textos
                val m = { v: Double -> String.format(Locale.getDefault(), "%.2f m", v) }
                // Cabecera compacta: flecha de la direccion + proxima marea y cuanto falta
                val estado = if (siguiente == null) "" else
                    (if (siguiente.esPleamar) "↑ " else "↓ ") +
                        context.getString(if (siguiente.esPleamar) R.string.w_pleamar else R.string.w_bajamar) + " " +
                        context.getString(R.string.w_en, WidgetComun.duracion(siguiente.horaMs - ahora))
                val proximaTxt = ""
                val pleaTxt = pleamar?.let { "${WidgetComun.hhmm(it.horaMs, zona)} · ${m(it.altura)}" } ?: "--"
                val bajaTxt = bajamar?.let { "${WidgetComun.hhmm(it.horaMs, zona)} · ${m(it.altura)}" } ?: "--"
                val coefTxt = coef?.toString() ?: "--"
                val coefTag = when {
                    coef == null -> ""
                    coef >= 70 -> context.getString(R.string.w_vivas)
                    coef <= 45 -> context.getString(R.string.w_muertas)
                    else -> ""
                }
                val hora = WidgetComun.horaActualizacion()

                cache.edit()
                    .putBoolean("sinDatos", false)
                    .putString("ciudad", ciudad).putString("estado", estado).putString("proxima", proximaTxt)
                    .putString("plea", pleaTxt).putString("baja", bajaTxt)
                    .putString("coef", coefTxt).putString("coefTag", coefTag).putString("hora", hora)
                    .apply()
                WidgetComun.guardarBitmap(context, ARCHIVO_GRAFICA, grafica)

                pintar(views, ciudad, estado, proximaTxt, pleaTxt, bajaTxt, coefTxt, coefTag, hora, grafica)
            } catch (e: Exception) {
                aplicarCache(context, views, cache)
            }
            manager.updateAppWidget(widgetId, views)
        }

        private fun pintar(
            views: RemoteViews, ciudad: String, estado: String, proxima: String, plea: String, baja: String,
            coef: String, coefTag: String, hora: String, grafica: android.graphics.Bitmap?
        ) {
            if (ciudad.isNotBlank()) views.setTextViewText(R.id.wMar_ciudad, ciudad)
            views.setTextViewText(R.id.wMar_estado, estado)
            views.setTextViewText(R.id.wMar_plea, plea)
            views.setTextViewText(R.id.wMar_baja, baja)
            views.setTextViewText(R.id.wMar_coef, coef)
            views.setTextViewText(R.id.wMar_coefTag, coefTag)
            views.setTextViewText(R.id.wMar_hora, hora)
            views.setViewVisibility(R.id.wMar_datos, View.VISIBLE)
            views.setViewVisibility(R.id.wMar_sinDatos, View.GONE)
            if (grafica != null) views.setImageViewBitmap(R.id.wMar_grafica, grafica)
        }

        private fun pintarSinMareas(context: Context, views: RemoteViews, ciudad: String) {
            if (ciudad.isNotBlank()) views.setTextViewText(R.id.wMar_ciudad, ciudad)
            views.setTextViewText(R.id.wMar_estado, "")
            views.setTextViewText(R.id.wMar_hora, WidgetComun.horaActualizacion())
            views.setViewVisibility(R.id.wMar_datos, View.GONE)
            views.setViewVisibility(R.id.wMar_sinDatos, View.VISIBLE)
            views.setTextViewText(R.id.wMar_sinDatos, context.getString(R.string.w_sin_mareas))
        }

        private fun aplicarCache(context: Context, views: RemoteViews, cache: SharedPreferences) {
            if (cache.getBoolean("sinDatos", false)) {
                pintarSinMareas(context, views, cache.getString("ciudad", "") ?: "")
                return
            }
            val plea = cache.getString("plea", null) ?: return
            pintar(
                views, cache.getString("ciudad", "") ?: "", cache.getString("estado", "") ?: "",
                cache.getString("proxima", "") ?: "", plea, cache.getString("baja", "--") ?: "--",
                cache.getString("coef", "--") ?: "--", cache.getString("coefTag", "") ?: "",
                cache.getString("hora", "--:--") ?: "--:--", WidgetComun.cargarBitmap(context, ARCHIVO_GRAFICA)
            )
        }
    }
}
