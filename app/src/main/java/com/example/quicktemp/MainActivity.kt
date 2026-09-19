package com.diegohg.quicktemp

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.diegohg.quicktemp.R
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var adView: AdView

    private val locationPermissionRequestCode = 100
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        MobileAds.initialize(this)

        webView = findViewById(R.id.webview)
        adView = findViewById(R.id.adView)
        adView.loadAd(AdRequest.Builder().build())

        val rootLayout = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        settings.textZoom = 100
        settings.setGeolocationEnabled(true)

        // El WebView por defecto no sabe abrir esquemas que no sean http/https
        // (por ejemplo "intent://..." o "market://..."), que es justo lo que usa
        // Play Store para redirigir a su propia app -- sin este override, cualquier
        // enlace a la ficha de Play Store (como el botón "Valorar QuickTemp") daba
        // net::ERR_UNKNOWN_URL_SCHEME en vez de abrir la Play Store nativa.
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                return try {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    startActivity(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    // La app de destino (ej. Play Store) no esta instalada:
                    // no hacemos nada, evitamos que la webview se quede en blanco.
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (hasLocationPermission()) {
                    callback?.invoke(origin, true, false)
                } else {
                    pendingGeolocationOrigin = origin
                    pendingGeolocationCallback = callback
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ),
                        locationPermissionRequestCode
                    )
                }
            }
        }

        webView.setInitialScale(1)
        // "?apk=1" identifica que la web se esta cargando desde la app
        // Android -- lo usa la propia web para activar cosas que solo
        // tienen sentido viniendo de la app instalada (ej. el boton de
        // valorar en Play Store), sin mostrarlas a visitantes normales
        // de la web/PWA.
        webView.loadUrl("https://diegohgc.github.io/temperatura/?apk=1")

        // Guardar última ubicación conocida para el widget
        guardarUltimaUbicacion()
        programarActualizacionPeriodicaWidget()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // Encola la actualización periódica del widget cada 15 min (el mínimo
    // que permite WorkManager) -- KEEP para no crear una tarea duplicada
    // cada vez que se abre la app, ya que WorkManager persiste la tarea
    // entre reinicios del dispositivo sin necesidad de más código.
    private fun programarActualizacionPeriodicaWidget() {
        val request = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "widget_actualizacion_periodica",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun guardarUltimaUbicacion() {
        if (!hasLocationPermission()) return
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (loc != null) {
                getSharedPreferences("widget_prefs", MODE_PRIVATE).edit()
                    .putFloat("lat", loc.latitude.toFloat())
                    .putFloat("lon", loc.longitude.toFloat())
                    .apply()
            }
        } catch (e: Exception) {}
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == locationPermissionRequestCode) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            pendingGeolocationCallback?.invoke(pendingGeolocationOrigin, granted, false)
            pendingGeolocationCallback = null
            pendingGeolocationOrigin = null
        }
    }

    override fun onResume() {
        super.onResume()
        adView.resume()
    }

    override fun onPause() {
        adView.pause()
        super.onPause()
    }

    override fun onDestroy() {
        adView.destroy()
        super.onDestroy()
    }
}
