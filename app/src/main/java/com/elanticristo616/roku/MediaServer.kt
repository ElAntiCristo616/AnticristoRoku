package com.elanticristo616.roku

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Servidor HTTP local en el teléfono (solo red Wi-Fi local, sin internet).
 * Sirve el archivo elegido para que el Reproductor de Roku (canal 2213)
 * lo reproduzca con ECP: /launch/2213?contentID=<url-local>&mediaType=...
 * Soporta Range (206 Partial Content) para que la Roku pueda buscar/adeltantar.
 */
class MediaServer(port: Int) : NanoHTTPD(port) {

    @Volatile
    var file: File? = null

    @Volatile
    var displayName: String = ""

    override fun serve(session: IHTTPSession): Response {
        val f = file
        if (f == null || !f.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Sin archivo")
        }
        val mime = mimeFor(f.name)
        val total = f.length()
        val range = session.headers["range"]
        return try {
            if (range != null && range.startsWith("bytes=")) {
                val parts = range.removePrefix("bytes=").split("-")
                val start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                val end = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.toLongOrNull()
                    ?: (total - 1)
                val clampedEnd = minOf(end, total - 1)
                val length = (clampedEnd - start + 1).coerceAtLeast(0)
                val input = FileInputStream(f)
                input.skip(start)
                val resp = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT, mime, input, length
                )
                resp.addHeader("Content-Range", "bytes $start-$clampedEnd/$total")
                resp.addHeader("Accept-Ranges", "bytes")
                resp
            } else {
                val resp = newFixedLengthResponse(
                    Response.Status.OK, mime, FileInputStream(f), total
                )
                resp.addHeader("Accept-Ranges", "bytes")
                resp
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error")
        }
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
