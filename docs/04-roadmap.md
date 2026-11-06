# 04 — Roadmap

## Fase 0 — Fundaciones ✅
- [x] Repo, estructura Gradle, documentación
- [x] Perfil GATT definido
- [x] Revisión técnica de la spec

## Fase 1 — Núcleo BLE ✅
- [x] Advertising + scanning simultáneos (`RadarBleManager`)
- [x] GATT server con los 3 characteristics + CCCD
- [x] GATT client: connect, discover, read profile, notify
- [x] Fragmentación / reensamblado de mensajes
- [x] Repositorio volátil + purga por desconexión
- [x] Máquina de estados del handshake
- [x] UI mínima con permisos en runtime

## Fase 2 — Que funcione de verdad
- [x] Malla multi-salto: dedup, TTL, store-and-forward (`mesh/`)
- [x] Vínculos BLE simultáneos (hasta 3 salientes, cola para el resto)
- [x] CI de compilación en GitHub Actions
- [x] Foreground service (`connectedDevice`) para background en Android
- [x] Manejo de errores GATT con códigos y reintento
- [x] Tests JVM del framing, router, crypto y repositorio volátil
- [x] Sesión fuera de la Activity (`RadarViewModel`) — sobrevive la rotación
- [x] `FLAG_SECURE` + `setRecentsScreenshotEnabled(false)`
- [ ] Compilar y correr en dos dispositivos físicos (**requiere hardware**)

## Fase 3 — Seguridad
- [x] Cifrado E2E con clave de sala (AES-256-GCM + PBKDF2) — R-02
- [x] Rotación de sala desde la UI
- [x] UUIDs aleatorios de 128 bits (R-01)
- [x] ACK de entrega a nivel aplicación (R-03)
- [ ] Retransmisión con backoff si el ACK no llega
- [ ] X25519 por par: forward secrecy
- [ ] Fingerprint visual del par (R-09)

## Fase 4 — Producto
- [x] Lista de peers con cercanía cualitativa (R-12)
- [x] QR de sala: generar, compartir, copiar y escanear
- [x] Modos BLE `BALANCED` y dedup de scan (R-11)
- [ ] Duty cycle de scan/advertise (10 s / 20 s) y `LOW_POWER` sin peers
- [ ] Rechazo explícito cuando hay sesión activa
- [ ] iOS: decidir alcance real (R-04)

## Fase 5 — Exploratorio
- [ ] Mesh con > 3 saltos o routing por demanda
- [ ] Sesiones múltiples simultáneas
- [ ] Transferencia de avatar (hoy es solo un ID)

---

## Checklist de validación en hardware

No se puede automatizar desde CI. Requiere 2–3 teléfonos Android reales (API 31+ idealmente uno de ellos con API 34+).

1. **Permisos y arranque** — instalar el APK, conceder permisos, verificar la notificación persistente del foreground service.
2. **Descubrimiento** — ambos teléfonos se ven en la lista con RSSI y cercanía.
3. **Vínculo** — conectar; el estado pasa a "Malla activa · 1 vínculo(s)".
4. **Mensaje directo** — enviar texto; llega y el emisor ve `✓ entregado` tras el ACK.
5. **Cifrado** — cambiar la sala en un solo teléfono; el mensaje no debe aparecer en el otro (`undecryptable` sube, no se muestra basura).
6. **Multi-salto** — con 3 teléfonos A–B–C, alejar A de C fuera de alcance; A y C deben intercambiar mensajes vía B y mostrar `salto(s) > 0`.
7. **Store-and-forward** — apagar C, enviar desde A, encender C dentro de 2 minutos: debe recibir lo pendiente al reconectar.
8. **Rotación** — girar el teléfono en pleno chat; la sesión y el código de sala no deben cambiar.
9. **Background** — minimizar la app 5–10 minutos; el advertising debe seguir (notificación activa) y los mensajes deben llegar.
10. **QR** — generar el QR en A, escanearlo en B, unirse y comprobar que se comunican.
11. **Purga** — alejar/apagar el peer; al caer el último vínculo el chat debe vaciarse.
12. **Batería** — medir consumo en 30 minutos con un vínculo activo; anotar % por hora.
