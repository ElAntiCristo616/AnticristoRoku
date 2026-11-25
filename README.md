# Anticristo Roku

App Android propia para controlar una Roku TV por la red Wi-Fi local
y mandarle videos, sin cuentas, sin anuncios y sin rastreadores.

> Desde la v1.1.0 la app es de **código abierto (GPL-3.0)**: usa
> yt-dlp con youtubedl-android (GPL-3.0) para sacar el video directo
> de los enlaces. El repo sigue siendo privado hasta que el dueño
> decida abrirlo.

## Qué hace

- **Descubre** Rokus en la red local por SSDP (`M-SEARCH`, `ST: roku:ecp`).
- **Control remoto completo** por ECP (`POST http://<ip>:8060/keypress/<tecla>`):
  D-pad, OK, Atrás, Inicio, volumen, silencio, play/pausa, retroceder/adelantar,
  apagar, y teclado para escribir texto (`Lit_`).
- **Transmitir cualquier video** por el canal receptor
  «Web Video Caster - Receiver» (gratis en la tienda Roku, id 259656):
  pega un enlace de YouTube, TikTok, X, Instagram o Facebook, la app
  saca el video directo con yt-dlp (sin descargar nada) y lo manda al
  canal con `POST :8060/launch/259656` y `POST :8060/input?cmd=play&url=…`.
  El canal reporta su estado (reproduciendo, pausa, error) al teléfono.
  También hay un **navegador** con detección de video (DOM + red) para
  transmitir lo que se esté viendo en una página.
- **Mandar YouTube** por DIAL: pega el enlace, la app extrae el id del video
  y hace `POST /apps/YouTube` con `v=<id>`. También abre Netflix. Si DIAL
  falla, el respaldo ECP usa el canal 837 de YouTube (el 12 es Netflix).
- **Mandar videos y fotos del celular**: la app levanta un servidor HTTP local
  (propio, con `java.net.ServerSocket`, solo red local) que sirve el archivo
  elegido y lanza el Reproductor de Roku (canal 2213) con
  `POST /launch/2213?contentID=<url-local>&mediaType=movie|song|photo`.
  También acepta una URL directa de video (mp4).
- **Estado**: muestra qué app está abierta en la tele
  (`/query/device-info`, `/query/active-app`) y la lista de apps instaladas
  (`/query/apps`) para lanzarlas.

## Requisitos en la tele

En la Roku: **Ajustes → Sistema → Ajustes avanzados del sistema →
Control por apps móviles → Activado**. La tele y el celular deben estar en
el mismo Wi-Fi. Para transmitir videos, instala gratis en la tele el canal
**«Web Video Caster - Receiver»** (Streaming Channels → buscar por nombre).

## Lo que NO hace

Duplicar pantalla (Miracast) no se puede hacer desde una app de terceros:
eso solo lo hace el sistema del celular. Esta app manda videos y controla
la tele, que es lo que sí permiten los protocolos abiertos de Roku.

## Técnica

- Kotlin, minSdk 26, targetSdk 34.
- Dependencias: solo AndroidX + youtubedl-android 0.18.1 (yt-dlp).
  El servidor HTTP local es código propio. El protocolo del canal
  receptor se implementó desde su descripción pública (proyecto
  ClearCast), sin copiar su código.
- Firma: keystore propio, alias `roku`. El build de release se hace en
  GitHub Actions con los secretos del repo.
- Tema navideño: verde pino, rojo, nieve animada y guirnalda.

## Build

El APK release se construye solo en GitHub Actions (`.github/workflows/android.yml`)
al hacer push a `main`. No compilar en local.
