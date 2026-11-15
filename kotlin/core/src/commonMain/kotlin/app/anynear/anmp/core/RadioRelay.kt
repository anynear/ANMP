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
 *
 * The patron part follows the radio flow vectors (vectors/anmp-v1-radio-flows.json, ADR 0054 §3a), which the Go module
 * plays for the bridge too. [perGatewayHour] caps the packets per hour over all patronages and gateway ([onAsk] `via`;
 * the app has one, its radio); a patron whose gateway budget is used up does not offer itself. A packet goes to the
 * same asker (its hourly sender tag) at most once an hour, also across patronages: each ask brings a new one-time key,
 * and `since` alone does not stop resending (it overlaps by 10 minutes and only moves with internet or the radio
 * inbox). [jitterMillis] and [newKey] are the random parts of an offer, fixed only in the vectors.
 */
internal class RadioRelay(
    private val scope: CoroutineScope,
    private val env: Env,
    private val timings: RadioTimings = RadioTimings(),
    private val perGatewayHour: Int = MAX_PER_HOUR,
    private val jitterMillis: () -> Long = { Random.nextLong(0, JITTER_MS) },
    private val newKey: suspend () -> AnmpEphemeral = { AnmpEphemeral.generate() },
) {
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

        /** Sends a patron's packet through gateway [via] (bridge); the app has only its radio, `via` `null`. */
        suspend fun broadcastVia(packet: ByteArray, hopLimit: Int?, via: String?): Boolean = broadcast(packet, hopLimit)

        /** A patron held a packet back: [which] limit was reached, [LIMIT_PATRONAGE] or [LIMIT_GATEWAY]. */
        fun onLimit(which: String) = Unit

        fun log(text: String)
    }

    private class Patronage(
        /** Hourly sender tag of the asker (pickup field 1). */
        val asker: String,
        val tags: Set<String>,
        var hops: Int,
        var until: Long,
        val since: Long,
        val via: String?,
        val sent: MutableSet<String> = mutableSetOf(),
        val sentAt: ArrayDeque<Long> = ArrayDeque(),
    )

    /** Offer as patron, until the asking device answers with `pickup`. */
    private class Offer(val key: AnmpEphemeral, val hops: Int, val since: Long, val at: Long, val via: String?)

    private val lock = Mutex()
    private val uplinked = LinkedHashMap<String, Long>()
    private val cache = LinkedHashMap<String, RadioInboxPacket>()
    private val patronages = mutableMapOf<String, Patronage>()
    private val offers = mutableListOf<Offer>()
    private var pickupSent = true

    /** Packets sent as patron in the last hour, per gateway (`null`: the own radio). */
    private val sentAt = mutableMapOf<String?, ArrayDeque<Long>>()

    /** Packets sent as patron in the last hour, by asker sender tag and packet, oldest first; at most [MAX_DELIVERED]. */
    private val delivered = LinkedHashMap<String, Long>()
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
            val head = Anmp.peek(packet) ?: return
            val asker = head.senderKey?.let(::keyOf) ?: return
            val p = lock.withLock {
                val until = now + timings.patronage.inWholeMilliseconds
                val old = patronages[asker]
                Patronage(keyOf(head.from), old?.tags.orEmpty() + tags, offer.hops, until, offer.since, offer.via, old?.sent ?: mutableSetOf(), old?.sentAt ?: ArrayDeque())
                    .also { patronages[asker] = it }
            }
            refresh()
            val waiting = lock.withLock { matching(p.tags).filter { it.ts > p.since } }
            env.log("radio: patron for ${tags.size} tags, ${waiting.size} waiting, hops ${p.hops}")
            send(p, waiting.take(MAX_PER_ASK))
            return
        }
    }

    /**
     * Heard another device's request ([hops]: measured, [snr]: signal to noise, [via]: gateway it came through, bridge
     * only). Hop limit of the answer and the packets: measured hops, at least 1.
     */
    suspend fun onAsk(header: AnmpHeader, hops: Int?, snr: Float?, via: String? = null) {
        if (!env.online() || !env.inboxReady() || !env.mayServeOthers()) return
        val askedAt = env.nowMillis()
        scope.launch {
            val wait = patronWait(snr)
            val key = newKey()
            delay(wait)
            // Listen first, then send: if another patron answered while we waited, we stay quiet. So does a patron whose
            // gateway budget is used up: it could not send anything.
            val quiet = lock.withLock {
                when {
                    lastPatronHeard >= askedAt -> true
                    gatewayFull(via, env.nowMillis()) -> true.also { env.onLimit(LIMIT_GATEWAY) }
                    else -> false
                }
            }
            if (quiet) return@launch
            if (!env.radioReady()) return@launch
            val packet = env.patronPacket(key.publicKey) ?: return@launch
            if (!env.broadcastVia(packet, hops?.coerceAtLeast(1), via)) return@launch
            val since = (header.since ?: 0) - timings.sinceOverlap.inWholeSeconds
            lock.withLock { offers += Offer(key, (hops ?: DEFAULT_HOPS).coerceAtLeast(1), since, env.nowMillis(), via) }
            env.log("radio: offered as patron, hops ${hops ?: "?"}")
        }
    }

    private fun patronWait(snr: Float?): Duration {
        val min = timings.patronWaitMin.inWholeMilliseconds
        val max = timings.patronWaitMax.inWholeMilliseconds
        // Who hears better goes first: SNR 5 dB and more → shortest wait, −15 dB and less → longest.
        val share = ((5f - (snr ?: -15f)) / 20f).coerceIn(0f, 1f)
        return (min + ((max - min) * share).toLong() + jitterMillis()).milliseconds
    }

    /** Packets of the last hour through [via]; call under [lock]. */
    private fun gatewayFull(via: String?, now: Long): Boolean {
        val gateway = sentAt.getOrPut(via) { ArrayDeque() }
        while (gateway.isNotEmpty() && now - gateway.first() > HOUR_MS) gateway.removeFirst()
        return gateway.size >= perGatewayHour
    }

    /**
     * Sends [packets] for [p]: each once per patronage and once an hour per asker, at most [MAX_PER_HOUR] per patronage
     * and [perGatewayHour] per gateway.
     */
    private suspend fun send(p: Patronage, packets: List<RadioInboxPacket>) {
        for (packet in packets) {
            val key = keyOf(packet.packet)
            val toAsker = "${p.asker}/$key"
            val (allowed, limit) = lock.withLock {
                val now = env.nowMillis()
                val gateway = sentAt.getOrPut(p.via) { ArrayDeque() }
                for (q in listOf(p.sentAt, gateway)) while (q.isNotEmpty() && now - q.first() > HOUR_MS) q.removeFirst()
                val old = delivered.entries.iterator()
                while (old.hasNext() && now - old.next().value > HOUR_MS) old.remove()
                when {
                    key in p.sent || toAsker in delivered -> false to null
                    p.sentAt.size >= MAX_PER_HOUR -> false to LIMIT_PATRONAGE
                    gateway.size >= perGatewayHour -> false to LIMIT_GATEWAY
                    else -> true.also {
                        p.sent += key
                        p.sentAt.addLast(now)
                        gateway.addLast(now)
                        delivered[toAsker] = now
                        while (delivered.size > MAX_DELIVERED) delivered.remove(delivered.keys.first())
                    } to null
                }
            }
            if (limit != null) env.onLimit(limit)
            if (!allowed) continue
            if (!env.broadcastVia(packet.packet, p.hops, p.via)) lock.withLock {
                p.sent -= key
                delivered.remove(toAsker)
                p.sentAt.removeLastOrNull()
                sentAt[p.via]?.removeLastOrNull()
            }
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
                    serve()
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

    /** Reads the radio inbox and sends what the patronages wait for (every [RadioTimings.pollEvery]). */
    internal suspend fun serve() {
        refresh()
        servePatronages()
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
        /** At most this many packets per request and per hour and patronage (ADR 0051, 0054 §4). */
        const val MAX_PER_ASK = 5
        const val MAX_PER_HOUR = 20
        /** Per gateway and hour at the bridge, over all patronages (ADR 0054 §4, a parameter of the bridge). */
        const val BRIDGE_PER_GATEWAY_HOUR = 60
        /** Random part of the wait before an offer, on top of the wait by signal. */
        const val JITTER_MS = 2_000L
        const val LIMIT_PATRONAGE = "patronage"
        const val LIMIT_GATEWAY = "gateway"
        /** Packets remembered per asker for an hour, over all askers (bounded besides by the gateway budgets). */
        const val MAX_DELIVERED = 2_000
        const val MAX_ASK_HOPS = 3
        /** Hop limit when the radio did not measure the ask's hops. */
        const val DEFAULT_HOPS = 3
        private const val HOUR_MS = 3_600_000L
        /** How long an offer as patron waits for `pickup`. */
        const val OFFER_MS = 120_000L
        /** Wait of the bridge before an offer, longer than any app's (ADR 0054 §4), plus [JITTER_MS]; no SNR. */
        val BRIDGE_PATRON_WAIT = 30.seconds
        /** How long entries of the radio inbox live. */
        private const val LIFETIME_S = RadioInbox.LIFETIME_SECONDS
        private const val MAX_CACHE = 20_000

        private fun keyOf(bytes: ByteArray): String = Base64.encode(bytes)
    }
}
