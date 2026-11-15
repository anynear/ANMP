package app.anynear.anmp.core

import app.anynear.anmp.Anmp
import app.anynear.anmp.AnmpCipher
import app.anynear.anmp.AnmpContent
import app.anynear.anmp.AnmpEphemeral
import app.anynear.anmp.AnmpHeader
import app.anynear.anmp.AnmpKind
import app.anynear.anmp.AnmpRadio
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/**
 * vectors/anmp-v1-radio-flows.json: radio flow vectors of the patron (ADR 0054 §3a). Each flow is a list of steps at a
 * time, each with an input (heard ask, patron or pickup, entries put into the radio inbox, a reading of the radio inbox)
 * and the expected outputs up to and including that step (packets sent with hop limit and gateway, limits reached).
 * Flows run with the settings of the app (wait 2 to 20 s by signal) and of the bridge (30 s), the random part fixed
 * in each ask.
 *
 * `UPDATE_VECTORS=1 ./gradlew :core:jvmTest` builds the flows below, plays them through [RadioRelay] and writes the
 * file. Otherwise the test plays the inputs of the file and compares the outputs, as the Go module does
 * (go/anmp/patron_test.go); the flows below must all be in the file. JVM only, like [RadioRelayTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioFlowVectorsTest {
    private val json = Json { prettyPrint = true }

    /** Settings of the patron; [name] is the key in the file. */
    private class Setting(val name: String, val timings: RadioTimings, val perGatewayHour: Int)

    private val app = Setting("app", RadioTimings(), RadioRelay.MAX_PER_HOUR)
    private val bridge = Setting(
        "bridge",
        RadioTimings(patronWaitMin = RadioRelay.BRIDGE_PATRON_WAIT, patronWaitMax = RadioRelay.BRIDGE_PATRON_WAIT),
        RadioRelay.BRIDGE_PER_GATEWAY_HOUR,
    )
    private val settings = listOf(app, bridge)

    private class Ask(val packet: ByteArray, val hops: Int?, val snr: Float?, val gateway: String?, val jitter: Long, val key: ByteArray)

    private class Step(
        val t: Long,
        val note: String? = null,
        val ask: Ask? = null,
        val patron: ByteArray? = null,
        val pickup: ByteArray? = null,
        val inbox: List<RadioInboxPacket> = emptyList(),
        val serve: Boolean = false,
    )

    private class Sent(val packet: ByteArray, val hops: Int?, val gateway: String?)

    private data class Out(val sent: List<Sent>, val limits: List<String>) {
        fun kinds() = sent.map { Anmp.peek(it.packet)?.kind }
    }

    private class Flow(val name: String, val setting: Setting, val steps: List<Step>, val check: suspend (List<Out>) -> Unit = {})

    // ---- Running a flow through RadioRelay --------------------------------------------------------------------------

    private class FlowEnv(val test: TestScope) : RadioRelay.Env {
        val inbox = mutableListOf<RadioInboxPacket>()
        val sent = mutableListOf<Sent>()
        val limits = mutableListOf<String>()
        private var polled = 0

        override fun nowMillis(): Long = START + test.testScheduler.currentTime
        override fun radioReady() = true
        override fun online() = true
        override fun inboxReady() = true
        override fun mayServeOthers() = true
        override suspend fun isOwnRoute(route: ByteArray, atSeconds: Long) = false
        override suspend fun uplink(packet: ByteArray) = true
        override suspend fun pollInbox(wanted: (ByteArray) -> Boolean): List<RadioInboxPacket> =
            inbox.drop(polled).also { polled = inbox.size }
        override suspend fun deliverOwn(packet: ByteArray, atSeconds: Long) = Unit
        override suspend fun askPacket(since: Long): ByteArray? = null
        override suspend fun patronPacket(key: ByteArray) = Anmp.encodeOpen(AnmpHeader(AnmpKind.PATRON, PATRON_FROM, senderKey = key))
        override suspend fun pickupPackets(patronKey: ByteArray): List<ByteArray> = emptyList()
        override suspend fun broadcast(packet: ByteArray, hopLimit: Int?) = broadcastVia(packet, hopLimit, null)
        override suspend fun broadcastVia(packet: ByteArray, hopLimit: Int?, via: String?): Boolean = sent.add(Sent(packet, hopLimit, via))
        override fun onLimit(which: String) {
            limits += which
        }
        override fun log(text: String) = Unit

        fun drain() = Out(sent.toList(), limits.toList()).also { sent.clear(); limits.clear() }
    }

    private fun play(flow: Flow): List<Out> {
        val outs = mutableListOf<Out>()
        runTest {
            val env = FlowEnv(this)
            val jitters = ArrayDeque<Long>()
            val keys = ArrayDeque<ByteArray>()
            val relay = RadioRelay(
                backgroundScope, env, flow.setting.timings, flow.setting.perGatewayHour,
                jitterMillis = { jitters.removeFirst() },
                newKey = { AnmpEphemeral.of(keys.removeFirst()) },
            )
            for (step in flow.steps) {
                val now = testScheduler.currentTime
                require(step.t >= now) { "${flow.name}: steps must not go back in time" }
                if (step.t > now) advanceTimeBy((step.t - now).milliseconds)
                runCurrent()
                env.inbox += step.inbox
                step.ask?.let {
                    jitters += it.jitter
                    keys += it.key
                    relay.onAsk(Anmp.peek(it.packet)!!, it.hops, it.snr, it.gateway)
                }
                step.patron?.let { relay.onPatron(Anmp.peek(it)!!) }
                step.pickup?.let { relay.onPickup(it) }
                if (step.serve) relay.serve()
                runCurrent()
                outs += env.drain()
            }
        }
        return outs
    }

    // ---- The flows ----------------------------------------------------------------------------------------------------

    private val nowHour = AnmpRadio.hourOf(START / 1000)

    /** Someone without internet: radio secret, sender tag and one-time keys for their pickups. */
    private class Person(val name: String, val n: Int) {
        val secret = ByteArray(32) { (n * 16 + it).toByte() }
        val from = ByteArray(8) { (0xA0 + n).toByte() }
        fun askerKey(i: Int) = ByteArray(32) { (n * 16 + i + 100).toByte() }
    }

    private val clara = Person("Clara", 1)
    private val bob = Person("Bob", 2)
    private val dora = Person("Dora", 3)
    private val emil = Person("Emil", 4)

    /** One-time key of the patron for its [i]th offer in a flow; [FOREIGN] is another patron's. */
    private fun patronKey(i: Int) = if (i == FOREIGN) ByteArray(32) { 0x77 } else ByteArray(32) { (0x40 + i * 3 + it).toByte() }

    private suspend fun route(p: Person, hoursAgo: Long) = AnmpRadio.routeTag(p.secret, nowHour - hoursAgo)

    /** A circle message for [p] in the public form; fixed nonce, so the bytes stay the same. */
    private suspend fun message(p: Person, hoursAgo: Long, n: Int): ByteArray =
        Anmp.encode(
            AnmpHeader(AnmpKind.CIRCLE, ByteArray(8) { 0x33 }, epoch = 0, route = route(p, hoursAgo)),
            AnmpContent(id = ByteArray(8) { (p.n * 64 + n).toByte() }, ts = 1, text = "M$n"),
            "c",
            FixedCipher(n),
        )

    /** Radio inbox entry for [p], put [secondsAgo] before the start, in hour [hoursAgo]. */
    private suspend fun entry(p: Person, n: Int, hoursAgo: Long = 0, secondsAgo: Long = hoursAgo * 3600) =
        RadioInboxPacket(message(p, hoursAgo, n), START / 1000 - secondsAgo)

    /** Entry for [p] put [secondsAfter] after the start, in the current hour. */
    private suspend fun later(p: Person, n: Int, secondsAfter: Long) = RadioInboxPacket(message(p, 0, n), START / 1000 + secondsAfter)

    private fun askPacket(p: Person, since: Long = 0) = Anmp.encodeOpen(AnmpHeader(AnmpKind.ASK, p.from, since = since))

    private fun ask(p: Person, i: Int, hops: Int? = 1, snr: Float? = null, gateway: String? = null, jitter: Long = 0, since: Long = 0) =
        Ask(askPacket(p, since), hops, snr, gateway, jitter, patronKey(i))

    private fun patronOfAnother() = Anmp.encodeOpen(AnmpHeader(AnmpKind.PATRON, ByteArray(8) { 0x55 }, senderKey = ByteArray(32) { 0x66 }))

    /** Old file while writing: pickups whose content stayed the same keep their bytes (the nonce is random). */
    private var oldPickups: Map<String, ByteArray> = emptyMap()

    /** [p]'s pickup ([i]: their [i]th one-time key) for the patron's [patronIndex]th offer, in flow [flow]. */
    private suspend fun pickup(flow: String, p: Person, i: Int, patronIndex: Int): ByteArray {
        val patron = AnmpEphemeral.of(patronKey(patronIndex))
        val windows = AnmpRadio.pickupWindows(p.secret, nowHour)
        val mine = AnmpEphemeral.of(p.askerKey(i))
        oldPickups["$flow/${p.name}/$i/$patronIndex"]?.let { old ->
            val opened = AnmpRadio.openPickup(old, patron)
            val head = Anmp.peek(old)
            if (opened != null && opened.hour == nowHour && opened.windows.map { listOf(it.epoch, it.f.toList(), it.b.toList()) } == windows.map { listOf(it.epoch, it.f.toList(), it.b.toList()) } &&
                 head?.senderKey.contentEquals(mine.publicKey) &&
                head?.from.contentEquals(p.from)
            ) return old
        }
        return AnmpRadio.pickupPacket(p.from, mine, patron.publicKey, nowHour, windows)
            .also { oldPickups = oldPickups + ("$flow/${p.name}/$i/$patronIndex" to it) }
    }

    /** Wait of the patron for an ask with [snr] and [jitter]: what [RadioRelay] computes. */
    private fun wait(s: Setting, snr: Float?, jitter: Long): Long {
        val min = s.timings.patronWaitMin.inWholeMilliseconds
        val max = s.timings.patronWaitMax.inWholeMilliseconds
        return min + ((max - min) * ((5f - (snr ?: -15f)) / 20f).coerceIn(0f, 1f)).toLong() + jitter
    }

    private fun Out.routes() = sent.map { Anmp.peek(it.packet)?.route?.toList() }

    private suspend fun flows(): List<Flow> = buildList {
        for (s in settings) {
            val n = s.name
            // 1. The patron waits, offers itself, gets Clara's pickup and sends what is hers from the last 30 days.
            run {
                val name = "$n: patron delivers the last 30 days"
                val gw = if (s == bridge) "gw-a" else null
                val w = wait(s, 6f, 700)
                val inbox = listOf(entry(clara, 1, hoursAgo = 5), entry(clara, 2, hoursAgo = 20 * 24), entry(clara, 3, hoursAgo = 31 * 24), entry(bob, 4, hoursAgo = 1))
                add(Flow(name, s, listOf(
                    Step(0, "radio inbox: Clara 5 h and 20 days ago, Clara 31 days ago, Bob", inbox = inbox),
                    Step(0, "Clara asks, 2 hops, SNR 6 dB", ask = ask(clara, 0, hops = 2, snr = 6f, gateway = gw, jitter = 700)),
                    Step(w - 1, "still waiting"),
                    Step(w, "offer with a one-time key"),
                    Step(w + 3_000, "Clara's pickup: her packets of the last 30 days, newest first", pickup = pickup(name, clara, 0, 0)),
                    Step(w + 33_000, "reading the radio inbox again sends nothing twice", serve = true),
                )) { outs ->
                    assertTrue(outs.take(3).all { it.sent.isEmpty() })
                    assertEquals(listOf<AnmpKind?>(AnmpKind.PATRON), outs[3].kinds())
                    assertEquals(listOf(route(clara, 5).toList(), route(clara, 20 * 24).toList()), outs[4].routes())
                    assertTrue(outs[4].sent.all { it.hops == 2 && it.gateway == gw })
                    assertTrue(outs[5].sent.isEmpty())
                })
            }
            // 2. Another patron answers while we wait: we stay quiet ("app on site was faster").
            run {
                val name = if (s == bridge) "$n: app on site was faster" else "$n: another app was faster"
                val w = wait(s, -15f, 1_500)
                add(Flow(name, s, listOf(
                    Step(0, inbox = listOf(entry(clara, 1, hoursAgo = 1))),
                    Step(0, "Clara asks, weak signal", ask = ask(clara, 0, snr = -15f, gateway = "gw-a".takeIf { s == bridge }, jitter = 1_500)),
                    Step(if (s == bridge) 21_000 else 5_000, "a patron answers", patron = patronOfAnother()),
                    Step(w, "our wait is over: nothing"),
                    Step(w + 60_000, "nothing later either", serve = true),
                )) { outs -> assertTrue(outs.all { it.sent.isEmpty() && it.limits.isEmpty() }) })
            }
            // 3. A patron heard before the ask does not count.
            run {
                val name = "$n: patron heard before the ask"
                val w = wait(s, 0f, 0)
                add(Flow(name, s, listOf(
                    Step(0, "a patron for someone else", patron = patronOfAnother()),
                    Step(1_000, "Clara asks", ask = ask(clara, 0, snr = 0f, gateway = "gw-a".takeIf { s == bridge })),
                    Step(1_000 + w, "offer"),
                )) { outs -> assertEquals(listOf<AnmpKind?>(AnmpKind.PATRON), outs[2].kinds()) })
            }
            // 4. Five per ask when the pickup comes; the rest goes with the next reading of the radio inbox.
            run {
                val name = "$n: five per ask at the pickup, the rest with the next reading"
                val w = wait(s, 5f, 0)
                add(Flow(name, s, listOf(
                    Step(0, "eight packets for Clara", inbox = (1..8).map { entry(clara, it, secondsAgo = 600L * it, hoursAgo = 0) }),
                    Step(0, ask = ask(clara, 0, snr = 5f, gateway = "gw-a".takeIf { s == bridge })),
                    Step(w, "offer"),
                    Step(w + 1_000, "pickup: the five newest", pickup = pickup(name, clara, 0, 0)),
                    Step(w + 31_000, "next reading: the other three", serve = true),
                )) { outs ->
                    assertEquals(5, outs[3].sent.size)
                    assertEquals(3, outs[4].sent.size)
                })
            }
            // 5. Only packets put after the ask's since minus 10 minutes.
            run {
                val name = "$n: since minus ten minutes"
                val since = START / 1000 - 3_600
                val w = wait(s, null, 300)
                add(Flow(name, s, listOf(
                    Step(0, "put one second before, at and one second after since minus 10 minutes", inbox = listOf(
                        entry(clara, 1, hoursAgo = 1, secondsAgo = 4_201), entry(clara, 2, hoursAgo = 1, secondsAgo = 4_200), entry(clara, 3, hoursAgo = 1, secondsAgo = 4_199),
                    )),
                    Step(0, "Clara asks, since 60 minutes before the start", ask = ask(clara, 0, gateway = "gw-a".takeIf { s == bridge }, jitter = 300, since = since)),
                    Step(w, "offer"),
                    Step(w + 1_000, "only the packet later than since minus 10 minutes", pickup = pickup(name, clara, 0, 0)),
                )) { outs -> assertEquals(1, outs[3].sent.size) })
            }
            // 6. Hop limit = measured hops, at least 1; unknown hops: the patron answers with the radio's own limit and sends with 3.
            run {
                val name = "$n: hop limit at least one"
                val w = wait(s, 10f, 0)
                add(Flow(name, s, listOf(
                    Step(0, inbox = listOf(entry(clara, 1), entry(bob, 2))),
                    Step(0, "Clara asks, heard directly (0 hops)", ask = ask(clara, 0, hops = 0, snr = 10f, gateway = "gw-a".takeIf { s == bridge })),
                    Step(0, "Bob asks, hops unknown", ask = ask(bob, 1, hops = null, snr = 10f, gateway = "gw-b".takeIf { s == bridge })),
                    Step(w, "two offers"),
                    Step(w + 1_000, "Clara's pickup: hop limit 1", pickup = pickup(name, clara, 0, 0)),
                    Step(w + 2_000, "Bob's pickup: hop limit 3", pickup = pickup(name, bob, 0, 1)),
                )) { outs ->
                    assertEquals(listOf(1, null), outs[3].sent.map { it.hops })
                    assertEquals(1, outs[4].sent.single().hops)
                    assertEquals(RadioRelay.DEFAULT_HOPS, outs[5].sent.single().hops)
                })
            }
            // 7. A pickup for another patron or after the offer ran out gives nothing.
            run {
                val name = "$n: pickup for another patron or too late"
                val w = wait(s, 5f, 0)
                add(Flow(name, s, listOf(
                    Step(0, inbox = listOf(entry(clara, 1), entry(bob, 2))),
                    Step(0, ask = ask(clara, 0, snr = 5f, gateway = "gw-a".takeIf { s == bridge })),
                    Step(w, "offer"),
                    Step(w + 1_000, "pickup for another patron", pickup = pickup(name, clara, 0, FOREIGN)),
                    Step(w + 60_000, "Bob asks", ask = ask(bob, 1, snr = 5f, gateway = "gw-a".takeIf { s == bridge })),
                    Step(w + 60_000 + w, "offer for Bob"),
                    Step(w + 60_000 + w + RadioRelay.OFFER_MS + 1, "Bob's pickup comes too late", pickup = pickup(name, bob, 0, 1)),
                )) { outs ->
                    assertTrue(outs[3].sent.isEmpty())
                    assertEquals(listOf<AnmpKind?>(AnmpKind.PATRON), outs[5].kinds())
                    assertTrue(outs[6].sent.isEmpty())
                })
            }
            // 8. The patronage goes on for 60 minutes after the pickup, then ends.
            run {
                val name = "$n: patronage for sixty minutes"
                val w = wait(s, 5f, 0)
                val until = w + 1_000 + s.timings.patronage.inWholeMilliseconds
                add(Flow(name, s, listOf(
                    Step(0, ask = ask(clara, 0, snr = 5f, gateway = "gw-a".takeIf { s == bridge })),
                    Step(w, "offer"),
                    Step(w + 1_000, "pickup, nothing waiting yet", pickup = pickup(name, clara, 0, 0)),
                    Step(w + 600_000, "new message for Clara", inbox = listOf(later(clara, 1, 600))),
                    Step(w + 630_000, "sent at the next reading", serve = true),
                    Step(until, "another one, at the last moment", inbox = listOf(later(clara, 2, until / 1000)), serve = true),
                    Step(until + 1, "a third one: the patronage is over", inbox = listOf(later(clara, 3, until / 1000 + 1)), serve = true),
                )) { outs ->
                    assertEquals(1, outs[4].sent.size)
                    assertEquals(1, outs[5].sent.size)
                    assertTrue(outs[6].sent.isEmpty())
                })
            }
            // 9. At most 20 per hour and patronage.
            run {
                val name = "$n: twenty per hour and patronage"
                val w = wait(s, 5f, 0)
                add(Flow(name, s, listOf(
                    Step(0, ask = ask(clara, 0, snr = 5f, gateway = "gw-a".takeIf { s == bridge })),
                    Step(w, "offer"),
                    Step(w + 1_000, "pickup, nothing waiting yet", pickup = pickup(name, clara, 0, 0)),
                    Step(w + 60_000, "25 new messages for Clara: 20 go out", inbox = (1..25).map { later(clara, it, 60L + it) }, serve = true),
                )) { outs ->
                    assertEquals(20, outs[3].sent.size)
                    assertEquals(List(5) { RadioRelay.LIMIT_PATRONAGE }, outs[3].limits)
                })
            }
            // 10. Budget full: the gateway's budget per hour is used up by other patronages. Packets of a patronage are
            // held back; a patron whose budget is used up does not offer itself at all.
            run {
                val name = "$n: budget full"
                val w = wait(s, 5f, 0)
                val full = s.perGatewayHour / RadioRelay.MAX_PER_HOUR // patronages that fill the budget
                val all = listOf(clara, bob, dora, emil)
                val people = all.take(full)
                val last = all[full]
                val gwA = "gw-a".takeIf { s == bridge }
                val steps = mutableListOf<Step>()
                var t = 0L
                people.forEachIndexed { i, p ->
                    steps += Step(t, "20 messages for ${p.name}", inbox = (1..20).map { later(p, it, t / 1000) })
                    steps += Step(t, "${p.name} asks", ask = ask(p, i, snr = 5f, gateway = gwA))
                    steps += Step(t + w, "offer")
                    steps += Step(t + w + 1_000, "pickup: five", pickup = pickup(name, p, 0, i))
                    if (i < people.lastIndex) steps += Step(t + w + 31_000, "next reading: fifteen more", serve = true)
                    t += w + 60_000
                }
                steps += Step(t, "messages for ${last.name}", inbox = (1..3).map { later(last, it, t / 1000) })
                steps += Step(t, "${last.name} asks", ask = ask(last, full, snr = 5f, gateway = gwA))
                val offered = steps.size
                steps += Step(t + w, "offer: fifteen packets left in the budget")
                steps += Step(t + w + 500, "next reading: fifteen more for ${people.last().name}, the budget is used up", serve = true)
                val heldBack = steps.size
                steps += Step(t + w + 1_000, "pickup: budget full, all three held back", pickup = pickup(name, last, 0, full))
                t += w + 60_000
                steps += Step(t, "${last.name} asks again", ask = ask(last, full + 1, snr = 5f, gateway = gwA))
                val noOffer = steps.size
                steps += Step(t + w, "budget full: no offer")
                if (s == bridge) {
                    t += w + 60_000
                    steps += Step(t, "${last.name} asks again, heard by gateway b", ask = ask(last, full + 2, snr = 5f, gateway = "gw-b"))
                    steps += Step(t + w, "offer through gateway b")
                    steps += Step(t + w + 1_000, "pickup: gateway b has its own budget", pickup = pickup(name, last, 1, full + 2))
                }
                add(Flow(name, s, steps) { outs ->
                    assertEquals(s.perGatewayHour, outs.sumOf { o -> o.kinds().count { it == AnmpKind.CIRCLE } } - if (s == bridge) 3 else 0)
                    assertEquals(listOf<AnmpKind?>(AnmpKind.PATRON), outs[offered].kinds())
                    assertTrue(outs[heldBack].sent.isEmpty())
                    assertEquals(List(3) { RadioRelay.LIMIT_GATEWAY }, outs[heldBack].limits)
                    assertTrue(outs[noOffer].sent.isEmpty())
                    assertEquals(listOf(RadioRelay.LIMIT_GATEWAY), outs[noOffer].limits)
                    if (s == bridge) {
                        assertEquals(listOf<AnmpKind?>(AnmpKind.PATRON), outs[outs.size - 2].kinds())
                        assertEquals(3, outs.last().sent.size)
                    }
                })
            }
            // 11. Each ask brings a new one-time key and the same since: what went to the asker (its sender tag) in the
            // last hour does not go again, also not from another patronage. After the hour it may.
            run {
                val name = "$n: nothing twice to the same asker in an hour"
                val w = wait(s, 5f, 0)
                val gw = "gw-a".takeIf { s == bridge }
                val again = 300_000L
                val news = 600_000L
                val after = news + 3_600_001L
                add(Flow(name, s, listOf(
                    Step(0, inbox = listOf(entry(clara, 1, hoursAgo = 1), entry(clara, 2, hoursAgo = 2))),
                    Step(0, "Clara asks", ask = ask(clara, 0, snr = 5f, gateway = gw)),
                    Step(w, "offer"),
                    Step(w + 1_000, "pickup: both packets", pickup = pickup(name, clara, 0, 0)),
                    Step(again, "Clara asks again, same since", ask = ask(clara, 1, snr = 5f, gateway = gw)),
                    Step(again + w, "offer"),
                    Step(again + w + 1_000, "pickup with a new one-time key: nothing again", pickup = pickup(name, clara, 1, 1)),
                    Step(news, "a new message for Clara: once, though two patronages wait for it", inbox = listOf(later(clara, 3, news / 1000)), serve = true),
                    Step(after, "Clara asks again more than an hour later", ask = ask(clara, 2, snr = 5f, gateway = gw)),
                    Step(after + w, "offer"),
                    Step(after + w + 1_000, "pickup: all three again", pickup = pickup(name, clara, 2, 2)),
                )) { outs ->
                    assertEquals(2, outs[3].sent.size)
                    assertEquals(listOf<AnmpKind?>(AnmpKind.PATRON), outs[5].kinds())
                    assertTrue(outs[6].sent.isEmpty() && outs[6].limits.isEmpty())
                    assertEquals(1, outs[7].sent.size)
                    assertEquals(3, outs[10].sent.size)
                })
            }
        }
    }

    // ---- File ---------------------------------------------------------------------------------------------------------

    @Test
    fun vectorsMatchFile() = runBlocking {
        val file = File(System.getProperty("anmp.radioFlowVectors") ?: error("anmp.radioFlowVectors not set"))
        val update = System.getProperty("anmp.updateVectors") == "1"
        val old = if (file.exists()) json.parseToJsonElement(file.readText()).jsonObject else null
        if (update) old?.let { oldPickups = pickupsOf(it) }
        val defined = flows()
        if (update) {
            val doc = buildJsonObject {
                put("protocol", "ANMP version v1, radio flows of the patron (ADR 0051, ADR 0054 §3a, §4)")
                put("comment", COMMENT)
                put("start", START)
                put("settings", buildJsonObject { for (s in settings) put(s.name, encode(s)) })
                put("flows", buildJsonArray {
                    for (f in defined) {
                        val outs = play(f)
                        f.check(outs)
                        add(encode(f, outs))
                    }
                })
            }
            file.writeText(json.encodeToString(JsonObject.serializer(), doc) + "\n")
            return@runBlocking
        }
        if (old == null) fail("vectors/anmp-v1-radio-flows.json missing: run UPDATE_VECTORS=1 ./gradlew :core:jvmTest")
        assertEquals(old["start"]?.jsonPrimitive?.long, START, "start")
        for (s in settings) assertEquals(encode(s), old["settings"]!!.jsonObject[s.name], "settings ${s.name}")
        val inFile = old["flows"]!!.jsonArray.map { it.jsonObject }
        assertEquals(defined.map { it.name }, inFile.map { it["name"]!!.jsonPrimitive.content }, "flows in the file: run UPDATE_VECTORS=1")
        for ((f, o) in defined.zip(inFile)) {
            val flow = decode(o)
            val outs = play(flow)
            f.check(outs)
            o["steps"]!!.jsonArray.zip(outs).forEachIndexed { i, (step, out) ->
                assertEquals(step.jsonObject["send"], sends(out), "${f.name}, step $i: send")
                assertEquals(step.jsonObject["limits"], limits(out), "${f.name}, step $i: limits")
            }
        }
    }

    private fun encode(s: Setting) = buildJsonObject {
        put("waitMinMs", s.timings.patronWaitMin.inWholeMilliseconds)
        put("waitMaxMs", s.timings.patronWaitMax.inWholeMilliseconds)
        put("jitterMaxMs", RadioRelay.JITTER_MS)
        put("perAsk", RadioRelay.MAX_PER_ASK)
        put("perHour", RadioRelay.MAX_PER_HOUR)
        put("perGatewayHour", s.perGatewayHour)
        put("patronageMs", s.timings.patronage.inWholeMilliseconds)
        put("offerMs", RadioRelay.OFFER_MS)
        put("sinceOverlapS", s.timings.sinceOverlap.inWholeSeconds)
        put("defaultHops", RadioRelay.DEFAULT_HOPS)
        put("inboxLifetimeS", RadioInbox.LIFETIME_SECONDS)
    }

    private fun encode(f: Flow, outs: List<Out>) = buildJsonObject {
        put("name", f.name)
        put("setting", f.setting.name)
        put("patronFrom", hex(PATRON_FROM))
        put("steps", buildJsonArray {
            for ((step, out) in f.steps.zip(outs)) add(buildJsonObject {
                put("t", step.t)
                step.note?.let { put("note", it) }
                if (step.inbox.isNotEmpty()) put("inbox", buildJsonArray {
                    for (e in step.inbox) add(buildJsonObject { put("packet", hex(e.packet)); put("ts", e.ts) })
                })
                step.ask?.let { a ->
                    put("ask", buildJsonObject {
                        put("packet", hex(a.packet))
                        put("hops", a.hops?.let(::JsonPrimitive) ?: JsonNull)
                        put("snr", a.snr?.let(::JsonPrimitive) ?: JsonNull)
                        put("gateway", a.gateway?.let(::JsonPrimitive) ?: JsonNull)
                        put("jitterMs", a.jitter)
                        put("patronKey", hex(a.key))
                    })
                }
                step.patron?.let { put("patron", hex(it)) }
                step.pickup?.let { put("pickup", hex(it)) }
                if (step.serve) put("serve", true)
                put("send", sends(out))
                put("limits", limits(out))
            })
        })
    }

    private fun sends(out: Out) = buildJsonArray {
        for (s in out.sent) add(buildJsonObject {
            put("packet", hex(s.packet))
            put("hops", s.hops?.let(::JsonPrimitive) ?: JsonNull)
            put("gateway", s.gateway?.let(::JsonPrimitive) ?: JsonNull)
        })
    }

    private fun limits(out: Out) = JsonArray(out.limits.map(::JsonPrimitive))

    private fun decode(o: JsonObject): Flow {
        val setting = settings.single { it.name == o["setting"]!!.jsonPrimitive.content }
        assertEquals(hex(PATRON_FROM), o["patronFrom"]!!.jsonPrimitive.content, "patronFrom")
        val steps = o["steps"]!!.jsonArray.map { it.jsonObject }.map { s ->
            Step(
                t = s["t"]!!.jsonPrimitive.long,
                ask = (s["ask"] as? JsonObject)?.let { a ->
                    Ask(
                        unhex(a["packet"]!!.jsonPrimitive.content),
                        a["hops"]!!.jsonPrimitive.intOrNull,
                        (a["snr"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toFloat(),
                        (a["gateway"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
                        a["jitterMs"]!!.jsonPrimitive.long,
                        unhex(a["patronKey"]!!.jsonPrimitive.content),
                    )
                },
                patron = s["patron"]?.jsonPrimitive?.content?.let(::unhex),
                pickup = s["pickup"]?.jsonPrimitive?.content?.let(::unhex),
                inbox = (s["inbox"] as? JsonArray).orEmpty().map { e ->
                    RadioInboxPacket(unhex(e.jsonObject["packet"]!!.jsonPrimitive.content), e.jsonObject["ts"]!!.jsonPrimitive.long)
                },
                serve = s["serve"]?.jsonPrimitive?.content == "true",
            )
        }
        return Flow(o["name"]!!.jsonPrimitive.content, setting, steps)
    }

    /** Pickups of an old file by flow, person, one-time key and offer, for [pickup]. */
    private suspend fun pickupsOf(old: JsonObject): Map<String, ByteArray> = buildMap {
        val people = listOf(clara, bob, dora, emil)
        for (f in old["flows"]?.jsonArray.orEmpty().map { it.jsonObject }) {
            val name = f["name"]!!.jsonPrimitive.content
            for (s in f["steps"]!!.jsonArray.map { it.jsonObject }) {
                val bytes = s["pickup"]?.jsonPrimitive?.content?.let(::unhex) ?: continue
                val head = Anmp.peek(bytes) ?: continue
                val p = people.firstOrNull { it.from.contentEquals(head.from) } ?: continue
                val i = (0..3).firstOrNull { AnmpEphemeral.of(p.askerKey(it)).publicKey.contentEquals(head.senderKey) } ?: continue
                val offer = ((0..8) + FOREIGN).firstOrNull { AnmpEphemeral.of(patronKey(it)).publicKey.copyOf(8).contentEquals(head.to) } ?: continue
                put("$name/${p.name}/$i/$offer", bytes)
            }
        }
    }

    /** AES-256-GCM with a nonce fixed by [n], so that the packets of the flows keep their bytes. */
    private class FixedCipher(private val n: Int) : AnmpCipher {
        private val key = SecretKeySpec(ByteArray(32) { 0x21 }, "AES")

        override suspend fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            val nonce = ByteArray(12) { (n + it).toByte() }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            c.updateAAD(associatedData)
            return nonce + c.doFinal(plaintext)
        }

        override suspend fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray? = null
    }

    private companion object {
        const val START = 1_762_000_000_000L
        const val FOREIGN = 99

        /** Sender tag of the patron in the flows (the app's hourly one, the bridge's own). */
        val PATRON_FROM = ByteArray(8) { 0x50 }

        const val COMMENT = "Flows of a patron (ADR 0051 §5, §5a, §6; ADR 0054 §4), written by kotlin/core RadioFlowVectorsTest " +
            "(UPDATE_VECTORS=1) through RadioRelay and played by go/anmp patron_test.go. Times: start + t in milliseconds, " +
            "inbox ts in Unix seconds. Each step first lets the time run to t (offers whose wait ends at or before t go " +
            "out), then takes its input: inbox (entries put into the radio inbox, read only with the next pickup or serve), " +
            "ask (heard through gateway, measured hops or null, SNR or null, the random part jitterMs of the wait and the " +
            "one-time key patronKey (X25519 private) the patron draws for it), patron (heard another patron), pickup " +
            "(heard), serve (the patron reads the radio inbox and serves its patronages). send: packets that went out in " +
            "the step, in order, with hop limit (null: the radio's own) and gateway (null: the app's radio). limits: " +
            "packets held back, patronage (perHour per patronage) or gateway (perGatewayHour over all patronages; also an " +
            "offer not sent because the budget of its gateway is used up). A packet goes to the same asker (sender tag of " +
            "the pickup) at most once an hour, across patronages. Wait " +
            "before an offer: waitMin + (waitMax − waitMin) × clamp((5 − SNR) / 20, 0, 1) in float32, truncated, + " +
            "jitterMs; SNR null counts as −15. Patron packets are open packets with from = patronFrom."

        fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

        fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
