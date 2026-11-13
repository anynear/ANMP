package app.anynear.anmp.core

import app.anynear.anmp.Anmp
import app.anynear.anmp.AnmpEphemeral
import app.anynear.anmp.AnmpHeader
import app.anynear.anmp.AnmpRadio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Delivery through patrons (ADR 0051): uplink into the radio inbox, ask without internet, be a patron for others.
 * Knows neither circles nor messages; everything about content is done by [Env] ([RadioWay]).
 */
internal class RadioRelay(private val scope: CoroutineScope, private val env: Env, private val timings: RadioTimings = RadioTimings()) {
    /** What the relay needs from the radio way. */
    interface Env {
        fun nowMillis(): Long

        /** Radio connected and radio channel set up. */
        fun radioReady(): Boolean

        /** At least one relay answers. */
        fun online(): Boolean

        /** Radio inbox set up. */
        fun inboxReady(): Boolean

        /** "Relay for others" on (default) and enough battery at the radio. */
        fun mayServeOthers(): Boolean

        /** Does this route tag belong to this device or an own circle, in the hour around [atSeconds]? */
        suspend fun isOwnRoute(route: ByteArray, atSeconds: Long): Boolean

        suspend fun uplink(packet: ByteArray): Boolean

        suspend fun pollInbox(wanted: (ByteArray) -> Boolean): List<RadioInboxPacket>

        /** Sorts an own packet from the radio inbox (as if heard over radio). */
        suspend fun deliverOwn(packet: ByteArray, atSeconds: Long)

        suspend fun askPacket(since: Long): ByteArray?

        /** Answer as patron with the one-time key [key] (ADR 0051 §5a). */
        suspend fun patronPacket(key: ByteArray): ByteArray?

        /** Pickup keys of the last 30 days for this device and its circles, only for the patron with [patronKey]. */
        suspend fun pickupPackets(patronKey: ByteArray): List<ByteArray>

        /** Sends to everyone on the radio channel; [hopLimit] `null` = the radio's default. `false` if nothing went out. */
        suspend fun broadcast(packet: ByteArray, hopLimit: Int?): Boolean

        fun log(text: String)
    }

    private class Patronage(val tags: Set<String>, var hops: Int, var until: Long, val since: Long, val sent: MutableSet<String> = mutableSetOf())

    /** Offer as patron, until the asking device answers with `pickup`. */
    private class Offer(val key: AnmpEphemeral, val hops: Int, val since: Long, val at: Long)

    private val lock = Mutex()
    private val uplinked = LinkedHashMap<String, Long>()
    private val cache = LinkedHashMap<String, RadioInboxPacket>()
    private val patronages = mutableMapOf<String, Patronage>()
    private val offers = mutableListOf<Offer>()
    private var pickupSent = true
    private val sentAt = ArrayDeque<Long>()
    private var lastPatronHeard = 0L
    private var lastOnline = 0L
    private var lastDelivery = 0L
    private var lastAsk = 0L
    private var askHops = 1
    private var askAnswered = true

    private val _servedByPatron = MutableStateFlow(false)

    /** A patron answered the last request: messages reach this device over radio. */
    val servedByPatron: StateFlow<Boolean> = _servedByPatron.asStateFlow()

    fun start() {
        scope.launch { loop() }
    }

    /** Ask at once if the situation calls for it (app opened). */
    fun onForeground() {
        scope.launch { lock.withLock { lastAsk = 0 } }
    }

    /** Heard a packet in the public form over radio: uplink it if possible. */
    suspend fun onHeard(packet: ByteArray) {
        if (!env.online() || !env.inboxReady() || !env.mayServeOthers()) return
        val key = keyOf(packet)
        val now = env.nowMillis()
        if (!lock.withLock { remember(uplinked, key, now) }) return
        if (env.uplink(packet)) env.log("radio: packet uplinked") else lock.withLock { uplinked.remove(key) }
    }

    /** A packet for this device arrived from the radio inbox: counts as delivery for `since`. */
    suspend fun onDelivered() = lock.withLock { lastDelivery = env.nowMillis() }

