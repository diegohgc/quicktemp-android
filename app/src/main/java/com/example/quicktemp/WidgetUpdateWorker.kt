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
        val manager = AppWidgetManager.getInstance(applicationContext)

        val ids = manager.getAppWidgetIds(ComponentName(applicationContext, TemperaturaWidget::class.java))
        for (id in ids) {
            val views = TemperaturaWidget.crearViewsBase(applicationContext, id)
            TemperaturaWidget.actualizarWidgetSync(applicationContext, manager, id, views)
        }

        val idsHoras = manager.getAppWidgetIds(ComponentName(applicationContext, TemperaturaWidgetHoras::class.java))
        for (id in idsHoras) {
            val views = TemperaturaWidgetHoras.crearViewsBase(applicationContext, id)
            TemperaturaWidgetHoras.actualizarWidgetHorasSync(applicationContext, manager, id, views)
        }

        return Result.success()
    }
}
