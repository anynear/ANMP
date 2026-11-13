package app.anynear.anmp

import app.anynear.anmp.Fixtures.messageId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One radio channel, hourly tags, public form, ask and patron (ADR 0051). */
class RadioTest {
    private val hour = AnmpRadio.hourOf(1_762_000_000)

    @Test
    fun hours() {
        assertEquals(489_444, AnmpRadio.hourOf(1_762_000_000))
        assertEquals(-1, AnmpRadio.hourOf(-1))
        assertContentEquals(longArrayOf(hour - 1, hour, hour + 1), AnmpRadio.acceptedHours(1_762_000_000))
    }

    @Test
    fun channelPskIsFixed() = runTest {
        val psk = AnmpRadio.channelPsk()
        assertEquals(32, psk.size)
        assertContentEquals(psk, AnmpRadio.channelPsk())
    }

    @Test
    fun tagsChangeEveryHourAndDifferByUse() = runTest {
        val device = Fixtures.deviceA().deviceId
        val secret = AnmpRadio.deviceSecret(device)
        val route = AnmpRadio.routeTag(secret, hour)
        val sender = AnmpRadio.senderTag(secret, hour)
        assertEquals(Anmp.ROUTE_TAG_BYTES, route.size)
        assertEquals(Anmp.TAG_BYTES, sender.size)
        assertContentEquals(route, AnmpRadio.routeTag(secret, hour), "deterministic")
        assertFalse(route.contentEquals(AnmpRadio.routeTag(secret, hour + 1)), "changes every hour")
        assertFalse(route.contentEquals(sender), "route and sender tag are unlinkable")
        assertFalse(route.contentEquals(Anmp.tagOf(device).copyOf(Anmp.ROUTE_TAG_BYTES)), "no fixed tag")

        val circle = AnmpRadio.circleSecret(Fixtures.circleSecret)
        assertFalse(circle.contentEquals(secret))
        assertFalse(AnmpRadio.routeTag(circle, hour).contentEquals(route))
    }

    @Test
    fun askTags() {
        val route = ByteArray(8) { it.toByte() }
        val ask = AnmpRadio.askTag(route)
        assertEquals(Anmp.ASK_TAG_BYTES, ask.size)
        assertTrue(AnmpRadio.matchesAsk(route, ask))
        assertFalse(AnmpRadio.matchesAsk(route, ByteArray(6) { 9 }))
        assertFalse(AnmpRadio.matchesAsk(route, ByteArray(5)))
    }

    @Test
    fun publicCircleRoundTrip() = runTest {
        val me = Fixtures.deviceA()
        val keys = AnmpCircleKeys.derive(Fixtures.circleSecret)
        val route = AnmpRadio.routeTag(AnmpRadio.circleSecret(Fixtures.circleSecret), hour)
        val sender = AnmpRadio.senderTag(AnmpRadio.deviceSecret(me.deviceId), hour)
        val header = AnmpHeader(AnmpKind.CIRCLE, sender, epoch = 0, route = route)
        val packet = Anmp.encode(header, AnmpContent(id = messageId, ts = 1, text = "Bin gleich da"), Fixtures.CIRCLE_ID, keys.cipher, me)

        val peeked = assertNotNull(Anmp.peek(packet))
        assertContentEquals(route, peeked.route)
        assertContentEquals(sender, peeked.from)
        assertNull(peeked.to)
        val msg = assertNotNull(Anmp.decode(packet, Fixtures.CIRCLE_ID, keys.cipher, AnmpVerifier.withKey(me.signKey), requireSignature = true))
        assertEquals("Bin gleich da", msg.content.text)
        assertTrue(Anmp.fitsRadio(packet))
    }

