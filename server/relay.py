#!/usr/bin/env python3
"""Relé WebSocket de Radar: une dos o más teléfonos por internet (WiFi/datos).

Es deliberadamente «tonto»:

* Reenvía bytes entre los miembros de una sala. **No descifra nada**: los paquetes que
  circulan ya vienen cifrados con AES-256-GCM desde el teléfono (la clave se deriva del
  código de sala, que viaja en el *fragmento* del enlace y por tanto nunca llega aquí).
* No guarda nada: ni mensajes, ni cuentas, ni historial. Todo vive en la memoria del proceso.
* La sala se identifica con un hash del código (`room`), no con el código en sí.

Solo usa la librería estándar de Python (3.9+): se despliega en cualquier sitio.

Variables de entorno (todas opcionales):
    PORT                   Puerto (por defecto 8080; Render/Fly/Railway lo inyectan).
    RELAY_TOKEN            Si se define, los clientes deben enviar ?token=<valor>.
    RELAY_MAX_MESSAGE      Bytes máximos por mensaje (32768).
    RELAY_MAX_ROOM_PEERS   Miembros máximos por sala (16).
    RELAY_MAX_CONNECTIONS  Conexiones totales (500).
    RELAY_MAX_PER_IP       Conexiones por IP (20).
    RELAY_RATE_BYTES       Bytes/s sostenidos por conexión (262144).
"""
from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import logging
import os
import re
import struct
import time
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

log = logging.getLogger("radar-relay")

GUID = b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

MAX_MESSAGE = int(os.getenv("RELAY_MAX_MESSAGE", 32 * 1024))
MAX_ROOM_PEERS = int(os.getenv("RELAY_MAX_ROOM_PEERS", 16))
MAX_CONNECTIONS = int(os.getenv("RELAY_MAX_CONNECTIONS", 500))
MAX_PER_IP = int(os.getenv("RELAY_MAX_PER_IP", 20))
RATE_BYTES = int(os.getenv("RELAY_RATE_BYTES", 256 * 1024))
TOKEN = os.getenv("RELAY_TOKEN", "")

PING_INTERVAL = 25
IDLE_TIMEOUT = 70
HEADER_TIMEOUT = 10
MAX_HEADER_BYTES = 8 * 1024
MAX_WRITE_BACKLOG = 1024 * 1024  # un cliente lento no puede hacernos acumular memoria

ROOM_RE = re.compile(r"^[A-Za-z0-9_-]{16,64}$")

OP_CONT, OP_TEXT, OP_BIN, OP_CLOSE, OP_PING, OP_PONG = 0x0, 0x1, 0x2, 0x8, 0x9, 0xA


class ProtocolError(Exception):
    """El cliente rompió el protocolo WebSocket."""


# --------------------------------------------------------------------------- página

LANDING_STYLE = (
    "body{margin:0;min-height:100vh;display:grid;place-items:center;background:#070b12;"
    "color:#e8eef8;font:16px/1.5 system-ui,sans-serif}"
    "main{max-width:420px;padding:24px;text-align:center}"
    "h1{margin:0 0 4px;font-size:28px}p{color:#93a1bd;margin:8px 0}"
    "a.btn{display:inline-block;margin:16px 0 8px;padding:14px 28px;border-radius:999px;"
    "background:#3de0ff;color:#00212b;font-weight:700;text-decoration:none}"
    "code{display:block;margin:12px 0;padding:10px;border-radius:12px;background:#0e1522;"
    "color:#4ce3a5;word-break:break-all;font-size:15px}"
    "small{color:#93a1bd}"
)

LANDING_SCRIPT = (
    "var raw=decodeURIComponent((location.hash||'').slice(1)).toUpperCase();"
    "var ok=/^[A-Z0-9-]{20,30}$/.test(raw);"
    "var code=document.getElementById('code'),btn=document.getElementById('open'),"
    "msg=document.getElementById('msg');"
    "if(ok){code.textContent=raw;"
    "btn.setAttribute('href','radar://join?code='+encodeURIComponent(raw)"
    "+'&relay='+encodeURIComponent('wss://'+location.host+'/ws'));"
    "msg.textContent='Toca el botón para entrar a la sala en la app Radar.';}"
    "else{code.textContent='';btn.style.display='none';"
    "msg.textContent='Este enlace no trae un código de sala válido.';}"
)

