package com.elanticristo616.roku

import android.content.Context
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
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var client: RokuClient? = null
    private var server: MediaServer? = null
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

    override fun onDestroy() {
        super.onDestroy()
        try { server?.stop() } catch (e: Exception) { }
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
            val ok = try { c.keypress(key) } catch (e: Exception) { false }
            if (!ok) mostrarErrorConexion()
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
            val ok = try { c.sendText(texto) } catch (e: Exception) { false }
            if (!ok) mostrarErrorConexion()
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
            val ok = try { c.launchYouTube(id) } catch (e: Exception) { false }
            if (ok) {
                binding.tvStatusText.text = "Video mandado a la tele. Conectada: ${connectedName ?: ""}"
            } else {
                mostrarErrorConexion()
            }
        }
    }

    private fun mandarNetflix() {
        val c = requiereConexion() ?: return
        lifecycleScope.launch {
            val ok = try { c.launchNetflix() } catch (e: Exception) { false }
            if (!ok) mostrarErrorConexion()
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
            val ok = mandarArchivoLocal(c, file)
            if (!ok) mostrarErrorConexion()
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
            val mediaType = MediaServer.mediaTypeFor(url.substringBefore('?'))
            val ok = try { c.launch("2213", url, mediaType) } catch (e: Exception) { false }
            if (ok) {
                binding.mediaStatusText.text = "Mandando a la tele…"
            } else {
                mostrarErrorConexion()
            }
        }
    }

    /** Copia el archivo elegido a caché, levanta el servidor local y lo manda a la Roku. */
    private suspend fun mandarArchivoLocal(c: RokuClient, file: File): Boolean {
        return try {
            val srv = ensureServer()
            srv.file = file
            srv.displayName = file.name
            val url = srv.mediaUrl()
            if (url == null) {
                binding.mediaStatusText.text = "No encontré la IP de tu Wi-Fi. Revisa que estés conectado al Wi-Fi."
                return false
            }
            val mediaType = MediaServer.mediaTypeFor(file.name)
            val ok = c.launch("2213", url, mediaType)
            if (ok) {
                binding.mediaStatusText.text = "Reproduciendo «${file.name}» en la tele.\n" +
                    "Mantén la app abierta mientras se ve."
            }
            ok
        } catch (e: Exception) {
            false
        }
    }

    private fun ensureServer(): MediaServer {
        server?.let { return it }
        var port = 8080
        var lastError: Exception? = null
        while (port <= 8090) {
            try {
                val srv = MediaServer(port)
                srv.start()
                server = srv
                return srv
            } catch (e: Exception) {
                lastError = e
                port++
            }
        }
        throw lastError ?: IllegalStateException("No se pudo levantar el servidor local")
    }

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

    private fun mostrarErrorConexion() {
        Toast.makeText(
            this,
            "No pude hablar con la tele. Revisa que esté prendida, en el mismo Wi-Fi que tu celular, " +
                "y que en la tele esté activado: Ajustes → Sistema → Ajustes avanzados del sistema → " +
                "Control por apps móviles → Activado.",
            Toast.LENGTH_LONG
        ).show()
    }
}
