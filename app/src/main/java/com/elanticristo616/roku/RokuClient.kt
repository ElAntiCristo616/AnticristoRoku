package com.elanticristo616.roku

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Cliente para hablar con una Roku por su red local.
 * Usa los protocolos abiertos oficiales de Roku:
 *  - ECP (External Control Protocol): HTTP en el puerto 8060
 *  - DIAL: lanzamiento de apps por HTTP en el mismo puerto 8060
 *  - SSDP: descubrimiento con M-SEARCH (ST: roku:ecp)
 * No requiere emparejar ni cuenta. Todo queda en la red local.
 */

/** Error hablando con la tele: lleva el código HTTP real (-1 = sin conexión). */
class RokuException(val code: Int = -1, val timedOut: Boolean = false, message: String = "") :
    Exception(message)

class RokuClient(val ip: String) {

    private val base = "http://$ip:8060"

    data class RokuApp(val id: String, val name: String, val version: String = "")
    data class DeviceInfo(val friendlyName: String, val modelName: String, val serial: String = "")

    /** Pulsa una tecla del control por ECP. Teclas válidas: Home, Back, Up, Down, Left,
     *  Right, Select, Play, Rev, Fwd, VolumeUp, VolumeDown, VolumeMute, PowerOff,
     *  PowerOn, Info, Search, InstantReplay, ChannelUp, ChannelDown, InputTuner,
     *  o Lit_<carácter> para escribir texto. */
    suspend fun keypress(key: String): Boolean = withContext(Dispatchers.IO) {
        try { keypressCode(key) in 200..299 } catch (e: Exception) { false }
    }

    /** Pulsa una tecla y devuelve el código HTTP real. Lanza RokuException sin conexión. */
    suspend fun keypressCode(key: String): Int = withContext(Dispatchers.IO) {
        postCode("$base/keypress/$key")
    }

    /** Escribe texto en la tele, tecla por tecla (Lit_). Lanza RokuException si falla. */
    suspend fun sendText(text: String) = withContext(Dispatchers.IO) {
        for (ch in text) {
            val encoded = URLEncoder.encode(ch.toString(), StandardCharsets.UTF_8.name())
                .replace("+", "%20")
            val code = postCode("$base/keypress/Lit_$encoded")
            if (code !in 200..299) throw RokuException(code, false, "Tecla Lit_ $code")
        }
    }

    /** Lanza un canal por su id (p. ej. 2213 = Reproductor de Roku). */
    suspend fun launch(id: String, contentId: String? = null, mediaType: String? = null): Boolean =
        try { launchCode(id, contentId, mediaType) in 200..299 } catch (e: Exception) { false }

