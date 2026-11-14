package app.anynear.anmp

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertTest {
    private fun body(
        text: String = "Rauch im Keller",
        prio: Int = AnmpAlerts.ALARM,
        title: String? = "Rauchmelder",
        url: String? = null,
        attachments: List<AnmpAttachment>? = null,
        replaces: ByteArray? = null,
        ended: Boolean = false,
    ) = AnmpAlertBody(Fixtures.alertId, 1_760_000_000, prio, title, text, url, replaces = replaces, attachments = attachments, ended = ended)

    @Test
    fun sealAndOpen() = runTest {
        val sender = AnmpSpaceKey.fromPrivateKey(Fixtures.senderSeed)
        val readPublic = AnmpAlerts.readPublicKey(Fixtures.readKey)
        val packet = AnmpAlerts.encode(body(), sender, readPublic)
        assertTrue(AnmpAlerts.isAlert(packet))
        assertFalse(Anmp.isAnmp(packet))
        val a = assertNotNull(AnmpAlerts.open(packet, Fixtures.readKey))
        assertContentEquals(sender.publicKey, a.signer)
        assertEquals("Rauch im Keller", a.body.text)
        assertEquals(AnmpAlerts.ALARM, a.body.prio)
        assertEquals(1_760_000_000 + 86_400L, a.body.expires)
        // Every packet has its own one-time key.
        assertFalse(packet.contentEquals(AnmpAlerts.encode(body(), sender, readPublic)))
    }

    @Test
    fun wrongKeyOrTamperedFails() = runTest {
        val sender = AnmpSpaceKey.fromPrivateKey(Fixtures.senderSeed)
        val packet = AnmpAlerts.encode(body(), sender, AnmpAlerts.readPublicKey(Fixtures.readKey))
        assertNull(AnmpAlerts.open(packet, Fixtures.ephKey))
        for (i in listOf(0, 5, 40, packet.size - 1)) {
            val bad = packet.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull(AnmpAlerts.open(bad, Fixtures.readKey), "byte $i")
        }
        assertNull(AnmpAlerts.open(packet.copyOf(20), Fixtures.readKey))
    }

    @Test
    fun forgedSignatureFails() = runTest {
        val sender = AnmpSpaceKey.fromPrivateKey(Fixtures.senderSeed)
        val other = AnmpSpaceKey.fromPrivateKey(Fixtures.channelOwner)
        val signed = AnmpAlerts.sign(body(), sender)
        val s = Anmp.cbor.decodeFromByteArray(AnmpAlerts.Signed.serializer(), signed)
        // Another signer key claimed for the same signature.
        val forged = Anmp.cbor.encodeToByteArray(AnmpAlerts.Signed.serializer(), AnmpAlerts.Signed(s.body, other.publicKey, s.sig))
        assertNull(AnmpAlerts.verify(forged))
        assertNotNull(AnmpAlerts.verify(signed))
    }

    @Test
    fun bodyRules() {
        val att = AnmpAttachment(ByteArray(32), ByteArray(32), "jpg", size = 3)
        assertTrue(AnmpAlerts.isValid(body()))
        assertTrue(AnmpAlerts.isValid(body(text = "", attachments = listOf(att))))
        assertFalse(AnmpAlerts.isValid(body(text = "")))
        assertFalse(AnmpAlerts.isValid(body(prio = 0)))
        assertFalse(AnmpAlerts.isValid(body(prio = 4)))
        assertFalse(AnmpAlerts.isValid(body(title = "x".repeat(201))))
        assertTrue(AnmpAlerts.isValid(body(title = "x".repeat(200))))
        assertFalse(AnmpAlerts.isValid(body(text = "ä".repeat(2049))))
        assertTrue(AnmpAlerts.isValid(body(text = "x".repeat(4096))))
        assertFalse(AnmpAlerts.isValid(body(url = "javascript:alert(1)")))
        assertFalse(AnmpAlerts.isValid(body(url = "ftp://cam.local/")))
        assertTrue(AnmpAlerts.isValid(body(url = "HTTPS://cam.local/")))
        assertFalse(AnmpAlerts.isValid(body(attachments = List(6) { att })))
        assertFalse(AnmpAlerts.isValid(body(replaces = ByteArray(7))))
        assertFalse(AnmpAlerts.isValid(AnmpAlertBody(ByteArray(7), 1, 1, text = "x")))
        assertFailsWith<IllegalArgumentException> { AnmpAlerts.encodeBody(body(prio = 9)) }
        // At most 10 MiB plaintext per attachment.
        val big = AnmpAttachment(ByteArray(32), ByteArray(32), "pdf", size = AnmpAlerts.MAX_ATTACHMENT_BYTES)
        assertTrue(AnmpAlerts.isValid(body(attachments = listOf(big))))
        assertFalse(AnmpAlerts.isValid(body(attachments = listOf(att, AnmpAttachment(ByteArray(32), ByteArray(32), "pdf", size = AnmpAlerts.MAX_ATTACHMENT_BYTES + 1)))))
        assertFalse(AnmpAlerts.isValid(body(attachments = listOf(AnmpAttachment(ByteArray(32), ByteArray(32), "jpg", size = -1)))))
    }

    @Test
    fun areaOnlyInCuratedChannels() = runTest {
        val regional = AnmpAlertBody(Fixtures.alertId, 1_760_000_000, AnmpAlerts.WARNING, text = "Hochwasser", area = listOf("06412"))
        assertTrue(AnmpAlerts.isValid(regional))
        assertTrue(AnmpAlerts.isValidIn(regional, curated = true))
        assertFalse(AnmpAlerts.isValidIn(regional, curated = false))
        assertTrue(AnmpAlerts.isValidIn(body(), curated = false))
        // A body over the attachment limit does not open, whoever signed it.
        val sender = AnmpSpaceKey.fromPrivateKey(Fixtures.senderSeed)
        val bodyBytes = Anmp.cbor.encodeToByteArray(
            AnmpAlertBody.serializer(),
            body(attachments = listOf(AnmpAttachment(ByteArray(32), ByteArray(32), "pdf", size = AnmpAlerts.MAX_ATTACHMENT_BYTES + 1))),
        )
        val signed = Anmp.cbor.encodeToByteArray(AnmpAlerts.Signed.serializer(), AnmpAlerts.Signed(bodyBytes, sender.publicKey, sender.sign(AnmpAlerts.signingBytes(bodyBytes))))
        assertNull(AnmpAlerts.verify(signed))
    }

    @Test
    fun incidentKey() = runTest {
        val k = AnmpAlerts.alertKey("keller-wasser")
        assertEquals(AnmpAlerts.ID_BYTES, k.size)
        assertEquals(k.toHex(), AnmpAlerts.alertKey("keller-wasser").toHex())
        assertFalse(k.contentEquals(AnmpAlerts.alertKey("keller-wasser2")))
        assertTrue(AnmpAlerts.isValid(AnmpAlertBody(Fixtures.alertId, 1, AnmpAlerts.ALARM, text = "x", key = k)))
        assertTrue(AnmpAlerts.isValid(AnmpAlertBody(Fixtures.alertId, 1, AnmpAlerts.INFO, text = "x", key = k, ended = true)))
        assertFalse(AnmpAlerts.isValid(AnmpAlertBody(Fixtures.alertId, 1, AnmpAlerts.INFO, text = "x", key = ByteArray(7))))
        assertFailsWith<IllegalArgumentException> { AnmpAlerts.alertKey("") }
        assertFailsWith<IllegalArgumentException> { AnmpAlerts.alertKey("x".repeat(AnmpAlerts.MAX_KEY_BYTES + 1)) }
        val decoded = AnmpAlerts.decodeBody(AnmpAlerts.encodeBody(AnmpAlertBody(Fixtures.alertId, 1, AnmpAlerts.INFO, text = "x", key = k)))
        assertEquals(k.toHex(), decoded?.key?.toHex())
    }

    @Test
    fun defaultsAreOmitted() {
        // id, ts, prio and text only: a map of four entries.
        val b = AnmpAlerts.encodeBody(AnmpAlertBody(Fixtures.alertId, 1, AnmpAlerts.INFO, text = "x"))
        assertEquals(0xA4, b[0].toInt() and 0xff)
        val ended = AnmpAlerts.encodeBody(body(replaces = Fixtures.alertId, ended = true, title = null))
        assertEquals(0xA6, ended[0].toInt() and 0xff)
    }

    @Test
    fun emptyTextIsWrittenAndRead() {
        val att = AnmpAttachment(ByteArray(32), ByteArray(32), "file", size = 0)
        val b = AnmpAlerts.encodeBody(AnmpAlertBody(Fixtures.alertId, 1, AnmpAlerts.INFO, text = "", attachments = listOf(att)))
        // id, ts, prio, text "", attachments; the attachment keeps type and size 0.
        assertEquals(0xA5, b[0].toInt() and 0xff)
        assertTrue(b.toHex().contains("0460"))
        assertTrue(b.toHex().contains("0400"))
        val back = assertNotNull(AnmpAlerts.decodeBody(b))
        assertEquals("", back.text)
        assertEquals(0L, back.attachments!!.single().size)
    }

    @Test
    fun channelLink() {
        val invite = AnmpChannelInvite(Fixtures.networkId, AnmpKeys.encode(ByteArray(32) { 7 }), "Haus", Fixtures.readKey, Fixtures.READ_GRANT, curated = true)
        val link = AnmpAlerts.channelLink("anynear.net", invite)
        assertTrue(link.startsWith("https://anynear.net/a#"))
        val back = assertNotNull(AnmpAlerts.parseChannelLink("Schau mal: $link !", "anynear.net"))
        assertEquals(invite.space, back.space)
        assertEquals("Haus", back.name)
        assertContentEquals(Fixtures.readKey, back.readKey)
        assertEquals(Fixtures.READ_GRANT, back.grant)
        assertTrue(back.curated)
        assertFalse(AnmpAlerts.parseChannelLink(AnmpAlerts.channelLink("anynear.net", AnmpChannelInvite(Fixtures.networkId, invite.space, "x", Fixtures.readKey, "g")), "anynear.net")!!.curated)
        assertNull(AnmpAlerts.parseChannelLink(link, "example.org"))
        assertNull(AnmpAlerts.parseChannelLink(link.replace("/a#", "/k#"), "anynear.net"))
        assertNull(AnmpAlerts.parseChannelLink("https://anynear.net/a#AAAA", "anynear.net"))
    }

    @Test
    fun ingressRecord() = runTest {
        val token = AnmpAlerts.newToken()
        assertEquals(43, token.length)
        val raw = assertNotNull(AnmpAlerts.tokenBytes(token))
        assertNull(AnmpAlerts.tokenBytes(token.dropLast(1)))
        val record = AnmpIngressRecord(1, "space", AnmpAlerts.readPublicKey(Fixtures.readKey), Fixtures.senderSeed, Fixtures.SENDER_GRANT, "Home Assistant")
        val sealed = AnmpAlerts.sealIngress(raw, record)
        val back = assertNotNull(AnmpAlerts.openIngress(raw, sealed))
        assertEquals("Home Assistant", back.label)
        assertContentEquals(Fixtures.senderSeed, back.senderSeed)
        assertNull(AnmpAlerts.openIngress(Fixtures.token, sealed))
        // Empty label: omitted on the wire, read back as "".
        val bare = AnmpIngressRecord(1, "space", record.readPublicKey, Fixtures.senderSeed, Fixtures.SENDER_GRANT)
        assertEquals(0xA5, Anmp.cbor.encodeToByteArray(AnmpIngressRecord.serializer(), bare)[0].toInt() and 0xff)
        assertEquals("", assertNotNull(AnmpAlerts.openIngress(raw, AnmpAlerts.sealIngress(raw, bare))).label)
        assertEquals(AnmpAlerts.ingressSpace(raw).space, AnmpAlerts.ingressSpace(raw).space)
        assertEquals("https://alerts.anynear.app/$token", AnmpAlerts.ingressUrl("alerts.anynear.app", token))
    }
}
