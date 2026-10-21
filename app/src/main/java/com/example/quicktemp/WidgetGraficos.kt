package com.diegohg.quicktemp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.DashPathEffect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Pleamar (esPleamar = true) o bajamar con su hora (UTC, millis) y altura (m).
data class ExtremoMarea(val horaMs: Long, val altura: Double, val esPleamar: Boolean)

// Dibujo de los graficos de los widgets nuevos (mareas, lluvia, sol y luna).
// Los RemoteViews no admiten vistas personalizadas, asi que cada grafico se
// pinta en un Bitmap y se muestra en un ImageView. Todos los tamanos estan en
// pixeles del bitmap (se escalan al ancho del widget), con fondo transparente
// y colores claros, porque el fondo del widget es oscuro (#CC000000).
object WidgetGraficos {

    private const val BLANCO = 0xFFFFFFFF.toInt()
    private const val GRIS = 0xFFAAAAAA.toInt()
    private const val AZUL = 0xFF4FC3F7.toInt()
    private const val AZUL_FONDO_ARRIBA = 0x884FC3F7.toInt()
    private const val AZUL_FONDO_ABAJO = 0x004FC3F7

    private fun texto(color: Int, tam: Float, centrado: Boolean = true, negrita: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = tam
            textAlign = if (centrado) Paint.Align.CENTER else Paint.Align.LEFT
            isFakeBoldText = negrita
        }

    private fun hhmm(ms: Long, zona: TimeZone): String {
        val f = SimpleDateFormat("HH:mm", Locale.getDefault())
        f.timeZone = zona
        return f.format(Date(ms))
    }

