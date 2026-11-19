package app.anynear.anmp.core

import app.anynear.andp.Credentials
import app.anynear.andp.Fetched
import app.anynear.andp.GetOptions
import app.anynear.andp.Hash
import app.anynear.andp.ID
import app.anynear.andp.Key
import app.anynear.andp.Meta
import app.anynear.andp.AndpHttpException
import app.anynear.andp.Placement
import app.anynear.andp.PutOptions
import app.anynear.andp.Ref
import app.anynear.andp.Relay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Duration

/** One object of a pending write. [done]: node keys that confirmed it (receipt). */
@Serializable
internal data class PendingObject(
    val id: String,
    val size: Long,
    val until: Long,
    val entry: Boolean,
    val refs: List<Ref> = emptyList(),
    val done: List<String> = emptyList(),
    /** Revocation entry: the revoked grant hash (ANDP §4.3). */
    val revokes: String? = null,
    /** Space of this object when it differs from the job's (history parts in our own mailbox). */
    val space: String? = null,
    /** Failed tries so far (ADR 0090): after a restart the plan goes on at this step. */
    val tries: Int = 0,
)

/**
 * A write that survives restarts (ADR 0016): contents first, then the entry (ADR 0035 §4: if a content
 * fails, no entry is written). The bytes lie in the [BlobStore] (ciphertext only); the metadata is
 * signed anew for every attempt because relays accept client writes only near the current time (ANDP §3 rule 5).
 */
@Serializable
internal data class PendingJob(
    val id: String,
    val space: String,
    val contents: List<PendingObject>,
    val entry: PendingObject?,
    /** Message ID or object ID for [CoreEvent.Stored]; `""` for writes without status. */
    val ref: String,
    val notify: Boolean,
    val createdAt: Long,
    /** Wake with alarm priority (`ANDP-Notify: alarm`, alerts with priority alarm, ADR 0062). */
    val alarm: Boolean = false,
    /**
     * Receipt entry (ANMP kind `hold`, ADR 0088 §2) that carries the refs to [contents] with their expiry, written
     * after the contents and before [entry], which then has no refs of its own and may live without expiry.
     */
    val hold: PendingObject? = null,
)

/** Outcome of a read of a content. */
internal sealed class ReadResult {
    class Found(val fetched: Fetched, val missingAt: List<Relay>) : ReadResult()

    data object Gone : ReadResult()

    data object Later : ReadResult()
}

/**
 * Writes and reads objects over the relays of the network: placement, retry with backoff, pending writes over
 * restarts, receipts, read via any relay with holders and proxy (ANDP §9, ADR 0028 §6a).
 */
