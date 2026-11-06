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
| **Entrega** | Sin ACK extremo a extremo: no hay garantía. |
| **Ancho de banda** | BLE GATT práctico: 1–10 KB/s por vínculo. Texto sí; imágenes no. |
| **Batería** | Relay constante = consumo constante. El duty cycle es obligatorio en producción. |
| **Orden** | Los mensajes pueden llegar desordenados. No hay reloj global. |

## 🔴 Seguridad: la malla rompe la confidencialidad

Este es el punto que hay que decidir antes de usar la app con gente real:

> En una malla, **los nodos intermedios ven el payload**. Hoy el paquete viaja en texto plano. B ya lee lo que A le escribió a C.

Cifrar el enlace BLE (pairing) **no alcanza**: protege A→B y B→C, pero B descifra y reenvía. La única solución es **cifrado extremo a extremo** con claves que B no tiene.

Requisito derivado: R-02 pasa de "importante" a **bloqueante**.

Diseño propuesto (fase siguiente):

```
payload = AES-256-GCM(
    key  = HKDF(X25519(privA, pubC)),
    aad  = messageId || originId,
    data = texto
)
```

- Cada nodo publica su clave pública X25519 en `Profile Info` (reemplaza el avatar ID).
- El emisor deriva la clave compartida solo con el destinatario.
- Los relays reenvían ciphertext que no pueden abrir.
- El `messageId` va en claro (necesario para dedup) pero no revela contenido.

## Lo implementado

- [x] `MeshPacket` con serialización binaria
- [x] `MeshRouter`: dedup LRU, TTL, originId, store-and-forward
- [x] Múltiples vínculos BLE simultáneos (salientes y entrantes)
- [x] Reenvío excluyendo el link de origen
- [x] Métricas: vínculos, relayados, salto máximo
- [x] UI muestra los saltos de cada mensaje

## Pendiente

- [ ] Cifrado E2E (bloqueante)
- [ ] ACK / confirmación de entrega
- [ ] Duty cycle de scan/advertise
- [ ] Priorización: texto > control
- [ ] Limitar reenvíos por nodo y por unidad de tiempo (anti-abuso)
- [ ] Persistencia opcional de la cola (hoy se pierde al matar el proceso)