    // Curva de la marea en una ventana de tiempo (unas horas antes de ahora y
    // las siguientes), con relleno degradado, marcador "ahora" y la hora de
    // cada pleamar/bajamar de la ventana.
    fun curvaMarea(
        ancho: Int, alto: Int,
        tiempos: List<Long>, alturas: List<Double>,
        ahoraMs: Long, extremos: List<ExtremoMarea>, zona: TimeZone
    ): Bitmap {
        val bmp = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
        if (tiempos.size < 3) return bmp
        val c = Canvas(bmp)
        val t0 = tiempos.first().toDouble()
        val t1 = tiempos.last().toDouble()
        val hMin = alturas.min()
        val hMax = alturas.max()
        val margenArriba = alto * 0.22f
        val margenAbajo = alto * 0.22f
        val rango = max(hMax - hMin, 0.2)
        fun px(t: Long) = (((t - t0) / (t1 - t0)) * ancho).toFloat()
        fun py(h: Double) = (alto - margenAbajo - ((h - hMin) / rango) * (alto - margenAbajo - margenArriba)).toFloat()

        // Curva suavizada (Catmull-Rom convertido a Bezier cubicas)
        val pts = tiempos.indices.map { px(tiempos[it]) to py(alturas[it]) }
        val linea = Path()
        linea.moveTo(pts[0].first, pts[0].second)
        for (i in 0 until pts.size - 1) {
            val p0 = pts[max(i - 1, 0)]
            val p1 = pts[i]
            val p2 = pts[i + 1]
            val p3 = pts[min(i + 2, pts.size - 1)]
            val c1x = p1.first + (p2.first - p0.first) / 6f
            val c1y = p1.second + (p2.second - p0.second) / 6f
            val c2x = p2.first - (p3.first - p1.first) / 6f
            val c2y = p2.second - (p3.second - p1.second) / 6f
            linea.cubicTo(c1x, c1y, c2x, c2y, p2.first, p2.second)
        }
        val relleno = Path(linea)
        relleno.lineTo(pts.last().first, alto.toFloat())
        relleno.lineTo(pts.first().first, alto.toFloat())
        relleno.close()
        val pRelleno = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, margenArriba, 0f, alto.toFloat(), AZUL_FONDO_ARRIBA, AZUL_FONDO_ABAJO, Shader.TileMode.CLAMP)
        }
        c.drawPath(relleno, pRelleno)
        val pLinea = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AZUL; style = Paint.Style.STROKE; strokeWidth = alto * 0.028f; strokeCap = Paint.Cap.ROUND
        }
        c.drawPath(linea, pLinea)

        // Marcador "ahora"
        if (ahoraMs in tiempos.first()..tiempos.last()) {
            val xa = px(ahoraMs)
            val i = tiempos.indexOfLast { it <= ahoraMs }.coerceIn(0, tiempos.size - 2)
            val f = (ahoraMs - tiempos[i]).toDouble() / (tiempos[i + 1] - tiempos[i]).toDouble()
            val ha = alturas[i] + (alturas[i + 1] - alturas[i]) * f
            val ya = py(ha)
            val pRaya = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x99FFFFFF.toInt(); strokeWidth = alto * 0.012f
                pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f); style = Paint.Style.STROKE
            }
            c.drawLine(xa, 0f, xa, alto.toFloat(), pRaya)
            c.drawCircle(xa, ya, alto * 0.045f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLANCO })
        }

        // Pleamares y bajamares dentro de la ventana, con su hora
        val tam = alto * 0.15f
        for (e in extremos) {
            if (e.horaMs < tiempos.first() || e.horaMs > tiempos.last()) continue
            val x = px(e.horaMs)
            val y = py(e.altura)
            c.drawCircle(x, y, alto * 0.03f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLANCO })
            val etiqueta = hhmm(e.horaMs, zona)
            val p = texto(BLANCO, tam)
            val xt = x.coerceIn(tam * 1.6f, ancho - tam * 1.6f)
            if (e.esPleamar) c.drawText(etiqueta, xt, max(y - alto * 0.07f, tam), p)
            else c.drawText(etiqueta, xt, min(y + alto * 0.2f, alto - 4f), p)
        }
        return bmp
    }

    // Barras de lluvia (mm por hora) de las proximas horas. El color sube de
    // azul claro a azul fuerte y morado segun la intensidad.
    fun barrasLluvia(ancho: Int, alto: Int, mm: List<Double>, horas: List<String>): Bitmap {
        val bmp = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
        if (mm.isEmpty()) return bmp
        val c = Canvas(bmp)
        val n = mm.size
        val margenAbajo = alto * 0.22f
        val margenArriba = alto * 0.16f
        val maxMm = max(2.0, mm.max())
        val ancho1 = ancho.toFloat() / n
        val grosor = ancho1 * 0.62f
        val base = alto - margenAbajo
        val pBase = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44FFFFFF; strokeWidth = 2f }
        c.drawLine(0f, base, ancho.toFloat(), base, pBase)
        val tam = alto * 0.14f
        for (i in 0 until n) {
            val cx = ancho1 * i + ancho1 / 2f
            val v = mm[i]
            if (v >= 0.1) {
                val altoBarra = max(((v / maxMm) * (base - margenArriba)).toFloat(), alto * 0.05f)
                val color = when {
                    v < 0.5 -> 0xFF81D4FA.toInt()
                    v < 2.0 -> 0xFF29B6F6.toInt()
                    v < 5.0 -> 0xFF1E88E5.toInt()
                    else -> 0xFF7E57C2.toInt()
                }
                val r = RectF(cx - grosor / 2, base - altoBarra, cx + grosor / 2, base)
                c.drawRoundRect(r, grosor / 4, grosor / 4, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
                if (v >= 0.5) {
                    val txt = if (v >= 10) v.toInt().toString() else String.format(Locale.US, "%.1f", v)
                    c.drawText(txt, cx, base - altoBarra - tam * 0.25f, texto(BLANCO, tam * 0.9f))
                }
            } else {
                // hora seca: puntito para que se vea que hay dato
                c.drawCircle(cx, base - alto * 0.02f, alto * 0.018f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF })
            }
            if (i % 2 == 0 && i < horas.size) c.drawText(horas[i], cx, alto - 4f, texto(GRIS, tam))
        }
        return bmp
    }

    // Arco del recorrido del sol entre amanecer y atardecer, con el sol en
    // su posicion actual (si es de dia) y las horas en los extremos.
    fun arcoSolar(
        ancho: Int, alto: Int, ahoraMs: Long, ortoMs: Long, ocasoMs: Long, zona: TimeZone
    ): Bitmap {
        val bmp = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val margen = ancho * 0.09f
        val horizonte = alto * 0.70f
        val radioY = alto * 0.58f
        val xi = margen
        val xf = ancho - margen
        val pHor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF; strokeWidth = 3f }
        c.drawLine(0f, horizonte, ancho.toFloat(), horizonte, pHor)

        val arco = Path()
        val pasos = 60
        for (i in 0..pasos) {
            val f = i / pasos.toFloat()
            val x = xi + (xf - xi) * f
            val y = horizonte - sin(PI * f).toFloat() * radioY
            if (i == 0) arco.moveTo(x, y) else arco.lineTo(x, y)
        }
        val pArco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x88FFC107.toInt(); style = Paint.Style.STROKE; strokeWidth = alto * 0.03f
            pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        }
        c.drawPath(arco, pArco)

        if (ahoraMs in ortoMs..ocasoMs) {
            val f = ((ahoraMs - ortoMs).toDouble() / (ocasoMs - ortoMs)).toFloat()
            val x = xi + (xf - xi) * f
            val y = horizonte - sin(PI * f).toFloat() * radioY
            c.drawCircle(x, y, alto * 0.13f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFC107 })
            c.drawCircle(x, y, alto * 0.075f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC107.toInt() })
        }
        val tam = alto * 0.17f
        c.drawText(hhmm(ortoMs, zona), xi, alto - 4f, texto(BLANCO, tam))
        c.drawText(hhmm(ocasoMs, zona), xf, alto - 4f, texto(BLANCO, tam))
        return bmp
    }

    // Luna con su fase real: se sombrea cada pixel de la esfera segun el
    // angulo de iluminacion. fase: 0 = nueva, 0.25 = cuarto creciente,
    // 0.5 = llena, 0.75 = cuarto menguante (hemisferio norte: la luna
    // creciente se ilumina por la derecha).
    fun luna(tam: Int, fase: Double): Bitmap {
        val bmp = Bitmap.createBitmap(tam, tam, Bitmap.Config.ARGB_8888)
        val r = tam / 2.0 - 2
        val cx = tam / 2.0
        val alfa = 2 * PI * fase
        val lx = sin(alfa)
        val lz = -cos(alfa)
        val pixeles = IntArray(tam * tam)
        for (y in 0 until tam) {
            for (x in 0 until tam) {
                val nx = (x + 0.5 - cx) / r
                val ny = (y + 0.5 - cx) / r
                val d2 = nx * nx + ny * ny
                if (d2 > 1.0) { pixeles[y * tam + x] = Color.TRANSPARENT; continue }
                val nz = sqrt(1.0 - d2)
                val luz = nx * lx + nz * lz
                // borde suave del disco
                val bordeAlfa = (min(1.0, (1.0 - sqrt(d2)) * r / 1.6) * 255).toInt().coerceIn(0, 255)
                val brillo = ((luz + 0.06) / 0.14).coerceIn(0.0, 1.0)
                val rO = 38 + (240 - 38) * brillo
                val gO = 46 + (236 - 46) * brillo
                val bO = 62 + (214 - 62) * brillo
                // ligera textura de "mares" lunares para que no sea un disco plano
                val tex = 1.0 - 0.05 * (sin(nx * 9.0 + 1.0) * cos(ny * 7.0 - 0.5) + 1.0) / 2.0 * brillo
                pixeles[y * tam + x] = Color.argb(bordeAlfa, (rO * tex).toInt(), (gO * tex).toInt(), (bO * tex).toInt())
            }
        }
        bmp.setPixels(pixeles, 0, tam, 0, 0, tam, tam)
        // contorno fino para que se vea la parte oscura
        val c = Canvas(bmp)
        c.drawCircle(tam / 2f, tam / 2f, r.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x44FFFFFF; style = Paint.Style.STROKE; strokeWidth = 2f
        })
        return bmp
    }

    // Fase lunar (0..1) para un instante, aproximacion por mes sinodico
    // (suficiente para un widget: error de menos de medio dia).
    fun faseLunar(ms: Long): Double {
        val diasDesdeLunaNueva = (ms - 947182440000L) / 86400000.0   // luna nueva del 2000-01-06 18:14 UTC
        val ciclo = 29.530588853
        val f = (diasDesdeLunaNueva % ciclo) / ciclo
        return if (f < 0) f + 1 else f
    }

    fun iluminacion(fase: Double): Int = (((1 - cos(2 * PI * fase)) / 2) * 100).toInt()

    // Cuantos dias faltan para una fase concreta (0 = nueva, 0.5 = llena).
    fun diasHasta(faseActual: Double, faseObjetivo: Double): Int {
        var d = faseObjetivo - faseActual
        if (d <= 0) d += 1.0
        return (d * 29.530588853).toInt().coerceAtLeast(0)
    }

}
