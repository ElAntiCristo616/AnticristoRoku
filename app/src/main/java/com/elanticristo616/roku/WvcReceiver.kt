package com.elanticristo616.roku

import java.net.URLEncoder

/**
 * Habla con el canal receptor «Web Video Caster - Receiver» de la tele
 * (canal gratis de la tienda Roku, id 259656).
 *
 * Cómo funciona (protocolo público, documentado por el proyecto
 * abierto ClearCast a partir del canal v1.8.0):
 *  - Se lanza el canal por ECP: POST :8060/launch/259656
 *  - Se le mandan comandos por ECP: POST :8060/input?cmd=<cmd>&callback=<url>&...
 *  - El canal reproduce el video directo desde internet y reporta su
 *    estado con POST JSON a <callback>/<evento> (state, position...).
 *
 * Esto sustituye al Reproductor de Roku (canal 2213) para mandar videos:
 * Roku bloqueó el «Play on Roku» por URL desde OS 11.5, pero un canal
 * receptor lanzado por ECP y alimentado por /input sí funciona.
 * Implementación propia desde la descripción del protocolo; no se copió
 * código de ningún otro proyecto.
 */
class WvcReceiver(
    private val client: RokuClient,
    callbackBase: String,
    sessionId: String
) {
    companion object {
        /** Id del canal «Web Video Caster - Receiver» en la tienda Roku. */
        const val CHANNEL_ID = "259656"
    }

    /** URL a la que el canal reporta su estado: http://<ip-wifi>:<puerto>/remote_playback/<sesion> */
    private val callback = "$callbackBase/remote_playback/$sessionId"

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Abre el canal receptor en la tele. Devuelve el código HTTP. */
    suspend fun launch(): Int = client.launchCode(CHANNEL_ID)

    /** Saluda al canal y le dice dónde reportar su estado. */
    suspend fun deviceConnect(): Int =
        client.postInputCode(
            "cmd=deviceConnect" +
                "&welcomeMessage=${enc("Anticristo Roku")}" +
                "&callback=${enc(callback)}"
        )

    /** Manda reproducir un video. fmt: mp4 | hls | dash | mp3. media: video | audio | image. */
    suspend fun play(
        url: String,
        title: String,
        media: String = "video",
        fmt: String = "mp4",
        pos: Int = 0,
        headers: Map<String, String>? = null
    ): Int {
        val q = StringBuilder("cmd=play&callback=${enc(callback)}")
        q.append("&url=").append(enc(url))
        q.append("&pos=").append(pos)
        q.append("&tit=").append(enc(title))
        q.append("&media=").append(enc(media))
        q.append("&fmt=").append(enc(fmt))
        if (!headers.isNullOrEmpty()) {
            val hdrJson = headers.entries.joinToString(",", "{", "}") { (k, v) ->
                "\"${k.replace("\"", "")}\":\"${v.replace("\"", "")}\""
            }
            q.append("&hdr=").append(enc(hdrJson))
        }
        return client.postInputCode(q.toString())
    }

    /** Pausa lo que está sonando en el canal. */
    suspend fun pause(): Int =
        client.postInputCode("cmd=pause&callback=${enc(callback)}")

    /** Reanuda lo que estaba en pausa. */
    suspend fun resume(): Int =
        client.postInputCode("cmd=resume&callback=${enc(callback)}")

    /** Para la reproducción y cierra el video. */
    suspend fun stop(): Int =
        client.postInputCode("cmd=stop&callback=${enc(callback)}")

    /** Pide al canal que reporte su posición (llega por el callback). */
    suspend fun positionGet(): Int =
        client.postInputCode("cmd=positionGet&callback=${enc(callback)}")

    /** Salta a una posición en segundos. */
    suspend fun positionSet(pos: Int): Int =
        client.postInputCode("cmd=positionSet&position=$pos&callback=${enc(callback)}")
}
