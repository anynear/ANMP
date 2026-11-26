package app.anynear.anmp.core

import app.anynear.andp.B64
import app.anynear.anmp.Anmp
import app.anynear.anmp.AnmpCipher
import app.anynear.anmp.AnmpCircleKeys
import app.anynear.anmp.AnmpContent
import app.anynear.anmp.AnmpDevice
import app.anynear.anmp.AnmpEphemeral
import app.anynear.anmp.AnmpHeader
import app.anynear.anmp.AnmpKind
import app.anynear.anmp.AnmpMailbox
import app.anynear.anmp.AnmpMessage
import app.anynear.anmp.AnmpRadio
import app.anynear.anmp.AnmpVerifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.js.JsExport
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The radio as the core sees it (ADR 0057): one channel for everyone (ADR 0051), packets in the public form. The app
 * implements it for its radio (Meshtastic today); the core does everything above it.
 */
interface RadioPort {
    /** Radio connected and the radio channel set up. */
    val ready: StateFlow<Boolean>

    /** ANMP packets heard on the radio channel. */
    val incoming: Flow<RadioFrame>

    /** Sends [payload] to everyone on the radio channel; [hopLimit] `null` = the radio's default. `null` if nothing went out. */
    suspend fun broadcast(payload: ByteArray, hopLimit: Int?): RadioSent?

    /**
     * Other packets the radio heard, not ANMP (NodeInfo, telemetry, position, text …), only who sent them (ADR 0090):
     * a radio of a target is in reach again. ANMP packets come through [incoming].
     */
    val heard: Flow<RadioHeard> get() = emptyFlow()
}

/** A packet from radio [node] was heard, after [hops] hops if the radio measured them; [app]: an ANMP packet. */
class RadioHeard(val node: Int, val hops: Int? = null, val app: Boolean = false)

/**
 * A packet heard on the radio: [fromNode] and [packetId] as the radio network names them (0 if unknown), [atMillis]
 * when it arrived, [hops] it travelled and its signal to noise ratio [snr], if the radio reports them.
 */
class RadioFrame(
    val payload: ByteArray,
    val fromNode: Int = 0,
    val packetId: Int = 0,
    val atMillis: Long = 0,
    val hops: Int? = null,
    val snr: Float? = null,
)

/**
 * A packet that went out; [packetId] as the radio network names it (0 if unknown), [states] what the radio reports
 * afterwards, ending with the last state it knows reliably.
 */
class RadioSent(val packetId: Int, val states: Flow<RadioSendState> = emptyFlow())

/** State of a packet on the radio, as far as the radio reports it. */
@JsExport
enum class RadioSendState {
    /** Waiting, not yet taken by the radio. */
    QUEUED,

    /** The radio took it for sending. */
    HANDED,

    /** A relay or acknowledgement was heard from the network; no proof of arrival. */
    RELAYED,

    /** Sent, but nothing heard within the radio's wait. */
    NO_CONFIRMATION,

    FAILED,
}

/** What the radio way asks the app at the moment it needs it: settings of the person and the device state. */
interface RadioHost {
    /** At least one relay answers. */
    fun online(): Boolean

    /** No longer asked: circle packets over radio are always signed (ADR 0021, since 12.11.2026). */
    @Deprecated("Circle packets over radio are always signed; the switch is gone.")
    fun signCircle(): Boolean = true

    /** "Relay for others" on (default) and enough battery at the radio (ADR 0051). */
    fun mayServeOthers(): Boolean = true

    /** `false`: the queued message [mid] should no longer go over radio (e.g. long delivered over the internet). */
    fun stillWanted(mid: String): Boolean = true
}

