package app.anynear.anmp.core

import app.anynear.andp.B64
import app.anynear.anmp.Anmp
import app.anynear.anmp.AnmpContent
import app.anynear.anmp.AnmpKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Packets to a person's mailbox recipient (ADR 0107): no signature, the pair key proves the sending device. */
class PersonRecipientTest {
    private suspend fun identity() = CoreIdentity.loadOrCreate(MemorySecretStore())

    private fun mailboxOf(i: CoreIdentity) = Peer(B64.encode(i.mailbox.id), B64.encode(i.mailbox.publicKey), person = true)

    private fun contentOf(sender: CoreIdentity, signKey: ByteArray = sender.device.signKey) =
        AnmpContent(id = ByteArray(Anmp.ID_BYTES) { 1 }, ts = 1, text = "Hallo", key = sender.device.publicKey, signKey = signKey)

    @Test
    fun packetToThePersonIsUnsignedAndOpensWithItsSender() = runTest {
        val anna = identity()
        val ben = identity()
        val packet = Packets.encode(ben, AnmpKind.DIRECT, contentOf(ben), Anmp.DIRECT_SCOPE, null, mailboxOf(anna)).packet
        val opened = assertNotNull(Packets.open(anna, packet, Anmp.DIRECT_SCOPE, null) { null })
        assertFalse(opened.message.signed)
        assertEquals(ben.deviceId, opened.fromDevice)
        assertEquals(B64.encode(ben.device.publicKey), opened.fromKey)
        assertEquals("Hallo", opened.content.text)
    }

    @Test
    fun packetToADeviceStaysSigned() = runTest {
        val anna = identity()
        val ben = identity()
        val toDevice = Peer(anna.deviceId, B64.encode(anna.device.publicKey))
        val packet = Packets.encode(ben, AnmpKind.DIRECT, contentOf(ben), Anmp.DIRECT_SCOPE, null, toDevice).packet
        assertTrue(assertNotNull(Packets.open(anna, packet, Anmp.DIRECT_SCOPE, null) { null }).message.signed)
    }

    @Test
    fun claimingAnotherDeviceIsRejected() = runTest {
        val anna = identity()
        val ben = identity()
        val mallory = identity()
        // Mallory seals with her own keys but names Ben's signing key in content 9: it does not match header 5.
        val forged = Packets.encode(mallory, AnmpKind.DIRECT, contentOf(mallory, signKey = ben.device.signKey), Anmp.DIRECT_SCOPE, null, mailboxOf(anna)).packet
        assertNull(Packets.open(anna, forged, Anmp.DIRECT_SCOPE, null) { null })
    }

    @Test
    fun onlyThePersonOpensIt() = runTest {
        val anna = identity()
        val ben = identity()
        val carol = identity()
        val packet = Packets.encode(ben, AnmpKind.DIRECT, contentOf(ben), Anmp.DIRECT_SCOPE, null, mailboxOf(anna)).packet
        assertNull(Packets.open(carol, packet, Anmp.DIRECT_SCOPE, null) { null })
    }
}
