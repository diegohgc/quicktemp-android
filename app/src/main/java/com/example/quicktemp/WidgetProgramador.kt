package com.diegohg.quicktemp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

// Garantiza que los widgets se actualicen solos, sin depender de que el
// usuario abra la app: cada widget llama a asegurar() al colocarse o
// actualizarse, y el worker lo vuelve a llamar al terminar. Hay dos
// mecanismos que se refuerzan entre si:
//  1) Tarea periodica de WorkManager cada 15 min (el minimo permitido).
//  2) Alarma aproximada cada hora en punto (+1 min): la prevision por
//     horas cambia justo al cambiar de hora y no hasta el siguiente ciclo.
// La alarma es "inexacta" (setAndAllowWhileIdle): no requiere permisos
// especiales y respeta el ahorro de bateria; puede retrasarse unos minutos
// con el movil en reposo profundo.
object WidgetProgramador {
    private const val NOMBRE_PERIODICO = "widget_actualizacion_periodica"
    private const val NOMBRE_TICK = "widget_actualizacion_tick"
    const val ACTION_TICK = "com.diegohg.quicktemp.WIDGET_TICK"
    private const val CODIGO_ALARMA = 7001

    fun asegurar(context: Context) {
        val app = context.applicationContext
        try {
            val request = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                NOMBRE_PERIODICO, ExistingPeriodicWorkPolicy.KEEP, request
            )
        } catch (e: Exception) { /* sin WorkManager: queda la alarma y el refresco del sistema */ }
        armarAlarmaHoraria(app)
    }

    fun armarAlarmaHoraria(context: Context) {
        try {
            val alarmas = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val cal = Calendar.getInstance().apply {
                add(Calendar.HOUR_OF_DAY, 1)
                set(Calendar.MINUTE, 1)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val intent = Intent(context, WidgetTickReceiver::class.java).apply { action = ACTION_TICK }
            val pending = PendingIntent.getBroadcast(
                context, CODIGO_ALARMA, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmas.setAndAllowWhileIdle(AlarmManager.RTC, cal.timeInMillis, pending)
        } catch (e: Exception) { /* sin alarma: queda el worker periodico */ }
    }

    // Lanza ya una pasada de actualizacion de todos los widgets.
    fun actualizarYa(context: Context) {
        try {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                NOMBRE_TICK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
            )
        } catch (e: Exception) { }
    }
}

// Recibe la alarma horaria: actualiza los widgets y arma la siguiente.
class WidgetTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.i("QTWidget", "tick alarm")
        WidgetProgramador.actualizarYa(context)
        WidgetProgramador.armarAlarmaHoraria(context)
    }
}