/** Times of the patron exchange (ADR 0051); shorter in tests. */
class RadioTimings(
    val offlineBeforeAsk: Duration = 5.minutes,
    /** At most hourly, like the Meshtastic defaults for automatic transmissions (ADR 0127). */
    val askEvery: Duration = 60.minutes,
    val askAnswerWait: Duration = 60.seconds,
    val patronage: Duration = 60.minutes,
    val pollEvery: Duration = 30.seconds,
    val patronWaitMin: Duration = 2.seconds,
    val patronWaitMax: Duration = 20.seconds,
    val sinceOverlap: Duration = 10.minutes,
    val tick: Duration = 5.seconds,
    /** How long the queue follows the states of a packet after the radio took it. */
    val trackSends: Duration = 2.minutes,
    /** Online, an own message waits this long for its receipt over the internet before it goes over radio. */
    val internetFirst: Duration = 30.seconds,
    /** Without a receipt this long after a radio send, it goes once more one step up in [RadioHops.STEPS]. */
    val hopRetry: Duration = 60.seconds,
    /** How long the hop limit that worked is kept per target. */
    val hopMemory: Duration = 7.days,
    /** Receipts over radio wait this long to go out together, one packet per sender device. */
    val receiptDelay: Duration = 3.seconds,
    /** Receipts over radio that could not go out are dropped after this long (ADR 0090). */
    val receiptKeep: Duration = 24.hours,
    /** Retries after a trigger (ADR 0090): at the earliest this long after the last radio send, one gap per retry. */
    val retryGaps: List<Duration> = listOf(10.minutes, 30.minutes, 2.hours, 6.hours),
    /**
     * Retries without a trigger, on the off chance (ADR 0090): this long after the last radio send, one per entry, with
     * [RadioHops.MAX]. They count towards [retryPerDay].
     */
    val blindRetryAfter: List<Duration> = listOf(2.hours, 12.hours),
    /** Retries after a trigger per device in 24 hours, over all targets. */
    val retryPerDay: Int = 20,
    /** A message waits for a trigger at most this long after it was queued. */
    val retryMaxAge: Duration = 7.days,
    /** Random wait before a retry when the target's app or a radio check was heard. */
    val retryWaitApp: Pair<Duration, Duration> = 20.seconds to 60.seconds,
    /** Random wait before a retry when only the target's radio was heard. */
    val retryWaitRadio: Pair<Duration, Duration> = 60.seconds to 180.seconds,
    /** Between two retries after the same trigger. */
    val retryBetween: Duration = 10.seconds,
    /** How often waiting entries are checked for receipts and age. */
    val retryCleanup: Duration = 10.minutes,
)

/**
 * Packets in the public form (ADR 0021, 0051) with the device keys of this installation: circle packets with the
 * circle key, direct ones with the key of the device pair (scope `""`). Packets carry only hourly tags: the sender tag
 * of this device and the route tag of the target (device or circle).
 */
