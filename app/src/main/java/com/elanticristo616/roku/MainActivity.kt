package com.elanticristo616.roku

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.elanticristo616.roku.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * Anticristo Roku: control remoto + mandar videos a la Sharp Roku TV
 * por la red local (ECP/DIAL, puerto 8060). Sin cuentas, sin anuncios.
 * NOTA: duplicar pantalla (Miracast) no se puede hacer desde una app
 * de terceros; esta app manda videos y controla la tele, que es lo
 * que sí permiten los protocolos abiertos de Roku.
 * Pantalla principal con la estructura del control de la app oficial
 * de Roku (barra superior, iconos, D-pad en cruz, rejilla, hoja
 * «Mi Roku»), con el tema navideño de la app. Sin micrófono ni
 * audífonos: son exclusivos de la app oficial. Sin guía: ECP no
 * tiene tecla Guide.
 * v1.1.0: los videos se transmiten por el canal receptor
 * «Web Video Caster - Receiver» (gratis en la tienda Roku), con
 * yt-dlp para sacar el video directo de cualquier enlace y un
 * navegador con detección de video. El Reproductor de Roku (2213)
 * ya no se usa para transmitir: Roku lo bloqueó en OS 11.5.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var client: RokuClient? = null
    private var connectedIp: String? = null
    private var connectedName: String? = null

    private val prefs by lazy { getSharedPreferences("roku", Context.MODE_PRIVATE) }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) sendLocalFile(uri)
    }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) sendLocalFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Barra superior
        binding.btnCerrar.setOnClickListener { desconectar() }
        binding.tvNombreTele.setOnClickListener { buscarTeles() }

        // Mi tele
        binding.btnBuscar.setOnClickListener { buscarTeles() }
        binding.btnConectarIp.setOnClickListener { conectarPorIp() }
        binding.btnCambiarTele.setOnClickListener {
            binding.searchControls.visibility = View.VISIBLE
            binding.btnCambiarTele.visibility = View.GONE
        }

        // Fila de iconos del control
        binding.btnBuscarTop.setOnClickListener { tecla("Search") }
        binding.btnTecladoIcono.setOnClickListener {
            val sec = binding.tecladoSection
            sec.visibility = if (sec.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        binding.btnAjustesIcono.setOnClickListener {
            val card = binding.ayudaCard
            card.visibility = if (card.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        binding.btnYouTube.setOnClickListener { mandarYouTube() }
        binding.btnNetflix.setOnClickListener { mandarNetflix() }

        // Transmitir cualquier video por el canal receptor
        binding.btnCastLink.setOnClickListener { transmitirEnlace() }
        binding.btnAbrirNavegador.setOnClickListener {
            startActivity(Intent(this, BrowserActivity::class.java))
        }
        binding.btnCastPausa.setOnClickListener { comandoReceptor("pause") }
        binding.btnCastSeguir.setOnClickListener { comandoReceptor("resume") }
        binding.btnCastParar.setOnClickListener { comandoReceptor("stop") }

        binding.btnElegirVideo.setOnClickListener { pickVideo.launch("video/*") }
        binding.btnElegirFoto.setOnClickListener { pickPhoto.launch("image/*") }
        binding.btnUrlDirecta.setOnClickListener { mandarUrlDirecta() }

        binding.btnUp.setOnClickListener { tecla("Up") }
        binding.btnDown.setOnClickListener { tecla("Down") }
        binding.btnLeft.setOnClickListener { tecla("Left") }
        binding.btnRight.setOnClickListener { tecla("Right") }
        binding.btnOk.setOnClickListener { tecla("Select") }
        binding.btnBack.setOnClickListener { tecla("Back") }
        binding.btnHome.setOnClickListener { tecla("Home") }
        binding.btnVolUp.setOnClickListener { tecla("VolumeUp") }
        binding.btnVolDown.setOnClickListener { tecla("VolumeDown") }
        binding.btnMute.setOnClickListener { tecla("VolumeMute") }
        binding.btnPlayPause.setOnClickListener { tecla("Play") }
        binding.btnRev.setOnClickListener { tecla("Rev") }
        binding.btnFwd.setOnClickListener { tecla("Fwd") }
        binding.btnPower.setOnClickListener { tecla("PowerOff") }
        binding.btnRepetir.setOnClickListener { tecla("InstantReplay") }
        binding.btnInfo.setOnClickListener { tecla("Info") }

        binding.btnActualizarApps.setOnClickListener { actualizarApps() }
        binding.btnEstado.setOnClickListener { actualizarEstado() }
        binding.btnTeclado.setOnClickListener { escribirEnTele() }

        // Reconectar con la última tele guardada
        val lastIp = prefs.getString("last_ip", null)
        val lastName = prefs.getString("last_name", null)
        if (lastIp != null) {
            conectar(lastIp, lastName ?: "Roku TV", silencioso = true)
        } else {
            actualizarVisibilidad()
            binding.tvStatusText.text = "Sin tele conectada. Toca «Buscar teles»."
        }
    }

    override fun onResume() {
        super.onResume()
        CastSession.onReceiverEvent = { event, body ->
            runOnUiThread {
                CastHelper.describeReceiverEvent(event, body)?.let {
                    binding.castStatusText.text = it
                }
            }
        }
    }

    override fun onPause() {
        if (CastSession.onReceiverEvent != null) CastSession.onReceiverEvent = null
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        CastSession.reset()
    }

    // ---------- Visibilidad según conexión ----------

    /** Muestra el control solo con tele conectada; la sección «Mi tele»
     *  queda visible siempre: sin tele muestra el buscador, con tele
     *  muestra el nombre con punto verde y el botón de cambiar. */
    private fun actualizarVisibilidad() {
        val conectado = client != null
        binding.remoteSection.visibility = if (conectado) View.VISIBLE else View.GONE
        binding.tvNombreTele.text = if (conectado) "● ${connectedName ?: "Roku TV"}" else "● Sin tele"
        if (conectado) {
            binding.searchControls.visibility = View.GONE
            binding.btnCambiarTele.visibility = View.VISIBLE
        } else {
            binding.searchControls.visibility = View.VISIBLE
            binding.btnCambiarTele.visibility = View.GONE
        }
    }

    /** Cierra el control (botón X): desconecta sin borrar nada más.
     *  Borra la última tele guardada para no reconectar sola al abrir. */
    private fun desconectar() {
        client = null
        connectedIp = null
        connectedName = null
        CastSession.rokuIp = null
        CastSession.rokuName = null
        CastSession.receiver = null
        prefs.edit().remove("last_ip").remove("last_name").apply()
        binding.tvStatusText.text = "Sin tele conectada. Toca «Buscar teles»."
        actualizarVisibilidad()
    }

    // ---------- Conexión ----------

    private fun buscarTeles() {
        binding.tvStatusText.text = "Buscando teles en tu Wi-Fi…"
        binding.devicesContainer.removeAllViews()
        lifecycleScope.launch {
            val devices = try {
                RokuClient.discover(this@MainActivity)
            } catch (e: Exception) {
                emptyList()
            }
            binding.devicesContainer.removeAllViews()
            if (devices.isEmpty()) {
                binding.tvStatusText.text = "No encontré ninguna Roku en este Wi-Fi.\n" +
                    "Revisa que la tele esté prendida y en el mismo Wi-Fi que tu celular. " +
                    "Si sigue sin aparecer, escribe su IP a mano abajo."
                return@launch
            }
            binding.tvStatusText.text = "Toca tu tele para conectar:"
            for (d in devices) {
                val b = Button(this@MainActivity).apply {
                    text = if (d.model.isNotBlank()) "${d.name} (${d.model})" else d.name
                    setOnClickListener { conectar(d.ip, d.name) }
                }
                binding.devicesContainer.addView(b)
            }
        }
    }

    /** Conecta escribiendo la IP a mano (Ajustes → Red → Acerca de en la tele). */
    private fun conectarPorIp() {
        val ip = binding.etIpManual.text.toString().trim()
        if (!Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(ip)) {
            Toast.makeText(this, "Esa IP no se ve bien. Ejemplo: 192.168.1.5", Toast.LENGTH_LONG).show()
            return
        }
        binding.tvStatusText.text = "Conectando con $ip…"
        conectar(ip, "Roku TV")
    }

    private fun conectar(ip: String, name: String, silencioso: Boolean = false) {
        lifecycleScope.launch {
            val c = RokuClient(ip)
            val ok = try { c.ping() } catch (e: Exception) { false }
            if (ok) {
                // Nombre real de la tele, si responde
                val info = try { c.queryDeviceInfo() } catch (e: Exception) { null }
                val realName = info?.friendlyName?.ifBlank { null } ?: name
                client = c
                connectedIp = ip
                connectedName = realName
                CastSession.rokuIp = ip
                CastSession.rokuName = realName
                prefs.edit().putString("last_ip", ip).putString("last_name", realName).apply()
                binding.tvStatusText.text = "Conectada: $realName ($ip)"
                actualizarVisibilidad()
                actualizarApps()
                actualizarEstado()
            } else {
                if (!silencioso) {
                    binding.tvStatusText.text = "No pude conectar con $name ($ip)."
                    mostrarErrorConexion()
                } else {
                    binding.tvStatusText.text = "Sin tele conectada. Toca «Buscar teles»."
                }
                actualizarVisibilidad()
            }
        }
    }

    private fun requiereConexion(): RokuClient? {
        val c = client
        if (c == null) {
            Toast.makeText(this, "Primero conecta tu tele con «Buscar teles».", Toast.LENGTH_LONG).show()
        }
        return c
    }

    // ---------- Control remoto ----------

    private fun tecla(key: String) {
        val c = requiereConexion() ?: return
        lifecycleScope.launch {
            try {
                val code = c.keypressCode(key)
                if (code !in 200..299) mostrarErrorConexion(motivoDe(RokuException(code)))
            } catch (e: RokuException) {
                mostrarErrorConexion(motivoDe(e))
            }
        }
    }

    private fun escribirEnTele() {
        val c = requiereConexion() ?: return
        val texto = binding.etTeclado.text.toString()
        if (texto.isBlank()) {
            Toast.makeText(this, "Escribe algo primero.", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                c.sendText(texto)
            } catch (e: RokuException) {
                mostrarErrorConexion(motivoDe(e))
            }
        }
    }

    // ---------- YouTube / Netflix ----------

    private fun mandarYouTube() {
        val c = requiereConexion() ?: return
        val link = binding.etYouTube.text.toString()
        val id = RokuClient.extractYouTubeId(link)
        if (id == null) {
            Toast.makeText(this, "Ese enlace de YouTube no lo reconozco. Pega el enlace completo del video.", Toast.LENGTH_LONG).show()
            return
        }
        binding.tvStatusText.text = "Mandando video a la tele…"
        lifecycleScope.launch {
            try {
                c.launchYouTube(id)
                binding.tvStatusText.text = "Video mandado a la tele. Conectada: ${connectedName ?: ""}"
            } catch (e: RokuException) {
                val motivo = if (e.code == 404) "YouTube no está instalado en la tele."
                    else motivoDe(e)
                mostrarErrorConexion(motivo)
            }
        }
    }

    private fun mandarNetflix() {
        val c = requiereConexion() ?: return
        lifecycleScope.launch {
            try {
                c.launchNetflix()
                binding.tvStatusText.text = "Abriendo Netflix en la tele…"
            } catch (e: RokuException) {
                val motivo = if (e.code == 404) "Netflix no está instalado en la tele."
                    else motivoDe(e)
                mostrarErrorConexion(motivo)
            }
        }
    }

    // ---------- Transmitir cualquier video (canal receptor) ----------

    /** Pega un enlace (YouTube, TikTok, X, Instagram, Facebook…),
     *  saca el video directo con yt-dlp y lo transmite por el canal
     *  receptor. El receptor debe estar instalado en la tele. */
    private fun transmitirEnlace() {
        val c = requiereConexion() ?: return
        val link = binding.etCastLink.text.toString().trim()
        if (link.isBlank()) {
            Toast.makeText(this, "Pega un enlace primero.", Toast.LENGTH_SHORT).show()
            return
        }
        binding.castStatusText.text = "Extrayendo video…"
        lifecycleScope.launch {
            val res = CastHelper.castLink(this@MainActivity, c, link) { msg ->
                binding.castStatusText.text = msg
            }
            binding.castStatusText.text = res.message
            if (!res.ok) {
                Toast.makeText(this@MainActivity, res.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Pausa / reanuda / para lo que se está transmitiendo. */
    private fun comandoReceptor(cmd: String) {
        lifecycleScope.launch {
            val res = CastHelper.receiverCommand(cmd)
            binding.castStatusText.text = res.message
            if (!res.ok) {
                Toast.makeText(this@MainActivity, res.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- Video / foto del celular ----------

    private fun sendLocalFile(uri: Uri) {
        val c = requiereConexion() ?: return
        binding.mediaStatusText.text = "Preparando archivo…"
        lifecycleScope.launch {
            val file = try {
                copyToCache(uri)
            } catch (e: Exception) {
                null
            }
            if (file == null) {
                binding.mediaStatusText.text = "No pude leer ese archivo. Prueba con otro."
                return@launch
            }
            // mandarArchivoLocal ya muestra el motivo real si algo falla
            mandarArchivoLocal(c, file)
        }
    }

    private fun mandarUrlDirecta() {
        val c = requiereConexion() ?: return
        val url = binding.etUrlDirecta.text.toString().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, "Pega una URL que empiece con http:// o https://", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            try {
                val code = c.launchCode("2213", url, MediaServer.mediaTypeFor(url.substringBefore('?')))
                if (code in 200..299) {
                    binding.mediaStatusText.text = "Mandando a la tele…"
                } else {
                    val motivo = motivoDe(RokuException(code))
                    binding.mediaStatusText.text = "No se pudo mandar. $motivo"
                    mostrarErrorConexion(motivo)
                }
            } catch (e: RokuException) {
                val motivo = motivoDe(e)
                binding.mediaStatusText.text = "No se pudo mandar. $motivo"
                mostrarErrorConexion(motivo)
            }
        }
    }

    /** Copia el archivo elegido a caché, levanta el servidor local y lo manda a la Roku. */
    private suspend fun mandarArchivoLocal(c: RokuClient, file: File): Boolean {
        return try {
            val srv = ensureServer()
            srv.file = file
            srv.displayName = file.name
            val url = srv.mediaUrl(this)
            if (url == null) {
                binding.mediaStatusText.text = "No encontré la IP de tu Wi-Fi. Revisa que estés conectado al Wi-Fi."
                return false
            }
            // La URL debe llevar la IP del Wi-Fi, nunca la de los datos móviles:
            // con la IP del operador la tele no puede bajar el archivo.
            val wifiIp = MediaServer.wifiIp(this)
            if (wifiIp != null && !url.contains("://$wifiIp:")) {
                val motivo = "La IP del servidor no es de tu Wi-Fi."
                binding.mediaStatusText.text = "$motivo La tele no puede bajar el archivo. " +
                    "Revisa que el celular esté en Wi-Fi."
                mostrarErrorConexion(motivo)
                return false
            }
            val mediaType = MediaServer.mediaTypeFor(file.name)
            try {
                val code = c.launchCode("2213", url, mediaType)
                if (code in 200..299) {
                    binding.mediaStatusText.text = "Reproduciendo «${file.name}» en la tele.\n" +
                        "Mantén la app abierta mientras se ve."
                    true
                } else {
                    val motivo = motivoDe(RokuException(code))
                    binding.mediaStatusText.text = "No se pudo mandar. $motivo"
                    mostrarErrorConexion(motivo)
                    false
                }
            } catch (e: RokuException) {
                val motivo = motivoDe(e)
                binding.mediaStatusText.text = "No se pudo mandar. $motivo"
                mostrarErrorConexion(motivo)
                false
            }
        } catch (e: Exception) {
            binding.mediaStatusText.text = "No se pudo mandar el archivo a la tele."
            mostrarErrorConexion()
            false
        }
    }

    /** Servidor HTTP local compartido (lo usa también el navegador
     *  para recibir el estado del canal receptor). */
    private fun ensureServer(): MediaServer = CastSession.ensureServer()

    private fun copyToCache(uri: Uri): File {
        val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
        } ?: "archivo_${System.currentTimeMillis()}"
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outFile = File(cacheDir, safeName)
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("No se pudo abrir el archivo")
        return outFile
    }

    // ---------- Apps y estado ----------

    private fun actualizarApps() {
        val c = client ?: return
        lifecycleScope.launch {
            val apps = try { c.queryApps() } catch (e: Exception) { emptyList() }
            binding.appsContainer.removeAllViews()
            if (apps.isEmpty()) {
                binding.appsContainer.addView(Button(this@MainActivity).apply {
                    text = "No se pudieron cargar las apps"
                    isEnabled = false
                })
                return@launch
            }
            for (app in apps) {
                val b = Button(this@MainActivity).apply {
                    text = app.name
                    setOnClickListener {
                        lifecycleScope.launch {
                            val ok = try { c.launch(app.id) } catch (e: Exception) { false }
                            if (!ok) mostrarErrorConexion()
                        }
                    }
                }
                binding.appsContainer.addView(b)
            }
        }
    }

    private fun actualizarEstado() {
        val c = client ?: return
        lifecycleScope.launch {
            val info = try { c.queryDeviceInfo() } catch (e: Exception) { null }
            val active = try { c.queryActiveApp() } catch (e: Exception) { null }
            val sb = StringBuilder()
            if (info != null) {
                sb.append("Tele: ${info.friendlyName}")
                if (info.modelName.isNotBlank()) sb.append(" (${info.modelName})")
                sb.append("\n")
            }
            if (active != null) {
                sb.append("Abierto ahora: ${active.name}")
            } else if (info == null) {
                sb.append("No pude leer el estado de la tele.")
            }
            binding.estadoText.text = sb.toString()
            binding.estadoText.visibility = View.VISIBLE
        }
    }

    // ---------- Errores ----------

    /** Muestra el motivo real del fallo en el texto de estado y en un toast. */
    private fun mostrarErrorConexion(motivo: String? = null) {
        val detalle = motivo ?: "Revisa que esté prendida, en el mismo Wi-Fi que tu celular, " +
            "y que en la tele esté activado: Ajustes → Sistema → Ajustes avanzados del sistema → " +
            "Control por apps móviles → Activado."
        val texto = "No pude hablar con la tele. $detalle"
        binding.tvStatusText.text = texto
        Toast.makeText(this, texto, Toast.LENGTH_LONG).show()
    }

    /** Traduce un error de Roku a un motivo en español. */
    private fun motivoDe(e: RokuException): String = when {
        e.timedOut -> "No se pudo conectar: tiempo agotado."
        e.code == 404 -> "La tele respondió 404: esa app no está instalada en la tele."
        e.code == 403 -> "La tele respondió 403: revisa Control por apps móviles en Activado."
        e.code > 0 -> "La tele respondió ${e.code}."
        else -> "No se pudo conectar con la tele. Revisa que esté prendida y en el mismo Wi-Fi."
    }
}