    @Test
    fun publicDirectRoundTrip() = runTest {
        val a = Fixtures.deviceA()
        val b = Fixtures.deviceB()
        val route = AnmpRadio.routeTag(AnmpRadio.deviceSecret(b.deviceId), hour)
        val sender = AnmpRadio.senderTag(AnmpRadio.deviceSecret(a.deviceId), hour)
        val packet = Anmp.encode(
            AnmpHeader(AnmpKind.DIRECT, sender, route = route),
            AnmpContent(id = messageId, ts = 1, text = "Hallo"),
            Anmp.DIRECT_SCOPE,
            a.directCipher(b.deviceId, b.publicKey),
        )
        val msg = assertNotNull(Anmp.decode(packet, Anmp.DIRECT_SCOPE, b.directCipher(a.deviceId, a.publicKey), AnmpVerifier.none, false))
        assertEquals("Hallo", msg.content.text)
        assertContentEquals(route, msg.header.route)
    }

    @Test
    fun publicFormBudget() = runTest {
        val tag = ByteArray(8)
        val circle = AnmpHeader(AnmpKind.CIRCLE, tag, epoch = 0, route = tag)
        val direct = AnmpHeader(AnmpKind.DIRECT, tag, route = tag)
        fun text(t: String) = AnmpContent(id = messageId, ts = 1_762_000_000, text = t)
        assertEquals(83, Anmp.maxTextBytes(circle, signed = true, ::text))
        assertEquals(149, Anmp.maxTextBytes(circle, signed = false, ::text))
        assertEquals(152, Anmp.maxTextBytes(direct, signed = false, ::text))
    }

    @Test
    fun askAndPatronAreOpen() = runTest {
        val sender = ByteArray(8) { 1 }
        val tags = arrayOf(ByteArray(6) { 2 }, ByteArray(6) { 3 })
        val ask = Anmp.encodeOpen(AnmpHeader(AnmpKind.ASK, sender, since = 1_762_000_000, askTags = tags))
        val h = assertNotNull(Anmp.peek(ask))
        assertEquals(AnmpKind.ASK, h.kind)
        assertEquals(1_762_000_000, h.since)
        assertEquals(2, h.askTags?.size)
        assertContentEquals(tags[1], h.askTags!![1])
        assertNull(Anmp.decode(ask, "", AnmpCircleKeys.derive(Fixtures.circleSecret).cipher, AnmpVerifier.any, false), "open packets are never decoded")

        val full = Anmp.encodeOpen(AnmpHeader(AnmpKind.ASK, sender, since = 1_762_000_000, askTags = Array(Anmp.ASK_MAX_TAGS) { ByteArray(6) }))
        assertTrue(Anmp.fitsRadio(full))
        assertTrue(full.size <= 90, "ask with all tags: ${full.size} bytes")

        val patron = Anmp.encodeOpen(AnmpHeader(AnmpKind.PATRON, sender, waiting = 3))
        assertEquals(3, Anmp.peek(patron)?.waiting)
        assertTrue(patron.size <= 24, "patron: ${patron.size} bytes")
    }

    @Test
    fun openKindsAreRejectedWhereNotAllowed() = runTest {
        val cipher = AnmpCircleKeys.derive(Fixtures.circleSecret).cipher
        assertFailsWith<IllegalArgumentException> { Anmp.encode(AnmpHeader(AnmpKind.ASK, ByteArray(8)), AnmpContent(), "", cipher) }
        assertFailsWith<IllegalArgumentException> { Anmp.encodeOpen(AnmpHeader(AnmpKind.CIRCLE, ByteArray(8))) }
        assertFailsWith<IllegalArgumentException> {
            Anmp.encodeOpen(AnmpHeader(AnmpKind.ASK, ByteArray(8), askTags = Array(Anmp.ASK_MAX_TAGS + 1) { ByteArray(6) }))
        }
        assertFailsWith<IllegalArgumentException> { Anmp.encode(AnmpHeader(AnmpKind.CIRCLE, ByteArray(8), route = ByteArray(6)), AnmpContent(), "", cipher) }
    }

