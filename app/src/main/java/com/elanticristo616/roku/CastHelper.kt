package com.elanticristo616.roku

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * El flujo completo de transmitir al canal receptor, usado igual por
 * la pantalla principal y por el navegador:
 *  1) levanta el servidor local (para el callback de estado),
 *  2) lanza el canal «Web Video Caster - Receiver» en la tele,
 *  3) lo saluda (deviceConnect) y le manda el video (play).
 * Los errores llevan el motivo real en español, nunca un genérico.
 */
object CastHelper {

    data class CastResult(val ok: Boolean, val message: String)

    /** Manda una URL directa de video al canal receptor.
     *  url: el video (mp4, m3u8 o mpd). fmt: mp4 | hls | dash | mp3. */
    suspend fun castUrl(
        context: Context,
        client: RokuClient,
        url: String,
        title: String,
        media: String = "video",
        fmt: String = "mp4"
    ): CastResult = withContext(Dispatchers.IO) {
        val server = try {
            CastSession.ensureServer()
        } catch (e: Exception) {
            return@withContext CastResult(
                false,
                "No pude levantar el servidor local para hablar con la tele."
            )
        }
        val wifiIp = MediaServer.wifiIp(context) ?: MediaServer.localIp(context)
            ?: return@withContext CastResult(
                false,
                "No encontré la IP de tu Wi-Fi. Revisa que estés conectado al Wi-Fi."
            )
        val callbackBase = "http://$wifiIp:${server.listeningPort}"
        val session = CastSession.newSessionId()
        val receiver = WvcReceiver(client, callbackBase, session)

        // Las URLs de yt-dlp pueden ser larguísimas y el canal solo
        // acepta unas 3000 letras: si se pasa, la tele baja el video
        // por una redirección corta de nuestro servidor local.
        val sendUrl = if (url.length > 2800) {
            server.redirectUrl(context, session, url) ?: url
        } else {
            url
        }

        val launchCode = try {
            receiver.launch()
        } catch (e: RokuException) {
            return@withContext CastResult(false, motivoDe(e))
        }
        if (launchCode !in 200..299) {
            return@withContext if (launchCode == 404) {
                CastResult(
                    false,
                    "El canal «Web Video Caster - Receiver» no está instalado en la tele. " +
                        "Búscalo gratis en la tienda de Roku (Streaming Channels) e instálalo."
                )
            } else {
                CastResult(
                    false,
                    "No pude abrir el canal receptor en la tele (código $launchCode)."
                )
            }
        }

        // Saludo al canal; si no contesta, se sigue igual: lo que
        // importa es el play.
        try {
            receiver.deviceConnect()
        } catch (e: Exception) {
            // Se sigue al play
        }

        val playCode = try {
            receiver.play(sendUrl, title, media, fmt)
        } catch (e: RokuException) {
            return@withContext CastResult(false, motivoDe(e))
        }
        if (playCode !in 200..299) {
            return@withContext CastResult(
                false,
                "El canal receptor respondió $playCode y no quiso el video."
            )
        }
        CastSession.receiver = receiver
        CastSession.receiverState = "buffering"
        CastResult(true, "Mandando «${title.take(60)}» a la tele…")
    }

    /** Pega un enlace (YouTube, TikTok, X, Instagram, Facebook…),
     *  saca el video directo con yt-dlp y lo manda al canal receptor.
     *  onProgress avisa en qué paso va («Extrayendo video…»). */
    suspend fun castLink(
        context: Context,
        client: RokuClient,
        link: String,
        onProgress: (String) -> Unit = {}
    ): CastResult {
        val video = try {
            onProgress("Extrayendo video…")
            YtDlpExtractor.extract(context, link)
        } catch (e: YtDlpExtractor.YtDlpException) {
            return CastResult(false, e.message ?: "No pude sacar el video de ese enlace.")
        } catch (e: Exception) {
            return CastResult(
                false,
                "No pude sacar el video de ese enlace (${e.message?.take(100) ?: "error"})."
            )
        }
        onProgress("Mandando «${video.title.take(50)}» a la tele…")
        return castUrl(context, client, video.url, video.title, "video", video.fmt)
    }

    /** Pausa / reanuda / para lo que se está transmitiendo. */
    suspend fun receiverCommand(cmd: String): CastResult {
        val receiver = CastSession.receiver
            ?: return CastResult(false, "Nada transmitiendo ahora.")
        return try {
            val code = when (cmd) {
                "pause" -> receiver.pause()
                "resume" -> receiver.resume()
                "stop" -> receiver.stop()
                else -> return CastResult(false, "Comando desconocido.")
            }
            if (code in 200..299) {
                CastResult(
                    true,
                    when (cmd) {
                        "pause" -> "En pausa en la tele."
                        "resume" -> "Siguiendo en la tele."
                        else -> "Parado en la tele."
                    }
                )
            } else {
                CastResult(false, "El canal receptor respondió $code.")
            }
        } catch (e: RokuException) {
            CastResult(false, motivoDe(e))
        }
    }

    /** Traduce un reporte del canal receptor a texto en español.
     *  Devuelve null si el evento no trae nada que mostrar. */
    fun describeReceiverEvent(event: String, body: String): String? {
        return try {
            val json = JSONObject(body)
            when (event) {
                "state" -> {
                    val state = json.optString("state", "")
                    val err = json.optString("errorcode", "")
                    val httpErr = json.optString("httperror", "")
                    CastSession.receiverState = state.ifBlank { null }
                    when {
                        err.isNotBlank() ->
                            "La tele marcó error $err" +
                                (if (httpErr.isNotBlank()) " (HTTP $httpErr)" else "") +
                                ". Ese video no lo pudo reproducir."
                        state == "buffering" -> "Cargando video en la tele…"
                        state == "playing" -> "Reproduciendo en la tele."
                        state == "paused" -> "En pausa en la tele."
                        state == "stopped" -> "Se detuvo en la tele."
                        state == "finished" -> "Terminó el video en la tele."
                        else -> null
                    }
                }
                "position" -> {
                    val pos = json.optDouble("position", -1.0)
                    val dur = json.optDouble("duration", -1.0)
                    if (pos >= 0 && dur > 0) {
                        "Reproduciendo en la tele — ${fmtTime(pos)} / ${fmtTime(dur)}"
                    } else {
                        null
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fmtTime(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return "%d:%02d".format(m, s)
    }

    /** Motivo en español de un error hablando con la tele. */
    fun motivoDe(e: RokuException): String = when {
        e.timedOut -> "No se pudo conectar: tiempo agotado."
        e.code == 404 -> "La tele respondió 404: eso no está instalado en la tele."
        e.code == 403 -> "La tele respondió 403: revisa Control por apps móviles en Activado."
        e.code > 0 -> "La tele respondió ${e.code}."
        else -> "No se pudo conectar con la tele. Revisa que esté prendida y en el mismo Wi-Fi."
    }
}
