package com.diegohg.quicktemp

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Utilidades compartidas por los widgets nuevos (mareas, lluvia, sol y luna).
object WidgetComun {

    // Descarga con tiempos limite: un widget no debe quedarse colgado
    // esperando una red que no responde (el worker de refresco es corto).
    fun descargar(url: String): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 10000
        con.readTimeout = 15000
        try {
            return con.inputStream.bufferedReader().use { it.readText() }
        } finally {
            con.disconnect()
        }
    }

    // "2026-10-21T10:00" en la hora local de la ubicacion (utc_offset_seconds
    // de la respuesta de Open-Meteo) -> milisegundos UTC.
    fun parsearIso(texto: String, utcOffsetSeg: Int): Long {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        val ms = f.parse(texto)?.time ?: 0L
        return ms - utcOffsetSeg * 1000L
    }

    fun zonaDe(utcOffsetSeg: Int): TimeZone =
        TimeZone.getTimeZone(String.format(Locale.US, "GMT%+03d:%02d", utcOffsetSeg / 3600, Math.abs(utcOffsetSeg % 3600) / 60))

    fun hhmm(ms: Long, zona: TimeZone): String {
        val f = SimpleDateFormat("HH:mm", Locale.getDefault())
        f.timeZone = zona
        return f.format(Date(ms))
    }

    // 135 min -> "2 h 15 min"; 40 min -> "40 min"
    fun duracion(ms: Long): String {
        val totalMin = Math.max(0L, ms / 60000L)
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "$h h $m min" else "$m min"
    }

    fun horaActualizacion(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    // Clic en todo el widget -> abre la app.
    fun clicAbrirApp(context: Context, views: RemoteViews, rootId: Int, widgetId: Int, desplazamiento: Int) {
        val abrirApp = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val pending = PendingIntent.getActivity(
            context, widgetId + desplazamiento, abrirApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(rootId, pending)
    }

    // Boton propio de refresco (tiene prioridad sobre el clic del contenedor).
    fun clicRefrescar(context: Context, views: RemoteViews, botonId: Int, widgetId: Int, clase: Class<*>, accion: String) {
        val intent = Intent(context, clase).apply { action = accion }
        val pending = PendingIntent.getBroadcast(
            context, widgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(botonId, pending)
    }

    // Nombre de la localidad (BigDataCloud, igual que los otros widgets);
    // si falla se usa la ultima guardada.
    fun ciudad(lat: Double, lon: Double, cache: SharedPreferences): String {
        val idioma = Locale.getDefault().language.ifBlank { "es" }
        return try {
            val cj = JSONObject(descargar("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon&localityLanguage=$idioma"))
            cj.optString("locality")
                .ifBlank { cj.optString("city") }
                .ifBlank { cj.optString("countryName") }
                .ifBlank { cache.getString("ciudad", "") ?: "" }
        } catch (e: Exception) {
            cache.getString("ciudad", "") ?: ""
        }
    }

    // Imagen del ultimo grafico bueno, para repintarla si falla la red.
    fun guardarBitmap(context: Context, nombre: String, bmp: Bitmap) {
        try {
            File(context.filesDir, nombre).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) { /* sin cache de imagen: no pasa nada */ }
    }

    fun cargarBitmap(context: Context, nombre: String): Bitmap? = try {
        val f = File(context.filesDir, nombre)
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    } catch (e: Exception) { null }
}
