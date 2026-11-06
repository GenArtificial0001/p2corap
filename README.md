# Radar

Mensajería **peer-to-peer efímera sobre Bluetooth Low Energy (BLE)**. Sin servidores, sin cuentas, sin persistencia: el chat vive y muere con la conexión.

> **Estado:** Fase 1 — esqueleto funcional (Android/Kotlin). El núcleo BLE, el perfil GATT y la capa de mensajería volátil están implementados. La UI es mínima y el proyecto **no fue compilado todavía** en este entorno (no hay JDK/Android SDK disponibles).

## Principios

| Principio | Implementación |
|---|---|
| **Offline-first** | No hay backend ni API remota. Todo ocurre en el aire. |
| **Zero-Storage** | Sin Room, SQLite ni SharedPreferences. El estado vive en `StateFlow` en RAM. |
| **Sesión = conexión** | Al caer el vínculo GATT, el historial se purga. |
| **Doble rol** | Cada dispositivo es Peripheral (GATT server) y Central (GATT client) a la vez. |

## Estructura

```
docs/                       Especificación, perfil GATT, revisión técnica y roadmap
android/
  app/src/main/java/com/radar/
    ble/                    Advertising, scanning, GATT server y client
    chat/                   Modelo de mensaje + repositorio volátil
    session/                Máquina de estados del handshake
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

## Aviso

La especificación original afirma que el borrado en RAM es "estricto". En la JVM/ART eso **no es garantizable** (ver revisión técnica, hallazgo R-05). Este repo implementa la mejor aproximación posible y lo documenta en lugar de prometerlo.
