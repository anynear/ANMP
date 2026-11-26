package app.anynear.anmp.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** An own message waiting for the radio: [circleId] or [deviceId] as target, text as it goes over radio. */
@Serializable
internal data class RadioQueued(
    val mid: String,
    val circleId: String? = null,
    val deviceId: String? = null,
    val ts: Long,
    val text: String,
    val attempts: Int = 0,
    /** Answer to this message ID and author tag (b64u, ADR 0092); goes along over radio only if it fits. */
    val replyTo: String? = null,
    val replyFrom: String? = null,
    /** A place that goes along if it fits (ADR 0121 §8); missing in entries queued before. */
    val spot: MessageSpot? = null,
    /** Direct only: what the reader sees of the sender's place, goes along if it fits (ADR 0121 §20). */
    val place: PlaceShare? = null,
    /** Not before this time (Unix ms): online, the internet goes first (ADR 0087). 0: at once. */
    val notBefore: Long = 0,
    /** Hop limit of the next send; `null`: from [RadioHops] for the target. */
    val hops: Int? = null,
    /** When it was queued first (Unix ms, ADR 0090: lives at most [RadioTimings.retryMaxAge]); 0 = not yet known. */
    val since: Long = 0,
    /** The next send is a further try one step up in [RadioHops.STEPS] (ADR 0087); [firstHops] was the limit of the last one. */
    val escalate: Boolean = false,
    val firstHops: Int? = null,
    /** Both tries are out: waits for a trigger (ADR 0090), not for the clock. */
    val waiting: Boolean = false,
    /** Last time it went over radio (Unix ms). */
    val lastSent: Long = 0,
    /** Retries after a trigger so far (ADR 0090). */
    val retries: Int = 0,
    /** The next send is a retry after a trigger. */
    val retry: Boolean = false,
    /** Retries without a trigger so far (ADR 0090, [RadioTimings.blindRetryAfter]). */
    val blindRetries: Int = 0,
) {
    /** One entry per message and target: a direct message to a person with several devices goes once to each. */
    val key: String get() = mid + "|" + RadioHops.targetOf(this)
}

/**
 * Hop limits per target (ADR 0087, ADR 0127): tries in [STEPS], 3 (the Meshtastic default), then 5, then 7; the one that
 * worked is kept for a while, so the next message to the same person or circle starts there instead of going out again.
 */
internal class RadioHops(private val store: KeyValueStore, private val now: () -> Long, private val keep: Long) {
    @Serializable
    private data class Kept(val hops: Int, val at: Long)

    private val serializer = kotlinx.serialization.builtins.MapSerializer(String.serializer(), Kept.serializer())

