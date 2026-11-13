package app.anynear.anmp.core

import app.anynear.anmp.Anmp
import app.anynear.anmp.AnmpCircleKeys
import app.anynear.anmp.AnmpContent
import app.anynear.anmp.AnmpEphemeral
import app.anynear.anmp.AnmpHeader
import app.anynear.anmp.AnmpKind
import app.anynear.anmp.AnmpRadio
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Delivery through patrons (ADR 0051): offer, pickup keys for 30 days, staying quiet, limits, asking. JVM only: virtual
 * time does not wait for the crypto of the JS target, which completes outside the test scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioRelayTest {
    private class FakeEnv(val test: TestScope) : RadioRelay.Env {
        var online = true
        var radio = true
        var serve = true
        val inbox = mutableListOf<RadioInboxPacket>()
        val uplinked = mutableListOf<ByteArray>()
        val sent = mutableListOf<Pair<ByteArray, Int?>>()
        val own = mutableListOf<ByteArray>()
        val delivered = mutableListOf<ByteArray>()
        val pickupsFor = mutableListOf<ByteArray>()
        private var polled = 0

        override fun nowMillis(): Long = START + test.testScheduler.currentTime
        override fun radioReady() = radio
        override fun online() = online
        override fun inboxReady() = true
        override fun mayServeOthers() = serve
        override suspend fun isOwnRoute(route: ByteArray, atSeconds: Long) = own.any { it.contentEquals(route) }
        override suspend fun uplink(packet: ByteArray): Boolean = uplinked.add(packet)
        override suspend fun pollInbox(wanted: (ByteArray) -> Boolean): List<RadioInboxPacket> =
            inbox.drop(polled).also { polled = inbox.size }
        override suspend fun deliverOwn(packet: ByteArray, atSeconds: Long) {
            delivered += packet
        }
        override suspend fun askPacket(since: Long) = Anmp.encodeOpen(AnmpHeader(AnmpKind.ASK, ByteArray(8), since = since))
        override suspend fun patronPacket(key: ByteArray) = Anmp.encodeOpen(AnmpHeader(AnmpKind.PATRON, ByteArray(8), senderKey = key))
        override suspend fun pickupPackets(patronKey: ByteArray): List<ByteArray> {
            pickupsFor += patronKey
            return listOf(byteArrayOf(1, 2, 3))
        }
        override suspend fun broadcast(packet: ByteArray, hopLimit: Int?): Boolean = sent.add(packet to hopLimit)
        override fun log(text: String) = Unit

        fun sentKinds() = sent.map { Anmp.peek(it.first)?.kind }
    }

    private val nowHour = AnmpRadio.hourOf(START / 1000)

    private suspend fun packet(route: ByteArray, n: Int): ByteArray =
        Anmp.encode(
            AnmpHeader(AnmpKind.CIRCLE, ByteArray(8), epoch = 0, route = route),
            AnmpContent(id = ByteArray(8) { n.toByte() }, ts = 1, text = "Message $n"),
            "c",
            AnmpCircleKeys.derive(ByteArray(32)).cipher,
        )

    private fun ask(since: Long = 0) = Anmp.peek(Anmp.encodeOpen(AnmpHeader(AnmpKind.ASK, ByteArray(8), since = since)))!!

    private fun patron(key: ByteArray? = null) = Anmp.peek(Anmp.encodeOpen(AnmpHeader(AnmpKind.PATRON, ByteArray(8), senderKey = key)))!!

    private val claraSecret = ByteArray(32) { 7 }

    private suspend fun claraRoute(hoursAgo: Long) = AnmpRadio.routeTag(claraSecret, nowHour - hoursAgo)

    /** Inbox entry for Clara from [hoursAgo] hours ago. */
    private suspend fun entry(hoursAgo: Long, n: Int) = RadioInboxPacket(packet(claraRoute(hoursAgo), n), START / 1000 - hoursAgo * 3600)

    /** Clara answers the patron of [env] with her pickup. */
    private suspend fun claraPickup(env: FakeEnv): ByteArray {
        val patronKey = assertNotNull(Anmp.peek(env.sent.single { Anmp.peek(it.first)?.kind == AnmpKind.PATRON }.first)?.senderKey)
        return AnmpRadio.pickupPacket(ByteArray(8), AnmpEphemeral.generate(), patronKey, nowHour, AnmpRadio.pickupWindows(claraSecret, nowHour))
    }

    @Test
    fun patronGetsThirtyDaysAndNothingElse() = runTest {
        val env = FakeEnv(this)
        val relay = RadioRelay(backgroundScope, env)
        env.inbox += entry(5, 1)
        env.inbox += entry(20 * 24, 2)
        env.inbox += entry(31 * 24, 3) // older than 30 days
        env.inbox += RadioInboxPacket(packet(ByteArray(8) { 1 }, 4), START / 1000) // someone else

        relay.onAsk(ask(), hops = 2, snr = 6f)
        advanceTimeBy(25.seconds)
        runCurrent()
        assertEquals(listOf<AnmpKind?>(AnmpKind.PATRON), env.sentKinds(), "first only the offer with a one-time key")

        relay.onPickup(claraPickup(env))
        val data = env.sent.drop(1)
        assertEquals(setOf(1, 2), data.map { Anmp.peek(it.first)!!.route!! }.map { r ->
            listOf(5L, 20L * 24).indexOfFirst { claraRoute(it).contentEquals(r) } + 1
        }.toSet(), "the packets from 5 hours and 20 days ago")
        assertTrue(data.all { it.second == 2 }, "hop limit = measured hops")
    }

    @Test
    fun pickupForAnotherPatronIsIgnored() = runTest {
        val env = FakeEnv(this)
        val relay = RadioRelay(backgroundScope, env)
        env.inbox += entry(1, 1)
        relay.onAsk(ask(), hops = 1, snr = 6f)
        advanceTimeBy(25.seconds)
        val foreign = AnmpRadio.pickupPacket(ByteArray(8), AnmpEphemeral.generate(), AnmpEphemeral.generate().publicKey, nowHour, AnmpRadio.pickupWindows(claraSecret, nowHour))
        relay.onPickup(foreign)
        assertEquals(1, env.sent.size, "only the own offer")
    }

    @Test
    fun anotherPatronFirstMeansSilence() = runTest {
        val env = FakeEnv(this)
        val relay = RadioRelay(backgroundScope, env)
        relay.onAsk(ask(), hops = 1, snr = -15f)
        advanceTimeBy(1.seconds)
        relay.onPatron(patron(AnmpEphemeral.generate().publicKey))
        advanceTimeBy(30.seconds)
        runCurrent()
        assertTrue(env.sent.isEmpty())
        assertTrue(env.pickupsFor.isEmpty(), "we did not ask, so no pickup")
    }

    @Test
    fun nobodyServesWithoutInternetOrWhenSwitchedOff() = runTest {
        val env = FakeEnv(this)
        val relay = RadioRelay(backgroundScope, env)
        env.serve = false
        relay.onAsk(ask(), hops = 1, snr = 0f)
        relay.onHeard(packet(claraRoute(0), 2))
        env.serve = true
        env.online = false
        relay.onAsk(ask(), hops = 1, snr = 0f)
        relay.onHeard(packet(claraRoute(0), 3))
        advanceTimeBy(30.seconds)
        runCurrent()
        assertTrue(env.sent.isEmpty())
        assertTrue(env.uplinked.isEmpty())
    }

    @Test
    fun uplinkOnlyOncePerPacket() = runTest {
        val env = FakeEnv(this)
        val relay = RadioRelay(backgroundScope, env)
        val p = packet(claraRoute(0), 1)
        relay.onHeard(p)
        relay.onHeard(p.copyOf())
        relay.onHeard(packet(claraRoute(0), 2))
        assertEquals(2, env.uplinked.size)
    }

    @Test
    fun patronageGoesOnAndKeepsTheHourlyLimit() = runTest {
        val env = FakeEnv(this)
        val relay = RadioRelay(backgroundScope, env)
        relay.start()
        relay.onAsk(ask(), hops = 1, snr = 10f)
        advanceTimeBy(25.seconds)
        relay.onPickup(claraPickup(env))
        // New packets for Clara come one by one (tag of the current hour); at most 20 per hour in all.
        repeat(30) {
            env.inbox += RadioInboxPacket(packet(claraRoute(0), 100 + it), START / 1000 + 100 + it)
            advanceTimeBy(31.seconds)
        }
        runCurrent()
        assertEquals(RadioRelay.MAX_PER_HOUR, env.sentKinds().count { it == AnmpKind.CIRCLE })
    }

    @Test
    fun ownPacketsFromTheInboxAreDelivered() = runTest {
        val env = FakeEnv(this)
        val relay = RadioRelay(backgroundScope, env)
        env.own += claraRoute(0)
        env.inbox += entry(0, 1)
        env.inbox += RadioInboxPacket(packet(ByteArray(8) { 2 }, 2), START / 1000)
        relay.start()
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(1, env.delivered.size)
    }

    @Test
    fun asksWhenOfflineClimbsHopsAndHandsKeysToTheFirstPatron() = runTest {
        val env = FakeEnv(this)
        env.online = false
        val relay = RadioRelay(backgroundScope, env)
        relay.start()
        fun asks() = env.sent.filter { Anmp.peek(it.first)?.kind == AnmpKind.ASK }.map { it.second }
        advanceTimeBy(10.seconds)
        assertEquals(listOf<Int?>(1), asks(), "first request at once, hop 1")
        advanceTimeBy(65.seconds)
        advanceTimeBy(65.seconds)
        advanceTimeBy(65.seconds)
        assertEquals(listOf<Int?>(1, 2, 3), asks(), "without answer one hop further, up to 3")
        assertFalse(relay.servedByPatron.value)

        val first = AnmpEphemeral.generate().publicKey
        relay.onPatron(patron(first))
        relay.onPatron(patron(AnmpEphemeral.generate().publicKey))
        assertEquals(1, env.pickupsFor.size, "only the first patron gets the keys")
        assertTrue(env.pickupsFor.single().contentEquals(first))
        assertTrue(relay.servedByPatron.value)
        advanceTimeBy(10.minutes)
        assertEquals(3, asks().size, "with an answer again only after 15 minutes")
        advanceTimeBy(5.minutes)
        assertEquals(listOf<Int?>(1, 2, 3, 1), asks().take(4))
    }

    private companion object {
        const val START = 1_762_000_000_000L
    }
}
