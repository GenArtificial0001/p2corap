# Privacidad

Radar es mensajería efímera **sin servidores**. Esta página describe exactamente qué se guarda, qué se transmite y qué ve la red. Si algo no está acá, no existe.

## Qué NO se guarda

| Dato | Estado |
|---|---|
| Historial de mensajes | Solo en RAM (`VolatileChatRepository`). Se purga al caer el último vínculo, al apagar Radar o al rotar la sala. |
| Clave de sala | Solo en RAM (`MeshCrypto`). Se pierde al cerrar el proceso. |
| Identidad / alias | Solo en RAM. No hay cuenta, login ni identificador estable. |
| Contactos, peers, direcciones | Solo en RAM. |
| Logs de contenido | No se escriben. Los logs (`Log.d/w/e`) son de estado y errores, nunca texto de mensajes. |
| Backups | `allowBackup=false` y `dataExtractionRules` sin backup ni transferencia. |
| Analítica / crash reporting | No hay SDKs de terceros que reporten a un servidor. |

No hay `Room`, `SQLite`, `SharedPreferences`, `DataStore` ni `SavedStateHandle` con contenido de chat.

## Qué se transmite por el aire

1. **Advertising BLE**: el UUID del servicio Radar (128 bits, aleatorio). No incluye nombre del dispositivo ni texto.
2. **Perfil GATT (READ)**: alias elegido por el usuario (máx. 31 bytes) + ID de avatar. Es legible por cualquier dispositivo que se conecte.
3. **Connection Control**: opcodes de handshake (`CONNECT_REQUEST`, `ACCEPT`, `REJECT`, `BYE`).
4. **Mensajes**: `nonce || AES-256-GCM(texto)`, empaquetados en el header de malla (messageId, originId efímero, TTL, hopCount, timestamp).
5. **ACK**: `messageId` de 8 bytes, cifrado igual que un mensaje.

## Qué ve cada actor

| Actor | Puede ver |
|---|---|
| **Peer directo** | Tu alias, RSSI aproximado y el ciphertext de los mensajes. |
| **Nodo relay** | `messageId`, `originId` efímero, TTL, timestamp y ciphertext. **No** puede descifrar sin el código de sala. |
| **Observador BLE pasivo** | El UUID de servicio y el tráfico GATT si no hay cifrado de enlace (LESC). El contenido sigue cifrado a nivel de aplicación. |
| **Servidor / nube** | Nada. No hay backend. |

## Límites honestos

- **Clave de grupo**: quien tenga el código de sala lee todos los mensajes. No hay forward secrecy por par todavía (X25519 está en el roadmap).
- **Metadatos**: `originId` es aleatorio por proceso, pero un relay puede correlacionar tráfico durante la sesión. TTL y timestamps revelan la topología aproximada.
- **RAM no es borrado seguro**: en ART no se puede garantizar la sobrescritura de `String` ni forzar el GC (R-05). Se sueltan las referencias y el sistema hace el resto.
- **Alias no autenticado**: sin firma, cualquiera puede usar el mismo alias (R-09). No hay verificación de identidad.
- **Screenshots**: `FLAG_SECURE` bloquea capturas dentro de la app y `setRecentsScreenshotEnabled(false)` (API 33+) la oculta del carrusel de recientes. Un dispositivo rooteado o con un grabador externo sigue pudiendo capturar la pantalla física.
- **Sistema operativo**: el SO puede volcar memoria del proceso en un tombstone o swap. Eso queda fuera del control de la app.

## Permisos

| Permiso | Para qué |
|---|---|
| `BLUETOOTH_SCAN` / `ADVERTISE` / `CONNECT` (31+) | Operar BLE. `SCAN` declara `neverForLocation`. |
| `ACCESS_FINE_LOCATION` (≤ 30) | Requisito del sistema para escanear BLE en Android 11 e inferiores. |
| `POST_NOTIFICATIONS` (33+) | Mostrar la notificación del foreground service. |
| `CAMERA` (opcional) | Escanear el QR de una sala. Se pide solo al abrir el escáner; si se deniega, el resto funciona. |
| `FOREGROUND_SERVICE` / `CONNECTED_DEVICE` | Mantener el advertising vivo en background. |

No se usa ubicación para nada: el permiso existe únicamente porque el stack BLE de Android ≤ 11 lo exige. No se deriva ni se guarda posición.


## Actualización: medios, Wi-Fi local e internet opcional

* **Permisos nuevos:** `RECORD_AUDIO` (solo al tocar el micrófono), `INTERNET` y `ACCESS_NETWORK_STATE` (solo si activas el
  relé), `ACCESS_WIFI_STATE` y `CHANGE_WIFI_MULTICAST_STATE` (Wi-Fi local). La cámara se usa solo al elegir «Cámara» o escanear un QR.
* **Preferencias guardadas** (`SharedPreferences`, excluidas de backups): la dirección del relé y si activaste el Wi-Fi local.
  No hay contenido de mensajes ni códigos de sala.
* **Archivo temporal de video:** al comprimir un video, el transcodificador escribe un `.mp4` en la caché de la app; se lee a RAM y se
  borra al terminar, fallar o cancelar.
* **Wi-Fi local:** anuncia en tu red un servicio `_radar._tcp` cuyo nombre incluye un hash de la sala (no el código). Es opcional y
  está apagado por defecto. Quien esté en tu red puede ver que ese servicio existe.
* **Relé de internet (opcional):** ve tu IP, el hash de la sala y el tamaño/hora de los paquetes; **no** ve el código ni el contenido
  (el código viaja en el fragmento `#` del enlace, que el navegador no envía).
* **Medios recibidos:** viven en RAM con tope de 6 MB, se muestran tapados hasta tocarlos y se descartan al cerrarse el último vínculo.