LANDING_HTML = """<!doctype html>
<html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>Radar · Unirme a una sala</title>
<style>{style}</style></head>
<body><main>
<h1>Radar</h1>
<p id="msg"></p>
<code id="code"></code>
<a class="btn" id="open" href="#">Abrir en Radar</a>
<p><small>El código va después del # del enlace: el servidor nunca lo recibe.<br>
Los mensajes viajan cifrados de extremo a extremo.</small></p>
</main>
<script>{script}</script>
</body></html>
"""


def _csp_hash(text: str) -> str:
    digest = hashlib.sha256(text.encode("utf-8")).digest()
    return "'sha256-" + base64.b64encode(digest).decode("ascii") + "'"


LANDING_BODY = LANDING_HTML.format(style=LANDING_STYLE, script=LANDING_SCRIPT).encode("utf-8")
LANDING_CSP = (
    "default-src 'none'; "
    f"script-src {_csp_hash(LANDING_SCRIPT)}; "
    f"style-src {_csp_hash(LANDING_STYLE)}; "
    "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
)


# --------------------------------------------------------------------------- frames


def encode_frame(opcode: int, payload: bytes = b"") -> bytes:
    """Frame de servidor → cliente (sin máscara, FIN activo)."""
    n = len(payload)
    head = bytes([0x80 | opcode])
    if n < 126:
        head += bytes([n])
    elif n < 65536:
        head += bytes([126]) + struct.pack(">H", n)
    else:
        head += bytes([127]) + struct.pack(">Q", n)
    return head + payload


