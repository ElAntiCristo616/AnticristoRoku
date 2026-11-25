package com.elanticristo616.roku

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Saca el video directo de un enlace con yt-dlp (el mismo motor que
 * usa Seal y la descargadora), SIN descargar nada: solo pide la
 * información del video y elige el mejor formato que la tele puede
 * reproducir. Funciona con YouTube, TikTok, X, Instagram, Facebook
 * y los más de 1000 sitios que yt-dlp conoce.
 *
 * OJO: la librería youtubedl-android es GPL-3.0, así que esta versión
 * de la app es código abierto (GPL-3.0). yt-dlp en sí es de dominio
 * público (Unlicense).
 */
object YtDlpExtractor {

    class YtDlpException(message: String) : Exception(message)

    /** Video listo para mandar a la tele. fmt: mp4 | hls | dash | mp3. */
    data class ExtractedVideo(
        val title: String,
        val url: String,
        val fmt: String,
        val height: Int
    )

    @Volatile
    private var initialized = false
    private val initLock = Any()

    /** Arranca yt-dlp una sola vez por proceso (extrae Python a archivos
     *  privados de la app; tarda unos segundos la primera vez). */
    private fun ensureInit(context: Context) {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            try {
                YoutubeDL.getInstance().init(context.applicationContext)
                initialized = true
            } catch (e: Exception) {
                throw YtDlpException("No pude arrancar yt-dlp: ${e.message ?: "error desconocido"}")
            }
        }
    }

    /** Extrae el mejor video directo de un enlace. Lanza YtDlpException
     *  con un motivo en español si algo falla. */
    suspend fun extract(context: Context, link: String): ExtractedVideo =
        withContext(Dispatchers.IO) {
            ensureInit(context)
            val request = YoutubeDLRequest(link.trim()).apply {
                addOption("--no-playlist")
                addOption("--socket-timeout", "10")
                addOption("-R", "1")
            }
            val info = try {
                YoutubeDL.getInstance().getInfo(request)
            } catch (e: Exception) {
                throw YtDlpException(friendlyError(e))
            }
            pickFormat(info)
                ?: throw YtDlpException(
                    "Ese enlace no trae un video que la tele pueda reproducir."
                )
        }

    /** Elige el mejor formato para la Roku:
     *  1) MP4 combinado (video+audio) de 720p o menos, el más alto;
     *  2) si no, MP4 combinado de cualquier altura, el más cercano a 720p;
     *  3) si no, HLS (.m3u8); 4) si no, DASH (.mpd);
     *  5) si no, cualquier combinado con URL directa; 6) solo audio. */
    private fun pickFormat(info: VideoInfo): ExtractedVideo? {
        val title = info.title?.takeIf { it.isNotBlank() }
            ?: info.fulltitle?.takeIf { it.isNotBlank() }
            ?: "Video"
        val formats = try {
            info.formats ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        if (formats.isEmpty()) return null

        fun hasUrl(f: com.yausername.youtubedl_android.mapper.VideoFormat) =
            !f.url.isNullOrBlank()

        fun isCombined(f: com.yausername.youtubedl_android.mapper.VideoFormat) =
            hasUrl(f) && f.vcodec != null && f.vcodec != "none" &&
                f.acodec != null && f.acodec != "none"

        // 1 y 2) MP4 combinado
        val combinedMp4 = formats.filter { isCombined(it) && it.ext == "mp4" }
        val best720 = combinedMp4.filter { it.height in 1..720 }
            .maxByOrNull { it.height }
            ?: combinedMp4.minByOrNull { f ->
                val h = if (f.height > 0) f.height else 1080
                kotlin.math.abs(h - 720)
            }
        if (best720 != null) {
            return ExtractedVideo(title, best720.url!!, "mp4", best720.height)
        }

        // 3) HLS (.m3u8), por manifest o por URL directa
        val hls = formats.firstOrNull { f ->
            (!f.manifestUrl.isNullOrBlank() && f.manifestUrl!!.contains(".m3u8")) ||
                (!f.url.isNullOrBlank() && f.url!!.contains(".m3u8"))
        }
        if (hls != null) {
            val u = if (!hls.manifestUrl.isNullOrBlank() && hls.manifestUrl!!.contains(".m3u8")) {
                hls.manifestUrl!!
            } else {
                hls.url!!
            }
            return ExtractedVideo(title, u, "hls", hls.height)
        }

        // 4) DASH (.mpd)
        val dash = formats.firstOrNull { f ->
            !f.manifestUrl.isNullOrBlank() && f.manifestUrl!!.contains(".mpd")
        }
        if (dash != null) {
            return ExtractedVideo(title, dash.manifestUrl!!, "dash", dash.height)
        }

        // 5) Cualquier combinado con URL directa
        val anyCombined = formats.firstOrNull { isCombined(it) }
        if (anyCombined != null) {
            val fmt = if (anyCombined.url!!.contains(".mp3")) "mp3" else "mp4"
            return ExtractedVideo(title, anyCombined.url!!, fmt, anyCombined.height)
        }

        // 6) Solo audio (mp3/m4a)
        val audio = formats.firstOrNull { f ->
            hasUrl(f) && f.vcodec == "none" && f.acodec != null && f.acodec != "none" &&
                (f.ext == "mp3" || f.ext == "m4a")
        }
        if (audio != null) {
            return ExtractedVideo(
                title,
                audio.url!!,
                if (audio.ext == "mp3") "mp3" else "mp4",
                0
            )
        }
        return null
    }

    private fun friendlyError(e: Exception): String {
        val m = (e.message ?: "").lowercase()
        return when {
            "unsupported url" in m ->
                "Ese enlace no es de un sitio que yt-dlp conozca."
            "private video" in m || "sign in" in m || "log in" in m ||
                "login required" in m || "cookies" in m ->
                "Ese video es privado o pide iniciar sesión."
            "unavailable" in m || "not available" in m ->
                "Ese video no está disponible."
            "timed out" in m || "timeout" in m ->
                "El sitio tardó demasiado en responder. Prueba de nuevo."
            "unable to download" in m || "unable to extract" in m ->
                "No pude leer ese enlace."
            e.message != null ->
                "No pude sacar el video de ese enlace (${e.message!!.take(120)})."
            else -> "No pude sacar el video de ese enlace."
        }
    }
}
