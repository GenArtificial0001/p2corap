# Radar

[![Android CI](https://github.com/GenArtificial0001/p2corap/actions/workflows/android.yml/badge.svg)](https://github.com/GenArtificial0001/p2corap/actions/workflows/android.yml)

Mensajería **peer-to-peer efímera sobre Bluetooth Low Energy (BLE)**. Sin servidores, sin cuentas, sin persistencia: el chat vive y muere con la conexión.

Cada dispositivo con la app **extiende la red**: los mensajes se reenvían de teléfono en teléfono (mesh multi-salto), así que dos personas pueden escribirse sin estar dentro del alcance Bluetooth de la otra.

> **Estado:** Fase 2 — Android/Kotlin. Núcleo BLE, perfil GATT, mensajería volátil y **malla multi-salto** implementados. La compilación está **verde** en CI y cada push publica un APK debug descargable desde la pestaña *Actions*.

## Principios

| Principio | Implementación |
|---|---|
| **Offline-first** | No hay backend ni API remota. Todo ocurre en el aire. |
| **Zero-Storage** | Sin Room, SQLite ni SharedPreferences. El estado vive en `StateFlow` en RAM. |
| **Sesión = conexión** | Al caer el vínculo GATT, el historial se purga. |
| **Doble rol** | Cada dispositivo es Peripheral (GATT server) y Central (GATT client) a la vez. |
| **Malla** | Hasta 3 saltos por mensaje, con dedup y store-and-forward. |
| **Cifrado** | AES-256-GCM con clave derivada de un código de sala. Los relays no leen. |

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
| `0000FA01-…` | Radar Service | — |
| `0000FA02-…` | Profile Info | READ |
| `0000FA03-…` | Connection Control | WRITE |
| `0000FA04-…` | Message Tx/Rx | WRITE, NOTIFY |

Detalle completo en [`docs/02-gatt.md`](docs/02-gatt.md).

## Malla

```
[A] ──── [B] ──── [C] ──── [D]
```

- Cada nodo reenvía **una sola vez** cada mensaje (dedup por `messageId`).
- TTL de **3 saltos** ≈ 4 dispositivos de extremo a extremo.
- Store-and-forward: lo que no pudo salir queda hasta 2 minutos y se entrega al próximo vecino.
- Hasta 3 conexiones salientes simultáneas por nodo.

> 🔴 **Límite de seguridad:** sin cifrado extremo a extremo, los nodos intermedios leen el texto. Es el próximo bloqueante. Ver [`docs/05-mesh.md`](docs/05-mesh.md).

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

## Aviso

La especificación original afirma que el borrado en RAM es "estricto". En la JVM/ART eso **no es garantizable** (ver revisión técnica, hallazgo R-05). Este repo implementa la mejor aproximación posible y lo documenta en lugar de prometerlo.
