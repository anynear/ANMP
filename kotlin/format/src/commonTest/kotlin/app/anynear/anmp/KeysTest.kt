package app.anynear.anmp

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Key derivation, identifiers, internet signature rule, blobs (from OnlineCryptoTest, DecentralTest, ForwardBoxTest). */
class KeysTest {
    @Test
    fun pairCodeIsSixDigitsAndDependsOnSecretAndDevice() = runTest {
        // ADR 0093: same input, same code on both devices; another secret or another device, another code.
        val secret = ByteArray(32) { it.toByte() }
        val sign = ByteArray(32) { (it + 100).toByte() }
        val code = AnmpKeys.pairCode(secret, sign)
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
        assertEquals(code, AnmpKeys.pairCode(secret.copyOf(), sign.copyOf()))
        assertNotEquals(code, AnmpKeys.pairCode(ByteArray(32) { 7 }, sign))
        assertNotEquals(code, AnmpKeys.pairCode(secret, ByteArray(32) { 9 }))
    }

    @Test
    fun deviceIdBelongsToItsSigningKey() = runTest {
        val a = Fixtures.deviceA()
        assertContentEquals(a.deviceId, AnmpKeys.deviceIdOf(a.signKey))
        assertContentEquals(a.deviceId, AnmpKeys.deviceIdOfText(AnmpKeys.encode(a.signKey)))
        assertEquals(16, a.deviceId.size)
        assertEquals(22, a.deviceIdText.length)
        assertContentEquals(a.deviceId.copyOf(8), a.tag)
        assertContentEquals(a.deviceId, Fixtures.deviceA().deviceId, "same keys, same ID")
        assertNull(AnmpKeys.deviceIdOfText("kaputt"))
    }

    @Test
    fun directKeyIsSameInBothDirections() = runTest {
        val a = Fixtures.deviceA()
        val b = Fixtures.deviceB()
        assertContentEquals(a.directKey(b.deviceId, b.publicKey), b.directKey(a.deviceId, a.publicKey))
        val c = AnmpDevice.of(AnmpKeys.randomKey(), AnmpKeys.randomKey())
        assertFalse(a.directKey(c.deviceId, c.publicKey).contentEquals(a.directKey(b.deviceId, b.publicKey)))
    }

    @Test
    fun circleKeysAreSeparate() = runTest {
        val keys = AnmpCircleKeys.derive(Fixtures.circleSecret)
        val other = AnmpCircleKeys.derive(ByteArray(32) { 7 })
        assertNotEquals(keys.space, other.space)
        assertEquals(43, keys.space.length)
        val aad = "space\nid1".encodeToByteArray()
        val sealed = keys.cipher.seal("Hallo".encodeToByteArray(), aad)
        assertContentEquals("Hallo".encodeToByteArray(), keys.cipher.open(sealed, aad))
        assertNull(keys.cipher.open(sealed, "space\nid2".encodeToByteArray()), "other ID")
        assertNull(other.cipher.open(sealed, aad), "other circle")
        val sig = keys.spaceKey.sign("x".encodeToByteArray())
        assertTrue(AnmpVerifier.withKey(keys.spaceKey.publicKey).verify(AnmpHeader(AnmpKind.CIRCLE, Fixtures.tagA), AnmpContent(), "x".encodeToByteArray(), sig))
    }

    @Test
    fun legacyChannelNamesAreRecognizedForRemoval() {
        assertTrue(AnmpKeys.isLegacyChannelName(AnmpInfo.CHANNEL_PREFIX + "K3V9Q"))
        assertFalse(AnmpKeys.isLegacyChannelName(AnmpInfo.CHANNEL_PREFIX + "K3V9"), "too short")
        assertFalse(AnmpKeys.isLegacyChannelName(AnmpInfo.CHANNEL_PREFIX + "K3V0Q"), "0 is not in the alphabet")
        assertFalse(AnmpKeys.isLegacyChannelName("anynear"))
        assertFalse(AnmpKeys.isLegacyChannelName(null))
    }

    @Test
    fun envelopeHidesThePacketPerSpace() = runTest {
        val keys = AnmpCircleKeys.derive(Fixtures.circleSecret)
        val packet = "PM\u0001packet".encodeToByteArray()
        val env = keys.envelope.seal(packet, keys.space)
        assertContentEquals(packet, keys.envelope.open(env, keys.space))
        assertNull(keys.envelope.open(env, "other-space"), "bound to its space")
        assertNull(AnmpEnvelope.circle(ByteArray(32) { 7 }).open(env, keys.space), "other circle")
        // Own key, not the circle key (no key reuse between packet and envelope).
        assertNull(keys.cipher.open(env, AnmpEnvelope.aad(keys.space)))
        val a = Fixtures.deviceA()
        val inbox = AnmpEnvelope.inbox(a.publicKey)
        val owner = AnmpSpaceKey.fromPrivateKey(Fixtures.inboxOwner)
        val sealed = inbox.seal(packet, owner.space)
        assertContentEquals(packet, AnmpEnvelope.inbox(a.publicKey).open(sealed, owner.space))
        assertNull(AnmpEnvelope.inbox(Fixtures.deviceB().publicKey).open(sealed, owner.space))
    }

    @Test
    fun inboxSpaceComesFromTheGivenOwnerKey() = runTest {
        val owner = AnmpSpaceKey.fromPrivateKey(Fixtures.inboxOwner)
        assertEquals(AnmpSpaceKey.spaceOf(owner.publicKey), owner.space)
        assertContentEquals(owner.publicKey, AnmpKeys.decodeOrNull(owner.space))
        // Not derivable from the device keys any more (ADR 0035 §11).
        val a = Fixtures.deviceA()
        assertNotEquals(AnmpSpaceKey.spaceOf(a.signKey), owner.space)
    }