    @Test
    fun pickupWindowCoversExactlyThirtyDays() = runTest {
        val secret = AnmpRadio.deviceSecret(Fixtures.deviceA().deviceId)
        val epoch = AnmpRadio.epochOf(hour)
        // In the middle of an epoch: one window.
        val mid = AnmpRadio.epochStart(epoch) + 2000
        val windows = AnmpRadio.pickupWindows(secret, mid)
        assertEquals(1, windows.size)
        val tags = AnmpRadio.windowTags(windows.single(), mid)
        assertEquals(AnmpRadio.WINDOW_HOURS.toInt(), tags.size)
        assertEquals(mid - AnmpRadio.WINDOW_HOURS + 1, tags.first().first)
        assertEquals(mid, tags.last().first)
        for ((h, t) in listOf(tags.first(), tags[300], tags.last())) assertContentEquals(AnmpRadio.routeTag(secret, h), t)
        val all = tags.map { it.second.toHex() }.toSet()
        assertFalse(AnmpRadio.routeTag(secret, mid + 1).toHex() in all, "keine Stunde danach")
        assertFalse(AnmpRadio.routeTag(secret, mid - AnmpRadio.WINDOW_HOURS).toHex() in all, "keine Stunde davor")
    }

    @Test
    fun pickupAcrossAnEpochBorderHasTwoWindows() = runTest {
        val secret = AnmpRadio.circleSecret(Fixtures.circleSecret)
        val h = AnmpRadio.epochStart(AnmpRadio.epochOf(hour)) + 100
        val windows = AnmpRadio.pickupWindows(secret, h)
        assertEquals(2, windows.size)
        val tags = windows.flatMap { AnmpRadio.windowTags(it, h) }
        assertEquals(AnmpRadio.WINDOW_HOURS.toInt(), tags.size)
        assertEquals((h - AnmpRadio.WINDOW_HOURS + 1..h).toList(), tags.map { it.first }.sorted())
        assertContentEquals(AnmpRadio.routeTag(secret, h - 500), tags.single { it.first == h - 500 }.second)
    }

    @Test
    fun pickupOpensOnlyForThePatron() = runTest {
        val clara = AnmpEphemeral.generate()
        val patron = AnmpEphemeral.generate()
        val other = AnmpEphemeral.generate()
        val secrets = listOf(AnmpRadio.deviceSecret(Fixtures.deviceA().deviceId), AnmpRadio.circleSecret(Fixtures.circleSecret))
        val windows = secrets.flatMap { AnmpRadio.pickupWindows(it, hour) }.take(AnmpRadio.PICKUP_MAX_WINDOWS)
        val packet = AnmpRadio.pickupPacket(ByteArray(8) { 1 }, clara, patron.publicKey, hour, windows)
        assertTrue(Anmp.fitsRadio(packet), "pickup mit ${windows.size} Fenstern: ${packet.size} Byte")
        val full = AnmpRadio.pickupPacket(ByteArray(8) { 1 }, clara, patron.publicKey, hour, List(AnmpRadio.PICKUP_MAX_WINDOWS) { windows.first() })
        assertTrue(Anmp.fitsRadio(full), "volles pickup: ${full.size} Byte")

        val opened = assertNotNull(AnmpRadio.openPickup(packet, patron))
        assertEquals(hour, opened.hour)
        assertEquals(windows.size, opened.windows.size)
        assertContentEquals(windows.first().b, opened.windows.first().b)
        assertNull(AnmpRadio.openPickup(packet, other), "anderer Pate")
        assertNull(Anmp.decode(packet, AnmpInfo.PICKUP, AnmpCircleKeys.derive(Fixtures.circleSecret).cipher, AnmpVerifier.any, false))
    }

    @Test
    fun patronAnnouncesItsOneTimeKey() = runTest {
        val patron = AnmpEphemeral.generate()
        val packet = Anmp.encodeOpen(AnmpHeader(AnmpKind.PATRON, ByteArray(8), senderKey = patron.publicKey))
        assertContentEquals(patron.publicKey, Anmp.peek(packet)!!.senderKey)
        assertTrue(packet.size <= 60, "patron mit Schlüssel: ${packet.size} Byte")
    }
}
