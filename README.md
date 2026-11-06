# Radar

[![Android CI](https://github.com/GenArtificial0001/p2corap/actions/workflows/android.yml/badge.svg)](https://github.com/GenArtificial0001/p2corap/actions/workflows/android.yml)

Mensajería **peer-to-peer efímera sobre Bluetooth Low Energy (BLE)**. Sin servidores, sin cuentas, sin persistencia: el chat vive y muere con la conexión.

Cada dispositivo con la app **extiende la red**: los mensajes se reenvían de teléfono en teléfono (mesh multi-salto), así que dos personas pueden escribirse sin estar dentro del alcance Bluetooth de la otra.

> **Estado:** Fase 2/3 — Android/Kotlin. Núcleo BLE, GATT dual, mensajería volátil, **malla multi-salto**, cifrado de grupo, ACK, QR y foreground service implementados. La compilación está **verde** en CI y cada push publica un APK debug descargable desde la pestaña *Actions*. La validación con teléfonos reales queda pendiente (checklist en [`docs/04-roadmap.md`](docs/04-roadmap.md)).

## Principios

| Principio | Implementación |
|---|---|
| **Offline-first** | No hay backend ni API remota. Todo ocurre en el aire. |
| **Zero-Storage** | Sin Room, SQLite ni SharedPreferences. El estado vive en `StateFlow` en RAM. |
| **Sesión = conexión** | Al caer el vínculo GATT, el historial se purga. |
| **Doble rol** | Cada dispositivo es Peripheral (GATT server) y Central (GATT client) a la vez. |
| **Malla** | Hasta 3 saltos por mensaje, con dedup y store-and-forward. |
| **Cifrado** | AES-256-GCM con clave derivada de un código de sala. Los relays no leen. |
| **Confirmación** | ACK de aplicación por mensaje (`✓ entregado`), propagado por la malla. |
| **Background** | Foreground service `connectedDevice`: el advertising sobrevive a Doze. |

## Estructura

```
docs/                       Especificación, perfil GATT, revisión técnica y roadmap
android/
  app/src/main/java/com/radar/
    ble/                    Advertising, scanning, GATT server y client
    mesh/                   Paquete de malla, dedup, TTL, store-and-forward
    crypto/                 Clave de sala, AES-256-GCM, código legible
    chat/                   Modelo de mensaje + repositorio volátil
    session/                Vínculos simultáneos, handshake y ruteo
    ui/                     UI Compose mínima
```

## Perfil GATT

| UUID | Nombre | Propiedades |
|---|---|---|
| `7D022782-8CD9-4FD0-8648-780D092EF19B` | Radar Service | — |
| `23ECFC0A-133C-4E13-9151-C8250E514414` | Profile Info | READ |
| `DBD2BE6F-0278-47CE-AA7E-137BF8F40F6F` | Connection Control | WRITE |
| `77188928-C80D-4CB5-8C3B-B73C0D1B4585` | Message Tx/Rx | WRITE, NOTIFY |

UUIDs aleatorios de 128 bits (v4), fuera del rango reservado del SIG (R-01 resuelto). Detalle completo en [`docs/02-gatt.md`](docs/02-gatt.md).

## Malla

```
[A] ──── [B] ──── [C] ──── [D]
```

- Cada nodo reenvía **una sola vez** cada mensaje (dedup por `messageId`).
- TTL de **3 saltos** ≈ 4 dispositivos de extremo a extremo.
- Store-and-forward: lo que no pudo salir queda hasta 2 minutos y se entrega al próximo vecino.
- Hasta 3 conexiones salientes simultáneas por nodo; el resto espera en cola.
- ACK de entrega: el receptor confirma el `messageId` y el emisor marca `✓ entregado`.

> 🔴 **Límite de seguridad:** la clave es de **grupo**. Cualquiera con el código de sala lee la conversación, y los relays ven el ciphertext (no el texto). Forward secrecy por par está en el roadmap. Ver [`docs/05-mesh.md`](docs/05-mesh.md).

## Cifrado

Cada mensaje viaja como `nonce || AES-256-GCM(texto)`. La clave se deriva de un **código de sala** que los participantes comparten fuera de banda:

```
Sala · 7K3M9-QX2AB-...

Todos los dispositivos deben usar el mismo código.
```

- Los nodos intermedios reenvían ciphertext que no pueden descifrar.
- Código inválido o mensaje alterado → se descarta (tag GCM).
- La clave vive solo en RAM; al cerrar la app se pierde.
- "Sala nueva" genera otro código y purga el chat.

Es una **clave de grupo**: quien tenga el código lee la sala. Forward secrecy por par (X25519) está en el roadmap.

### Sala, rotación y QR

- El código de sala se muestra en la UI y se puede **copiar**, **compartir** con el selector de Android o convertir en **QR**.
- El botón **Escanear** abre la cámara y lee el QR de otra persona (ZXing embedded).
- **Sala pública de fábrica**: todos los dispositivos empiezan con el mismo código (`RADAR000000000000000`), así que dos teléfonos cualquiera pueden chatear sin intercambiar códigos.
- **Sala nueva** rota la clave y purga el chat (genera un código privado; hay que compartirlo para que otros se unan).
- **Unirse** valida el código (20 caracteres Crockford) antes de cambiar de sala.
- La sesión vive en un `ViewModel`: rotar la pantalla ya no reinicia la malla ni pierde el chat.

### Ciclo de vida

- `RadarForegroundService` (tipo `connectedDevice`) mantiene el proceso vivo mientras Radar está activo.
- Al apagar Radar o caer el último vínculo, el historial se purga.
- Sin `allowBackup`, sin exportación de datos, sin logs de contenido: ver [`docs/PRIVACY.md`](docs/PRIVACY.md).

## Compilar

```bash
cd android
gradle wrapper --gradle-version 8.9   # solo la primera vez (genera el wrapper)
./gradlew assembleDebug               # requiere JDK 17 + Android SDK 35
```

El advertising y el scanning **no funcionan en emulador**: hace falta hardware real y dos dispositivos para una prueba de punta a punta.

## Documentación

- [`docs/01-arquitectura.md`](docs/01-arquitectura.md) — visión y decisiones
- [`docs/02-gatt.md`](docs/02-gatt.md) — perfil, framing y handshake
- [`docs/03-revision-tecnica.md`](docs/03-revision-tecnica.md) — **hallazgos y riesgos** (leer antes de codear)
- [`docs/04-roadmap.md`](docs/04-roadmap.md) — fases
- [`docs/05-mesh.md`](docs/05-mesh.md) — **malla multi-salto**: formato, TTL y límites
- [`docs/PRIVACY.md`](docs/PRIVACY.md) — qué datos se guardan (ninguno) y qué ve la red

## Aviso

La especificación original afirma que el borrado en RAM es "estricto". En la JVM/ART eso **no es garantizable** (ver revisión técnica, hallazgo R-05). Este repo implementa la mejor aproximación posible y lo documenta en lugar de prometerlo.
