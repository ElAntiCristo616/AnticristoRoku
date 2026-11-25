package com.elanticristo616.roku

import java.util.UUID

/**
 * Estado compartido entre la pantalla principal y el navegador:
 * la tele conectada, el servidor HTTP local (uno solo por proceso)
 * y el canal receptor con el que se está transmitiendo.
 */
object CastSession {

    /** IP de la tele conectada, o null si no hay. */
    var rokuIp: String? = null

    /** Nombre de la tele conectada. */
    var rokuName: String? = null

    /** Servidor HTTP local compartido (sirve archivos y recibe los
     *  reportes de estado del canal receptor). */
    var server: MediaServer? = null

    /** Canal receptor con el que se está transmitiendo ahora mismo. */
    var receiver: WvcReceiver? = null

    /** Sesión actual del receptor (cambia con cada transmisión). */
    var receiverSession: String = newSessionId()
        private set

    /** Último estado que reportó el canal (playing, paused, error...). */
    @Volatile
    var receiverState: String? = null

    /** Se avisa (en el hilo de red del servidor) cuando el canal
     *  receptor reporta algo: (evento, cuerpo JSON). La actividad en
     *  pantalla lo pone aquí en onResume y lo quita en onPause. */
    var onReceiverEvent: ((event: String, body: String) -> Unit)? = null

    /** Genera una sesión nueva para la próxima transmisión. */
    fun newSessionId(): String {
        receiverSession = UUID.randomUUID().toString().replace("-", "").take(12)
        return receiverSession
    }

    /** Levanta el servidor local (puertos 8080-8090) si no está arriba,
     *  y le conecta el reporte de estado del canal receptor. */
    fun ensureServer(): MediaServer {
        server?.let { return it }
        var port = 8080
        var lastError: Exception? = null
        while (port <= 8090) {
            try {
                val srv = MediaServer(port)
                srv.onReceiverEvent = { session, event, body ->
                    // Solo valen los reportes de la sesión actual
                    if (session == receiverSession) {
                        onReceiverEvent?.invoke(event, body)
                    }
                }
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

    /** Apaga el servidor y olvida el receptor. */
    fun reset() {
        try { server?.stop() } catch (e: Exception) { }
        server = null
        receiver = null
        receiverState = null
    }
}
