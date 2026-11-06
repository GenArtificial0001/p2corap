# 04 — Roadmap

## Fase 0 — Fundaciones ✅
- [x] Repo, estructura Gradle, documentación
- [x] Perfil GATT definido
- [x] Revisión técnica de la spec

## Fase 1 — Núcleo BLE ✅ (scaffold, sin compilar)
- [x] Advertising + scanning simultáneos (`RadarBleManager`)
- [x] GATT server con los 3 characteristics + CCCD
- [x] GATT client: connect, discover, read profile, notify
- [x] Fragmentación / reensamblado de mensajes
- [x] Repositorio volátil + purga por desconexión
- [x] Máquina de estados del handshake
- [x] UI mínima con permisos en runtime

## Fase 2 — Que funcione de verdad
- [x] Malla multi-salto: dedup, TTL, store-and-forward (`mesh/`)
- [x] Vínculos BLE simultáneos (hasta 3 salientes)
- [x] CI de compilación en GitHub Actions
- [ ] Compilar y correr en dos dispositivos físicos
- [ ] Foreground service (`connectedDevice`) para background en Android
- [ ] `FLAG_SECURE` + `setRecentsScreenshotEnabled(false)`
- [ ] Manejo de errores GATT con códigos y reintento
- [ ] Tests instrumentados del framing (JVM puro, sin BLE)

## Fase 3 — Seguridad
- [x] Cifrado E2E con clave de sala (AES-256-GCM + PBKDF2) — R-02
- [x] Rotación de sala desde la UI
- [x] Tests unitarios de crypto y framing en CI
- [ ] Migrar a UUIDs aleatorios (R-01)
- [ ] X25519 por par: forward secrecy
- [ ] Fingerprint visual del par (R-09)
- [ ] ACK + retransmisión, o L2CAP CoC (R-03)

## Fase 4 — Producto
- [ ] Duty cycle de scan/advertise (R-11)
- [ ] Lista de peers con cercanía cualitativa (R-12)
- [ ] Rechazo explícito cuando hay sesión activa
- [ ] iOS: decidir alcance real (R-04)

## Fase 5 — Exploratorio
- [ ] Mesh multi-salto
- [ ] Sesiones múltiples
- [ ] Transferencia de avatar (hoy es solo un ID)
