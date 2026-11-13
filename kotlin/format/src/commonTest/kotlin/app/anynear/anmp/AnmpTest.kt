package app.anynear.anmp

import app.anynear.anmp.Fixtures.messageId
import app.anynear.anmp.Fixtures.tagA
import app.anynear.anmp.Fixtures.tagB
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Packet format: CBOR, AEAD, signature, radio sizes (from AnmpTest in the app). */
class AnmpTest {
    private val any = AnmpVerifier.any

    @Test
    fun headerBytes() {
        assertEquals("a3000101480102030405060708" + "0302", Anmp.encodeHeader(AnmpHeader(AnmpKind.CIRCLE, tagA, epoch = 2)).toHex())
        assertEquals("a3000201480102030405060708" + "02481112131415161718", Anmp.encodeHeader(AnmpHeader(AnmpKind.DIRECT, tagA, tagB)).toHex())
    }

    @Test
    fun contentBytes() {
        val c = AnmpContent(id = messageId, ts = 1_760_000_000, text = "Hallo")
        assertEquals("a3" + "0048a0a1a2a3a4a5a6a7" + "011a68e77800" + "026548616c6c6f", Anmp.encodeContent(c).toHex())
    }

    @Test
    fun circleRoundTripWithSignature() = runTest {
        val me = Fixtures.deviceA()
        val keys = AnmpCircleKeys.derive(Fixtures.circleSecret)
        val packet = Anmp.encode(AnmpHeader(AnmpKind.CIRCLE, me.tag, epoch = 0), AnmpContent(id = messageId, ts = 1, text = "Bin gleich da"), "circle-1", keys.cipher, me)

        assertTrue(Anmp.isPmp(packet))
        val verify = AnmpVerifier.withKey(me.signKey)
        val msg = assertNotNull(Anmp.decode(packet, "circle-1", keys.cipher, verify, requireSignature = true))
        assertEquals(AnmpKind.CIRCLE, msg.header.kind)
        assertEquals("Bin gleich da", msg.content.text)
        assertContentEquals(messageId, msg.content.id)
        assertTrue(msg.signed)

        assertNull(Anmp.decode(packet, "circle-2", keys.cipher, verify, requireSignature = true), "other circle")
        assertNull(Anmp.decode(packet, "circle-1", AnmpCircleKeys.derive(ByteArray(32) { 7 }).cipher, verify, false), "other key")
        assertNull(Anmp.decode(packet, "circle-1", keys.cipher, AnmpVerifier.none, false), "wrong signature never counts")
        val tampered = packet.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertNull(Anmp.decode(tampered, "circle-1", keys.cipher, verify, false))
    }

    @Test
    fun openedPacketIsVerifiableWithoutKey() = runTest {
        val me = Fixtures.deviceA()
        val keys = AnmpCircleKeys.derive(Fixtures.circleSecret)
        val encoded = Anmp.encodeFull(AnmpHeader(AnmpKind.CIRCLE, me.tag), AnmpContent(id = messageId, text = "Verlauf"), "c", keys.cipher, me)
        val msg = assertNotNull(Anmp.decodeOpened(encoded.opened, "c", AnmpVerifier.withKey(me.signKey), requireSignature = true))
        assertEquals("Verlauf", msg.content.text)
        assertContentEquals(encoded.opened, msg.opened)
        assertNull(Anmp.decodeOpened(encoded.opened, "other", AnmpVerifier.withKey(me.signKey), requireSignature = true))
    }

    @Test
    fun unsignedOnlyWhereAllowed() = runTest {
        val keys = AnmpCircleKeys.derive(Fixtures.circleSecret)
        val packet = Anmp.encode(AnmpHeader(AnmpKind.CIRCLE, tagA), AnmpContent(text = "ohne Signatur"), "k", keys.cipher)
        assertNotNull(Anmp.peek(packet))
        assertNull(Anmp.decode(packet, "k", keys.cipher, any, requireSignature = true))
        val msg = assertNotNull(Anmp.decode(packet, "k", keys.cipher, any, requireSignature = false))
        assertEquals("ohne Signatur", msg.content.text)
        assertFalse(msg.signed)
    }

