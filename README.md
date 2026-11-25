# Anticristo Roku

App Android propia (cerrada) para controlar una Roku TV por la red Wi-Fi local
y mandarle videos, sin cuentas, sin anuncios y sin rastreadores.

## Qué hace

- **Descubre** Rokus en la red local por SSDP (`M-SEARCH`, `ST: roku:ecp`).
- **Control remoto completo** por ECP (`POST http://<ip>:8060/keypress/<tecla>`):
  D-pad, OK, Atrás, Inicio, volumen, silencio, play/pausa, retroceder/adelantar,
  apagar, y teclado para escribir texto (`Lit_`).
- **Mandar YouTube** por DIAL: pega el enlace, la app extrae el id del video
  y hace `POST /apps/YouTube` con `v=<id>`. También abre Netflix.
- **Mandar videos y fotos del celular**: la app levanta un servidor HTTP local
  (NanoHTTPD, solo red local) que sirve el archivo elegido y lanza el
  Reproductor de Roku (canal 2213) con
  `POST /launch/2213?contentID=<url-local>&mediaType=movie|song|photo`.
  También acepta una URL directa de video (mp4).
- **Estado**: muestra qué app está abierta en la tele
  (`/query/device-info`, `/query/active-app`) y la lista de apps instaladas
  (`/query/apps`) para lanzarlas.

## Requisitos en la tele

En la Roku: **Ajustes → Sistema → Ajustes avanzados del sistema →
Control por apps móviles → Activado**. La tele y el celular deben estar en
el mismo Wi-Fi.

## Lo que NO hace

Duplicar pantalla (Miracast) no se puede hacer desde una app de terceros:
eso solo lo hace el sistema del celular. Esta app manda videos y controla
la tele, que es lo que sí permiten los protocolos abiertos de Roku.

## Técnica

- Kotlin, minSdk 26, targetSdk 34.
- Dependencias: AndroidX + NanoHTTPD 2.3.2 (Apache 2.0). Sin código GPL.
- Firma: keystore propio, alias `roku`. El build de release se hace en
  GitHub Actions con los secretos del repo.
- Tema navideño: verde pino, rojo, nieve animada y guirnalda.

## Build

El APK release se construye solo en GitHub Actions (`.github/workflows/android.yml`)
al hacer push a `main`. No compilar en local.
