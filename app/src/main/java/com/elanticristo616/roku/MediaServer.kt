package com.elanticristo616.roku

import android.content.Context
import android.net.wifi.WifiManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Servidor HTTP local en el teléfono (solo red Wi-Fi local, sin internet).
 * Sirve el archivo elegido para que el Reproductor de Roku (canal 2213)
 * lo reproduzca con ECP: /launch/2213?contentID=<url-local>&mediaType=...
 * Soporta Range (206 Partial Content) para que la Roku pueda buscar/adelantar.
 * Implementación propia con java.net.ServerSocket: sin dependencias externas.
 */
class MediaServer(port: Int) {

    @Volatile
    var file: File? = null

    @Volatile
    var displayName: String = ""

    /** Se avisa cuando el canal receptor reporta algo:
     *  (sesión, evento, cuerpo JSON). El canal hace POST a
     *  /remote_playback/<sesión>/<evento> con el estado (state,
     *  position, deviceInfo...) en JSON. */
    @Volatile
    var onReceiverEvent: ((session: String, event: String, body: String) -> Unit)? = null

    /** URLs largas guardadas por sesión: el canal receptor solo acepta
     *  URLs de unas 3000 letras, así que las de yt-dlp (que pueden ser
     *  larguísimas) se le entregan por una redirección corta /go/<sesión>. */
    private val redirectUrls = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Guarda una URL larga y devuelve su dirección corta local, o null
     *  si no hay IP de Wi-Fi para servirla. */
    fun redirectUrl(context: Context?, sessionId: String, targetUrl: String): String? {
        val ip = localIp(context) ?: return null
        redirectUrls[sessionId] = targetUrl
        return "http://$ip:$listeningPort/go/$sessionId"
    }

    private val serverSocket = ServerSocket(port)

    val listeningPort: Int get() = serverSocket.localPort

