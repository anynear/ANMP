package app.anynear.anmp.core

import app.anynear.andp.B64
import app.anynear.andp.ID
import app.anynear.andp.Key
import app.anynear.andp.ListOptions
import app.anynear.andp.ObjectState
import app.anynear.andp.Placement
import app.anynear.andp.Policy
import app.anynear.andp.Ref
import app.anynear.andp.Andp
import app.anynear.andp.AndpHttpException
import app.anynear.andp.SigningKey
import app.anynear.anmp.Anmp
import app.anynear.anmp.AnmpAead
import app.anynear.anmp.AnmpBlob
import app.anynear.anmp.AnmpInfo
import app.anynear.anmp.AnmpKeys
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel
import kotlin.js.JsExport

/**
 * One file of the personal store ("Meine Ablage", ADR 0041) or of a circle store ("Kreis-Ablage", ADR 0049) as the
 * relays hold it. [entryId] is the ANDP object ID
 * of its entry; several entries of the same [file] can exist for a short time after a change, the newest
 * [changed] wins.
 */
@JsExport
class StoredFile internal constructor(
    val entryId: String,
    /** Chosen by the app, stays the same over renames. */
    val file: String,
    val name: String,
    /** Attachment type (`jpg`, `pdf`, `file`, …), as in [app.anynear.anmp.AnmpAttachment.type]. */
    val type: String,
    val size: Long,
    /** Unix milliseconds. */
    val added: Long,
    val changed: Long,
    val width: Int,
    val height: Int,
    /** Kind of document and details (ADR 0067), `null` if not recognized yet. */
    val doc: DocInfo?,
    internal val blob: ByteArray,
    internal val key: ByteArray,
    /**
     * Relays that hold the content (the file itself) when it was listed, by their `site` (or the start of the node
     * key if the network list has none), in placement order of the content ID. Only its holders are asked
     * (chosen and substitutes), and only those that answered `here` count; the entry's relays do not.
     */
    val relays: List<String> = emptyList(),
    /** Space it was listed in; for a circle during a key change also the previous one. */
    internal val space: Key? = null,
    /** Size of the sealed content (from the entry's refs), 0 if unknown. */
    internal val contentSize: Long = 0,
    /** Person ID of who put the file in; `null` for files from before (counted for nobody in a circle). */
    val by: String? = null,
    /** What it counts against the quota: entry and content (ANDP §7). */
    internal val weight: Long = 0,
    /** In the bin since (Unix milliseconds, ADR 0086); 0: not in the bin. Still counts against the quota. */
    val trashed: Long = 0,
    /** When a file in the bin is deleted for good (Unix milliseconds); 0 if it is not in the bin. */
    val purgeAt: Long = 0,
    /**
     * Different operators (`op`, ANDP §6) among [relays] when it was listed: how many data centres of different
     * operators really hold the content. The network aims at three (ADR 0028 §2).
     */
    val operators: Int = 0,
    /** Title (ADR 0091), `null` if none is made yet. */
    val title: FileTitle? = null,
) {
    /** What the app shows as the name of the file: the title, otherwise the file name (ADR 0091 §1). */
    val shown: String get() = FileTitles.shown(title, name)

    /** At least [SECURED_OPERATORS] operators hold the content: "Im Netz gesichert" (ADR 0028 §7). */
    val secured: Boolean get() = operators >= SECURED_OPERATORS

    internal fun withRelays(relays: List<String>, operators: Int) =
        StoredFile(entryId, file, name, type, size, added, changed, width, height, doc, blob, key, relays, space, contentSize, by, weight, trashed, purgeAt, operators, title)

    companion object {
        /** Operators that must hold a content before the app calls it secured: at least two, target three (ADR 0028 §2, §7). */
        const val SECURED_OPERATORS = 2
    }
}

/**
 * How full a store is (ADR 0029, 0041): [used] bytes count as the relays count them against `quota_space_bytes`
 * (entries and their refs, ANDP §7), [quota] is the smallest quota of the holders that answered.
 */
