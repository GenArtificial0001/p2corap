# 05 — Malla (mesh) multi-salto

## Por qué

Un vínculo BLE llega a 10–50 m según el entorno. Si cada teléfono solo hablara con el que tiene al lado, la red muere en la puerta de la habitación. La malla convierte a cada dispositivo con la app en un **repetidor**: A ↔ B ↔ C permite que A y C se escriban sin verse.

## Modelo: DTN epidémico acotado

No hay rutas, ni tabla de vecinos, ni handshake de topología. Es **store-carry-forward** (red tolerante a demoras):

1. Un nodo origina un paquete con `messageId` único y `ttl`.
2. Lo envía a **todos** sus vínculos.
3. Cada vecino lo entrega a su UI, lo guarda y lo reenvía a todos sus otros vínculos con `ttl - 1`.
4. Si un nodo no tiene más vecinos, el paquete queda en cola hasta 2 minutos y se entrega cuando aparece alguien nuevo.
5. Si `ttl` llega a 1, el paquete se entrega pero no se reenvía.

Es flooding, pero **acotado**: sin dedup sería una tormenta infinita; con dedup y TTL es un broadcast controlado.

## Formato del paquete

```
 0      version      1
 1      type         1     (1 = TEXT)
 2-9    messageId    8     aleatorio
10-13   originId     4     identidad efímera del proceso
14      ttl          1     saltos restantes
15      hopCount     1     saltos recorridos
16-23   timestamp    8
24-25   payloadLen   2
26..    payload      n     UTF-8
```

Header de 26 bytes. Payload máximo 512 bytes (~180 caracteres en español). El paquete completo se fragmenta después en tramas de 17 bytes para BLE.

## Reglas de reenvío

| Situación | Acción |
|---|---|
| `messageId` ya visto | Descartar (dedup) |
| `originId == self` | Descartar (eco propio) |
| `ttl > 1` | Entregar + reenviar con `ttl-1`, `hop+1` |
| `ttl == 1` | Entregar, no reenviar |
| Llega por el vínculo X | Reenviar a todos **menos X** |

La memoria de dedup es un LRU de 512 IDs con TTL de 5 minutos, en RAM (coherente con Zero-Storage).

## Topología real

```
        (A) ──── (B) ──── (C) ──── (D)
         │                          │
         └────────── (E) ───────────┘
```

- Cada nodo mantiene hasta **3 conexiones salientes** (Central) y varias entrantes (Peripheral).
- El límite no es arbitrario: cada conexión GATT consume stack y batería; Android degrada con muchas simultáneas.
- Un nodo con 5 vecinos reenvía 5 veces cada paquete. El costo crece lineal con el grado, no con la red.

## Límites honestos

| Aspecto | Realidad |
|---|---|
| **Cobertura** | Se extiende, pero solo si hay dispositivos en el medio. Sin gente, no hay red. |
| **Latencia** | Oportunista. Un mensaje puede tardar segundos o nunca llegar si no hay puente. |
| **Entrega** | ACK de aplicación propagado por la malla (`✓ entregado`). No hay retransmisión si el ACK no llega. |
| **Ancho de banda** | BLE GATT práctico: 1–10 KB/s por vínculo. Texto sí; imágenes no. |
| **Batería** | Relay constante = consumo constante. El duty cycle es obligatorio en producción. |
| **Orden** | Los mensajes pueden llegar desordenados. No hay reloj global. |

## 🔒 Seguridad: clave de sala (implementado)

Sin cifrado, **los nodos intermedios leen el payload**. Por eso los mensajes viajan cifrados con una **clave de sala**:

```
payload = nonce(12) || AES-256-GCM(clave_sala, texto)
clave_sala = PBKDF2-HMAC-SHA256(código, salt="radar-v1", 120k iteraciones)
```

- El **código** (20 caracteres, alfabeto Crockford) se comparte fuera de banda: de viva voz, QR o mensaje.
- La clave **nunca viaja por la malla**: los relays reenvían ciphertext que no pueden abrir.
- El nonce es aleatorio por mensaje; el tag GCM detecta cualquier alteración.
- Si un nodo recibe un paquete de otra sala, lo **reenvía igual** (para no romper la malla) pero no lo muestra.

### Qué protege y qué no

| Protección | Estado |
|---|---|
| Relay intermedio no lee el contenido | ✅ |
| Sniffer BLE pasivo no lee el contenido | ✅ |
| Alteración del mensaje se detecta (GCM) | ✅ |
| Quien conoce el código lee todo el chat | ⚠️ por diseño (clave de grupo) |
| Suplantación de identidad | ❌ pendiente (sin firmas) |
| Forward secrecy (clave por sesión) | ❌ pendiente (X25519) |

**Limitación honesta:** es una clave de grupo. Si alguien filtra el código, lee la sala. Para el caso de uso (grupo chico, encuentro presencial, mensajes efímeros) es el intercambio correcto entre seguridad y fricción.

### Evolución prevista

- X25519 por par + rotación de clave para forward secrecy.
- Identidad firmada (fingerprint visual estilo Signal) para evitar suplantación.

## Lo implementado

- [x] `MeshPacket` con serialización binaria
- [x] `MeshRouter`: dedup LRU, TTL, originId, store-and-forward
- [x] Múltiples vínculos BLE simultáneos (salientes y entrantes)
- [x] Reenvío excluyendo el link de origen
- [x] **Cifrado E2E con clave de sala** (AES-256-GCM + PBKDF2)
- [x] Sala configurable por código, con rotación ("Sala nueva")
- [x] Tests unitarios de crypto, framing, ensamblado y router en CI
- [x] Métricas: vínculos, relayados, salto máximo, no descifrables, ACK y cola
- [x] UI muestra los saltos de cada mensaje
- [x] ACK de entrega (`TYPE_ACK`) y marca `✓ entregado` en la UI
- [x] QR para compartir la sala (generar, copiar, compartir y escanear)

## Pendiente

- [ ] Forward secrecy por par (X25519) e identidad firmada
- [ ] Retransmisión con backoff si el ACK no llega
- [ ] Duty cycle de scan/advertise
- [ ] Priorización: texto > control
- [ ] Limitar reenvíos por nodo y por unidad de tiempo (anti-abuso)
- [ ] Persistencia opcional de la cola (hoy se pierde al matar el proceso)
