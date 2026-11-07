# Radar Relay

Relé WebSocket mínimo para hablar por **internet (WiFi o datos)** con alguien que no está al alcance
del Bluetooth. Un solo archivo, **sin dependencias** (Python 3.9+).

## Qué ve y qué no ve el servidor

| Ve | No ve |
|---|---|
| IP de conexión, hash de la sala, tamaño y hora de los paquetes | El código de sala, el texto, las fotos, audios, videos |

* El código de sala viaja en el **fragmento** del enlace (`https://host/join#CODIGO`). Los navegadores
  no envían el fragmento al servidor.
* La sala del relé es `room = SHA-256("radar-relay-v1|" + CODIGO)` (32 hex). Conocer `room` no permite
  derivar la clave AES-256-GCM, que usa otra sal.
* No hay base de datos ni logs de contenido. Reiniciar el servicio borra todo.

## Probar hoy en tu PC (1 minuto)

```bash
cd server
python relay.py            # escucha en :8080
python -m unittest -v test_relay
```

Para que un teléfono por datos móviles llegue a tu PC, expón el puerto con un túnel (sin abrir puertos):

```bash
cloudflared tunnel --url http://localhost:8080     # imprime https://algo.trycloudflare.com
```

En la app, la URL del relé es `wss://algo.trycloudflare.com/ws`.

## Subirlo a internet gratis (sin tarjeta)

**Render** (lo más directo; ya hay `render.yaml` en la raíz del repo):

1. Entra a <https://render.com> con tu cuenta de GitHub.
2. *New +* → *Blueprint* → elige este repo → *Apply*.
3. Al terminar tendrás `https://radar-relay-XXXX.onrender.com`. Comprueba `…/health` → `ok`.
4. En la app: relé = `wss://radar-relay-XXXX.onrender.com/ws`.

El plan gratuito duerme tras ~15 min sin tráfico; la primera conexión tarda ~50 s en despertar.
Para uso continuo: Fly.io (`fly launch` con este `Dockerfile`) o Railway, mismo contenedor.

## Endpoints

| Ruta | Qué hace |
|---|---|
| `GET /ws?room=<hash>[&token=<t>]` | WebSocket. Los mensajes **binarios** se reenvían al resto de la sala. |
| `GET /join#<CODIGO>` (o `/`) | Página de invitación: botón «Abrir en Radar» (`radar://join?code=…&relay=…`). |
| `GET /health` | `ok`, para el health check de la plataforma. |

El servidor envía además un texto JSON `{"t": "presence", "peers": N}` cuando alguien entra o sale.

## Límites (configurables por variables de entorno)

32 KB por mensaje · 16 miembros por sala · 20 conexiones por IP · 500 totales · 256 KB/s por conexión ·
`RELAY_TOKEN` opcional. Ver cabecera de `relay.py`.

## Seguridad

* TLS lo pone la plataforma (Render/Fly/Cloudflare): usa siempre `wss://`.
* El relé es un transporte: la confidencialidad e integridad son **de extremo a extremo** (AES-256-GCM
  con la clave de la sala). Un relé malicioso puede descartar o repetir paquetes, no leerlos ni alterarlos;
  la app deduplica por `messageId`.
* La sala pública de fábrica (`RADAR…`) es legible por cualquiera con la app: para chatear en privado usa
  una **sala nueva** y comparte el enlace.