@JsExport
class StoreUsage internal constructor(val used: Long, val quota: Long) {
    /** Less than [FULL_MARGIN] left: nothing new fits for sure. */
    val full: Boolean get() = quota > 0 && quota - used < FULL_MARGIN

    companion object {
        const val FULL_MARGIN: Long = 1L shl 20

        /**
         * Quota of a person over all stores without a subscription (ADR 0076, 0079): 250 MiB. The relays only know a
         * limit per space (`quota_space_bytes`, higher, also for circle stores); until the entitlement service tells
         * the plan, this is the person's limit.
         */
        const val FREE_QUOTA: Long = 250L shl 20
    }
}

/** Thrown by putting a new file when the person's storage, over all stores together, has no room for it. */
class StorageFullException(val used: Long, val quota: Long) : Exception("storage full: $used of $quota")

/** Entry of a store, CBOR with integer keys, sealed with the store key (spec/anmp.md, "Personal store", "Circle store"). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class StoreEntry(
    @CborLabel(0) val file: String,
    @CborLabel(1) val name: String,
    @CborLabel(2) val type: String,
    @CborLabel(3) val size: Long,
    @CborLabel(4) val blob: ByteArray,
    @CborLabel(5) val key: ByteArray,
    @CborLabel(6) val added: Long,
    @CborLabel(7) val changed: Long,
    @CborLabel(8) val width: Int = 0,
    @CborLabel(9) val height: Int = 0,
    /** ADR 0067: kind of document and details, optional; older readers skip it. */
    @CborLabel(10) val doc: StoreDoc? = null,
    /** Person ID of who put the file in (shared quota); stays the same over changes. Absent in older entries. */
    @CborLabel(11) val by: String? = null,
    /** ADR 0086: in the bin since (Unix ms), absent if not. Older readers skip it and show the file as usual. */
    @CborLabel(12) val trashed: Long = 0,
    /** ADR 0091: title of the file, optional; older readers skip it. */
    @CborLabel(13) val title: StoreTitle? = null,
)

/**
 * Keys of a store. Personal store: a random secret [SECRET] (secret store, travels with backup and move like the other
 * `core.*` keys) gives the owner key of an own space and the AES-256-GCM key of its entries. The space is separate
 * from the personal mailbox, so nobody can tell from a contact card that it exists. Circle store ([ofCircle]): the
 * same from the circle secret, so every member holds it and a key change gives a new space.
 */
internal class StoreKeys private constructor(val owner: SigningKey, val aead: AnmpAead, private val aad: String) {
    val space: Key get() = owner.key

    suspend fun seal(entry: StoreEntry): ByteArray =
        aead.seal(coreCbor.encodeToByteArray(StoreEntry.serializer(), entry), aad.encodeToByteArray())

    suspend fun open(bytes: ByteArray): StoreEntry? {
        val plain = aead.open(bytes, aad.encodeToByteArray()) ?: return null
        return runCatching { coreCbor.decodeFromByteArray(StoreEntry.serializer(), plain) }.getOrNull()
            ?.takeIf { it.blob.size == Anmp.BLOB_ID_BYTES && it.key.size == 32 && it.file.isNotEmpty() }
    }

    companion object {
        const val SECRET = "core.store.secret"

        suspend fun loadOrCreate(secrets: SecretStore): StoreKeys {
            val secret = secrets.get(SECRET)?.takeIf { it.size == 32 } ?: AnmpKeys.randomKey().also { secrets.put(SECRET, it) }
            return of(secret)
        }

        suspend fun of(secret: ByteArray): StoreKeys =
            StoreKeys(SigningKey.fromSeed(AnmpKeys.hkdf(secret, AnmpInfo.STORE_SIGN)), AnmpAead.of(AnmpKeys.hkdf(secret, AnmpInfo.STORE_AEAD)), AnmpInfo.STORE_AEAD)

        /** Circle store of the circle secret [secret] (b64u, as in [CircleState.secret]). */
        suspend fun ofCircle(secret: String): StoreKeys {
            val raw = B64.decode(secret)
            return StoreKeys(
                SigningKey.fromSeed(AnmpKeys.hkdf(raw, AnmpInfo.CIRCLE_STORE_SIGN)),
                AnmpAead.of(AnmpKeys.hkdf(raw, AnmpInfo.CIRCLE_STORE_AEAD)),
                AnmpInfo.CIRCLE_STORE_AEAD,
            )
        }
    }
}

