"""Pruebas del relé. Se ejecutan con:  python -m unittest -v test_relay   (sin dependencias)."""
from __future__ import annotations

import asyncio
import base64
import hashlib
import os
import struct
import unittest

import relay

ROOM_A = "a" * 32
ROOM_B = "b" * 32


class WsClient:
    """Cliente WebSocket mínimo (con máscara, como exige el RFC 6455)."""

    def __init__(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        self.reader, self.writer = reader, writer

    @classmethod
    async def connect(cls, port: int, room: str, token: str = "", expect: str = "101") -> "WsClient | str":
        reader, writer = await asyncio.open_connection("127.0.0.1", port)
        key = base64.b64encode(os.urandom(16)).decode()
        query = f"room={room}" + (f"&token={token}" if token else "")
        writer.write((
            f"GET /ws?{query} HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        head = (await reader.readuntil(b"\r\n\r\n")).decode()
        status = head.split(" ")[1]
        if status != "101":
            writer.close()
            return status
        expected = base64.b64encode(hashlib.sha1(key.encode() + relay.GUID).digest()).decode()
        assert expected in head, "Sec-WebSocket-Accept incorrecto"
        return cls(reader, writer)

    def send(self, opcode: int, payload: bytes, fin: bool = True) -> None:
        mask = os.urandom(4)
        n = len(payload)
        head = bytes([(0x80 if fin else 0) | opcode])
        if n < 126:
            head += bytes([0x80 | n])
        elif n < 65536:
            head += bytes([0x80 | 126]) + struct.pack(">H", n)
        else:
            head += bytes([0x80 | 127]) + struct.pack(">Q", n)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.writer.write(head + mask + masked)

    async def recv(self, timeout: float = 2.0) -> tuple[int, bytes]:
        async def read() -> tuple[int, bytes]:
            h = await self.reader.readexactly(2)
            n = h[1] & 0x7F
            if n == 126:
                (n,) = struct.unpack(">H", await self.reader.readexactly(2))
            elif n == 127:
                (n,) = struct.unpack(">Q", await self.reader.readexactly(8))
            return h[0] & 0x0F, await self.reader.readexactly(n)

        return await asyncio.wait_for(read(), timeout)

    async def recv_data(self, timeout: float = 2.0) -> tuple[int, bytes]:
        """Ignora presencia/ping y devuelve el siguiente dato real."""
        while True:
            op, data = await self.recv(timeout)
            if op in (relay.OP_BIN, relay.OP_TEXT) and not (op == relay.OP_TEXT and b"presence" in data):
                return op, data

    async def close(self) -> None:
        self.writer.close()


async def http_get(port: int, path: str) -> tuple[str, str, bytes]:
    reader, writer = await asyncio.open_connection("127.0.0.1", port)
    writer.write(f"GET {path} HTTP/1.1\r\nHost: x\r\n\r\n".encode())
    raw = await reader.read()
    writer.close()
    head, _, body = raw.partition(b"\r\n\r\n")
    return head.decode().split(" ")[1], head.decode(), body


class RelayTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.server = await relay.serve("127.0.0.1", 0)
        self.port = self.server.sockets[0].getsockname()[1]

    async def asyncTearDown(self) -> None:
        self.server.close()
        await self.server.wait_closed()

    async def test_reenvia_a_los_demas_y_no_hace_eco(self) -> None:
        a = await WsClient.connect(self.port, ROOM_A)
        b = await WsClient.connect(self.port, ROOM_A)
        c = await WsClient.connect(self.port, ROOM_A)
        payload = bytes(range(256)) * 4
        a.send(relay.OP_BIN, payload)

        self.assertEqual((relay.OP_BIN, payload), await b.recv_data())
        self.assertEqual((relay.OP_BIN, payload), await c.recv_data())
        with self.assertRaises(asyncio.TimeoutError):
            await a.recv_data(timeout=0.3)  # el emisor no recibe su propio paquete

    async def test_las_salas_estan_aisladas(self) -> None:
        a = await WsClient.connect(self.port, ROOM_A)
        b = await WsClient.connect(self.port, ROOM_B)
        a.send(relay.OP_BIN, b"secreto")
        with self.assertRaises(asyncio.TimeoutError):
            await b.recv_data(timeout=0.3)

    async def test_presencia_informa_cuantos_hay(self) -> None:
        a = await WsClient.connect(self.port, ROOM_A)
        self.assertEqual((relay.OP_TEXT, b'{"t": "presence", "peers": 1}'), await a.recv())
        b = await WsClient.connect(self.port, ROOM_A)
        self.assertIn(b'"peers": 2', (await a.recv())[1])
        await b.close()
        self.assertIn(b'"peers": 1', (await a.recv())[1])

    async def test_mensaje_fragmentado_se_reensambla(self) -> None:
        a = await WsClient.connect(self.port, ROOM_A)
        b = await WsClient.connect(self.port, ROOM_A)
        a.send(relay.OP_BIN, b"hola ", fin=False)
        a.send(relay.OP_CONT, b"mundo", fin=True)
        self.assertEqual((relay.OP_BIN, b"hola mundo"), await b.recv_data())

    async def test_ping_se_responde_con_pong(self) -> None:
        a = await WsClient.connect(self.port, ROOM_A)
        await a.recv()  # presencia propia
        a.send(relay.OP_PING, b"x")
        self.assertEqual((relay.OP_PONG, b"x"), await a.recv())

    async def test_room_invalida_es_400(self) -> None:
        self.assertEqual("400", await WsClient.connect(self.port, "corta"))
        self.assertEqual("400", await WsClient.connect(self.port, "../etc/passwd" + "x" * 20))

    async def test_mensaje_demasiado_grande_cierra_la_conexion(self) -> None:
        a = await WsClient.connect(self.port, ROOM_A)
        b = await WsClient.connect(self.port, ROOM_A)
        a.send(relay.OP_BIN, b"x" * (relay.MAX_MESSAGE + 1))
        with self.assertRaises((asyncio.IncompleteReadError, ConnectionError, asyncio.TimeoutError)):
            for _ in range(5):
                await a.recv(timeout=1.0)
        with self.assertRaises(asyncio.TimeoutError):
            await b.recv_data(timeout=0.3)

    async def test_el_texto_del_cliente_no_se_reenvia(self) -> None:
        a = await WsClient.connect(self.port, ROOM_A)
        b = await WsClient.connect(self.port, ROOM_A)
        a.send(relay.OP_TEXT, b"hola")
        with self.assertRaises(asyncio.TimeoutError):
            await b.recv_data(timeout=0.3)

    async def test_sala_llena(self) -> None:
        original = relay.MAX_ROOM_PEERS
        relay.MAX_ROOM_PEERS = 2
        try:
            a = await WsClient.connect(self.port, ROOM_A)
            b = await WsClient.connect(self.port, ROOM_A)
            self.assertEqual("503", await WsClient.connect(self.port, ROOM_A))
            self.assertIsInstance(a, WsClient)
            self.assertIsInstance(b, WsClient)
        finally:
            relay.MAX_ROOM_PEERS = original

    async def test_limite_de_tasa(self) -> None:
        original = relay.RATE_BYTES
        relay.RATE_BYTES = 1000
        try:
            a = await WsClient.connect(self.port, ROOM_A)
            await a.recv()
            a.send(relay.OP_BIN, b"x" * 1500)
            a.send(relay.OP_BIN, b"x" * 1500)  # supera la ráfaga de 2 s
            a.send(relay.OP_BIN, b"x" * 1500)
            op = None
            for _ in range(5):
                try:
                    op, _data = await a.recv(timeout=1.0)
                except (asyncio.IncompleteReadError, ConnectionError, asyncio.TimeoutError):
                    break
                if op == relay.OP_CLOSE:
                    break
            self.assertEqual(relay.OP_CLOSE, op)
        finally:
            relay.RATE_BYTES = original

    async def test_token_obligatorio_si_se_configura(self) -> None:
        original = relay.TOKEN
        relay.TOKEN = "s3creto"
        try:
            self.assertEqual("401", await WsClient.connect(self.port, ROOM_A))
            self.assertEqual("401", await WsClient.connect(self.port, ROOM_A, token="malo"))
            self.assertIsInstance(await WsClient.connect(self.port, ROOM_A, token="s3creto"), WsClient)
        finally:
            relay.TOKEN = original

    async def test_http_basico(self) -> None:
        status, _, body = await http_get(self.port, "/health")
        self.assertEqual(("200", b"ok"), (status, body))

        status, head, body = await http_get(self.port, "/")
        self.assertEqual("200", status)
        self.assertIn("Content-Security-Policy", head)
        self.assertIn(b"Abrir en Radar", body)

        status, _, _ = await http_get(self.port, "/nada")
        self.assertEqual("404", status)

    async def test_ws_sin_upgrade_es_426(self) -> None:
        status, _, _ = await http_get(self.port, f"/ws?room={ROOM_A}")
        self.assertEqual("426", status)

    async def test_la_csp_coincide_con_el_html(self) -> None:
        # Si alguien edita el script/estilo sin recalcular el hash, el navegador lo bloquearía.
        _, head, body = await http_get(self.port, "/")
        self.assertIn(relay._csp_hash(relay.LANDING_SCRIPT), head)
        self.assertIn(relay._csp_hash(relay.LANDING_STYLE), head)
        self.assertIn(relay.LANDING_SCRIPT.encode(), body)


if __name__ == "__main__":
    unittest.main()