    @Volatile
    private var running = false
    private var acceptThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        acceptThread = thread(name = "roku-media-server", isDaemon = true) {
            while (running) {
                try {
                    val socket = serverSocket.accept()
                    thread(name = "roku-media-conn", isDaemon = true) {
                        handle(socket)
                    }
                } catch (e: Exception) {
                    // Al cerrar el socket se sale del bucle
                    if (!running) break
                }
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket.close() } catch (e: Exception) { }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val out = BufferedOutputStream(socket.getOutputStream())
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            // Cabeceras (se necesitan para Range y para el cuerpo del POST)
            val headers = mutableMapOf<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotBlank()) {
                val idx = line!!.indexOf(':')
                if (idx > 0) {
                    headers[line!!.substring(0, idx).trim().lowercase()] =
                        line!!.substring(idx + 1).trim()
                }
            }
            when {
                path.startsWith("/remote_playback/") ->
                    handleReceiverCallback(out, method, path, headers, reader)
                path.startsWith("/go/") ->
                    handleRedirect(out, method, path)
                path == "/media" ->
                    handleMedia(out, method, headers["range"])
                else ->
                    writeStatus(out, 404, "Not Found", "text/plain", "Sin archivo".toByteArray())
            }
        } catch (e: Exception) {
            // Conexión cortada por la Roku o error de lectura: no pasa nada
        } finally {
            try { socket.close() } catch (e: Exception) { }
        }
    }

    /** Reporte del canal receptor: POST /remote_playback/<sesión>/<evento>
     *  con el estado en JSON. Se avisa al listener y se contesta 200. */
    private fun handleReceiverCallback(
        out: BufferedOutputStream,
        method: String,
        path: String,
        headers: Map<String, String>,
        reader: BufferedReader
    ) {
        if (method != "POST") {
            writeStatus(out, 405, "Method Not Allowed", "text/plain", "Método no permitido".toByteArray())
            return
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) {
            val buf = CharArray(length)
            var read = 0
            while (read < length) {
                val n = reader.read(buf, read, length - read)
                if (n <= 0) break
                read += n
            }
            String(buf, 0, read)
        } else {
            ""
        }
        val segments = path.split("/").filter { it.isNotBlank() }
        // /remote_playback/<sesión>/<evento>
        val session = segments.getOrNull(1) ?: ""
        val event = segments.getOrNull(2) ?: ""
        try {
            onReceiverEvent?.invoke(session, event, body)
        } catch (e: Exception) {
            // El reporte no debe tumbar el servidor
        }
        writeStatus(out, 200, "OK", "application/json", "{\"ok\":true}".toByteArray())
    }

    /** Redirección corta a una URL larga: GET /go/<sesión> responde 302
     *  hacia la URL real guardada (la tele la sigue sola). */
    private fun handleRedirect(out: BufferedOutputStream, method: String, path: String) {
        if (method != "GET") {
            writeStatus(out, 405, "Method Not Allowed", "text/plain", "Método no permitido".toByteArray())
            return
        }
        val session = path.removePrefix("/go/").substringBefore('/')
        val target = redirectUrls[session]
        if (target == null) {
            writeStatus(out, 404, "Not Found", "text/plain", "Sin archivo".toByteArray())
            return
        }
        val headers = "HTTP/1.1 302 Found\r\nLocation: $target\r\n" +
            "Content-Length: 0\r\nConnection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** Sirve el archivo en /media (GET, con Range 206 para buscar). */
    private fun handleMedia(
        out: BufferedOutputStream,
        method: String,
        rangeHeader: String?
    ) {
        if (method != "GET") {
            writeStatus(out, 405, "Method Not Allowed", "text/plain", "Método no permitido".toByteArray())
            return
        }
        val f = file
        if (f == null || !f.exists()) {
            writeStatus(out, 404, "Not Found", "text/plain", "Sin archivo".toByteArray())
            return
        }
        val mime = mimeFor(f.name)
        val total = f.length()
        val (start, end, partial) = parseRange(rangeHeader, total)
        val length = (end - start + 1).coerceAtLeast(0)
        val headers = StringBuilder()
        if (partial) {
            headers.append("HTTP/1.1 206 Partial Content\r\n")
            headers.append("Content-Range: bytes $start-$end/$total\r\n")
        } else {
            headers.append("HTTP/1.1 200 OK\r\n")
        }
        headers.append("Content-Type: $mime\r\n")
        headers.append("Content-Length: $length\r\n")
        headers.append("Accept-Ranges: bytes\r\n")
        headers.append("Connection: close\r\n")
        headers.append("\r\n")
        out.write(headers.toString().toByteArray(Charsets.UTF_8))
        out.flush()
        BufferedInputStream(FileInputStream(f)).use { input ->
            var skipped = 0L
            while (skipped < start) {
                skipped += input.skip(start - skipped)
            }
            val buf = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val read = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (read <= 0) break
                out.write(buf, 0, read)
                remaining -= read
            }
            out.flush()
        }
    }

    private fun writeStatus(
        out: BufferedOutputStream,
        code: Int,
        reason: String,
        mime: String,
        body: ByteArray
    ) {
        val headers = "HTTP/1.1 $code $reason\r\nContent-Type: $mime\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(headers.toString().toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun parseRange(range: String?, total: Long): Triple<Long, Long, Boolean> {
        if (range == null || !range.startsWith("bytes=")) {
            return Triple(0L, total - 1, false)
        }
        val spec = range.removePrefix("bytes=").split("-")
        val start = spec.getOrNull(0)?.toLongOrNull() ?: 0L
        val end = spec.getOrNull(1)?.takeIf { it.isNotBlank() }?.toLongOrNull()
            ?: (total - 1)
        val clampedEnd = minOf(end, total - 1).coerceAtLeast(start)
        return Triple(start.coerceAtLeast(0), clampedEnd, true)
    }

    /** URL local del archivo que se está sirviendo, o null si no hay.
     *  Usa la IP del Wi-Fi (nunca la de los datos móviles): si la URL
     *  llevara la IP del operador, la tele no podría bajar el archivo. */
    fun mediaUrl(context: Context? = null): String? {
        val f = file ?: return null
        val ip = localIp(context) ?: return null
        return "http://$ip:$listeningPort/media"
    }

    companion object {
        /** IP del Wi-Fi desde WifiManager: la más fiable.
         *  connectionInfo.ipAddress viene en little-endian. Null sin Wi-Fi. */
        fun wifiIp(context: Context?): String? {
            if (context == null) return null
            return try {
                val wifi = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
                val raw = wifi.connectionInfo?.ipAddress ?: 0
                if (raw == 0) return null
                "${raw and 0xFF}.${(raw shr 8) and 0xFF}.${(raw shr 16) and 0xFF}.${(raw shr 24) and 0xFF}"
            } catch (e: Exception) {
                null
            }
        }

        /** IP local para el servidor: primero la del Wi-Fi (WifiManager).
         *  Sin ella, se escanean las interfaces prefiriendo wlan* y 192.168.x.x;
         *  una 10.x de datos móviles (rmnet) solo se usa si no hay ninguna otra. */
        fun localIp(context: Context? = null): String? {
            wifiIp(context)?.let { return it }
            return try {
                val candidates = mutableListOf<Pair<Int, String>>()
                val ifaces = NetworkInterface.getNetworkInterfaces()
                while (ifaces.hasMoreElements()) {
                    val iface = ifaces.nextElement()
                    if (!iface.isUp || iface.isLoopback) continue
                    val name = iface.name ?: ""
                    val isWlan = name.startsWith("wlan") || name.startsWith("wifi")
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr !is Inet4Address || !addr.isSiteLocalAddress) continue
                        val ip = addr.hostAddress ?: continue
                        val score = when {
                            isWlan && ip.startsWith("192.168.") -> 1
                            isWlan && isPrivate172(ip) -> 2
                            ip.startsWith("192.168.") -> 3
                            isPrivate172(ip) -> 4
                            isWlan && ip.startsWith("10.") -> 5
                            else -> 6
                        }
                        candidates += score to ip
                    }
                }
                candidates.sortedBy { it.first }.firstOrNull()?.second
            } catch (e: Exception) {
                null
            }
        }

        private fun isPrivate172(ip: String): Boolean {
            val parts = ip.split(".")
            val second = parts.getOrNull(1)?.toIntOrNull() ?: return false
            return parts.getOrNull(0) == "172" && second in 16..31
        }

        fun mimeFor(name: String): String {
            return when (name.substringAfterLast('.', "").lowercase()) {
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "mov" -> "video/quicktime"
                "avi" -> "video/x-msvideo"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "ogg" -> "audio/ogg"
                "wav" -> "audio/wav"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "application/octet-stream"
            }
        }

        fun mediaTypeFor(name: String): String {
            return when (name.substringAfterLast('.', "").lowercase()) {
                "mp3", "m4a", "ogg", "wav" -> "song"
                "jpg", "jpeg", "png", "webp", "gif" -> "photo"
                else -> "movie"
            }
        }
    }
}