    /** Lanza un canal y devuelve el código HTTP real. Lanza RokuException sin conexión. */
    suspend fun launchCode(id: String, contentId: String? = null, mediaType: String? = null): Int =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append("$base/launch/$id")
                val params = mutableListOf<String>()
                if (contentId != null) params += "contentID=" + URLEncoder.encode(contentId, "UTF-8")
                if (mediaType != null) params += "mediaType=" + URLEncoder.encode(mediaType, "UTF-8")
                if (params.isNotEmpty()) append("?" + params.joinToString("&"))
            }
            postCode(url)
        }

    /** Lanza YouTube por DIAL con un video (v=<id>). Primero revisa que esté
     *  instalada (404 o installable = no instalada); manda Origin como los
     *  clientes reales de YouTube; si DIAL falla, prueba por ECP con el
     *  canal de YouTube en Roku (837; el 12 es Netflix).
     *  Devuelve el código HTTP. Lanza RokuException con el código real si falla. */
    suspend fun launchYouTube(videoId: String): Int = withContext(Dispatchers.IO) {
        if (!checkDialApp("YouTube")) throw RokuException(404, false, "YouTube no instalado")
        val code = postDialCode("YouTube", "v=$videoId", origin = "https://www.youtube.com")
        if (code in 200..299) return@withContext code
        val fb = postCode("$base/launch/837?contentID=" + URLEncoder.encode(videoId, "UTF-8"))
        if (fb in 200..299) return@withContext fb
        throw RokuException(fb, false, "DIAL $code, ECP $fb")
    }

    /** POST a /input?... para hablar con un canal receptor (Web Video
     *  Caster - Receiver). Devuelve el código HTTP real.
     *  Lanza RokuException sin conexión. */
    suspend fun postInputCode(query: String): Int = withContext(Dispatchers.IO) {
        postCode("$base/input?$query")
    }

    /** Lanza Netflix por DIAL (abre la app). Revisa que esté instalada primero. */
    suspend fun launchNetflix(): Int = withContext(Dispatchers.IO) {
        if (!checkDialApp("Netflix")) throw RokuException(404, false, "Netflix no instalado")
        postDialCode("Netflix", null)
    }

    /** ¿La app DIAL está instalada? GET /apps/<app>: 404 o state=installable = no. */
    private fun checkDialApp(app: String): Boolean {
        val (code, body) = getCode("$base/apps/$app")
        if (code == 404) return false
        if (code != 200) return false
        if (body != null && Regex("""<state>\s*installable\s*</state>""").containsMatchIn(body)) return false
        return true
    }

    /** Apps instaladas en la tele. */
    suspend fun queryApps(): List<RokuApp> = withContext(Dispatchers.IO) {
        val xml = get("$base/query/apps") ?: return@withContext emptyList()
        parseApps(xml)
    }

    /** Info del dispositivo (nombre, modelo, serie). */
    suspend fun queryDeviceInfo(): DeviceInfo? = withContext(Dispatchers.IO) {
        val xml = get("$base/query/device-info") ?: return@withContext null
        parseDeviceInfo(xml)
    }

    /** App abierta ahora mismo en la tele. */
    suspend fun queryActiveApp(): RokuApp? = withContext(Dispatchers.IO) {
        val xml = get("$base/query/active-app") ?: return@withContext null
        parseApps(xml).firstOrNull()
    }

    /** ¿La tele responde a ECP? */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        get("$base/query/device-info") != null
    }

    /** POST que devuelve el código HTTP real. Lanza RokuException sin conexión. */
    private fun postCode(url: String): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 4000
            conn.readTimeout = 5000
            conn.connect()
            return conn.responseCode
        } catch (e: java.net.SocketTimeoutException) {
            throw RokuException(-1, timedOut = true, "Tiempo agotado")
        } catch (e: Exception) {
            throw RokuException(-1, false, e.message ?: "Sin conexión")
        } finally {
            try { conn.disconnect() } catch (e: Exception) { }
        }
    }

    /** POST DIAL que devuelve el código HTTP real. Lanza RokuException sin conexión. */
    private fun postDialCode(app: String, body: String?, origin: String? = null): Int {
        val conn = URL("$base/apps/$app").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            if (origin != null) conn.setRequestProperty("Origin", origin)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/plain")
                conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            conn.connect()
            return conn.responseCode
        } catch (e: java.net.SocketTimeoutException) {
            throw RokuException(-1, timedOut = true, "Tiempo agotado")
        } catch (e: Exception) {
            throw RokuException(-1, false, e.message ?: "Sin conexión")
        } finally {
            try { conn.disconnect() } catch (e: Exception) { }
        }
    }

    private fun get(url: String): String? {
        return try {
            val (code, body) = getCode(url)
            if (code == 200) body else null
        } catch (e: Exception) {
            null
        }
    }

    /** GET que devuelve (código HTTP, cuerpo o null). Lanza RokuException sin conexión. */
    private fun getCode(url: String): Pair<Int, String?> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 4000
            conn.readTimeout = 5000
            conn.connect()
            val code = conn.responseCode
            if (code != 200) return code to null
            val text = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).readText()
            return code to text
        } catch (e: java.net.SocketTimeoutException) {
            throw RokuException(-1, timedOut = true, "Tiempo agotado")
        } catch (e: Exception) {
            throw RokuException(-1, false, e.message ?: "Sin conexión")
        } finally {
            try { conn.disconnect() } catch (e: Exception) { }
        }
    }

    private fun parseApps(xml: String): List<RokuApp> {
        val apps = mutableListOf<RokuApp>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            var currentId: String? = null
            var currentName: String? = null
            var currentVersion = ""
            var currentText = ""
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "app") {
                            currentId = parser.getAttributeValue(null, "id")
                            currentVersion = parser.getAttributeValue(null, "version") ?: ""
                        }
                        currentText = ""
                    }
                    XmlPullParser.TEXT -> currentText = parser.text ?: ""
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "app") {
                            currentName = currentText.trim()
                            if (currentId != null && !currentName.isNullOrBlank()) {
                                apps += RokuApp(currentId, currentName, currentVersion)
                            }
                            currentId = null
                            currentName = null
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            // XML inesperado: se devuelve lo que se alcanzó a leer
        }
        return apps
    }

    private fun parseDeviceInfo(xml: String): DeviceInfo? {
        var friendly = ""
        var model = ""
        var serial = ""
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            var tag = ""
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> tag = parser.name
                    XmlPullParser.TEXT -> {
                        val text = (parser.text ?: "").trim()
                        when (tag) {
                            "friendly-device-name" -> friendly = text
                            "model-name" -> model = text
                            "serial-number" -> serial = text
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            return null
        }
        if (friendly.isBlank() && model.isBlank()) return null
        return DeviceInfo(friendly, model, serial)
    }

    companion object {
        /** Saca el id de video (11 caracteres) de un enlace de YouTube. */
        fun extractYouTubeId(link: String): String? {
            val t = link.trim()
            val patterns = listOf(
                Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
                Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
                Regex("""/shorts/([A-Za-z0-9_-]{11})"""),
                Regex("""/embed/([A-Za-z0-9_-]{11})"""),
                Regex("""/live/([A-Za-z0-9_-]{11})""")
            )
            for (p in patterns) {
                val m = p.find(t)
                if (m != null) return m.groupValues[1]
            }
            // Si pegó solo el id
            if (Regex("""^[A-Za-z0-9_-]{11}$""").matches(t)) return t
            return null
        }

        /** Busca Rokus en la red Wi-Fi local con SSDP.
         *  Manda cada sonda (ST roku:ecp y ST DIAL) 3 veces y escucha 8 s.
         *  Usa MulticastLock: sin él, algunos celulares no reciben las
         *  respuestas y la tele «no aparece» aunque esté ahí. */
        suspend fun discover(context: Context, timeoutMs: Int = 8000): List<RokuDevice> = withContext(Dispatchers.IO) {
            val found = LinkedHashMap<String, RokuDevice>()
            var socket: DatagramSocket? = null
            var lock: WifiManager.MulticastLock? = null
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                lock = wifi?.createMulticastLock("anticristo-roku")
                lock?.setReferenceCounted(true)
                try { lock?.acquire() } catch (e: Exception) { }
                socket = DatagramSocket()
                socket.soTimeout = 500
                val group = InetAddress.getByName("239.255.255.250")
                val sts = listOf("roku:ecp", "urn:dial-multiscreen-org:service:dial:1")
                fun sendProbes() {
                    for (st in sts) {
                        val request = (
                            "M-SEARCH * HTTP/1.1\r\n" +
                                "HOST: 239.255.255.250:1900\r\n" +
                                "MAN: \"ns=1\"\r\n" +
                                "MX: 3\r\n" +
                                "ST: $st\r\n" +
                                "\r\n"
                            ).toByteArray(StandardCharsets.UTF_8)
                        socket?.send(DatagramPacket(request, request.size, group, 1900))
                    }
                }
                val buf = ByteArray(4096)
                val deadline = System.currentTimeMillis() + timeoutMs
                var sent = 0
                var nextSend = 0L
                while (System.currentTimeMillis() < deadline) {
                    val now = System.currentTimeMillis()
                    if (sent < 3 && now >= nextSend) {
                        try { sendProbes() } catch (e: Exception) { }
                        sent++
                        nextSend = now + 500
                    }
                    try {
                        val recv = DatagramPacket(buf, buf.size)
                        socket.receive(recv)
                        val text = String(recv.data, 0, recv.length, StandardCharsets.UTF_8)
                        val isRoku = text.contains("roku:ecp", ignoreCase = true)
                        val isDial = text.contains("dial", ignoreCase = true)
                        if (!isRoku && !isDial) continue
                        val location = Regex("LOCATION:\\s*(\\S+)", RegexOption.IGNORE_CASE)
                            .find(text)?.groupValues?.getOrNull(1) ?: continue
                        val ip = Regex("://([0-9.]+)").find(location)?.groupValues?.getOrNull(1)
                            ?: recv.address.hostAddress ?: continue
                        if (!found.containsKey(ip)) {
                            val client = RokuClient(ip)
                            val info = try {
                                client.queryDeviceInfo()
                            } catch (e: Exception) {
                                null
                            }
                            // Una respuesta DIAL pura (sin roku:ecp) solo vale si
                            // el aparato responde como Roku al pedirle su info.
                            if (info == null && !isRoku) continue
                            found[ip] = RokuDevice(
                                ip = ip,
                                name = info?.friendlyName?.ifBlank { null } ?: "Roku TV",
                                model = info?.modelName ?: "",
                                location = location
                            )
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Sin respuesta en este intervalo: se sigue esperando
                    }
                }
            } catch (e: Exception) {
                // Sin red o sin permiso: se devuelve lo encontrado (vacío)
            } finally {
                try { socket?.close() } catch (e: Exception) { }
                try { if (lock?.isHeld == true) lock.release() } catch (e: Exception) { }
            }
            found.values.toList()
        }
    }

    data class RokuDevice(
        val ip: String,
        val name: String,
        val model: String,
        val location: String
    )
}
