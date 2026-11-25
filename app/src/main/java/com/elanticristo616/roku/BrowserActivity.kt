package com.elanticristo616.roku

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.elanticristo616.roku.databinding.ActivityBrowserBinding
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * Navegador con detección de video y transmisión a la tele.
 *  - Abre páginas en un WebView y detecta videos de dos formas:
 *    a) oliendo la red (peticiones .mp4/.m3u8/.mpd),
 *    b) escaneando el DOM (<video> y <source>).
 *  - El video detectado se transmite directo al canal receptor.
 *  - Para enlaces de YouTube, TikTok, X, Instagram o Facebook se usa
 *    yt-dlp, que saca el video directo sin descargar nada.
 * Todo se manda por el canal receptor «Web Video Caster - Receiver»;
 * el Reproductor de Roku (2213) no se usa: Roku lo bloqueó en OS 11.5.
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private var client: RokuClient? = null
    private var pageTitle: String = ""
    private var currentUrl: String = ""
    private val detectedUrls = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val ip = CastSession.rokuIp
        if (ip == null) {
            Toast.makeText(
                this,
                "Primero conecta tu tele en la pantalla principal.",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }
        client = RokuClient(ip)

        binding.btnVolver.setOnClickListener { finish() }
        binding.btnIr.setOnClickListener { irA(binding.etDireccion.text.toString()) }
        binding.btnTransmitirDetectado.setOnClickListener { transmitirDetectado() }
        binding.btnTransmitirEnlace.setOnClickListener { transmitirEnlace() }
        binding.btnTransmitirPagina.setOnClickListener { transmitirPagina() }

        setupWebView()
        binding.webView.loadUrl("https://duckduckgo.com")
        binding.etDireccion.setText("https://duckduckgo.com")
    }

    override fun onResume() {
        super.onResume()
        CastSession.onReceiverEvent = { event, body ->
            runOnUiThread {
                CastHelper.describeReceiverEvent(event, body)?.let {
                    binding.tvCastStatus.text = it
                }
            }
        }
    }

    override fun onPause() {
        if (CastSession.onReceiverEvent != null) CastSession.onReceiverEvent = null
        super.onPause()
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    currentUrl = url
                    binding.etDireccion.setText(url)
                    detectedUrls.clear()
                    updateDetectedCard()
                    scanPageForVideos()
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val u = request.url.toString()
                    if (isDirectVideoUrl(u)) {
                        runOnUiThread { onDirectVideoFound(u) }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String?) {
                    pageTitle = title ?: ""
                }
            }
        }
    }

    private fun irA(text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://duckduckgo.com/?q=" +
                java.net.URLEncoder.encode(t, "UTF-8")
        }
        binding.webView.loadUrl(url)
    }

    // ---------- Detección de video ----------

    /** ¿La URL apunta directo a un archivo o flujo de video? */
    private fun isDirectVideoUrl(u: String): Boolean {
        val lower = u.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val noQuery = lower.substringBefore('?').substringBefore('#')
        return noQuery.endsWith(".mp4") || noQuery.endsWith(".m3u8") ||
            noQuery.endsWith(".mpd") || noQuery.endsWith(".mov") ||
            noQuery.endsWith(".mkv") || noQuery.endsWith(".webm") ||
            lower.contains(".mp4?") || lower.contains(".m3u8?") ||
            lower.contains(".mpd?")
    }

    private fun onDirectVideoFound(url: String) {
        if (detectedUrls.add(url)) updateDetectedCard()
    }

    /** Busca <video> y <source> en la página ya cargada. */
    private fun scanPageForVideos() {
        val js = "(function(){var v=[];" +
            "document.querySelectorAll('video').forEach(function(el){" +
            "if(el.currentSrc)v.push(el.currentSrc);" +
            "if(el.src)v.push(el.src);" +
            "el.querySelectorAll('source').forEach(function(s){if(s.src)v.push(s.src);});" +
            "});return JSON.stringify(v);})()"
        binding.webView.evaluateJavascript(js) { result ->
            try {
                val arr = JSONArray(result)
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i, "")
                    if (isDirectVideoUrl(u)) detectedUrls.add(u)
                }
                updateDetectedCard()
            } catch (e: Exception) {
                // La página no trajo videos directos: nada que mostrar
            }
        }
    }

    private fun updateDetectedCard() {
        val first = detectedUrls.firstOrNull()
        if (first == null) {
            binding.detectedCard.visibility = View.GONE
            binding.tvBrowserStatus.text =
                "Abre una página con video y te aviso para transmitirlo."
        } else {
            binding.detectedCard.visibility = View.VISIBLE
            binding.tvDetectedUrl.text = first
            val extra = if (detectedUrls.size > 1) " (+${detectedUrls.size - 1} más)" else ""
            binding.tvBrowserStatus.text = "Video detectado$extra. Toca «Transmitir este video»."
        }
    }

    // ---------- Transmitir ----------

    private fun fmtFor(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") -> "hls"
            lower.contains(".mpd") -> "dash"
            lower.contains(".mp3") -> "mp3"
            else -> "mp4"
        }
    }

    private fun transmitirDetectado() {
        val c = client ?: return
        val url = detectedUrls.firstOrNull() ?: return
        val title = pageTitle.ifBlank { "Video" }
        binding.tvCastStatus.text = "Mandando video a la tele…"
        lifecycleScope.launch {
            val res = CastHelper.castUrl(this@BrowserActivity, c, url, title, "video", fmtFor(url))
            binding.tvCastStatus.text = res.message
            if (!res.ok) {
                Toast.makeText(this@BrowserActivity, res.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun transmitirEnlace() {
        val c = client ?: return
        val link = binding.etEnlace.text.toString().trim()
        if (link.isBlank()) {
            Toast.makeText(this, "Pega un enlace primero.", Toast.LENGTH_SHORT).show()
            return
        }
        binding.tvCastStatus.text = "Extrayendo video…"
        lifecycleScope.launch {
            val res = CastHelper.castLink(this@BrowserActivity, c, link) { msg ->
                binding.tvCastStatus.text = msg
            }
            binding.tvCastStatus.text = res.message
            if (!res.ok) {
                Toast.makeText(this@BrowserActivity, res.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun transmitirPagina() {
        val c = client ?: return
        val url = currentUrl.ifBlank { binding.webView.url ?: "" }
        if (url.isBlank()) {
            Toast.makeText(this, "Todavía no hay página cargada.", Toast.LENGTH_SHORT).show()
            return
        }
        binding.tvCastStatus.text = "Extrayendo video de esta página…"
        lifecycleScope.launch {
            val res = CastHelper.castLink(this@BrowserActivity, c, url) { msg ->
                binding.tvCastStatus.text = msg
            }
            binding.tvCastStatus.text = res.message
            if (!res.ok) {
                Toast.makeText(this@BrowserActivity, res.message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
