# 06 · Interfaz y correcciones de robustez

Este documento recoge la revisión de código posterior a la Fase 3 y el rediseño de la interfaz.

> **Estado de verificación:** los cambios se escribieron sin JDK ni Android SDK en el equipo
> de desarrollo, así que **todavía no se compilaron ni se ejecutaron los tests**. La validación
> es el CI (`.github/workflows/android.yml`) y, después, dos teléfonos reales.

## Correcciones

| # | Hallazgo | Efecto observable | Corrección |
|---|---|---|---|
| C-01 | Los callbacks GATT (cliente y servidor) llegan en hilos del binder y tocaban `mutableMapOf`/`ArrayDeque` de `RadarSessionManager`, `MeshRouter` y de las colas de los vínculos sin sincronizar. | `ConcurrentModificationException` o estado corrupto bajo tráfico; fallos intermitentes difíciles de reproducir. | Todos los callbacks se reenvían al hilo principal (`Handler`) antes de tocar estado. Un único hilo ⇒ sin locks. |
| C-02 | El servidor disparaba una notificación por fragmento sin esperar `onNotificationSent`. El stack admite una en vuelo por dispositivo. | Mensajes largos (decenas de fragmentos) llegaban incompletos y se descartaban en el reensamblado: el emisor veía «enviado» y nadie recibía. | `RadarGattServer` mantiene una cola por dispositivo; el siguiente fragmento sale en `onNotificationSent`, con timeout y reintentos acotados. |
| C-03 | El límite de 400 se aplicaba a *caracteres*. Con emojis/acentos, el payload cifrado superaba `MAX_PAYLOAD` (512 B). | El receptor descartaba el paquete en `MeshPacket.decode`; sin error visible. | `TextLimit` recorta por **bytes UTF-8** (480 B + 28 B de nonce/tag ≤ 512). La UI muestra un contador. |
| C-04 | `joinRoom`/`newRoom` llamaban `purgeSession()`, que pone `connected = false`. | Al cambiar de sala con vínculos activos, la UI volvía a la lista de pares como si nadie estuviera conectado. | Nuevo `clearMessages()`: borra mensajes y conserva el estado de conexión. |
| C-05 | `RadarBleManager.close()` desregistraba el receiver de estado de Bluetooth y nunca se volvía a registrar. | Tras «Detener → Iniciar», la app ya no se enteraba de que Bluetooth se apagaba/encendía. | El receiver se registra en cada arranque (`registerReceiverIfNeeded`) y se libera en `close()`. |
| C-06 | Al apagar Bluetooth los GATT mueren, pero la sesión conservaba vínculos fantasma y no reabría el servidor al volver. | Chat «conectado» sin transporte; no se aceptaban conexiones entrantes tras encender Bluetooth. | `bluetoothListener`: al apagarse se sueltan vínculos y servidor; al encenderse se reabre el servidor. |
| C-07 | Tras un reintento de conexión no se rearmaba el timeout. | Botón «Conectando…» eterno y *todos* los demás «Conectar» deshabilitados. | `retryOrGiveUp` rearma `connectTimeout`. |
| C-08 | `onReady` se disparaba antes de terminar la lectura del perfil; el `CONNECT_REQUEST` se escribía con la lectura aún en vuelo. | Escritura rechazada por el stack y reintentos a ciegas (el handshake funcionaba «por suerte»). | `onReady` se dispara al terminar la lectura del perfil (o si falla), una sola vez. |
| C-09 | `targetSdk 35` fuerza el modo borde a borde y la UI no manejaba insets. | En Android 15 el encabezado quedaba bajo la barra de estado y el compositor bajo la de navegación/teclado. | `enableEdgeToEdge()` + insets explícitos (ver abajo). |
| C-10 | El borrador vivía en `remember`. | Rotar el teléfono borraba lo escrito. | El borrador vive en el `ViewModel` (RAM). **No** se usa `rememberSaveable`: el estado guardado puede sobrevivir al proceso y Radar no persiste texto. |
| C-11 | `onCharacteristicReadRequest` ignoraba el `offset`. | Lecturas largas devolvían siempre el perfil completo. | Se responde con el tramo desde `offset`. |