internal class Transport(
    private val scope: CoroutineScope,
    private val platform: AnmpPlatform,
    private val net: NetworkDirectory,
    private val pool: RelayPool,
    private val timings: CoreTimings,
    /** Credentials for a space, also after a restart; `null` drops the write (circle left, grant gone). */
    private val access: suspend (space: String) -> SpaceAccess?,
    private val ownWatchId: () -> String,
    private val events: suspend (CoreEvent) -> Unit,
    /**
     * Every holder refused a message entry for [space] with 403 (no right, revoked grant): the owner of that mailbox
     * most likely took the grant back, e.g. after deleting everything (ADR 0046). [grant]: hash of the grant we wrote
     * with. `true` if another grant for the space is worth one more try (the refused one was dropped).
     */
    private val denied: suspend (space: String, grant: String?) -> Boolean = { _, _ -> false },
) {
    private val lock = Mutex()
    private val running = mutableSetOf<String>()
    private val wake = Wake()

    /** Ends every wait of the delivery plan at once (network back, foreground, relay answers again; ADR 0090). */
    fun kick() = wake.kick()

    /** Jobs withdrawn by [cancel] while they run; their loops stop at the next step. */
    private val cancelled = mutableSetOf<String>()

    private suspend fun isCancelled(job: PendingJob) = lock.withLock { job.id in cancelled }

    /**
     * Withdraws every pending write of the message [ref] (deleted by the person): nothing of it goes out any more, also
     * not after a restart. What a relay already has stays there. `true` if something was pending.
     */
    suspend fun cancel(ref: String): Boolean {
        if (ref.isEmpty()) return false
        val jobs = load().filter { it.ref == ref }
        if (jobs.isEmpty()) return false
        lock.withLock { cancelled += jobs.map { it.id } }
        jobs.forEach { drop(it) }
        platform.log.log("write: ${ref.take(6)} withdrawn, ${jobs.size} pending")
        wake.kick()
        return true
    }

    /** Starts every pending write again (after a restart). */
    suspend fun resumeAll() {
        val now = platform.clock.nowMillis()
        val (fresh, old) = load().partition { now - it.createdAt < timings.pendingMaxAge.inWholeMilliseconds }
        old.forEach { drop(it) }
        fresh.forEach { job -> scope.launch { run(job) } }
    }

    /**
     * Queues a write. [contents] are (object ID, sealed bytes) with placement object; [entry] the enveloped
     * packet with placement space and `refs` to the contents. Returns at once; [CoreEvent.Stored] and
     * [CoreEvent.SendFailed] tell the outcome.
     */
    suspend fun submit(
        space: Key,
        entry: ByteArray?,
        until: Long,
        contents: List<Pair<ID, ByteArray>> = emptyList(),
        contentUntil: Long = until,
        ref: String = "",
        notify: Boolean = false,
        /** With [notify]: wake with alarm priority (ANDP §10). */
        alarm: Boolean = false,
        revokes: String? = null,
        /** Space of the contents when they do not lie in [space]; then the entry carries no refs to them. */
        contentSpace: Key? = null,
        /** Access known at submit time; after a restart it comes from the resolver. */
        known: SpaceAccess? = null,
        knownContent: SpaceAccess? = null,
        /** Further refs of the entry to contents already stored in [space] (ADR 0067: changed details, same file). */
        refs: List<Ref> = emptyList(),
        /**
         * Receipt entry (kind `hold`, ADR 0088 §2): takes the refs to the own [contents] with [contentUntil], so that
         * an [entry] without expiry does not count them on the space's quota for ever.
         */
        hold: ByteArray? = null,
    ): PendingJob {
        val jobId = ID.of(space.unsafe() + (entry ?: ByteArray(0)) + contents.flatMap { it.first.bytes.toList() }.toByteArray() + platform.clock.nowMillis().toString().encodeToByteArray()).toString().take(22)
        contents.forEach { (id, bytes) -> platform.blobs.write(blobName(jobId, id.toString()), bytes) }
        val contentObjects = contents.map { (id, bytes) ->
            PendingObject(id.toString(), bytes.size.toLong(), contentUntil, entry = false, space = contentSpace?.toString())
        }
        val own = if (contentSpace == null) contentObjects.map { Ref(ID.parse(it.id), it.size) } else emptyList()
        val holdObject = hold?.takeIf { own.isNotEmpty() }?.let { h ->
            val id = ID.of(h)
            platform.blobs.write(blobName(jobId, id.toString()), h)
            PendingObject(id.toString(), h.size.toLong(), contentUntil, entry = true, refs = own)
        }
        val entryObject = entry?.let { e ->
            val id = ID.of(e)
            platform.blobs.write(blobName(jobId, id.toString()), e)
            PendingObject(id.toString(), e.size.toLong(), until, entry = true, refs = (if (holdObject == null) own else emptyList()) + refs, revokes = revokes)
        }
        val job = PendingJob(jobId, space.toString(), contentObjects, entryObject, ref, notify, platform.clock.nowMillis(), alarm = notify && alarm, hold = holdObject)
        save { it + job }
        scope.launch { run(job, known, knownContent) }
        return job
    }

    private suspend fun run(job: PendingJob, known: SpaceAccess? = null, knownContent: SpaceAccess? = null) {
        if (!lock.withLock { running.add(job.id) }) return
        try {
            val a = known ?: access(job.space)
            if (a == null) {
                platform.log.log("write ${job.id}: no access to the space any more")
                if (job.ref.isNotEmpty()) events(CoreEvent.SendFailed(job.ref))
                return drop(job)
            }
            val deadline = job.createdAt + timings.pendingMaxAge.inWholeMilliseconds
            // Contents: every one must be stored once before the entry goes out; the rest continues in the background.
            val firsts = job.contents.map { obj ->
                val first = CompletableDeferred<Boolean>()
                val oa = obj.space?.let { knownContent ?: access(it) } ?: a
                scope.launch { deliver(job, obj, oa, deadline) { n -> if (n >= 1) first.complete(true) }.also { first.complete(it) } }
                first
            }
            val contentsOk = firsts.all { it.await() }
            if (!contentsOk) {
                platform.log.log("write ${job.id}: a content was refused, entry not written")
                if (job.ref.isNotEmpty()) events(CoreEvent.SendFailed(job.ref))
                return drop(job)
            }
            // The receipt with the refs (ADR 0088 §2) must be stored once before the message, like a content.
            val hold = job.hold
            if (hold != null && !deliver(job.copy(notify = false, alarm = false), hold, a, deadline) { }) {
                platform.log.log("write ${job.id}: the receipt was refused, entry not written")
                if (job.ref.isNotEmpty()) events(CoreEvent.SendFailed(job.ref))
                return drop(job)
            }
            val entry = job.entry
            if (isCancelled(job)) return
            val stored: suspend (Int) -> Unit = { n -> if (job.ref.isNotEmpty()) events(CoreEvent.Stored(job.ref, n)) }
            var retry = false
            var ok = if (entry == null) true else deliver(job, entry, a, deadline, onDenied = { retry = it }, onStored = stored)
            // Refused for a revoked grant while another one is there (contact added again): try with that one.
            var used: SpaceAccess = a
            var left = MAX_GRANT_RETRIES
            while (!ok && retry && entry != null && left-- > 0 && !isCancelled(job)) {
                retry = false
                val fresh = access(job.space) ?: break
                if (fresh.grant?.hash == used.grant?.hash) break
                used = fresh
                ok = deliver(job, entry, fresh, deadline, onDenied = { retry = it }, onStored = stored)
            }
            if (!ok && job.ref.isNotEmpty()) events(CoreEvent.SendFailed(job.ref))
            // Wait for the contents to reach all their holders before forgetting the job.
            drop(job)
        } finally {
            // A withdrawn job stays in [cancelled]: its contents may still be on their way in the background.
            lock.withLock { running.remove(job.id) }
        }
    }

    /**
     * Puts [obj] at all its holders until each confirmed or [deadline]. [onStored] gets the number of relays that
     * have it after every new receipt. `false` if no relay took it.
     */
    private suspend fun deliver(
        job: PendingJob, obj: PendingObject, a: SpaceAccess, deadline: Long, onDenied: (retry: Boolean) -> Unit = {}, onStored: suspend (Int) -> Unit,
    ): Boolean {
        val bytes = platform.blobs.read(blobName(job.id, obj.id)) ?: return obj.done.isNotEmpty()
        val done = obj.done.toMutableSet()
        val refused = mutableSetOf<String>()
        val forbidden = mutableSetOf<String>()
        val key = if (obj.entry) job.space else obj.id
        val space = obj.space ?: job.space
        var tries = obj.tries
        suspend fun waitAfterFailure() {
            tries++
            markTries(job, obj.id, tries)
            wake.sleep(timings.writeRetry.waitAfter(tries))
        }
        while (platform.clock.nowMillis() < deadline) {
            if (isCancelled(job)) break
            if (obj.until != 0L && obj.until * 1000 <= platform.clock.nowMillis()) break
            val targets = net.holders(key)
            if (targets.isEmpty()) {
                net.await(timings.writeRetry.waitAfter(tries + 1))
                if (net.holders(key).isEmpty()) waitAfterFailure()
                continue
            }
            val remaining = targets.filter { it.node.toString() !in done && it.node.toString() !in refused }
            if (remaining.isEmpty()) break
            coroutineScope {
                remaining.forEach { relay ->
                    launch {
                        when (put(relay, a, space, obj, bytes, job.notify, job.alarm)) {
                            PutOutcome.OK -> {
                                val n = lock.withLock { done += relay.node.toString(); done.size }
                                markDone(job, obj.id, relay.node.toString())
                                onStored(n)
                            }
                            PutOutcome.REFUSED -> lock.withLock { refused += relay.node.toString() }
                            PutOutcome.FORBIDDEN -> lock.withLock {
                                refused += relay.node.toString()
                                forbidden += relay.node.toString()
                            }
                            PutOutcome.RETRY -> Unit
                        }
                    }
                }
            }
            if (net.holders(key).all { it.node.toString() in done || it.node.toString() in refused }) break
            waitAfterFailure()
        }
        // A message entry no holder took, every one with 403: tell the core whose mailbox refuses us.
        if (done.isEmpty() && obj.entry && job.ref.isNotEmpty() && forbidden.isNotEmpty() && forbidden == refused &&
            net.holders(key).all { it.node.toString() in forbidden }
        ) {
            onDenied(denied(job.space, a.grant?.hash?.toString()))
        }
        return done.isNotEmpty()
    }

    private enum class PutOutcome { OK, REFUSED, FORBIDDEN, RETRY }

    private suspend fun put(relay: Relay, a: SpaceAccess, space: String, obj: PendingObject, bytes: ByteArray, notify: Boolean, alarm: Boolean): PutOutcome {
        val client = pool.client(relay) ?: return PutOutcome.REFUSED
        return try {
            val meta = sign(a, ID.parse(obj.id), bytes.size.toLong(), obj.until, if (obj.entry) Placement.SPACE else Placement.OBJECT, obj.refs,
                revokes = obj.revokes?.let { Hash.parse(it) })
            val wake = notify && obj.entry
            client.put(
                Credentials(if (meta.writer == a.space) a.owner ?: a.signer else a.signer), meta, bytes,
                PutOptions(noNotify = !wake, watchSkip = if (wake) ownWatchId() else "", alarm = wake && alarm),
            )
            pool.ok(relay.node)
            PutOutcome.OK
        } catch (e: CancellationException) {
            throw e
        } catch (e: AndpHttpException) {
            pool.ok(relay.node)
            if (permanent(e.status)) {
                platform.log.log("put $space/${obj.id.take(8)} at ${relay.site}: refused ${e.status}")
                if (e.status == 403) PutOutcome.FORBIDDEN else PutOutcome.REFUSED
            } else {
                PutOutcome.RETRY
            }
        } catch (e: Exception) {
            pool.failed(relay.node)
            PutOutcome.RETRY
        }
    }

    /** Signs object metadata with the current time; contents without refs. */
    suspend fun sign(a: SpaceAccess, id: ID, size: Long, until: Long, placement: Placement, refs: List<Ref>, owner: Boolean = false, revokes: Hash? = null): Meta {
        val ts = platform.clock.nowMillis() / 1000
        val signer = if (owner || revokes != null) a.owner ?: a.signer else a.signer
        return Meta.sign(signer, a.space, id, size, ts, until, placement, refs, revokes, grant = if (signer.key == a.space) null else a.grant)
    }

    /**
     * Puts an object of someone else unchanged in its bytes, refs and expiry, re-signed with the owner key
     * (ADR 0028 §6b; see "Core on ANDP" in spec/anmp.md for why the original signature cannot be reused).
     */
    suspend fun reput(relay: Relay, a: SpaceAccess, meta: Meta, bytes: ByteArray): Boolean {
        if (a.owner == null) return false
        if (meta.until != 0L && meta.until * 1000 <= platform.clock.nowMillis() + 60_000) return false
        val client = pool.client(relay) ?: return false
        return try {
            val m = sign(a, meta.id, meta.size, meta.until, meta.placement, meta.refs, owner = true)
            client.put(Credentials(a.owner), m, bytes, PutOptions(noNotify = true))
            pool.ok(relay.node)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: AndpHttpException) {
            pool.ok(relay.node)
            false
        } catch (e: Exception) {
            pool.failed(relay.node)
            false
        }
    }

    /**
     * Reads a content: its holders first (healthy first), then the other active relays; follows `holders` of a
     * 404 only to relays of our list; a 410 ends the search. If holders are out of reach, asks a relay that
     * answered to proxy (`ANDP-Fetch: proxy`). [ReadResult.Found.missingAt] are chosen holders that answered 404.
     */
    suspend fun readContent(space: Key, id: ID): ReadResult {
        val chosen = net.place(id.toString()).chosen
        val queue = ArrayDeque(net.readOrder(id.toString()))
        if (queue.isEmpty()) return ReadResult.Later
        val tried = mutableSetOf<Key>()
        val answered = mutableListOf<Relay>()
        val missing = mutableListOf<Relay>()
        var allAnswered = true
        var hinted = false
        while (queue.isNotEmpty()) {
            val relay = queue.removeFirst()
            if (!tried.add(relay.node)) continue
            val client = pool.client(relay) ?: continue
            try {
                val f = client.get(null, space, id)
                pool.ok(relay.node)
                return ReadResult.Found(f, missing)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AndpHttpException) {
                pool.ok(relay.node)
                answered += relay
                when {
                    e.isGone -> return ReadResult.Gone
                    e.isNotFound -> {
                        if (chosen.any { it.node == relay.node }) missing += relay
                        val holders = e.holders.orEmpty()
                        if (holders.isNotEmpty()) hinted = true
                        holders.mapNotNull { net.known(it) }.filter { it.node !in tried }.asReversed().forEach { queue.addFirst(it) }
                    }
                    else -> allAnswered = false
                }
            } catch (e: Exception) {
                // Includes a failed verification: that relay is broken or malicious, try the next.
                if (e !is app.anynear.andp.AndpException) pool.failed(relay.node)
                allAnswered = false
            }
        }
        if (hinted || !allAnswered) {
            for (relay in answered) {
                val client = pool.client(relay) ?: continue
                try {
                    return ReadResult.Found(client.get(null, space, id, GetOptions(proxy = true)), missing)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AndpHttpException) {
                    if (e.isGone) return ReadResult.Gone
                } catch (e: Exception) {
                    continue
                }
            }
            return ReadResult.Later
        }
        return ReadResult.Gone
    }

    /** Deletes an object at its holders (and every other active relay: tombstones win, ANDP §5.5). */
    suspend fun delete(cred: Credentials, space: Key, id: ID, entry: Boolean) {
        val key = if (entry) space.toString() else id.toString()
        coroutineScope {
            net.readOrder(key).forEach { relay ->
                launch {
                    val c = pool.client(relay) ?: return@launch
                    runCatching { c.delete(cred, space, id) }.onSuccess { pool.ok(relay.node) }
                }
            }
        }
    }

    private suspend fun markDone(job: PendingJob, objectId: String, node: String) = save { list ->
        list.map { j ->
            if (j.id != job.id) j else j.copy(
                contents = j.contents.map { if (it.id == objectId) it.copy(done = (it.done + node).distinct()) else it },
                entry = j.entry?.let { if (it.id == objectId) it.copy(done = (it.done + node).distinct()) else it },
                hold = j.hold?.let { if (it.id == objectId) it.copy(done = (it.done + node).distinct()) else it },
            )
        }
    }

    private suspend fun markTries(job: PendingJob, objectId: String, tries: Int) = save { list ->
        list.map { j ->
            if (j.id != job.id) j else j.copy(
                contents = j.contents.map { if (it.id == objectId) it.copy(tries = tries) else it },
                entry = j.entry?.let { if (it.id == objectId) it.copy(tries = tries) else it },
                hold = j.hold?.let { if (it.id == objectId) it.copy(tries = tries) else it },
            )
        }
    }

    private suspend fun drop(job: PendingJob) {
        save { list -> list.filterNot { it.id == job.id } }
        (job.contents + listOfNotNull(job.hold, job.entry)).forEach { platform.blobs.delete(blobName(job.id, it.id)) }
    }

    /** Pending writes that are still open. */
    suspend fun pending(): List<PendingJob> = storeLock.withLock { load() }

    private val storeLock = Mutex()

    private suspend fun save(change: (List<PendingJob>) -> List<PendingJob>) = storeLock.withLock {
        platform.store.putString(STORE_KEY, coreJson.encodeToString(serializer, change(load()).takeLast(MAX_JOBS)))
    }

    private fun load(): List<PendingJob> =
        platform.store.getString(STORE_KEY)?.let { runCatching { coreJson.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    companion object {
        const val STORE_KEY = "core.pending"
        private const val MAX_JOBS = 2_000

        /** How often a message refused for a revoked grant is tried with another grant of the same mailbox. */
        private const val MAX_GRANT_RETRIES = 3
        private val serializer = ListSerializer(PendingJob.serializer())

        fun blobName(job: String, id: String) = "core/job/$job/$id"

        /** Repeating does not help for these answers (ANDP §5.3). 401 may be a clock difference, 429/507/5xx pass. */
        fun permanent(status: Int) = status in setOf(400, 403, 404, 405, 409, 410, 413)
    }
}

internal fun Key.unsafe(): ByteArray = bytes

/** Expiry for an object written now with [lifetime], capped by [maxUntil] seconds (relay policy). */
internal fun untilFor(nowMillis: Long, lifetime: Duration, maxUntil: Long): Long {
    val now = nowMillis / 1000
    val life = lifetime.inWholeSeconds.coerceAtMost(if (maxUntil > 0) maxUntil else lifetime.inWholeSeconds)
    return now + life
}
