package com.radar.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayRoomTest {

    @Test
    fun `el id de sala coincide con el que calcula el servidor`() {
        // Valores calculados con hashlib.sha256(b"radar-relay-v1|" + código)[:32] (ver server/relay.py).
        assertEquals("052f5a840f8b26799e2b09d99dba7513", RelayRoom.idFor("RADAR000000000000000"))
        assertEquals("91e86e38acd9c0b682f71628dc24deb1", RelayRoom.idFor("ABCDE12345FGHJK67890"))
    }

    @Test
    fun `el id no depende del formato en que se escribe el codigo`() {
        val a = RelayRoom.idFor("ABCDE-12345-FGHJK-67890")
        val b = RelayRoom.idFor("abcde 12345 fghjk 67890")

        assertEquals(a, b)
        assertEquals(RelayRoom.idFor("ABCDE12345FGHJK67890"), a)
    }

    @Test
    fun `el id cumple el formato que acepta el servidor`() {
        val id = RelayRoom.idFor("ABCDE12345FGHJK67890")

        assertTrue(Regex("^[A-Za-z0-9_-]{16,64}$").matches(id))
        assertNotEquals(id, RelayRoom.idFor("ABCDE12345FGHJK67891"))
    }

    @Test
    fun `construye el enlace con el codigo en el fragmento`() {
        val link = InviteLink.build("wss://radar.example.com/ws", "ABCDE12345FGHJK67890")

        assertEquals("https://radar.example.com/join#ABCDE-12345-FGHJK-67890", link)
        // Lo que va tras el # no se envía al servidor.
        assertTrue(!link!!.substringBefore('#').contains("ABCDE"))
    }

    @Test
    fun `conserva el puerto no estandar`() {
        val link = InviteLink.build("wss://radar.example.com:8443/ws", "ABCDE12345FGHJK67890")

        assertEquals("https://radar.example.com:8443/join#ABCDE-12345-FGHJK-67890", link)
    }

    @Test
    fun `interpreta el enlace https compartido`() {
        val invite = InviteLink.parse("https://radar.example.com/join#ABCDE-12345-FGHJK-67890")

        assertNotNull(invite)
        assertEquals("wss://radar.example.com/ws", invite!!.relayUrl)
        assertEquals("ABCDE-12345-FGHJK-67890", invite.code)
    }

    @Test
    fun `interpreta el enlace de la app y su ida y vuelta`() {
        val app = InviteLink.appLink("wss://radar.example.com/ws", "ABCDE12345FGHJK67890")

        val invite = InviteLink.parse(app)

        assertEquals("wss://radar.example.com/ws", invite?.relayUrl)
        assertEquals("ABCDE-12345-FGHJK-67890", invite?.code)
    }

    @Test
    fun `una invitacion sin rele es para Wi-Fi local`() {
        val app = InviteLink.appLink(null, "ABCDE12345FGHJK67890")

        assertEquals("radar://join?code=ABCDE-12345-FGHJK-67890", app)
        val invite = InviteLink.parse(app)
        assertNull(invite?.relayUrl)
        assertEquals("ABCDE-12345-FGHJK-67890", invite?.code)
    }

    @Test
    fun `rechaza enlaces invalidos o peligrosos`() {
        // Sin código, código corto, esquema distinto, relé sin TLS, basura.
        assertNull(InviteLink.parse("https://radar.example.com/join"))
        assertNull(InviteLink.parse("https://radar.example.com/join#ABC"))
        assertNull(InviteLink.parse("http://radar.example.com/join#ABCDE-12345-FGHJK-67890"))
        assertNull(InviteLink.parse("radar://join?code=ABCDE12345FGHJK67890&relay=ws://evil.example/ws"))
        assertNull(InviteLink.parse("radar://join?code=ABC"))
        assertNull(InviteLink.parse("radar://join?relay=wss://radar.example.com/ws"))
        assertNull(InviteLink.parse("javascript:alert(1)"))
        assertNull(InviteLink.parse("no es un enlace"))
        assertNull(InviteLink.parse(""))
    }

    @Test
    fun `solo se acepta wss`() {
        assertNotNull(InviteLink.parseRelay("wss://radar.example.com/ws"))
        assertNull(InviteLink.parseRelay("ws://radar.example.com/ws"))
        assertNull(InviteLink.parseRelay("https://radar.example.com/ws"))
        assertNull(InviteLink.parseRelay("wss://"))
        assertNull(InviteLink.parseRelay("radar.example.com"))
    }
}
