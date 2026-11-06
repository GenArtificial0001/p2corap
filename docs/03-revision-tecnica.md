# 03 — Revisión técnica de la especificación

Revisión del documento *"Arquitectura Base y Especificación Técnica: Radar App"*. Los hallazgos están ordenados por severidad. **R-01 a R-04 deberían resolverse antes de escribir la capa de red definitiva.**

| ID | Hallazgo | Severidad | Estado |
|---|---|---|---|
| R-01 | UUIDs en rango reservado del Bluetooth SIG | 🔴 Alta | ✅ Resuelto |
| R-02 | "Cifrado temporal" sin definir | 🔴 Alta | ✅ Clave de sala (E2E de grupo) |
| R-03 | Sin ACK ni retransmisión: pérdida silenciosa | 🔴 Alta | ✅ ACK de aplicación |
| R-04 | iOS no sostiene advertising en background | 🔴 Alta | Pendiente |
| R-05 | "Zero-Storage estricto" no es garantizable en ART | 🟠 Media | Documentado |
| R-06 | `System.gc()` es antipatrón | 🟠 Media | Corregido |
| R-07 | Falta el GATT **server** en el código de ejemplo | 🟠 Media | Implementado |
| R-08 | Sin fragmentación ni negociación de MTU | 🟠 Media | Implementado |
| R-09 | Identidad sin autenticación: suplantación trivial | 🟠 Media | Pendiente |
| R-10 | Permisos y background restrictions no especificados | 🟠 Media | Implementado |
| R-11 | Scan agresivo: batería y `ScanFilter` sin dedup | 🟡 Baja | ✅ Implementado |
| R-12 | RSSI ≠ distancia | 🟡 Baja | ✅ Implementado |
| R-13 | Una sola sesión; sin multiplexación | 🟡 Baja | Por diseño |
| R-14 | Advertising sin nombre: correcto, pero cerca del límite | 🟡 Baja | OK |
| R-15 | Errores del snippet original (Kotlin) | 🟡 Baja | Corregido |
| R-16 | Referencias bibliográficas a verificar | ⚪ Info | Pendiente |

---

## R-01 — UUIDs en rango reservado 🔴

`0000FA01-0000-1000-8000-00805F9B34FB` usa la base UUID del SIG con un alias de 16 bits (`0xFA01`). Los alias de 16 bits **no son asignables libremente**: el rango `0xFA00–0xFAFF` está reservado y usarlo puede colisionar con servicios de otros fabricantes.

**Resuelto:** los cuatro UUIDs del perfil se generaron con `uuidgen` (v4, 128 bits aleatorios) y se centralizaron en `RadarUuids`:

```
Servicio     7D022782-8CD9-4FD0-8648-780D092EF19B
Perfil       23ECFC0A-133C-4E13-9151-C8250E514414
Control      DBD2BE6F-0278-47CE-AA7E-137BF8F40F6F
Mensajes     77188928-C80D-4CB5-8C3B-B73C0D1B4585
```

## R-02 — El cifrado no está especificado 🔴

La spec dice "paquetes de texto cifrados temporalmente", pero no define:

- **De dónde sale la clave.** No hay servidor ni PKI. Si la clave es fija y compartida, cualquiera que lea el repo descifra todo.
- **Qué algoritmo.** BLE ya cifra el enlace (LESC), pero solo si hay pairing; sin pairing, el tráfico va en claro.
- **Contra qué amenaza.** Suplantación, replay, lectura pasiva.

**Resuelto (v1):** clave de sala compartida fuera de banda. `AES-256-GCM` con nonce aleatorio y clave derivada del código vía `PBKDF2-HMAC-SHA256` (120k iteraciones). Los relays reenvían ciphertext sin poder leerlo. Ver `05-mesh.md`.

**Pendiente (v2):** X25519 efímero por par para forward secrecy + identidad firmada contra suplantación.

## R-03 — Sin ACK, sin retransmisión 🔴

`WRITE` sin respuesta no confirma entrega; `WRITE` con respuesta confirma a nivel ATT pero no a nivel aplicación. Un mensaje de 200 bytes son ~12 fragmentos: la probabilidad de perder uno crece rápido con la distancia.

**Mitigación actual:** ACK a nivel aplicación. Al entregar un `TYPE_TEXT`, el receptor responde un `TYPE_ACK` con los 8 bytes del `messageId`; el ACK viaja por la malla igual que un mensaje (TTL, dedup) y el emisor marca el mensaje como `✓ entregado`. El ACK se genera en `RadarSessionManager.onText()` y se procesa en `onAck()`. Timeout de reensamblado y descarte limpio siguen activos.
**Pendiente:** retransmisión con backoff si el ACK no llega en N segundos, o pasar a `L2CAP CoC` (API 29+) que da un socket orientado a stream y elimina el framing manual.

## R-04 — iOS en background 🔴

`CBPeripheralManager` **deja de anunciar** cuando la app pasa a background. `CBCentralManager` puede escanear en background solo con service UUIDs explícitos y de forma intermitente. La promesa de "100% autónoma y offline" se sostiene en Android con foreground service; en iOS es **solo en foreground** (o con `bluetooth-central`/`bluetooth-peripheral` y aún así limitado).

