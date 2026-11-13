package app.anynear.anmp.core

import app.anynear.andp.Choice
import app.anynear.andp.Info
import app.anynear.andp.Key
import app.anynear.andp.NetworkList
import app.anynear.andp.AndpClient
import app.anynear.andp.Placements
import app.anynear.andp.RadioHead
import app.anynear.andp.Relay
import app.anynear.andp.SignedList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.time.Duration

internal val coreJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

/**
 * The network list (ADR 0015, 0035 §6, ANDP §5.8): built-in or saved list, whichever is newer; without one
 * or when no relay answered for [CoreTimings.seedAfter], the starting point (`https://anynear.net`); otherwise
 * every [CoreTimings.listRefresh] from all relays of the list, the newest valid one, and sooner on [refreshSoon]
 * (the app came to the foreground). Only the signature counts, never who delivered the list. Older lists are
 * never taken.
 */
internal class NetworkDirectory(
    private val config: AnmpCoreConfig,
    private val platform: AnmpPlatform,
    private val pool: RelayPool,
) {
    private val lock = Mutex()
    private val _list = MutableStateFlow<NetworkList?>(null)
    val list: StateFlow<NetworkList?> = _list.asStateFlow()
    private var radioHead: RadioHead? = null
    private var lastRadioRefresh = Long.MIN_VALUE / 2
    private val _radio = MutableStateFlow<RadioList?>(null)

    /**
     * The newest radio list (ANDP §5.9, ADR 0054 §1b) with the update rules of Docs ADR 0096; fetched with the network
     * list, kept across restarts, `null` until one arrived.
     */
    val radio: StateFlow<RadioList?> = _radio.asStateFlow()
    private var lastAnswer = 0L
    private var lastRefresh = 0L
    private val soon = Channel<Unit>(Channel.CONFLATED)

    /** Active relays of the current list. */
    val active: List<Relay> get() = _list.value?.active().orEmpty()

    suspend fun loadSaved() {
        platform.store.getString(STORE_KEY)?.let(::parse)?.let { accept(it, save = false, fromStore = true) }
        config.embeddedList.takeIf { it.isNotBlank() }?.let(::parse)?.let { accept(it) }
        platform.store.getString(RADIO_STORE_KEY)?.let(::parse)?.let { loadRadio(it) }
    }

    /** A saved radio list was checked when it arrived (possibly under a key since replaced); only the ID must match. */
    private suspend fun loadRadio(signed: SignedList) = lock.withLock {
        runCatching {
            val head = RadioHead.parse(signed.list).takeIf { it.id == config.networkId } ?: return@runCatching
            val content = RadioList.parse(signed.list)
            radioHead = head
            _radio.value = content
        }
    }

    /** Runs forever: seeds if needed, refreshes, seeds again when relays are silent too long. */
    suspend fun run() {
        lastAnswer = platform.clock.nowMillis()
        if (_list.value == null) seed()
        while (true) {
            refresh()
            if (platform.clock.nowMillis() - lastAnswer > config.timings.seedAfter.inWholeMilliseconds || _list.value == null) seed()
            withTimeoutOrNull(if (_list.value == null) config.timings.backoffMax else config.timings.listRefresh) { soon.receive() }
        }
    }

    /** Ask all relays at once, at most every [CoreTimings.listRefreshSoon]: a new relay shows up without waiting hours. */
    fun refreshSoon() {
        if (platform.clock.nowMillis() - lastRefresh >= config.timings.listRefreshSoon.inWholeMilliseconds) soon.trySend(Unit)
    }

    /** Waits up to [timeout] for a list. */
    suspend fun await(timeout: Duration): NetworkList? = _list.value ?: withTimeoutOrNull(timeout) { _list.first { it != null } }

    suspend fun refresh() {
        lastRefresh = platform.clock.nowMillis()
        val relays = _list.value?.relays.orEmpty()
        val lists = coroutineScope {
            relays.map { r -> async { runCatching { pool.client(r)?.net() }.getOrNull() } }.awaitAll()
        }.filterNotNull()
        if (lists.isNotEmpty()) lastAnswer = platform.clock.nowMillis()
        lists.forEach { accept(it) }
        refreshRadio()
    }

    /**
     * Asks the active relays for their radio list (ANDP §5.9) and takes the newest valid one. A relay without one
     * answers 404; that is no error.
     */
    suspend fun refreshRadio() {
        lastRadioRefresh = platform.clock.nowMillis()
        val relays = active
        val lists = coroutineScope {
            relays.map { r -> async { runCatching { pool.client(r)?.radio() }.getOrNull() } }.awaitAll()
        }.filterNotNull()
        lists.forEach { acceptRadio(it) }
    }

    /** Like [refreshRadio], at most every [CoreTimings.listRefreshSoon] (the app or tab came to the foreground). */
    suspend fun refreshRadioSoon() {
        if (platform.clock.nowMillis() - lastRadioRefresh >= config.timings.listRefreshSoon.inWholeMilliseconds) refreshRadio()
    }

    /** Takes [signed] if signed for the current network list and newer than the current radio list. */
    suspend fun acceptRadio(signed: SignedList): Boolean {
        val accepted = lock.withLock {
            val list = _list.value ?: return@withLock false
            try {
                val head = list.acceptRadio(radioHead, signed)
                val content = RadioList.parse(signed.list)
                radioHead = head
                _radio.value = content
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }
        if (accepted) {
            platform.store.putString(RADIO_STORE_KEY, coreJson.encodeToString(SignedList.serializer(), signed))
            platform.log.log("net: radio list ${radioHead?.issued}")
        }
        return accepted
    }

    suspend fun seed() {
        val signed = try {
            platform.connector.connectUrl(config.seedUrl).use { it.net() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            platform.log.log("net: starting point not reachable")
            return
        }
        if (accept(signed)) lastAnswer = platform.clock.nowMillis()
    }

    /** Takes [signed] if valid for the configured network and newer than the current list. */
    suspend fun accept(signed: SignedList, save: Boolean = true, fromStore: Boolean = false): Boolean {
        val accepted = lock.withLock {
            val current = _list.value
            val next = try {
                if (current == null) NetworkList.verify(signed, config.networkId, config.networkKey) else current.accept(signed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A saved list may already be signed by a newer key announced in an older list.
                if (current == null && fromStore) acceptRotated(signed)
                // An app that missed the hand-over list (`next`) still trusts the key built into it.
                else if (current != null) acceptConfigured(signed, current) else null
            } ?: return@withLock false
            _list.value = next
            true
        }
        if (accepted && save) platform.store.putString(STORE_KEY, coreJson.encodeToString(SignedList.serializer(), signed))
        if (accepted) platform.log.log("net: list ${_list.value?.issued} with ${_list.value?.relays?.size} relays")
        return accepted
    }

    /**
     * The saved list was accepted before, possibly after a key hand-over (`next`), so it may be signed by a
     * newer key than the configured one. It is only read from our own storage; the ID must still match.
     */
    private suspend fun acceptRotated(signed: SignedList): NetworkList? = runCatching {
        signed.open().takeIf { it.id == config.networkId }
    }.getOrNull()

    /**
     * [signed] is signed by the configured network key (the one built into the app) but not by the current list's
     * key or its `next`: after an update that brought a new network key, without having seen the hand-over list.
     * Same rules as a first contact, and it must be newer than [current].
     */
    private suspend fun acceptConfigured(signed: SignedList, current: NetworkList): NetworkList? = runCatching {
        NetworkList.verify(signed, config.networkId, config.networkKey).takeIf { it.issued > current.issued }
    }.getOrNull()

    private fun parse(text: String): SignedList? = runCatching { coreJson.decodeFromString(SignedList.serializer(), text) }.getOrNull()

    /** Placement of [key] (space for entries, object ID for contents) over the active relays (ANDP §6). */
    fun place(key: String): Choice = Placements.place(key, _list.value?.relays.orEmpty())

    /** Holders: healthy chosen relays plus substitutes for those that are down. */
    fun targets(key: String): List<Relay> = place(key).targets { pool.healthy(it) }

    /**
     * Where to write and list: chosen relays (also those that are down, so that they are tried again and come
     * back) plus substitutes for the down ones, as the app does today (`NetPicker.pick`).
     */
    fun holders(key: String): List<Relay> {
        val p = place(key)
        val t = p.targets { pool.healthy(it) }
        return p.chosen + t.filter { r -> p.chosen.none { it.node == r.node } }
    }

    /** All active relays in placement order for [key]: targets first, then the rest. */
    fun readOrder(key: String): List<Relay> {
        val t = targets(key)
        return t + Placements.order(key, active).filter { r -> t.none { it.node == r.node } }
    }

    /** A relay of the list with this node and TLS key (ANDP §9: follow only those). */
    fun known(holder: Relay): Relay? = _list.value?.relay(holder.node)?.takeIf { it.key == holder.key }

    companion object {
        const val STORE_KEY = "core.net.list"
        const val RADIO_STORE_KEY = "core.net.radio"
    }
}

/**
 * One [AndpClient] per relay and the health of every relay: a relay is down after a failed request and up
 * again after the next answer (as today). [reachableSince] feeds the fallback of §6b.
 */
internal class RelayPool(private val platform: AnmpPlatform) {
    private val clients = mutableMapOf<String, AndpClient>()
    private val infos = mutableMapOf<Key, CachedInfo>()
    private val lock = Mutex()
    private val _down = MutableStateFlow<Set<Key>>(emptySet())
    private val _up = MutableStateFlow<Map<Key, Long>>(emptyMap())

    val down: StateFlow<Set<Key>> = _down.asStateFlow()

    /** Relays that answered, with the time since when without interruption. */
    val up: StateFlow<Map<Key, Long>> = _up.asStateFlow()

    private val _minClientLevel = MutableStateFlow(1)

    /** Highest `clients.min` (ANDP §5.2) of the relays whose info is known; 1 without any. */
    val minClientLevel: StateFlow<Int> = _minClientLevel.asStateFlow()

    fun healthy(node: Key): Boolean = node !in _down.value

    fun reachableSince(node: Key): Long? = _up.value[node]

    suspend fun client(relay: Relay): AndpClient? = lock.withLock {
        val id = "${relay.node}|${relay.addr}|${relay.key}|${relay.host}"
        clients[id] ?: platform.connector.connect(relay)?.also { c ->
            c.now = { platform.clock.nowMillis() / 1000 }
            clients[id] = c
        }
    }

    /**
     * /andp/v1/info of a relay, cached for [INFO_MAX_AGE_MILLIS] (a relay may change its policy, ANDP §11); `null` if
     * not reachable and never fetched. An older answer stays in use while the relay does not answer.
     */
    suspend fun info(relay: Relay, maxAgeMillis: Long = INFO_MAX_AGE_MILLIS): Info? {
        val cached = lock.withLock { infos[relay.node] }
        if (cached != null && platform.clock.nowMillis() - cached.at < maxAgeMillis) return cached.info
        val c = client(relay) ?: return cached?.info
        val info = runCatching { c.info() }.getOrNull() ?: return cached?.info
        c.maxObjectBytes = info.policy.maxObjectBytes
        lock.withLock {
            infos[relay.node] = CachedInfo(relay, info, platform.clock.nowMillis())
            _minClientLevel.value = infos.values.maxOf { it.info.minClientLevel }
        }
        return info
    }

    /** Smallest max_until (seconds) of the relays we know, ANDP default without any. */
    suspend fun maxUntil(): Long = lock.withLock { infos.values.map { it.info.policy.maxUntil }.filter { it > 0 }.minOrNull() } ?: app.anynear.andp.Policy.DEFAULT.maxUntil

    /**
     * Smallest max_object_bytes of the relays we know. Below [needed] the infos are fetched again first, so that a
     * relay that raised its limit since takes the object without waiting for the cache to expire.
     */
    suspend fun maxObjectBytes(needed: Long = 0): Long {
        val known = cachedMaxObjectBytes()
        if (known >= needed) return known
        for (relay in lock.withLock { infos.values.map { it.relay } }) info(relay, maxAgeMillis = 0)
        return cachedMaxObjectBytes()
    }

    private suspend fun cachedMaxObjectBytes(): Long =
        lock.withLock { infos.values.map { it.info.policy.maxObjectBytes }.filter { it > 0 }.minOrNull() } ?: app.anynear.andp.Policy.DEFAULT.maxObjectBytes

    /** Throws [TooLargeException] if [size] bytes are more than the relays take. */
    suspend fun checkObjectSize(size: Long) {
        val limit = maxObjectBytes(needed = size)
        if (size > limit) throw TooLargeException(limit)
    }

    private class CachedInfo(val relay: Relay, val info: Info, val at: Long)

    companion object {
        /** A relay's policy is fetched again after an hour. */
        const val INFO_MAX_AGE_MILLIS = 60L * 60 * 1000
    }

    fun ok(node: Key) {
        val now = platform.clock.nowMillis()
        if (node in _down.value) _down.update { it - node }
        if (node !in _up.value) _up.update { it + (node to now) }
    }

    fun failed(node: Key) {
        if (node !in _down.value) _down.update { it + node }
        if (node in _up.value) _up.update { it - node }
    }

    suspend fun close() = lock.withLock {
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
    }
}

/** An attachment or file is larger than the relays take (`max_object_bytes`, no chunks in version v1); [limit] in bytes. */
class TooLargeException(val limit: Long) : IllegalArgumentException("larger than the relays accept ($limit bytes)")