### Mejoras de seguridad/privacidad en la UI

- El portapapeles marca el código de sala como **sensible** (Android 13+ oculta la vista previa).
- Los rótulos de sala nunca muestran caracteres del código privado: solo «Sala pública» / «Sala privada».
- La sala pública de fábrica se señala con aviso ámbar: cualquiera con la app puede leerla.

## Interfaz

**Dirección visual:** una pantalla de señal sobre fondo profundo. Cian = «tú / señal viva», violeta = acento,
menta = OK, ámbar = atención, coral = error. Tema oscuro fijo (coherente con la identidad); todos los pares
texto/fondo cumplen WCAG AA.

### Responsivo y adaptable

| Ancho | Layout |
|---|---|
| `< 720dp` (teléfono) | Una columna. Barra inferior **Cerca / Chat** con insignia de mensajes sin leer. Salta solo al chat al lograr el primer vínculo y vuelve al radar si se caen todos. |
| `≥ 720dp` (tablet, plegable abierto, teléfono apaisado) | **Dos paneles**: radar + pares a la izquierda (340–440dp), chat a la derecha. Sin barra inferior. |

Además: el contenido del chat se limita a 760dp, las tarjetas a 520dp y la hoja de sala pasa a QR + código
en paralelo a partir de 600dp. Todo desplaza (`LazyColumn` / `verticalScroll`) para tolerar pantallas bajas y
letra grande. Los objetivos táctiles miden ≥ 48dp.

### UX aplicada

- **Radar vivo**: anillos de distancia, barrido y un punto por dispositivo. Los puntos se encienden cuando el
  barrido los toca y se atenúan después. La distancia sale del RSSI; el ángulo es solo identidad visual
  (BLE no da dirección). Los pares vinculados llevan un aro menta.
- **Estado siempre visible**: píldora de estado en la barra superior, anunciada por TalkBack (*live region*).
- **Feedback inmediato**: «Uniendo» con indicador mientras se conecta, «Entregado/Enviado» en cada mensaje
  propio, vibración leve al enviar.
- **Prevención de errores**: crear sala nueva exige confirmación explícita (borra el chat); el contador de
  bytes aparece al acercarse al límite; el compositor se deshabilita sin vínculos y lo explica.
- **Estados vacíos con salida**: cada pantalla vacía dice qué pasa y ofrece la siguiente acción.
- **Sala por QR sin fricción**: al leer un QR válido, se une directamente.
- **Accesibilidad**: descripciones de contenido en radar y burbujas, respeta «Quitar animaciones»
  (el radar se dibuja quieto), contraste AA, texto escalable (`sp`).
- **Teclado**: el compositor sube con el teclado y la barra inferior se oculta mientras está abierto.
- **Icono propio**: icono adaptable (con versión monocroma para Android 13+) y ícono de notificación vectorial.

### Estructura (`ui/`)

```
RadarTheme.kt     Colores, tipografía, formas, espaciado, breakpoint
RadarScope.kt     Radar dibujado en Canvas + soporte de «reducir movimiento»
Components.kt     StatusPill, StatTile, SignalBars, avatar, íconos propios
PeersPane.kt      Pestaña «Cerca»: radar + estadísticas + lista de pares
ChatPane.kt       Pestaña «Chat»: encabezado, burbujas, compositor
RoomSheet.kt      Hoja de sala: QR, código, copiar/compartir/escanear, unirse
Chrome.kt         Barra superior, banner de error, barra inferior, permisos, pausa
MainActivity.kt   Raíz adaptable: insets, navegación, estado de la sesión
```

## Pendiente

- Probar en hardware real (checklist en `04-roadmap.md`).
- Ajustar el tamaño de fragmento al MTU negociado (hoy 17 B fijos ⇒ ~32 escrituras por mensaje máximo).
- Derivación de clave (PBKDF2, 120 000 iteraciones) corre en el hilo principal al cambiar de sala; moverla
  a un hilo de fondo si se nota en equipos lentos.
- Tests de UI (Compose) y de instrumentación; hoy solo hay tests JVM del núcleo.