    /**
     * Heard a patron's answer. Other possible patrons then stay quiet. If we just asked ourselves, the first patron gets
     * our pickup keys, sealed only for it (ADR 0051 §5a).
     */
    suspend fun onPatron(header: AnmpHeader) {
        val now = env.nowMillis()
        val (forUs, hops) = lock.withLock {
            lastPatronHeard = now
            val forUs = !pickupSent && now - lastAsk <= timings.askAnswerWait.inWholeMilliseconds * 2
            if (forUs) {
                pickupSent = true
                askAnswered = true
            }
            forUs to askHops
        }
        if (!forUs) return
        val key = header.senderKey ?: return
        val packets = env.pickupPackets(key)
        packets.forEach { env.broadcast(it, hops) }
        lock.withLock { askHops = 1 }
        _servedByPatron.value = true
        env.log("radio: patron there, ${packets.size} pickup sent")
    }

    /** Heard `pickup`: if it is for one of our offers, we become patron for the 30 days in it. */
    suspend fun onPickup(packet: ByteArray) {
        val now = env.nowMillis()
        val open = lock.withLock {
            offers.removeAll { now - it.at > OFFER_MS }
            offers.toList()
        }
        for (offer in open) {
            val pickup = AnmpRadio.openPickup(packet, offer.key) ?: continue
            val tags = pickup.windows.flatMap { AnmpRadio.windowTags(it, pickup.hour) }.map { keyOf(it.second) }.toSet()
            val asker = Anmp.peek(packet)?.senderKey?.let(::keyOf) ?: return
            val p = lock.withLock {
                val until = now + timings.patronage.inWholeMilliseconds
                val old = patronages[asker]
                Patronage(old?.tags.orEmpty() + tags, offer.hops, until, offer.since, old?.sent ?: mutableSetOf()).also { patronages[asker] = it }
            }
            refresh()
            val waiting = lock.withLock { matching(p.tags).filter { it.ts > p.since } }
            env.log("radio: patron for ${tags.size} tags, ${waiting.size} waiting, hops ${p.hops}")
            send(p, waiting.take(MAX_PER_ASK))
            return
        }
    }

    /** Heard another device's request ([hops]: measured, [snr]: signal to noise). */
    suspend fun onAsk(header: AnmpHeader, hops: Int?, snr: Float?) {
        if (!env.online() || !env.inboxReady() || !env.mayServeOthers()) return
        val askedAt = env.nowMillis()
        scope.launch {
            delay(patronWait(snr))
            // Listen first, then send: if another patron answered while we waited, we stay quiet.
            if (lock.withLock { lastPatronHeard >= askedAt }) return@launch
            if (!env.radioReady()) return@launch
            val key = AnmpEphemeral.generate()
            val packet = env.patronPacket(key.publicKey) ?: return@launch
            if (!env.broadcast(packet, hops)) return@launch
            val since = (header.since ?: 0) - timings.sinceOverlap.inWholeSeconds
            lock.withLock { offers += Offer(key, hops ?: DEFAULT_HOPS, since, env.nowMillis()) }
            env.log("radio: offered as patron, hops ${hops ?: "?"}")
        }
    }

    private fun patronWait(snr: Float?): Duration {
        val min = timings.patronWaitMin.inWholeMilliseconds
        val max = timings.patronWaitMax.inWholeMilliseconds
        // Who hears better goes first: SNR 5 dB and more → shortest wait, −15 dB and less → longest.
        val share = ((5f - (snr ?: -15f)) / 20f).coerceIn(0f, 1f)
        return (min + ((max - min) * share).toLong() + Random.nextLong(0, 2_000)).milliseconds
    }

    private suspend fun send(p: Patronage, packets: List<RadioInboxPacket>) {
        for (packet in packets) {
            val key = keyOf(packet.packet)
            val allowed = lock.withLock {
                val now = env.nowMillis()
                while (sentAt.isNotEmpty() && now - sentAt.first() > HOUR_MS) sentAt.removeFirst()
                if (key in p.sent || sentAt.size >= MAX_PER_HOUR) false else true.also { p.sent += key; sentAt.addLast(now) }
            }
            if (!allowed) continue
            if (!env.broadcast(packet.packet, p.hops)) lock.withLock { p.sent -= key; sentAt.removeLastOrNull() }
        }
    }