internal class RadioKeys(
    private val device: AnmpDevice,
    /** Mailbox recipient of the own person (ADR 0084, 0107): direct packets over radio go to it. */
    private val mailbox: AnmpMailbox,
    private val nowSeconds: () -> Long,
) {
    private val lock = Mutex()
    private val circleCiphers = mutableMapOf<String, AnmpCipher>()
    private val radioSecrets = mutableMapOf<String, ByteArray>()
    private val tags = mutableMapOf<String, ByteArray>()

    val deviceId: String get() = device.deviceIdText
    val tag: ByteArray get() = device.tag

    private suspend fun circleCipher(secret: String): AnmpCipher = lock.withLock {
        circleCiphers[secret] ?: AnmpCircleKeys.derive(B64.decode(secret)).cipher.also { if (circleCiphers.size < 64) circleCiphers[secret] = it }
    }

    private suspend fun deviceRadioSecret(deviceId: ByteArray): ByteArray = cached(radioSecrets, "d:" + B64.encode(deviceId)) { AnmpRadio.deviceSecret(deviceId) }

    private suspend fun circleRadioSecret(secret: String): ByteArray = cached(radioSecrets, "c:$secret") { AnmpRadio.circleSecret(B64.decode(secret)) }

    private suspend fun routeTag(radioSecret: ByteArray, hour: Long) = cached(tags, "r:" + B64.encode(radioSecret) + ":" + hour) { AnmpRadio.routeTag(radioSecret, hour) }

    private suspend fun senderTag(radioSecret: ByteArray, hour: Long) = cached(tags, "s:" + B64.encode(radioSecret) + ":" + hour) { AnmpRadio.senderTag(radioSecret, hour) }

    private suspend fun cached(map: MutableMap<String, ByteArray>, key: String, make: suspend () -> ByteArray): ByteArray {
        lock.withLock { map[key] }?.let { return it }
        val value = make()
        lock.withLock {
            if (map.size > MAX_CACHE) map.clear()
            map[key] = value
        }
        return value
    }

    private fun hour() = AnmpRadio.hourOf(nowSeconds())

    private suspend fun ownSender(h: Long = hour()) = senderTag(deviceRadioSecret(device.deviceId), h)

    /**
     * Route tags of [radioSecret] for the hours [hours] (ADR 0051 §5a): per epoch the forward chain once from the first,
     * the backward chain once from the last hour, instead of both anew for every hour.
     */
    private suspend fun routeTags(radioSecret: ByteArray, hours: LongArray): Map<Long, ByteArray> {
        val out = mutableMapOf<Long, ByteArray>()
        for ((_, group) in hours.sorted().groupBy { AnmpRadio.epochOf(it) }) {
            val first = group.first()
            val last = group.last()
            val n = (last - first + 1).toInt()
            val backs = ArrayList<ByteArray>(n)
            var b = AnmpRadio.backwardAt(radioSecret, last)
            repeat(n) { i -> if (i > 0) b = AnmpRadio.chainStep(b); backs += b }
            var f = AnmpRadio.forwardAt(radioSecret, first)
            for (i in 0 until n) {
                if (i > 0) f = AnmpRadio.chainStep(f)
                val h = first + i
                if (h in group) out[h] = AnmpRadio.tagOf(f, backs[n - 1 - i])
            }
        }
        return out
    }

    /** Own sender tag of the current hour. */
    suspend fun ownSenderTag(): ByteArray = ownSender()

    /**
     * `true` if [tag] is this device's sender tag in one of the hours around [atSeconds]: an own packet heard back or
     * read from the radio inbox later, written in another hour than now.
     */
    suspend fun isOwnSender(tag: ByteArray, atSeconds: Long): Boolean =
        AnmpRadio.acceptedHours(atSeconds).any { ownSender(it).contentEquals(tag) }

    /**
     * Circle packet (message, reaction, withdrawal), always with the device signature: every member knows the circle
     * key, only the signature proves which member wrote it (ADR 0021).
     */
    suspend fun circlePacket(kind: AnmpKind, circle: CircleState, content: AnmpContent): ByteArray {
        val h = hour()
        val header = AnmpHeader(kind, ownSender(h), epoch = circle.epoch, route = routeTag(circleRadioSecret(circle.secret), h))
        return Anmp.encode(header, content, circle.id, circleCipher(circle.secret), device)
    }

    /**
     * Direct packet (message, reaction, read and delivery receipt), without signature, to [peerDevice]: a person's
     * mailbox recipient (ID and key, ADR 0107) or, for chains without one, a device. Route tag and pair key are made
     * the same way for both, from the ID and key given.
     */
    suspend fun directPacket(kind: AnmpKind, peerDevice: String, peerPublicKey: String, content: AnmpContent): ByteArray {
        val peerId = B64.decode(peerDevice)
        val h = hour()
        val header = AnmpHeader(kind, ownSender(h), route = routeTag(deviceRadioSecret(peerId), h))
        return Anmp.encode(header, content, Anmp.DIRECT_SCOPE, device.directCipher(peerId, B64.decode(peerPublicKey)))
    }

    /** Open request `ask` without tags (ADR 0051 §5a); [since]: until when we have everything. */
    suspend fun askPacket(since: Long): ByteArray = Anmp.encodeOpen(AnmpHeader(AnmpKind.ASK, ownSender(), since = since))

    /** Open answer `patron` of a patron with its one-time key [key]. */
    suspend fun patronPacket(key: ByteArray): ByteArray = Anmp.encodeOpen(AnmpHeader(AnmpKind.PATRON, ownSender(), senderKey = key))

    /**
     * Pickup keys of the last 30 days for this device and [circleSecrets], sealed only for the patron with [patronKey]
     * (ADR 0051 §5a); at most three windows per packet, so one or more packets.
     */
    suspend fun pickupPackets(circleSecrets: List<String>, patronKey: ByteArray): List<ByteArray> {
        val h = hour()
        val secrets = listOf(deviceRadioSecret(mailbox.id), deviceRadioSecret(device.deviceId)) + circleSecrets.map { circleRadioSecret(it) }
        val windows = secrets.flatMap { AnmpRadio.pickupWindows(it, h) }
        val mine = AnmpEphemeral.generate()
        val sender = ownSender(h)
        return windows.chunked(AnmpRadio.PICKUP_MAX_WINDOWS).map { AnmpRadio.pickupPacket(sender, mine, patronKey, h, it) }
    }

    /**
     * Tags of the accepted hours around [atSeconds] (previous, same, next): route tags of this device and of [circles],
     * sender tags of the known devices [senders] (device IDs b64u). For entries of the radio inbox the time of the entry.
     */
    suspend fun table(circles: List<CircleState>, senders: Collection<String>, atSeconds: Long): RadioTable {
        val routes = mutableMapOf<String, RadioTarget>()
        val from = mutableMapOf<String, String>()
        val hours = AnmpRadio.acceptedHours(atSeconds)
        routeTags(deviceRadioSecret(device.deviceId), hours).values.forEach { routes[key(it)] = RadioTarget.Device }
        routeTags(deviceRadioSecret(mailbox.id), hours).values.forEach { routes[key(it)] = RadioTarget.Person }
        for (c in circles) {
            for (secret in c.openSecrets(atSeconds * 1000)) {
                routeTags(circleRadioSecret(secret), hours).values.forEach { routes[key(it)] = RadioTarget.Circle(c.id) }
            }
        }
        for (h in hours) {
            for (d in senders) {
                val id = runCatching { B64.decode(d) }.getOrNull()?.takeIf { it.size == Anmp.DEVICE_BYTES } ?: continue
                from[key(senderTag(deviceRadioSecret(id), h))] = d
            }
        }
        return RadioTable(routes, from)
    }

    /**
     * Opens a circle packet with the current circle key or, until the grace period ends at [atMillis], the previous one.
     * Without a valid signature of [verify] the packet is dropped (ADR 0021).
     */
    suspend fun openCircle(packet: ByteArray, circle: CircleState, verify: AnmpVerifier, atMillis: Long): AnmpMessage? =
        circle.openSecrets(atMillis).firstNotNullOfOrNull {
            Anmp.decode(packet, circle.id, circleCipher(it), verify, requireSignature = true)
        }

    /** Opens a direct packet of the device [peerDevice], sent to this device or, with [toPerson], to the own person. */
    suspend fun openDirect(packet: ByteArray, peerDevice: String, peerPublicKey: String, toPerson: Boolean = false): AnmpMessage? {
        val cipher = if (toPerson) {
            mailbox.directCipher(B64.decode(peerDevice), B64.decode(peerPublicKey))
        } else {
            device.directCipher(B64.decode(peerDevice), B64.decode(peerPublicKey))
        }
        return Anmp.decode(packet, Anmp.DIRECT_SCOPE, cipher, AnmpVerifier.none, requireSignature = false)
    }

    /** The current secret and, while the grace period of the last key change runs at [atMillis], the previous one. */
    private fun CircleState.openSecrets(atMillis: Long): List<String> =
        listOfNotNull(secret, previousSecret?.takeIf { graceUntil > atMillis })

    companion object {
        private const val MAX_CACHE = 4096

        fun key(tag: ByteArray): String = B64.encode(tag)

        /** Header of a packet for the size budget (as large as a real one). */
        fun budgetHeader(kind: AnmpKind, epoch: Int?): AnmpHeader =
            AnmpHeader(kind, ByteArray(Anmp.TAG_BYTES), epoch = epoch, route = ByteArray(Anmp.ROUTE_TAG_BYTES))
    }
}

/** Where a radio packet belongs: to this device, to the own person (ADR 0107) or into an own circle. */
internal sealed class RadioTarget {
    data object Device : RadioTarget()

    data object Person : RadioTarget()

    data class Circle(val circleId: String) : RadioTarget()
}

/** Tags of the accepted hours: route tag → target, sender tag → device ID. */
internal class RadioTable(private val routes: Map<String, RadioTarget>, private val senders: Map<String, String>) {
    fun target(route: ByteArray): RadioTarget? = routes[RadioKeys.key(route)]

    fun sender(tag: ByteArray): String? = senders[RadioKeys.key(tag)]

    /** `true` if a route tag belongs to this device, the own person or an own circle. */
    fun isOwn(route: ByteArray): Boolean = RadioKeys.key(route) in routes
}