    @Test
    fun directOnlyForTheRecipient() = runTest {
        val a = Fixtures.deviceA()
        val b = Fixtures.deviceB()
        val c = AnmpDevice.of(AnmpKeys.randomKey(), AnmpKeys.randomKey())
        val packet = Anmp.encode(
            AnmpHeader(AnmpKind.DIRECT, a.tag, b.tag),
            AnmpContent(id = messageId, text = "Nur für dich"),
            Anmp.DIRECT_SCOPE,
            a.directCipher(b.deviceId, b.publicKey),
        )
        assertContentEquals(b.tag, assertNotNull(Anmp.peek(packet)).to)
        assertEquals("Nur für dich", Anmp.decode(packet, "", b.directCipher(a.deviceId, a.publicKey), AnmpVerifier.none, false)?.content?.text)
        // Someone on the same radio who is not addressed cannot read it.
        assertNull(Anmp.decode(packet, "", c.directCipher(a.deviceId, a.publicKey), AnmpVerifier.none, false))
    }

    @Test
    fun reactionCanBeWithdrawn() = runTest {
        val keys = AnmpCircleKeys.derive(Fixtures.circleSecret)
        val packet = Anmp.encode(AnmpHeader(AnmpKind.REACT, tagA), AnmpContent(id = messageId, ref = messageId, refFrom = tagB, emoji = ""), "k", keys.cipher)
        val msg = assertNotNull(Anmp.decode(packet, "k", keys.cipher, any, false))
        assertEquals("", msg.content.emoji)
        assertContentEquals(tagB, msg.content.refFrom)
    }

    @Test
    fun foreignAndUnknownDataIsRejected() {
        assertNull(Anmp.peek("Hallo".encodeToByteArray()))
        assertNull(Anmp.peek(byteArrayOf(0x50, 0x52, 0x01, 0, 0, 0, 1)), "old read receipt (PR) is no ANMP")
        assertNull(Anmp.peek(Anmp.MAGIC + byteArrayOf(0x01, 0x02)), "broken")
        val unknownKind = Anmp.MAGIC + Anmp.cbor.encodeToByteArray(
            Anmp.Packet.serializer(),
            Anmp.Packet(Anmp.cbor.encodeToByteArray(Anmp.Header.serializer(), Anmp.Header(999, tagA)), ByteArray(30)),
        )
        assertNull(Anmp.peek(unknownKind), "unknown kind of a newer app is skipped")
        val shortTag = Anmp.MAGIC + Anmp.cbor.encodeToByteArray(
            Anmp.Packet.serializer(),
            Anmp.Packet(Anmp.cbor.encodeToByteArray(Anmp.Header.serializer(), Anmp.Header(1, ByteArray(7))), ByteArray(30)),
        )
        assertNull(Anmp.peek(shortTag), "wrong tag length")
    }

    @Test
    fun unknownContentFieldsAreSkipped() {
        // {0: id, 2: "Hi", 99: 1}
        val bytes = ("a3" + "0048a0a1a2a3a4a5a6a7" + "02624869" + "186301").hex()
        val c = assertNotNull(Anmp.decodeContent(bytes))
        assertEquals("Hi", c.text)
    }

    @Test
    fun radioTextBudgets() {
        // Values as in ADR 0021.
        fun circle(text: String) = AnmpHeader(AnmpKind.CIRCLE, tagA, epoch = 3) to AnmpContent(id = messageId, ts = 1_760_000_000, text = text)
        val signed = budget { t -> circle(t).let { Anmp.size(it.first, it.second, signed = true) } }
        val unsigned = budget { t -> circle(t).let { Anmp.size(it.first, it.second, signed = false) } }
        val direct = budget { t -> Anmp.size(AnmpHeader(AnmpKind.DIRECT, tagA, tagB), AnmpContent(id = messageId, ts = 1_760_000_000, text = t), signed = false) }
        assertEquals(94, signed)
        assertEquals(160, unsigned)
        assertEquals(152, direct)
        assertEquals(signed, Anmp.maxTextBytes(circle("").first, signed = true) { circle(it).second })
    }

    @Test
    fun sizeMatchesRealPacket() = runTest {
        val me = Fixtures.deviceA()
        val keys = AnmpCircleKeys.derive(Fixtures.circleSecret)
        for (text in listOf("", "kurz", "x".repeat(30), "ä".repeat(60))) {
            val header = AnmpHeader(AnmpKind.CIRCLE, me.tag, epoch = 1)
            val content = AnmpContent(id = messageId, ts = 1_760_000_000, text = text)
            assertEquals(Anmp.encode(header, content, "k", keys.cipher, me).size, Anmp.size(header, content, signed = true), text)
            assertEquals(Anmp.encode(header, content, "k", keys.cipher).size, Anmp.size(header, content, signed = false), text)
        }
    }

    /** Longest ASCII text whose packet still fits the radio. */
    private fun budget(size: (String) -> Int): Int = (0..Anmp.RADIO_MAX_BYTES).last { size("x".repeat(it)) <= Anmp.RADIO_MAX_BYTES }
}