    private suspend fun loop() {
        var lastPoll = 0L
        while (true) {
            try {
                val now = env.nowMillis()
                if (env.online()) lock.withLock { lastOnline = now }
                if (env.online() && env.inboxReady() && now - lastPoll >= timings.pollEvery.inWholeMilliseconds) {
                    lastPoll = now
                    refresh()
                    servePatronages()
                }
                maybeAsk(now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                env.log("radio: patron loop ${e.message}")
            }
            delay(timings.tick)
        }
    }

    /** Fetches news from the radio inbox: sorts own packets, keeps all for patronages. */
    private suspend fun refresh() {
        val fresh = env.pollInbox { true }
        if (fresh.isEmpty()) return
        val now = env.nowMillis()
        lock.withLock {
            fresh.forEach { cache[keyOf(it.packet)] = it }
            val oldest = now / 1000 - LIFETIME_S
            cache.entries.removeAll { it.value.ts < oldest }
            while (cache.size > MAX_CACHE) cache.remove(cache.keys.first())
        }
        for (p in fresh) {
            val route = Anmp.peek(p.packet)?.route ?: continue
            if (env.isOwnRoute(route, p.ts)) env.deliverOwn(p.packet, p.ts)
        }
    }

    private suspend fun servePatronages() {
        val active = lock.withLock {
            val now = env.nowMillis()
            patronages.entries.removeAll { it.value.until < now }
            patronages.values.toList()
        }
        if (active.isEmpty() || !env.radioReady() || !env.mayServeOthers()) return
        for (p in active) send(p, lock.withLock { matching(p.tags).filter { it.ts > p.since } })
    }

    private suspend fun maybeAsk(now: Long) {
        if (env.online() || !env.radioReady()) return
        val (due, since) = lock.withLock {
            // Without pickup sent to a patron the request counts as unanswered.
            val offlineLongEnough = now - lastOnline >= timings.offlineBeforeAsk.inWholeMilliseconds || lastAsk == 0L && lastOnline == 0L
            // No answer after the wait: one hop further, up to 3; otherwise every [RadioTimings.askEvery].
            val retry = !askAnswered && askHops < MAX_ASK_HOPS && now - lastAsk >= timings.askAnswerWait.inWholeMilliseconds
            if (retry) askHops++
            val due = offlineLongEnough && (retry || now - lastAsk >= timings.askEvery.inWholeMilliseconds)
            due to maxOf(lastOnline, lastDelivery) / 1000
        }
        if (!due) return
        val packet = env.askPacket(since) ?: return
        val hops = lock.withLock { askHops }
        if (env.broadcast(packet, hops)) {
            lock.withLock {
                lastAsk = now
                askAnswered = false
                pickupSent = false
            }
            _servedByPatron.value = false
            env.log("radio: asked, hops $hops")
        }
    }

    private fun matching(tags: Set<String>): List<RadioInboxPacket> =
        cache.values.filter { p -> Anmp.peek(p.packet)?.route?.let { keyOf(it) in tags } == true }
            .sortedByDescending { it.ts }

    private fun remember(map: LinkedHashMap<String, Long>, key: String, now: Long): Boolean {
        map.entries.removeAll { now - it.value > LIFETIME_S * 1000 }
        if (key in map) return false
        map[key] = now
        while (map.size > MAX_CACHE) map.remove(map.keys.first())
        return true
    }

    companion object {
        /** At most this many packets per request and per hour over all (ADR 0051). */
        const val MAX_PER_ASK = 5
        const val MAX_PER_HOUR = 20
        const val MAX_ASK_HOPS = 3
        private const val DEFAULT_HOPS = 3
        private const val HOUR_MS = 3_600_000L
        /** How long an offer as patron waits for `pickup`. */
        private const val OFFER_MS = 120_000L
        /** How long entries of the radio inbox live. */
        private const val LIFETIME_S = RadioInbox.LIFETIME_SECONDS
        private const val MAX_CACHE = 20_000

        private fun keyOf(bytes: ByteArray): String = Base64.encode(bytes)
    }
}