async def read_frame(reader: asyncio.StreamReader) -> tuple[bool, int, bytes]:
    """Lee un frame de cliente. Devuelve (fin, opcode, payload)."""
    h = await reader.readexactly(2)
    fin = bool(h[0] & 0x80)
    if h[0] & 0x70:
        raise ProtocolError("RSV no soportado")
    opcode = h[0] & 0x0F
    if not (h[1] & 0x80):
        raise ProtocolError("frame de cliente sin máscara")
    length = h[1] & 0x7F
    if length == 126:
        (length,) = struct.unpack(">H", await reader.readexactly(2))
    elif length == 127:
        (length,) = struct.unpack(">Q", await reader.readexactly(8))
    if opcode >= 0x8 and (length > 125 or not fin):
        raise ProtocolError("frame de control inválido")
    if length > MAX_MESSAGE:
        raise ProtocolError("mensaje demasiado grande")
    mask = await reader.readexactly(4)
    data = await reader.readexactly(length) if length else b""
    if length:
        key = (mask * (length // 4 + 1))[:length]
        data = (int.from_bytes(data, "big") ^ int.from_bytes(key, "big")).to_bytes(length, "big")
    return fin, opcode, data


# --------------------------------------------------------------------------- estado


class Client:
    __slots__ = ("writer", "room", "ip", "tokens", "stamp", "last_seen", "closed")

    def __init__(self, writer: asyncio.StreamWriter, room: str, ip: str) -> None:
        self.writer = writer
        self.room = room
        self.ip = ip
        self.tokens = float(RATE_BYTES * 2)
        self.stamp = time.monotonic()
        self.last_seen = time.monotonic()
        self.closed = False

    def allow(self, size: int) -> bool:
        """Cubo de fichas: RATE_BYTES/s sostenidos con ráfaga de 2 s."""
        now = time.monotonic()
        self.tokens = min(RATE_BYTES * 2.0, self.tokens + (now - self.stamp) * RATE_BYTES)
        self.stamp = now
        if size > self.tokens:
            return False
        self.tokens -= size
        return True

    def send(self, data: bytes) -> None:
        if self.closed:
            return
        if self.writer.transport.get_write_buffer_size() > MAX_WRITE_BACKLOG:
            log.warning("cliente lento en sala %s…: se descarta", self.room[:6])
            self.close()
            return
        try:
            self.writer.write(data)
        except Exception:  # noqa: BLE001 - el transporte puede estar ya roto
            self.close()

    def close(self) -> None:
        if self.closed:
            return
        self.closed = True
        try:
            self.writer.close()
        except Exception:  # noqa: BLE001
            pass


class Hub:
    """Salas en memoria. Nada se persiste."""

    def __init__(self) -> None:
        self.rooms: dict[str, set[Client]] = {}
        self.per_ip: dict[str, int] = {}
        self.total = 0

    def can_accept(self, room: str, ip: str) -> str | None:
        if self.total >= MAX_CONNECTIONS:
            return "servidor lleno"
        if self.per_ip.get(ip, 0) >= MAX_PER_IP:
            return "demasiadas conexiones desde tu IP"
        if len(self.rooms.get(room, ())) >= MAX_ROOM_PEERS:
            return "sala llena"
        return None

    def join(self, client: Client) -> None:
        self.rooms.setdefault(client.room, set()).add(client)
        self.per_ip[client.ip] = self.per_ip.get(client.ip, 0) + 1
        self.total += 1
        self.announce(client.room)

    def leave(self, client: Client) -> None:
        members = self.rooms.get(client.room)
        if members is not None:
            members.discard(client)
            if not members:
                del self.rooms[client.room]
        n = self.per_ip.get(client.ip, 0) - 1
        if n <= 0:
            self.per_ip.pop(client.ip, None)
        else:
            self.per_ip[client.ip] = n
        self.total = max(0, self.total - 1)
        self.announce(client.room)

    def announce(self, room: str) -> None:
        members = self.rooms.get(room)
        if not members:
            return
        frame = encode_frame(OP_TEXT, json.dumps({"t": "presence", "peers": len(members)}).encode())
        for member in list(members):
            member.send(frame)

    def forward(self, sender: Client, frame: bytes) -> None:
        for member in list(self.rooms.get(sender.room, ())):
            if member is not sender:
                member.send(frame)


# --------------------------------------------------------------------------- HTTP


def http_response(status: str, body: bytes = b"", ctype: str = "text/plain; charset=utf-8",
                  extra: dict[str, str] | None = None) -> bytes:
    headers = {
        "Content-Type": ctype,
        "Content-Length": str(len(body)),
        "Connection": "close",
        "Cache-Control": "no-store",
        "X-Content-Type-Options": "nosniff",
    }
    headers.update(extra or {})
    head = f"HTTP/1.1 {status}\r\n" + "".join(f"{k}: {v}\r\n" for k, v in headers.items()) + "\r\n"
    return head.encode("latin-1") + body


async def read_head(reader: asyncio.StreamReader) -> tuple[str, str, dict[str, str]] | None:
    try:
        raw = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), HEADER_TIMEOUT)
    except (asyncio.IncompleteReadError, asyncio.LimitOverrunError, asyncio.TimeoutError, ConnectionError):
        return None
    if len(raw) > MAX_HEADER_BYTES:
        return None
    lines = raw.decode("latin-1").split("\r\n")
    parts = lines[0].split(" ")
    if len(parts) != 3:
        return None
    headers: dict[str, str] = {}
    for line in lines[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k.strip().lower()] = v.strip()
    return parts[0], parts[1], headers


def client_ip(writer: asyncio.StreamWriter, headers: dict[str, str]) -> str:
    # Detrás del proxy de Render/Fly la IP real llega en X-Forwarded-For.
    fwd = headers.get("x-forwarded-for", "").split(",")[0].strip()
    if fwd:
        return fwd[:45]
    peer = writer.get_extra_info("peername")
    return str(peer[0]) if peer else "?"


# --------------------------------------------------------------------------- conexión


def make_handler(hub: Hub):
    async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            await _handle(hub, reader, writer)
        except (ConnectionError, asyncio.IncompleteReadError, asyncio.CancelledError):
            pass
        except ProtocolError as exc:
            log.info("protocolo: %s", exc)
        except Exception:  # noqa: BLE001
            log.exception("error inesperado")
        finally:
            try:
                writer.close()
            except Exception:  # noqa: BLE001
                pass

    return handle


