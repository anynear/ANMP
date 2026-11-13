package app.anynear.anmp.core

import app.anynear.andp.B64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Duration

/**
 * Delivery receipts waiting for the radio (ADR 0003, 0051, 0058, 0090): per sender device the message IDs to confirm
 * and the hop limit to use. They wait [wait], so that several messages heard together need one packet, and wait for
 * the radio if it is away, also over restarts (`core.radio.receipts`), at most [keep].
 */
internal class RadioReceipts(
    private val scope: CoroutineScope,
    private val store: KeyValueStore,
    private val port: RadioPort,
    private val wait: Duration,
    private val keep: Duration,
    private val now: () -> Long,
    private val log: (String) -> Unit,
    /**
     * Sends a receipt to [deviceId] for the first IDs of [ids] that fit; returns how many went out (0: nothing went
     * out, -1: the device is unknown, drop them).
     */
    private val send: suspend (deviceId: String, ids: List<ByteArray>, hopLimit: Int?) -> Int,
) {
    @Serializable
    private data class Pending(
        val device: String,
        val ids: List<String> = emptyList(),
        val hopLimit: Int? = null,
        val unknownHops: Boolean = false,
        val failures: Int = 0,
        /** When the oldest ID came (Unix ms). */
        val since: Long = 0,
    )

    private val lock = Mutex()
    private val pending = LinkedHashMap<String, Pending>()
    private var flushing = false

    /** Sends what waited over a restart, once the radio is there. */
    suspend fun start() {
        val t = now()
        val start = lock.withLock {
            load().filter { t - it.since < keep.inWholeMilliseconds }.forEach { pending[it.device] = it }
            save()
            (pending.isNotEmpty() && !flushing).also { if (it) flushing = true }
        }
        if (start) scope.launch { flushLater() }
    }

    /**
     * Confirms message [mid] to [deviceId]. [hops]: hops the message travelled to this radio, if the radio measured it;
     * the receipt then goes with one hop more, otherwise with the radio's own limit.
     */
    suspend fun add(deviceId: String, mid: ByteArray, hops: Int?) {
        val id = B64.encode(mid)
        val start = lock.withLock {
            val p = pending[deviceId] ?: Pending(deviceId, since = now())
            val ids = (p.ids.filter { it != id } + id).takeLast(MAX_PENDING)
            pending[deviceId] = if (hops == null) p.copy(ids = ids, unknownHops = true) else p.copy(ids = ids, hopLimit = maxOf(p.hopLimit ?: 0, hopLimitFor(hops)))
            save()
            (!flushing).also { if (it) flushing = true }
        }
        if (start) scope.launch { flushLater() }
    }

    private suspend fun flushLater() {
        delay(wait)
        while (true) {
            if (!port.ready.value) port.ready.first { it }
            val p = lock.withLock {
                val t = now()
                pending.values.filter { t - it.since >= keep.inWholeMilliseconds }.forEach { pending.remove(it.device) }
                pending.values.firstOrNull() ?: run {
                    flushing = false
                    save()
                    null
                }
            } ?: return
            val ids = p.ids.mapNotNull { runCatching { B64.decode(it) }.getOrNull() }
            val sent = try {
                send(p.device, ids, if (p.unknownHops) null else p.hopLimit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("radio receipt failed ${e.message}")
                0
            }
            if (sent < 0 || ids.isEmpty()) {
                lock.withLock {
                    pending.remove(p.device)
                    save()
                }
                continue
            }
            if (sent == 0) {
                // Not possible right now: again once the radio is back; while it says ready, a few times with a pause.
                if (port.ready.value) {
                    val giveUp = lock.withLock {
                        val f = (pending[p.device]?.failures ?: 0) + 1
                        if (f >= MAX_FAILURES) pending.remove(p.device) else pending[p.device]?.let { pending[p.device] = it.copy(failures = f) }
                        save()
                        f >= MAX_FAILURES
                    }
                    if (giveUp) log("radio receipt: gave up for ${p.device.take(6)}") else delay(wait)
                }
                continue
            }
            lock.withLock {
                val done = p.ids.take(sent).toSet()
                val left = pending[p.device]?.let { it.copy(ids = it.ids.filterNot { id -> id in done }) }
                if (left == null || left.ids.isEmpty()) pending.remove(p.device) else pending[p.device] = left
                save()
            }
        }
    }

    private fun load(): List<Pending> =
        store.getString(KEY)?.let { runCatching { coreJson.decodeFromString(ListSerializer(Pending.serializer()), it) }.getOrNull() }.orEmpty()

    private fun save() =
        store.putString(KEY, if (pending.isEmpty()) null else coreJson.encodeToString(ListSerializer(Pending.serializer()), pending.values.toList()))

    companion object {
        /** Name in the app's store (ADR 0034 §5): receipts over radio still to send, only on this device. */
        const val KEY = "core.radio.receipts"

        /** IDs waiting per sender device; older ones drop out (the internet receipt still goes). */
        const val MAX_PENDING = 50

        /** Failed sends while the radio says ready, before the receipts of a device are dropped. */
        const val MAX_FAILURES = 5

        /** Hop limit of a packet answering one that travelled [hops]: one more, at least 1, at most [RadioHops.MAX]. */
        fun hopLimitFor(hops: Int): Int = (hops + 1).coerceIn(1, RadioHops.MAX)
    }
}
