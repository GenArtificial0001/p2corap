# 03 — Revisión técnica de la especificación

Revisión del documento *"Arquitectura Base y Especificación Técnica: Radar App"*. Los hallazgos están ordenados por severidad. **R-01 a R-04 deberían resolverse antes de escribir la capa de red definitiva.**

| ID | Hallazgo | Severidad | Estado |
|---|---|---|---|
| R-01 | UUIDs en rango reservado del Bluetooth SIG | 🔴 Alta | Pendiente |
| R-02 | "Cifrado temporal" sin definir | 🔴 Alta | Pendiente |
| R-03 | Sin ACK ni retransmisión: pérdida silenciosa | 🔴 Alta | Mitigado parcial |
| R-04 | iOS no sostiene advertising en background | 🔴 Alta | Pendiente |
| R-05 | "Zero-Storage estricto" no es garantizable en ART | 🟠 Media | Documentado |
| R-06 | `System.gc()` es antipatrón | 🟠 Media | Corregido |
| R-07 | Falta el GATT **server** en el código de ejemplo | 🟠 Media | Implementado |
| R-08 | Sin fragmentación ni negociación de MTU | 🟠 Media | Implementado |
| R-09 | Identidad sin autenticación: suplantación trivial | 🟠 Media | Pendiente |
| R-10 | Permisos y background restrictions no especificados | 🟠 Media | Implementado |
| R-11 | Scan agresivo: batería y `ScanFilter` sin dedup | 🟡 Baja | Implementado |
| R-12 | RSSI ≠ distancia | 🟡 Baja | Documentado |
| R-13 | Una sola sesión; sin multiplexación | 🟡 Baja | Por diseño |
| R-14 | Advertising sin nombre: correcto, pero cerca del límite | 🟡 Baja | OK |
| R-15 | Errores del snippet original (Kotlin) | 🟡 Baja | Corregido |
| R-16 | Referencias bibliográficas a verificar | ⚪ Info | Pendiente |

---

## R-01 — UUIDs en rango reservado 🔴

`0000FA01-0000-1000-8000-00805F9B34FB` usa la base UUID del SIG con un alias de 16 bits (`0xFA01`). Los alias de 16 bits **no son asignables libremente**: el rango `0xFA00–0xFAFF` está reservado y usarlo puede colisionar con servicios de otros fabricantes.

**Acción:** generar UUIDs aleatorios de 128 bits (`uuidgen`) y centralizarlos en `RadarUuids`. Es un cambio de una línea por UUID.

## R-02 — El cifrado no está especificado 🔴

La spec dice "paquetes de texto cifrados temporalmente", pero no define:

- **De dónde sale la clave.** No hay servidor ni PKI. Si la clave es fija y compartida, cualquiera que lea el repo descifra todo.
- **Qué algoritmo.** BLE ya cifra el enlace (LESC), pero solo si hay pairing; sin pairing, el tráfico va en claro.
- **Contra qué amenaza.** Suplantación, replay, lectura pasiva.

**Propuesta:** X25519 efímero por sesión (intercambio en `Connection Control`), HKDF-SHA256 y AES-256-GCM por mensaje con nonce = contador. Sin pairing del sistema: la clave muere con la sesión. Esto además da **autenticidad** (R-09) si se firma el handshake con la identidad de largo plazo.

## R-03 — Sin ACK, sin retransmisión 🔴

`WRITE` sin respuesta no confirma entrega; `WRITE` con respuesta confirma a nivel ATT pero no a nivel aplicación. Un mensaje de 200 bytes son ~12 fragmentos: la probabilidad de perder uno crece rápido con la distancia.

**Mitigación actual:** timeout de reensamblado y descarte limpio (no se muestra un mensaje corrupto).
**Pendiente:** ACK acumulativo + retransmisión con backoff, o pasar a `L2CAP CoC` (API 29+) que da un socket orientado a stream y elimina el framing manual.

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

**Lo que falta:** `FLAG_SECURE` para bloquear screenshots, y evaluar `setRecentsScreenshotEnabled(false)`.

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

Doze y los ahorros de energía de cada OEM matan el advertising si no hay foreground service. El manifiesto ya lo declara; falta el servicio.

## R-11 — Batería 🟡

`SCAN_MODE_LOW_LATENCY` + `ADVERTISE_MODE_LOW_LATENCY` de forma continua vacía la batería en horas. Para producción: duty cycle (ej. 10 s scan / 20 s pausa), `ADVERTISE_MODE_BALANCED`, y bajar a `LOW_POWER` cuando no hay peers.

También faltaba **deduplicar** resultados: el stack entrega el mismo dispositivo varias veces por segundo. `RadarBleManager` mantiene un mapa por `address` con `lastSeen`.

## R-12 — RSSI 🟡

RSSI varía ±10 dB con el cuerpo, la mano y la orientación. Sirve para ordenar por cercanía aproximada, **no** para medir metros. Cualquier UI que muestre "3,2 m" está mintiendo. Mostrar barras o "cerca/lejos".

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
