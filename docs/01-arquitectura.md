# 01 — Arquitectura

## Visión

Radar permite que dos dispositivos cercanos se descubran y conversen sin infraestructura. No hay login, no hay nube, no hay historial. La propuesta de valor es la **negación de la persistencia**: lo que se dice, se borra.

## Modelo de roles

BLE no es simétrico. Cada dispositivo cumple ambos roles a la vez:

```
┌──────────────┐                         ┌──────────────┐
│  Dispositivo │ ── ADV (Radar UUID) ──► │  Dispositivo │
│      A       │ ◄──── SCAN ───────────  │      B       │
│              │                         │              │
│  GATT Server │ ◄─── WRITE/NOTIFY ────► │ GATT Client  │
│  GATT Client │ ◄─── WRITE/NOTIFY ────► │ GATT Server  │
└──────────────┘                         └──────────────┘
```

- **Advertiser** publica el `Service UUID` en el paquete de advertising (sin nombre de dispositivo).
- **Scanner** filtra por ese UUID y obtiene `address` + `RSSI`.
- El descubrimiento **no** transporta identidad: el alias se lee recién al conectar, por el characteristic `Profile Info`.
- Una vez conectados, ambos lados abren notificaciones y pueden escribir en `Message Tx/Rx` → canal full-duplex.

## Capas

```
┌─────────────────────────────────────────┐
│ UI (Compose)                            │
├─────────────────────────────────────────┤
│ SessionManager  (vínculos + handshake)  │
├─────────────────────────────────────────┤
│ MeshRouter  (dedup · TTL · reenvío)     │
├──────────────────────┬──────────────────┤
│ VolatileChatRepository│ RadarGattClient │
│ (RAM, StateFlow)      │ RadarGattServer │
├──────────────────────┴──────────────────┤
│ RadarBleManager (advertise + scan)      │
├─────────────────────────────────────────┤
│ Android BLE stack                       │
└─────────────────────────────────────────┘
```

## Decisiones

### D-01 — Nativo antes que multiplataforma
`CoreBluetooth` (iOS) y `android.bluetooth.le` (Android) exponen ciclos de vida que los plugins de Flutter/RN abstraen mal: advertising en background, multi-advertiser y notificaciones GATT se comportan distinto en cada OEM. La capa BLE va en nativo.

Si se necesita UI unificada, Flutter **solo** para presentación, con `MethodChannel`/`EventChannel` hacia el módulo nativo. Nunca la lógica de red en Dart.

### D-02 — Estado en memoria, no en disco
`VolatileChatRepository` es la única fuente de verdad del chat. No hay DAO, ni `SavedStateHandle`, ni cache de imágenes. `allowBackup=false` y sin logs de contenido.

### D-03 — Un servicio GATT compartido
Los tres characteristics viven bajo un único `Service UUID`, lo que reduce el tamaño del advertising y permite filtrar con un solo `ScanFilter`.

### D-04 — Purga por evento, no por timer
`onConnectionStateChange(DISCONNECTED)` dispara `purgeSession()`. No hay TTL del historial: si el vínculo sigue vivo, la conversación sigue viva.

## Fuera de alcance (v1)

- Mensajería multi-salto **sin** cifrado E2E (la malla existe, pero el contenido viaja en claro).
- Varias conversaciones simultáneas (el repo modela **una** sesión activa).
- Persistencia, historial, búsqueda.
- Identidad criptográfica verificable (ver R-09).