/**
 * A store on ANDP (personal or of one circle key): contents (sealed files) and entries (sealed [StoreEntry] with `refs`
 * to their content), both without expiry (`until` 0, ANDP §3 rule 4), counted against the space quota of the relays.
 * Nothing is listed in the background; the app asks with [list] when it wants to compare.
 */
internal class FileStore(
    private val keys: StoreKeys,
    private val net: NetworkDirectory,
    private val pool: RelayPool,
    private val transport: Transport,
    private val access: SpaceAccess,
    /** Start of the refs of [put]; the file ID follows. */
    private val refPrefix: String = REF_PREFIX,
    /** Own person ID, written as `by` into new files. */
    private val me: () -> String = { "" },
    /** Personal store: every file counts as own, whoever is in `by`. */
    private val allMine: Boolean = false,
    /** Unix milliseconds, for the bin. */
    private val now: () -> Long = { 0 },
    /** How long a file stays in the bin ([CoreTimings.trashKeep]). */
    private val trashMillis: Long = 7 * 86_400_000L,
    private val log: (String) -> Unit,
) {
    val space: Key get() = keys.space

    fun accessOf(): SpaceAccess = access

    /** Space used and quota as of the last [list] that reached a relay; `null` before. */
    var usage: StoreUsage? = null
        private set

    /** What of this store counts against the own quota (shared over all stores), as of the last [list]; `null` before. */
    var ownUsed: Long? = null
        private set

    /** `by` of each file as of the last [list], kept over changes. */
    private val byOf = mutableMapOf<String, String?>()

    /** The file is already here (a change, not a new file). */
    fun knows(file: String): Boolean = file in byOf

    /**
     * Seals [plaintext] with a new key and queues content and entry. Returns the ref of [CoreEvent.Stored] and
     * [CoreEvent.SendFailed] (ref prefix + [file]); a full quota shows as SendFailed. [checkSize] throws
     * [TooLargeException] for a file larger than the relays take.
     */
    suspend fun put(
        file: String,
        plaintext: ByteArray,
        name: String,
        type: String,
        added: Long,
        changed: Long,
        width: Int,
        height: Int,
        checkSize: suspend (Long) -> Unit,
        doc: DocInfo? = null,
        /** Who put it in; default: who had it so far, else us. */
        by: String? = null,
        /** In the bin since (circle key change copies it as it is). */
        trashed: Long = 0,
        /** Title (ADR 0091), if already made. */
        title: FileTitle? = null,
    ): String {
        val key = AnmpBlob.newKey()
        val sealed = AnmpBlob.seal(key, plaintext)
        checkSize(sealed.bytes.size.toLong())
        val who = by ?: byOf[file] ?: me().ifEmpty { null }
        val entry = StoreEntry(file, name, type, plaintext.size.toLong(), sealed.id, key, added, changed, width, height, doc?.toWire(), who, trashed, title?.toWire())
        val ref = refPrefix + file
        transport.submit(space, keys.seal(entry), until = 0, contents = listOf(ID(sealed.id) to sealed.bytes), ref = ref, known = access)
        return ref
    }

    /**
     * Changes name, document details and title of [f] without uploading the file again (ADR 0067): a new entry for the same
     * file that refers to the content [f] already has. `null` if the size of that content is unknown or [f] lies in
     * another space (circle key change); then the caller puts the file again.
     */
    suspend fun update(f: StoredFile, name: String, doc: DocInfo?, changed: Long, trashed: Long = f.trashed, title: FileTitle? = f.title): String? {
        if (f.contentSize <= 0 || (f.space != null && f.space != space)) return null
        val entry = StoreEntry(f.file, name, f.type, f.size, f.blob, f.key, f.added, changed, f.width, f.height, doc?.toWire(), f.by ?: me().ifEmpty { null }, trashed, title?.toWire())
        val ref = refPrefix + f.file
        transport.submit(space, keys.seal(entry), until = 0, ref = ref, known = access, refs = listOf(Ref(ID(f.blob), f.contentSize)))
        return ref
    }

    /**
     * Every file on the relays, newest entry per file. Older entries of the same file are deleted on the way.
     * `null` if no holder of the space answered.
     */
    suspend fun list(): List<StoredFile>? {
        val entries = mutableMapOf<String, StoreEntry>()
        val sizes = mutableMapOf<String, Long>()
        // What each entry counts against the quota (ANDP §7): its size and that of its refs, also for entries we cannot open.
        val weights = mutableMapOf<String, Long>()
        val quotas = mutableListOf<Long>()
        var answered = false
        for (relay in net.readOrder(space.toString()).take(MAX_RELAYS)) {
            val client = pool.client(relay) ?: continue
            try {
                var after = ""
                while (true) {
                    val page = client.list(access.cred, space, ListOptions(after, 0, PAGE))
                    for (o in page.objects) {
                        val id = o.id.toString()
                        if (o.placement == Placement.SPACE) weights[id] = o.size + o.refs.sumOf { it.size }
                        if (o.revokes != null) continue
                        if (id !in entries) {
                            val bytes = o.inline() ?: client.get(access.cred, space, o.id).data
                            val e = keys.open(bytes) ?: continue
                            entries[id] = e
                            o.refs.firstOrNull { it.id.bytes.contentEquals(e.blob) }?.let { sizes[id] = it.size }
                        }
                    }
                    if (page.next.isEmpty() || page.next == after) break
                    after = page.next
                }
                answered = true
                pool.ok(relay.node)
                pool.info(relay)?.policy?.quotaSpaceBytes?.takeIf { it > 0 }?.let { quotas += it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AndpHttpException) {
                // A space without any entry is unknown to the relay: that is an answer, just an empty one.
                pool.ok(relay.node)
                if (e.isNotFound) answered = true else log("store list at ${relay.site}: ${e.status}")
            } catch (e: Exception) {
                log("store list at ${relay.site}: ${e.message}")
            }
        }
        if (!answered) return null
        val found = entries.mapValues { (id, e) ->
            StoredFile(
                id, e.file, e.name, e.type, e.size, e.added, e.changed, e.width, e.height, e.doc?.toInfo(), e.blob, e.key,
                space = space, contentSize = sizes[id] ?: 0, by = e.by, weight = weights[id] ?: 0, trashed = e.trashed,
                purgeAt = if (e.trashed > 0) e.trashed + trashMillis else 0, title = e.title?.toInfo(),
            )
        }
        val all = found.values.groupBy { it.file }.mapValues { (_, all) -> all.maxWith(compareBy({ it.changed }, { it.entryId })) }
        val old = found.values.filter { all[it.file] !== it }
        old.forEach { deleteEntry(it, all[it.file]?.blob) }
        // Bin (ADR 0086): whoever lists first after the time is up deletes the file for good.
        val t = now()
        val expired = all.values.filter { it.purgeAt in 1..<t }
        expired.forEach { deleteEntry(it, null) }
        val newest = all - expired.map { it.file }.toSet()
        // The relays count every entry with its refs, also when two entries refer to the same content.
        val freed = (old + expired).sumOf { weights[it.entryId] ?: 0 }
        usage = StoreUsage(maxOf(0, weights.values.sum() - freed), quotas.minOrNull() ?: Policy.DEFAULT.quotaSpaceBytes)
        // Shared quota: in a circle only what we put in counts for us; in the personal store everything.
        val self = me()
        ownUsed = newest.values.filter { allMine || (it.by != null && it.by == self) }.sumOf { it.weight }
        byOf.clear()
        newest.values.forEach { byOf[it.file] = it.by }
        val relays = contentRelays(newest.values)
        return newest.values.sortedByDescending { it.added }.map { f ->
            val r = relays[ID(f.blob)]
            f.withRelays(r?.first.orEmpty(), r?.second ?: 0)
        }
    }

    /**
     * Where the contents of [files] really are: each content is placed by its own ID (ANDP §6), so its holders
     * (chosen and substitutes) are asked with `status`, and only those that answer `here` count, in placement order.
     * Per content: the relays by name and how many different operators they belong to.
     */
    private suspend fun contentRelays(files: Collection<StoredFile>): Map<ID, Pair<List<String>, Int>> {
        val holders = files.associate { ID(it.blob) to net.holders(ID(it.blob).toString()) }
        val here = mutableMapOf<ID, MutableSet<Key>>()
        for (relay in holders.values.flatten().distinctBy { it.node }) {
            val ids = holders.filterValues { h -> h.any { it.node == relay.node } }.keys.toList()
            val client = pool.client(relay) ?: continue
            try {
                for (chunk in ids.chunked(Andp.MAX_STATUS_IDS)) {
                    client.status(access.cred, space, chunk).forEach { (id, state) ->
                        if (state == ObjectState.HERE) here.getOrPut(id) { mutableSetOf() } += relay.node
                    }
                }
                pool.ok(relay.node)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AndpHttpException) {
                pool.ok(relay.node)
                log("store status at ${relay.site}: ${e.status}")
            } catch (e: Exception) {
                log("store status at ${relay.site}: ${e.message}")
            }
        }
        return holders.mapValues { (id, h) ->
            val have = h.filter { it.node in here[id].orEmpty() }
            have.map { r -> r.site.ifEmpty { r.node.toString().take(8) } } to have.map { it.op }.distinct().size
        }
    }

    /** Loads and decrypts the content of [f]. */
    suspend fun fetch(f: StoredFile): BlobResult = when (val r = transport.readContent(space, ID(f.blob))) {
        ReadResult.Gone -> BlobResult.Gone
        ReadResult.Later -> BlobResult.Later
        is ReadResult.Found -> AnmpBlob.open(f.key, f.blob, r.fetched.data)?.let { BlobResult.Found(it) } ?: BlobResult.Later
    }

    /**
     * Moves [file] into the bin ([trashed] = now) or back out of it ([trashed] = 0) with a new entry for the same
     * content (ADR 0086). If that cannot be done (content size unknown, previous space of a key change), moving into
     * the bin deletes the file at once and restoring does nothing. `false` if no holder answered.
     */
    suspend fun setTrashed(file: String, trashed: Long): Boolean {
        val f = (list() ?: return false).firstOrNull { it.file == file } ?: return true
        if (update(f, f.name, f.doc, now(), trashed) == null && trashed > 0) deleteEntry(f, null)
        return true
    }

    /** Deletes [file] (every entry and its content). `false` if no holder answered; then try again later. */
    suspend fun delete(file: String): Boolean {
        val all = list() ?: return false
        all.filter { it.file == file }.forEach { deleteEntry(it, null) }
        return true
    }

    /** Entry first, so the file disappears from listings at once; the content follows ([keep]: still referenced). */
    suspend fun deleteEntry(f: StoredFile, keep: ByteArray?) {
        transport.delete(access.cred, space, ID.parse(f.entryId), entry = true)
        if (keep == null || !keep.contentEquals(f.blob)) transport.delete(access.cred, space, ID(f.blob), entry = false)
    }

    companion object {
        const val REF_PREFIX = "store:"
        private const val PAGE = 200

        /** Holders asked when listing: the chosen ones of the space and their substitutes. */
        private const val MAX_RELAYS = 4
    }
}
