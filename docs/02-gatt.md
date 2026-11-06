# 02 — Perfil GATT, framing y handshake

## UUIDs

| Rol | UUID | Propiedades | Payload |
|---|---|---|---|
| Service | `7D022782-8CD9-4FD0-8648-780D092EF19B` | — | — |
| Profile Info | `23ECFC0A-133C-4E13-9151-C8250E514414` | READ | `[lenAlias][alias UTF-8][avatarId]` |
| Connection Control | `DBD2BE6F-0278-47CE-AA7E-137BF8F40F6F` | WRITE | `[opcode][sessionId:4]` |
| Message Tx/Rx | `77188928-C80D-4CB5-8C3B-B73C0D1B4585` | WRITE, NOTIFY | `nonce ‖ AES-256-GCM(MeshPacket)` |
| CCCD | `00002902-…` | READ, WRITE | habilitar notificaciones |

> ✅ UUIDs aleatorios de 128 bits (v4), generados fuera del rango reservado del SIG. R-01 resuelto. Fuente única de verdad: `ble/RadarUuids.kt`.

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
