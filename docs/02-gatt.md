# 02 — Perfil GATT, framing y handshake

## UUIDs

| Rol | UUID | Propiedades | Payload |
|---|---|---|---|
| Service | `0000FA01-0000-1000-8000-00805F9B34FB` | — | — |
| Profile Info | `0000FA02-…` | READ | `[lenAlias][alias UTF-8][avatarId]` |
| Connection Control | `0000FA03-…` | WRITE | `[opcode][sessionId:4]` |
| Message Tx/Rx | `0000FA04-…` | WRITE, NOTIFY | `nonce ‖ AES-256-GCM(MeshPacket)` |
| CCCD | `00002902-…` | READ, WRITE | habilitar notificaciones |

> ⚠️ El rango `0xFA00–0xFAFF` está reservado por el Bluetooth SIG. Ver **R-01** en `03-revision-tecnica.md`: antes de publicar hay que migrar a UUIDs aleatorios de 128 bits.

## Opcodes del handshake

| Byte | Opcode | Dirección | Efecto |
|---|---|---|---|
| `0x01` | `CONNECT_REQUEST` | Central → Peripheral | Pide abrir sesión |
| `0x02` | `ACCEPT` | Peripheral → Central | Sesión aceptada |
| `0x03` | `REJECT` | Peripheral → Central | Rechazo (ocupado / denegado) |
| `0x04` | `BYE` | bidireccional | Cierre ordenado |

`sessionId` son 4 bytes aleatorios: evita cruzar mensajes de una sesión anterior si el vínculo se reutiliza.

## Framing de mensajes

El MTU por defecto de BLE es **23 bytes**, lo que deja **20 bytes útiles** por escritura. Un mensaje de chat no entra ahí: hay que fragmentar.

El payload es **opaco**: contiene un `MeshPacket` ya cifrado (`nonce ‖ ciphertext`). El framing solo corta bytes; no interpreta el contenido.

```
┌────────┬────────┬─────────┬──────────────────┐
│ seq    │ total  │ flags   │ payload (≤ 17)   │
│ 1 byte │ 1 byte │ 1 byte  │ UTF-8 parcial    │
└────────┴────────┴─────────┴──────────────────┘
```

- `seq`: índice del fragmento (0-based), máximo 255 fragmentos → ~4 KB por mensaje.
- `total`: cantidad total de fragmentos.
- `flags`: `0x01` = último fragmento.
- El receptor acumula hasta `total` y recién ahí muestra el mensaje.

**Limitación conocida:** no hay ACK ni retransmisión. Si un fragmento se pierde, el mensaje no se completa y se descarta por timeout. Ver **R-03**.

### MTU

`RadarGattClient` solicita `requestMtu(517)` al conectar. Si el peripheral lo acepta, los fragmentos pueden ser mayores; mientras tanto se usa el mínimo seguro de 17 bytes de payload. El receptor **no asume** un tamaño fijo: lee el `seq`/`total` de cada paquete.

## Secuencia de conexión

```
Central                                   Peripheral
   │  scan (service UUID)                     │ advertise
   │ ───────────────────────────────────────► │
   │  connectGatt()                           │
   │ ───────────────────────────────────────► │  onConnectionStateChange(CONNECTED)
   │  discoverServices()                      │
   │  read(Profile Info) ───────────────────► │  responde alias + avatar
   │  requestMtu(517)                         │
   │  write(CCCD = ENABLE_NOTIFICATION) ────► │
   │  write(Control, CONNECT_REQUEST) ──────► │
   │ ◄──────────────────── notify(ACCEPT) ─── │
   │  write(Message, fragmentos…) ──────────► │
   │ ◄──────────────────── notify(fragmentos)─│
   │  disconnect                              │
   │ ───────────────────────────────────────► │  purgeSession()
```

## Reglas de sesión

1. Un dispositivo acepta **una** sesión a la vez. Si ya hay una activa, responde `REJECT`.
2. Si el `sessionId` recibido no coincide con el negociado, el paquete se descarta.
3. Cualquier error de GATT (`status != GATT_SUCCESS`) cierra la sesión y purga.
4. `BYE` cierra ordenadamente; el receptor no debe notificar de vuelta.
