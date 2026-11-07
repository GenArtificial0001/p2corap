# 07 · Medios, Wi-Fi local y modo emergencia

> **Estado:** implementado y con tests JVM, **sin compilar ni probar en dispositivo** (ver `06`). Lo que depende
> de radio real (MTU, mDNS, hotspot, micrófono, transcodificación de video) hay que validarlo con teléfonos.

## Para qué existe esto

Radar debe servir cuando **no hay infraestructura**: apagón, sin internet, sin torres celulares, sin servidores.
Por eso el orden de prioridad de los transportes es:

| # | Transporte | Necesita | Alcance | Velocidad |
|---|---|---|---|---|
| 1 | **Bluetooth LE en malla** | Solo teléfonos con batería | ≈30–100 m por salto; hasta 5 saltos | ≈8–16 KB/s |
| 2 | **Wi-Fi local (mDNS + TCP)** | Una red local *cualquiera*, incluido el **hotspot de un teléfono** | La red (≈30–50 m de un teléfono) | ≈MB/s |
| 3 | Internet por relé *(opcional)* | Internet + un servidor | Mundial | Depende |

El #3 **no sirve en el escenario de desastre**; es una comodidad para el día a día. #1 y #2 no dependen de nada externo.

### Si se va la luz (guía de uso)

1. Todos con Radar abierto: se encuentran por Bluetooth en la **sala pública** sin intercambiar nada.
2. Para pasar fotos/audios/videos rápido: una persona activa el **hotspot** del teléfono (sin datos), el resto se
   conecta a esa Wi-Fi, y en *Sala → Wi-Fi local* activan el interruptor. Radar los une solo.
3. Para un grupo privado: *Sala nueva* y compartir el código/QR/enlace `radar://join?code=…`.

## Mensajes multimedia

| Tipo | Cómo viaja | Tamaño típico |
|---|---|---|
| Emoji | Texto UTF-8 | 4 B |
| Sticker | Solo su **id de 2 B** (catálogo integrado, 24 stickers) | 30 B cifrado |
| Foto | JPEG ≤ 480 px, calidad adaptativa | 30–64 KB (tope 96 KB) |
| Nota de voz | **IMA-ADPCM** 8 kHz mono, hasta 20 s | 4 KB/s (tope 128 KB) |
| Video | H.264 240p, **sin audio**, ≤ 5 s (Media3 Transformer) | 100–200 KB (tope 640 KB) |

* **Trozos cifrados**: cada medio va como `MEDIA_HEADER` + N × `MEDIA_CHUNK` (3 900 B de datos), cada uno con
  AES-256-GCM y su propio `messageId`. El receptor valida tamaño exacto por índice, descarta duplicados y
  transferencias estancadas (30 s) y limita memoria (3 transferencias, 1.2 MB).
* **Directo a los vecinos, sin reenvío** (`ttl = 1`): un video de cientos de KB no puede inundar la malla.
  Texto y stickers sí se reenvían (5 saltos).
* **Contrapresión**: el emisor solo encola más cuando el vínculo tiene poco pendiente; hay progreso y cancelar.
* **ACK** al terminar: el emisor ve «Entregado».
* **MTU**: el cliente pide MTU 247 antes de descubrir servicios ⇒ fragmentos de 241 B en lugar de 17 B. El
  receptor reensambla sin conocer el tamaño, así que cada vínculo usa el suyo.

### Tiempos esperados (estimación, sin medir)

| Archivo | MTU 247 (≈10 KB/s) | MTU 23 (≈1 KB/s) | Wi-Fi local |
|---|---|---|---|
| Sticker / emoji | instantáneo | instantáneo | instantáneo |
| Foto 50 KB | ≈5 s | ≈50 s | <1 s |
| Audio 10 s (40 KB) | ≈4 s | ≈40 s | <1 s |
| Video 5 s (150 KB) | ≈15 s | ≈2.5 min | ≈1 s |

### Seguridad y privacidad de los medios

* Fotos y videos **de otras personas se muestran tapados** hasta tocar («Toca para ver»): en una sala pública
  cualquiera puede mandar algo, y no se decodifica ni se reproduce nada que no se haya pedido. Las notas de voz
  solo suenan al tocar.
* Se rechazan imágenes de más de 4096 px y datos ADPCM con más de 30 s declarados (bombas de descompresión).
* Todo vive en RAM con tope de 6 MB; al superarlo se liberan los más antiguos.
* **Excepción a «Zero-Storage»:** el transcodificador de video escribe un archivo temporal en la caché de la
  app; se lee a RAM y se borra al terminar, fallar o cancelar. Audio y fotos no tocan el disco.

## Wi-Fi local (sin servidor)

`lan/LanLink.kt`: cada teléfono abre un puerto TCP libre y se anuncia por mDNS como
`_radar._tcp.` con nombre `radar-<hash de sala>-<etiqueta aleatoria>`. Quien tiene la etiqueta menor inicia la
conexión (evita pares duplicados). Tras conectar se intercambia un saludo `RDR1|sala|etiqueta`; si la sala no
coincide se cierra. Los paquetes son los mismos de BLE, con marco `[largo:4][bytes]` (tope 8 KB, validado
**antes** de reservar memoria porque cualquiera en la red puede abrir el socket).

* Es **opt-in** (interruptor en *Sala*): anunciarse en una Wi-Fi pública expone que usas Radar en esa red.
* Una red con **aislamiento de clientes** (wifi de invitados, algunas empresas) impide la conexión directa.
* El anuncio contiene un hash de la sala, no el código; el contenido va cifrado igual que por BLE.
* Un enlace `radar://join?code=…` (sin servidor) activa el Wi-Fi local al abrirse.

## Internet por relé (opcional)

`server/relay.py` (Python, sin dependencias) reenvía paquetes ya cifrados entre miembros de una sala. Ver
`server/README.md`. La app solo acepta `wss://`. Se activa en *Sala → Internet (opcional)* con la dirección del
relé o un enlace `https://<relé>/join#CODIGO`. Los paquetes del relé y de BLE forman **una sola malla** (la app
reenvía entre ambos).

## Modo emergencia (malla más resistente)

| Parámetro | Antes | Ahora | Motivo |
|---|---|---|---|
| TTL por defecto | 3 saltos | **5** | Llegar más lejos; la deduplicación acota el costo |
| Retención store-and-forward | 2 min | **15 min** | Quien llega a la zona recibe lo reciente |
| Mensajes retenidos | 64 | **128** | Más historia para quien llega |
| Memoria de «ya visto» | 5 min / 512 | **30 min / 1024** | Debe superar la retención o habría rebotes |

El sticker «🚨 Ayuda» y «📍 Estoy acá» sirven como mensajes de auxilio de un toque.

## Límites conocidos

* BLE es lento y de corto alcance; la malla depende de que haya gente entre medio o que se mueva.
* iOS no está soportado (solo Android).
* Video sin audio y de 5 s: es el máximo razonable para BLE.
* Cámara: se usa la miniatura que entrega el sistema (`TakePicturePreview`), de baja resolución; la galería
  da calidad completa. No hay grabación de video con cámara (solo desde la galería).
* El fragmento de 17 B (MTU 23) sigue siendo el piso: dispositivos sin Data Length Extension serán lentos.
* Pendiente: Wi-Fi Direct (conexión Wi-Fi sin router *ni* hotspot), compartir el APK por Bluetooth para que la
  app se propague sin internet, y el tablón/perfiles/historias (ver `08`).
