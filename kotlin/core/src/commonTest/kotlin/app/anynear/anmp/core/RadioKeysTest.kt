package app.anynear.anmp.core

import app.anynear.andp.B64
import app.anynear.anmp.Anmp
import app.anynear.anmp.AnmpCircleKeys
import app.anynear.anmp.AnmpContent
import app.anynear.anmp.AnmpDevice
import app.anynear.anmp.AnmpHeader
import app.anynear.anmp.AnmpKind
import app.anynear.anmp.AnmpRadio
import app.anynear.anmp.AnmpVerifier
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Radio packets in the public form (ADR 0021, 0051) with the device keys of the core. */
class RadioKeysTest {
    private val secret = B64.encode(ByteArray(32) { it.toByte() })

    private suspend fun device(seed: Int) = AnmpDevice.of(ByteArray(32) { (seed + it).toByte() }, ByteArray(32) { (seed * 7 + it).toByte() })

    private suspend fun keys(seed: Int, now: Long = 1_762_000_000L) = RadioKeys(device(seed)) { now }

    private fun circle(id: String, secret: String) = CircleState(id = id, name = "Familie", secret = secret)

    @Test
    fun ownPacketOfAnEarlierHourIsStillOwn() = runTest {
        // Written two hours ago, read back from the radio inbox now (hardware test 11.11.2026: "sender unknown").
        val then = 1_762_000_000L
        val earlier = keys(1, then)
        val now = keys(1, then + 2 * 3600)
        val tag = earlier.ownSenderTag()
        assertFalse(now.ownSenderTag().contentEquals(tag), "another hour, another tag")
        assertTrue(now.isOwnSender(tag, then), "at the time of the entry it is ours")
        assertFalse(keys(2, then).isOwnSender(tag, then), "not someone else's")
    }

    @Test
    fun circlePacketIsSignedAndOpensOnlyWithTheCircleKey() = runTest {
        val anna = keys(1)
        val me = device(1)
        val content = AnmpContent(id = ByteArray(8) { 1 }, ts = 1_700_000_000, text = "Hallo Kreis")
        val packet = anna.circlePacket(AnmpKind.CIRCLE, circle("kreis-1", secret), content)
        assertTrue(Anmp.fitsRadio(packet))

        val ben = keys(2)
        val at = 1_762_000_000_000L
        val opened = ben.openCircle(packet, circle("kreis-1", secret), AnmpVerifier.withKey(me.signKey), at)!!
        assertTrue(opened.signed)
        assertEquals("Hallo Kreis", opened.content.text)
        // Another circle (scope) or another key: nothing.
        assertNull(ben.openCircle(packet, circle("kreis-2", secret), AnmpVerifier.withKey(me.signKey), at))
        assertNull(ben.openCircle(packet, circle("kreis-1", B64.encode(ByteArray(32) { 5 })), AnmpVerifier.withKey(me.signKey), at))
        // Wrong signing key: invalid.
        assertNull(ben.openCircle(packet, circle("kreis-1", secret), AnmpVerifier.withKey(device(2).signKey), at))
    }

    @Test
    fun unsignedCirclePacketIsDropped() = runTest {
        // Every member knows the circle key: without the signature anyone of them could write as Anna (ADR 0021).
        val anna = device(1)
        val h = AnmpRadio.hourOf(1_762_000_000L)
        val header = AnmpHeader(AnmpKind.CIRCLE, AnmpRadio.senderTag(AnmpRadio.deviceSecret(anna.deviceId), h), epoch = 0,
            route = AnmpRadio.routeTag(AnmpRadio.circleSecret(B64.decode(secret)), h))
        val content = AnmpContent(id = ByteArray(8) { 2 }, ts = 1_700_000_000, text = "Ich bin Anna")
        val unsigned = Anmp.encode(header, content, "kreis-1", AnmpCircleKeys.derive(B64.decode(secret)).cipher, null)
        assertNull(keys(2).openCircle(unsigned, circle("kreis-1", secret), AnmpVerifier.withKey(anna.signKey), 1_762_000_000_000L))
    }

    @Test
    fun previousCircleKeyOpensOnlyDuringTheGracePeriod() = runTest {
        // After a key change the previous key still opens packets sent before (grace period), then no more: a removed
        // member keeps that key and must not write into the circle over radio for good (ADR 0021).
        val old = B64.encode(ByteArray(32) { (it + 9).toByte() })
        val at = 1_762_000_000_000L
        val packet = keys(1).circlePacket(AnmpKind.CIRCLE, circle("kreis-1", old), AnmpContent(id = ByteArray(8) { 3 }, ts = 1, text = "alt"))
        val changed = CircleState(id = "kreis-1", name = "Familie", secret = secret, epoch = 1, previousSecret = old, graceUntil = at + 1000)
        val verify = AnmpVerifier.withKey(device(1).signKey)
        assertEquals("alt", keys(2).openCircle(packet, changed, verify, at)?.content?.text)
        assertNull(keys(2).openCircle(packet, changed, verify, at + 1000))
        val route = Anmp.peek(packet)!!.route!!
        assertTrue(keys(2).table(listOf(changed), emptyList(), at / 1000).target(route) != null)
        assertNull(keys(2).table(listOf(changed), emptyList(), at / 1000 + 2).target(route))
    }

    @Test
    fun directPacketOpensOnlyForTheRecipient() = runTest {
        val now = 1_762_000_000L
        val anna = keys(1)
        val ben = keys(2)
        val clara = keys(3)
        val a = device(1)
        val b = device(2)
        val packet = anna.directPacket(AnmpKind.DIRECT, b.deviceIdText, B64.encode(b.publicKey), AnmpContent(id = ByteArray(8), ts = 1, text = "nur für Ben"))
        // Public form (ADR 0051): no fixed tag in the header, only the route tag of Ben's device.
        val header = Anmp.peek(packet)!!
        assertNull(header.to)
        assertTrue(ben.table(emptyList(), listOf(a.deviceIdText), now).isOwn(header.route!!))
        assertFalse(clara.table(emptyList(), listOf(a.deviceIdText), now).isOwn(header.route!!))
        assertEquals(a.deviceIdText, ben.table(emptyList(), listOf(a.deviceIdText), now).sender(header.from))
        assertEquals("nur für Ben", ben.openDirect(packet, a.deviceIdText, B64.encode(a.publicKey))!!.content.text)
        assertNull(clara.openDirect(packet, a.deviceIdText, B64.encode(a.publicKey)))
    }

    @Test
    fun inboxEntriesAreMatchedByTheHourTheyWereWritten() = runTest {
        // ADR 0051: an entry of the radio inbox carries the tags of the hour Anna sent it.
        val now = 1_762_000_000L
        val earlier = now - 5 * 3600
        val anna = keys(1, earlier)
        val ben = keys(2, now)
        val a = device(1)
        val b = device(2)
        val packet = anna.directPacket(AnmpKind.DIRECT, b.deviceIdText, B64.encode(b.publicKey), AnmpContent(id = ByteArray(8), ts = earlier, text = "vor fünf Stunden"))
        val header = Anmp.peek(packet)!!
        assertFalse(ben.table(emptyList(), listOf(a.deviceIdText), now).isOwn(header.route!!), "not recognizable with the tags of now")
        val then = ben.table(emptyList(), listOf(a.deviceIdText), earlier)
        assertTrue(then.isOwn(header.route!!))
        assertEquals(a.deviceIdText, then.sender(header.from))
    }
}