**Acción:** decidir si iOS es target real. Si lo es, definir el modo de uso (app abierta) y comunicarlo, en vez de prometer paridad.

## R-05 — Zero-Storage no es absoluto 🟠

En ART, `String` es inmutable y el GC no es determinista. Sobrescribir "la RAM" antes de soltar la referencia **no es posible** en Kotlin: lo único que se puede hacer es dejar de referenciar y esperar. A eso se suma que el SO puede escribir el proceso a swap o volcar un tombstone.

**Lo que sí se hizo:**

- `allowBackup=false` + `dataExtractionRules` (sin backup ni transferencia).
- Sin `Room`, sin `SharedPreferences`, sin `SavedStateHandle`.
- Purga explícita en `onConnectionStateChange` y en `onStop`.
- Sin logs de contenido (solo metadatos).

**Lo que falta:** nada crítico. `FLAG_SECURE` y `setRecentsScreenshotEnabled(false)` están activos en `MainActivity`, así que el chat no aparece en capturas ni en el carrusel de recientes (API 33+).

## R-06 — `System.gc()` 🟠

Forzar GC en Android provoca jank, no garantiza nada y está desaconsejado por Google. Se eliminó: la purga es por pérdida de referencias. Si se quiere defensa en profundidad, lo correcto es guardar el historial en `CharArray`/`ByteArray` y sobreescribir explícitamente, no invocar el GC.

## R-07 / R-08 — Faltaba la mitad del sistema 🟠

El snippet original solo cubría advertising + scanning. Faltaban:

- `BluetoothGattServer` (`openGattServer`, `addService`, `respondToRequest`, CCCD).
- `BluetoothGatt` client (descubrir servicios, leer perfil, habilitar notificaciones).
- Fragmentación y reensamblado.

Todo eso está implementado en `ble/`.

## R-09 — Identidad 🟠

`Profile Info` es un `READ` abierto: cualquiera puede leer el alias, y cualquiera puede **escribir** en `Connection Control` haciéndose pasar por otro. Sin firma, el alias no prueba nada.

**Acción:** derivar un `fingerprint` de la clave pública efímera y mostrarlo en la UI (verificación visual estilo Signal). Opcional: TOFU.

## R-10 — Permisos y background 🟠

| API | Requisito |
|---|---|
| ≤ 30 | `ACCESS_FINE_LOCATION` para escanear |
| 31+ | `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` |
| 31+ | `android:usesPermissionFlags="neverForLocation"` si no se deriva ubicación |
| 34+ | Foreground service tipo `connectedDevice` + permiso `FOREGROUND_SERVICE_CONNECTED_DEVICE` |

Doze y los ahorros de energía de cada OEM matan el advertising si no hay foreground service. **Implementado:** `RadarForegroundService` con `foregroundServiceType="connectedDevice"`, `ServiceCompat.startForeground(...)` y notificación de baja importancia. Se inicia en `RadarSessionManager.start()` y se detiene en `stop()`.

## R-11 — Batería 🟡

`SCAN_MODE_LOW_LATENCY` + `ADVERTISE_MODE_LOW_LATENCY` de forma continua vacía la batería en horas. **Implementado:** `SCAN_MODE_BALANCED` y `ADVERTISE_MODE_BALANCED` con `TX_POWER_HIGH`, y dedup por `address` con `lastSeen` (`prune(ttlMs)`). Pendiente: duty cycle explícito (10 s scan / 20 s pausa) y bajar a `LOW_POWER` sin peers.

También faltaba **deduplicar** resultados: el stack entrega el mismo dispositivo varias veces por segundo. `RadarBleManager` mantiene un mapa por `address` con `lastSeen`.

## R-12 — RSSI 🟡

RSSI varía ±10 dB con el cuerpo, la mano y la orientación. Sirve para ordenar por cercanía aproximada, **no** para medir metros. Cualquier UI que muestre "3,2 m" está mintiendo.

**Implementado:** la UI muestra `cerca / medio / lejos` derivado de bandas de RSSI (`RadarPeer.proximity`) y el valor en dBm, nunca una distancia.

## R-15 — Bugs del código de ejemplo 🟡

| Problema | Consecuencia |
|---|---|
| No chequea `bluetoothAdapter?.isEnabled` | Crash o fallo silencioso con BT apagado |
| No valida `isMultipleAdvertisementSupported` | `startAdvertising` falla en dispositivos viejos |
| `onDiscoveredCallback` asignado después de `startScan` | Race benigna, pero frágil |
| Sin `close()` del `BluetoothGatt` en todos los caminos | Fuga de recursos y conexiones zombie |
| `setTimeout(0)` | Anuncia para siempre |
| Sin límite de tamaño de advertising | `onStartFailure(DATA_TOO_LARGE)` si se agregan campos |

---

## Resumen ejecutivo

La spec acierta en lo importante: **nativo, GATT dual, estado volátil, filtrado por service UUID**. Falla en los detalles que definen si el producto funciona: **UUIDs inválidos, cifrado inexistente, transporte sin confiabilidad y una promesa de background que iOS no puede cumplir**.

Orden sugerido: R-01 → R-02 → R-03 → R-04, y recién después pulir UI.