    private fun load(): Map<String, Kept> =
        store.getString(KEY)?.let { runCatching { coreJson.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    /** Hop limit for the first send to [target]. */
    fun start(target: String): Int = load()[target]?.takeIf { now() - it.at < keep }?.hops ?: FIRST

    /** Start with [hops] for [target] from now on: it reached it, or a lower limit did not; kept for [keep]. */
    fun worked(target: String, hops: Int) {
        val t = now()
        val next = (load().filterValues { t - it.at < keep } + (target to Kept(hops, t)))
        store.putString(KEY, coreJson.encodeToString(serializer, next))
    }

    companion object {
        /** Hop limits of the tries one after the other (ADR 0127): the first one is the Meshtastic default. */
        val STEPS = listOf(3, 5, 7)
        val FIRST = STEPS.first()
        val MAX = STEPS.last()

        /** Hop limit of the try after one with [hops]: the next step, at most [MAX]. */
        fun after(hops: Int): Int = STEPS.firstOrNull { it > hops } ?: MAX

        /** Name in the app's store (ADR 0034 §5). */
        const val KEY = "core.radio.hops"

        fun targetOf(entry: RadioQueued): String = entry.circleId?.let { "c:$it" } ?: "d:${entry.deviceId}"
    }
}

/** How a trigger for a retry was heard (ADR 0090): the app of the target, only its radio, or a radio check. */
internal enum class HeardBy { APP, RADIO, CHECK }

/**
 * Queue of own messages for the radio (ADR 0057 step 5, ADR 0087, ADR 0090), kept over restarts in the store of the
 * app. A message goes out once, after each [RadioTimings.hopRetry] without receipt once more one step up in [RadioHops.STEPS], and then
 * waits for a trigger: a packet heard from a radio of the target or an answered radio check. Then it goes again after a
 * random wait, within the limits of [RadioTimings] (gaps, per message, per day, maximum age). It is dropped as soon as
 * it is no longer wanted ([RadioHost.stillWanted]). A send the radio provably never took (never
 * [RadioSendState.HANDED]) is repeated quietly. The packet is built when it goes out, with the tags of that hour.
 */
/**
 * The order in which packets get the radio (ADR 0083 §7, ADR 0121 §13). The radio network is slow and shared, so what
 * matters most goes first. Internal: it is no field of a packet and says nothing to other devices.
 */
internal enum class RadioPriority {
    /** A call for the helpers and the answers to it: before everything, and kept until the radio is there. */
    FIRST,

    /** Signs of life and what people write: in the order they came. */
    MESSAGE,

    /** Where someone is: only when no message waits. */
    LAST,
    ;

    companion object {
        fun of(kind: app.anynear.anmp.AnmpKind): RadioPriority = when (kind) {
            app.anynear.anmp.AnmpKind.SOS -> FIRST
            app.anynear.anmp.AnmpKind.POSITION -> LAST
            else -> MESSAGE
        }
    }
}

internal class RadioQueue(
    private val scope: CoroutineScope,
    private val store: KeyValueStore,
    private val port: RadioPort,
    private val host: RadioHost,
    private val timings: RadioTimings,
    /** Builds the packet; `null` if the circle or device is gone or it does not fit. */
    private val packet: suspend (RadioQueued) -> ByteArray?,
    private val report: suspend (mid: String, packetId: Int, state: RadioSendState) -> Unit,
    /** Radio node numbers of the target of an entry (ADR 0058 field 10), as the radio network names them. */
    private val nodesOf: (RadioQueued) -> Set<Int>,
    private val log: (String) -> Unit,
    private val now: () -> Long,
    /**
     * What goes before every queued message ([RadioPriority.FIRST], a call for the helpers): sent first whenever the
     * queue gets its turn.
     */
    private val first: suspend () -> Unit = {},
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
) {
    private val hops = RadioHops(store, now, timings.hopMemory.inWholeMilliseconds)

    private val lock = Mutex()
    private val queue = ArrayDeque(load().map { if (it.since == 0L) it.copy(since = now()) else it })
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        scope.launch {
            merge(wake.receiveAsFlow(), port.ready.filter { it }.map { }).collect { drain() }
        }
        scope.launch {
            port.heard.collect { onHeard(it.node, it.hops, if (it.app) HeardBy.APP else HeardBy.RADIO) }
        }
        // Waiting entries that are no longer wanted or too old go now and then, also without a radio.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(timings.retryCleanup)
                cleanup()
            }
        }
        // After a restart: messages that still wait (internet first, second try) go out when their time is up, and
        // waiting ones get their try on the off chance.
        queue.filter { !it.waiting }.map { it.notBefore }.filter { it > now() }.minOrNull()?.let(::wakeAt)
        queue.mapNotNull(::blindDue).minOrNull()?.let(::wakeAt)
    }

    /** `true` if an entry matching [predicate] waits. */
    suspend fun has(predicate: (RadioQueued) -> Boolean): Boolean = lock.withLock { queue.any(predicate) }

    /** `true` while a message waits for its first way out: packets of [RadioPriority.LAST] stand back then. */
    suspend fun busy(): Boolean = lock.withLock { val t = now(); queue.any { !it.waiting && it.notBefore <= t } }

    /** Gives the queue its turn, e.g. after something of [RadioPriority.FIRST] was handed in. */
    fun kick() {
        wake.trySend(Unit)
    }

    suspend fun add(entry: RadioQueued) {
        // Online the internet goes first: over radio only if no receipt came in time (ADR 0087).
        val wait = if (host.online()) timings.internetFirst.inWholeMilliseconds else 0L
        val t = now()
        val queued = entry.copy(notBefore = maxOf(entry.notBefore, t + wait), since = entry.since.takeIf { it > 0 } ?: t)
        lock.withLock {
            if (queue.any { it.key == entry.key }) return
            queue.addLast(queued)
            save()
        }
        wakeAt(queued.notBefore)
    }

    /**
     * Something was heard from radio [node] (ADR 0090): waiting messages for that target go again after a random wait,
     * within the limits. [heardHops]: hops the packet travelled, if known; the retry goes with one more.
     */
    suspend fun onHeard(node: Int, heardHops: Int?, by: HeardBy) {
        if (node == 0) return
        val t = now()
        val (minWait, maxWait) = when (by) {
            HeardBy.RADIO -> timings.retryWaitRadio
            HeardBy.APP, HeardBy.CHECK -> timings.retryWaitApp
        }
        var first: Long? = null
        lock.withLock {
            val due = queue.filter { it.waiting && node in nodesOf(it) && it.retries < timings.retryGaps.size && t - it.lastSent >= timings.retryGaps[it.retries].inWholeMilliseconds }
            if (due.isEmpty()) return
            val sent = retryLog().filter { t - it < DAY_MS }
            var budget = timings.retryPerDay - sent.size
            if (budget <= 0) return log("radio queue: retry limit for today reached")
            val chosen = due.filter { !expired(it, t) && host.stillWanted(it.mid) }
                .groupBy(RadioHops::targetOf).values.flatMap { it.sortedBy { e -> e.since }.take(MAX_PER_TRIGGER) }
                .sortedBy { it.since }
            val base = t + random.nextLong(minWait.inWholeMilliseconds, maxWait.inWholeMilliseconds + 1)
            val newLog = sent.toMutableList()
            chosen.forEachIndexed { i, e ->
                if (budget <= 0) return@forEachIndexed
                budget--
                val at = base + i * timings.retryBetween.inWholeMilliseconds
                newLog += at
                replace(e.key) {
                    it.copy(waiting = false, retry = true, retries = it.retries + 1, notBefore = at, hops = heardHops?.let(RadioReceipts::hopLimitFor))
                }
                first = minOf(first ?: at, at)
                log("radio queue: ${e.mid.take(6)} heard ${by.name.lowercase()} of the target, retry ${e.retries + 1}")
            }
            saveRetryLog(newLog)
            save()
        }
        first?.let(::wakeAt)
    }

    /** Takes every entry of message [mid] out (deleted by the person): it does not go over radio any more. */
    suspend fun cancel(mid: String): Boolean = lock.withLock {
        val removed = queue.removeAll { it.mid == mid }
        if (removed) {
            save()
            log("radio queue: ${mid.take(6)} withdrawn")
        }
        removed
    }

    /** Look at the queue again at [at] (Unix ms), or at once. */
    private fun wakeAt(at: Long) {
        val delayMs = at - now()
        if (delayMs <= 0) wake.trySend(Unit) else scope.launch {
            kotlinx.coroutines.delay(delayMs)
            wake.trySend(Unit)
        }
    }

    private fun expired(e: RadioQueued, t: Long) = e.since > 0 && t - e.since > timings.retryMaxAge.inWholeMilliseconds

    /** When a waiting entry gets its next try on the off chance (Unix ms), `null` if it has none left. */
    private fun blindDue(e: RadioQueued): Long? =
        if (!e.waiting) null else timings.blindRetryAfter.getOrNull(e.blindRetries)?.let { e.lastSent + it.inWholeMilliseconds }

    private fun exhausted(e: RadioQueued) = e.waiting && e.retries >= timings.retryGaps.size && blindDue(e) == null

    /**
     * Drops entries that are no longer wanted or too old, and waiting ones out of retries; puts waiting ones whose try on
     * the off chance is due back in line, with [RadioHops.MAX] after a random wait (ADR 0090).
     */
    private suspend fun cleanup() {
        var wakeAtMs: Long? = null
        lock.withLock {
            val t = now()
            val gone = queue.filter { expired(it, t) || !host.stillWanted(it.mid) || exhausted(it) }
            // A receipt came before the next try: the last hop limit was enough for this target.
            gone.filter { it.escalate && !host.stillWanted(it.mid) }.forEach { e -> e.firstHops?.let { hops.worked(RadioHops.targetOf(e), it) } }
            queue.removeAll { it in gone }
            val due = queue.filter { e -> blindDue(e)?.let { it <= t } == true }.sortedBy { it.since }
            if (due.isNotEmpty()) {
                val sent = retryLog().filter { t - it < DAY_MS }.toMutableList()
                val (minWait, maxWait) = timings.retryWaitRadio
                due.forEachIndexed { i, e ->
                    if (sent.size >= timings.retryPerDay) return@forEachIndexed
                    val at = t + random.nextLong(minWait.inWholeMilliseconds, maxWait.inWholeMilliseconds + 1) + i * timings.retryBetween.inWholeMilliseconds
                    sent += at
                    replace(e.key) { it.copy(waiting = false, retry = true, blindRetries = it.blindRetries + 1, notBefore = at, hops = RadioHops.MAX) }
                    log("radio queue: ${e.mid.take(6)} no trigger, try ${e.blindRetries + 1} on the off chance")
                }
                saveRetryLog(sent)
            }
            if (gone.isNotEmpty() || due.isNotEmpty()) save()
            // Only times ahead: what is due now goes in this drain, or when the radio is back.
            wakeAtMs = (queue.filter { !it.waiting }.map { it.notBefore } + queue.mapNotNull(::blindDue)).filter { it > t }.minOrNull()
        }
        wakeAtMs?.let(::wakeAt)
    }

    private suspend fun drain() {
        cleanup()
        while (port.ready.value) {
            first()
            val t = now()
            val next = lock.withLock { queue.firstOrNull { !it.waiting && it.notBefore <= t } } ?: run {
                lock.withLock { queue.filter { !it.waiting }.minOfOrNull { it.notBefore } }?.let(::wakeAt)
                return
            }
            val target = RadioHops.targetOf(next)
            if (!host.stillWanted(next.mid) || expired(next, t)) {
                // A receipt came in time: the last hop limit was enough for this target.
                if (next.escalate) next.firstHops?.let { hops.worked(target, it) }
                remove(next)
                continue
            }
            if (next.escalate) {
                val up = next.hops ?: RadioHops.MAX
                log("radio queue: ${next.mid.take(6)} no receipt, again with $up hops")
                // The lower limit was not enough: the next message to this target starts here at once, whatever this try
                // brings. A circle with a member that is never reachable would otherwise send every message three times.
                hops.worked(target, up)
            }
            val bytes = packet(next)
            if (bytes == null) {
                remove(next)
                continue
            }
            val hopLimit = next.hops ?: hops.start(target)
            val sent = try {
                port.broadcast(bytes, hopLimit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return // Not possible right now; again when the radio is back.
            val after = if (next.retry || hopLimit >= RadioHops.MAX) {
                next.copy(waiting = true, lastSent = t, escalate = false, retry = false, hops = null, notBefore = 0)
            } else {
                next.copy(escalate = true, firstHops = hopLimit, hops = RadioHops.after(hopLimit), notBefore = t + timings.hopRetry.inWholeMilliseconds, lastSent = t)
            }
            lock.withLock {
                replace(next.key) { after }
                save()
            }
            (if (after.waiting) blindDue(after) else after.notBefore)?.let(::wakeAt)
            log(
                "radio queue: ${next.mid.take(6)} sent to ${RadioHops.targetOf(next).take(8)} with $hopLimit hops, packet ${sent.packetId}" +
                    when {
                        next.retry -> ", retry"
                        next.escalate -> ", try with more hops"
                        else -> ""
                    },
            )
            report(next.mid, sent.packetId, RadioSendState.QUEUED)
            scope.launch { track(next.copy(attempts = next.attempts + 1, hops = hopLimit), sent) }
        }
    }

    private suspend fun track(entry: RadioQueued, sent: RadioSent) {
        var taken = false
        var again = false
        withTimeoutOrNull(timings.trackSends) {
            sent.states
                .takeWhile { state ->
                    if (state != RadioSendState.QUEUED && state != RadioSendState.FAILED) taken = true
                    // The radio never took it (e.g. connection gone while handing over): queue it again quietly.
                    again = state == RadioSendState.FAILED && !taken && entry.attempts < MAX_ATTEMPTS
                    !again
                }
                .collect { report(entry.mid, sent.packetId, it) }
        }
        if (again) {
            log("radio queue: ${entry.mid.take(6)} again, attempt ${entry.attempts + 1}")
            lock.withLock {
                // Back to the state before this send, at once and with the same hop limit.
                if (queue.none { it.key == entry.key }) queue.addFirst(entry.copy(notBefore = 0)) else replace(entry.key) { entry.copy(notBefore = 0) }
                save()
            }
            wake.trySend(Unit)
        }
    }

    /** Replaces the entry with [key] in place; call with [lock] held. */
    private fun replace(key: String, change: (RadioQueued) -> RadioQueued) {
        val i = queue.indexOfFirst { it.key == key }
        if (i >= 0) queue[i] = change(queue[i])
    }

    private suspend fun remove(entry: RadioQueued) = lock.withLock {
        queue.removeAll { it.key == entry.key }
        save()
    }

    private fun load(): List<RadioQueued> = load(store)

    private fun save() = save(store, queue.toList())

    private fun retryLog(): List<Long> =
        store.getString(RETRY_LOG_KEY)?.let { runCatching { coreJson.decodeFromString(ListSerializer(Long.serializer()), it) }.getOrNull() }.orEmpty()

    private fun saveRetryLog(list: List<Long>) =
        store.putString(RETRY_LOG_KEY, if (list.isEmpty()) null else coreJson.encodeToString(ListSerializer(Long.serializer()), list))

    companion object {
        fun load(store: KeyValueStore): List<RadioQueued> =
            store.getString(KEY)?.let { runCatching { coreJson.decodeFromString(ListSerializer(RadioQueued.serializer()), it) }.getOrNull() }.orEmpty()

        fun save(store: KeyValueStore, list: List<RadioQueued>) =
            store.putString(KEY, if (list.isEmpty()) null else coreJson.encodeToString(ListSerializer(RadioQueued.serializer()), list))


        /** Name in the app's store; protocol constant (ADR 0034 §5), goes into backup and move. */
        const val KEY = "core.radio.queue"

        /** Times of the retries after a trigger, for the limit per day (ADR 0090); only on this device. */
        const val RETRY_LOG_KEY = "core.radio.retries"
        const val MAX_ATTEMPTS = 20

        /** Messages per target and trigger; the rest at the next trigger. */
        const val MAX_PER_TRIGGER = 3
        private const val DAY_MS = 24L * 3600 * 1000
    }
}