async def _handle(hub: Hub, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    head = await read_head(reader)
    if head is None:
        return
    method, target, headers = head
    url = urlsplit(target)

    if method != "GET":
        writer.write(http_response("405 Method Not Allowed"))
        return

    if url.path == "/health":
        writer.write(http_response("200 OK", b"ok"))
        return

    if url.path in ("/", "/join"):
        writer.write(http_response("200 OK", LANDING_BODY, "text/html; charset=utf-8", {
            "Content-Security-Policy": LANDING_CSP,
            "Referrer-Policy": "no-referrer",
            "X-Frame-Options": "DENY",
        }))
        return

    if url.path != "/ws":
        writer.write(http_response("404 Not Found", b"no encontrado"))
        return

    query = parse_qs(url.query)
    room = (query.get("room") or [""])[0]
    if not ROOM_RE.match(room):
        writer.write(http_response("400 Bad Request", b"room invalida"))
        return
    if TOKEN and not hmac.compare_digest((query.get("token") or [""])[0], TOKEN):
        writer.write(http_response("401 Unauthorized", b"token invalido"))
        return

    key = headers.get("sec-websocket-key", "")
    if (headers.get("upgrade", "").lower() != "websocket"
            or "upgrade" not in headers.get("connection", "").lower()
            or headers.get("sec-websocket-version") != "13" or not key):
        writer.write(http_response("426 Upgrade Required", b"se requiere WebSocket",
                                   extra={"Upgrade": "websocket"}))
        return

    ip = client_ip(writer, headers)
    refusal = hub.can_accept(room, ip)
    if refusal:
        writer.write(http_response("503 Service Unavailable", refusal.encode()))
        return

    accept = base64.b64encode(hashlib.sha1(key.encode() + GUID).digest()).decode()
    writer.write(
        ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
         f"Sec-WebSocket-Accept: {accept}\r\n\r\n").encode()
    )
    await writer.drain()

    client = Client(writer, room, ip)
    hub.join(client)
    log.info("sala %s…: +1 (%d)", room[:6], len(hub.rooms.get(room, ())))
    pinger = asyncio.create_task(_keepalive(client))
    try:
        await _pump(hub, reader, client)
    finally:
        pinger.cancel()
        hub.leave(client)
        client.close()
        log.info("sala %s…: -1", room[:6])


async def _keepalive(client: Client) -> None:
    while not client.closed:
        await asyncio.sleep(PING_INTERVAL)
        if time.monotonic() - client.last_seen > IDLE_TIMEOUT:
            client.close()
            return
        client.send(encode_frame(OP_PING))


async def _pump(hub: Hub, reader: asyncio.StreamReader, client: Client) -> None:
    buffer = bytearray()
    kind = OP_BIN
    fragmenting = False
    while not client.closed:
        fin, opcode, data = await read_frame(reader)
        client.last_seen = time.monotonic()

        if opcode == OP_CLOSE:
            client.send(encode_frame(OP_CLOSE, data[:2]))
            return
        if opcode == OP_PING:
            client.send(encode_frame(OP_PONG, data))
            continue
        if opcode == OP_PONG:
            continue

        if opcode in (OP_TEXT, OP_BIN):
            if fragmenting:
                raise ProtocolError("mensaje nuevo con otro fragmentado pendiente")
            kind = opcode
        elif opcode == OP_CONT:
            if not fragmenting:
                raise ProtocolError("continuación sin inicio")
        else:
            raise ProtocolError("opcode inesperado")

        buffer += data
        if len(buffer) > MAX_MESSAGE:
            raise ProtocolError("mensaje demasiado grande")
        if not fin:
            fragmenting = True
            continue

        fragmenting = False
        message = bytes(buffer)
        buffer.clear()
        if not client.allow(len(message)):
            client.send(encode_frame(OP_CLOSE, struct.pack(">H", 1008) + b"limite de tasa"))
            return
        # Solo se reenvían binarios (paquetes ya cifrados). El texto queda reservado.
        if kind == OP_BIN and message:
            hub.forward(client, encode_frame(OP_BIN, message))


# --------------------------------------------------------------------------- arranque


async def serve(host: str = "0.0.0.0", port: int = 8080, hub: Hub | None = None) -> asyncio.base_events.Server:
    hub = hub or Hub()
    return await asyncio.start_server(make_handler(hub), host, port, limit=MAX_HEADER_BYTES * 2)


async def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    port = int(os.getenv("PORT", "8080"))
    server = await serve("0.0.0.0", port)
    log.info("Radar relay escuchando en :%d (token %s)", port, "activado" if TOKEN else "desactivado")
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
