package com.diegohg.quicktemp

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

// Refresco periódico del widget vía WorkManager, más fiable frente al
// ahorro de batería agresivo de algunos fabricantes (Samsung, Xiaomi...)
// que el simple updatePeriodMillis del AppWidgetProvider -- ver
// programarActualizacionPeriodica() en MainActivity, que encola este
// worker cada 15 minutos (el mínimo que permite WorkManager) con
// KEEP para no duplicar la tarea en cada apertura de la app.
class WidgetUpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        android.util.Log.i("QTWidget", "worker run")
        val manager = AppWidgetManager.getInstance(applicationContext)

        // Cada widget se actualiza por separado y dentro de su propio
        // try/catch: si uno falla (red, datos...) los demas se actualizan igual.
        fun grupo(clase: Class<*>, actualizar: (Int) -> Unit) {
            val ids = try {
                manager.getAppWidgetIds(ComponentName(applicationContext, clase))
            } catch (e: Exception) { IntArray(0) }
            for (id in ids) {
                try { actualizar(id) } catch (e: Exception) { /* se conserva el ultimo dato bueno */ }
            }
        }

        grupo(TemperaturaWidget::class.java) { id ->
            val views = TemperaturaWidget.crearViewsBase(applicationContext, id)
            TemperaturaWidget.actualizarWidgetSync(applicationContext, manager, id, views)
        }
        grupo(TemperaturaWidgetHoras::class.java) { id ->
            val views = TemperaturaWidgetHoras.crearViewsBase(applicationContext, id)
            TemperaturaWidgetHoras.actualizarWidgetHorasSync(applicationContext, manager, id, views)
        }
        grupo(TemperaturaWidgetMareas::class.java) { id ->
            val views = TemperaturaWidgetMareas.crearViewsBase(applicationContext, id)
            TemperaturaWidgetMareas.actualizarSync(applicationContext, manager, id, views)
        }
        grupo(TemperaturaWidgetLluvia::class.java) { id ->
            val views = TemperaturaWidgetLluvia.crearViewsBase(applicationContext, id)
            TemperaturaWidgetLluvia.actualizarSync(applicationContext, manager, id, views)
        }
        grupo(TemperaturaWidgetSolLuna::class.java) { id ->
            val views = TemperaturaWidgetSolLuna.crearViewsBase(applicationContext, id)
            TemperaturaWidgetSolLuna.actualizarSync(applicationContext, manager, id, views)
        }

        // Rearma la alarma horaria (por si el sistema la perdio, p. ej. tras reiniciar)
        WidgetProgramador.armarAlarmaHoraria(applicationContext)
        return Result.success()
    }
}