    @Test
    fun localStorageKeyIsPerDevice() = runTest {
        val a = Fixtures.deviceA().localStorageKey()
        assertEquals(32, a.size)
        assertContentEquals(a, AnmpKeys.localStorageKey(Fixtures.xA))
        assertFalse(a.contentEquals(Fixtures.deviceB().localStorageKey()))
    }

    private fun hello(device: AnmpDevice, signKey: ByteArray = device.signKey) =
        AnmpContent(ts = 1, text = "Hallo", name = "Anna", key = device.publicKey, signKey = signKey)

    @Test
    fun internetRuleChecksDeviceAndScope() = runTest {
        val anna = Fixtures.deviceA()
        val ben = Fixtures.deviceB()
        val k = AnmpCircleKeys.derive(Fixtures.circleSecret)
        val ok = Anmp.encode(AnmpHeader(AnmpKind.CIRCLE, anna.tag), hello(anna), "circle", k.cipher, anna)
        assertNotNull(Anmp.decode(ok, "circle", k.cipher, AnmpVerifier.fromContent, requireSignature = true))
        assertNull(Anmp.decode(ok, "other-circle", k.cipher, AnmpVerifier.fromContent, true), "cannot be moved to another circle")
        // Ben signs with his key but claims to be Anna (Anna's tag in the header).
        val forged = Anmp.encode(AnmpHeader(AnmpKind.CIRCLE, anna.tag), hello(anna, ben.signKey), "circle", k.cipher, ben)
        assertNull(Anmp.decode(forged, "circle", k.cipher, AnmpVerifier.fromContent, true), "foreign device ID")
        val unsigned = Anmp.encode(AnmpHeader(AnmpKind.CIRCLE, anna.tag), hello(anna), "circle", k.cipher)
        assertNull(Anmp.decode(unsigned, "circle", k.cipher, AnmpVerifier.fromContent, true), "unsigned never counts over the internet")
    }

    @Test
    fun internetDirectCarriesSenderKeys() = runTest {
        val anna = Fixtures.deviceA()
        val ben = Fixtures.deviceB()
        val header = AnmpHeader(AnmpKind.DIRECT, anna.tag, ben.tag, senderKey = anna.publicKey, senderDevice = anna.deviceId)
        val packet = Anmp.encode(header, hello(anna), Anmp.DIRECT_SCOPE, anna.directCipher(ben.deviceId, ben.publicKey), anna)
        val h = assertNotNull(Anmp.peek(packet))
        val cipher = ben.directCipher(assertNotNull(h.senderDevice), assertNotNull(h.senderKey))
        assertEquals("Hallo", Anmp.decode(packet, "", cipher, AnmpVerifier.fromContent, true)?.content?.text)
        // senderDevice of someone else does not match the signing key.
        val wrong = AnmpHeader(AnmpKind.DIRECT, anna.tag, ben.tag, senderKey = anna.publicKey, senderDevice = ben.deviceId)
        val bad = Anmp.encode(wrong, hello(anna), "", anna.directCipher(ben.deviceId, ben.publicKey), anna)
        assertNull(Anmp.decode(bad, "", cipher, AnmpVerifier.fromContent, true))
    }

    @Test
    fun blobIdIsTheHashOfItsBytes() = runTest {
        val key = AnmpBlob.newKey()
        val blob = AnmpBlob.seal(key, "Bild".encodeToByteArray())
        assertEquals(32, blob.id.size)
        assertEquals(43, blob.idText.length)
        assertContentEquals(AnmpBlob.idOf(blob.bytes), blob.id)
        assertContentEquals("Bild".encodeToByteArray(), AnmpBlob.open(key, blob.id, blob.bytes))
        assertNull(AnmpBlob.open(AnmpBlob.newKey(), blob.id, blob.bytes), "other key")
        val tampered = blob.bytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertNull(AnmpBlob.open(key, blob.id, tampered), "hash does not match")
        assertNull(AnmpBlob.open(key, AnmpBlob.idOf(tampered), tampered), "tag does not match")
    }

    @Test
    fun attachmentsCarryHashIds() = runTest {
        val blob = AnmpBlob.seal(Fixtures.blobKey, "pdf".encodeToByteArray())
        val c = AnmpContent(attachments = listOf(AnmpAttachment(blob.id, Fixtures.blobKey, "pdf", "a.pdf", 3)))
        val back = assertNotNull(Anmp.decodeContent(Anmp.encodeContent(c)))
        assertContentEquals(blob.id, back.attachments!!.single().id)
    }

    @Test
    fun mailboxPairKeyIsTheSameOnBothSides() = runTest {
        val a = Fixtures.deviceA()
        val mailbox = AnmpMailbox.of(Fixtures.inboxOwner)
        val inbox = AnmpSpaceKey.fromPrivateKey(Fixtures.inboxOwner)
        assertContentEquals(AnmpKeys.mailboxIdOf(inbox.publicKey), mailbox.id)
        assertContentEquals(a.directKey(mailbox.id, mailbox.publicKey), mailbox.directKey(a.deviceId, a.publicKey))
        assertEquals(8, mailbox.tag.size)
        // Not the same as any device key derived from the same seed.
        assertFalse(mailbox.publicKey.contentEquals(a.publicKey))
    }
}
