package com.elanticristo616.roku

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
            if (parts.size < 2 || parts[0] != "GET") {
                writeStatus(out, 405, "Method Not Allowed", "text/plain", "Método no permitido".toByteArray())
                return
            }
            val path = parts[1].substringBefore('?')
            var rangeHeader: String? = null
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotBlank()) {
                val header = line!!
                if (header.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = header.substringAfter(':').trim()
                }
            }
            if (path != "/media") {
                writeStatus(out, 404, "Not Found", "text/plain", "Sin archivo".toByteArray())
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
        } catch (e: Exception) {
            // Conexión cortada por la Roku o error de lectura: no pasa nada
        } finally {
            try { socket.close() } catch (e: Exception) { }
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

    /** URL local del archivo que se está sirviendo, o null si no hay. */
    fun mediaUrl(): String? {
        val f = file ?: return null
        val ip = localIp() ?: return null
        return "http://$ip:$listeningPort/media"
    }

    companion object {
        /** Primera IPv4 local (192.168.x.x, 10.x.x.x...). */
        fun localIp(): String? {
            return try {
                val ifaces = NetworkInterface.getNetworkInterfaces()
                while (ifaces.hasMoreElements()) {
                    val iface = ifaces.nextElement()
                    if (!iface.isUp || iface.isLoopback) continue
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && addr.isSiteLocalAddress) {
                            return addr.hostAddress
                        }
                    }
                }
                null
            } catch (e: Exception) {
                null
            }
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
