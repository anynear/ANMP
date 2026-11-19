package app.anynear.anmp.core

import app.anynear.andp.B64
import app.anynear.andp.Placements
import app.anynear.andp.StreamNew
import app.anynear.andp.bytesToHex
import app.anynear.andp.watchId
import app.anynear.andp.Credentials
import app.anynear.andp.Grant
import app.anynear.andp.GrantBody
import app.anynear.andp.Hash
import app.anynear.andp.ID
import app.anynear.andp.Key
import app.anynear.andp.ListOptions
import app.anynear.andp.ListedObject
import app.anynear.andp.Meta
import app.anynear.andp.Andp
import app.anynear.andp.AndpHttpException
import app.anynear.andp.Placement
import app.anynear.andp.Relay
import app.anynear.andp.Rights
import app.anynear.andp.SigningKey
import app.anynear.andp.WatchRequest
import app.anynear.andp.WatchTarget
import app.anynear.andp.watchIdForHexToken
import app.anynear.anmp.Anmp
import app.anynear.anmp.AnmpIntro
import app.anynear.anmp.AnmpIntroCard
import app.anynear.anmp.AnmpIntroReply
import app.anynear.anmp.AnmpIntros
import app.anynear.anmp.AnmpAead
import app.anynear.anmp.AnmpMailbox
import app.anynear.anmp.AnmpAlertBody
import app.anynear.anmp.AnmpAlerts
import app.anynear.anmp.AnmpChannelInvite
import app.anynear.anmp.AnmpChannelJoin
import app.anynear.anmp.AnmpChannelRef
import app.anynear.anmp.AnmpIngressRecord
import app.anynear.anmp.AnmpAlive
import app.anynear.anmp.AnmpAliveState
import app.anynear.anmp.AnmpCare
import app.anynear.anmp.AnmpCareState
import app.anynear.anmp.AnmpLink
import app.anynear.anmp.AnmpAttachment
import app.anynear.anmp.AnmpBlob
import app.anynear.anmp.AnmpArrived
import app.anynear.anmp.AnmpContent
import app.anynear.anmp.AnmpEnvelope
import app.anynear.anmp.AnmpHistory
import app.anynear.anmp.AnmpHistoryPart
import app.anynear.anmp.AnmpImage
import app.anynear.anmp.AnmpInfo
import app.anynear.anmp.AnmpSpaceKey
import app.anynear.anmp.AnmpStats
import app.anynear.anmp.AnmpKeys
import app.anynear.anmp.AnmpKind
import app.anynear.anmp.AnmpDeviceEntry
import app.anynear.anmp.AnmpDeviceList
import app.anynear.anmp.AnmpMeta
import app.anynear.anmp.AnmpOwnData
import app.anynear.anmp.AnmpPerson
import app.anynear.anmp.AnmpPersonCert
import app.anynear.anmp.AnmpPersons
import app.anynear.anmp.AnmpRekey
import app.anynear.anmp.AnmpRemoved
import app.anynear.anmp.AnmpSync
import app.anynear.anmp.AnmpSyncChannel
import app.anynear.anmp.AnmpSyncCircle
import app.anynear.anmp.AnmpSyncContact
import app.anynear.anmp.AnmpSyncGone
import app.anynear.anmp.AnmpSyncIngress
import app.anynear.anmp.AnmpSyncSubscriber
import app.anynear.anmp.AnmpSyncKeys
import app.anynear.anmp.AnmpSyncAsked
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel
import kotlin.js.JsExport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.days

/** What the core asks the app for (the app keeps messages and files). */
interface AnmpCoreHost {
    /** Opened, signed circle packets ([CoreEvent.Message.opened]) of a circle, oldest first, for a new member (ADR 0014). */
    suspend fun circleHistory(circleId: String): List<ByteArray> = emptyList()

    /**
     * Plaintext of a picture or attachment with plaintext hash [hash] (16 bytes, b64u) that may be shared with
     * [fromDevice] in [conversation] (ADR 0014, `want`/`have`); `null` if not on this device.
     */
    suspend fun sharedContent(conversation: Conversation, hash: String, fromDevice: String): ByteArray? = null
}

/** Public identity of this installation and its person (ADR 0042). All values b64u. */
@JsExport
class IdentityInfo(val deviceId: String, val tag: String, val publicKey: String, val signKey: String, val inbox: String, val personId: String = deviceId)

/** An attachment ready to send: ANMP attachment (blob ID, key, metadata); its ciphertext waits in the [BlobStore]. */
@JsExport
class PreparedAttachment internal constructor(val attachment: AnmpAttachment, internal val sealedName: String)

/**
 * Preview of a web link for [AnmpCore.sendCircle] and [AnmpCore.sendDirect] (ADR 0069). The app fetched it; [image] is a
 * small photo from [AnmpCore.prepareAttachment] (type `jpg`), uploaded with the message.
 */
@JsExport
class PreparedLink(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val site: String? = null,
    val image: PreparedAttachment? = null,
)

/**
 * Core of ANMP on ANDP (ADR 0034–0036): circles, contacts, personal mailbox, sending and fetching with
 * receipts, reactions, read receipts, want/have, history, key changes, removing, leaving and forwarding
 * (ADR 0022), without UI and without platform code. Entry point for the app and (through `AnmpCoreWeb`) the web
 * client.
 *
 * Model (spec/anmp.md "Core on ANDP"): every circle is an ANDP space owned by everyone holding the circle secret;
 * every installation owns a personal mailbox with a random owner key and hands out write grants (per contact,
 * per circle in the member announcement, `*` with a short expiry in the contact link). Packets lie as entries
 * (placement space, inside `AnmpEnvelope`), blobs as contents (placement object) referenced by `refs`.
 *
 * Call [start] once. Everything else may be called afterwards from any coroutine.
 */
class AnmpCore(
    scope: CoroutineScope,
    private val config: AnmpCoreConfig,
    private val platform: AnmpPlatform,
    private val host: AnmpCoreHost = object : AnmpCoreHost {},
) {
    private val timings = config.timings
    private val job = SupervisorJob(scope.coroutineContext[Job])
    /** All work of the core runs on one serial dispatcher: no data races between loops and API calls. */
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val cs = CoroutineScope(scope.coroutineContext + job + serial)
    private val pool = RelayPool(platform)
    private val net = NetworkDirectory(config, platform, pool)
    private val duty = Duty(timings)
    private val log = platform.log

    private val _events = MutableSharedFlow<CoreEvent>(extraBufferCapacity = 1024)

    /** Everything that happens, see [CoreEvent]. */
    val events: SharedFlow<CoreEvent> = _events.asSharedFlow()

    private val stateLock = Mutex()
    private val _state = MutableStateFlow(CoreState())

    /** Circles on this device, also removed ones until their grace period ends. */
    val circles: StateFlow<List<CircleState>> = _state.map { it.circles }.stateIn(cs, SharingStarted.Eagerly, emptyList())

    /** Members of all circles and contacts ([MemberState.CONTACTS]). */
    val members: StateFlow<List<MemberState>> = _state.map { it.members }.stateIn(cs, SharingStarted.Eagerly, emptyList())

    /** Own and subscribed channels (ADR 0062). */
    val channels: StateFlow<List<ChannelState>> = _state.map { it.channels }.stateIn(cs, SharingStarted.Eagerly, emptyList())

    /** Persons whose mailbox refuses us, with since when (ms; [CoreEvent.ContactUnreachable]). */
    val unreachable: StateFlow<Map<String, Long>> = _state.map { it.unreachable }.stateIn(cs, SharingStarted.Eagerly, emptyMap())

    /** Accepted alerts as kept (30 days); [alertTick] recomputes the status when one expires. */
    private val storedAlerts = MutableStateFlow<List<StoredAlert>>(emptyList())
    private val alertTick = MutableStateFlow(0L)

    /** Alerts of the last 30 days in all channels, newest first, checked and without duplicates (ADR 0062 §6). */
    val alerts: StateFlow<List<AlertItem>> = combine(storedAlerts, alertTick) { list, _ -> ChannelRules.items(list, now()) }
        .stateIn(cs, SharingStarted.Eagerly, emptyList())

    /**
     * `true` when the relays serve fully only newer clients than this core ([AnmpCoreConfig.CLIENT_LEVEL] below
     * `clients.min`, ANDP §5.2). The core keeps working as far as it can; the app asks the person to update.
     */
    val updateNeeded: StateFlow<Boolean> = pool.minClientLevel.map { it > AnmpCoreConfig.CLIENT_LEVEL }
        .stateIn(cs, SharingStarted.Eagerly, false)

    /**
     * The newest radio list (ANDP §5.9): regions and communities (ADR 0054 §1b) and the update rules `app` and `web`
     * (ADR 0096). Fetched with the network list, kept across restarts and without internet.
     */
    val radioList: StateFlow<RadioList?> get() = net.radio

    /**
     * Asks the relays for a newer radio list, e.g. when the app comes to the foreground; at most every
     * [CoreTimings.listRefreshSoon] unless [force].
     */
    suspend fun refreshRadioList(force: Boolean = false) = if (force) net.refreshRadio() else net.refreshRadioSoon()

    /** Relays of the network list and which answered last time. */
    val relays: StateFlow<RelayStatus> = combine(net.list, pool.up) { l, up ->
        val active = l?.active().orEmpty()
        RelayStatus(active.map { it.node.toString() }, up.keys.map { it.toString() }, active.map { it.site })
    }.stateIn(cs, SharingStarted.Eagerly, RelayStatus())

    private val _identity = MutableStateFlow<IdentityInfo?>(null)
    val identity: StateFlow<IdentityInfo?> = _identity.asStateFlow()

    private lateinit var me: CoreIdentity
    private val opened = CompletableDeferred<Unit>()
    private val openLock = Mutex()
    private var started = false
    private val pushTarget = MutableStateFlow<WatchTarget?>(null)

    private lateinit var store: FileStore

    /** Circle stores by circle secret, made on first use. */
    private val circleStores = mutableMapOf<String, FileStore>()

    /** Entries of a previous circle space already copied into the new one since the start. */
    private val circleCopies = mutableSetOf<String>()

    private val transport = Transport(cs, platform, net, pool, timings, ::accessFor, ::ownWatchId, { emit(it) }) { space, grant ->
        onCore { onMailboxDenied(space, grant) }
    }
    private val radioSoftware = RadioInbox.parse(config.radioSoftwareSpace, config.radioSoftwareGrant)?.let { (space, grant) ->
        PublishedSpace(space, grant, net, pool)
    }
    private val radioInbox = RadioInbox.parse(config.radioInboxSpace, config.radioInboxGrant)?.let { (space, grant) ->
        RadioInbox(space, grant, platform, net, pool)
    }

    // --- Lifecycle -------------------------------------------------------------------------------

    /** Loads identity, state and the saved network list without starting any network loop (iOS extension). */
    suspend fun open(): IdentityInfo {
        openLock.withLock {
            if (!opened.isCompleted) {
                me = CoreIdentity.loadOrCreate(platform.secrets)
                loadStreamKeys(create = false)
                store = ownStore(StoreKeys.loadOrCreate(platform.secrets))
                _state.value = loadState()
                // Only an installation that ran before ADR 0107 has entries sealed for its device; a new one, added or
                // back from the emergency card has a new device ID and nothing to move (§6).
                if (state.ownProof == null && !state.mailboxMigrated && !config.legacyDirect) update { s -> s.copy(mailboxMigrated = true, historyKept = true) }
                // An installation that already ran has read what it has; only new devices show the loading notice.
                if (state.ownProof != null && !state.mailboxRead) {
                    update { s -> s.copy(mailboxRead = true, circlesRead = (s.circlesRead + s.circles.map { it.id }).distinct()) }
                }
                warmProofs()
                ensureOwnChain()
                markMoved()
                platform.secrets.get(RECOVER_SYNC)?.let { raw ->
                    runCatching { coreCbor.decodeFromByteArray(AnmpSync.serializer(), raw) }.getOrNull()?.let { applySync(it) }
                    platform.secrets.put(RECOVER_SYNC, null)
                }
                loadSeen()
                loadAlerts()
                net.loadSaved()
                if (state.previousStore != null) cs.launch { runCatching { moveOldStore() } }
                if (!state.mailboxMigrated && !config.legacyDirect) cs.launch { keepMigrating() }
                _identity.value = IdentityInfo(me.deviceId, B64.encode(me.tag), me.publicKey, me.signKey, me.inbox.toString(), ownPersonId)
                opened.complete(Unit)
            }
        }
        return _identity.value!!
    }

    /** Opens and starts fetching, sending, repair fallbacks, announcements and watch. */
    suspend fun start(): IdentityInfo {
        val info = open()
        openLock.withLock {
            if (started) return info
            started = true
        }
        cs.launch { net.run() }
        cs.launch { checkClientLevel() }
        cs.launch { transport.resumeAll() }
        cs.launch { kickWritesOnChange() }
        cs.launch { pollAll() }
        cs.launch { reseedLoop() }
        cs.launch { maintenance() }
        cs.launch { watchLoop() }
        cs.launch { streamLoop() }
        cs.launch { announceOnChange() }
        cs.launch { syncOnChange() }
        if (tellContactsOnStart) cs.launch {
            delay(timings.reannounceDelay)
            state.members.filter { it.circleId == MemberState.CONTACTS }.map { it.deviceId }.distinct().forEach { runCatching { sendProfileTo(it) } }
            update { s -> s.copy(profile = s.profile.copy(imageSentTs = s.profile.imageTs)) }
        } else if (state.profile.imageTs > state.profile.imageSentTs) cs.launch {
            // The picture changed without reaching the contacts (e.g. it came from another own device before they were told).
            delay(timings.reannounceDelay)
            onCore { runCatching { sendProfileToContacts() } }
        }
        cs.launch { flushSeenLoop() }
        cs.launch { alertClock() }
        cs.launch {
            delay(timings.reannounceDelay)
            onCore { runCatching { channelsOnStart() }.onFailure { e -> if (e is CancellationException) throw e else log.log("channels: ${e.message}") } }
        }
        cs.launch { storedAlerts.drop(1).debounce(ALERT_SAVE_DELAY).collect { if (!revokedHere) runCatching { saveAlerts(it) } } }
        return info
    }

    /** Stops all loops. Pending writes continue after the next [start]. */
    suspend fun stop() {
        if (opened.isCompleted && !revokedHere) runCatching { saveAlerts(storedAlerts.value) }
        flushSeen()
        job.cancel()
        pool.close()
    }

    /**
     * Runs [block] on the core's serial context, only after [open]: a change before the saved state is loaded would write
     * the empty start state over it (contacts, circles and persons gone; hardware test 02.11.2026, `setSettings` at start).
     */
    private suspend fun <T> onCore(block: suspend () -> T): T {
        opened.await()
        return withContext(serial) { block() }
    }

    private fun now() = platform.clock.nowMillis()

    private fun nowS() = now() / 1000

    private suspend fun emit(e: CoreEvent) = _events.emit(e)

    /** Hosts whose links the core reads: the link host first, then the others (ADR 0061 decision 4). */
    private val linkHosts: List<String> by lazy { (listOf(config.linkHost) + config.otherLinkHosts).distinct() }

    /** Asks two active relays for their info when the list changes and every hour, for `clients.min` (ANDP §5.2). */
    private suspend fun checkClientLevel() {
        while (true) {
            refreshClientLevel(maxAgeMillis = RelayPool.INFO_MAX_AGE_MILLIS)
            withTimeoutOrNull(1.hours) { net.list.drop(1).first() }
        }
    }

    /** Fetches the info of two active relays, cached ones only if younger than [maxAgeMillis]. */
    internal suspend fun refreshClientLevel(maxAgeMillis: Long = 0) {
        for (r in net.list.value?.active().orEmpty().shuffled().take(2)) pool.info(r, maxAgeMillis)
    }

    // --- State ----------------------------------------------------------------------------------

    private fun loadState(): CoreState =
        platform.secrets.get(STATE_KEY)?.let { runCatching { coreJson.decodeFromString(CoreState.serializer(), it.decodeToString()) }.getOrNull() } ?: CoreState()

    private suspend fun update(change: (CoreState) -> CoreState): CoreState = stateLock.withLock {
        val prev = _state.value
        val next = change(prev)
        if (next != prev) {
            _state.value = next
            if (next.circles != prev.circles || next.mailboxRead != prev.mailboxRead || next.circlesRead != prev.circlesRead) refreshLoading()
            platform.secrets.put(STATE_KEY, coreJson.encodeToString(CoreState.serializer(), next).encodeToByteArray())
        }
        next
    }

    private val state: CoreState get() = _state.value

    fun circle(id: String): CircleState? = state.circles.firstOrNull { it.id == id }

    fun membersOf(circleId: String): List<MemberState> = state.members.filter { it.circleId == circleId }

    private fun member(circleId: String, deviceId: String) = state.members.firstOrNull { it.circleId == circleId && it.deviceId == deviceId }

    private fun anyMember(deviceId: String) = state.members.lastOrNull { it.deviceId == deviceId }

    /** The first seen X25519 key of a device; a different one is refused. */
    private fun pinnedKey(deviceId: String): String? = state.members.firstOrNull { it.deviceId == deviceId }?.publicKey

    private fun peerByTag(tag: ByteArray): Peer? =
        state.members.firstOrNull { m -> runCatching { Anmp.tagOf(B64.decode(m.deviceId)).contentEquals(tag) }.getOrDefault(false) }
            ?.let { Peer(it.deviceId, it.publicKey) }

    /**
     * Name and node as announced; the picture only if newer. Returns `false` if another key is pinned for the device.
     */
    private suspend fun upsertMember(m: MemberState): Pair<Boolean, Boolean> {
        var isNew = false
        var ok = true
        update { s ->
            val pinned = s.members.firstOrNull { it.deviceId == m.deviceId }?.publicKey
            if (pinned != null && pinned != m.publicKey) {
                ok = false
                return@update s
            }
            val existing = s.members.firstOrNull { it.circleId == m.circleId && it.deviceId == m.deviceId }
            isNew = existing == null
            val signKey = m.signKey ?: s.members.firstNotNullOfOrNull { it.signKey?.takeIf { _ -> it.deviceId == m.deviceId } }
            // Node 0 means "not sent" (profile, contact card, device list), never "no radio any more": keep the known one.
            val node = m.node.takeIf { it != 0L } ?: existing?.node?.takeIf { it != 0L }
                ?: s.members.firstNotNullOfOrNull { it.node.takeIf { n -> n != 0L && it.deviceId == m.deviceId } } ?: 0L
            var merged = m.copy(
                signKey = signKey, node = node, inbox = m.inbox ?: existing?.inbox ?: s.members.firstNotNullOfOrNull { it.inbox?.takeIf { _ -> it.deviceId == m.deviceId } },
                mailboxKey = m.mailboxKey ?: existing?.mailboxKey ?: s.members.firstNotNullOfOrNull { it.mailboxKey?.takeIf { _ -> it.deviceId == m.deviceId } },
            )
            if (existing != null && existing.imageTs > merged.imageTs) merged = merged.copy(image = existing.image, imageTs = existing.imageTs)
            merged = merged.copy(addedAt = addedAtOf(s, m, existing))
            if (existing == merged) return@update s
            s.copy(members = s.members.filterNot { it.circleId == m.circleId && it.deviceId == m.deviceId } + merged)
        }
        if (ok) member(m.circleId, m.deviceId)?.let { emit(CoreEvent.MemberUpdated(it, isNew)) }
        // A request of someone who became a contact (on another own device, or by a circle) is answered (ADR 0116).
        if (ok && m.circleId == MemberState.CONTACTS && state.requests.any { it.deviceId == m.deviceId || it.person == m.person }) {
            val answered = state.requests.filter { it.deviceId == m.deviceId || it.person == m.person }
            update { s -> s.copy(requests = s.requests - answered.toSet()) }
            // Its grant may have come only with the request (e.g. its answer reached this device before the snapshot
            // that made it a contact here): keep it, so this device can write to it too.
            answered.forEach { r ->
                val grant = r.grant?.let { runCatching { B64.decode(it) }.getOrNull() } ?: return@forEach
                val inbox = r.inbox?.let { runCatching { B64.decode(it) }.getOrNull() } ?: return@forEach
                acceptGrant(r.deviceId, inbox, grant, null)
            }
            emit(CoreEvent.ContactRequestsChanged())
        }
        return ok to isNew
    }

    /**
     * When the contact [m] was added (ADR 0008, sorting of „Nachrichten“): a known entry keeps its time, a further
     * device of a contact takes the time of the person (0 stays unknown), a new contact the time from a snapshot of an
     * own device (none: unknown, [ADDED_UNKNOWN]) or now. Entries of circles carry none.
     */
    private fun addedAtOf(s: CoreState, m: MemberState, existing: MemberState?): Long {
        if (m.circleId != MemberState.CONTACTS) return 0
        if (existing != null) return existing.addedAt.takeIf { it > 0 } ?: m.addedAt
        val siblings = s.members.filter { it.circleId == MemberState.CONTACTS && it.person == m.person }
        if (siblings.isNotEmpty()) return siblings.map { it.addedAt }.filter { it > 0 }.minOrNull() ?: 0
        return if (m.addedAt == ADDED_UNKNOWN) 0 else m.addedAt.takeIf { it > 0 } ?: now()
    }

    /**
     * [deviceId] uses the radio [node], told inside an authenticated radio packet (ADR 0058): every entry of the device
     * gets it, like the node an internet packet carries (see [upsertMember]).
     */
    private suspend fun learnNode(deviceId: String, node: Long) {
        if (deviceId == me.deviceId) return
        var changed = emptyList<MemberState>()
        update { s ->
            changed = s.members.filter { it.deviceId == deviceId && it.node != node }
            if (changed.isEmpty()) s else s.copy(members = s.members.map { if (it.deviceId == deviceId) it.copy(node = node) else it })
        }
        changed.forEach { m -> member(m.circleId, m.deviceId)?.let { emit(CoreEvent.MemberUpdated(it, false)) } }
    }

    private suspend fun forgetMember(circleId: String, deviceId: String) =
        update { s -> s.copy(members = s.members.filterNot { it.circleId == circleId && it.deviceId == deviceId }) }

    // --- Persons and devices (ADR 0042) --------------------------------------------------------------

    /** Own key chain; `null` only on a device that was added without one (should not happen). */
    private var own: AnmpPerson? = null

    /** [eraseEverything] runs: nothing new is taken in. */
    private var erasing = false

    private val _ownDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())

    /** Devices of the own person, this one included. */
    val ownDevices: StateFlow<List<DeviceInfo>> = _ownDevices.asStateFlow()

    /** `true` once another own device removed this one from the list: nothing is sent any more. */
    private var revokedHere = false

    private val _revoked = MutableStateFlow(false)

    /**
     * `true` once another own device removed this one (ADR 0042 §6), also right after a restart: the app deletes
     * everything on this device. [CoreEvent.Revoked] reports the moment it happens.
     */
    val revoked: StateFlow<Boolean> = _revoked.asStateFlow()

    private val ownPersonId: String get() = own?.personIdText ?: me.deviceId

    /** Builds the own chain on first start or after an update; adds this device to an existing list if it is missing. */
    private suspend fun ensureOwnChain() {
        // A device added by another own device gets its chain handed over in the secret store (ADR 0042 §5). The state
        // may still hold the chain this installation made for itself before the hand-over (the running core saved it
        // again before the restart): take whichever chain certifies the person key this device holds.
        val stored = listOfNotNull(state.ownProof?.let(B64::decode), platform.secrets.get(CoreIdentity.PROOF))
            .firstNotNullOfOrNull { raw -> runCatching { AnmpPersons.verify(raw) }.getOrNull()?.takeIf { it.cert.person.contentEquals(me.person.publicKey) } }
        if (stored != null && (stored.device(me.device.deviceId) != null || stored.isRevoked(me.device.deviceId))) {
            setOwn(stored)
            // Chains from before ADR 0107 do not name the mailbox recipient yet: the next list does.
            if (stored.list.mailbox == null && stored.list.inbox.contentEquals(me.inbox.bytes) && !revokedHere && !config.legacyDirect) {
                changeOwnList(stored.list.devices, stored.list.revoked, quiet = true)
            }
            return
        }
        val root = me.root
        val certSigned = stored?.certSigned ?: root?.let { AnmpPersons.certify(it, AnmpPersonCert(it.publicKey, me.person.publicKey, 0, now())) }
        if (certSigned == null) {
            log.log("no key chain and no root key: this device runs as its own person")
            return
        }
        val cert = AnmpPersons.verifyCert(certSigned) ?: return
        val devices = stored?.list?.devices.orEmpty() + AnmpDeviceEntry(me.device.signKey, me.device.publicKey, added = now(), moved = movedHere)
        val list = AnmpDeviceList(cert.root, cert.n, (stored?.list?.seq ?: 0) + 1, now(), me.inbox.bytes, devices, stored?.list?.revoked.orEmpty(), listMailbox)
        val proof = AnmpPersons.proof(certSigned, AnmpPersons.signList(me.person, list))
        val p = AnmpPersons.verify(proof) ?: return
        update { s -> s.copy(ownProof = B64.encode(p.proof)) }
        setOwn(p)
        // Added to an existing list (hand-over, recovery): contacts learn this device after the start.
        if (stored != null) tellContactsOnStart = true
    }

    private var tellContactsOnStart = false

    private fun setOwn(p: AnmpPerson) {
        own = p
        revokedHere = p.isRevoked(me.device.deviceId) || state.revoked
        _revoked.value = revokedHere
        _ownDevices.value = deviceInfos(p)
    }

    private fun deviceInfos(p: AnmpPerson): List<DeviceInfo> =
        p.list.devices.mapIndexed { i, d -> DeviceInfo(B64.encode(p.deviceIds[i]), d.name.orEmpty(), d.added, d.node ?: 0, d.moved) }

    /**
     * Adds a device to the own list (ADR 0042 §5) and returns the new chain (b64u) to hand over together with the person
     * key, the mailbox owner key and the store secret. The app confirms with the device lock before. `null` if the
     * device is invalid, revoked or the list is full.
     */
    suspend fun addOwnDevice(signKey: String, publicKey: String, name: String): String? = onCore {
        opened.await()
        val cur = own ?: return@onCore null
        val sk = runCatching { B64.decode(signKey) }.getOrNull()?.takeIf { it.size == 32 } ?: return@onCore null
        val x = runCatching { B64.decode(publicKey) }.getOrNull()?.takeIf { it.size == 32 } ?: return@onCore null
        val id = AnmpKeys.deviceIdOf(sk)
        if (cur.isRevoked(id)) return@onCore null
        if (cur.device(id) != null) return@onCore B64.encode(cur.proof)
        // A device added now has nothing sealed for it from before ADR 0107.
        val entry = AnmpDeviceEntry(sk, x, name.trim().take(AnmpLinks.MAX_NAME).takeIf { it.isNotEmpty() }, now(), moved = true)
        changeOwnList(cur.list.devices + entry, cur.list.revoked)?.let { B64.encode(it.proof) }
    }

    /** Name of this device in the own list (e.g. the model, „Pixel 9“); signs a new list only if it changed. */
    suspend fun setOwnDeviceName(name: String): Unit = onCore {
        opened.await()
        val cur = own ?: return@onCore
        if (revokedHere) return@onCore
        val n = name.trim().take(AnmpLinks.MAX_NAME).takeIf { it.isNotEmpty() }
        val i = cur.deviceIds.indexOfFirst { it.contentEquals(me.device.deviceId) }.takeIf { it >= 0 } ?: return@onCore
        val d = cur.list.devices[i]
        if (d.name == n) return@onCore
        changeOwnList(cur.list.devices.mapIndexed { j, e -> if (j == i) AnmpDeviceEntry(e.signKey, e.key, n, e.added, e.node, e.moved) else e }, cur.list.revoked, quiet = true)
    }

    /** Removes an own device for good (ADR 0042 §6); it stops once it sees the new list. `false` for this device or if unknown. */
    suspend fun removeOwnDevice(deviceId: String): Boolean = onCore {
        opened.await()
        val cur = own ?: return@onCore false
        if (deviceId == me.deviceId) return@onCore false
        val id = runCatching { B64.decode(deviceId) }.getOrNull() ?: return@onCore false
        val i = cur.deviceIds.indexOfFirst { it.contentEquals(id) }.takeIf { it >= 0 } ?: return@onCore false
        changeOwnList(cur.list.devices.filterIndexed { j, _ -> j != i }, cur.list.revoked + id) != null
    }

    /**
     * After theft (ADR 0042 §6): the root key certifies a new person key (n + 1), the person gets a new mailbox, the
     * [stolen] devices are revoked, every circle changes its key, contacts get the new chain and the own other devices
     * the new keys. Needs the root key; `false` without it.
     */
    suspend fun renewPersonKey(stolen: List<String>, code: String? = null): Boolean = onCore {
        opened.await()
        val kern = code?.let { loadKern(it) }
        val root = me.root ?: kern?.let { AnmpSpaceKey.fromPrivateKey(it.root) } ?: return@onCore false
        val cur = own ?: return@onCore false
        val stolenIds = stolen.filter { it != me.deviceId }.mapNotNull { runCatching { B64.decode(it) }.getOrNull()?.takeIf { b -> b.size == 16 } }
        val keep = cur.list.devices.indices.filterNot { i -> stolenIds.any { it.contentEquals(cur.deviceIds[i]) } }
        val oldOthers = ownOthers().filter { p -> stolenIds.none { B64.encode(it) == p.deviceId } }
        val oldAccess = ownAccess()
        val person = AnmpKeys.randomKey()
        val inbox = AnmpKeys.randomKey()
        val personKey = AnmpSpaceKey.fromPrivateKey(person)
        val inboxKey = SigningKey.fromSeed(inbox)
        val certSigned = AnmpPersons.certify(root, AnmpPersonCert(root.publicKey, personKey.publicKey, cur.cert.n + 1, now()))
        val list = AnmpDeviceList(
            cur.root, cur.cert.n + 1, 1, now(), inboxKey.key.bytes, keep.map { cur.list.devices[it] },
            (cur.list.revoked + stolenIds).distinctBy { B64.encode(it) },
            AnmpMailbox.of(inbox).publicKey,
        )
        val p = AnmpPersons.verify(AnmpPersons.proof(certSigned, AnmpPersons.signList(personKey, list))) ?: return@onCore false
        // The stolen devices knew the state secret, which names the person key: a new one, named in the Kern-space of the
        // code typed now or of the card still waiting for confirmation (root key on this device). Without either the old
        // state space is emptied and the card has to be made anew ([hasEmergencyCard] is then `false`).
        val oldState = platform.secrets.get(STATE_SECRET)
        val kernCode = code?.let { RecoveryCode.parse(it) } ?: platform.secrets.get(PENDING_CODE)
        val rootSeed = kern?.root ?: platform.secrets.get(CoreIdentity.ROOT)
        val newState = if (kernCode != null && rootSeed != null) AnmpKeys.randomKey() else null
        if (newState != null) {
            kernSpace(kernCode!!).put(coreCbor.encodeToByteArray(KernEntry.serializer(), KernEntry(rootSeed!!, newState, now())))
            platform.secrets.put(STATE_SECRET, newState)
        } else if (oldState != null) {
            platform.secrets.put(STATE_SECRET, null)
        }
        oldState?.let { old -> cs.launch { runCatching { stateSpace(old).let { sp -> sp.delete(sp.read().entries.keys) } } } }
        // The stolen devices knew the store secret too (own store, passwords, profile): a new one; the files move over.
        val newStore = AnmpKeys.randomKey()
        // The own other devices get the new keys through the old mailbox, which they still read.
        val keys = AnmpSyncKeys(person, inbox, newStore, platform.secrets.get(STATE_SECRET))
        for (peer in oldOthers) {
            val encoded = Packets.encode(me, AnmpKind.SYNC, content(withChain = false, keys = keys).withPerson(p), Anmp.DIRECT_SCOPE, null, peer)
            transport.submit(oldAccess.space, oldAccess.seal(encoded.packet), lifetimeUntil(), known = oldAccess)
        }
        // The stolen devices get only the new chain: an honest app sees itself removed and stops.
        for (i in cur.list.devices.indices.filterNot { it in keep }) {
            val peer = Peer(B64.encode(cur.deviceIds[i]), B64.encode(cur.list.devices[i].key))
            val encoded = Packets.encode(me, AnmpKind.SYNC, content(withChain = false).withPerson(p), Anmp.DIRECT_SCOPE, null, peer)
            transport.submit(oldAccess.space, oldAccess.seal(encoded.packet), lifetimeUntil(), known = oldAccess)
        }
        adoptKeys(person, inbox, p)
        switchStore(newStore)
        emit(CoreEvent.DevicesChanged(ownPersonId, own = true, added = emptyList(), removed = stolenIds.map(B64::encode)))
        cs.launch {
            runCatching { backupState() }
            val stolenText = stolenIds.map(B64::encode)
            // Every stolen device is left out of every key change (rotate skips revoked devices); the first one in the
            // circle is named as removed.
            state.circles.filterNot { it.removed }.forEach { c -> rotate(c.id, stolenText.firstOrNull { d -> membersOf(c.id).any { m -> m.deviceId == d } }, WHY_DEVICE) }
            state.members.filter { it.circleId == MemberState.CONTACTS }.map { it.deviceId }.distinct().forEach { runCatching { sendProfileTo(it) } }
        }
        true
    }

    /** Takes new person and mailbox keys (own change or from an own device) and the matching chain. */
    private suspend fun adoptKeys(person: ByteArray, inbox: ByteArray, p: AnmpPerson) {
        platform.secrets.put(CoreIdentity.PERSON, person)
        platform.secrets.put(CoreIdentity.INBOX, inbox)
        me = CoreIdentity.loadOrCreate(platform.secrets)
        update { s -> s.copy(ownProof = B64.encode(p.proof)) }
        setOwn(p)
        _identity.value = IdentityInfo(me.deviceId, B64.encode(me.tag), me.publicKey, me.signKey, me.inbox.toString(), ownPersonId)
    }

    private fun ownStore(keys: StoreKeys) = FileStore(
        keys, net, pool, transport,
        SpaceAccess(keys.space, keys.owner, null, me.inboxEnvelope, keys.owner),
        me = { ownPersonId }, allMine = true, now = ::now, trashMillis = timings.trashKeep.inWholeMilliseconds,
    ) { log.log(it) }

    /**
     * A new store secret after a person key change (ADR 0042 §6): own store, password and profile spaces start anew;
     * what lies under the previous secret is moved over ([moveOldStore]) and then deleted there.
     */
    private suspend fun switchStore(secret: ByteArray) {
        val old = platform.secrets.get(StoreKeys.SECRET)
        if (old != null && old.contentEquals(secret)) return
        platform.secrets.put(StoreKeys.SECRET, secret)
        store = ownStore(StoreKeys.of(secret))
        if (old != null && state.previousStore == null) update { s -> s.copy(previousStore = B64.encode(old), previousStoreSince = now()) }
        cs.launch { runCatching { moveOldStore() }.onFailure { if (it is CancellationException) throw it; log.log("store move: ${it.message}") } }
    }

    private val moving = Mutex()

    /** Moves files, passwords and profile from the previous store secret; forgets it once all is over (or too old). */
    private suspend fun moveOldStore() = moving.withLock {
        val done = onCore {
            val old = state.previousStore?.let(B64::decode) ?: return@onCore true
            val files = moveOldStoreFiles()
            val vault = moveOldVault(old)
            // The profile goes over by writing the merged profile into the new space; the old one is emptied.
            val profile = profileSpace(old)?.let { sp ->
                val r = sp.read()
                if (!r.answered) return@let false
                profileSync()
                r.entries.values.mapNotNull { runCatching { coreCbor.decodeFromByteArray(ProfileEntry.serializer(), it).image?.id }.getOrNull() }
                    .forEach { id -> transport.delete(sp.accessOf().cred, sp.space, ID(id), entry = false) }
                if (r.entries.isNotEmpty()) sp.delete(r.entries.keys)
                true
            } ?: true
            val devices = moveOldDeviceSpace(old)
            files && vault && profile && devices
        }
        if (done || now() - state.previousStoreSince > STORE_CLEANUP_KEEP) onCore {
            update { s -> s.copy(previousStore = null, previousStoreSince = 0) }
        }
    }

    /** Files of the previous own store into the current one, then deleted there; `true` once none is left. */
    private suspend fun moveOldStoreFiles(): Boolean {
        val old = state.previousStore?.let(B64::decode) ?: return true
        val previous = ownStore(StoreKeys.of(old))
        val files = previous.list() ?: return false
        val known = store.list()?.map { it.file }?.toSet() ?: return false
        var ok = true
        for (f in files) {
            if (f.file !in known) {
                val got = previous.fetch(f) as? BlobResult.Found
                if (got == null) { ok = false; continue }
                val put = runCatching { store.put(f.file, got.plaintext, f.name, f.type, f.added, f.changed, f.width, f.height, pool::checkObjectSize, f.doc, by = f.by, trashed = f.trashed, title = f.title) }
                    .onFailure { if (it is CancellationException) throw it; log.log("store move ${f.file.take(8)}: ${it.message}") }
                if (put.isFailure) { ok = false; continue }
            }
            if (!previous.delete(f.file)) ok = false
        }
        return ok
    }

    /** The newest password vault of the previous store secret into the current space if that has none; then deleted. */
    private suspend fun moveOldVault(old: ByteArray): Boolean {
        val from = vaultSpace(old) ?: return true
        val r = from.read()
        if (!r.answered) return false
        if (r.entries.isEmpty()) return true
        val to = vaultSpace() ?: return false
        val there = to.read()
        if (!there.answered) return false
        val newest = newestVault(r.entries.values)
        val current = newestVault(there.entries.values)
        if (newest != null && (current == null || newest.changed > current.changed)) {
            to.put(coreCbor.encodeToByteArray(VaultEntry.serializer(), newest))
            if (there.entries.isNotEmpty()) to.delete(there.entries.keys)
        }
        from.delete(r.entries.keys)
        return true
    }

    private fun AnmpContent.withPerson(p: AnmpPerson) = AnmpContent(
        id, ts, text, ref, refFrom, emoji, ids, name, key, signKey, node, attachments, image, imageTs, imageHash, circleImageHash, meta,
        wantHash, haveRef, needsHistory, history, rekey, removed, inbox, grant, p.proof, peer, p.ref, circle, sync, keys, ownData,
    )

    /** Signs a new own list (seq + 1) and tells everyone: circles (member), contacts (profile), own devices (sync). */
    private suspend fun changeOwnList(devices: List<AnmpDeviceEntry>, revoked: List<ByteArray>, quiet: Boolean = false): AnmpPerson? {
        val cur = own ?: return null
        if (devices.size > AnmpPersons.MAX_DEVICES || revoked.size > AnmpPersons.MAX_REVOKED) return null
        val list = AnmpDeviceList(cur.root, cur.cert.n, cur.list.seq + 1, now(), me.inbox.bytes, devices, revoked, listMailbox)
        val p = AnmpPersons.verify(AnmpPersons.proof(cur.certSigned, AnmpPersons.signList(me.person, list))) ?: return null
        update { s -> s.copy(ownProof = B64.encode(p.proof)) }
        setOwn(p)
        val oldIds = cur.deviceIds.map(B64::encode)
        val added = deviceInfos(p).filter { it.deviceId !in oldIds }
        val removed = oldIds - p.deviceIds.map(B64::encode).toSet()
        if (!quiet) emit(CoreEvent.DevicesChanged(ownPersonId, own = true, added = added, removed = removed))
        // A removed device may still read the own mailbox with the old owner key; its own copies just stop.
        cs.launch {
            state.circles.filterNot { it.removed }.forEach { announce(it.id) }
            state.members.filter { it.circleId == MemberState.CONTACTS }.map { it.deviceId }.distinct().forEach { runCatching { sendProfileTo(it) } }
            // The removed device must learn it is out: one last copy of the chain into its slot of the own mailbox.
            removed.forEach { r -> cur.device(B64.decode(r))?.let { d -> postOwnTo(Peer(r, B64.encode(d.key)), AnmpKind.SYNC, content(withChain = true)) } }
        }
        return p
    }

    // --- Pairing: adding a device by QR (ADR 0042 §5) -------------------------------------------------

    /**
     * On a new device, before anything else: the pairing link to show as QR. Contains this device's keys, a pickup
     * space this device owns with a write grant for anyone (1 hour) and a one-time secret. Fails if this installation
     * already has circles or contacts (they would be lost).
     */
    suspend fun pairingOffer(name: String = ""): String = onCore {
        opened.await()
        check(state.circles.isEmpty() && state.members.isEmpty()) { "pairing only on a new installation" }
        fun keyOf(k: String) = platform.secrets.get(k)?.takeIf { it.size == 32 } ?: AnmpKeys.randomKey().also { platform.secrets.put(k, it) }
        val owner = SigningKey.fromSeed(keyOf(PAIR_OWNER))
        val secret = keyOf(PAIR_SECRET)
        val t = nowS()
        val grant = Grant.issue(owner, GrantBody(owner.key, Rights.WRITE, null, null, t + PAIR_LIFETIME.inWholeSeconds, t - GRANT_BACKDATE))
        AnmpLinks.pairLink(config.linkHost, PairOffer(config.networkId.toString(), me.deviceId, me.signKey, me.publicKey, owner.key.toString(), grant.toString(), B64.encode(secret), name))
    }

    /**
     * On the new device: waits up to [timeout] for the hand-over of [acceptPairing]. On success the keys of the person are
     * in the secret store and the extras of the app are returned; the app then stops this core and starts a new one.
     * `null` on timeout or without [pairingOffer] before.
     */
    suspend fun awaitPairing(timeout: Duration): Map<String, ByteArray>? {
        opened.await()
        val owner = platform.secrets.get(PAIR_OWNER)?.let { SigningKey.fromSeed(it) } ?: return null
        val secret = platform.secrets.get(PAIR_SECRET) ?: return null
        val aead = AnmpAead.of(AnmpKeys.hkdf(secret, AnmpInfo.PAIR))
        val aad = (AnmpInfo.PAIR + "\n" + owner.key).encodeToByteArray()
        return withTimeoutOrNull(timeout) {
            while (true) {
                for (r in net.holders(owner.key.toString())) {
                    val client = pool.client(r) ?: continue
                    val page = runCatching { client.list(Credentials(owner), owner.key, ListOptions("", 0)) }.getOrNull() ?: continue
                    for (o in page.objects) {
                        val data = o.inline() ?: runCatching { client.get(Credentials(owner), owner.key, o.id).data }.getOrNull() ?: continue
                        val h = aead.open(data, aad)?.let { runCatching { coreCbor.decodeFromByteArray(Handover.serializer(), it) }.getOrNull() } ?: continue
                        if (takeHandover(h)) return@withTimeoutOrNull h.extras
                    }
                }
                delay(timings.minPollInterval * 10)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }

    /**
     * On the new device, after [pairingOffer]: the comparison code to show next to the QR (ADR 0093). The own device
     * that accepts shows the same code from the link ([pairingCode]); the person compares both. `null` without an offer.
     */
    suspend fun ownPairingCode(): String? = onCore {
        val secret = platform.secrets.get(PAIR_SECRET) ?: return@onCore null
        AnmpKeys.pairCode(secret, me.device.signKey)
    }

    /** On the accepting device: the comparison code of the pairing link [text] (ADR 0093); `null` if it is no pairing link. */
    suspend fun pairingCode(text: String): String? = onCore {
        val offer = linkHosts.firstNotNullOfOrNull { AnmpLinks.parsePair(text, it) } ?: return@onCore null
        runCatching { AnmpKeys.pairCode(B64.decode(offer.secret), B64.decode(offer.signKey)) }.getOrNull()
    }

    /** Checks the hand-over against this device and writes the person's keys; the state starts empty. */
    private suspend fun takeHandover(h: Handover): Boolean {
        if (h.person.size != 32 || h.inbox.size != 32) return false
        val p = AnmpPersons.verify(h.proof) ?: return false
        if (p.device(me.device.deviceId) == null) return false
        if (!AnmpSpaceKey.fromPrivateKey(h.person).publicKey.contentEquals(p.cert.person)) return false
        if (!SigningKey.fromSeed(h.inbox).key.bytes.contentEquals(p.list.inbox)) return false
        val sec = platform.secrets
        sec.put(CoreIdentity.PERSON, h.person)
        sec.put(CoreIdentity.INBOX, h.inbox)
        h.store?.takeIf { it.size == 32 }?.let { sec.put(StoreKeys.SECRET, it) }
        h.state?.takeIf { it.size == 32 }?.let { sec.put(STATE_SECRET, it) }
        sec.put(CoreIdentity.PROOF, h.proof)
        sec.put(CoreIdentity.ROOT, null)
        sec.put(STATE_KEY, null)
        sec.put(PAIR_OWNER, null)
        sec.put(PAIR_SECRET, null)
        return true
    }

    /**
     * On an own device holding the person key: adds the device of the pairing link [text] to the list and hands over
     * the chain, person key, mailbox owner key, store secret and the app's [extras] (e.g. the password key), sealed with
     * the one-time secret, into the new device's pickup space. The app confirms with the device lock before.
     */
    suspend fun acceptPairing(text: String, extras: Map<String, ByteArray> = emptyMap()): PairResult = onCore {
        opened.await()
        val offer = linkHosts.firstNotNullOfOrNull { AnmpLinks.parsePair(text, it) } ?: return@onCore PairResult.Invalid
        if (offer.networkId != config.networkId.toString()) return@onCore PairResult.ForeignNetwork
        val cur = own ?: return@onCore PairResult.Invalid
        val id = B64.decode(offer.deviceId)
        if (offer.deviceId == me.deviceId) return@onCore PairResult.Invalid
        if (cur.isRevoked(id)) return@onCore PairResult.Revoked
        val existed = cur.device(id) != null
        if (!existed && cur.list.devices.size >= AnmpPersons.MAX_DEVICES) return@onCore PairResult.Full
        val proof = addOwnDevice(offer.signKey, offer.publicKey, offer.name) ?: return@onCore PairResult.Invalid
        val sec = platform.secrets
        val h = Handover(
            B64.decode(proof), sec.get(CoreIdentity.PERSON) ?: return@onCore PairResult.Invalid, sec.get(CoreIdentity.INBOX) ?: return@onCore PairResult.Invalid,
            sec.get(StoreKeys.SECRET), extras, sec.get(STATE_SECRET),
        )
        val space = Key.parse(offer.space)
        val aead = AnmpAead.of(AnmpKeys.hkdf(B64.decode(offer.secret), AnmpInfo.PAIR))
        val entry = aead.seal(coreCbor.encodeToByteArray(Handover.serializer(), h), (AnmpInfo.PAIR + "\n" + space).encodeToByteArray())
        val grant = runCatching { Grant.parse(offer.grant) }.getOrNull() ?: return@onCore PairResult.Invalid
        transport.submit(space, entry, lifetimeUntil(PAIR_LIFETIME), known = SpaceAccess(space, me.writer, grant, me.inboxEnvelope, null))
        val info = _ownDevices.value.firstOrNull { it.deviceId == offer.deviceId } ?: DeviceInfo(offer.deviceId, offer.name)
        return@onCore if (existed) PairResult.AlreadyAdded(info) else PairResult.Added(info)
    }

    /** The own other devices as recipients. */
    /** The own mailbox recipient as a recipient of own copies (ADR 0107). */
    /** The own mailbox recipient as named in the own list (ADR 0107); `null` only in tests of the old form. */
    private val listMailbox: ByteArray? get() = me.mailbox.publicKey.takeUnless { config.legacyDirect }

    private val ownMailbox: Peer get() = Peer(B64.encode(me.mailbox.id), B64.encode(me.mailbox.publicKey), person = true)

    private fun ownOthers(): List<Peer> {
        val p = own ?: return emptyList()
        return p.list.devices.indices.map { Peer(B64.encode(p.deviceIds[it]), B64.encode(p.list.devices[it].key)) }.filter { it.deviceId != me.deviceId }
    }

    private fun personState(personId: String): PersonState? = state.persons.firstOrNull { it.personId == personId }

    private fun isRevoked(deviceId: String): Boolean =
        state.persons.any { deviceId in it.revoked } || own?.isRevoked(B64.decode(deviceId)) == true

    /**
     * Person a device belongs or belonged to: like [personOf], and a revoked device still names its person (spec "Persons
     * and devices", rule 4), e.g. the old phone after recovering with the emergency card. For the app to attribute
     * messages it already keeps; never for checks or sending.
     */
    suspend fun personOfDevice(deviceId: String): String = onCore { revokedOwner(deviceId)?.first ?: personOf(deviceId) }

    /** Person of a revoked device and the time (ms) of the list that names it revoked; `null` if not revoked. */
    private fun revokedOwner(deviceId: String): Pair<String, Long>? {
        val raw = runCatching { B64.decode(deviceId) }.getOrNull() ?: return null
        own?.takeIf { it.isRevoked(raw) }?.let { return it.personIdText to it.list.ts }
        val s = state.persons.firstOrNull { deviceId in it.revoked } ?: return null
        return s.personId to (parsedProof(s)?.list?.ts ?: 0)
    }

    /**
     * Author of a message in a circle history (ADR 0014): a revoked device still belongs to its person for what it
     * wrote before; `null` drops what it wrote after the list that names it revoked.
     */
    private fun historyAuthorOf(deviceId: String, tsMillis: Long): String? {
        val (person, listTs) = revokedOwner(deviceId) ?: return personOf(deviceId)
        return person.takeIf { tsMillis <= listTs }
    }

    /**
     * Person of a device: the own person for an own device, a person whose list names it, a member entry; else the
     * device itself. Own devices are often in no common circle, so the lists come first.
     */
    private fun personOf(deviceId: String): String {
        val raw = runCatching { B64.decode(deviceId) }.getOrNull()
        if (raw != null) {
            own?.takeIf { it.device(raw) != null }?.let { return it.personIdText }
            state.persons.firstOrNull { s -> parsedProof(s)?.device(raw) != null }?.let { return it.personId }
        }
        return state.members.firstNotNullOfOrNull { it.personId?.takeIf { _ -> it.deviceId == deviceId } } ?: deviceId
    }

    /**
     * Checks the sender of [o] against its person (spec "Persons and devices"): takes a newer chain from content 25,
     * refuses revoked devices and devices moved to another person. Returns the person ID, `null` to drop.
     */
    private suspend fun acceptSender(o: Opened): String? {
        if (isRevoked(o.fromDevice)) return null
        val known = personOf(o.fromDevice)
        val carried = o.content.person?.let { AnmpPersons.verify(it) }
        // Unsigned (to a mailbox recipient, ADR 0107): the pair key proves the device whose X25519 key it used; that key
        // must be the one the person's chain lists for the device (or the one pinned for a member without a chain).
        val chain = carried ?: if (known == ownPersonId) own else personState(known)?.let(::parsedProof)
        if (!o.message.signed && !o.forwarded && !keyListed(o, chain)) return null
        if (o.content.person == null) return known
        val p = carried ?: return known
        val device = B64.decode(o.fromDevice)
        if (p.device(device) == null) return null
        val pid = p.personIdText
        if (known != o.fromDevice && known != pid) return null
        if (pid == ownPersonId) {
            onOwnChain(p)
            return pid
        }
        val s = personState(pid)
        val revoked = (s?.revoked.orEmpty() + p.list.revoked.map(B64::encode)).distinct()
        when {
            s == null -> {
                cacheProof(B64.encode(p.proof), p)
                update { st -> st.copy(persons = st.persons + PersonState(pid, B64.encode(p.proof), p.cert.n, p.list.seq, revoked)) }
            }
            p.cert.n > s.n || (p.cert.n == s.n && p.list.seq > s.seq) -> {
                val before = parsedProofs[s.proof] ?: AnmpPersons.verify(B64.decode(s.proof))
                cacheProof(B64.encode(p.proof), p)
                update { st -> st.copy(persons = st.persons.map { if (it.personId == pid) PersonState(pid, B64.encode(p.proof), p.cert.n, p.list.seq, revoked, it.conflict) else it }) }
                onPersonChanged(pid, before, p, revoked)
            }
            p.cert.n == s.n && p.list.seq == s.seq && s.proof != B64.encode(p.proof) && !s.conflict -> {
                update { st -> st.copy(persons = st.persons.map { if (it.personId == pid) it.copy(conflict = true, revoked = revoked) else it }) }
                emit(CoreEvent.PersonConflict(pid))
            }
            revoked.size != s.revoked.size -> update { st -> st.copy(persons = st.persons.map { if (it.personId == pid) it.copy(revoked = revoked) else it }) }
        }
        return pid
    }

    /**
     * Takes the other devices of the contact [pid] from its list as contacts and sends each a profile with a personal
     * grant, so every device of the person may write to us (ADR 0042), also those linked before we met. Only devices
     * not yet known as contacts; a person learned before it became a contact gets no [onPersonChanged] for them.
     */
    private suspend fun adoptDevicesOf(pid: String) {
        if (pid == ownPersonId) return
        val s = personState(pid) ?: return
        val p = parsedProof(s) ?: return
        val known = state.members.filter { it.circleId == MemberState.CONTACTS && it.person == pid }
        val name = known.firstOrNull()?.name ?: return
        val inbox = B64.encode(p.list.inbox)
        val added = mutableListOf<String>()
        p.list.devices.forEachIndexed { i, d ->
            val id = B64.encode(p.deviceIds[i])
            if (known.any { it.deviceId == id } || isRevoked(id)) return@forEachIndexed
            val (ok, _) = upsertMember(MemberState(MemberState.CONTACTS, id, name, B64.encode(d.key), B64.encode(d.signKey), inbox = inbox, personId = pid))
            if (ok) added += id
        }
        sendProfilesTo(p, added)
    }

    /**
     * A profile for the new devices [ids] of [p]'s person: to its mailbox recipient once, if no grant for it was given
     * yet (it reaches every device, ADR 0107); for older chains one per device.
     */
    private fun sendProfilesTo(p: AnmpPerson, ids: List<String>) {
        if (ids.isEmpty()) return
        if (mailboxTargetOf(p) == null) {
            ids.forEach { id -> cs.launch { sendProfileTo(id) } }
        } else if (!hasIssued(grantPurposeOf(ids.first()))) {
            cs.launch { sendProfileTo(ids.first()) }
        }
    }

    /** A known person has a new list: forget revoked devices, take new ones as contacts where the person is one. */
    private suspend fun onPersonChanged(pid: String, before: AnmpPerson?, p: AnmpPerson, revoked: List<String>) {
        val oldIds = before?.deviceIds?.map(B64::encode).orEmpty()
        val newIds = p.deviceIds.map(B64::encode)
        val gone = (oldIds - newIds.toSet()) + revoked.filter { r -> state.members.any { it.deviceId == r } }
        update { s -> s.copy(members = s.members.filterNot { it.deviceId in gone }, grants = s.grants.filterNot { it.deviceId in gone }) }
        val isContact = state.members.any { it.circleId == MemberState.CONTACTS && it.person == pid }
        val name = state.members.firstOrNull { it.person == pid }?.name.orEmpty()
        val added = mutableListOf<DeviceInfo>()
        val newContacts = mutableListOf<String>()
        p.list.devices.forEachIndexed { i, d ->
            val id = newIds[i]
            if (id in oldIds) return@forEachIndexed
            added += DeviceInfo(id, d.name.orEmpty(), d.added, d.node ?: 0)
            if (isContact) {
                upsertMember(MemberState(MemberState.CONTACTS, id, name, B64.encode(d.key), B64.encode(d.signKey), inbox = B64.encode(p.list.inbox), personId = pid))
                newContacts += id
            }
        }
        sendProfilesTo(p, newContacts)
        if (before != null && (added.isNotEmpty() || gone.isNotEmpty())) emit(CoreEvent.DevicesChanged(pid, own = false, added = added, removed = gone.distinct()))
    }

    /** New keys from an own device after a person key change; the chain must be certified for them. */
    private suspend fun applyKeys(k: AnmpSyncKeys, proof: ByteArray?) {
        val p = proof?.let { AnmpPersons.verify(it) } ?: return
        if (p.personIdText != ownPersonId || p.device(me.device.deviceId) == null) return
        if (k.person.size != 32 || k.inbox.size != 32) return
        if (!AnmpSpaceKey.fromPrivateKey(k.person).publicKey.contentEquals(p.cert.person)) return
        if (!SigningKey.fromSeed(k.inbox).key.bytes.contentEquals(p.list.inbox)) return
        if (me.person.publicKey.contentEquals(p.cert.person)) return
        k.state?.takeIf { it.size == 32 }?.let { platform.secrets.put(STATE_SECRET, it) }
        adoptKeys(k.person, k.inbox, p)
        k.store?.takeIf { it.size == 32 }?.let { switchStore(it) }
    }

    /**
     * Another own list. Newer and covering ours: take it. Signed at the same time on two devices (or older but with
     * something we lack): merge both into one list that every device computes alike (ADR 0042, own lists).
     */
    private suspend fun onOwnChain(p: AnmpPerson) {
        val cur = own ?: return
        if (p.cert.n == cur.cert.n && !p.proof.contentEquals(cur.proof) && !covers(p, cur)) {
            if (covers(cur, p)) return
            mergeOwn(cur, p)
            return
        }
        if (!(p.cert.n > cur.cert.n || (p.cert.n == cur.cert.n && p.list.seq > cur.list.seq))) return
        // A new person key comes with its keys (applyKeys); without them only the device list may change.
        if (p.cert.n > cur.cert.n && !me.person.publicKey.contentEquals(p.cert.person)) {
            if (p.device(me.device.deviceId) == null) {
                // The new chain is not ours to keep (other person key): remember the removal for the next start.
                update { s -> s.copy(revoked = true) }
                setOwn(cur)
                emit(CoreEvent.Revoked)
            }
            return
        }
        val oldIds = cur.deviceIds.map(B64::encode)
        update { s -> s.copy(ownProof = B64.encode(p.proof)) }
        setOwn(p)
        if (revokedHere) {
            emit(CoreEvent.Revoked)
            return
        }
        val added = deviceInfos(p).filter { it.deviceId !in oldIds }
        val removed = oldIds - p.deviceIds.map(B64::encode).toSet()
        if (added.isNotEmpty() || removed.isNotEmpty()) emit(CoreEvent.DevicesChanged(ownPersonId, own = true, added = added, removed = removed))
    }

    /** `true` if [a] has every device (with the same entry) and every revocation of [b]. */
    private fun covers(a: AnmpPerson, b: AnmpPerson): Boolean {
        val revoked = a.list.revoked.map(B64::encode).toSet()
        if (!b.list.revoked.all { B64.encode(it) in revoked }) return false
        return b.deviceIds.indices.all { i ->
            val id = b.deviceIds[i]
            if (a.isRevoked(id)) return@all true
            val e = a.device(id) ?: return@all false
            val o = b.list.devices[i]
            e.name == o.name || o.name == null
        }
    }

    /**
     * One list from two of the same certificate: devices and revocations united, revoked devices out, of two names for a
     * device the one of the higher list (then the larger one), order by device ID, `seq` one above both, `ts` the later.
     * Deterministic, so two devices merging at once sign the same bytes (Ed25519 is deterministic).
     */
    private suspend fun mergeOwn(a: AnmpPerson, b: AnmpPerson) {
        val (hi, lo) = if (a.list.seq >= b.list.seq) a to b else b to a
        val revoked = (hi.list.revoked + lo.list.revoked).distinctBy { B64.encode(it) }.sortedBy { B64.encode(it) }
        val gone = revoked.map(B64::encode).toSet()
        val entries = mutableMapOf<String, AnmpDeviceEntry>()
        for (p in listOf(lo, hi)) p.list.devices.forEachIndexed { i, e ->
            val id = B64.encode(p.deviceIds[i])
            if (id in gone) return@forEachIndexed
            val prev = entries[id]
            val mine = e.name
            val theirs = prev?.name
            val name = when {
                prev == null || theirs == null -> mine
                mine == null -> theirs
                p === hi && theirs != mine -> mine
                else -> maxOf(theirs, mine)
            }
            entries[id] = AnmpDeviceEntry(e.signKey, e.key, name, minOf(e.added, prev?.added ?: e.added), e.node ?: prev?.node, e.moved || prev?.moved == true)
        }
        val devices = entries.entries.sortedBy { it.key }.map { it.value }
        if (devices.isEmpty() || devices.size > AnmpPersons.MAX_DEVICES) return
        // Every own device derives the same mailbox recipient from the mailbox owner seed, so both sides get the same bytes.
        val mailbox = if (hi.list.inbox.contentEquals(me.inbox.bytes)) listMailbox else hi.list.mailbox
        val list = AnmpDeviceList(hi.root, hi.cert.n, hi.list.seq + 1, maxOf(hi.list.ts, lo.list.ts), hi.list.inbox, devices, revoked, mailbox)
        val merged = AnmpPersons.verify(AnmpPersons.proof(hi.certSigned, AnmpPersons.signList(me.person, list))) ?: return
        val oldIds = a.deviceIds.map(B64::encode)
        update { s -> s.copy(ownProof = B64.encode(merged.proof)) }
        setOwn(merged)
        if (revokedHere) {
            emit(CoreEvent.Revoked)
            return
        }
        val added = deviceInfos(merged).filter { it.deviceId !in oldIds }
        val removed = oldIds - merged.deviceIds.map(B64::encode).toSet()
        if (added.isNotEmpty() || removed.isNotEmpty()) emit(CoreEvent.DevicesChanged(ownPersonId, own = true, added = added, removed = removed))
    }

    /** Recipients of a person: its devices from the chain, else the member entries of that device. */
    private fun targetsOf(person: String): List<Target> {
        personState(person)?.let { s ->
            val p = parsedProof(s) ?: return@let
            val inbox = B64.encode(p.list.inbox)
            return p.list.devices.indices.map { Target(Peer(B64.encode(p.deviceIds[it]), B64.encode(p.list.devices[it].key)), inbox, legacy = false) }
                .filterNot { isRevoked(it.peer.deviceId) }
        }
        // Before the chain: the device itself, or a member whose person we know from an introduction (ADR 0116).
        val m = state.members.lastOrNull { it.isOf(person) && it.inbox != null } ?: state.members.lastOrNull { it.isOf(person) } ?: return emptyList()
        return listOf(targetOfMember(m))
    }

    /** [id] is this member's device or, before its chain is known, its person (ADR 0116: known from the introduction). */
    private fun MemberState.isOf(id: String) = deviceId == id || personId == id

    private fun targetOfMember(m: MemberState) = Target(Peer(m.deviceId, m.publicKey), m.inbox.orEmpty(), legacy = m.personId == null)

    /**
     * Mailbox recipient of [person] (ADR 0084) while we know no key chain of it, only the contact link with field 8:
     * every device of the person opens packets to it. Its grant is for anyone, like the link grant, because we do not
     * know yet which device answers.
     */
    private suspend fun mailboxTargetOf(person: String): Target? {
        if (personState(person) != null) return null
        val m = state.members.lastOrNull { it.isOf(person) && it.mailboxKey != null && it.inbox != null } ?: return null
        val space = runCatching { Key.parse(m.inbox!!) }.getOrNull() ?: return null
        val id = AnmpKeys.mailboxIdOf(space.bytes)
        return Target(Peer(B64.encode(id), m.mailboxKey!!), m.inbox!!, legacy = false)
    }

    /** Target for one device (profile, key change, history, forwarding). */
    private fun targetOfDevice(deviceId: String): Target? {
        val pid = personOf(deviceId)
        personState(pid)?.let(::parsedProof)?.takeIf { p -> p.deviceIds.any { B64.encode(it) == deviceId } }?.let(::mailboxTargetOf)?.let { return it }
        targetsOf(pid).firstOrNull { it.peer.deviceId == deviceId }?.let { return it }
        return (state.members.lastOrNull { it.deviceId == deviceId && it.inbox != null } ?: anyMember(deviceId))?.let(::targetOfMember)
    }

    private val parsedProofs = mutableMapOf<String, AnmpPerson>()

    private fun parsedProof(s: PersonState): AnmpPerson? = parsedProofs[s.proof]

    /** Mailbox recipient ID per mailbox space (b64u), filled with the parsed chains: ID derivation is suspending. */
    private val mailboxIds = mutableMapOf<String, ByteArray>()

    private suspend fun cacheProof(key: String, p: AnmpPerson) {
        parsedProofs[key] = p
        val inbox = B64.encode(p.list.inbox)
        if (p.list.mailbox != null && inbox !in mailboxIds) mailboxIds[inbox] = AnmpKeys.mailboxIdOf(p.list.inbox)
    }

    /**
     * The one recipient of everything to [p]'s person (ADR 0107 §1, §3): its mailbox recipient (ADR 0084), if the chain
     * names it; `null` for chains from before.
     */
    private fun mailboxTargetOf(p: AnmpPerson): Target? {
        val key = p.list.mailbox ?: return null
        val inbox = B64.encode(p.list.inbox)
        val id = mailboxIds[inbox] ?: return null
        return Target(Peer(B64.encode(id), B64.encode(key), person = true), inbox, legacy = false, mailbox = true)
    }

    /** `true` if [p] lists the sending device of [o] with exactly the keys that opened and named it. */
    private fun keyListed(o: Opened, p: AnmpPerson?): Boolean {
        if (p == null) return pinnedKey(o.fromDevice) == o.fromKey
        val e = p.device(B64.decode(o.fromDevice)) ?: return false
        return B64.encode(e.key) == o.fromKey && B64.encode(e.signKey) == o.fromSignKey
    }

    /** Purpose of the personal grant for [deviceId]: per mailbox recipient once the chain names one, else per device. */
    private fun grantPurposeOf(deviceId: String): String =
        targetOfDevice(deviceId)?.takeIf { it.mailbox }?.let { contactPurpose(it.peer.deviceId) } ?: contactPurpose(deviceId)

    /** Recipients of everything to [person]: its mailbox recipient, else (older chains) each of its devices. */
    private fun recipientsOf(person: String): List<Target> {
        personState(person)?.let(::parsedProof)?.let(::mailboxTargetOf)?.let { return listOf(it) }
        return targetsOf(person)
    }

    /** Fills the cache of parsed chains (they verify once when stored). */
    private suspend fun warmProofs() {
        for (s in state.persons) if (s.proof !in parsedProofs) AnmpPersons.verify(B64.decode(s.proof))?.let { cacheProof(s.proof, it) }
    }

    // --- Seen IDs (dedup across relays and restarts) --------------------------------------------

    private val seenLock = Mutex()
    private val seenIds = LinkedHashSet<String>()
    private var seenDirty = false

    private fun loadSeen() {
        platform.store.getString(SEEN_KEY)?.let { runCatching { coreJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }
            ?.let { seenIds.addAll(it) }
    }

    /** `true` if [id] is new. */
    private suspend fun remember(id: String): Boolean = seenLock.withLock {
        if (!seenIds.add(id)) return@withLock false
        while (seenIds.size > MAX_SEEN) seenIds.remove(seenIds.first())
        seenDirty = true
        true
    }

    private suspend fun flushSeen() = seenLock.withLock {
        if (!seenDirty) return@withLock
        platform.store.putString(SEEN_KEY, coreJson.encodeToString(ListSerializer(String.serializer()), seenIds.toList()))
        seenDirty = false
    }

    private suspend fun flushSeenLoop() {
        while (true) {
            delay(SEEN_FLUSH)
            flushSeen()
        }
    }

    // --- Spaces and access ----------------------------------------------------------------------

    private suspend fun ownAccess() = SpaceAccess(me.inbox, me.inboxOwner, null, me.inboxEnvelope, me.inboxOwner)

    private suspend fun circleAccess(keys: CircleKeys) = SpaceAccess(keys.space, keys.owner, null, keys.anmp.envelope, keys.owner)

    /** The best grant for writing into the mailbox [inbox]: a personal one first, then the newest. */
    private fun grantOf(inbox: String): PeerGrant? {
        val now = nowS()
        return state.grants.filter { it.inbox == inbox && it.until > now + 60 }
            .sortedWith(compareByDescending<PeerGrant> { it.personal }.thenByDescending { it.issued })
            .firstOrNull()
    }

    /** Can we write into the personal mailbox of the person (or device) [id] (a grant for it)? */
    fun canWriteTo(id: String): Boolean = targetsOf(personOf(id)).any { grantOf(it.inbox) != null }

    /** Access to the mailbox of [t]. Its envelope key comes from the mailbox space (ADR 0042), for older apps from their X25519 key. */
    private suspend fun peerAccess(t: Target): SpaceAccess? {
        val g = grantOf(t.inbox) ?: return null
        val grant = runCatching { Grant.parse(g.grant) }.getOrNull() ?: return null
        val space = Key.parse(g.inbox)
        val envelope = if (t.legacy) AnmpEnvelope.inbox(t.peer.keyBytes) else AnmpEnvelope.inbox(space.bytes)
        return SpaceAccess(space, me.writer, grant, envelope, null)
    }

    /** Access to a space by name, for pending writes after a restart. */
    private suspend fun accessFor(space: String): SpaceAccess? {
        opened.await()
        if (space == me.inbox.toString()) return ownAccess()
        if (space == store.space.toString()) return store.accessOf()
        for (c in state.circles) {
            for (secret in listOfNotNull(c.secret, c.previousSecret)) {
                val k = CircleKeys.of(secret)
                if (k.space.toString() == space) return circleAccess(k)
                val s = circleStore(secret)
                if (s.space.toString() == space) return s.accessOf()
            }
        }
        for (c in state.channels) {
            if (c.id == space) return channelAccess(c)
            for (i in c.ingress) ingressAccess(i.token)?.takeIf { it.name == space }?.let { return it }
        }
        val g = state.grants.filter { it.inbox == space }.maxByOrNull { it.issued } ?: return null
        return targetOfDevice(g.deviceId)?.let { peerAccess(it) }
    }

    /** Spaces to list: every circle with its current key, the previous key during the grace period, the own mailbox, every channel. */
    private suspend fun listedSpaces(s: CoreState): List<Listed> {
        val now = now()
        val out = mutableListOf<Listed>(Listed.Inbox(ownAccess()))
        for (c in s.circles) {
            if (c.removed && c.graceUntil < now) continue
            val k = CircleKeys.of(c.secret)
            out += Listed.Circle(c.id, k, previous = false, access = circleAccess(k))
            if (!c.removed && c.previousSecret != null && c.graceUntil > now) {
                val p = CircleKeys.of(c.previousSecret)
                out += Listed.Circle(c.id, p, previous = true, access = circleAccess(p))
            }
        }
        for (c in s.channels) {
            val a = channelAccess(c) ?: continue
            out += Listed.Channel(c.id, B64.decode(c.readKey), a)
        }
        return out
    }

    private suspend fun currentCircle(circleId: String): Pair<CircleState, CircleKeys>? {
        val c = circle(circleId)?.takeIf { !it.removed } ?: return null
        return c to CircleKeys.of(c.secret)
    }

    /** A relay answers again or a new network list came: pending writes try at once (ADR 0090). */
    private suspend fun kickWritesOnChange() {
        var down = pool.down.value
        merge(pool.down.map { now -> (down - now).isNotEmpty().also { down = now } }.filter { it }, net.list.drop(1).map { true })
            .collect { transport.kick() }
    }

    // --- Fetching ------------------------------------------------------------------------------

    /** Raised by [wakeUp]: every poll starts again, also one waiting in a backoff. */
    private val pollEpoch = MutableStateFlow(0)

    /**
     * Fetch again at once, e.g. after an Android alarm woke the phone: timers stand still while the phone sleeps,
     * so a poll that waits out a backoff after a broken connection would wait until the app is opened. Also when
     * the app comes to the foreground; then the network list is asked for again too (at most every
     * [CoreTimings.listRefreshSoon]).
     */
    fun wakeUp() {
        log.log("wake: fetching again")
        pollEpoch.update { it + 1 }
        net.refreshSoon()
        transport.kick()
    }

    /**
     * The phone has a network again (ADR 0090): fetch and send at once instead of waiting out the delivery plan; the
     * step of the plan stays.
     */
    fun networkChanged() = wakeUp()

    /** Set by [setBackground]: list each space at one relay only. */
    private val background = MutableStateFlow(false)

    /**
     * The app is in the background (`true`) or visible again (`false`). In the background every space is listed at
     * one relay only, the first healthy holder, instead of at all of them: each list is a long poll that comes back
     * at least every [CoreTimings.longPollSeconds], and with every space at every holder the phone's radio never
     * rests. A relay that fails hands over to the next holder. Visible again, all holders are listed, each from its
     * own cursor, so whatever only one holder had comes in then. A list that brought nothing waits for
     * [CoreTimings.backgroundPollInterval] before the next round; switching restarts every list at once.
     */
    fun setBackground(inBackground: Boolean) {
        background.value = inBackground
        if (!inBackground) transport.kick()
    }

    /** Whether the app said it is in the background ([setBackground]), e.g. for its own rarer work there. */
    val inBackground: StateFlow<Boolean> get() = background.asStateFlow()

    /** Relays to list [key] at: all holders, in the background only the first healthy one (all if none is healthy). */
    private fun listTargets(key: String, lean: Boolean): List<Relay> {
        val all = net.holders(key)
        if (!lean) return all
        return net.targets(key).take(1).ifEmpty { all }
    }

    private suspend fun pollAll() {
        opened.await()
        combine(combine(_state, background, streamLive, ::Triple), net.list, pool.down, pollEpoch) { (s, lean, live), _, _, epoch -> Triple(s, lean to live, epoch) }
            .map { (s, mode, epoch) ->
                val (lean, live) = mode
                // In the background with a live stream (ADR 0072) nothing is polled: the stream says where to fetch.
                epoch to if (lean && live) emptyList() else listedSpaces(s).map { l -> l to listTargets(l.access.name, lean) }
            }
            .distinctUntilChanged { (ea, a), (eb, b) -> ea == eb && pollKey(a) == pollKey(b) }
            .collectLatest { (_, selection) ->
                coroutineScope { selection.forEach { (l, targets) -> targets.forEach { r -> launch { poll(l, r) } } } }
            }
    }

    /** What restarts polling: the spaces, their holders and, for a channel, its read key (new key after a reset or removal, ADR 0062 §11). */
    private fun pollKey(selection: List<Pair<Listed, List<Relay>>>) =
        selection.map { (l, rs) -> Triple(l.access.name, (l as? Listed.Channel)?.let { B64.encode(it.readKey) }, rs.map { it.node }) }

    private fun cursorKey(space: String, relay: Relay, prefix: String = "core.cursor") = "$prefix.$space.${relay.node}"

    // --- Stream from a home relay (ADR 0072, ANDP §10.2, §18) ------------------------------------

    /** Whether the stream to the home relay is up; then the background does not poll. */
    private val streamLive = MutableStateFlow(false)

    private val _streamStats = MutableStateFlow(StreamStats())

    /** Counters of the stream for the app's log and checks (ADR 0072 §6). */
    val streamStats: StateFlow<StreamStats> = _streamStats.asStateFlow()

    /** Stream key and tag key of this device, loaded in [open] if present, made on first use. */
    private var streamKey: SigningKey? = null
    private var streamTagKey: ByteArray? = null

    /** Lists made because of the stream go one at a time, so that a catch-up and an event do not race on cursors. */
    private val streamListLock = Mutex()

    private suspend fun loadStreamKeys(create: Boolean): Boolean {
        if (streamKey != null) return true
        var seed = platform.secrets.get(STREAM_SEED)
        var tag = platform.secrets.get(STREAM_TAG_KEY)
        if (seed == null || tag == null) {
            if (!create) return false
            seed = AnmpKeys.randomKey()
            tag = AnmpKeys.randomKey()
            platform.secrets.put(STREAM_SEED, seed)
            platform.secrets.put(STREAM_TAG_KEY, tag)
        }
        streamTagKey = tag
        streamKey = SigningKey.fromSeed(seed)
        return true
    }

    /** Tag of [space] for this device (ANDP §10.2): 16 bytes from the tag key, says nothing about the space. */
    private suspend fun streamTag(space: String): String = B64.encode(AnmpKeys.hkdf(streamTagKey!!, "anmp/v1/stream-tag/$space").copyOf(Andp.STREAM_TAG_BYTES))

    private suspend fun streamLoop() {
        opened.await()
        background.collectLatest { bg ->
            streamLive.value = false
            if (bg) runStream()
        }
    }

    /**
     * Relays that offer the stream now, in the order of the placement over the stream key (ANDP §6), healthy first.
     * `/andp/v1/info` at most [CoreTimings.streamRecheck] old: `stream.clients` is the switch of the network and
     * shall reach the apps within that time, not only after the usual hour of the info cache.
     */
    private suspend fun streamCandidates(token: String): List<Pair<Relay, Int>> {
        val order = Placements.order(token, net.active)
        return (order.filter { pool.healthy(it.node) } + order.filterNot { pool.healthy(it.node) }).mapNotNull { r ->
            val info = pool.info(r, maxAgeMillis = timings.streamRecheck.inWholeMilliseconds) ?: return@mapNotNull null
            val st = info.stream
            if (!info.hasExtension(Andp.EXTENSION_STREAM) || st == null || !st.clients) null else r to st.ping.coerceIn(10, 3600)
        }
    }

    /** One home relay after another; returns only when cancelled (back in the foreground). */
    private suspend fun runStream() = coroutineScope {
        loadStreamKeys(create = true)
        val key = streamKey!!
        val token = bytesToHex(key.key.bytes)
        var home: Relay? = null
        var offLogged = false
        var failures = 0
        var connectedBefore = false
        var registered: Pair<String, Set<String>>? = null
        var registeredAt = 0L
        var tags: Map<String, Listed> = emptyMap()
        // Safety net: the Android alarm (wakeUp) lists everything once while the stream is up.
        launch {
            pollEpoch.drop(1).collect { if (streamLive.value) streamCatchUp(missed = true) }
        }
        while (true) {
            val candidates = streamCandidates(token)
            if (candidates.isEmpty()) {
                streamLive.value = false
                if (home != null || !offLogged) log.log("stream: no relay offers it now (stream.clients off), polling")
                offLogged = true
                home = null
                updateStreamStats { it.copy(home = "") }
                withTimeoutOrNull(timings.streamRecheck) { merge(net.list.drop(1), pollEpoch.drop(1)).first() }
                continue
            }
            // Stay with the home relay until it failed twice in a row; then the next one in the order.
            val current = home
            val pick = when {
                current == null -> candidates.first()
                failures < 2 -> candidates.firstOrNull { it.first.node == current.node } ?: candidates.first()
                else -> candidates.getOrNull(candidates.indexOfFirst { it.first.node == current.node } + 1) ?: candidates.first()
            }
            if (current != null && pick.first.node != current.node) {
                failures = 0
                updateStreamStats { it.copy(homeSwitches = it.homeSwitches + 1) }
                log.log("stream: home ${current.site} -> ${pick.first.site}")
            }
            val (relay, ping) = pick
            home = relay
            val client = pool.client(relay)
            if (client == null) {
                failures++
                delay(streamRetryDelay())
                continue
            }
            // watches with target relay at the holders of every space (again after a change or a day).
            val spaces = listedSpaces(state).filterNot { it is Listed.Circle && it.previous }
            val want = relay.node.toString() to spaces.map { it.access.name }.toSet()
            if (registered != want || now() - registeredAt > timings.watchRefresh.inWholeMilliseconds) {
                tags = spaces.associateBy { streamTag(it.access.name) }
                for ((tag, l) in tags) {
                    val target = WatchTarget(Andp.WATCH_TYPE_RELAY, token, node = relay.node.toString(), tag = tag)
                    for (r in net.holders(l.access.name)) {
                        val info = pool.info(r) ?: continue
                        if (!info.hasExtension(Andp.EXTENSION_STREAM)) continue
                        runCatching { pool.client(r)?.watch(l.access.cred, WatchRequest(l.access.space, target)) }
                    }
                }
                registered = want
                registeredAt = now()
            }
            val started = now()
            updateStreamStats { it.copy(home = relay.site) }
            val reason = try {
                client.stream(Credentials(key), (2L * ping + STREAM_IDLE_SLACK_SECONDS) * 1000) { ev ->
                    when (ev.type) {
                        Andp.STREAM_EVENT_PING -> if (!streamLive.value) {
                            streamLive.value = true
                            updateStreamStats { it.copy(connects = it.connects + 1) }
                            log.log("stream: live at ${relay.site}")
                            launch { streamCatchUp(missed = connectedBefore) }
                            connectedBefore = true
                        }
                        Andp.STREAM_EVENT_NEW -> {
                            val n = runCatching { coreJson.decodeFromString(StreamNew.serializer(), ev.data) }.getOrNull()
                            val hit = n?.tags.orEmpty().mapNotNull { tags[it] }
                            if (hit.isNotEmpty()) launch { streamList(hit) }
                        }
                        Andp.STREAM_EVENT_BYE -> throw StreamBye()
                    }
                }
                "closed"
            } catch (e: CancellationException) {
                throw e
            } catch (e: StreamBye) {
                "bye"
            } catch (e: AndpHttpException) {
                "rejected_${e.status}"
            } catch (e: Exception) {
                if ((e::class.simpleName ?: "").contains("Timeout")) "ping_timeout" else "network"
            } finally {
                streamLive.value = false
            }
            val lasted = now() - started
            failures = if (lasted < STREAM_SHORT_MILLIS) failures + 1 else 0
            updateStreamStats { st -> st.copy(drops = st.drops + (reason to (st.drops[reason] ?: 0) + 1), lastSeconds = (st.lastSeconds + lasted / 1000).takeLast(20)) }
            log.log("stream: ended at ${relay.site} after ${lasted / 1000} s ($reason)")
            delay(streamRetryDelay())
        }
    }

    private class StreamBye : Exception()

    private fun streamRetryDelay() = timings.streamRetryMin + (timings.streamRetryMax - timings.streamRetryMin) * kotlin.random.Random.nextDouble()

    private fun updateStreamStats(f: (StreamStats) -> StreamStats) = _streamStats.update(f)

    /** All spaces once at one holder each, without waiting; [missed] counts what came in as missed notifications. */
    private suspend fun streamCatchUp(missed: Boolean) {
        val found = streamList(listedSpaces(state))
        updateStreamStats { it.copy(catchUps = it.catchUps + 1, missed = it.missed + if (missed && found > 0) 1 else 0) }
        if (missed && found > 0) log.log("stream: catch-up found $found new without notification")
    }

    /** Lists [spaces] once at their first healthy holder (ANDP §5.6 without wait); returns the number of new objects. */
    private suspend fun streamList(spaces: List<Listed>): Int = streamListLock.withLock {
        var found = 0
        for (l in spaces) {
            val relay = listTargets(l.access.name, lean = true).firstOrNull() ?: continue
            found += runCatching { listOnce(l, relay) }.getOrElse { e ->
                if (e is CancellationException) throw e
                pool.failed(relay.node)
                0
            }
        }
        found
    }

    private suspend fun listOnce(l: Listed, relay: Relay): Int {
        val key = cursorKey(l.access.name, relay)
        var after = platform.store.getString(key).orEmpty()
        val client = pool.client(relay) ?: return 0
        var found = 0
        while (true) {
            val page = client.list(l.access.cred, l.access.space, ListOptions(after, 0))
            pool.ok(relay.node)
            for (o in page.objects) handleListed(l, relay, o)
            found += page.objects.size
            if (page.next.isEmpty() || page.next == after) return found.also { caughtUp(l) }
            after = page.next
            platform.store.putString(key, after)
            if (page.objects.isEmpty()) return found
        }
    }

    private suspend fun poll(l: Listed, relay: Relay) {
        val key = cursorKey(l.access.name, relay)
        var after = platform.store.getString(key).orEmpty()
        var backoff = timings.backoffMin
        var answered = false
        while (true) {
            try {
                val client = pool.client(relay) ?: return
                val started = now()
                val page = client.list(l.access.cred, l.access.space, ListOptions(after, if (answered) timings.longPollSeconds else 0))
                if (!answered) pool.info(relay)
                answered = true
                pool.ok(relay.node)
                backoff = timings.backoffMin
                for (o in page.objects) handleListed(l, relay, o)
                if (page.next.isNotEmpty() && page.next != after) {
                    after = page.next
                    platform.store.putString(key, after)
                }
                if (page.objects.isEmpty()) {
                    caughtUp(l)
                    val took = now() - started
                    if (background.value) {
                        val left = timings.backgroundPollInterval.inWholeMilliseconds - took
                        if (left > 0) delay(left)
                    } else if (took < timings.minPollInterval.inWholeMilliseconds) {
                        delay(timings.minPollInterval)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AndpHttpException) {
                pool.ok(relay.node)
                log.log("list ${l.access.name.take(8)} at ${relay.site}: ${e.status}")
                delay(backoff)
                backoff = minOf(backoff * 2, timings.backoffMax)
            } catch (e: Exception) {
                // Only the first failure in a row, so that a relay that stays away does not fill the log.
                if (backoff == timings.backoffMin) log.log("list ${l.access.name.take(8)} at ${relay.site}: ${e::class.simpleName}")
                answered = false
                pool.failed(relay.node)
                delay(backoff)
                backoff = minOf(backoff * 2, timings.backoffMax)
            }
        }
    }

    private suspend fun entryBytes(l: Listed, relay: Relay, o: ListedObject): ByteArray? =
        o.inline() ?: runCatching { pool.client(relay)?.get(l.access.cred, l.access.space, o.id)?.data }.getOrNull()

    private suspend fun handleListed(l: Listed, relay: Relay, o: ListedObject) {
        val id = o.id.toString()

        val meta = o.meta(l.access.space)
        if (meta.revokes != null) return
        val known = seenLock.withLock { id in seenIds }
        if (known) {
            track(l, relay, meta, null)
            return
        }
        val data = entryBytes(l, relay, o) ?: return
        track(l, relay, meta, data)
        if (!remember(id)) return
        if (l is Listed.Channel) {
            runCatching { acceptAlert(l, meta, data, keep = true) }
                .onFailure { e -> if (e is CancellationException) throw e else log.log("alert ${id.take(8)}: ${e.message}") }
                .getOrNull()?.let { emit(it) }
            return
        }
        val packet = openEnvelope(l, data) ?: return
        val opened = Packets.open(me, packet, l.scope, (l as? Listed.Circle)?.keys, ::peerByTag) ?: return
        if (l is Listed.Inbox) sawViaInternet(opened)
        runCatching { dispatch(l, id, opened, meta.grant?.hash?.toString()) }.onFailure { e ->
            if (e is CancellationException) throw e
            log.log("entry ${id.take(8)}: ${e.message}")
        }
    }

    /** Opens the envelope of an entry; the own mailbox also with the key from before ADR 0042. */
    private suspend fun openEnvelope(l: Listed, data: ByteArray): ByteArray? =
        l.access.envelope?.open(data, l.access.name) ?: if (l is Listed.Inbox) me.legacyInboxEnvelope.open(data, l.access.name) else null

    private suspend fun dispatch(l: Listed, objectId: String, o: Opened, viaGrant: String? = null) {
        if (o.fromDevice == me.deviceId) return
        // A receipt entry only carries the refs of a message's contents for the quota (ADR 0088 §2).
        if (o.kind == AnmpKind.HOLD) return
        val pinned = pinnedKey(o.fromDevice)
        if (pinned != null && pinned != o.fromKey) {
            log.log("entry from ${o.fromDevice}: other key than pinned, dropped")
            return
        }
        val person = acceptSender(o) ?: return
        if (revokedHere || erasing) return
        when (l) {
            is Listed.Inbox -> if (person == ownPersonId && !o.forwarded) onOwnDevice(l, objectId, o) else {
                // The grant in a channel link lets anyone with the link write to us, but only to announce themselves (ADR 0062 §11).
                if (viaGrant != null && o.kind != AnmpKind.SUBSCRIBE && o.kind != AnmpKind.UNSUBSCRIBE &&
                    state.issued.any { it.hash == viaGrant && it.purpose.startsWith(JOIN_PURPOSE) }
                ) return
                onInbox(l, objectId, o, pinned != null, person, viaGrant)
            }
            is Listed.Circle -> onCircle(l, objectId, o, person)
            is Listed.Channel -> Unit
        }
    }

    /** Copies, read states and snapshots from an own other device (ADR 0042 §4). */
    private suspend fun onOwnDevice(l: Listed.Inbox, objectId: String, o: Opened) {
        if (!o.toUs) return
        val c = o.content
        val peer = c.peer?.takeIf { it.size == Anmp.DEVICE_BYTES }?.let(B64::encode)
        val conversation = c.circle?.let { Conversation.Circle(it) } ?: peer?.let { Conversation.Direct(it) }
        // Copy of a packet the conversation partner sent (ADR 0107): it counts as from its author, a device of that person.
        val author = c.author?.takeIf { it.size == Anmp.DEVICE_BYTES && peer != null }?.let(B64::encode)
            ?.takeIf { a -> personOf(a).let { it == peer || it == a } }
        if (c.author != null && author == null) return
        val from = author ?: o.fromDevice
        when (o.kind) {
            AnmpKind.DIRECT -> if (peer != null) {
                val space = targetsOf(peer).firstOrNull()?.inbox?.takeIf { it.isNotEmpty() } ?: l.access.name
                // The card of an introduction: the partner's for us, or our own to the partner (ADR 0116), shown like on the
                // device that received or sent it.
                val intro = if (author != null) introOf(o, by = peer, to = ownPersonId) else introOf(o, by = ownPersonId, to = peer)
                messageEvent(Conversation.Direct(peer), objectId, space, o, person = if (author != null) peer else ownPersonId, fromDevice = from, intro = intro)
                    ?.let { emitReceived(it, l.access.space, objectId) }
            }
            AnmpKind.REACT -> if (conversation != null) reactionEvent(conversation, o, fromDevice = from)?.let { emit(it) }
            AnmpKind.DELETE -> if (conversation != null) {
                val d = deleteEvent(conversation, from, o.content)
                if (conversation is Conversation.Direct) emitDeleted(d) else d?.let { emit(it) }
            }
            AnmpKind.READ -> if (conversation != null) {
                c.ids?.filter { it.size == Anmp.ID_BYTES }?.map(B64::encode)?.takeIf { it.isNotEmpty() }?.let { emit(CoreEvent.ReadElsewhere(conversation, it)) }
            }
            // Circle history for this device, from an own other device that is a member (ADR 0014).
            AnmpKind.HISTORY -> c.history?.takeIf { it.parts.size <= MAX_HISTORY_PARTS }?.let { onHistory(it, o.fromDevice) }
            AnmpKind.SYNC -> {
                c.keys?.let { k -> applyKeys(k, c.person) }
                c.sync?.let { applySync(it) }
                c.ownData?.takeIf { it.topic == PROFILE_TOPIC }?.let { cs.launch { runCatching { profileSync() } } }
                c.ownData?.takeIf { it.topic != PROFILE_TOPIC && it.parts.size <= MAX_HISTORY_PARTS }?.let { d -> receiveOwnData(d, o.fromDevice) }
            }
            else -> Unit
        }
    }

    private suspend fun onCircle(l: Listed.Circle, objectId: String, o: Opened, person: String) {
        val circle = circle(l.circleId) ?: return
        val c = o.content
        if (o.kind == AnmpKind.LEFT) return onLeft(circle, o.fromDevice, c.name.orEmpty())
        // Packets with a recipient in a circle space are only key changes for us.
        if (o.toUs && o.kind != AnmpKind.REKEY && o.kind != AnmpKind.REMOVED) return
        // The device the last key change removed can still write into the previous space during the grace period
        // (e.g. an announcement it queued before it learned of the removal); it is no member any more. A poll started
        // before the key change still has the old space as current, so compare with the circle's current space.
        val oldSpace = l.previous || CircleKeys.of(circle.secret).space != l.keys.space
        if (oldSpace && o.fromDevice == circle.epochRemoved && o.kind != AnmpKind.REKEY && o.kind != AnmpKind.REMOVED) return
        val isMemberKind = o.kind == AnmpKind.MEMBER
        val image = c.image?.takeIf { isMemberKind }?.let(::imageRef)
        val (ok, isNew) = upsertMember(
            MemberState(
                l.circleId, o.fromDevice, c.name.orEmpty(), o.fromKey, o.fromSignKey, c.node ?: 0,
                image = image, imageTs = if (isMemberKind && image != null) c.imageTs ?: 0 else 0,
                inbox = c.inbox?.takeIf { it.size == 32 }?.let(B64::encode), personId = person.takeIf { it != o.fromDevice },
            ),
        )
        if (!ok) return
        if (isMemberKind) c.grant?.let { acceptGrant(o.fromDevice, c.inbox, it, l.circleId) }
        if (isNew && !circle.removed) scheduleReannounce(circle.id, o.fromDevice)
        val conversation = Conversation.Circle(circle.id)
        when (o.kind) {
            AnmpKind.MEMBER -> onHistoryNeed(circle, o.fromDevice, c.needsHistory == true, l.previous)
            AnmpKind.CIRCLE -> messageEvent(conversation, objectId, l.access.name, o, person = person)?.let {
                emit(it)
                if (person != ownPersonId) it.mid?.let { mid -> confirmReceipt(o.fromDevice, mid, AnmpArrived.INTERNET) }
            }
            AnmpKind.REACT -> reactionEvent(conversation, o)?.let { emit(it) }
            AnmpKind.DELETE -> deleteEvent(conversation, o.fromDevice, c)?.let { emit(it) }
            AnmpKind.META -> c.meta?.let { onMeta(circle, it) }
            AnmpKind.ALIVE -> c.alive?.let { onAlive(circle, o.fromDevice, person, c.id, c.ts, it) }
            AnmpKind.CARE -> c.care?.let { onCare(circle, person, c.ts, it) }
            AnmpKind.WANT -> c.wantHash?.takeIf { it.size == 16 }?.let { onWant(conversation, o.fromDevice, B64.encode(it)) }
            AnmpKind.HAVE -> haveEvent(c)?.let { emit(it) }
            AnmpKind.REKEY -> c.rekey?.takeIf { o.toUs && it.key.size == 32 }?.let { r ->
                onKeyChange(circle, o.fromDevice, o.fromKey, c.name.orEmpty(), r.epoch, r.ts.takeIf { it > 0 } ?: (c.ts * 1000), B64.encode(r.key), r.removed?.let(B64::encode), r.why, l.previous)
            }
            AnmpKind.REMOVED -> c.removed?.takeIf { o.toUs }?.let { r ->
                onKeyChange(circle, o.fromDevice, o.fromKey, c.name.orEmpty(), r.epoch, r.ts.takeIf { it > 0 } ?: (c.ts * 1000), null, null, null, l.previous)
            }
            else -> Unit
        }
    }

    private suspend fun onInbox(l: Listed.Inbox, objectId: String, o: Opened, known: Boolean, person: String, viaGrant: String? = null) {
        if (!o.toUs) return
        reachableAgain(person)
        val c = o.content
        if (o.forwarded) {
            // Radio packet put into our mailbox by an app at a shared radio (ADR 0022); opened like over radio.
            when (o.kind) {
                AnmpKind.DIRECT -> messageEvent(Conversation.Direct(person), objectId, l.access.name, o, person = person, intro = introOf(o, person))
                    ?.let { emitReceived(it, l.access.space, objectId) }
                AnmpKind.REACT -> reactionEvent(Conversation.Direct(person), o)?.let { emit(it) }
                AnmpKind.DELETE -> emitDeleted(deleteEvent(Conversation.Direct(person), o.fromDevice, c))
                AnmpKind.READ -> c.ids?.filter { it.size == Anmp.ID_BYTES }?.map(B64::encode)?.takeIf { it.isNotEmpty() }
                    ?.let { emit(CoreEvent.Receipt(o.fromDevice, it, read = true)) }
                else -> Unit
            }
            return
        }
        // Someone new becomes a contact only once we agree (ADR 0116 §7): a request, unless we asked it (introduction or
        // our own request) or it wrote through a published link (support, §7.6).
        if ((o.kind == AnmpKind.DIRECT || o.kind == AnmpKind.PROFILE) && isStranger(person, o.fromDevice) &&
            sentRequestOf(person, o.fromDevice) == null && !viaPublicLink(viaGrant)
        ) {
            onStranger(l, objectId, o, person)
            return
        }
        if (o.kind == AnmpKind.DIRECT && o.content.introReply != null) {
            onIntroReply(o, person)
            return
        }
        // Whoever writes us directly and is not a stranger (see above) is or becomes a contact; receipts and the like do not.
        if (o.kind == AnmpKind.DIRECT || o.kind == AnmpKind.PROFILE) {
            endSentRequest(person, o.fromDevice)
            val (ok, isNew) = upsertMember(
                MemberState(
                    MemberState.CONTACTS, o.fromDevice, c.name.orEmpty(), o.fromKey, o.fromSignKey, c.node ?: 0,
                    // The newest known chain names the mailbox: a late packet from before a key change must not bring
                    // the old one back (ADR 0042 §6).
                    inbox = personState(person)?.let(::parsedProof)?.list?.inbox?.let(B64::encode) ?: c.inbox?.takeIf { it.size == 32 }?.let(B64::encode),
                    personId = person.takeIf { it != o.fromDevice },
                ),
            )
            if (!ok) return
            c.grant?.let { acceptGrant(o.fromDevice, c.inbox, it, null) }
            if (isNew || !hasIssued(grantPurposeOf(o.fromDevice))) cs.launch { sendProfileTo(o.fromDevice) }
            adoptDevicesOf(person)
        }
        val conversation = Conversation.Direct(person)
        when (o.kind) {
            AnmpKind.DIRECT -> messageEvent(conversation, objectId, l.access.name, o, person = person, intro = introOf(o, person))?.let {
                emitReceived(it, l.access.space, objectId)
                it.mid?.let { mid -> confirmReceipt(o.fromDevice, mid, AnmpArrived.INTERNET) }
            }
            AnmpKind.RECEIPT, AnmpKind.READ -> if (known) {
                c.ids?.filter { it.size == Anmp.ID_BYTES }?.map(B64::encode)?.takeIf { it.isNotEmpty() }
                    ?.let {
                        if (o.kind == AnmpKind.RECEIPT) onAliveReceipt(person, it)
                        emit(CoreEvent.Receipt(o.fromDevice, it, read = o.kind == AnmpKind.READ, arrived = c.arrived ?: 0))
                    }
            }
            AnmpKind.REACT -> if (known) reactionEvent(conversation, o)?.let { emit(it) }
            AnmpKind.DELETE -> if (known) emitDeleted(deleteEvent(conversation, o.fromDevice, c))
            AnmpKind.PROFILE -> {
                val image = c.image?.let(::imageRef)
                val ts = c.imageTs ?: 0
                update { s -> s.copy(members = s.members.map { if (it.deviceId == o.fromDevice && it.imageTs < ts) it.copy(image = image, imageTs = ts) else it }) }
                emit(CoreEvent.Profile(o.fromDevice, image, ts))
            }
            AnmpKind.WANT -> c.wantHash?.takeIf { it.size == 16 }?.let { onWant(conversation, o.fromDevice, B64.encode(it)) }
            AnmpKind.HAVE -> haveEvent(c)?.let { emit(it) }
            AnmpKind.HISTORY -> c.history?.takeIf { it.parts.size <= MAX_HISTORY_PARTS }?.let { onHistory(it, o.fromDevice) }
            AnmpKind.REKEY -> c.rekey?.takeIf { it.key.size == 32 && it.circle != null }?.let { r ->
                val circle = circle(r.circle!!) ?: return
                onKeyChange(circle, o.fromDevice, o.fromKey, c.name.orEmpty(), r.epoch, r.ts.takeIf { it > 0 } ?: (c.ts * 1000), B64.encode(r.key), r.removed?.let(B64::encode), r.why, viaPrevious = r.epoch == circle.epoch)
            }
            AnmpKind.REMOVED -> c.removed?.takeIf { it.circle != null }?.let { r ->
                val circle = circle(r.circle!!) ?: return
                onKeyChange(circle, o.fromDevice, o.fromKey, c.name.orEmpty(), r.epoch, r.ts.takeIf { it > 0 } ?: (c.ts * 1000), null, null, null, viaPrevious = r.epoch == circle.epoch)
            }
            AnmpKind.SUBSCRIBE -> c.channel?.let { onSubscribe(it, o) }
            AnmpKind.UNSUBSCRIBE -> c.channel?.let { onUnsubscribe(it, o) }
            AnmpKind.CHANNEL_KEY -> c.channel?.let { onChannelKey(it) }
            else -> Unit
        }
    }

    private fun messageEvent(
        conversation: Conversation, objectId: String, space: String, o: Opened, history: Boolean = false, person: String = personOf(o.fromDevice),
        fromDevice: String = o.fromDevice,
        intro: Intro? = null,
        fromRequest: Boolean = false,
    ): CoreEvent.Message? {
        val c = o.content
        val text = c.text.orEmpty()
        val attachments = c.attachments.orEmpty().take(MAX_ATTACHMENTS).filter { it.id.size == Anmp.BLOB_ID_BYTES && it.key.size == 32 && it.size > 0 }
        if (text.isEmpty() && attachments.isEmpty()) return null
        // Only a preview of a link that was really sent (ADR 0069); its picture like an attachment.
        val link = c.link?.takeIf { it.url.startsWith("https://") && text.contains(it.url) }?.let { l ->
            AnmpLink(
                l.url, l.title?.take(LINK_TITLE_MAX), l.description?.take(LINK_DESCRIPTION_MAX), l.site?.take(LINK_TITLE_MAX),
                l.image?.takeIf { it.id.size == Anmp.BLOB_ID_BYTES && it.key.size == 32 && it.size > 0 && it.type == "jpg" },
            )
        }
        return CoreEvent.Message(
            conversation, objectId, space, c.id?.takeIf { it.size == Anmp.ID_BYTES }?.let(B64::encode), fromDevice, c.name.orEmpty(), c.node ?: 0,
            text, c.ts * 1000, attachments,
            imageHash = c.imageHash?.takeIf { it.size == 16 }?.let(B64::encode), imageTs = c.imageTs ?: 0,
            circleImageHash = c.circleImageHash?.takeIf { it.size == 16 }?.let(B64::encode),
            opened = if (conversation is Conversation.Circle) o.message.opened else null,
            forwarded = o.forwarded, history = history, fromPerson = person, link = link,
            replyTo = replyOf(c)?.first, replyFrom = replyOf(c)?.second, passedOn = c.passedOn == true, intro = intro, fromRequest = fromRequest,
        )
    }

    /** Fields 3 and 4 of an answer to message [replyTo] of the installation with tag [replyFrom] (ADR 0092); `null` if invalid. */
    private fun replyBytes(replyTo: String?, replyFrom: String?): Pair<ByteArray, ByteArray>? {
        if (replyTo == null || replyFrom == null) return null
        val ref = runCatching { B64.decode(replyTo) }.getOrNull()?.takeIf { it.size == Anmp.ID_BYTES } ?: return null
        val from = runCatching { B64.decode(replyFrom) }.getOrNull()?.takeIf { it.size == Anmp.TAG_BYTES } ?: return null
        return ref to from
    }

    /**
     * Withdrawal of a message (ADR 0068): only from a device of the same person as the author ([AnmpContent.refFrom]).
     * Also used by the radio way.
     */
    internal fun deleteEvent(conversation: Conversation, fromDevice: String, c: AnmpContent): CoreEvent.Deleted? {
        val ref = c.ref?.takeIf { it.size == Anmp.ID_BYTES } ?: return null
        val author = c.refFrom?.takeIf { it.size == Anmp.TAG_BYTES } ?: return null
        val authorDevice = if (Anmp.tagOf(B64.decode(me.deviceId)).contentEquals(author)) me.deviceId else peerByTag(author)?.deviceId ?: return null
        if (personOf(authorDevice) != personOf(fromDevice)) return null
        return CoreEvent.Deleted(conversation, fromDevice, B64.encode(author), B64.encode(ref), c.ts * 1000)
    }

    private fun reactionEvent(conversation: Conversation, o: Opened, objectId: String = "", fromDevice: String = o.fromDevice): CoreEvent.Reaction? {
        val c = o.content
        val ref = c.ref?.takeIf { it.size == Anmp.ID_BYTES } ?: return null
        val author = c.refFrom?.takeIf { it.size == Anmp.TAG_BYTES } ?: return null
        val emoji = c.emoji ?: return null
        if (emoji.length > 16) return null
        return CoreEvent.Reaction(conversation, fromDevice, B64.encode(author), B64.encode(ref), emoji, c.ts * 1000, objectId)
    }

    private fun haveEvent(c: AnmpContent): CoreEvent.Have? {
        val hash = c.wantHash?.takeIf { it.size == 16 } ?: return null
        val ref = c.haveRef?.takeIf { it.hash?.contentEquals(hash) == true }?.let(::imageRef) ?: return null
        return CoreEvent.Have(B64.encode(hash), ref)
    }

    private fun imageRef(i: AnmpImage): ImageRef? {
        if (i.id.size != Anmp.BLOB_ID_BYTES || i.key.size != 32 || i.from.size != 32) return null
        return ImageRef(B64.encode(i.id), B64.encode(i.key), B64.encode(i.from), i.hash?.takeIf { it.size == 16 }?.let(B64::encode))
    }

    private fun anmpImage(r: ImageRef) = AnmpImage(B64.decode(r.id), B64.decode(r.key), B64.decode(r.space), r.hash?.let(B64::decode))

    // --- Grants ---------------------------------------------------------------------------------

    /** Purpose of the grant in a published contact link; not a prefix of another purpose (see [revoke]). */
    private val PUBLIC_LINK = "public-link"

    /** Tombstone prefix of a declined request (ADR 0116), so the own other devices drop it too. */
    private val DECLINED = "declined:"

    /** Tombstone prefix of a withdrawn published link (grant hash), so the own other devices stop taking it too. */
    private val REVOKED_LINK = "link-revoked:"


    private fun contactPurpose(deviceId: String) = "contact:$deviceId"

    private fun circlePurpose(circleId: String, epoch: Int) = "circle:$circleId:$epoch"

    private fun hasIssued(purpose: String) = state.issued.any { it.purpose == purpose && !it.revoked && it.until > nowS() }

    /** A grant for our mailbox for [purpose]; reused while more than [CoreTimings.grantRenewBefore] is left. */
    private suspend fun grantFor(purpose: String, grantee: Key?, lifetime: Duration = timings.grantLifetime, renewBefore: Duration = timings.grantRenewBefore): Grant {
        val now = nowS()
        state.issued.lastOrNull { it.purpose == purpose && !it.revoked && it.until - now > renewBefore.inWholeSeconds }
            ?.let { g -> runCatching { Grant.parse(g.grant) }.getOrNull()?.let { return it } }
        val g = Grant.issue(me.inboxOwner, GrantBody(me.inbox, Rights.WRITE, grantee, null, now + lifetime.inWholeSeconds, now - GRANT_BACKDATE))
        update { s -> s.copy(issued = s.issued + IssuedGrant(purpose, g.toString(), g.hash.toString(), g.body.until)) }
        return g
    }

    /** Takes a write grant for the mailbox [inbox] of [deviceId] after checking it. */
    private suspend fun acceptGrant(deviceId: String, inbox: ByteArray?, raw: ByteArray, circleId: String?) {
        if (inbox == null || inbox.size != 32) return
        val g = runCatching { Grant.decode(raw) }.getOrNull() ?: return
        val now = nowS()
        val b = g.body
        if (b.space != Key(inbox) || !b.rights.has(Rights.WRITE) || b.scope != null || b.until <= now) return
        if (b.grantee != null && b.grantee != me.writer.key) return
        if (runCatching { g.verifySig() }.isFailure) return
        val wire = g.toString()
        if (state.grants.any { it.grant == wire }) return
        val pg = PeerGrant(deviceId, b.space.toString(), wire, b.until, b.issued, personal = b.grantee != null, circleId = circleId)
        update { s ->
            // A newer circle grant replaces the older one of the same circle; personal grants are kept until they expire.
            val rest = s.grants.filterNot { it.deviceId == deviceId && it.until <= now || (circleId != null && it.deviceId == deviceId && it.circleId == circleId && it.issued <= pg.issued) }
            s.copy(grants = rest + pg)
        }
    }

    /** Revokes our grants with a purpose starting with [prefix] (revocation entry in our mailbox, ANDP §4.3). */
    private suspend fun revoke(prefix: String) {
        val due = state.issued.filter { it.purpose.startsWith(prefix) && !it.revoked && it.until > nowS() }
        if (due.isEmpty()) return
        update { s -> s.copy(issued = s.issued.map { if (it in due) it.copy(revoked = true) else it }) }
        for (g in due) {
            val hash = app.anynear.andp.Hash.parse(g.hash)
            transport.submit(me.inbox, Meta.revocationData(hash), until = 0, revokes = hash.toString(), known = ownAccess())
        }
    }

    // --- Content of outgoing packets --------------------------------------------------------------

    private fun content(
        id: ByteArray? = null,
        text: String? = null,
        ref: ByteArray? = null,
        refFrom: ByteArray? = null,
        emoji: String? = null,
        ids: List<ByteArray>? = null,
        attachments: List<AnmpAttachment>? = null,
        image: AnmpImage? = null,
        imageTs: Long? = null,
        imageHash: ByteArray? = null,
        circleImageHash: ByteArray? = null,
        meta: AnmpMeta? = null,
        wantHash: ByteArray? = null,
        haveRef: AnmpImage? = null,
        needsHistory: Boolean? = null,
        history: AnmpHistory? = null,
        rekey: AnmpRekey? = null,
        removed: AnmpRemoved? = null,
        inbox: Boolean = false,
        grant: Grant? = null,
        ts: Long = nowS(),
        withChain: Boolean = false,
        peer: String? = null,
        circle: String? = null,
        sync: AnmpSync? = null,
        keys: AnmpSyncKeys? = null,
        ownData: AnmpOwnData? = null,
        arrived: Int? = null,
        channel: AnmpChannelRef? = null,
        link: AnmpLink? = null,
        alive: AnmpAlive? = null,
        care: AnmpCare? = null,
        passedOn: Boolean = false,
        ask: Boolean? = null,
        intro: AnmpIntro? = null,
        introReply: AnmpIntroReply? = null,
    ): AnmpContent {
        val p = state.profile
        return AnmpContent(
            id = id, ts = ts, text = text, ref = ref, refFrom = refFrom, emoji = emoji, ids = ids,
            name = p.name, key = me.device.publicKey, signKey = me.device.signKey, node = p.node.takeIf { it != 0L },
            attachments = attachments?.takeIf { it.isNotEmpty() }, image = image, imageTs = imageTs, imageHash = imageHash,
            circleImageHash = circleImageHash, meta = meta, wantHash = wantHash, haveRef = haveRef, needsHistory = needsHistory,
            history = history, rekey = rekey, removed = removed,
            inbox = if (inbox || grant != null) me.inbox.bytes else null, grant = grant?.encode(),
            person = own?.proof?.takeIf { withChain }, peer = peer?.let(B64::decode), personRef = own?.ref, circle = circle, sync = sync, keys = keys, ownData = ownData,
            arrived = arrived, channel = channel, link = link, alive = alive, care = care, passedOn = true.takeIf { passedOn }, ask = ask, intro = intro, introReply = introReply,
        )
    }

    private suspend fun lifetimeUntil(lifetime: Duration = timings.entryLifetime) = untilFor(now(), lifetime, pool.maxUntil())

    /** Lifetime of an entry of [kind]: messages and what belongs to them without expiry (ADR 0088), the rest 30 days. */
    private fun lifetimeOf(kind: AnmpKind): Duration = if (kind in PERMANENT_KINDS) timings.messageLifetime else timings.entryLifetime

    /** `until` for [lifetime]; [Duration.ZERO] means no expiry (`0`). */
    private suspend fun untilOf(lifetime: Duration): Long = if (lifetime == Duration.ZERO) 0L else lifetimeUntil(lifetime)

    /** Expiry of the contents of an entry with [until]: their own lifetime when the entry has none (ADR 0088). */
    private suspend fun contentUntilOf(until: Long): Long = if (until == 0L) untilOf(timings.contentLifetime) else until

    /**
     * The receipt entry (kind `hold`, ADR 0088 §2) for an entry without expiry that has contents: it carries their refs
     * and expiry, so that they count on the space's quota only while they live. `null` when not needed.
     */
    private suspend fun holdFor(until: Long, contents: List<Pair<ID, ByteArray>>, seal: suspend (AnmpContent) -> ByteArray): ByteArray? =
        if (until == 0L && contents.isNotEmpty()) seal(content(id = Anmp.newMessageId())) else null

    /** Writes a packet into a circle space. */
    private suspend fun postCircle(
        circleId: String,
        kind: AnmpKind,
        content: AnmpContent,
        to: Peer? = null,
        keys: CircleKeys? = null,
        contents: List<Pair<ID, ByteArray>> = emptyList(),
        ref: String = "",
        notify: Boolean = false,
        lifetime: Duration? = null,
    ): ByteArray? {
        val k = keys ?: currentCircle(circleId)?.second ?: return null
        val encoded = Packets.encode(me, kind, content, circleId, k, to)
        val entry = k.anmp.envelope.seal(encoded.packet, k.space.toString())
        val until = untilOf(lifetime ?: lifetimeOf(kind))
        val hold = holdFor(until, contents) { c -> k.anmp.envelope.seal(Packets.encode(me, AnmpKind.HOLD, c, circleId, k, to).packet, k.space.toString()) }
        // Remember the own circle message so that deleting (ADR 0068) can remove entry, receipt and attachments from the relays.
        if (kind == AnmpKind.CIRCLE && ref.isNotEmpty()) rememberSent(ref, k.space, ID.of(entry), contents.map { it.first }, hold?.let(ID::of))
        transport.submit(k.space, entry, until, contents, contentUntilOf(until), ref, notify, known = circleAccess(k), hold = hold)
        return encoded.opened
    }

    /** Writes a packet for one device into the personal mailbox of its person; `false` without a grant for it. */
    private suspend fun postInbox(
        deviceId: String,
        kind: AnmpKind,
        content: AnmpContent,
        contents: List<Pair<ID, ByteArray>> = emptyList(),
        ref: String = "",
        notify: Boolean = false,
        lifetime: Duration? = null,
        contentSpace: Key? = null,
    ): Boolean {
        val t = targetOfDevice(deviceId) ?: return false
        return postTo(t, kind, content, contents, ref, notify, lifetime, contentSpace)
    }

    /**
     * Writes a packet for every device of [person] (ADR 0042 §4); contents only once, with the first entry. `false`
     * if it reached no device. With [notify] only one entry per mailbox wakes ([wakingTargets]): every device watches
     * the whole mailbox, so a wake-up per device would ring each phone once for every other device of the person.
     */
    private suspend fun postPerson(
        person: String,
        kind: AnmpKind,
        content: AnmpContent,
        contents: List<Pair<ID, ByteArray>> = emptyList(),
        ref: String = "",
        notify: Boolean = false,
        lifetime: Duration? = null,
    ): Boolean {
        var any = false
        val targets = recipientsOf(person)
        val waking = wakingTargets(targets)
        for (t in targets) {
            if (postTo(t, kind, content, if (any) emptyList() else contents, ref, notify && t in waking, lifetime)) any = true
        }
        return any
    }

    /**
     * The one target per mailbox whose entry carries the wake-up: the last, so the entries for the other devices are
     * already on their way (the extension waits a little for its own, see `WakeTexts` in the app).
     */
    private fun wakingTargets(targets: List<Target>): Set<Target> = targets.groupBy { it.inbox }.values.map { it.last() }.toSet()

    /** Writes a packet for each own other device into the own mailbox (copies, read state, snapshot). */
    private suspend fun postOwn(kind: AnmpKind, content: AnmpContent, lifetime: Duration? = null) {
        if (revokedHere) return
        // One copy for the own mailbox recipient (ADR 0107 §2): every own device reads it, also one added later.
        if (config.legacyDirect) ownOthers().forEach { postOwnTo(it, kind, content, lifetime) } else postOwnTo(ownMailbox, kind, content, lifetime)
    }

    private suspend fun postOwnTo(
        peer: Peer,
        kind: AnmpKind,
        content: AnmpContent,
        lifetime: Duration? = null,
        contents: List<Pair<ID, ByteArray>> = emptyList(),
    ) {
        val a = ownAccess()
        val encoded = Packets.encode(me, kind, content, Anmp.DIRECT_SCOPE, null, peer)
        val until = untilOf(lifetime ?: lifetimeOf(kind))
        val hold = holdFor(until, contents) { c -> a.seal(Packets.encode(me, AnmpKind.HOLD, c, Anmp.DIRECT_SCOPE, null, peer).packet) }
        transport.submit(a.space, a.seal(encoded.packet), until, contents, contentUntilOf(until), known = a, hold = hold)
    }

    private suspend fun postTo(
        t: Target,
        kind: AnmpKind,
        content: AnmpContent,
        contents: List<Pair<ID, ByteArray>> = emptyList(),
        ref: String = "",
        notify: Boolean = false,
        lifetime: Duration? = null,
        contentSpace: Key? = null,
    ): Boolean {
        if (revokedHere) return false
        val a = peerAccess(t) ?: return false.also { log.log("no grant for the mailbox of ${t.peer.deviceId}") }
        val encoded = Packets.encode(me, kind, content, Anmp.DIRECT_SCOPE, null, t.peer)
        val entry = a.seal(encoded.packet)
        val until = untilOf(lifetime ?: lifetimeOf(kind))
        val hold = if (contentSpace != null) null else holdFor(until, contents) { c -> a.seal(Packets.encode(me, AnmpKind.HOLD, c, Anmp.DIRECT_SCOPE, null, t.peer).packet) }
        transport.submit(a.space, entry, until, contents, contentUntilOf(until), ref, notify, contentSpace = contentSpace, known = a,
            knownContent = if (contentSpace == me.inbox) ownAccess() else null, hold = hold)
        return true
    }

    // --- Profile and announcements --------------------------------------------------------------

    /**
     * Own name and radio node, sent with every packet over the internet. [renamed]: the person changed the name here, so
     * it goes to the own other devices (ADR 0050); otherwise a newer name from the profile space still wins.
     */
    suspend fun setProfile(name: String, node: Long = 0, renamed: Boolean = false): Unit = onCore {
        val n = name.trim().take(AnmpLinks.MAX_NAME)
        val bump = renamed && n.isNotBlank()
        update { s -> s.copy(profile = s.profile.copy(name = n, node = node, nameTs = if (bump) now() else s.profile.nameTs)) }
        if (bump) {
            profileChanged()
            cs.launch { onCore { reannounceChannels() } }
        }
    }

    /** Own picture (`null` = generated one). Goes to all circles (member) and to contacts without a common circle (profile). */
    suspend fun setOwnImage(plaintext: ByteArray?): Unit = onCore {
        opened.await()
        val hash = plaintext?.let { B64.encode(plainHash(it)) }
        if (hash == state.profile.imageHash) return@onCore
        if (plaintext == null) {
            platform.blobs.delete(OWN_IMAGE)
            update { s -> s.copy(profile = s.profile.copy(imageHash = null, imageTs = now())) }
        } else {
            platform.blobs.write(OWN_IMAGE, plaintext)
            update { s -> s.copy(profile = s.profile.copy(imageHash = B64.encode(plainHash(plaintext)), imageTs = now())) }
        }
        sendProfileToContacts()
        profileChanged()
    }

    /** Own picture to contacts without a common circle (in circles it goes with the announcement). */
    private suspend fun sendProfileToContacts() {
        val inCircles = state.members.filter { it.circleId != MemberState.CONTACTS }.map { it.person }.toSet()
        val ts = state.profile.imageTs
        state.members.filter { it.circleId == MemberState.CONTACTS && it.person !in inCircles }.forEach { sendProfileTo(it.deviceId) }
        update { s -> s.copy(profile = s.profile.copy(imageSentTs = ts)) }
    }

    /**
     * Seals [plaintext] for [space] and returns the reference plus the bytes to put as content. The sealed copy is
     * cached per space and hash so that announcing again re-puts the same object (same ID, later expiry wins).
     */
    private suspend fun uploadFor(space: Key, plaintext: ByteArray, hash: String): Pair<ImageRef, Pair<ID, ByteArray>> {
        val name = "core/img/$space/$hash"
        state.uploads.firstOrNull { it.space == space.toString() && it.hash == hash }?.let { u ->
            platform.blobs.read(name)?.let { bytes -> return u.ref to (ID.parse(u.ref.id) to bytes) }
        }
        val key = AnmpBlob.newKey()
        val sealed = AnmpBlob.seal(key, plaintext)
        platform.blobs.write(name, sealed.bytes)
        val ref = ImageRef(sealed.idText, B64.encode(key), space.toString(), hash)
        update { s -> s.copy(uploads = s.uploads.filterNot { it.space == ref.space && it.hash == hash } + UploadedImage(hash, ref.space, ref)) }
        return ref to (ID(sealed.id) to sealed.bytes)
    }

    private suspend fun ownImageFor(space: Key): Pair<ImageRef, Pair<ID, ByteArray>>? {
        val hash = state.profile.imageHash ?: return null
        val plain = platform.blobs.read(OWN_IMAGE) ?: return null
        return uploadFor(space, plain, hash)
    }

    /** Announces this device in a circle (member): name, keys, picture, node, history need and our circle grant. */
    suspend fun announce(circleId: String): Unit = onCore {
        opened.await()
        val (circle, keys) = currentCircle(circleId) ?: return@onCore
        val grant = grantFor(circlePurpose(circleId, circle.epoch), null)
        val own = ownImageFor(keys.space)
        val needsHistory = circle.historyPending && circleId !in state.historyOffered
        val p = state.profile
        postCircle(
            circleId, AnmpKind.MEMBER,
            content(
                image = own?.first?.let(::anmpImage), imageTs = p.imageTs.takeIf { it > 0 }, imageHash = p.imageHash?.let(B64::decode),
                needsHistory = needsHistory.takeIf { it }, grant = grant, withChain = true,
            ),
            keys = keys, contents = listOfNotNull(own?.second),
        )
    }

    /** Announces in every circle we are in. */
    suspend fun announceAll(): Unit = onCore { state.circles.filterNot { it.removed }.forEach { announce(it.id) } }

    /** At start and on every change of name, node, picture or circle key: announce (as today). */
    private suspend fun announceOnChange() {
        _state.map { s -> listOf(s.circles.filterNot { it.removed }.map { it.id to it.secret }, s.profile.copy(nameTs = 0, settings = emptyList(), imageSentTs = 0)) }
            .distinctUntilChanged()
            .collect { if (state.profile.name.isNotBlank()) announceAll() }
    }

    private val reannounceJobs = mutableMapOf<String, Job>()
    private val lastMetaAt = mutableMapOf<String, Long>()

    /** New member: wait, then announce again; whoever is first by rank also sends name and picture of the circle. */
    private fun scheduleReannounce(circleId: String, newDevice: String) {
        val since = now()
        runDuty(circleId, "meta/$newDevice", done = { (lastMetaAt[circleId] ?: 0) >= since }) {
            val c = circle(circleId) ?: return@runDuty
            if (c.nameTs > 0 || c.imageTs > 0) publishMeta(circleId)
        }
        if (reannounceJobs[circleId]?.isActive == true) return
        reannounceJobs[circleId] = cs.launch {
            delay(timings.reannounceDelay)
            if (state.profile.name.isNotBlank()) announce(circleId)
        }
    }

    private fun runDuty(circleId: String, occasion: String, minDelay: Duration = Duration.ZERO, done: () -> Boolean, action: suspend () -> Unit) {
        cs.launch {
            val rank = duty.rank(occasion, me.deviceId, membersOf(circleId).map { it.deviceId })
            delay(maxOf(minDelay, duty.delayFor(rank)))
            val c = circle(circleId)
            if (c == null || c.removed || done()) return@launch
            action()
        }
    }

    // --- Circles --------------------------------------------------------------------------------

    /** Founds a circle with a new secret. */
    suspend fun createCircle(name: String): CircleState  = onCore {
        opened.await()
        val id = randomId()
        val c = CircleState(id = id, name = name.trim().take(AnmpLinks.MAX_NAME), secret = B64.encode(AnmpKeys.randomKey()), joinedAt = now())
        // A new circle has no history to load (ADR 0107 §8): read through from the start, so no loading notice.
        update { s -> s.copy(circles = s.circles + c, circlesRead = s.circlesRead + id) }
        emit(CoreEvent.CircleUpdated(c))
        return@onCore c
    }

    /** Invitation link of a circle; [radio]: opaque radio contact of ours for the invited. */
    fun inviteLink(circleId: String, radio: ByteArray? = null): String? {
        val c = circle(circleId)?.takeIf { !it.removed } ?: return null
        return AnmpLinks.inviteLink(config.linkHost, Invite(config.networkId.toString(), c.id, c.name, c.secret, c.epoch, c.epochTs, c.epochBy, radio, state.profile.name))
    }

    fun parseInvite(text: String): Invite? = linkHosts.firstNotNullOfOrNull { AnmpLinks.parseInvite(text, it) }

    /** Reads a pairing link of a new own device (ADR 0042 §5), also inside text; [acceptPairing] checks it again. */
    suspend fun parsePair(text: String): PairOffer? = linkHosts.firstNotNullOfOrNull { AnmpLinks.parsePair(text, it) }

    /**
     * Reads a login link (`/l#…`, ADR 0122), also inside text; `null` if none, broken or from an issuer not in
     * [AnmpCoreConfig.loginIssuers].
     */
    fun parseLoginLink(text: String): LoginLink? =
        linkHosts.firstNotNullOfOrNull { AnmpLogin.parse(text, it) }?.takeIf { l -> config.loginIssuers.any { it.trimEnd('/') == l.issuer } }

    /** Whether [text] holds a login link at all (also a broken one or of an unknown issuer). */
    fun isLoginLink(text: String): Boolean = linkHosts.any { AnmpLogin.isLink(text, it) }

    private val http: HttpClient by lazy { platform.http ?: HttpClient() }

    /** `GET {issuer}/l/info` (ANIP §10a.3): service, domain and the three numbers. Changes nothing. */
    suspend fun loginInfo(link: LoginLink): LoginInfo {
        if (config.loginIssuers.none { it.trimEnd('/') == link.issuer }) return LoginInfo(LoginResult.INVALID)
        val res = runCatching {
            withTimeout(LOGIN_TIMEOUT) { http.get(link.issuer + "/l/info") { parameter("r", link.req) }.let { it.status.value to it.bodyAsText() } }
        }.getOrElse { if (it is CancellationException && it !is TimeoutCancellationException) throw it else return LoginInfo(LoginResult.OFFLINE) }
        val (code, body) = res
        if (code == 429) return LoginInfo(LoginResult.TOO_MANY)
        if (code == 400) return LoginInfo(LoginResult.EXPIRED)
        if (code != 200) return LoginInfo(LoginResult.OFFLINE)
        val w = runCatching { coreJson.decodeFromString(LoginInfoWire.serializer(), body) }.getOrNull()
            ?.takeIf { it.client.isNotEmpty() && it.name.isNotEmpty() && it.domain.isNotEmpty() && it.choices.size == 3 } ?: return LoginInfo(LoginResult.INVALID)
        return LoginInfo(LoginResult.OK, link.issuer, link.req, w.client, w.name.take(80), w.domain.take(253), w.choices.toIntArray(), w.exp)
    }

    /**
     * Confirms a login after the person picked [n] and unlocked the device (ANIP §10a.3): signs with the device key and
     * sends `POST {issuer}/l/confirm` with the own key chain. The app asks for the device lock before calling this.
     */
    suspend fun confirmLogin(info: LoginInfo, n: Int, name: String): LoginResult {
        if (info.status != LoginResult.OK || config.loginIssuers.none { it.trimEnd('/') == info.issuer }) return LoginResult(LoginResult.INVALID)
        val clean = AnmpLogin.cleanName(name) ?: return LoginResult(LoginResult.BAD_NAME)
        val req = runCatching { B64.decode(info.req) }.getOrNull() ?: return LoginResult(LoginResult.INVALID)
        val body = onCore {
            val proof = state.ownProof ?: return@onCore null
            val ts = nowS()
            val sig = me.device.sign(AnmpLogin.confirmBytes(info.issuer, info.client, req, n, clean, ts))
            LoginConfirmWire(info.req, n, clean, ts, proof, me.signKey, B64.encode(sig))
        } ?: return LoginResult(LoginResult.NO_CHAIN)
        val res = runCatching {
            withTimeout(LOGIN_TIMEOUT) {
                http.post(info.issuer + "/l/confirm") {
                    contentType(ContentType.Application.Json)
                    setBody(coreJson.encodeToString(LoginConfirmWire.serializer(), body))
                }.let { it.status.value to it.bodyAsText() }
            }
        }.getOrElse { if (it is CancellationException && it !is TimeoutCancellationException) throw it else return LoginResult(LoginResult.OFFLINE) }
        val (code, text) = res
        val answer = runCatching { coreJson.decodeFromString(LoginAnswerWire.serializer(), text) }.getOrNull()
        return LoginResult(
            when {
                code == 200 && answer?.ok == true -> LoginResult.OK
                code == 200 && answer != null -> LoginResult.WRONG_NUMBER
                code == 400 -> LoginResult.EXPIRED
                code == 403 -> LoginResult.REJECTED
                code == 429 -> LoginResult.TOO_MANY
                else -> LoginResult.OFFLINE
            },
        )
    }

    /** Joins by an invitation link (also inside text). The whole history comes from a member (ADR 0014). */
    suspend fun joinCircle(text: String): JoinResult  = onCore {
        opened.await()
        val invite = parseInvite(text) ?: return@onCore JoinResult.Invalid
        if (invite.networkId != config.networkId.toString()) return@onCore JoinResult.ForeignNetwork
        val known = circle(invite.circleId) ?: state.circles.firstOrNull { it.secret == invite.secret }
        if (known != null && !known.removed && known.secret == invite.secret) return@onCore JoinResult.AlreadyMember(known)
        val c = CircleState(
            id = invite.circleId, name = invite.name, secret = invite.secret, epoch = invite.epoch, epochTs = invite.epochTs, epochBy = invite.epochBy,
            joinedAt = known?.joinedAt?.takeIf { it > 0 } ?: now(), historyPending = true,
        )
        update { s -> s.copy(circles = s.circles.filterNot { it.id == c.id } + c, historyOffered = s.historyOffered - c.id) }
        emit(CoreEvent.CircleUpdated(c))
        return@onCore JoinResult.Joined(c)
    }

    /** Renames a circle; every member may (ADR 0012). */
    suspend fun renameCircle(circleId: String, name: String): Boolean  = onCore {
        val n = name.trim().take(AnmpLinks.MAX_NAME)
        if (n.isEmpty()) return@onCore false
        val c = circle(circleId)?.takeIf { !it.removed } ?: return@onCore false
        if (n == c.name) return@onCore true
        updateCircle(circleId) { it.copy(name = n, nameTs = now(), nameBy = me.deviceId) }
        publishMeta(circleId)
        return@onCore true
    }

    /** Sets (or with `null` removes) the circle picture; it lies in the circle space. */
    suspend fun setCircleImage(circleId: String, plaintext: ByteArray?): Boolean  = onCore {
        val (_, keys) = currentCircle(circleId) ?: return@onCore false
        val ref = plaintext?.let { uploadFor(keys.space, it, B64.encode(plainHash(it))).first }
        updateCircle(circleId) { it.copy(image = ref, imageTs = now(), imageBy = me.deviceId) }
        publishMeta(circleId)
        return@onCore true
    }

    private suspend fun publishMeta(circleId: String) {
        val (c, keys) = currentCircle(circleId) ?: return
        lastMetaAt[circleId] = now()
        val image = c.image
        val upload = image?.hash?.let { h -> state.uploads.firstOrNull { it.space == keys.space.toString() && it.hash == h } }
            ?.let { u -> platform.blobs.read("core/img/${u.space}/${u.hash}")?.let { ID.parse(u.ref.id) to it } }
        val meta = AnmpMeta(c.name, c.nameTs, deviceBytes(c.nameBy), image?.let(::anmpImage), c.imageTs, deviceBytes(c.imageBy))
        postCircle(circleId, AnmpKind.META, content(meta = meta), keys = keys, contents = listOfNotNull(upload))
    }

    private fun deviceBytes(id: String): ByteArray = runCatching { B64.decode(id) }.getOrNull()?.takeIf { it.size == 16 } ?: ByteArray(0)

    private suspend fun onMeta(circle: CircleState, meta: AnmpMeta) {
        val name = meta.name.trim().take(AnmpLinks.MAX_NAME)
        if (name.isEmpty()) return
        lastMetaAt[circle.id] = now()
        val nameBy = B64.encode(meta.nameBy)
        val imageBy = B64.encode(meta.imageBy)
        val image = meta.image?.let(::imageRef)
        val nameChanged = CircleState.isNewer(meta.nameTs, nameBy, circle.nameTs, circle.nameBy)
        val imageChanged = CircleState.isNewer(meta.imageTs, imageBy, circle.imageTs, circle.imageBy)
        if (!nameChanged && !imageChanged) return
        updateCircle(circle.id) {
            var c = it
            if (nameChanged) c = c.copy(name = name, nameTs = meta.nameTs, nameBy = nameBy)
            if (imageChanged) c = c.copy(image = image, imageTs = meta.imageTs, imageBy = imageBy)
            c
        }
        if (nameChanged && name != circle.name && meta.nameTs > circle.joinedAt) emit(CoreEvent.CircleRenamed(circle.id, nameBy, name))
        if (imageChanged && image != circle.image && meta.imageTs > circle.joinedAt) emit(CoreEvent.CircleImageChanged(circle.id, imageBy, image == null))
    }

    private suspend fun updateCircle(circleId: String, change: (CircleState) -> CircleState) {
        update { s -> s.copy(circles = s.circles.map { if (it.id == circleId) change(it) else it }) }
        circle(circleId)?.let { emit(CoreEvent.CircleUpdated(it)) }
    }

    /** Removes a member (any member may, ADR 0012): new key for the others, the removed one reads nothing new. */
    suspend fun removeMember(circleId: String, deviceId: String): Unit = onCore { rotate(circleId, deviceId, WHY_REMOVE) }

    /** Makes every invitation link invalid (new key). */
    suspend fun resetInvites(circleId: String): Unit = onCore { rotate(circleId, null, WHY_RESET) }

    private suspend fun rotate(circleId: String, removing: String?, why: String) {
        val (circle, oldKeys) = currentCircle(circleId) ?: return
        val newSecret = B64.encode(AnmpKeys.randomKey())
        val epoch = circle.epoch + 1
        val ts = now()
        val all = membersOf(circleId)
        val removed = all.firstOrNull { it.deviceId == removing }
        // Revoked devices (own ones after a theft, or of another person) never get the new key and are forgotten here.
        val revoked = all.filter { it.deviceId != removing && isRevoked(it.deviceId) }
        // Everything through the old space before the circle switches.
        all.filter { it.deviceId != removing && it !in revoked }.forEach { m ->
            val r = AnmpRekey(B64.decode(newSecret), epoch, removing?.let(B64::decode), why, circleId, ts)
            sendKeyChange(circleId, oldKeys, m, AnmpKind.REKEY, content(rekey = r))
        }
        removed?.let { m ->
            sendKeyChange(circleId, oldKeys, m, AnmpKind.REMOVED, content(removed = AnmpRemoved(epoch, circleId, ts)))
            forgetMember(circleId, m.deviceId)
        }
        revoked.forEach { forgetMember(circleId, it.deviceId) }
        updateCircle(circleId) {
            it.copy(
                secret = newSecret, epoch = epoch, epochTs = ts, epochBy = me.deviceId, epochRemoved = removing,
                previousSecret = it.secret, graceUntil = ts + timings.keyChangeGrace.inWholeMilliseconds,
            )
        }
        // The removed member keeps the old circle grant: revoke it; the new one goes out with the next announcement.
        revoke("circle:$circleId:")
        when (why) {
            WHY_REMOVE -> removed?.let { emit(CoreEvent.KeyChanged(circleId, CoreEvent.KIND_YOU_REMOVED, me.deviceId, state.profile.name, it.deviceId, it.name, why)) }
            WHY_RESET -> emit(CoreEvent.KeyChanged(circleId, CoreEvent.KIND_YOU_RESET, me.deviceId, state.profile.name, null, null, why))
        }
    }

    /** Key change for one member: through the old circle space and, if we may, its personal mailbox (ADR 0014). */
    private suspend fun sendKeyChange(circleId: String, oldKeys: CircleKeys, m: MemberState, kind: AnmpKind, c: AnmpContent) {
        val peer = Peer(m.deviceId, m.publicKey)
        postCircle(circleId, kind, c, to = peer, keys = oldKeys)
        // In the mailbox the scope is "" (direct); without a grant there it only goes through the old space.
        postInbox(m.deviceId, kind, c)
    }

    private suspend fun onKeyChange(
        circle: CircleState, from: String, fromKey: String, fromName: String, epoch: Int, ts: Long,
        newSecret: String?, removedDevice: String?, why: String?, viaPrevious: Boolean,
    ) {
        val sender = member(circle.id, from)?.takeIf { it.publicKey == fromKey } ?: return
        val who = sender.name.ifBlank { fromName }
        val change = KeyChange(from, epoch, ts, newSecret, removedDevice, viaPrevious)
        when (val outcome = change.apply(circle, now() + timings.keyChangeGrace.inWholeMilliseconds)) {
            KeyChange.Outcome.Ignored -> Unit
            is KeyChange.Outcome.StillRemoved -> updateCircle(circle.id) { outcome.circle }
            is KeyChange.Outcome.Readmitted -> {
                updateCircle(circle.id) { outcome.circle }
                emit(CoreEvent.KeyChanged(circle.id, CoreEvent.KIND_READMITTED, from, who, null, null, why))
            }
            is KeyChange.Outcome.Removed -> {
                updateCircle(circle.id) { outcome.circle }
                revoke("circle:${circle.id}:")
                emit(CoreEvent.KeyChanged(circle.id, CoreEvent.KIND_REMOVED_YOU, from, who, me.deviceId, state.profile.name, why))
            }
            is KeyChange.Outcome.Switched -> {
                updateCircle(circle.id) { outcome.circle }
                revoke("circle:${circle.id}:")
                val removed = removedDevice?.takeIf { it != me.deviceId }?.let { member(circle.id, it) }
                if (removed != null) forgetMember(circle.id, removed.deviceId)
                emit(CoreEvent.KeyChanged(circle.id, CoreEvent.KIND_SWITCHED, from, who, removed?.deviceId, removed?.name, why))
            }
        }
    }

    /** Someone left: a remaining member by rank changes the key (ADR 0014); of two simultaneous changes the earlier wins. */
    private suspend fun onLeft(circle: CircleState, deviceId: String, name: String) {
        val m = member(circle.id, deviceId) ?: return
        forgetMember(circle.id, deviceId)
        emit(CoreEvent.MemberLeft(circle.id, deviceId, m.name.ifBlank { name }))
        if (circle.removed) return
        val epoch = circle.epoch
        runDuty(circle.id, "left/$deviceId", minDelay = timings.leaveRotateDelay, done = { (circle(circle.id)?.epoch ?: Int.MAX_VALUE) > epoch }) {
            rotate(circle.id, deviceId, WHY_LEFT)
        }
    }

    /**
     * Leaves a circle: the others are told, the circle and its members are deleted from this device. Was no one else
     * known in it ([aloneIn]), its store is deleted on the relays too (ADR 0049 §5), in the background and again later
     * if no relay answers now; otherwise nobody could ever delete those files without expiry.
     */
    suspend fun leaveCircle(circleId: String): Unit = onCore {
        val c = circle(circleId) ?: return@onCore
        if (aloneIn(circleId)) {
            val t = now()
            val secrets = listOfNotNull(c.secret, c.previousSecret).filter { s -> state.storeCleanups.none { it.secret == s } }
            update { s -> s.copy(storeCleanups = s.storeCleanups + secrets.map { StoreCleanup(it, t) }) }
            cs.launch { cleanUpStores() }
        }
        if (!c.removed) postCircle(circleId, AnmpKind.LEFT, content())
        revoke("circle:$circleId:")
        dropCircle(circleId)
        addTombstone(circleId)
    }

    /**
     * No one but this person is known in the circle: only the own devices are members. Leaving then also deletes the
     * circle store for good ([leaveCircle]). `false` for a circle we were removed from or do not know.
     */
    fun aloneIn(circleId: String): Boolean {
        val c = circle(circleId)?.takeIf { !it.removed } ?: return false
        val own = _ownDevices.value.map { it.deviceId }.toSet() + me.deviceId
        return membersOf(c.id).all { it.deviceId in own || it.person == ownPersonId }
    }

    private val cleaning = Mutex()

    /**
     * Empties the circle stores of [CoreState.storeCleanups] on the relays; drops each once every file is gone, or
     * after [STORE_CLEANUP_KEEP] (then the files stay, as before this rule).
     */
    private suspend fun cleanUpStores() = cleaning.withLock {
        for (job in state.storeCleanups) {
            val done = runCatching { onCore { clearCircleStore(job.secret) } }
                .onFailure { if (it is CancellationException) throw it; log.log("circle store cleanup: ${it.message}") }
                .getOrDefault(false)
            if (done || now() - job.since > STORE_CLEANUP_KEEP) onCore {
                update { s -> s.copy(storeCleanups = s.storeCleanups.filterNot { it.secret == job.secret }) }
                circleStores.remove(job.secret)
            }
        }
    }

    /** Deletes every file of the circle store of [secret]; `true` once none is left on the relays. */
    private suspend fun clearCircleStore(secret: String): Boolean {
        val s = circleStore(secret)
        val files = s.list() ?: return false
        var ok = true
        for (f in files) if (!s.delete(f.file)) ok = false
        return ok && s.list()?.isEmpty() == true
    }

    private suspend fun dropCircle(circleId: String) {
        update { s -> s.copy(circles = s.circles.filterNot { it.id == circleId }, members = s.members.filterNot { it.circleId == circleId }, historyOffered = s.historyOffered - circleId) }
        emit(CoreEvent.CircleGone(circleId))
    }

    // --- Contacts -------------------------------------------------------------------------------

    /** Own contact link with network ID and a write grant for our mailbox (`*`, [CoreTimings.linkGrantLifetime]). */
    suspend fun ownContactLink(radio: ByteArray? = null): String  = onCore {
        opened.await()
        val g = grantFor("link", null, timings.linkGrantLifetime, timings.linkGrantLifetime / 2)
        val card = ContactCard(config.networkId.toString(), state.profile.name, me.deviceId, me.signKey, me.publicKey, me.inbox.toString(), g.toString(), radio, B64.encode(me.mailbox.publicKey))
        return@onCore AnmpLinks.contactLink(config.linkHost, card)
    }

    /**
     * Contact link to publish (ADR 0084 §6), e.g. built into the apps or put on a website: like [ownContactLink], but
     * its write grant lasts [CoreTimings.publicLinkGrantLifetime] and is renewed after half of it. Withdraw it with
     * [revokePublicContactLink] if it attracts spam; the next call then gives a new one.
     */
    suspend fun publicContactLink(radio: ByteArray? = null): String = onCore {
        opened.await()
        val g = grantFor(PUBLIC_LINK, null, timings.publicLinkGrantLifetime, timings.publicLinkGrantLifetime / 2)
        val card = ContactCard(config.networkId.toString(), state.profile.name, me.deviceId, me.signKey, me.publicKey, me.inbox.toString(), g.toString(), radio, B64.encode(me.mailbox.publicKey))
        return@onCore AnmpLinks.contactLink(config.linkHost, card)
    }

    /** Withdraws the grant of every published contact link (ADR 0084 §6); contacts made through it keep their own grants. */
    suspend fun revokePublicContactLink(): Unit = onCore {
        opened.await()
        // The own other devices know these grants too (sync field 7): a tombstone makes them drop them as well.
        val t = nowS()
        state.issued.filter { it.purpose == PUBLIC_LINK && !it.revoked && it.until > t }.forEach { addTombstone(REVOKED_LINK + it.hash) }
        revoke(PUBLIC_LINK)
    }

    suspend fun parseContact(text: String): ContactCard? = linkHosts.firstNotNullOfOrNull { AnmpLinks.parseContact(text, it) }

    /** Device ID of a contact link in this network, without adding it; `null` if [text] is none or from another network. */
    suspend fun contactDevice(text: String): String? = parseContact(text)?.takeIf { it.networkId == config.networkId.toString() }?.deviceId

    /**
     * Adds a contact from its link (ADR 0004): it becomes a contact, we take its link grant and send a profile with a
     * personal grant for our mailbox so that it can answer.
     */
    suspend fun addContact(text: String): AddContactResult  = onCore {
        opened.await()
        val card = parseContact(text) ?: return@onCore AddContactResult.Invalid
        if (card.networkId != config.networkId.toString()) return@onCore AddContactResult.ForeignNetwork
        if (card.deviceId == me.deviceId) return@onCore AddContactResult.Own
        val pinned = pinnedKey(card.deviceId)
        if (pinned != null && pinned != card.publicKey) return@onCore AddContactResult.KeyMismatch(card.name)
        val known = member(MemberState.CONTACTS, card.deviceId) != null
        upsertMember(MemberState(MemberState.CONTACTS, card.deviceId, card.name, card.publicKey, card.signKey, inbox = card.inbox, mailboxKey = card.mailboxKey))
        acceptGrant(card.deviceId, B64.decode(card.inbox), Grant.parse(card.grant).encode(), null)
        // It decides whether we become its contact (ADR 0116 §7); until it answers, the request is ours to show.
        if (!known) addSentRequest(SentRequest(card.deviceId, card.name, deviceId = card.deviceId, ts = now(), until = now() + REQUEST_LIFETIME.inWholeMilliseconds))
        sendProfileTo(card.deviceId, ask = !known)
        val m = member(MemberState.CONTACTS, card.deviceId)!!
        return@onCore if (known) AddContactResult.AlreadyKnown(m) else AddContactResult.Added(m)
    }

    /** Removes a contact person (all its devices; [id] is a person or device ID) and revokes its personal grants. */
    suspend fun removeContact(id: String): Unit = onCore {
        val person = personOf(id)
        val devices = state.members.filter { it.circleId == MemberState.CONTACTS && it.person == person }.map { it.deviceId }.ifEmpty { listOf(id) }
        // A grant sent to the mailbox recipient (ADR 0084) is issued for it, not for a device: revoke that one too.
        val purposes = (devices.flatMap { listOf(contactPurpose(it), grantPurposeOf(it)) } + recipientsOf(person).map { contactPurpose(it.peer.deviceId) }).toSet()
        devices.forEach { forgetMember(MemberState.CONTACTS, it) }
        purposes.forEach { revoke(it) }
        addTombstone(person)
        reachableAgain(person)
    }

    // --- Contact requests and introductions (ADR 0116) ------------------------------------------------

    /** Persons asking to become a contact, newest first (ADR 0116). */
    suspend fun contactRequests(): List<ContactRequest> = onCore {
        opened.await()
        // Checked again before showing: someone who became a contact meanwhile, or whom the own person asked on another
        // device, is no request (ADR 0116).
        state.requests.filter { isStranger(it.person, it.deviceId) && sentRequestOf(it.person, it.deviceId) == null }.sortedByDescending { it.ts }
    }

    /** Our requests not yet answered (ADR 0116): the app shows „Anfrage gesendet“ for these persons. */
    suspend fun sentContactRequests(): List<SentRequest> = onCore { opened.await(); state.sentRequests }

    /**
     * Agrees to the request of [person] (ADR 0116): it becomes a contact as if we had added its contact link, gets a
     * profile with a personal grant and can write to us. Messages it sent with the request were already reported
     * ([CoreEvent.Message.fromRequest]). `false` if there is no such request (any more).
     */
    suspend fun acceptContactRequest(person: String): Boolean = onCore {
        opened.await()
        val r = state.requests.firstOrNull { it.person == person } ?: return@onCore false
        return@onCore takeRequest(r, answer = true)
    }

    /**
     * Makes the person of [r] a contact, with the grant from its request. [answer]: we agreed and send our profile with
     * a personal grant; otherwise (the own person asked it, on another device) only if no grant for it was given yet.
     */
    private suspend fun takeRequest(r: ContactRequest, answer: Boolean): Boolean {
        update { s -> s.copy(requests = s.requests - r) }
        val (ok, isNew) = upsertMember(
            MemberState(
                MemberState.CONTACTS, r.deviceId, r.name, r.publicKey, r.signKey, r.node, r.image, r.imageTs, r.inbox,
                personId = r.person.takeIf { it != r.deviceId }, mailboxKey = r.mailboxKey,
            ),
        )
        emit(CoreEvent.ContactRequestsChanged())
        if (!ok) return false
        val grant = r.grant?.let { runCatching { B64.decode(it) }.getOrNull() }
        if (grant != null && r.inbox != null) acceptGrant(r.deviceId, B64.decode(r.inbox), grant, null)
        if (answer || isNew || !hasIssued(grantPurposeOf(r.deviceId))) sendProfileTo(r.deviceId)
        adoptDevicesOf(r.person)
        if (!answer) endSentRequest(r.person, r.deviceId)
        return true
    }

    /**
     * Declines the request of [person] silently (ADR 0116 §4): it learns nothing; its messages are for the app to
     * delete. Further requests of the person are not shown again; the own other devices drop it too.
     */
    suspend fun declineContactRequest(person: String): Unit = onCore {
        opened.await()
        update { s -> s.copy(requests = s.requests.filterNot { it.person == person }) }
        addTombstone(DECLINED + person)
        emit(CoreEvent.ContactRequestsChanged())
    }

    /**
     * Introduces our contact [person] to our contact [toPerson] (ADR 0116 §1): a direct message to [toPerson] with a card
     * of [person] (its own name, [image] if given, its person ID; nothing to reach it with) signed by our person key.
     * [text]: stand-in for apps that do not know introductions (e.g. „Kontakt: Ilse“). `null` if one of them is no
     * contact or [toPerson] cannot be written to.
     */
    suspend fun introduce(person: String, toPerson: String, text: String, image: PreparedAttachment? = null): Outgoing? = onCore {
        opened.await()
        val p = personOf(person)
        val to = personOf(toPerson)
        if (p == to || p == ownPersonId || to == ownPersonId) return@onCore null
        val contact = state.members.firstOrNull { it.circleId == MemberState.CONTACTS && it.person == p } ?: return@onCore null
        if (state.members.none { it.circleId == MemberState.CONTACTS && it.person == to }) return@onCore null
        val card = AnmpIntroCard(
            AnmpKeys.randomKey().copyOf(Anmp.DEVICE_BYTES), nowS() + AnmpIntros.LIFETIME_SECONDS, B64.decode(p), contact.name.take(AnmpLinks.MAX_NAME),
            B64.decode(to), B64.decode(ownPersonId),
        )
        val signed = AnmpIntros.sign(me.person, card)
        val intro = AnmpIntro(signed, image?.attachment?.takeIf { it.type == "jpg" })
        return@onCore directTo(to, text, attachments = listOfNotNull(image), intro = intro)
    }

    /**
     * We want the contact of the introduction [intro] (from [CoreEvent.Message.intro], ADR 0116 §1 step 3): asks the
     * introducer to pass our request on. Until the introduced person agrees, it is a [SentRequest]. Result as in
     * [addContact]; [AddContactResult.Invalid] for a card that is broken, expired or not for us.
     */
    suspend fun acceptIntro(intro: Intro): AddContactResult = onCore {
        opened.await()
        val signed = runCatching { B64.decode(intro.card) }.getOrNull() ?: return@onCore AddContactResult.Invalid
        val by = B64.encode(AnmpIntros.read(signed)?.by ?: return@onCore AddContactResult.Invalid)
        val card = verifyIntro(signed, by) ?: return@onCore AddContactResult.Invalid
        val person = B64.encode(card.person)
        if (B64.encode(card.to) != ownPersonId || card.until < nowS()) return@onCore AddContactResult.Invalid
        if (person == ownPersonId) return@onCore AddContactResult.Own
        state.members.firstOrNull { it.circleId == MemberState.CONTACTS && it.person == person }?.let { return@onCore AddContactResult.AlreadyKnown(it) }
        val link = ownContactLinkNow()
        directTo(by, "", introReply = AnmpIntroReply(signed, link), own = false) ?: return@onCore AddContactResult.Invalid
        addSentRequest(SentRequest(person, card.name, introducedBy = by, ts = now(), until = card.until * 1000))
        return@onCore AddContactResult.Requested(card.name)
    }

    /** Card of [signed] if signed by the current person key of [by] (our own or a known chain). */
    private suspend fun verifyIntro(signed: ByteArray, by: String): AnmpIntroCard? {
        val key = if (by == ownPersonId) own?.cert?.person else personState(by)?.let(::parsedProof)?.cert?.person
        return AnmpIntros.verify(signed, key ?: return null)?.takeIf { B64.encode(it.by) == by }
    }

    /**
     * An introduction in a direct message, to show as a card: signed by [by] (who introduces) for [to]. Received: [by] is
     * the sender, [to] we. An own copy of what we sent (ADR 0107): [by] we, [to] the partner. Every way a direct message
     * arrives (mailbox, own copy, forwarded radio packet, request, wake-up) takes it from here, so none shows only the
     * stand-in text.
     */
    private suspend fun introOf(o: Opened, by: String, to: String = ownPersonId): Intro? {
        val i = o.content.intro ?: return null
        val card = verifyIntro(i.card, by) ?: return null
        if (B64.encode(card.to) != to) return null
        val image = i.image?.takeIf { it.type == "jpg" && it.id.size == Anmp.BLOB_ID_BYTES && it.key.size == 32 && it.size > 0 }
        return Intro(B64.encode(card.id), B64.encode(card.person), card.name, by, card.until * 1000, B64.encode(i.card), image)
    }

    /**
     * An answer to an introduction (ADR 0116 §3): to the introducer, the person that wants the contact (we pass it on);
     * to the introduced person, the request passed on by the introducer (it becomes a [ContactRequest]).
     */
    private suspend fun onIntroReply(o: Opened, sender: String) {
        val r = o.content.introReply ?: return
        val raw = AnmpIntros.read(r.card) ?: return
        val by = B64.encode(raw.by)
        val card = verifyIntro(r.card, by) ?: return
        if (card.until < nowS()) return
        val introduced = B64.encode(card.person)
        val to = B64.encode(card.to)
        when {
            // We introduced, and the person we introduced to wants it: pass it on, if the introduced one is still a contact.
            by == ownPersonId && sender == to -> {
                val contact = state.members.firstOrNull { it.circleId == MemberState.CONTACTS && it.person == introduced } ?: return
                directTo(introduced, "", introReply = r, own = false)
                emit(CoreEvent.IntroAccepted(B64.encode(card.id), to, introduced, contact.name))
            }
            // We were introduced, the introducer (a contact of ours) passes on the request of [to].
            introduced == ownPersonId && sender == by -> {
                if (state.members.none { it.circleId == MemberState.CONTACTS && it.person == by }) return
                val link = parseContact(r.contact)?.takeIf { it.networkId == config.networkId.toString() } ?: return
                if (link.deviceId == me.deviceId || !isStranger(to, link.deviceId)) return
                if (state.tombstones.any { it.id == DECLINED + to }) return
                if (state.requests.any { it.introId == B64.encode(card.id) || it.person == to }) return
                val introducer = state.members.firstOrNull { it.circleId == MemberState.CONTACTS && it.person == by }?.name
                val req = ContactRequest(
                    to, link.deviceId, link.name, link.publicKey, link.signKey, link.inbox, link.grant, link.mailboxKey,
                    introducedBy = by, introducedByName = introducer, introId = B64.encode(card.id), ts = now(), until = card.until * 1000,
                )
                update { s -> s.copy(requests = s.requests + req) }
                emit(CoreEvent.ContactRequestsChanged())
            }
        }
    }

    /** No contact, no circle member: someone we do not know yet (ADR 0116). */
    private fun isStranger(person: String, deviceId: String): Boolean {
        if (person == ownPersonId) return false
        val devices = devicesOfPerson(person)
        return state.members.none { it.person == person || it.deviceId == deviceId || it.deviceId in devices }
    }

    /** Device IDs the known chain of [person] lists: a device we knew from its link may belong to it (ADR 0084). */
    private fun devicesOfPerson(person: String): Set<String> =
        personState(person)?.let(::parsedProof)?.deviceIds?.map(B64::encode)?.toSet().orEmpty()

    /** Written with the grant of a published contact link (support): taken as contact at once (ADR 0116 §7.6). */
    private fun viaPublicLink(viaGrant: String?): Boolean = viaGrant != null && state.issued.any { it.hash == viaGrant && it.purpose == PUBLIC_LINK }

    private fun sentRequestOf(person: String, deviceId: String): SentRequest? {
        val devices = devicesOfPerson(person)
        return state.sentRequests.firstOrNull { it.person == person || it.person == deviceId || it.deviceId == deviceId || it.deviceId in devices }
    }

    private suspend fun addSentRequest(r: SentRequest) {
        update { s -> s.copy(sentRequests = s.sentRequests.filterNot { it.person == r.person } + r) }
        emit(CoreEvent.ContactRequestsChanged())
    }

    /** Something of [person] arrived: our request to it is answered. */
    private suspend fun endSentRequest(person: String, deviceId: String) {
        val r = sentRequestOf(person, deviceId) ?: return
        update { s -> s.copy(sentRequests = s.sentRequests - r) }
        emit(CoreEvent.ContactRequestsChanged())
    }

    /**
     * A direct message or profile of someone we do not know (ADR 0116 §7): a request (or its update) instead of a
     * contact. Its messages are reported with [CoreEvent.Message.fromRequest]; no receipt, no grant before we agree.
     */
    private suspend fun onStranger(l: Listed.Inbox, objectId: String, o: Opened, person: String) {
        if (state.tombstones.any { it.id == DECLINED + person }) return
        val c = o.content
        val existing = state.requests.firstOrNull { it.person == person }
        val image = if (o.kind == AnmpKind.PROFILE) c.image?.let(::imageRef) else null
        val t = now()
        val req = ContactRequest(
            person, o.fromDevice, c.name.orEmpty().ifBlank { existing?.name.orEmpty() }.take(AnmpLinks.MAX_NAME), o.fromKey, o.fromSignKey,
            c.inbox?.takeIf { it.size == 32 }?.let(B64::encode) ?: existing?.inbox, betterGrant(c.grant?.let(B64::encode), existing?.grant), existing?.mailboxKey,
            c.node ?: existing?.node ?: 0, image ?: existing?.image, if (image != null) c.imageTs ?: 0 else existing?.imageTs ?: 0,
            ts = existing?.ts ?: t, until = existing?.until ?: (t + REQUEST_LIFETIME.inWholeMilliseconds),
        )
        if (req != existing) {
            update { s -> s.copy(requests = s.requests.filterNot { it.person == person } + req) }
            emit(CoreEvent.ContactRequestsChanged())
        }
        if (o.kind == AnmpKind.DIRECT) {
            messageEvent(Conversation.Direct(person), objectId, l.access.name, o, person = person, intro = introOf(o, person), fromRequest = true)?.let { emitReceived(it, l.access.space, objectId) }
        }
    }

    /**
     * Of two grants from a request, the one to keep: one naming our key outlives one for anyone (a grant for a mailbox
     * recipient goes when the person's mailbox changes); otherwise the newer [fresh] one.
     */
    private fun betterGrant(fresh: String?, kept: String?): String? {
        if (fresh == null || kept == null) return fresh ?: kept
        fun personal(g: String) = runCatching { Grant.decode(B64.decode(g)).body.grantee == me.writer.key }.getOrDefault(false)
        return if (personal(kept) && !personal(fresh)) kept else fresh
    }

    /** Drops requests past their time; a request of ours ends silently (ADR 0116 §4). */
    private suspend fun expireRequests() {
        val t = now()
        val ended = state.sentRequests.filter { it.until in 1..<t }
        if (ended.isEmpty() && state.requests.none { it.until in 1..<t }) return
        update { s -> s.copy(requests = s.requests.filterNot { it.until in 1..<t }, sentRequests = s.sentRequests.filterNot { it.until in 1..<t }) }
        // A contact added through its link that never agreed is no contact any more.
        ended.forEach { r ->
            val d = r.deviceId ?: return@forEach
            // Something of the person arrived on another own device (its chain is known here): it stays a contact.
            if (personState(personOf(d)) != null) return@forEach
            forgetMember(MemberState.CONTACTS, d)
            revoke(contactPurpose(d))
        }
        if (ended.isEmpty()) emit(CoreEvent.ContactRequestsChanged())
        ended.forEach { emit(CoreEvent.ContactRequestsChanged(ended = it.person)) }
    }

    /** [ownContactLink] inside the core lock. */
    private suspend fun ownContactLinkNow(): String {
        val g = grantFor("link", null, timings.linkGrantLifetime, timings.linkGrantLifetime / 2)
        val card = ContactCard(config.networkId.toString(), state.profile.name, me.deviceId, me.signKey, me.publicKey, me.inbox.toString(), g.toString(), null, B64.encode(me.mailbox.publicKey))
        return AnmpLinks.contactLink(config.linkHost, card)
    }

    /**
     * Every holder refused a message for the mailbox [space] with 403 ([Transport]): marks each other person with that
     * mailbox as unreachable (ADR 0046). Never the own person: our own mailbox refusing us is a different fault.
     */
    private suspend fun onMailboxDenied(space: String, grant: String? = null): Boolean {
        // The refused grant is revoked. If the owner gave us a newer one since (e.g. added us again after removing us),
        // drop the revoked one: the newer one is used from now on and the write is worth one more try. An older grant
        // (say from its contact link) does not count: removed stays removed.
        val refused = state.grants.filter { g -> g.inbox == space && grant != null && runCatching { Grant.parse(g.grant).hash.toString() }.getOrNull() == grant }
        val since = refused.maxOfOrNull { it.issued }
        if (since != null && state.grants.any { it.inbox == space && it !in refused && it.issued > since && it.until > nowS() + 60 }) {
            update { s -> s.copy(grants = s.grants.filterNot { it in refused }) }
            log.log("mailbox ${space.take(8)} refuses a revoked grant, trying the newer one")
            return true
        }
        val own = ownPersonId
        val persons = state.members.map { it.person }.distinct()
            .filter { it != own && it !in state.unreachable }
            .filter { p -> targetsOf(p).any { it.inbox == space } || mailboxTargetOf(p)?.inbox == space }
        if (persons.isEmpty()) return false
        val t = now()
        log.log("mailbox ${space.take(8)} refuses us: ${persons.size} person(s) unreachable")
        update { s -> s.copy(unreachable = s.unreachable + persons.associateWith { t }) }
        persons.forEach { emit(CoreEvent.ContactUnreachable(it, t)) }
        return false
    }

    /** Clears the mark of [person] set by [onMailboxDenied]. */
    private suspend fun reachableAgain(person: String) {
        if (person !in state.unreachable) return
        update { s -> s.copy(unreachable = s.unreachable - person) }
        emit(CoreEvent.ContactReachable(person))
    }

    /** Profile with own picture and a personal grant for [deviceId] (kind `profile`). */
    private suspend fun sendProfileTo(deviceId: String, ask: Boolean = false): Boolean {
        val m = anyMember(deviceId) ?: return false
        val t = targetOfDevice(deviceId) ?: return false
        // To the mailbox recipient every device of the person opens it: a grant for any of them, like the link grant.
        val grant = if (t.mailbox) grantFor(contactPurpose(t.peer.deviceId), null) else grantFor(contactPurpose(deviceId), m.signKey?.let { Key.parse(it) })
        val a = peerAccess(t) ?: return false
        val own = ownImageFor(a.space)
        val p = state.profile
        val c = content(image = own?.first?.let(::anmpImage), imageTs = p.imageTs.takeIf { it > 0 }, grant = grant, withChain = true, ask = ask.takeIf { it })
        // While its device list is unknown, also to the mailbox recipient from its link, so that any device of the
        // person sees a request or an answer (ADR 0084, 0116).
        val mailbox = mailboxTargetOf(deviceId)
        val sent = postTo(t, AnmpKind.PROFILE, c, contents = listOfNotNull(own?.second))
        if (mailbox != null) {
            // Whichever device answers: a grant for any of them, like the link grant (ADR 0084).
            val any = content(image = own?.first?.let(::anmpImage), imageTs = p.imageTs.takeIf { it > 0 }, grant = grantFor(contactPurpose(mailbox.peer.deviceId), null), withChain = true, ask = ask.takeIf { it })
            postTo(mailbox, AnmpKind.PROFILE, any, contents = listOfNotNull(own?.second))
        }
        return sent
    }

    // --- Messages -------------------------------------------------------------------------------

    /**
     * Seals an attachment (ADR 0011) with a random key; the ciphertext waits in the [BlobStore] until it is sent.
     * [type]: `jpg`, `pdf`, `m4a`, `file` (any other file, with [name]). Fails if it is larger than the relays' object limit (no chunks in version v1).
     */
    suspend fun prepareAttachment(plaintext: ByteArray, type: String, name: String? = null, width: Int = 0, height: Int = 0, durationMillis: Long = 0, transcribed: Boolean = false): PreparedAttachment  = onCore {
        val key = AnmpBlob.newKey()
        val sealed = AnmpBlob.seal(key, plaintext)
        pool.checkObjectSize(sealed.bytes.size.toLong())
        val blobName = "core/att/${sealed.idText}"
        platform.blobs.write(blobName, sealed.bytes)
        val a = AnmpAttachment(sealed.id, key, type, name, plaintext.size.toLong(), width, height, plainHash(plaintext), durationMillis, transcribed)
        return@onCore PreparedAttachment(a, blobName)
    }

    private suspend fun attachmentContents(list: List<PreparedAttachment>): List<Pair<ID, ByteArray>> =
        list.map { p -> ID(p.attachment.id) to (platform.blobs.read(p.sealedName) ?: error("attachment ciphertext missing")) }

    private suspend fun dropPrepared(list: List<PreparedAttachment>) = list.forEach { platform.blobs.delete(it.sealedName) }

    /**
     * Circle message to everyone in the circle. Contents (attachments) first, then the entry; [CoreEvent.Stored]
     * with [Outgoing.mid] as ref tells when it lies at the first relay ("gesendet").
     *
     * [mid]: message ID chosen by the app (b64u, 8 bytes), so that the same message carries the same ID on every
     * path (radio, internet, ANMP "Receipt"); `null` or invalid: a new one. [timestampMillis]: sender time of the
     * message (content field 1), `null`: now.
     */
    suspend fun sendCircle(
        circleId: String,
        text: String,
        attachments: List<PreparedAttachment> = emptyList(),
        circleImageHash: String? = null,
        mid: String? = null,
        timestampMillis: Long? = null,
        link: PreparedLink? = null,
        /** Answer to the message with this ID (b64u) of the installation with tag [replyFrom] (ADR 0092). */
        replyTo: String? = null,
        replyFrom: String? = null,
        /** The message passes on one the sender got („Weiterleiten“, content field 38, ADR 0118). */
        passedOn: Boolean = false,
    ): Outgoing?  = onCore {
        opened.await()
        val (_, keys) = currentCircle(circleId) ?: return@onCore null
        val reply = replyBytes(replyTo, replyFrom)
        val mid = messageIdOf(mid)
        val midText = B64.encode(mid)
        val p = state.profile
        val opened = postCircle(
            circleId, AnmpKind.CIRCLE,
            content(
                id = mid, text = text, attachments = attachments.map { it.attachment },
                imageHash = p.imageHash?.let(B64::decode), imageTs = p.imageTs.takeIf { p.imageHash != null }, circleImageHash = circleImageHash?.let(B64::decode),
                ts = timestampMillis?.let { it / 1000 } ?: nowS(), link = link?.let(::wireLink),
                ref = reply?.first, refFrom = reply?.second, passedOn = passedOn,
            ),
            keys = keys, contents = attachmentContents(attachments + listOfNotNull(link?.image)), ref = midText, notify = true,
        ) ?: return@onCore null
        dropPrepared(attachments + listOfNotNull(link?.image))
        return@onCore Outgoing(midText, opened)
    }

    /**
     * Direct message to the person [id] (person or device ID): one entry per device of the person in its mailbox,
     * end-to-end per device pair, attachments uploaded once; a copy for each own other device (ADR 0042 §4). `null`
     * without a grant for its mailbox. [mid] and [timestampMillis] as in [sendCircle].
     */
    suspend fun sendDirect(
        id: String,
        text: String,
        attachments: List<PreparedAttachment> = emptyList(),
        mid: String? = null,
        timestampMillis: Long? = null,
        link: PreparedLink? = null,
        /** As in [sendCircle] (ADR 0092). */
        replyTo: String? = null,
        replyFrom: String? = null,
        /** As in [sendCircle] (ADR 0118). */
        passedOn: Boolean = false,
    ): Outgoing?  = onCore {
        opened.await()
        directTo(id, text, attachments, mid, timestampMillis, link, replyTo, replyFrom, passedOn = passedOn)
    }

    /** Body of [sendDirect]; [intro] and [introReply] for introductions (ADR 0116), not shown as text by older apps. */
    private suspend fun directTo(
        id: String,
        text: String,
        attachments: List<PreparedAttachment> = emptyList(),
        mid: String? = null,
        timestampMillis: Long? = null,
        link: PreparedLink? = null,
        replyTo: String? = null,
        replyFrom: String? = null,
        intro: AnmpIntro? = null,
        introReply: AnmpIntroReply? = null,
        own: Boolean = true,
        passedOn: Boolean = false,
    ): Outgoing? {
        val reply = replyBytes(replyTo, replyFrom)
        val person = personOf(id)
        val targets = recipientsOf(person)
        if (targets.isEmpty()) return null
        val mid = messageIdOf(mid)
        val midText = B64.encode(mid)
        val p = state.profile
        val ts = timestampMillis?.let { it / 1000 } ?: nowS()
        val withImage = attachments + listOfNotNull(link?.image)
        val contents = attachmentContents(withImage)
        val wire = link?.let(::wireLink)
        var any = false
        // A new contact known only from its link: also to its mailbox, so that every device of it opens it (ADR 0084).
        val mailbox = mailboxTargetOf(person)
        val waking = wakingTargets(targets)
        for (t in targets + listOfNotNull(mailbox)) {
            val grant = if (t === mailbox || t.mailbox) {
                grantFor(contactPurpose(t.peer.deviceId), null)
            } else {
                anyMember(t.peer.deviceId)?.signKey?.let { grantFor(contactPurpose(t.peer.deviceId), Key.parse(it)) }
            }
            val c = content(
                id = mid, text = text, attachments = attachments.map { it.attachment }, imageHash = p.imageHash?.let(B64::decode),
                imageTs = p.imageTs.takeIf { p.imageHash != null }, inbox = true, grant = grant, ts = ts, withChain = true, link = wire,
                ref = reply?.first, refFrom = reply?.second, intro = intro, introReply = introReply, passedOn = passedOn,
            )
            if (postTo(t, AnmpKind.DIRECT, c, if (any) emptyList() else contents, ref = midText, notify = t === mailbox || t in waking)) any = true
        }
        if (!any) return null
        if (own) postOwn(AnmpKind.DIRECT, content(id = mid, text = text, attachments = attachments.map { it.attachment }, ts = ts, peer = person, link = wire, ref = reply?.first, refFrom = reply?.second, intro = intro, passedOn = passedOn))
        dropPrepared(withImage)
        return Outgoing(midText, null)
    }

    /**
     * Reaction [emoji] (`""` withdraws it) on message [targetMid] by the installation with tag [targetTag] (ADR 0018).
     * Wakes only for a direct reaction on a message of the other person: in a circle the holders cannot tell whose
     * message it is and would wake every member.
     */
    suspend fun react(conversation: Conversation, targetTag: String, targetMid: String, emoji: String): Boolean  = onCore {
        opened.await()
        val c = content(ref = B64.decode(targetMid), refFrom = B64.decode(targetTag), emoji = emoji)
        return@onCore when (conversation) {
            is Conversation.Circle -> postCircle(conversation.circleId, AnmpKind.REACT, c) != null
            is Conversation.Direct -> {
                val person = personOf(conversation.personId)
                val theirs = emoji.isNotEmpty() && peerByTag(B64.decode(targetTag))?.let { personOf(it.deviceId) == person } == true
                postPerson(person, AnmpKind.REACT, c, notify = theirs).also { ok ->
                    if (ok) postOwn(AnmpKind.REACT, content(ref = B64.decode(targetMid), refFrom = B64.decode(targetTag), emoji = emoji, peer = person))
                }
            }
        }
    }

    /**
     * Withdraws the own message [targetMid] (installation tag [targetTag], an own device) for everyone in [conversation]
     * (ADR 0068). Like [react], without waking anyone. The receivers check that the author is the same person.
     */
    suspend fun deleteMessage(conversation: Conversation, targetTag: String, targetMid: String): Boolean = onCore {
        opened.await()
        val c = content(ref = B64.decode(targetMid), refFrom = B64.decode(targetTag))
        return@onCore when (conversation) {
            is Conversation.Circle -> (postCircle(conversation.circleId, AnmpKind.DELETE, c) != null).also { ok ->
                if (ok) dropSentFromRelays(conversation.circleId, targetMid)
            }
            is Conversation.Direct -> {
                val person = personOf(conversation.personId)
                postPerson(person, AnmpKind.DELETE, c, notify = false).also { ok ->
                    if (ok) postOwn(AnmpKind.DELETE, content(ref = B64.decode(targetMid), refFrom = B64.decode(targetTag), peer = person))
                }
            }
        }
    }

    /**
     * Own circle messages that were sent (message ID → space, entry, contents), at most [SENT_MAX] and
     * [SENT_DAYS] days, on this device only. In the circle space every member is owner and may delete.
     */
    private suspend fun rememberSent(mid: String, space: Key, entry: ID, contents: List<ID>, hold: ID? = null) {
        val line = listOf(mid, nowS().toString(), space.toString(), entry.toString(), contents.joinToString(","), hold?.toString().orEmpty()).joinToString(" ")
        val cutoff = nowS() - SENT_DAYS * 86_400L
        val kept = sentLines().filter { (it.split(' ').getOrNull(1)?.toLongOrNull() ?: 0) >= cutoff }
        platform.store.putString(SENT_KEY, (kept + line).takeLast(SENT_MAX).joinToString("\n"))
    }

    private suspend fun sentLines(): List<String> = platform.store.getString(SENT_KEY)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    /**
     * After deleting an own message in the circle (ADR 0068): delete entry and attachments of the message on the
     * relays, as owner of the circle space. Only while the circle still has the same key; otherwise they expire.
     */
    private suspend fun dropSentFromRelays(circleId: String, mid: String) {
        val line = sentLines().lastOrNull { it.startsWith("$mid ") } ?: return
        val parts = line.split(' ')
        val keys = currentCircle(circleId)?.second ?: return
        if (parts.getOrNull(2) != keys.space.toString()) return
        val cred = circleAccess(keys).cred
        val entry = parts.getOrNull(3)?.let { runCatching { ID.parse(it) }.getOrNull() } ?: return
        val contents = parts.getOrNull(4).orEmpty().split(',').filter { it.isNotEmpty() }.mapNotNull { runCatching { ID.parse(it) }.getOrNull() }
        val hold = parts.getOrNull(5)?.takeIf { it.isNotEmpty() }?.let { runCatching { ID.parse(it) }.getOrNull() }
        cs.launch {
            runCatching { transport.delete(cred, keys.space, entry, entry = true) }
            hold?.let { runCatching { transport.delete(cred, keys.space, it, entry = true) } }
            contents.forEach { id -> runCatching { transport.delete(cred, keys.space, id, entry = false) } }
        }
        platform.store.putString(SENT_KEY, sentLines().filterNot { it.startsWith("$mid ") }.joinToString("\n"))
    }

    /**
     * Direct messages received in the own mailbox (message ID → entry), at most [RECEIVED_MAX] and [RECEIVED_DAYS]
     * days, on this device only. Messages live there without expiry (ADR 0088); the sender may not delete in our
     * mailbox, so after a valid `delete` we, as owner, remove the entry (ADR 0088 §3).
     */
    private suspend fun rememberReceived(mid: String, space: Key, entry: String) {
        if (space != me.inbox) return
        val cutoff = nowS() - RECEIVED_DAYS * 86_400L
        val kept = receivedLines().filter { (it.split(' ').getOrNull(1)?.toLongOrNull() ?: 0) >= cutoff && !it.startsWith("$mid ") }
        platform.store.putString(RECEIVED_KEY, (kept + "$mid ${nowS()} $entry").takeLast(RECEIVED_MAX).joinToString("\n"))
    }

    private suspend fun receivedLines(): List<String> = platform.store.getString(RECEIVED_KEY)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    /** After a valid `delete` of a direct message: remove its entry from the own mailbox (ADR 0088 §3). */
    private suspend fun dropReceivedFromRelays(d: CoreEvent.Deleted) {
        val line = receivedLines().lastOrNull { it.startsWith("${d.targetMid} ") } ?: return
        val entry = line.split(' ').getOrNull(2)?.let { runCatching { ID.parse(it) }.getOrNull() } ?: return
        val cred = ownAccess().cred
        cs.launch { runCatching { transport.delete(cred, me.inbox, entry, entry = true) } }
        platform.store.putString(RECEIVED_KEY, receivedLines().filterNot { it.startsWith("${d.targetMid} ") }.joinToString("\n"))
    }

    /** Emits a received direct message and remembers its entry for a later `delete`. */
    private suspend fun emitReceived(e: CoreEvent.Message, space: Key, objectId: String) {
        emit(e)
        e.mid?.let { rememberReceived(it, space, objectId) }
    }

    /** Emits a valid `delete` for a direct conversation and removes the message's entry from the own mailbox. */
    private suspend fun emitDeleted(d: CoreEvent.Deleted?) {
        d ?: return
        emit(d)
        dropReceivedFromRelays(d)
    }

    private fun wireLink(l: PreparedLink): AnmpLink =
        AnmpLink(l.url, l.title?.take(LINK_TITLE_MAX), l.description?.take(LINK_DESCRIPTION_MAX), l.site?.take(LINK_TITLE_MAX), l.image?.attachment)

    private val receiptLock = Mutex()
    private val pendingReceipts = mutableMapOf<String, MutableList<String>>()

    /**
     * Confirms receipt of message [mid] to every device of the person of [deviceId] (ADR 0003, 0042); batched, without
     * wake-up. [arrived]: the path it came on here, bits of [AnmpArrived] (ADR 0058); 0 says nothing.
     */
    fun confirmReceipt(deviceId: String, mid: String, arrived: Int = 0) = confirm(AnmpKind.RECEIPT, deviceId, mid, arrived)

    /**
     * Confirms that message [mid] of [deviceId] was read here (ADR 0019), like [confirmReceipt]; the own other devices
     * learn it for [conversation] (ADR 0042). Own messages of another own device are only passed on to the own devices.
     */
    fun confirmRead(deviceId: String, mid: String, conversation: Conversation? = null) {
        if (personOf(deviceId) != ownPersonId) confirm(AnmpKind.READ, deviceId, mid)
        conversation?.let { conv -> confirm(AnmpKind.READ, OWN_SLOT + conversationKey(conv), mid) }
    }

    private fun conversationKey(c: Conversation) = when (c) {
        is Conversation.Circle -> "c:" + c.circleId
        is Conversation.Direct -> "d:" + c.personId
    }

    private fun confirm(kind: AnmpKind, deviceId: String, mid: String, arrived: Int = 0) {
        // One batch per path, so a receipt tells for all its IDs how they came (ADR 0058).
        val slot = "${kind.code}\n$deviceId\n$arrived"
        cs.launch {
            val first = receiptLock.withLock {
                val list = pendingReceipts[slot]
                if (list != null) {
                    list += mid
                    false
                } else {
                    pendingReceipts[slot] = mutableListOf(mid)
                    true
                }
            }
            if (!first) return@launch
            delay(timings.receiptBatch)
            val ids = receiptLock.withLock { pendingReceipts.remove(slot) } ?: return@launch
            val raw = ids.distinct().mapNotNull { runCatching { B64.decode(it) }.getOrNull()?.takeIf { b -> b.size == Anmp.ID_BYTES } }
            if (deviceId.startsWith(OWN_SLOT)) {
                val key = deviceId.removePrefix(OWN_SLOT)
                val c = if (key.startsWith("c:")) content(ids = raw, circle = key.drop(2)) else content(ids = raw, peer = key.drop(2).takeIf { it.length == 22 })
                postOwn(kind, c)
            } else {
                postPerson(personOf(deviceId), kind, content(ids = raw, arrived = arrived.takeIf { it != 0 }))
            }
        }
    }

    // --- Signs of life (ADR 0083 §5) -------------------------------------------------------------

    /** Signs of life this device knows, own and watched, newest first. */
    fun alivePlans(): List<AlivePlan> = state.alive.sortedByDescending { it.start }

    /** What the sign of life [planId] means at [now] (ADR 0083 §5.2–5.4); `null` if unknown. */
    fun aliveStatus(planId: String, now: Long = now()): AliveStatus? =
        state.alive.firstOrNull { it.id == planId }?.let { AliveLogic.status(it, it.owner == ownPersonId, ownPersonId, now) }

    /** Statuses of all signs of life that are not ended. */
    fun aliveStatuses(now: Long = now()): List<AliveStatus> =
        state.alive.map { AliveLogic.status(it, it.owner == ownPersonId, ownPersonId, now) }.filter { it.phase != AliveStatus.ENDED }

    /** When something changes next without news (ms), for a wake-up; `null` if nothing waits. */
    fun aliveNextAt(now: Long = now()): Long? = aliveStatuses(now).map { it.nextAt }.filter { it > 0 }.minOrNull()

    /**
     * Starts a sign of life in the circle [circleId] the person chose; its other members watch it (ADR 0083 §1). An own
     * one still running ends first. [until]: latest end (ms); [quiet]: how long the phone may lie still before it asks,
     * at least [AliveLogic.MIN_QUIET]; [firstDue]: first time to ask, if not after [quiet] (e.g. „einmal am Morgen“).
     * `null` if the circle is unknown or we were removed from it.
     */
    suspend fun startAlive(
        circleId: String,
        label: String,
        until: Long,
        quiet: Long,
        motion: Boolean = false,
        firstDue: Long? = null,
        hint: Long = AliveLogic.DEFAULT_HINT,
        alarm: Long = AliveLogic.DEFAULT_ALARM,
    ): AlivePlan? = onCore {
        opened.await()
        val circle = circle(circleId)?.takeIf { !it.removed } ?: return@onCore null
        state.alive.filter { it.owner == ownPersonId && !it.ended }.forEach { sendAlive(it, AnmpAliveState.END) }
        val start = now()
        val end = until.coerceIn(start + 60_000L, start + AliveLogic.MAX_LENGTH)
        val q = quiet.coerceIn(AliveLogic.MIN_QUIET, AliveLogic.MAX_QUIET)
        val h = hint.coerceIn(60_000L, 3_600_000L)
        val plan = AlivePlan(
            id = B64.encode(messageIdOf(null)), circleId = circle.id, owner = ownPersonId, label = label.trim().take(AliveLogic.MAX_LABEL),
            start = start, until = end, due = (firstDue ?: AliveLogic.nextDue(start, q, end)).coerceIn(start, end), hint = h,
            alarm = alarm.coerceIn(h, 86_400_000L), quiet = q, motion = motion, state = AnmpAliveState.START, lastAt = start,
            confirmedAt = start,
        )
        return@onCore sendAlive(plan, AnmpAliveState.START, mid = plan.id)
    }

    /**
     * Confirms the own sign of life [planId]: a tap („Alles gut“), or with [moving] movement of the phone, which is sent
     * only if [AliveLogic.movingWorthSending]. `false` if nothing was sent.
     */
    suspend fun confirmAlive(planId: String, moving: Boolean = false): Boolean = onCore {
        val plan = ownPlan(planId) ?: return@onCore false
        val now = now()
        if (moving && (!plan.motion || !AliveLogic.movingWorthSending(plan, now))) return@onCore false
        return@onCore sendAlive(plan, if (moving) AnmpAliveState.MOVING else AnmpAliveState.OK) != null
    }

    /** Ends the own sign of life on purpose: „Alles gut, beendet“. */
    suspend fun endAlive(planId: String): Boolean = onCore {
        val plan = ownPlan(planId) ?: return@onCore false
        return@onCore sendAlive(plan, AnmpAliveState.END) != null
    }

    /** Tells the helpers once that the battery is nearly empty ([percent]). */
    suspend fun aliveBattery(planId: String, percent: Int): Boolean = onCore {
        val plan = ownPlan(planId)?.takeIf { it.battery == null || it.battery > percent + 5 } ?: return@onCore false
        return@onCore sendAlive(plan, AnmpAliveState.BATTERY, battery = percent.coerceIn(0, 100)) != null
    }

    /**
     * Does what is due for own signs of life at the current time: reports „nicht bestätigt“ after the phone asked in
     * vain, ends at the latest end. Call it at [aliveNextAt] and when the app wakes; returns the next time.
     */
    suspend fun aliveTick(): Long? = onCore {
        val now = now()
        state.alive.filter { AliveLogic.stale(it, now) }.takeIf { it.isNotEmpty() }?.let { gone ->
            update { s -> s.copy(alive = s.alive - gone.toSet()) }
        }
        // Only the device that sends a plan reports for it, so own other devices do not send twice.
        for (plan in state.alive.filter { it.owner == ownPersonId && !it.ended && it.sentMids.isNotEmpty() }) {
            when {
                now >= plan.until -> sendAlive(plan, AnmpAliveState.TIMEOUT)
                plan.state != AnmpAliveState.MISSED && now >= AliveLogic.missedAt(plan) -> sendAlive(plan, AnmpAliveState.MISSED)
            }
        }
        return@onCore aliveNextAt(now)
    }

    /** Answers a watched sign of life as a helper ([AnmpCareState]): looking after it, away, back. */
    suspend fun careAlive(planId: String, careState: Int): Boolean = onCore {
        val plan = state.alive.firstOrNull { it.id == planId && it.owner != ownPersonId } ?: return@onCore false
        val ts = now()
        val id = messageIdOf(null)
        val care = AnmpCare(B64.decode(planId), careState)
        val sent = postCircle(plan.circleId, AnmpKind.CARE, content(id = id, care = care, ts = ts / 1000), notify = true) != null
        if (sent) {
            putCare(planId, AliveCare(ownPersonId, careState, ts))
            radio?.let { way -> cs.launch { runCatching { way.care(plan.circleId, id, ts / 1000, care) } } }
        }
        return@onCore sent
    }

    private fun ownPlan(planId: String): AlivePlan? = state.alive.firstOrNull { it.id == planId && it.owner == ownPersonId && !it.ended }

    /**
     * Sends a packet of the own plan in its state [st] and keeps it. Confirmations do not wake anyone, unless the
     * helpers may already show a hint; start, „nicht bestätigt“, battery and end do.
     */
    private suspend fun sendAlive(plan: AlivePlan, st: Int, mid: String? = null, battery: Int? = null): AlivePlan? {
        val now = now()
        val confirming = st == AnmpAliveState.OK || st == AnmpAliveState.MOVING
        val due = when {
            st == AnmpAliveState.START -> plan.due
            confirming -> AliveLogic.nextDue(now, plan.quiet, plan.until)
            else -> plan.due
        }
        val next = plan.copy(
            state = st, lastAt = now, receivedAt = now, due = due, battery = battery ?: plan.battery,
            confirmedAt = if (confirming) now else plan.confirmedAt,
        )
        val id = messageIdOf(mid)
        val wire = AnmpAlive(
            B64.decode(plan.id), st, plan.start / 1000, plan.until / 1000, due / 1000, (plan.hint / 1000).toInt(), (plan.alarm / 1000).toInt(),
            (plan.quiet / 1000).toInt(), plan.motion, plan.label.takeIf { it.isNotEmpty() }, next.battery,
        )
        val wake = !confirming || now >= plan.due
        postCircle(plan.circleId, AnmpKind.ALIVE, content(id = id, alive = wire, ts = now / 1000), notify = wake) ?: return null
        // Also over radio, with the same message ID, so helpers without internet learn it (ADR 0083 §7).
        radio?.let { way -> cs.launch { runCatching { way.alive(plan.circleId, id, now / 1000, wire) } } }
        val sent = next.copy(sentMids = (plan.sentMids + B64.encode(id)).takeLast(4), unconfirmed = plan.unconfirmed + 1)
        putPlan(sent)
        return sent
    }

    private suspend fun putPlan(plan: AlivePlan) {
        update { s -> s.copy(alive = s.alive.filterNot { it.id == plan.id } + plan) }
        emit(CoreEvent.AliveChanged(plan.id, plan.circleId))
    }

    private suspend fun putCare(planId: String, care: AliveCare) {
        val plan = state.alive.firstOrNull { it.id == planId } ?: return
        // Packets carry seconds: within the same second the answer that came last wins.
        if (plan.care.any { it.person == care.person && (it.ts > care.ts || (it.ts == care.ts && it.state == care.state)) }) return
        putPlan(plan.copy(care = plan.care.filterNot { it.person == care.person } + care))
    }

    /**
     * A sign of life in [circle]: always of the sender's person; a plan stays with the person and circle of its first
     * packet, and only newer packets replace the known state. A packet without label (over radio, when it did not fit)
     * keeps the known label. [receipt]: confirm it over the internet (over radio the radio way confirms it). `false` if
     * the packet is invalid or belongs to another person or circle; a packet already known counts as valid.
     */
    private suspend fun onAlive(circle: CircleState, fromDevice: String, person: String, mid: ByteArray?, ts: Long, a: AnmpAlive, receipt: Boolean = true): Boolean {
        if (!AliveLogic.valid(a)) return false
        val id = B64.encode(a.plan)
        val at = maxOf(ts * 1000, a.start * 1000)
        val known = state.alive.firstOrNull { it.id == id }
        if (known != null && (known.circleId != circle.id || known.owner != person)) return false
        if (known != null && (at < known.lastAt || (at == known.lastAt && !replacesInSameSecond(a.state, known.state)))) {
            // The same packet over the internet after the radio one, which had to leave the label behind.
            a.label?.takeIf { it.isNotEmpty() && known.label.isEmpty() }?.let { putPlan(known.copy(label = it)) }
            return true
        }
        val confirming = a.state == AnmpAliveState.OK || a.state == AnmpAliveState.MOVING || a.state == AnmpAliveState.START
        val plan = AlivePlan(
            id = id, circleId = circle.id, owner = person, label = a.label ?: known?.label.orEmpty(), start = a.start * 1000, until = a.until * 1000,
            due = a.due * 1000, hint = a.hint * 1000L, alarm = a.alarm * 1000L, quiet = a.quiet * 1000L, motion = a.motion,
            state = a.state.takeIf { it in AnmpAliveState.START..AnmpAliveState.TIMEOUT } ?: AnmpAliveState.OK, lastAt = at, receivedAt = now(),
            battery = a.battery?.coerceIn(0, 100), confirmedAt = if (confirming) at else known?.confirmedAt ?: at,
            care = known?.care.orEmpty(), heard = known?.heard.orEmpty(), sentMids = known?.sentMids.orEmpty(), unconfirmed = known?.unconfirmed ?: 0,
        )
        putPlan(plan)
        if (receipt && person != ownPersonId) mid?.takeIf { it.size == Anmp.ID_BYTES }?.let { confirmReceipt(fromDevice, B64.encode(it), AnmpArrived.INTERNET) }
        return true
    }

    /**
     * Whether a packet with [new] replaces the known [old] state although both carry the same second, e.g. the copy of
     * a start over the internet after the end over radio: start never replaces another state, end and timeout are never
     * replaced, between the states in between the one read last wins.
     */
    private fun replacesInSameSecond(new: Int, old: Int): Boolean {
        fun rank(s: Int) = when (s) {
            AnmpAliveState.START -> 0
            AnmpAliveState.END, AnmpAliveState.TIMEOUT -> 2
            else -> 1
        }
        return rank(new) > rank(old) || (rank(new) == 1 && rank(old) == 1 && new != old)
    }

    /** Answer of a helper; from any member of the circle of the plan. */
    private suspend fun onCare(circle: CircleState, person: String, ts: Long, c: AnmpCare) {
        if (c.plan.size != Anmp.ID_BYTES || c.state !in AnmpCareState.LOOKING..AnmpCareState.BACK) return
        val id = B64.encode(c.plan)
        if (state.alive.none { it.id == id && it.circleId == circle.id }) return
        putCare(id, AliveCare(person, c.state, ts * 1000))
    }

    /** Receipts for own signs of life: who heard them, and that they arrive (ADR 0083 §5.1, §5.2). */
    private suspend fun onAliveReceipt(person: String, mids: List<String>) {
        val plan = state.alive.firstOrNull { p -> p.owner == ownPersonId && p.sentMids.any { it in mids } } ?: return
        val heard = plan.heard.filterNot { it.person == person } + AliveCare(person, 0, now())
        putPlan(plan.copy(heard = heard, unconfirmed = 0))
    }

    // --- Personal store ("Meine Ablage", ADR 0041) ------------------------------------------------

    /**
     * Puts a file into the personal store on the relays, without expiry. [file] is the app's ID of the file and
     * stays the same over changes; putting it again (new name) replaces the older entry at the next [storeList].
     * Returns the ref of [CoreEvent.Stored] / [CoreEvent.SendFailed].
     * A new file throws [StorageFullException] when the shared quota ([storageUsage]) has no room for it.
     */
    suspend fun storePut(
        file: String,
        plaintext: ByteArray,
        name: String,
        type: String,
        added: Long,
        width: Int = 0,
        height: Int = 0,
        /** Kind and details of the document, if already recognized (ADR 0067). */
        doc: DocInfo? = null,
        /** Title of the file, if already made (ADR 0091). */
        title: FileTitle? = null,
    ): String = onCore {
        opened.await()
        if (!store.knows(file)) checkRoom(plaintext.size.toLong())
        store.put(file, plaintext, name, type, added, now(), width, height, pool::checkObjectSize, doc, title = title)
    }

    /**
     * How full the own storage is over all stores together (shared quota): the personal store and, in every circle,
     * what we put in ourselves (`by`), as of the last [storeList] and [circleStoreList]; `null` before the personal store
     * was listed. Files others put into a circle do not count for us. The quota is the person's ([StoreUsage.FREE_QUOTA]),
     * at most the smallest space quota of the relays.
     */
    suspend fun storageUsage(): StoreUsage? = onCore {
        opened.await()
        storageUsageNow()
    }

    private suspend fun storageUsageNow(): StoreUsage? {
        val own = store.usage ?: return null
        val circlesUsed = state.circles.filter { !it.removed }.sumOf { c -> circleStores[c.secret]?.ownUsed ?: 0 }
        return StoreUsage((store.ownUsed ?: own.used) + circlesUsed, minOf(own.quota, StoreUsage.FREE_QUOTA))
    }

    /** Throws [StorageFullException] if a new file of [size] bytes does not fit into the shared quota any more. */
    private suspend fun checkRoom(size: Long) {
        val u = storageUsageNow() ?: return
        if (u.used + size > u.quota) throw StorageFullException(u.used, u.quota)
    }

    /**
     * Changes name, document details and title (ADR 0091; default: as it is) of [f] (from [storeList]) without
     * uploading the file again (ADR 0067).
     * Returns the ref of [CoreEvent.Stored] / [CoreEvent.SendFailed]; `null` if it cannot be done this way, then put
     * the file again with [storePut].
     */
    suspend fun storeUpdate(f: StoredFile, name: String, doc: DocInfo?, title: FileTitle? = f.title): String? = onCore {
        opened.await()
        store.update(f, name, doc, now(), title = title)
    }

    /** Every file in the personal store, newest first; `null` if the relays cannot be reached now. */
    suspend fun storeList(): List<StoredFile>? = onCore {
        opened.await()
        if (state.previousStore != null) moveOldStoreFiles()
        store.list()
    }

    /**
     * How full the personal store is (ADR 0029): as of the last [storeList] that reached a relay, without asking the
     * relays again; `null` before.
     */
    suspend fun storeUsage(): StoreUsage? = onCore {
        opened.await()
        store.usage
    }

    /** Content of a file from [storeList]. */
    suspend fun storeFetch(f: StoredFile): BlobResult = onCore {
        opened.await()
        store.fetch(f)
    }

    /** Removes [file] from the relays for good. `false`: no relay reachable, try again later. */
    suspend fun storeDelete(file: String): Boolean = onCore {
        opened.await()
        store.delete(file)
    }

    /**
     * Moves [file] into the bin (ADR 0086): it stays listed with [StoredFile.trashed] and still counts against the
     * quota until [StoredFile.purgeAt], then the next [storeList] deletes it for good. `false`: try again later.
     */
    suspend fun storeTrash(file: String): Boolean = onCore {
        opened.await()
        store.setTrashed(file, now())
    }

    /** Takes [file] back out of the bin. `false`: no relay reachable, try again later. */
    suspend fun storeRestore(file: String): Boolean = onCore {
        opened.await()
        store.setTrashed(file, 0)
    }

    // --- Circle store ("Kreis-Ablage", ADR 0049) ------------------------------------------------

    private suspend fun circleStore(secret: String): FileStore = circleStores[secret] ?: run {
        val keys = StoreKeys.ofCircle(secret)
        val envelope = CircleKeys.of(secret).anmp.envelope
        FileStore(keys, net, pool, transport, SpaceAccess(keys.space, keys.owner, null, envelope, keys.owner), CIRCLE_REF_PREFIX, me = { ownPersonId }, now = ::now, trashMillis = timings.trashKeep.inWholeMilliseconds) { log.log(it) }
            .also { circleStores[secret] = it }
    }

    /** Store of the current key and, during the grace period of a key change, of the previous one. */
    private suspend fun circleStoresOf(circleId: String): Pair<FileStore, FileStore?>? {
        val c = circle(circleId)?.takeIf { !it.removed } ?: return null
        val previous = c.previousSecret?.takeIf { c.graceUntil > now() }?.let { circleStore(it) }
        return circleStore(c.secret) to previous
    }

    /**
     * Puts a file into the store of the circle [circleId], without expiry, like [storePut]. Every member may change
     * and delete every file. `null` if we are not (or no longer) in the circle.
     * Counts for us in the shared quota and throws [StorageFullException] like [storePut].
     */
    suspend fun circleStorePut(
        circleId: String,
        file: String,
        plaintext: ByteArray,
        name: String,
        type: String,
        added: Long,
        width: Int = 0,
        height: Int = 0,
        /** Kind and details of the document, if already recognized (ADR 0067). */
        doc: DocInfo? = null,
        /** Title of the file, if already made (ADR 0091). */
        title: FileTitle? = null,
    ): String? = onCore {
        opened.await()
        val (current, _) = circleStoresOf(circleId) ?: return@onCore null
        if (!current.knows(file)) checkRoom(plaintext.size.toLong())
        current.put(file, plaintext, name, type, added, now(), width, height, pool::checkObjectSize, doc, title = title)
    }

    /**
     * Like [storeUpdate] for a file of the circle store, for every member. `null` if we are not in the circle or the
     * file still lies in the previous space of a key change; then put it again with [circleStorePut].
     */
    suspend fun circleStoreUpdate(circleId: String, f: StoredFile, name: String, doc: DocInfo?, title: FileTitle? = f.title): String? = onCore {
        opened.await()
        val (current, _) = circleStoresOf(circleId) ?: return@onCore null
        current.update(f, name, doc, now(), title = title)
    }

    /**
     * Every file in the store of the circle, newest first; `null` if the relays cannot be reached now or we are not in
     * the circle. After a key change the files of the previous space are copied into the new one (the content is
     * sealed anew) and then deleted there; until then they are listed from the previous space.
     */
    suspend fun circleStoreList(circleId: String): List<StoredFile>? = onCore {
        opened.await()
        val (current, previous) = circleStoresOf(circleId) ?: return@onCore null
        val now = current.list() ?: return@onCore null
        val old = previous?.list() ?: return@onCore now
        val known = now.map { it.file }.toSet()
        val waiting = mutableListOf<StoredFile>()
        for (f in old) {
            if (f.file in known) {
                previous.deleteEntry(f, null)
                continue
            }
            waiting += f
            if (f.entryId in circleCopies) continue
            val got = previous.fetch(f) as? BlobResult.Found ?: continue
            circleCopies += f.entryId
            runCatching { current.put(f.file, got.plaintext, f.name, f.type, f.added, f.changed, f.width, f.height, pool::checkObjectSize, f.doc, by = f.by, trashed = f.trashed, title = f.title) }
                .onFailure { if (it is CancellationException) throw it; log.log("circle store copy ${f.file.take(8)}: ${it.message}") }
        }
        (now + waiting).sortedByDescending { it.added }
    }

    /** Like [storeUsage] for the current store of the circle; `null` before [circleStoreList] or if not in the circle. */
    suspend fun circleStoreUsage(circleId: String): StoreUsage? = onCore {
        opened.await()
        circleStoresOf(circleId)?.first?.usage
    }

    /** Content of a file from [circleStoreList]. */
    suspend fun circleStoreFetch(circleId: String, f: StoredFile): BlobResult = onCore {
        opened.await()
        val (current, previous) = circleStoresOf(circleId) ?: return@onCore BlobResult.Gone
        if (previous != null && f.space == previous.space) previous.fetch(f) else current.fetch(f)
    }

    /** Like [storeTrash] for the circle store, for every member. `false`: no relay reachable, try again later. */
    suspend fun circleStoreTrash(circleId: String, file: String): Boolean = onCore {
        opened.await()
        val (current, previous) = circleStoresOf(circleId) ?: return@onCore true
        previous?.setTrashed(file, now())
        current.setTrashed(file, now())
    }

    /** Like [storeRestore] for the circle store, for every member. */
    suspend fun circleStoreRestore(circleId: String, file: String): Boolean = onCore {
        opened.await()
        val (current, _) = circleStoresOf(circleId) ?: return@onCore true
        current.setTrashed(file, 0)
    }

    /** Removes [file] from the circle store for good, for every member. `false`: no relay reachable, try again later. */
    suspend fun circleStoreDelete(circleId: String, file: String): Boolean = onCore {
        opened.await()
        val (current, previous) = circleStoresOf(circleId) ?: return@onCore true
        previous?.delete(file)
        current.delete(file)
    }

    // --- Blobs: attachments, pictures, want/have ------------------------------------------------

    /** Loads and decrypts an attachment of a message that lies in [space] ([CoreEvent.Message.space]). */
    suspend fun fetchAttachment(space: String, attachment: AnmpAttachment): BlobResult = onCore { fetchBlob(space, attachment.id, attachment.key) }

    /** Loads a picture (or a blob from `have`). The caller checks the plaintext hash if it needs to. */
    suspend fun fetchImage(ref: ImageRef): BlobResult = onCore { fetchBlob(ref.space, B64.decode(ref.id), B64.decode(ref.key)) }

    private suspend fun fetchBlob(space: String, id: ByteArray, key: ByteArray): BlobResult {
        opened.await()
        if (id.size != Anmp.BLOB_ID_BYTES) return BlobResult.Gone
        val spaceKey = runCatching { Key.parse(space) }.getOrNull() ?: return BlobResult.Gone
        return when (val r = transport.readContent(spaceKey, ID(id))) {
            ReadResult.Gone -> BlobResult.Gone
            ReadResult.Later -> BlobResult.Later
            is ReadResult.Found -> {
                val plain = AnmpBlob.open(key, id, r.fetched.data) ?: return BlobResult.Later
                reseedContent(space, r)
                BlobResult.Found(plain)
            }
        }
    }

    /** §6b for contents: a chosen holder answered 404 although it is up; put the object there again (as owner). */
    private fun reseedContent(space: String, r: ReadResult.Found) {
        if (r.missingAt.isEmpty()) return
        val meta = r.fetched.meta
        if (now() - meta.ts * 1000 < timings.reseedAfter.inWholeMilliseconds) return
        cs.launch {
            val a = accessFor(space)?.takeIf { it.owner != null } ?: return@launch
            r.missingAt.forEach { relay -> transport.reput(relay, a, meta, r.fetched.data) }
        }
    }

    /** Asks for a picture or attachment with plaintext hash [hash] (ADR 0014): in the circle, or in the mailbox of a contact. */
    suspend fun want(conversation: Conversation, hash: String): Boolean  = onCore {
        val c = content(wantHash = B64.decode(hash))
        return@onCore when (conversation) {
            is Conversation.Circle -> postCircle(conversation.circleId, AnmpKind.WANT, c, lifetime = timings.wantLifetime) != null
            is Conversation.Direct -> postPerson(personOf(conversation.personId), AnmpKind.WANT, c, lifetime = timings.wantLifetime)
        }
    }

    private val haveAt = mutableMapOf<String, Long>()

    /** Someone looks for [hash]: if the app has it, one member by rank uploads it and answers with `have`. */
    private fun onWant(conversation: Conversation, fromDevice: String, hash: String) {
        val since = now()
        val answer: suspend () -> Unit = answer@{
            val bytes = host.sharedContent(conversation, hash, fromDevice) ?: return@answer
            if (B64.encode(plainHash(bytes)) != hash) return@answer
            val space = when (conversation) {
                is Conversation.Circle -> currentCircle(conversation.circleId)?.second?.space
                is Conversation.Direct -> targetsOf(personOf(conversation.personId)).firstNotNullOfOrNull { peerAccess(it) }?.space
            } ?: return@answer
            val (ref, upload) = uploadFor(space, bytes, hash)
            haveAt[hash] = now()
            val c = content(wantHash = B64.decode(hash), haveRef = anmpImage(ref))
            when (conversation) {
                is Conversation.Circle -> postCircle(conversation.circleId, AnmpKind.HAVE, c, contents = listOf(upload), lifetime = timings.wantLifetime)
                is Conversation.Direct -> postPerson(personOf(conversation.personId), AnmpKind.HAVE, c, contents = listOf(upload), lifetime = timings.wantLifetime)
            }
        }
        when (conversation) {
            is Conversation.Circle -> runDuty(conversation.circleId, "want/$hash", done = { (haveAt[hash] ?: 0) >= since }) { answer() }
            is Conversation.Direct -> cs.launch { answer() }
        }
    }

    // --- History for new members (ADR 0014) ----------------------------------------------------

    private val historyNeeded = mutableMapOf<Pair<String, String>, Boolean>()

    private fun onHistoryNeed(circle: CircleState, deviceId: String, needs: Boolean, viaPrevious: Boolean) {
        val key = circle.id to deviceId
        historyNeeded[key] = needs
        if (!needs || viaPrevious || circle.removed) return
        runDuty(circle.id, "history/$deviceId", done = { historyNeeded[key] == false }) {
            if (sendHistory(circle.id, deviceId)) historyNeeded[key] = false
        }
    }

    /**
     * Sends the history to a new member: parts of at most [HISTORY_PART_BYTES] as contents in our own mailbox
     * (expiry [CoreTimings.entryLifetime]), each with a scoped delete grant for the new member, and the index
     * (kind `history`) into its mailbox. An own other device shares our mailbox: index and parts go there without a
     * grant (ADR 0042), so a new own device gets the history even when no other member is online.
     */
    private suspend fun sendHistory(circleId: String, deviceId: String): Boolean {
        if (revokedHere) return false
        val m = member(circleId, deviceId) ?: return false
        val grantee = m.signKey?.let { Key.parse(it) } ?: return false
        val ownPeer = ownOthers().firstOrNull { it.deviceId == deviceId }
        if (ownPeer == null && targetOfDevice(deviceId)?.let { grantOf(it.inbox) } == null) return false
        val items = host.circleHistory(circleId)
        val until = lifetimeUntil()
        val parts = mutableListOf<AnmpHistoryPart>()
        val uploads = mutableListOf<Pair<ID, ByteArray>>()
        for (chunk in chunkHistory(items)) {
            val key = AnmpBlob.newKey()
            val sealed = AnmpBlob.seal(key, chunk)
            val del = Grant.issue(me.inboxOwner, GrantBody(me.inbox, Rights.DELETE, grantee, ID(sealed.id), until, nowS() - GRANT_BACKDATE))
            parts += AnmpHistoryPart(sealed.id, key, me.inbox.bytes, AnmpKeys.sha256(chunk), grant = del.encode())
            uploads += ID(sealed.id) to sealed.bytes
        }
        val history = AnmpHistory(circleId, items.size, 0, 0, parts)
        if (ownPeer != null) return true.also { postOwnTo(ownPeer, AnmpKind.HISTORY, content(history = history), timings.wantLifetime, uploads) }
        return postInbox(deviceId, AnmpKind.HISTORY, content(history = history), contents = uploads, lifetime = timings.wantLifetime, contentSpace = me.inbox)
    }

    private fun chunkHistory(items: List<ByteArray>): List<ByteArray> {
        val chunks = mutableListOf<List<ByteArray>>()
        var current = mutableListOf<ByteArray>()
        var size = 5
        for (item in items) {
            val itemSize = item.size + 5
            if (current.isNotEmpty() && size + itemSize > HISTORY_PART_BYTES) {
                chunks += current
                current = mutableListOf()
                size = 5
            }
            current += item
            size += itemSize
        }
        if (current.isNotEmpty()) chunks += current
        return chunks.map { coreCbor.encodeToByteArray(historySerializer, it) }
    }

    private fun onHistory(h: AnmpHistory, fromDevice: String) {
        val circle = circle(h.circle) ?: return
        if (!circle.historyPending || circle.id in state.historyOffered) return
        cs.launch {
            update { s -> s.copy(historyOffered = s.historyOffered + circle.id) }
            announce(circle.id)
            var pause = timings.historyRetryMin
            while (true) {
                val messages = fetchHistory(h)
                if (messages != null) {
                    messages.forEach { emit(it) }
                    updateCircle(circle.id) { it.copy(historyPending = false) }
                    emit(CoreEvent.HistoryLoaded(circle.id, messages.size))
                    return@launch
                }
                delay(pause)
                pause = minOf(pause * 2, timings.historyRetryMax)
            }
        }
    }

    /** All parts, hash and device signature of every message checked; then the parts are deleted. `null`: later. */
    private suspend fun fetchHistory(h: AnmpHistory): List<CoreEvent.Message>? {
        val chunks = mutableListOf<ByteArray>()
        for (part in h.parts) {
            val space = runCatching { Key(part.from) }.getOrNull() ?: continue
            val bytes = when (val r = transport.readContent(space, ID(part.id))) {
                is ReadResult.Found -> AnmpBlob.open(part.key, part.id, r.fetched.data)
                ReadResult.Gone -> continue
                ReadResult.Later -> return null
            } ?: continue
            if (!AnmpKeys.sha256(bytes).contentEquals(part.hash)) continue
            chunks += bytes
        }
        val messages = chunks.flatMap { runCatching { coreCbor.decodeFromByteArray(historySerializer, it) }.getOrDefault(emptyList()) }
            .mapNotNull { opened ->
                val o = Packets.openHistory(opened, h.circle)?.takeIf { it.kind == AnmpKind.CIRCLE } ?: return@mapNotNull null
                val id = "history:${o.fromDevice}:${o.content.id?.let(B64::encode) ?: o.content.ts}"
                val author = historyAuthorOf(o.fromDevice, o.content.ts * 1000) ?: return@mapNotNull null
                messageEvent(Conversation.Circle(h.circle), id, "", o, history = true, person = author)
            }
        h.parts.forEach { part ->
            val g = part.grant?.let { runCatching { Grant.decode(it) }.getOrNull() } ?: return@forEach
            cs.launch { transport.delete(Credentials(me.writer, g), Key(part.from), ID(part.id), entry = false) }
        }
        return messages
    }

    // --- Forwarding at a shared radio (ADR 0022) ------------------------------------------------

    /**
     * A direct radio packet for another installation (kinds direct, react, read) received at a shared radio: put
     * unchanged into its personal mailbox if we know it and may write there. `false` otherwise (the app may keep it).
     */
    suspend fun forwardRadioPacket(packet: ByteArray): Boolean  = onCore {
        opened.await()
        val h = Anmp.peek(packet) ?: return@onCore false
        if (h.kind !in Packets.FORWARDED_KINDS || h.senderKey != null) return@onCore false
        val to = h.to ?: return@onCore false
        if (to.contentEquals(me.tag)) return@onCore false
        val peer = peerByTag(to) ?: return@onCore false
        val a = targetOfDevice(peer.deviceId)?.let { peerAccess(it) } ?: return@onCore false
        if (transport.pending().count { it.ref.startsWith(FORWARD_REF) } >= MAX_FORWARDS) return@onCore false
        val entry = a.seal(packet)
        transport.submit(a.space, entry, untilOf(lifetimeOf(h.kind)), ref = FORWARD_REF + ID.of(packet).toString().take(11), notify = h.kind == AnmpKind.DIRECT, known = a)
        return@onCore true
    }

    // --- Usage statistics (ADR 0045) -------------------------------------------------------------------

    /**
     * Sends today's usage report of this device (ADR 0045): signed with the stats key of the UTC day, to the relay the
     * person marker picks among the active relays (the next one if it fails). The app counts what only it knows
     * ([radios] on this device, its [platformName] and [version]) and calls this at most once a day, and only while
     * the person has the stats on. [curatedIds] maps curated channel links of the config to their report IDs (e.g.
     * `nina`). `true` once a relay took the report.
     */
    suspend fun sendStats(platformName: String, version: String, radios: Int, curatedIds: Map<String, String>): Boolean {
        val prepared = onCore {
            opened.await()
            if (revokedHere) return@onCore null
            val deviceSeed = platform.secrets.get(CoreIdentity.SIGN)?.takeIf { it.size == 32 } ?: return@onCore null
            val personSeed = platform.secrets.get(CoreIdentity.PERSON)?.takeIf { it.size == 32 } ?: return@onCore null
            val curatedBySpace = curatedIds.mapNotNull { (link, id) ->
                linkHosts.firstNotNullOfOrNull { h -> AnmpAlerts.parseChannelLink(link, h) }?.space?.let { it to id }
            }.toMap()
            val body = AnmpStats.body(
                deviceSeed, personSeed, nowS(), platformName, version, radios,
                channels = state.channels.count { !it.curated },
                curated = state.channels.filter { it.curated }.mapNotNull { curatedBySpace[it.id] },
                devices = _ownDevices.value.size,
                circles = state.circles.count { !it.removed },
            )
            body.person to AnmpStats.sign(body, deviceSeed)
        } ?: return false
        val (marker, report) = prepared
        return postStats(marker, report)
    }

    /**
     * Tells once that the person switched the stats [on] or off on this device (ADR 0045): day, platform and the change,
     * signed with the stats key of the day, nothing else. The app calls it only on the device where the person switched,
     * not when the setting arrives from another own device. `true` once a relay took it.
     */
    suspend fun sendStatsSwitch(platformName: String, on: Boolean): Boolean {
        val report = onCore {
            opened.await()
            if (revokedHere) return@onCore null
            val deviceSeed = platform.secrets.get(CoreIdentity.SIGN)?.takeIf { it.size == 32 } ?: return@onCore null
            AnmpStats.signSwitch(deviceSeed, nowS(), platformName, on)
        } ?: return false
        // No person marker in a switch report: any relay counts it; the report bytes pick one.
        return postStats(AnmpKeys.sha256(report), report)
    }

    /** Sends a stats report to the relay [route] picks among the active relays, else to the next. */
    private suspend fun postStats(route: ByteArray, report: ByteArray): Boolean {
        // Right after the start the network list may still be loading.
        val list = net.list.value ?: withTimeoutOrNull(STATS_LIST_WAIT) { net.list.filterNotNull().first() }
        val relays = list?.active().orEmpty().sortedBy { it.node.toString() }
        if (relays.isEmpty()) return false
        val start = AnmpStats.relayIndex(route, relays.size)
        for (i in relays.indices) {
            val client = pool.client(relays[(start + i) % relays.size]) ?: continue
            try {
                client.postStats(report)
                return true
            } catch (e: AndpHttpException) {
                // A rejected report (4xx) would be rejected by the others too; on 5xx the next relay.
                log.log("stats: relay answered ${e.status}")
                if (e.status in 400..499) return false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Not reachable: the next relay.
            }
        }
        return false
    }

    // --- Channels and alerts (ADR 0062) ---------------------------------------------------------------

    fun channel(id: String): ChannelState? = state.channels.firstOrNull { it.id == id }

    /** Owner keys of own channels and random reader keys (per channel and run, so relays cannot link the channels of a reader). */
    private val channelOwners = mutableMapOf<String, SigningKey>()
    private val channelReaders = mutableMapOf<String, SigningKey>()

    private val curatedSpaces: Set<String> by lazy { config.curatedChannels.mapNotNull { linkHosts.firstNotNullOfOrNull { h -> AnmpAlerts.parseChannelLink(it, h) }?.space }.toSet() }

    private suspend fun channelOwner(c: ChannelState): SigningKey? {
        val seed = c.ownerSeed ?: return null
        return channelOwners.getOrPut(c.id) { SigningKey.fromSeed(B64.decode(seed)) }
    }

    /** Access to a channel space: as owner, else with a reader key and the read grant. Entries have no envelope. */
    private suspend fun channelAccess(c: ChannelState): SpaceAccess? {
        val space = runCatching { Key.parse(c.id) }.getOrNull() ?: return null
        channelOwner(c)?.let { return SpaceAccess(space, it, null, null, it) }
        val grant = runCatching { Grant.parse(c.grant) }.getOrNull() ?: return null
        val reader = channelReaders.getOrPut(c.id) { SigningKey.generate() }
        return SpaceAccess(space, reader, grant, null, null)
    }

    /** Owner access to the ingress space of a device address token. */
    private suspend fun ingressAccess(token: String): SpaceAccess? {
        val raw = AnmpAlerts.tokenBytes(token) ?: return null
        val k = SigningKey.fromSeed(AnmpAlerts.ingressSeed(raw))
        return SpaceAccess(k.key, k, null, null, k)
    }

    private suspend fun updateChannel(id: String, change: (ChannelState) -> ChannelState) =
        update { s -> s.copy(channels = s.channels.map { if (it.id == id) change(it) else it }) }

    /** Creates an own channel: owner key, read key and read grant for anyone (ADR 0062 §1). */
    suspend fun createChannel(name: String): ChannelState = onCore {
        val owner = SigningKey.generate()
        val grant = Grant.issue(owner, GrantBody(owner.key, Rights.READ + Rights.LIST, null, null, AnmpAlerts.GRANT_UNTIL, nowS() - GRANT_BACKDATE))
        val c = ChannelState(
            owner.key.toString(), name.trim().take(AnmpAlerts.MAX_NAME), owner = true, readKey = B64.encode(AnmpKeys.randomKey()),
            grant = grant.toString(), ownerSeed = B64.encode(owner.seed), joinedAt = now(), addedHere = now(),
        )
        update { s -> s.copy(channels = s.channels + c) }
        ensureJoinGrant(c)
        return@onCore c
    }

    /** Channel link (`https://anynear.app/a#…`) of an own channel; `null` for channels of others. */
    fun channelLink(channelId: String): String? {
        val c = channel(channelId)?.takeIf { it.owner } ?: return null
        val join = if (c.curated) null else joinGrant(c.id)?.let { g ->
            runCatching { Grant.parse(g.grant) }.getOrNull()?.let { AnmpChannelJoin(B64.decode(me.deviceId), B64.decode(me.publicKey), me.inbox.bytes, it.encode()) }
        }
        return AnmpAlerts.channelLink(config.linkHost, AnmpChannelInvite(config.networkId.bytes, c.id, c.name, B64.decode(c.readKey), c.grant, c.curated, join))
    }

    /**
     * What an invitation link in a message stands for, without changing anything (e.g. to show it as a card instead of
     * the link): circle (`/k#`), contact (`/c#`) or channel (`/a#`), its name and whether we are already in, know it or
     * subscribed. `null` if [link] is none of these, broken or from another network.
     */
    suspend fun previewLink(link: String): LinkPreview? {
        opened.await()
        val network = config.networkId.toString()
        parseInvite(link)?.let { invite ->
            if (invite.networkId != network) return null
            val circle = (circle(invite.circleId) ?: state.circles.firstOrNull { it.secret == invite.secret })
                ?.takeIf { !it.removed && it.secret == invite.secret }
            return LinkPreview(LinkPreview.CIRCLE, invite.name, joined = circle != null, target = circle?.id, inviter = invite.inviter)
        }
        parseContact(link)?.let { card ->
            if (card.networkId != network) return null
            val self = card.deviceId == me.deviceId || _ownDevices.value.any { it.deviceId == card.deviceId }
            val known = member(MemberState.CONTACTS, card.deviceId)
            return LinkPreview(LinkPreview.CONTACT, card.name, joined = known != null, self = self, target = known?.person)
        }
        parseChannelLink(link)?.let { invite ->
            if (invite.networkId != network) return null
            val joined = channel(invite.channelId) != null
            return LinkPreview(LinkPreview.CHANNEL, invite.name, joined = joined, curated = invite.curated, target = invite.channelId.takeIf { joined }, seenByOwner = invite.seenByOwner)
        }
        parsePair(link)?.let { offer ->
            if (offer.networkId != network) return null
            // joined: already one of the own devices; self: the code comes from this device.
            val added = offer.deviceId == me.deviceId || _ownDevices.value.any { it.deviceId == offer.deviceId }
            return LinkPreview(LinkPreview.DEVICE, offer.name, joined = added, self = offer.deviceId == me.deviceId)
        }
        return null
    }

    /**
     * Reads a channel link, also inside text. The grant must be a read grant (read, list) for anyone of the channel's
     * space that has not expired; its signature is checked by [joinChannel].
     */
    fun parseChannelLink(link: String): ChannelInvite? {
        val w = linkHosts.firstNotNullOfOrNull { AnmpAlerts.parseChannelLink(link, it) } ?: return null
        val space = runCatching { Key.parse(w.space) }.getOrNull() ?: return null
        val grant = runCatching { Grant.parse(w.grant) }.getOrNull() ?: return null
        val b = grant.body
        if (b.space != space || !b.rights.has(Rights.READ + Rights.LIST) || b.grantee != null || b.scope != null || b.until <= nowS()) return null
        val join = w.join?.let { j -> ChannelJoin(B64.encode(j.device), B64.encode(j.key), B64.encode(j.inbox), B64.encode(j.grant)) }
        return ChannelInvite(B64.encode(w.networkId), w.space, w.name, w.curated, w.readKey, grant, join)
    }

    /**
     * Subscribes to a channel by its link. If the link carries a way to the owner (own channels, ADR 0062 §11), this
     * device announces itself there, so the owner sees it; otherwise nobody learns of it. A channel counts as curated only if
     * [AnmpCoreConfig.curatedChannels] lists it. A link of a known channel with a new read key (the owner reset it)
     * replaces the old key.
     */
    suspend fun joinChannel(link: String): ChannelJoinResult = onCore {
        val invite = parseChannelLink(link) ?: return@onCore ChannelJoinResult.Invalid
        if (invite.networkId != config.networkId.toString()) return@onCore ChannelJoinResult.ForeignNetwork
        if (runCatching { invite.grant.verifySig() }.isFailure) return@onCore ChannelJoinResult.Invalid
        val readKey = B64.encode(invite.readKey)
        val known = channel(invite.channelId)
        if (known != null && (known.owner || known.readKey == readKey)) return@onCore ChannelJoinResult.AlreadyJoined(known)
        val t = now()
        val c = ChannelState(
            invite.channelId, invite.name, owner = false, curated = invite.curated && invite.channelId in curatedSpaces,
            readKey = readKey, grant = invite.grant.toString(), joinedAt = known?.joinedAt ?: t,
            nameTs = if (known != null) t else 0, keyTs = if (known != null) t else 0, addedHere = known?.addedHere ?: t,
            join = invite.join.takeIf { !invite.curated },
        )
        channelReaders.remove(c.id)
        update { s -> s.copy(channels = s.channels.filterNot { it.id == c.id } + c) }
        if (c.join != null) cs.launch { onCore { runCatching { announceChannel(c.id) } } }
        return@onCore ChannelJoinResult.Joined(c)
    }

    /**
     * Leaves a channel: no more listing and wake-ups, its alerts and cursors are forgotten. For an own channel the device addresses
     * are revoked first; whoever has the link can still read what is on the relays until it expires.
     */
    suspend fun leaveChannel(channelId: String): Unit = onCore {
        val c = channel(channelId) ?: return@onCore
        if (c.owner) c.ingress.forEach { dropIngress(c, it) }
        if (c.owner) revoke(JOIN_PURPOSE + c.id)
        if (!c.owner && c.join != null) {
            runCatching { joinTarget(c)?.let { postTo(it, AnmpKind.UNSUBSCRIBE, content(channel = AnmpChannelRef(c.id))) } }
            revoke(SUBSCRIBED_PURPOSE + c.id)
        }
        forgetChannel(c)
        addTombstone(channelId)
    }

    /** Forgets a channel on this device only (left here or on an own other device): watch, state, cursors, alerts. */
    private suspend fun forgetChannel(c: ChannelState) {
        val channelId = c.id
        val target = pushTarget.value
        if (target != null) channelAccess(c)?.let { a -> runCatching { watchSpace(a, target, remove = true) } }
        update { s -> s.copy(channels = s.channels.filterNot { it.id == channelId }) }
        // Cursors of the space at every relay of the list (app and wake-up), so a later join starts afresh.
        for (r in net.active) for (prefix in listOf("core.cursor", "core.wake")) platform.store.putString(cursorKey(channelId, r, prefix), null)
        channelReaders.remove(channelId)
        channelOwners.remove(channelId)
        storedAlerts.update { list -> list.filterNot { it.channelId == channelId } }
    }

    /** Renames an own channel on every own device: the name in links shared later changes, not at the subscribers. */
    suspend fun renameChannel(channelId: String, name: String): Boolean = onCore {
        if (channel(channelId)?.owner != true) return@onCore false
        updateChannel(channelId) { it.copy(name = name.trim().take(AnmpAlerts.MAX_NAME), nameTs = now()) }
        return@onCore true
    }

    /**
     * Sends an alert into an own channel as its owner (ADR 0062 §2): signed with the owner key, sealed to the read key,
     * attachments (at most 5, each at most 10 MiB, from [prepareAttachment]) as contents in the channel space. Priority
     * alarm wakes with alarm priority (`ANDP-Notify: alarm`). [until] Unix seconds (`null`: 24 hours), [replaces] hex ID of
     * an earlier alert of the channel, [ended] for an all clear. [CoreEvent.Stored] and [CoreEvent.SendFailed] carry the
     * hex ID as ref. `null` if the channel is not ours; throws [IllegalArgumentException] for an invalid alert.
     */
    suspend fun sendAlert(
        channelId: String,
        prio: Int,
        title: String?,
        text: String,
        url: String? = null,
        attachments: List<PreparedAttachment> = emptyList(),
        until: Long? = null,
        replaces: String? = null,
        ended: Boolean = false,
    ): AlertItem? = onCore {
        val c = channel(channelId)?.takeIf { it.owner } ?: return@onCore null
        val owner = channelOwner(c) ?: return@onCore null
        val a = channelAccess(c) ?: return@onCore null
        require(attachments.all { it.attachment.size <= AnmpAlerts.MAX_ATTACHMENT_BYTES }) { "attachment larger than 10 MiB" }
        val replacesId = replaces?.let { requireNotNull(it.hexOrNull()?.takeIf { b -> b.size == AnmpAlerts.ID_BYTES }) { "replaces is no alert ID" } }
        val body = AnmpAlertBody(
            Anmp.newMessageId(), nowS(), prio, title?.takeIf { it.isNotBlank() }, text, url?.takeIf { it.isNotBlank() }, until, replacesId,
            attachments = attachments.map { it.attachment }.takeIf { it.isNotEmpty() }, ended = ended,
        )
        require(AnmpAlerts.isValid(body)) { "invalid alert" }
        val signed = AnmpAlerts.sign(body, AnmpSpaceKey.fromPrivateKey(owner.seed))
        val alert = requireNotNull(AnmpAlerts.verify(signed))
        val packet = AnmpAlerts.seal(signed, AnmpAlerts.readPublicKey(B64.decode(c.readKey)))
        val id = body.id.toHex()
        val entryUntil = lifetimeUntil(AnmpAlerts.KEEP_SECONDS.seconds)
        transport.submit(
            a.space, packet, entryUntil, attachmentContents(attachments), entryUntil, ref = id, notify = true,
            alarm = prio == AnmpAlerts.ALARM, known = a,
        )
        dropPrepared(attachments)
        val stored = ChannelRules.storedOf(c.id, alert, now())
        storedAlerts.update { pruneAlerts(it + stored) }
        return@onCore ChannelRules.items(storedAlerts.value, now()).first { it.channelId == c.id && it.id == id }
    }

    /**
     * Creates a device address for an own channel (ADR 0062 §4): a new token and sender key, a write grant for it, and
     * the record sealed with the token in the token's ingress space. `null` if the channel is not ours.
     */
    suspend fun createIngress(channelId: String, label: String): Ingress? = onCore {
        val c = channel(channelId)?.takeIf { it.owner } ?: return@onCore null
        val owner = channelOwner(c) ?: return@onCore null
        val token = AnmpAlerts.newToken()
        val sender = SigningKey.generate()
        val grant = Grant.issue(owner, GrantBody(owner.key, Rights.WRITE, sender.key, null, AnmpAlerts.GRANT_UNTIL, nowS() - GRANT_BACKDATE))
        val i = Ingress(
            sender.key.bytes.toHex(), label.trim().take(MAX_LABEL), AnmpAlerts.ingressUrl(config.ingressHost, token), now(), token,
            grant.hash.toString(), entryId = "", senderSeed = B64.encode(sender.seed), senderGrant = grant.toString(),
        )
        val written = writeIngress(c, i) ?: return@onCore null
        updateChannel(c.id) { it.copy(ingress = it.ingress + written) }
        return@onCore written
    }

    /** Writes the record of [i] (with the current read key of [c]) as owner of its ingress space, without expiry. */
    private suspend fun writeIngress(c: ChannelState, i: Ingress): Ingress? {
        val raw = AnmpAlerts.tokenBytes(i.token) ?: return null
        val a = ingressAccess(i.token) ?: return null
        val record = AnmpIngressRecord(AnmpAlerts.VERSION, c.id, AnmpAlerts.readPublicKey(B64.decode(c.readKey)), B64.decode(i.senderSeed), i.senderGrant, i.label)
        val sealed = AnmpAlerts.sealIngress(raw, record)
        transport.submit(a.space, sealed, until = 0, ref = INGRESS_REF + i.id, known = a)
        return i.copy(entryId = ID.of(sealed).toString())
    }

    /** Revokes a device address: deletes its record and revokes its sender grant. `false` if unknown. */
    suspend fun revokeIngress(channelId: String, ingressId: String): Boolean = onCore {
        val c = channel(channelId)?.takeIf { it.owner } ?: return@onCore false
        val i = c.ingress.firstOrNull { it.id == ingressId } ?: return@onCore false
        dropIngress(c, i)
        updateChannel(c.id) {
            it.copy(ingress = it.ingress.filterNot { x -> x.id == ingressId }, revokedIngress = (it.revokedIngress - ingressId + ingressId).takeLast(MAX_REVOKED_INGRESS))
        }
        return@onCore true
    }

    private suspend fun dropIngress(c: ChannelState, i: Ingress) {
        ingressAccess(i.token)?.let { a -> runCatching { ID.parse(i.entryId) }.getOrNull()?.let { transport.delete(a.cred, a.space, it, entry = true) } }
        revokeChannelGrant(c, i.grantHash)
    }

    /** Revocation entry by the owner in the channel space (ANDP §4.3). */
    private suspend fun revokeChannelGrant(c: ChannelState, grantHash: String) {
        val a = channelAccess(c)?.takeIf { it.owner != null } ?: return
        val hash = runCatching { Hash.parse(grantHash) }.getOrNull() ?: return
        transport.submit(a.space, Meta.revocationData(hash), until = 0, revokes = hash.toString(), known = a)
    }

    /**
     * "Link zurücksetzen" (ADR 0062 §2): a new read key and read grant for an own channel, the old read grant revoked.
     * Subscribers with the old link read nothing new any more; share [channelLink] again. Device addresses keep their
     * URL; their records are written anew with the new read key. `null` if the channel is not ours.
     */
    suspend fun resetChannelLink(channelId: String): ChannelState? = onCore {
        val c = channel(channelId)?.takeIf { it.owner } ?: return@onCore null
        val t = now()
        // Everyone loses access, so the list starts empty; whoever subscribes with the new link comes back.
        val done = rekeyChannel(c) { it.copy(subscribers = emptyList(), unsubscribed = mergeGone(it.unsubscribed, it.subscribers.map { s -> SubscriberGone(s.deviceId, t) })) }
        return@onCore done
    }

    /**
     * Removes a subscriber of an own channel (ADR 0062 §11): a new read key and read grant as with [resetChannelLink],
     * and every other subscriber gets them in its mailbox, signed by the owner key. The removed one reads nothing new.
     * The old link no longer works; share [channelLink] again. `false` if the channel is not ours or has no such subscriber.
     */
    suspend fun removeSubscriber(channelId: String, deviceId: String): Boolean = onCore {
        val c = channel(channelId)?.takeIf { it.owner } ?: return@onCore false
        if (c.subscribers.none { it.deviceId == deviceId }) return@onCore false
        val t = now()
        val done = rekeyChannel(c) { it.copy(subscribers = it.subscribers.filterNot { s -> s.deviceId == deviceId }, unsubscribed = mergeGone(it.unsubscribed, listOf(SubscriberGone(deviceId, t)))) }
            ?: return@onCore false
        val owner = channelOwner(done) ?: return@onCore true
        val readKey = B64.decode(done.readKey)
        val sig = owner.sign(AnmpAlerts.channelKeySigningBytes(done.id, readKey, done.grant)).bytes
        val ref = AnmpChannelRef(done.id, readKey = readKey, grant = done.grant, sig = sig)
        for (s in done.subscribers) {
            runCatching {
                acceptGrant(s.deviceId, B64.decode(s.inbox), B64.decode(s.grant), null)
                if (!postTo(Target(Peer(s.deviceId, s.publicKey), s.inbox, legacy = false), AnmpKind.CHANNEL_KEY, content(channel = ref))) {
                    log.log("channel key: no way to ${s.deviceId.take(6)}")
                }
            }.onFailure { e -> if (e is CancellationException) throw e }
        }
        return@onCore true
    }

    /** New read key and read grant for an own channel, old grant revoked, device addresses rewritten; [change] adjusts the rest. */
    private suspend fun rekeyChannel(c: ChannelState, change: (ChannelState) -> ChannelState): ChannelState? {
        val owner = channelOwner(c) ?: return null
        val old = runCatching { Grant.parse(c.grant) }.getOrNull()
        // Same body in the same second would be the same grant (Ed25519 is deterministic), revoked together with the old one.
        val issued = maxOf(nowS() - GRANT_BACKDATE, (old?.body?.issued ?: 0) + 1)
        val grant = Grant.issue(owner, GrantBody(owner.key, Rights.READ + Rights.LIST, null, null, AnmpAlerts.GRANT_UNTIL, issued))
        val next = c.copy(readKey = B64.encode(AnmpKeys.randomKey()), grant = grant.toString(), keyTs = now())
        val ingress = next.ingress.map { i ->
            val previous = i.entryId
            val written = writeIngress(next, i) ?: i
            ingressAccess(i.token)?.let { a ->
                runCatching { ID.parse(previous) }.getOrNull()?.let { id -> cs.launch { delay(OLD_RECORD_DELAY); transport.delete(a.cred, a.space, id, entry = true) } }
            }
            written
        }
        val done = change(next.copy(ingress = ingress))
        updateChannel(c.id) { done }
        old?.let { revokeChannelGrant(done, it.hash.toString()) }
        return done
    }

    // --- Subscribers (ADR 0062 §11) ----------------------------------------------------------------

    /** Our grant in the links of an own channel, if issued and not revoked. */
    private fun joinGrant(channelId: String): IssuedGrant? {
        val now = nowS()
        return state.issued.lastOrNull { it.purpose == JOIN_PURPOSE + channelId && !it.revoked && it.until > now }
    }

    /** Issues (or renews) the grant for the links of an own channel. */
    private suspend fun ensureJoinGrant(c: ChannelState) {
        if (!c.owner || c.curated) return
        grantFor(JOIN_PURPOSE + c.id, null, JOIN_GRANT_LIFETIME, JOIN_GRANT_RENEW)
    }

    /** At start: grants for the links of own channels, and announcements this device still owes (new link, new name). */
    private suspend fun channelsOnStart() {
        for (c in state.channels) {
            if (c.owner) ensureJoinGrant(c)
            else if (c.join != null && (c.announced == 0L || c.announced < state.profile.nameTs)) announceChannel(c.id)
        }
    }

    /** Announces this device to the owner of a subscribed channel again (e.g. after a new name). */
    private suspend fun reannounceChannels() {
        for (c in state.channels) if (!c.owner && c.join != null) runCatching { announceChannel(c.id) }.onFailure { e -> if (e is CancellationException) throw e }
    }

    private suspend fun joinTarget(c: ChannelState): Target? {
        val j = c.join ?: return null
        acceptGrant(j.deviceId, B64.decode(j.inbox), B64.decode(j.grant), null)
        return Target(Peer(j.deviceId, j.publicKey), j.inbox, legacy = false)
    }

    /** Sends subscribe to the owner: name, our mailbox with a grant back, the hash of the read grant we hold. */
    private suspend fun announceChannel(channelId: String) {
        val c = channel(channelId)?.takeIf { !it.owner && it.join != null } ?: return
        val t = joinTarget(c) ?: return
        val readGrant = runCatching { Grant.parse(c.grant) }.getOrNull() ?: return
        val back = grantFor(SUBSCRIBED_PURPOSE + c.id, null, JOIN_GRANT_LIFETIME, JOIN_GRANT_RENEW)
        if (postTo(t, AnmpKind.SUBSCRIBE, content(grant = back, channel = AnmpChannelRef(c.id, readGrant = readGrant.hash.bytes)))) {
            updateChannel(c.id) { it.copy(announced = now()) }
        }
    }

    /** Owner: a subscriber announced itself with the current link (ADR 0062 §11). */
    private suspend fun onSubscribe(ref: AnmpChannelRef, o: Opened) {
        val c = channel(ref.space)?.takeIf { it.owner && !it.curated } ?: return
        val current = runCatching { Grant.parse(c.grant) }.getOrNull() ?: return
        if (ref.readGrant?.contentEquals(current.hash.bytes) != true) return
        val content = o.content
        val inbox = content.inbox?.takeIf { it.size == 32 } ?: return
        val raw = content.grant ?: return
        val g = runCatching { Grant.decode(raw) }.getOrNull() ?: return
        val b = g.body
        if (b.space != Key(inbox) || !b.rights.has(Rights.WRITE) || b.scope != null || b.until <= nowS()) return
        if (runCatching { g.verifySig() }.isFailure) return
        val t = now()
        val name = content.name.orEmpty().trim().take(AnmpLinks.MAX_NAME)
        updateChannel(c.id) { ch ->
            val prev = ch.subscribers.firstOrNull { it.deviceId == o.fromDevice }
            if (prev == null && ch.subscribers.size >= AnmpAlerts.MAX_SUBSCRIBERS) return@updateChannel ch
            val sub = ChannelSubscriber(o.fromDevice, name, prev?.since ?: t, t, o.fromKey, B64.encode(inbox), B64.encode(raw))
            ch.copy(
                subscribers = ch.subscribers.filterNot { it.deviceId == o.fromDevice } + sub,
                unsubscribed = ch.unsubscribed.filterNot { it.id == o.fromDevice },
            )
        }
        acceptGrant(o.fromDevice, inbox, raw, null)
    }

    /** Owner: a subscriber left. */
    private suspend fun onUnsubscribe(ref: AnmpChannelRef, o: Opened) {
        val c = channel(ref.space)?.takeIf { it.owner } ?: return
        if (c.subscribers.none { it.deviceId == o.fromDevice }) return
        val t = now()
        updateChannel(c.id) { it.copy(subscribers = it.subscribers.filterNot { s -> s.deviceId == o.fromDevice }, unsubscribed = mergeGone(it.unsubscribed, listOf(SubscriberGone(o.fromDevice, t)))) }
    }

    /**
     * Subscriber: a new read key after the owner removed someone else (ADR 0062 §11). Taken only if the owner key signed
     * it and its read grant is newer than ours.
     */
    private suspend fun onChannelKey(ref: AnmpChannelRef) {
        val c = channel(ref.space)?.takeIf { !it.owner } ?: return
        val readKey = ref.readKey?.takeIf { it.size == 32 } ?: return
        val grantText = ref.grant ?: return
        val sig = ref.sig?.takeIf { it.size == 64 } ?: return
        val space = runCatching { Key.parse(c.id) }.getOrNull() ?: return
        if (!space.verify(AnmpAlerts.channelKeySigningBytes(c.id, readKey, grantText), app.anynear.andp.Sig(sig))) return
        val grant = runCatching { Grant.parse(grantText) }.getOrNull() ?: return
        val b = grant.body
        if (b.space != space || !b.rights.has(Rights.READ + Rights.LIST) || b.grantee != null || b.scope != null || b.until <= nowS()) return
        if (runCatching { grant.verifySig() }.isFailure) return
        val mine = runCatching { Grant.parse(c.grant) }.getOrNull()
        if (mine != null && b.issued <= mine.body.issued) return
        channelReaders.remove(c.id)
        updateChannel(c.id) { it.copy(readKey = B64.encode(readKey), grant = grantText, keyTs = now()) }
    }

    /** Joins tombstones of subscribers by device, the later time wins; at most [AnmpAlerts.MAX_SUBSCRIBERS]. */
    private fun mergeGone(a: List<SubscriberGone>, b: List<SubscriberGone>): List<SubscriberGone> =
        (a + b).groupBy { it.id }.map { (_, l) -> l.maxBy { it.ts } }.sortedBy { it.ts }.takeLast(AnmpAlerts.MAX_SUBSCRIBERS)

    /** Loads an attachment of an alert from its channel space. */
    suspend fun fetchAlertAttachment(alert: AlertItem, attachment: AnmpAttachment): BlobResult = onCore { fetchBlob(alert.channelId, attachment.id, attachment.key) }

    /**
     * An alert written before this device took its channel (with a minute of slack for clocks): shown, not notified.
     */
    private fun isHistory(item: AlertItem): Boolean {
        val c = channel(item.channelId) ?: return false
        val since = c.addedHere.takeIf { it > 0 } ?: c.joinedAt
        return item.ts * 1000 < since - HISTORY_SLACK_MS
    }

    /**
     * Opens and checks a listed entry of a channel (ADR 0062 §2 "Annehmen"): alert packet opens with the read key,
     * signature holds, the signer is the owner or wrote with a sender grant of the owner, `ts` at most 10 minutes ahead
     * and at most 30 days old, not yet known in this channel. [keep]: add it to [alerts]. `null` if not accepted;
     * otherwise the event with history and quiet update (ADR 0062 §4) worked out.
     */
    private suspend fun acceptAlert(l: Listed.Channel, meta: Meta, data: ByteArray, keep: Boolean): CoreEvent.Alert? {
        if (meta.placement != Placement.SPACE) return null
        val a = AnmpAlerts.open(data, l.readKey) ?: return null
        if (!AnmpAlerts.isValidIn(a.body, channel(l.channelId)?.curated == true)) {
            log.log("alert in channel ${l.channelId.take(8)}: regional keys outside a curated channel, dropped")
            return null
        }
        if (!ChannelRules.maySend(l.access.space, a.signer, meta)) {
            log.log("alert in channel ${l.channelId.take(8)}: sender not allowed, dropped")
            return null
        }
        if (!ChannelRules.fresh(a.body.ts, nowS())) return null
        val s = ChannelRules.storedOf(l.channelId, a, now())
        if (storedAlerts.value.any { it.key == s.key }) return null
        val list = if (keep) storedAlerts.updateAndGet { pruneAlerts(it + s) } else storedAlerts.value + s
        val item = ChannelRules.items(list, now()).firstOrNull { it.channelId == l.channelId && it.id == a.body.id.toHex() } ?: return null
        return CoreEvent.Alert(item, isHistory(item), ChannelRules.quiet(list, s))
    }

    /** Alerts younger than 30 days, at most [MAX_ALERTS], oldest first. */
    private fun pruneAlerts(list: List<StoredAlert>): List<StoredAlert> {
        val from = nowS() - AnmpAlerts.KEEP_SECONDS
        return list.filter { (it.bodyOrNull()?.ts ?: 0) >= from }.sortedBy { it.bodyOrNull()?.ts ?: 0 }.takeLast(MAX_ALERTS)
    }

    /** Recomputes the status whenever the next active alert expires, and drops alerts older than 30 days. */
    private suspend fun alertClock() {
        storedAlerts.collectLatest { list ->
            while (true) {
                val t = now()
                val next = ChannelRules.items(list, t).filter { it.status == AlertStatus.ACTIVE }.minOfOrNull { it.until * 1000 }
                delay(((next ?: (t + ALERT_CLOCK_MAX)) - t).coerceIn(1_000, ALERT_CLOCK_MAX).milliseconds)
                alertTick.update { it + 1 }
                if (list.any { (it.bodyOrNull()?.ts ?: 0) < nowS() - AnmpAlerts.KEEP_SECONDS }) {
                    storedAlerts.update { pruneAlerts(it) }
                    return@collectLatest
                }
            }
        }
    }

    private suspend fun alertCipher(): AnmpAead = AnmpAead.of(me.device.localStorageKey())

    /** Alerts stay on the device sealed with the local storage key (BlobStore holds only ciphertext). */
    private suspend fun loadAlerts() {
        val sealed = platform.blobs.read(ALERTS_BLOB) ?: return
        val plain = alertCipher().open(sealed, ALERTS_AAD.encodeToByteArray()) ?: return
        val list = runCatching { coreJson.decodeFromString(ListSerializer(StoredAlert.serializer()), plain.decodeToString()) }.getOrNull() ?: return
        storedAlerts.value = pruneAlerts(list)
    }

    private suspend fun saveAlerts(list: List<StoredAlert>) {
        val plain = coreJson.encodeToString(ListSerializer(StoredAlert.serializer()), list).encodeToByteArray()
        platform.blobs.write(ALERTS_BLOB, alertCipher().seal(plain, ALERTS_AAD.encodeToByteArray()))
    }

    // --- Software for radios (ADR 0056) ---------------------------------------------------------

    /** Key of the space with the software for radios; it also signs the list. `null` while it is not set up. */
    val radioSoftwareKey: String? get() = config.radioSoftwareSpace.takeIf { radioSoftware != null }

    /**
     * Every list of released radio software in the space, as written by anynear (signed envelopes with download links;
     * the caller checks the signature and takes the newest). `null` if it is not set up or no relay answered.
     */
    suspend fun radioSoftwareLists(): List<ByteArray>? {
        val shelf = radioSoftware ?: return null
        opened.await()
        return shelf.entries()
    }

    // --- Radio inbox (ADR 0051) ------------------------------------------------------------------

    /** `true` if the radio inbox is set up for this network; without it there is no uplink and no patron. */
    val hasRadioInbox: Boolean get() = radioInbox != null

    /**
     * Uplink: puts a radio packet in the public form that this app heard into the radio inbox, whoever it is for.
     * Best effort without a queue; `false` if it is no such packet, the inbox is not set up or no holder took it.
     */
    suspend fun uplinkRadioPacket(packet: ByteArray): Boolean {
        val inbox = radioInbox ?: return false
        opened.await()
        return inbox.put(packet)
    }

    /**
     * New radio packets in the radio inbox whose route tag passes [wanted] (own tags, or the tags a patron
     * serves), newest first, each once per start. Empty if the inbox is not set up.
     */
    suspend fun pollRadioInbox(wanted: (routeTag: ByteArray) -> Boolean): List<RadioInboxPacket> {
        val inbox = radioInbox ?: return emptyList()
        opened.await()
        return inbox.poll(wanted)
    }

    // --- Radio way (ADR 0057) --------------------------------------------------------------------

    private var radio: RadioWay? = null

    /**
     * Starts the radio way over [port] (ADR 0057): the core sorts, opens and answers what it hears, relays for others and
     * asks patrons when there is no internet (ADR 0051). Once, after [start].
     */
    suspend fun startRadio(port: RadioPort, host: RadioHost, timings: RadioTimings = RadioTimings()) {
        opened.await()
        radioLock.withLock { if (radio == null) createRadio(port, host, timings) }
    }

    /** Radio keys of this device once the radio way runs; tests use them to build packets an attacker could send. */
    internal var radioKeys: RadioKeys? = null
        private set

    private fun createRadio(port: RadioPort, host: RadioHost, timings: RadioTimings) {
        val keys = RadioKeys(me.device, me.mailbox) { nowS() }
        radioKeys = keys
        radio = RadioWay(cs, object : RadioCore {
            override val keys = keys

            override fun copyFromRadio(kind: AnmpKind, content: AnmpContent, fromDevice: String) = this@AnmpCore.copyFromRadio(kind, content, fromDevice)

            override fun radioPeer(deviceId: String): Peer? = targetOfDevice(deviceId)?.peer
                ?: state.members.firstOrNull { it.deviceId == deviceId }?.let { Peer(it.deviceId, it.publicKey) }

            override val circles: List<CircleState> get() = state.circles.filterNot { it.removed }
            override val members: List<MemberState> get() = state.members
            override val hasInbox: Boolean get() = radioInbox != null
            override val store: KeyValueStore get() = platform.store

            override fun nowMillis(): Long = now()

            override val ownNode: Long get() = state.profile.node

            override fun personOf(deviceId: String): String = this@AnmpCore.personOf(deviceId)

            override suspend fun learnNode(deviceId: String, node: Long) = this@AnmpCore.learnNode(deviceId, node)

            override suspend fun emit(event: CoreEvent) = this@AnmpCore.emit(event)

            override fun deleteEvent(conversation: Conversation, fromDevice: String, content: AnmpContent) =
                this@AnmpCore.deleteEvent(conversation, fromDevice, content)

            override suspend fun onAlive(circleId: String, fromDevice: String, mid: ByteArray?, ts: Long, alive: AnmpAlive): Boolean =
                withContext(serial) {
                    val circle = circle(circleId) ?: return@withContext false
                    val person = this@AnmpCore.personOf(fromDevice)
                    this@AnmpCore.onAlive(circle, fromDevice, person, mid, ts, alive, receipt = false) && person != ownPersonId
                }

            override suspend fun onCare(circleId: String, fromDevice: String, ts: Long, care: AnmpCare) = withContext(serial) {
                circle(circleId)?.let { this@AnmpCore.onCare(it, this@AnmpCore.personOf(fromDevice), ts, care) } ?: Unit
            }

            override suspend fun onReceipt(fromDevice: String, mids: List<String>) = withContext(serial) {
                onAliveReceipt(this@AnmpCore.personOf(fromDevice), mids)
            }

            override suspend fun uplink(packet: ByteArray): Boolean = uplinkRadioPacket(packet)

            override suspend fun poll(wanted: (ByteArray) -> Boolean): List<RadioInboxPacket> = pollRadioInbox(wanted)

            override fun log(line: String) = this@AnmpCore.log.log(line)
        }, port, host, timings).also { way ->
            way.start()
            cs.launch { way.servedByPatron.collect { _servedByPatron.value = it } }
        }
    }

    private val _servedByPatron = MutableStateFlow(false)

    /** A patron answered the last request: messages reach this device over radio (ADR 0051). */
    val servedByPatron: StateFlow<Boolean> = _servedByPatron.asStateFlow()

    /**
     * A radio check to [node] was answered (ADR 0053): own messages over radio that wait for that person or circle may
     * go again (ADR 0090).
     */
    suspend fun radioHeard(node: Long) {
        radio?.radioHeard(node.toInt())
    }

    /** The app came to the foreground: a device without internet asks at once (ADR 0051). */
    fun radioForeground() {
        radio?.onForeground()
    }

    /**
     * Radio packet of an own message [mid] with [text] at [tsMillis] in circle [circleId], or direct to the device
     * [deviceId] if [circleId] is `null`. The app queues it until the radio takes it ([sendRadioPacket]). `null` without
     * radio way, for unknown circles or devices and if it does not fit.
     */
    suspend fun radioTextPacket(
        circleId: String?, deviceId: String?, mid: String, tsMillis: Long, text: String, replyTo: String? = null, replyFrom: String? = null,
    ): ByteArray? = radio?.textPacket(circleId, deviceId, mid, tsMillis, text, replyTo, replyFrom)

    /**
     * Queues an own message [mid] with [text] at [tsMillis] for the radio, in circle [circleId] or direct to the device
     * [deviceId] (ADR 0057 step 5). The queue survives restarts; it sends when the radio is ready, as long as
     * [RadioHost.stillWanted], and reports states as [CoreEvent.RadioSend]. Before [startRadio] it waits in the store.
     * A direct message is sealed for one device; for a person with several devices call it once per device (one entry
     * per message and device).
     */
    suspend fun queueRadioText(
        circleId: String?, deviceId: String?, mid: String, tsMillis: Long, text: String, replyTo: String? = null, replyFrom: String? = null,
    ) {
        val entry = RadioQueued(mid, circleId, deviceId, tsMillis, text, replyTo = replyTo, replyFrom = replyFrom)
        radioLock.withLock {
            radio?.queueText(entry) ?: RadioQueue.save(platform.store, RadioQueue.load(platform.store).filterNot { it.key == entry.key } + entry)
        }
    }

    private val radioLock = Mutex()

    /**
     * The person deleted own message [mid], for themselves or for everyone (ADR 0068): it no longer goes out on any way.
     * Pending writes over the internet are withdrawn, the entries in the radio queue taken out, also before the radio
     * way runs. What a relay or a radio already has is not called back here (that is [deleteMessage]).
     */
    suspend fun cancelSend(mid: String) {
        transport.cancel(mid)
        radioLock.withLock {
            radio?.cancelText(mid) ?: RadioQueue.save(platform.store, RadioQueue.load(platform.store).filterNot { it.mid == mid })
        }
    }

    /** Sends a packet from [radioTextPacket] to everyone on the radio channel. `null` if nothing went out. */
    suspend fun sendRadioPacket(packet: ByteArray): RadioSent? = radio?.sendPacket(packet)

    /** Longest text in bytes for a message over radio in circle [circleId] (always signed), or direct if `null`. */
    fun radioTextLimit(circleId: String?): Int {
        val ts = nowS()
        val sample = { text: String -> AnmpContent(id = ByteArray(Anmp.ID_BYTES), ts = ts, text = text) }
        return if (circleId != null) {
            Anmp.maxTextBytes(RadioKeys.budgetHeader(AnmpKind.CIRCLE, circle(circleId)?.epoch ?: 0), signed = true, content = sample)
        } else {
            Anmp.maxTextBytes(RadioKeys.budgetHeader(AnmpKind.DIRECT, null), signed = false, content = sample)
        }
    }

    /** Circle packets over radio are always signed now; [signed] is ignored. */
    @Deprecated("Circle packets over radio are always signed.", ReplaceWith("radioTextLimit(circleId)"))
    fun radioTextLimit(circleId: String?, @Suppress("UNUSED_PARAMETER") signed: Boolean): Int = radioTextLimit(circleId)

    /** Own reaction over radio, see [RadioWay.react]. `null` if nothing went out. */
    suspend fun radioReact(circleId: String?, deviceId: String?, targetTag: String, targetMid: String, emoji: String, tsMillis: Long): RadioSent? =
        radio?.react(circleId, deviceId, targetTag, targetMid, emoji, tsMillis)

    /** Own withdrawal of [targetMid] over radio (ADR 0068), like [radioReact]. `null` if nothing went out. */
    suspend fun radioDelete(circleId: String?, deviceId: String?, targetTag: String, targetMid: String, tsMillis: Long): RadioSent? =
        radio?.delete(circleId, deviceId, targetTag, targetMid, tsMillis)

    /** Read receipt over radio for [mids] of the device [deviceId] (as many as fit, the last ones). `null` if nothing went out. */
    suspend fun radioRead(deviceId: String, mids: List<String>): RadioSent? = radio?.read(deviceId, mids)

    // --- §6b: apps put missing entries back --------------------------------------------------------

    private class SeenEntry(val meta: Meta, val data: ByteArray, val firstSeen: Long, val relays: MutableSet<Key>, val tried: MutableSet<Key> = mutableSetOf())

    private val trackLock = Mutex()
    private val tracked = mutableMapOf<String, LinkedHashMap<String, SeenEntry>>()
    private val trackedListed = mutableMapOf<String, Listed>()

    private suspend fun track(l: Listed, relay: Relay, meta: Meta, data: ByteArray?) = trackLock.withLock {
        if (meta.writer == me.writer.key) return@withLock
        val map = tracked.getOrPut(l.access.name) { LinkedHashMap() }
        trackedListed[l.access.name] = l
        val e = map[meta.id.toString()] ?: data?.let { SeenEntry(meta, it, now(), mutableSetOf()) }?.also { map[meta.id.toString()] = it } ?: return@withLock
        e.relays += relay.node
        while (map.size > MAX_TRACKED) map.remove(map.keys.first())
    }

    private suspend fun reseedLoop() {
        opened.await()
        while (true) {
            delay(timings.reseedCheck)
            runCatching { reseedMissing() }.onFailure { if (it is CancellationException) throw it }
        }
    }

    /**
     * Entries that a chosen relay, reachable for [CoreTimings.reseedAfter], did not deliver: put them there again,
     * in a circle by the member first in rank (ADR 0028 §6b level 1).
     */
    private suspend fun reseedMissing() {
        val t = now()
        val after = timings.reseedAfter.inWholeMilliseconds
        val due = mutableListOf<Triple<Listed, SeenEntry, Relay>>()
        trackLock.withLock {
            for ((space, map) in tracked) {
                map.values.removeAll { it.meta.isExpired(t / 1000) }
                val l = trackedListed[space] ?: continue
                val targets = net.holders(space)
                for (e in map.values) for (r in targets) {
                    val since = pool.reachableSince(r.node) ?: continue
                    if (r.node in e.relays || r.node in e.tried) continue
                    if (t - maxOf(e.firstSeen, since) < after) continue
                    e.tried += r.node
                    due += Triple(l, e, r)
                }
            }
        }
        for ((l, e, r) in due) {
            cs.launch {
                if (l is Listed.Circle) {
                    val rank = duty.rank("seed/${e.meta.id}/${r.node}", me.deviceId, membersOf(l.circleId).map { it.deviceId })
                    delay(duty.delayFor(rank))
                }
                if (trackLock.withLock { r.node in e.relays }) return@launch
                if (transport.reput(r, l.access, e.meta, e.data)) trackLock.withLock { e.relays += r.node }
            }
        }
    }

    // --- Maintenance ---------------------------------------------------------------------------

    /** Purges removed circles after their grace period; renews grants before they expire. */
    private suspend fun maintenance() {
        opened.await()
        while (true) {
            val t = now()
            state.circles.filter { it.removed && it.graceUntil < t }.forEach { dropCircle(it.id) }
            update { s -> s.copy(grants = s.grants.filter { it.until > t / 1000 }, issued = s.issued.filter { it.until > t / 1000 }) }
            // Contacts whose personal grant runs out get a new one.
            val renew = state.members.filter { it.circleId == MemberState.CONTACTS }.map { it.deviceId }.distinct().filter { d ->
                state.issued.none { it.purpose == contactPurpose(d) && !it.revoked && it.until - t / 1000 > timings.grantRenewBefore.inWholeSeconds }
            }
            renew.forEach { runCatching { sendProfileTo(it) } }
            expireRequests()
            if (state.storeCleanups.isNotEmpty()) cs.launch { cleanUpStores() }
            delay(timings.purgeCheck)
        }
    }

    // --- watch (iOS wake-up, ANDP §10) ------------------------------------------------------------

    /**
     * Push target of this device (APNs token as hex, topic, environment) or `null`. The core registers it for the
     * own mailbox and every circle space at their holders and skips its own watch ID on its writes.
     */
    fun setPushTarget(token: String?, topic: String = "", sandbox: Boolean = false) {
        pushTarget.value = token?.let { WatchTarget("apns", it.lowercase(), topic, if (sandbox) "sandbox" else "production") }
    }

    /**
     * Web Push of the web client (ANDP §10.1, ADR 0060): [endpoint] is the browser's push subscription URL, `null`
     * deregisters. Like [setPushTarget], the relays then wake the browser on every new entry of the own spaces.
     */
    fun setWebPushTarget(endpoint: String?) {
        pushTarget.value = endpoint?.let { e -> WatchTarget(Andp.EXTENSION_WEBPUSH, e.encodeToByteArray().joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }) }
    }

    /** VAPID public key (b64u) of the relays for a browser push subscription; `null` if no relay offers Web Push. */
    suspend fun webPushKey(): String? {
        opened.await()
        // Right after the start the network list may still be on its way.
        withTimeoutOrNull(WEB_PUSH_KEY_WAIT) { net.list.first { it != null } }
        for (r in net.active) {
            val info = pool.info(r) ?: continue
            if (info.hasExtension(Andp.EXTENSION_WEBPUSH) && info.webpush.isNotEmpty()) return info.webpush
        }
        return null
    }

    /** Own watch ID for ANDP-Watch-Skip: the push token (iOS, browser), else the stream key (ADR 0072). */
    private fun ownWatchId(): String = pushTarget.value?.let { runCatching { watchIdForHexToken(it.token) }.getOrNull() }
        ?: streamKey?.let { watchId(it.key.bytes) }.orEmpty()

    private suspend fun watchLoop() {
        opened.await()
        var previous: WatchTarget? = null
        combine(pushTarget, _state.map { s -> s.circles.filterNot { it.removed }.map { it.secret } + s.channels.map { it.id + it.grant } }.distinctUntilChanged(), net.list) { t, _, _ -> t }
            .collectLatest { target ->
                val old = previous
                if (old != null && old != target) registerWatch(old, remove = true)
                previous = target
                if (target == null) return@collectLatest
                while (true) {
                    registerWatch(target, remove = false)
                    delay(timings.watchRefresh)
                }
            }
    }

    private suspend fun registerWatch(target: WatchTarget, remove: Boolean) {
        val spaces = listedSpaces(state).filterNot { it is Listed.Circle && it.previous }
        for (l in spaces) watchSpace(l.access, target, remove)
    }

    private suspend fun watchSpace(a: SpaceAccess, target: WatchTarget, remove: Boolean) {
        for (r in net.holders(a.name)) {
            val info = pool.info(r) ?: continue
            if (!info.hasExtension(app.anynear.andp.Andp.EXTENSION_WATCH)) continue
            if (target.type == Andp.EXTENSION_WEBPUSH && !info.hasExtension(Andp.EXTENSION_WEBPUSH)) continue
            runCatching { pool.client(r)?.watch(a.cred, WatchRequest(a.space, target, remove)) }
        }
    }

    /**
     * Work of the iOS notification extension after a wake-up (replaces `WakeFetcher`): every space at its holders
     * once without waiting, circle and direct messages and reactions ([CoreEvent.Message], [CoreEvent.Reaction]),
     * new alerts with their priority ([CoreEvent.Alert], ADR 0062) and signs of life of others ([CoreEvent.AliveWake])
     * opened, nothing stored or emitted. Own cursors (`core.wake.…`) so that the app later fetches the same
     * objects itself. Call [open] first.
     *
     * The receipts for the messages found go out right away and unbatched ([sendWakeReceipts]): the extension ends as
     * soon as the notification is shown, a batched receipt would never leave, and the sender would see "arrived" only
     * once the phone is unlocked and the app fetches itself.
     */
    suspend fun wakeFetch(): List<CoreEvent> = wakeFetchResult().events

    /**
     * Like [wakeFetch], and says whether the result is complete ([WakeFetch.complete]): every space answered at at least
     * one of its holders. Only then does "nothing found" mean that the wake-up had nothing to show (an own message from
     * another device, an alert outside the region, a duplicate wake-up of another holder); the extension may then
     * swallow it (ADR 0117). Without an answer it must keep the fixed text, a message may be waiting.
     */
    suspend fun wakeFetchResult(): WakeFetch {
        val result = wakeFetchFound()
        sendWakeReceipts(result.events.filterIsInstance<CoreEvent.Message>())
        return result
    }

    /**
     * Receipts for messages of other people found in [wakeFetch], one packet per device and at most
     * [WAKE_RECEIPT_TIMEOUT], so that the notification still gets its text. A later receipt of the app for the same
     * message changes nothing for the sender.
     */
    private suspend fun sendWakeReceipts(messages: List<CoreEvent.Message>) {
        val byDevice = messages.filter { it.mid != null && !it.history && personOf(it.fromDevice) != ownPersonId }
            .groupBy({ it.fromDevice }, { it.mid!! })
        if (byDevice.isEmpty()) return
        withTimeoutOrNull(WAKE_RECEIPT_TIMEOUT) {
            for ((device, mids) in byDevice) {
                val raw = mids.distinct().mapNotNull { runCatching { B64.decode(it) }.getOrNull()?.takeIf { b -> b.size == Anmp.ID_BYTES } }
                if (raw.isEmpty()) continue
                runCatching { postPerson(personOf(device), AnmpKind.RECEIPT, content(ids = raw, arrived = AnmpArrived.INTERNET)) }
                    .onFailure { if (it is CancellationException) throw it }
            }
        }
    }

    private suspend fun wakeFetchFound(): WakeFetch = onCore {
        opened.await()
        val found = mutableMapOf<String, CoreEvent>()
        // Objects reported by earlier wake-ups: another holder may list the same object later (sent counts from the
        // first relay), and it must not notify twice.
        val seen = platform.store.getString(WAKE_SEEN_KEY).orEmpty().split(' ').filter { it.isNotEmpty() }
        var complete = true
        for (l in listedSpaces(state).filterNot { it is Listed.Circle && it.previous }) {
            var answered = false
            for (r in net.holders(l.access.name)) {
                val key = cursorKey(l.access.name, r, "core.wake")
                val after = platform.store.getString(key) ?: platform.store.getString(cursorKey(l.access.name, r)).orEmpty()
                val page = runCatching { pool.client(r)?.list(l.access.cred, l.access.space, ListOptions(after, 0)) }.getOrNull() ?: continue
                answered = true
                if (page.next.isNotEmpty()) platform.store.putString(key, page.next)
                for (o in page.objects) {
                    val id = o.id.toString()
                    if (id in found || id in seen) continue
                    // Listed but not readable: a message may be in it, so the result is not complete.
                    val data = entryBytes(l, r, o) ?: run { complete = false; null } ?: continue
                    if (l is Listed.Channel) {
                        val event = runCatching { acceptAlert(l, o.meta(l.access.space), data, keep = false) }.getOrNull() ?: continue
                        val item = event.item
                        if (found.values.none { it is CoreEvent.Alert && it.item.channelId == item.channelId && it.item.id == item.id }) found[id] = event
                        continue
                    }
                    val packet = openEnvelope(l, data) ?: continue
                    val p = Packets.open(me, packet, l.scope, (l as? Listed.Circle)?.keys, ::peerByTag) ?: continue
                    if (isRevoked(p.fromDevice)) continue
                    if (p.fromDevice == me.deviceId) continue
                    // Written on another own device (circle message, reaction): nothing to ring for.
                    if (personOf(p.fromDevice) == ownPersonId) continue
                    val pinned = pinnedKey(p.fromDevice)
                    if (pinned != null && pinned != p.fromKey) continue
                    val msg = when {
                        l is Listed.Circle && p.kind == AnmpKind.CIRCLE && !p.toUs -> messageEvent(Conversation.Circle(l.circleId), id, l.access.name, p)
                        l is Listed.Inbox && p.kind == AnmpKind.DIRECT && p.toUs && personOf(p.fromDevice) != ownPersonId ->
                            messageEvent(Conversation.Direct(personOf(p.fromDevice)), id, l.access.name, p, intro = introOf(p, personOf(p.fromDevice)))
                        l is Listed.Circle && p.kind == AnmpKind.REACT && !p.toUs -> reactionEvent(Conversation.Circle(l.circleId), p, id)
                        l is Listed.Inbox && p.kind == AnmpKind.REACT && p.toUs && personOf(p.fromDevice) != ownPersonId ->
                            reactionEvent(Conversation.Direct(personOf(p.fromDevice)), p, id)
                        // Sign of life of another person in the circle (ADR 0083); the app decides what to show.
                        l is Listed.Circle && p.kind == AnmpKind.ALIVE && !p.toUs -> p.content.alive?.takeIf { AliveLogic.valid(it) }?.let {
                            CoreEvent.AliveWake(id, l.circleId, personOf(p.fromDevice), it, maxOf(p.content.ts, it.start) * 1000)
                        }
                        else -> null
                    }
                    msg?.let { found[id] = it }
                }
            }
            if (!answered) complete = false
        }
        if (found.isNotEmpty()) platform.store.putString(WAKE_SEEN_KEY, (seen + found.keys).takeLast(WAKE_SEEN_MAX).joinToString(" "))
        val events = found.values.sortedBy {
            when (it) {
                is CoreEvent.Message -> it.timestampMillis
                is CoreEvent.Reaction -> it.timestampMillis
                is CoreEvent.Alert -> it.item.ts * 1000
                is CoreEvent.AliveWake -> it.timestampMillis
                else -> 0L
            }
        }
        return@onCore WakeFetch(events, complete)
    }

    // --- Snapshot for the own other devices (ADR 0042 §4) ---------------------------------------------

    private suspend fun addTombstone(id: String) {
        val t = now()
        update { s -> s.copy(tombstones = (s.tombstones.filterNot { it.id == id } + Tombstone(id, t)).filter { t - it.ts < TOMBSTONE_KEEP }) }
    }

    /** Sends a snapshot to the own other devices whenever circles, contacts, channels, name or tombstones change. */
    private suspend fun syncOnChange() {
        _state.map { s ->
            listOf(
                s.circles.filterNot { it.removed }.map { it.id to it.secret }, s.members.filter { it.circleId == MemberState.CONTACTS }.map { it.deviceId },
                s.profile.name, s.tombstones, s.ownProof, s.channels.map { it.copy(addedHere = 0) }, s.sentRequests,
                s.issued.filter { it.purpose == PUBLIC_LINK && !it.revoked }.map { it.hash },
            )
        }
            .distinctUntilChanged()
            .debounce(timings.receiptBatch)
            .collect {
                if (revokedHere) return@collect
                if (ownOthers().isNotEmpty()) runCatching { postOwn(AnmpKind.SYNC, content(sync = snapshot(), withChain = true)) }
                runCatching { backupState() }
            }
    }

    private suspend fun snapshot(): AnmpSync {
        warmProofs()
        val s = state
        val circles = s.circles.filterNot { it.removed }.map {
            AnmpSyncCircle(it.id, it.name, B64.decode(it.secret), it.epoch, it.epochTs, it.epochBy.takeIf { b -> b.isNotEmpty() }?.let(B64::decode))
        }
        val contacts = s.members.filter { it.circleId == MemberState.CONTACTS && it.signKey != null }.map { m ->
            AnmpSyncContact(
                m.name, B64.decode(m.signKey!!), B64.decode(m.publicKey), m.inbox?.let(B64::decode), m.personId?.let { personState(it) }?.proof?.let(B64::decode),
                m.addedAt,
            )
        }
        val channels = s.channels.map { c ->
            AnmpSyncChannel(
                c.id, c.name, B64.decode(c.readKey), c.grant, c.joinedAt, c.curated, c.ownerSeed?.let(B64::decode), c.nameTs, c.keyTs,
                c.ingress.mapNotNull { i ->
                    AnmpAlerts.tokenBytes(i.token)?.let { AnmpSyncIngress(it, i.label, i.created, B64.decode(i.senderSeed), i.senderGrant, i.entryId) }
                },
                c.revokedIngress,
                c.subscribers.map { sub ->
                    AnmpSyncSubscriber(B64.decode(sub.deviceId), sub.name, B64.decode(sub.publicKey), B64.decode(sub.inbox), B64.decode(sub.grant), sub.since, sub.ts)
                },
                c.unsubscribed.map { AnmpSyncGone(it.id, it.ts) },
                c.join?.let { j -> AnmpChannelJoin(B64.decode(j.deviceId), B64.decode(j.publicKey), B64.decode(j.inbox), B64.decode(j.grant)) },
            )
        }
        val asked = s.sentRequests.map { AnmpSyncAsked(it.person, it.name, it.deviceId, it.introducedBy, it.ts, it.until) }
        val t = nowS()
        val links = s.issued.filter { it.purpose == PUBLIC_LINK && !it.revoked && it.until > t }.map { it.grant }
        return AnmpSync(now(), s.profile.name.takeIf { it.isNotBlank() }, circles, contacts, s.tombstones.map { AnmpSyncGone(it.id, it.ts) }, channels, asked, links)
    }

    /** Takes from a snapshot what this device does not have; drops what was left or removed there. */
    private suspend fun applySync(sync: AnmpSync) {
        for (g in sync.gone) {
            if (state.tombstones.any { it.id == g.id && it.ts >= g.ts }) continue
            update { s -> s.copy(tombstones = s.tombstones.filterNot { it.id == g.id } + Tombstone(g.id, g.ts)) }
            circle(g.id)?.takeIf { it.joinedAt < g.ts }?.let { revoke("circle:${it.id}:"); dropCircle(it.id) }
            // The device that left an own channel already revoked its device addresses.
            channel(g.id)?.takeIf { it.joinedAt < g.ts }?.let { forgetChannel(it) }
            val devices = state.members.filter { it.circleId == MemberState.CONTACTS && it.person == g.id }.map { it.deviceId }
            devices.forEach { forgetMember(MemberState.CONTACTS, it); revoke(contactPurpose(it)) }
            // Published link withdrawn on another own device: its grant no longer makes a writer a contact here.
            g.id.removePrefix(REVOKED_LINK).takeIf { g.id.startsWith(REVOKED_LINK) }?.let { hash ->
                update { s -> s.copy(issued = s.issued.map { if (it.hash == hash && it.purpose == PUBLIC_LINK) it.copy(revoked = true) else it }) }
            }
            // Declined on another own device (ADR 0116).
            g.id.removePrefix(DECLINED).takeIf { g.id.startsWith(DECLINED) }?.let { declined ->
                if (state.requests.any { it.person == declined }) {
                    update { s -> s.copy(requests = s.requests.filterNot { it.person == declined }) }
                    emit(CoreEvent.ContactRequestsChanged())
                }
            }
        }
        for (c in sync.circles) {
            if (c.secret.size != 32 || state.tombstones.any { it.id == c.id && it.ts > sync.ts }) continue
            val known = circle(c.id)
            if (known != null && !known.removed && (known.epoch >= c.epoch)) continue
            if (known != null && !known.removed) continue // a key change reaches this device through the circle itself
            val joined = CircleState(
                id = c.id, name = c.name.take(AnmpLinks.MAX_NAME), secret = B64.encode(c.secret), epoch = c.epoch, epochTs = c.epochTs,
                epochBy = c.epochBy?.let(B64::encode).orEmpty(), joinedAt = now(), historyPending = true,
            )
            update { s -> s.copy(circles = s.circles.filterNot { it.id == c.id } + joined, historyOffered = s.historyOffered - c.id) }
            emit(CoreEvent.CircleUpdated(joined))
        }
        // Requests the own person sent on another device (ADR 0116): their answer is let in here too.
        val t = now()
        val asked = sync.asked.filter { it.until > t && it.person.isNotEmpty() && state.sentRequests.none { s -> s.person == it.person } }
            .map { SentRequest(it.person, it.name.take(AnmpLinks.MAX_NAME), it.device, it.by, it.ts, it.until) }
        if (asked.isNotEmpty()) {
            update { s -> s.copy(sentRequests = s.sentRequests + asked) }
            emit(CoreEvent.ContactRequestsChanged())
            state.requests.filter { sentRequestOf(it.person, it.deviceId) != null }.forEach { takeRequest(it, answer = false) }
        }
        for (c in sync.contacts) {
            if (c.signKey.size != 32 || c.key.size != 32) continue
            val device = B64.encode(AnmpKeys.deviceIdOf(c.signKey))
            if (device == me.deviceId || isRevoked(device)) continue
            val chain = c.person?.let { AnmpPersons.verify(it) }?.takeIf { it.device(B64.decode(device)) != null }
            val pid = chain?.personIdText
            if (state.tombstones.any { it.id == (pid ?: device) && it.ts > sync.ts }) continue
            if (chain != null && personState(chain.personIdText) == null) {
                update { s -> s.copy(persons = s.persons + PersonState(chain.personIdText, B64.encode(chain.proof), chain.cert.n, chain.list.seq, chain.list.revoked.map(B64::encode))) }
            }
            if (member(MemberState.CONTACTS, device) != null) continue
            upsertMember(
                MemberState(
                    MemberState.CONTACTS, device, c.name.take(AnmpLinks.MAX_NAME), B64.encode(c.key), B64.encode(c.signKey),
                    inbox = c.inbox?.takeIf { it.size == 32 }?.let(B64::encode), personId = pid,
                    // Older devices send no time, and 0 is unknown there too: such a contact is not new, keep it unknown.
                    addedAt = c.since.takeIf { it in 1..now() } ?: ADDED_UNKNOWN,
                ),
            )
        }
        takePublicLinks(sync.links)
        for (c in sync.channels) runCatching { takeChannel(c, sync.ts) }.onFailure { e -> if (e is CancellationException) throw e }
        sync.name?.takeIf { it.isNotBlank() && state.profile.name.isBlank() }?.let { n -> update { s -> s.copy(profile = s.profile.copy(name = n.take(AnmpLinks.MAX_NAME))) } }
        warmProofs()
    }

    /**
     * Grants of published contact links made on another own device (ADR 0084 §6): only write grants for anyone on our
     * own mailbox, signed by its owner and not expired. Kept like our own, so [viaPublicLink] knows them here too.
     */
    private suspend fun takePublicLinks(wires: List<String>) {
        val t = nowS()
        val fresh = wires.distinct().mapNotNull { wire ->
            val g = runCatching { Grant.parse(wire) }.getOrNull() ?: return@mapNotNull null
            val b = g.body
            if (b.space != me.inbox || !b.rights.has(Rights.WRITE) || b.grantee != null || b.scope != null || b.until <= t) return@mapNotNull null
            if (runCatching { g.verifySig() }.isFailure) return@mapNotNull null
            val hash = g.hash.toString()
            if (state.issued.any { it.hash == hash } || state.tombstones.any { it.id == REVOKED_LINK + hash }) return@mapNotNull null
            IssuedGrant(PUBLIC_LINK, g.toString(), hash, b.until)
        }
        if (fresh.isNotEmpty()) update { s -> s.copy(issued = s.issued + fresh) }
    }

    /**
     * Takes a channel of a snapshot (ADR 0062): unknown ones are subscribed (polling starts at once, older alerts come
     * as history), known ones merged by ID: the newer name and the newer read key win, device addresses are joined by ID
     * and revoked ones stay revoked. Skipped if it was left here after it was subscribed there.
     */
    private suspend fun takeChannel(sc: AnmpSyncChannel, syncTs: Long) {
        val space = runCatching { Key.parse(sc.id) }.getOrNull() ?: return
        if (sc.readKey.size != 32) return
        val grant = runCatching { Grant.parse(sc.grant) }.getOrNull() ?: return
        val b = grant.body
        if (b.space != space || !b.rights.has(Rights.READ + Rights.LIST) || b.grantee != null || b.scope != null) return
        val ownerSeed = sc.ownerSeed?.let { seed ->
            if (seed.size != 32 || SigningKey.fromSeed(seed).key != space) return
            B64.encode(seed)
        }
        val joinedAt = sc.joinedAt.takeIf { it > 0 } ?: syncTs
        if (state.tombstones.any { it.id == sc.id && it.ts >= joinedAt }) return
        val revoked = sc.revoked.takeLast(MAX_REVOKED_INGRESS)
        val ingress = if (ownerSeed == null) emptyList() else sc.ingress.mapNotNull { ingressOf(it, space) }.filterNot { it.id in revoked }
        val name = sc.name.trim().take(AnmpAlerts.MAX_NAME)
        val gone = sc.unsubscribed.map { SubscriberGone(it.id, it.ts) }
        val subs = if (ownerSeed == null) emptyList() else sc.subscribers.mapNotNull { s ->
            if (s.device.size != Anmp.DEVICE_BYTES || s.key.size != 32 || s.inbox.size != 32 || s.grant.isEmpty()) return@mapNotNull null
            ChannelSubscriber(B64.encode(s.device), s.name.trim().take(AnmpLinks.MAX_NAME), s.since, s.ts, B64.encode(s.key), B64.encode(s.inbox), B64.encode(s.grant))
        }
        val join = sc.join?.takeIf { it.isValid && ownerSeed == null }?.let { j -> ChannelJoin(B64.encode(j.device), B64.encode(j.key), B64.encode(j.inbox), B64.encode(j.grant)) }
        val known = channel(sc.id)
        if (known == null) {
            val c = ChannelState(
                sc.id, name, owner = ownerSeed != null, curated = sc.curated && sc.id in curatedSpaces, ingress = ingress,
                readKey = B64.encode(sc.readKey), grant = sc.grant, ownerSeed = ownerSeed, joinedAt = joinedAt, nameTs = sc.nameTs,
                keyTs = sc.keyTs, revokedIngress = revoked, addedHere = now(),
                subscribers = mergeSubscribers(emptyList(), subs, gone), unsubscribed = mergeGone(emptyList(), gone), join = join,
                // The device that subscribed already announced itself.
                announced = if (join != null) now() else 0,
            )
            update { s -> s.copy(channels = s.channels.filterNot { it.id == c.id } + c) }
            ensureJoinGrant(c)
            return
        }
        val newerKey = sc.keyTs > known.keyTs
        val allRevoked = (known.revokedIngress + revoked).distinct().takeLast(MAX_REVOKED_INGRESS)
        val theirs = ingress.associateBy { it.id }
        val mine = known.ingress.map { i -> theirs[i.id]?.takeIf { newerKey } ?: i }
        var next = known.copy(
            ingress = (mine + ingress.filter { i -> known.ingress.none { it.id == i.id } }).filterNot { it.id in allRevoked },
            revokedIngress = allRevoked,
        )
        if (sc.nameTs > known.nameTs) next = next.copy(name = name, nameTs = sc.nameTs)
        if (newerKey) next = next.copy(readKey = B64.encode(sc.readKey), grant = sc.grant, keyTs = sc.keyTs)
        if (ownerSeed != null && !known.owner) next = next.copy(owner = true, ownerSeed = ownerSeed)
        if (next.owner) {
            val allGone = mergeGone(known.unsubscribed, gone)
            next = next.copy(subscribers = mergeSubscribers(known.subscribers, subs, allGone), unsubscribed = allGone)
        } else if (known.join == null && join != null) {
            next = next.copy(join = join, announced = now())
        }
        if (next == known) return
        if (newerKey || next.owner != known.owner) {
            channelReaders.remove(known.id)
            channelOwners.remove(known.id)
        }
        update { s -> s.copy(channels = s.channels.map { if (it.id == known.id) next else it }) }
        if (next.owner && !known.owner) ensureJoinGrant(next)
    }

    /** Subscribers of two own devices joined by device: the later announcement wins, minus those removed or left after it. */
    private fun mergeSubscribers(a: List<ChannelSubscriber>, b: List<ChannelSubscriber>, gone: List<SubscriberGone>): List<ChannelSubscriber> {
        val goneAt = gone.associate { it.id to it.ts }
        return (a + b).groupBy { it.deviceId }
            .map { (_, l) -> l.maxBy { it.ts }.copy(since = l.minOf { it.since }) }
            .filter { s -> (goneAt[s.deviceId] ?: -1) < s.ts }
            .sortedBy { it.since }
            .take(AnmpAlerts.MAX_SUBSCRIBERS)
    }

    /** A device address from a snapshot; `null` unless token, sender seed and sender grant fit together and to [space]. */
    private suspend fun ingressOf(si: AnmpSyncIngress, space: Key): Ingress? {
        val token = AnmpAlerts.tokenBytes(B64.encode(si.token))?.let(B64::encode) ?: return null
        if (si.senderSeed.size != 32) return null
        val sender = SigningKey.fromSeed(si.senderSeed)
        val g = runCatching { Grant.parse(si.senderGrant) }.getOrNull() ?: return null
        if (g.body.space != space || g.body.grantee != sender.key || !g.body.rights.has(Rights.WRITE)) return null
        return Ingress(
            sender.key.bytes.toHex(), si.label.trim().take(MAX_LABEL), AnmpAlerts.ingressUrl(config.ingressHost, token), si.created, token,
            g.hash.toString(), si.entryId, B64.encode(si.senderSeed), si.senderGrant,
        )
    }

    // --- Emergency card, Kern-space and state space (ADR 0042 §2, §6) ------------------------------------

    private var recoveryExtras: Map<String, ByteArray> = emptyMap()

    /**
     * `true` once an emergency card exists: the root key lies only in the Kern-space (also on a device that was added
     * or recovered later, which never holds the root key but the state secret).
     */
    /**
     * `true` while the root key is still on this device (emergency card not yet confirmed): [renewPersonKey] then works
     * without the code.
     */
    val canRenewWithoutCode: Boolean get() = platform.secrets.get(CoreIdentity.ROOT) != null

    val hasEmergencyCard: Boolean get() = state.cardTs > 0 || (platform.secrets.get(CoreIdentity.ROOT) == null && platform.secrets.get(STATE_SECRET) != null)

    /**
     * Secrets of the app that a recovered device needs (e.g. the password key), kept in the state space. Names that are
     * not in [extras] keep the value an own device put there before.
     */
    suspend fun setRecoveryExtras(extras: Map<String, ByteArray>): Unit = onCore {
        recoveryExtras = extras
        runCatching { backupState() }
    }

    private suspend fun kernSpace(code: ByteArray) = SealedSpace.of(code, AnmpInfo.CORE_SIGN, AnmpInfo.CORE_AEAD, net, pool, transport, me.inboxEnvelope)

    private suspend fun stateSpace(secret: ByteArray) = SealedSpace.of(secret, AnmpInfo.STATE_SIGN, AnmpInfo.STATE_AEAD, net, pool, transport, me.inboxEnvelope)

    /** Newest entry of the Kern-space of [code]; `null` if none or no relay answered. */
    private suspend fun loadKern(code: String): KernEntry? {
        val raw = RecoveryCode.parse(code) ?: return null
        return kernSpace(raw).read().entries.values.mapNotNull { runCatching { coreCbor.decodeFromByteArray(KernEntry.serializer(), it) }.getOrNull() }
            .filter { it.root.size == 32 && it.state.size == 32 }.maxByOrNull { it.ts }
    }

    /** Writes the current state into the state space and deletes older entries there. */
    private suspend fun backupState() {
        val secret = platform.secrets.get(STATE_SECRET) ?: return
        val sec = platform.secrets
        val proof = own?.proof ?: return
        val sp = stateSpace(secret)
        val read = sp.read()
        val older = read.entries.keys
        // Extras another own device put there (e.g. the password key) stay until this device sets them itself.
        val kept = read.entries.values.mapNotNull { runCatching { coreCbor.decodeFromByteArray(StateEntry.serializer(), it) }.getOrNull() }
            .maxByOrNull { it.ts }?.extras.orEmpty()
        val entry = StateEntry(now(), proof, sec.get(CoreIdentity.PERSON) ?: return, sec.get(CoreIdentity.INBOX) ?: return, sec.get(StoreKeys.SECRET), snapshot(), kept + recoveryExtras)
        sp.put(coreCbor.encodeToByteArray(StateEntry.serializer(), entry))
        if (older.isNotEmpty()) sp.delete(older)
    }

    /**
     * Writes a new emergency card: a new recovery code whose Kern-space holds the root key and the state secret. The
     * root key stays on this device until [confirmEmergencyCard]. Without the root key on the device the previous
     * [oldCode] is needed. Returns the code to show (`K7QM-…`), `null` if the root key is not available or no relay
     * took the Kern-space in time.
     */
    suspend fun createEmergencyCard(oldCode: String? = null): String? = onCore {
        opened.await()
        val old = oldCode?.let { loadKern(it) }
        val rootSeed = platform.secrets.get(CoreIdentity.ROOT) ?: old?.root ?: return@onCore null
        val stateSecret = platform.secrets.get(STATE_SECRET) ?: old?.state ?: AnmpKeys.randomKey()
        platform.secrets.put(STATE_SECRET, stateSecret)
        val code = RecoveryCode.newCode()
        val ref = "kern:" + B64.encode(code.copyOf(4))
        val stored = CompletableDeferred<Boolean>()
        val watch = cs.launch {
            events.collect { e ->
                if (e is CoreEvent.Stored && e.ref == ref) stored.complete(true)
                if (e is CoreEvent.SendFailed && e.ref == ref) stored.complete(false)
            }
        }
        kernSpace(code).put(coreCbor.encodeToByteArray(KernEntry.serializer(), KernEntry(rootSeed, stateSecret, now())), ref)
        val ok = withTimeoutOrNull(KERN_TIMEOUT) { stored.await() } == true
        watch.cancel()
        if (!ok) return@onCore null
        backupState()
        platform.secrets.put(PENDING_CODE, code)
        if (oldCode != null && old != null) cs.launch { RecoveryCode.parse(oldCode)?.let { c -> kernSpace(c).let { sp -> sp.delete(sp.read().entries.keys) } } }
        RecoveryCode.format(code)
    }

    /**
     * Code of the last [createEmergencyCard] that was not confirmed yet (`K7QM-…`), `null` if there is none. The card is
     * already in the Kern-space (and an older one deleted): the app shows this code again instead of making a new card,
     * e.g. after the dialog was closed before typing it. Shown only on this device, which still holds the root key.
     */
    suspend fun pendingEmergencyCard(): String? = onCore {
        platform.secrets.get(PENDING_CODE)?.takeIf { it.size == RecoveryCode.BYTES }?.let(RecoveryCode::format)
    }

    /**
     * The person typed the code once (ADR 0023): if it matches the last [createEmergencyCard], the root key is deleted
     * from this device; from now on it lies only in the Kern-space. `false` if it does not match.
     */
    suspend fun confirmEmergencyCard(typed: String): Boolean = onCore {
        val pending = platform.secrets.get(PENDING_CODE) ?: return@onCore false
        if (!RecoveryCode.parse(typed).contentEquals(pending)) return@onCore false
        platform.secrets.put(PENDING_CODE, null)
        platform.secrets.put(CoreIdentity.ROOT, null)
        me = CoreIdentity.loadOrCreate(platform.secrets)
        update { s -> s.copy(cardTs = now()) }
        true
    }

    /**
     * On a new installation, instead of starting anew: reads the Kern-space and the state space of [code] and puts the
     * person's keys in place. Then the app stops this core and starts a new one; circles and contacts come back,
     * circle histories from the members. Locking lost devices: [renewPersonKey] with the code, or [removeOwnDevice].
     */
    suspend fun recoverWithCode(code: String): RecoverResult = onCore {
        opened.await()
        check(state.circles.isEmpty() && state.members.isEmpty()) { "recovering only on a new installation" }
        val raw = RecoveryCode.parse(code) ?: return@onCore RecoverResult.Invalid
        val k = kernSpace(raw).read()
        if (!k.answered) return@onCore RecoverResult.Later
        val kern = k.entries.values.mapNotNull { runCatching { coreCbor.decodeFromByteArray(KernEntry.serializer(), it) }.getOrNull() }
            .filter { it.root.size == 32 && it.state.size == 32 }.maxByOrNull { it.ts } ?: return@onCore RecoverResult.NotFound
        val st = stateSpace(kern.state).read()
        if (!st.answered) return@onCore RecoverResult.Later
        val entry = st.entries.values.mapNotNull { runCatching { coreCbor.decodeFromByteArray(StateEntry.serializer(), it) }.getOrNull() }.maxByOrNull { it.ts }
            ?: return@onCore RecoverResult.NotFound
        val p = AnmpPersons.verify(entry.proof) ?: return@onCore RecoverResult.NotFound
        if (!p.cert.root.contentEquals(AnmpSpaceKey.fromPrivateKey(kern.root).publicKey)) return@onCore RecoverResult.NotFound
        if (!AnmpSpaceKey.fromPrivateKey(entry.person).publicKey.contentEquals(p.cert.person)) return@onCore RecoverResult.NotFound
        val sec = platform.secrets
        sec.put(CoreIdentity.PERSON, entry.person)
        sec.put(CoreIdentity.INBOX, entry.inbox)
        entry.store?.takeIf { it.size == 32 }?.let { sec.put(StoreKeys.SECRET, it) }
        sec.put(STATE_SECRET, kern.state)
        sec.put(CoreIdentity.PROOF, entry.proof)
        sec.put(CoreIdentity.ROOT, null)
        entry.sync?.let { sec.put(RECOVER_SYNC, coreCbor.encodeToByteArray(AnmpSync.serializer(), it)) }
        sec.put(STATE_KEY, null)
        RecoverResult.Recovered(deviceInfos(p), entry.extras)
    }

    // --- Data for the own devices, password space (ADR 0042) -----------------------------------------

    /**
     * Sends [data] of the app to the own other devices ([toDevice]: only to that one), e.g. the direct history for an
     * added device. Sealed parts of at most [HISTORY_PART_BYTES] in the own mailbox, one `sync` entry per device; each
     * receives [CoreEvent.OwnData]. `false` without own other devices.
     */
    suspend fun sendOwnData(topic: String, data: ByteArray, toDevice: String? = null): Boolean = onCore {
        opened.await()
        if (revokedHere) return@onCore false
        val targets = ownOthers().filter { toDevice == null || it.deviceId == toDevice }
        if (targets.isEmpty()) return@onCore false
        val parts = mutableListOf<AnmpHistoryPart>()
        val uploads = mutableListOf<Pair<ID, ByteArray>>()
        val chunks = if (data.isEmpty()) listOf(data) else (data.indices step HISTORY_PART_BYTES).map { data.copyOfRange(it, minOf(it + HISTORY_PART_BYTES, data.size)) }
        for (chunk in chunks) {
            val key = AnmpBlob.newKey()
            val sealed = AnmpBlob.seal(key, chunk)
            parts += AnmpHistoryPart(sealed.id, key, me.inbox.bytes, AnmpKeys.sha256(chunk))
            uploads += ID(sealed.id) to sealed.bytes
        }
        val c = content(ownData = AnmpOwnData(topic, parts, now()))
        val a = ownAccess()
        val until = lifetimeUntil()
        targets.forEachIndexed { i, peer ->
            val encoded = Packets.encode(me, AnmpKind.SYNC, c, Anmp.DIRECT_SCOPE, null, peer)
            transport.submit(a.space, a.seal(encoded.packet), until, if (i == 0) uploads else emptyList(), known = a)
        }
        true
    }

    /** Fetches the parts (again later while relays repair) and hands the bytes to the app. */
    private fun receiveOwnData(d: AnmpOwnData, fromDevice: String) {
        cs.launch {
            var pause = timings.historyRetryMin
            while (true) {
                val chunks = mutableListOf<ByteArray>()
                var later = false
                for (part in d.parts) {
                    val space = runCatching { Key(part.from) }.getOrNull() ?: return@launch
                    val bytes = when (val r = transport.readContent(space, ID(part.id))) {
                        is ReadResult.Found -> AnmpBlob.open(part.key, part.id, r.fetched.data)
                        ReadResult.Gone -> return@launch
                        ReadResult.Later -> null.also { later = true }
                    }
                    if (later) break
                    if (bytes == null || !AnmpKeys.sha256(bytes).contentEquals(part.hash)) return@launch
                    chunks += bytes
                }
                if (!later) {
                    val all = chunks.fold(ByteArray(0)) { acc, b -> acc + b }
                    emit(CoreEvent.OwnData(d.topic, all, fromDevice, d.ts))
                    return@launch
                }
                delay(pause)
                pause = minOf(pause * 2, timings.historyRetryMax)
            }
        }
    }

    /** Password space: from the store secret, so every own device and the emergency card reach it. */
    private suspend fun vaultSpace(secret: ByteArray? = platform.secrets.get(StoreKeys.SECRET)): SealedSpace? =
        secret?.let { SealedSpace.of(it, AnmpInfo.VAULT_SIGN, AnmpInfo.VAULT_AEAD, net, pool, transport, me.inboxEnvelope) }

    /**
     * Puts the password vault of the app on the relays without expiry ([data] is already sealed with the app's key `pv`;
     * the space adds its own layer). [changed] (ms) decides between devices: the newest wins. Older entries are deleted.
     * [key]: the app's key `pv`, so that every own device can open the vault; `null` keeps the key of the newest entry.
     */
    suspend fun vaultPut(data: ByteArray, changed: Long, key: ByteArray? = null): Unit = onCore {
        opened.await()
        if (revokedHere) return@onCore
        val sp = vaultSpace() ?: return@onCore
        val read = sp.read()
        val older = read.entries.keys
        val keep = key ?: newestVault(read.entries.values)?.key
        sp.put(coreCbor.encodeToByteArray(VaultEntry.serializer(), VaultEntry(changed, data, keep)))
        if (older.isNotEmpty()) sp.delete(older)
    }

    /** The newest password vault on the relays. */
    suspend fun vaultGet(): VaultResult = onCore {
        opened.await()
        val sp = vaultSpace() ?: return@onCore VaultResult.Empty
        val r = sp.read()
        if (!r.answered) return@onCore VaultResult.Later
        val newest = newestVault(r.entries.values) ?: return@onCore VaultResult.Empty
        VaultResult.Found(newest.data, newest.changed, newest.key)
    }

    private fun newestVault(entries: Collection<ByteArray>): VaultEntry? =
        entries.mapNotNull { runCatching { coreCbor.decodeFromByteArray(VaultEntry.serializer(), it) }.getOrNull() }.maxByOrNull { it.changed }

    // --- Moving the own mailbox to the person (ADR 0107 §6) ------------------------------------------

    /**
     * Once per device: every entry of the own mailbox that was sealed for this device (before ADR 0107) and is part of a
     * conversation (direct, react, delete) is written again as an own copy to the own mailbox recipient, so every own
     * device, also one back from the emergency card, reads it. Received ones name their author (content 37). Then the old
     * entry goes, unless it still carries attachments younger than 30 days. Entries for devices that are no longer in
     * the own list cannot be read by anyone and go too. Runs until it got through once; `true` then.
     */
    suspend fun migrateMailbox(): Boolean = migrateLock.withLock { onCore { migrateOnce() } }

    /** One run at a time: the reads below suspend, and a second run would write the copies again. */
    private val migrateLock = Mutex()

    private suspend fun migrateOnce(): Boolean {
        opened.await()
        if (state.mailboxMigrated) return true
        if (revokedHere || erasing) return false
        val a = ownAccess()
        val l = Listed.Inbox(a)
        val listed = listOwnMailbox(a) ?: return false
        val ownIds = own?.deviceIds?.map { Anmp.tagOf(it) }.orEmpty()
        // Messages already in the form to the person (copies of another own device): not again.
        val done = mutableSetOf<String>()
        val old = mutableListOf<Pair<ID, Opened>>()
        val unreadable = mutableListOf<ID>()
        for ((id, data) in listed) {
            val packet = openEnvelope(l, data) ?: continue
            val to = Anmp.peek(packet)?.to ?: continue
            when {
                to.contentEquals(me.mailbox.tag) -> Packets.open(me, packet, l.scope, null, ::peerByTag)
                    ?.let { o -> o.content.id?.let { done += o.kind.name + B64.encode(it) } }
                to.contentEquals(me.tag) -> Packets.open(me, packet, l.scope, null, ::peerByTag)?.let { old += id to it }
                // Sealed for an own device that is gone: nobody can open it any more.
                ownIds.none { it.contentEquals(to) } -> unreadable += id
            }
        }
        val mine = me.device
        val cutoff = nowS() - ATTACHMENT_KEEP_S
        for ((id, o) in old) {
            if (o.kind != AnmpKind.DIRECT && o.kind != AnmpKind.REACT && o.kind != AnmpKind.DELETE) continue
            val mid = o.content.id?.let { o.kind.name + B64.encode(it) }
            if (mid != null && mid !in done) {
                val fromOwn = personOf(o.fromDevice) == ownPersonId
                val peer = if (fromOwn) o.content.peer else B64.decode(personOf(o.fromDevice)).takeIf { it.size == Anmp.DEVICE_BYTES }
                if (peer == null) continue
                val copy = o.content.asCopy(peer, if (fromOwn) null else B64.decode(o.fromDevice), mine.publicKey, mine.signKey)
                postOwnTo(ownMailbox, o.kind, copy)
                done += mid
            }
            // Attachments live with their entry: it stays until they expire anyway.
            if (o.content.attachments.isNullOrEmpty() || o.content.ts < cutoff) transport.delete(a.cred, a.space, id, entry = true)
        }
        unreadable.forEach { transport.delete(a.cred, a.space, it, entry = true) }
        update { s -> s.copy(mailboxMigrated = true) }
        markMoved()
        log.log("mailbox moved to the person: ${old.size} old entries, ${unreadable.size} unreadable removed")
        return true
    }

    /** Every entry of the own mailbox with its bytes, from the first holders that answer; `null` if none answered. */
    private suspend fun listOwnMailbox(a: SpaceAccess): Map<ID, ByteArray>? {
        val listed = mutableMapOf<ID, ByteArray>()
        var answered = false
        for (relay in net.readOrder(a.name).take(4)) {
            val client = pool.client(relay) ?: continue
            try {
                var after = ""
                while (true) {
                    val page = client.list(a.cred, a.space, ListOptions(after, 0, 200))
                    for (o in page.objects) {
                        if (o.id in listed || o.revokes != null) continue
                        (o.inline() ?: client.get(a.cred, a.space, o.id).data).let { listed[o.id] = it }
                    }
                    if (page.next.isEmpty() || page.next == after) break
                    after = page.next
                }
                answered = true
                pool.ok(relay.node)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AndpHttpException) {
                pool.ok(relay.node)
                if (e.isNotFound) answered = true
            } catch (_: Exception) {
            }
        }
        return listed.takeIf { answered }
    }

    /**
     * Writes the app's own direct history [items] once as own copies into the own mailbox (ADR 0107 §6): what a person
     * with only one device sent lay only in the recipients' mailboxes. Items whose message is already there in the form
     * to the person are left out. Only installations from before ADR 0107; `true` once done (also when nothing was due).
     */
    suspend fun keepOwnHistory(items: List<OwnHistoryItem>): Boolean = migrateLock.withLock {
        onCore {
            opened.await()
            if (state.historyKept || config.legacyDirect) return@onCore true
            if (revokedHere || erasing) return@onCore false
            val a = ownAccess()
            val l = Listed.Inbox(a)
            val listed = listOwnMailbox(a) ?: return@onCore false
            val there = mutableSetOf<String>()
            for (data in listed.values) {
                val packet = openEnvelope(l, data) ?: continue
                if (Anmp.peek(packet)?.to?.contentEquals(me.mailbox.tag) != true) continue
                Packets.open(me, packet, l.scope, null, ::peerByTag)?.takeIf { it.kind == AnmpKind.DIRECT }?.content?.id?.let { there += B64.encode(it) }
            }
            var written = 0
            for (item in items) {
                if (item.mid in there) continue
                val id = runCatching { B64.decode(item.mid) }.getOrNull()?.takeIf { it.size == Anmp.ID_BYTES } ?: continue
                val peer = runCatching { B64.decode(personOf(item.peerPerson)) }.getOrNull()?.takeIf { it.size == Anmp.DEVICE_BYTES } ?: continue
                val author = item.author?.let { runCatching { B64.decode(it) }.getOrNull()?.takeIf { b -> b.size == Anmp.DEVICE_BYTES } ?: continue }
                val reply = replyBytes(item.replyTo, item.replyFrom)
                val c = AnmpContent(
                    id = id, ts = item.tsMillis / 1000, text = item.text, ref = reply?.first, refFrom = reply?.second,
                    key = me.device.publicKey, signKey = me.device.signKey, attachments = item.attachments.takeIf { it.isNotEmpty() },
                    peer = peer, author = author,
                )
                postOwnTo(ownMailbox, AnmpKind.DIRECT, c)
                there += item.mid
                written++
            }
            update { s -> s.copy(historyKept = true) }
            markMoved()
            log.log("own history kept: $written of ${items.size}")
            true
        }
    }

    // --- Loading after adding or recovering (ADR 0107 §8) -------------------------------------------------

    private val _loading = MutableStateFlow(HistoryLoading(mailbox = false, circles = emptyList()))

    /**
     * What this device has not read through once yet: its own mailbox (direct conversations) and circle spaces. The app
     * shows „Nachrichten werden geladen …“ while something is in it. Empty on installations that already ran.
     */
    val loading: StateFlow<HistoryLoading> = _loading.asStateFlow()

    private fun refreshLoading() {
        val read = state.circlesRead.toSet()
        _loading.value = HistoryLoading(
            mailbox = !state.mailboxRead,
            circles = state.circles.filterNot { it.removed || it.id in read }.map { it.id },
        )
    }

    /** [l] was listed to its end at one holder. */
    private suspend fun caughtUp(l: Listed) {
        when (l) {
            is Listed.Inbox -> if (!state.mailboxRead) update { s -> s.copy(mailboxRead = true) }
            is Listed.Circle -> if (l.circleId !in state.circlesRead && !l.previous) update { s -> s.copy(circlesRead = s.circlesRead + l.circleId) }
            else -> Unit
        }
    }

    /** Mailbox and own history are in the form to the person here (ADR 0107 §6). */
    private val movedHere: Boolean get() = state.mailboxMigrated && state.historyKept

    /** Notes [movedHere] in the own entry of the device list once (a quiet list change). */
    private suspend fun markMoved() {
        if (!movedHere || revokedHere || config.legacyDirect) return
        val cur = own ?: return
        val i = cur.deviceIds.indexOfFirst { it.contentEquals(me.device.deviceId) }
        if (i < 0 || cur.list.devices[i].moved) return
        changeOwnList(cur.list.devices.mapIndexed { j, e -> if (j == i) AnmpDeviceEntry(e.signKey, e.key, e.name, e.added, e.node, moved = true) else e }, cur.list.revoked, quiet = true)
    }

    /** Message IDs (kind + ID) of direct packets that came over the internet lately, so radio copies are not written twice. */
    private val viaInternet = LinkedHashSet<String>()

    private fun sawViaInternet(o: Opened) {
        if (o.kind != AnmpKind.DIRECT && o.kind != AnmpKind.REACT && o.kind != AnmpKind.DELETE) return
        val id = o.content.id ?: return
        viaInternet += o.kind.name + B64.encode(id)
        while (viaInternet.size > MAX_SEEN) viaInternet.remove(viaInternet.first())
    }

    /**
     * A direct packet of [fromDevice] that came over radio goes into the own mailbox as an own copy after
     * [CoreTimings.radioCopyWait], unless it came over the internet meanwhile (ADR 0106 §6, 0107 §4). Without internet the write
     * waits in the transport like any other.
     */
    private fun copyFromRadio(kind: AnmpKind, content: AnmpContent, fromDevice: String) {
        val id = content.id ?: return
        cs.launch {
            delay(timings.radioCopyWait)
            onCore {
                if (kind.name + B64.encode(id) in viaInternet || revokedHere || erasing) return@onCore
                val person = personOf(fromDevice).takeIf { it != fromDevice } ?: return@onCore
                if (person == ownPersonId) return@onCore
                postOwnTo(ownMailbox, kind, content.asCopy(B64.decode(person), B64.decode(fromDevice), me.device.publicKey, me.device.signKey))
            }
        }
    }

    /** Keeps trying [migrateMailbox] until it got through once, also without internet for a while. */
    private suspend fun keepMigrating() {
        var pause = timings.historyRetryMin
        while (!runCatching { migrateMailbox() }.getOrDefault(false)) {
            delay(pause)
            pause = minOf(pause * 2, timings.historyRetryMax)
        }
    }

    // --- Profile space: name, picture and settings on all own devices (ADR 0050) ---------------------------

    /** Profile space: from the store secret, so every own device and the emergency card reach it. */
    private suspend fun profileSpace(secret: ByteArray? = platform.secrets.get(StoreKeys.SECRET)): SealedSpace? =
        secret?.let { SealedSpace.of(it, AnmpInfo.PROFILE_SIGN, AnmpInfo.PROFILE_AEAD, net, pool, transport, me.inboxEnvelope) }

    /**
     * Settings of the app that apply to the person (name → bytes, opaque to the core). A value that differs from the
     * known one counts as changed now and goes to the profile space; one the core did not know yet counts as old
     * (time 0), so that a fresh or recovered device takes the person's settings. Names not in [values] stay.
     */
    suspend fun setSettings(values: Map<String, ByteArray>): Unit = onCore {
        val known = state.profile.settings.associateBy { it.name }
        val changed = values.filter { (k, v) -> known[k]?.value != B64.encode(v) }
        if (changed.isEmpty()) return@onCore
        val t = now()
        val fresh = changed.map { (k, v) -> SharedSetting(k, B64.encode(v), if (k in known) t else 0) }
        update { s -> s.copy(profile = s.profile.copy(settings = s.profile.settings.filterNot { it.name in changed } + fresh)) }
        profileChanged()
    }

    /** Settings of the person as known here (name → bytes). */
    suspend fun settings(): Map<String, ByteArray> = onCore { state.profile.settings.associate { it.name to B64.decode(it.value) } }

    private fun profileChanged() {
        cs.launch { runCatching { if (profileSync()) sendOwnData(PROFILE_TOPIC, ByteArray(0)) } }
    }

    /**
     * Merges the profile here with the profile space (ADR 0050): per name, picture and setting the newest change wins,
     * on a tie the one on the relays. Takes what is newer there ([CoreEvent.ProfileChanged]) and writes the merged
     * profile back if this device had something newer. `true` if it wrote.
     */
    suspend fun profileSync(): Boolean = onCore {
        opened.await()
        if (revokedHere) return@onCore false
        val sp = profileSpace() ?: return@onCore false
        val read = sp.read()
        if (!read.answered) return@onCore false
        val entries = read.entries.values.mapNotNull { runCatching { coreCbor.decodeFromByteArray(ProfileEntry.serializer(), it) }.getOrNull() }
        val remoteName = entries.mapNotNull { it.name }.maxByOrNull { it.changed }
        val remoteImage = entries.mapNotNull { it.image }.maxByOrNull { it.changed }
        val remoteSettings = entries.flatMap { it.settings.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, v) -> v.maxBy { it.changed } }

        // Take what is newer (or on a tie, different) on the relays.
        val p = state.profile
        val takeName = remoteName?.takeIf { it.changed >= p.nameTs && it.data.decodeToString() != p.name && it.data.isNotEmpty() }
        val imageHashThere = remoteImage?.hash?.let(B64::encode)
        var takeImage = remoteImage?.takeIf { it.changed >= p.imageTs && imageHashThere != p.imageHash }
        var imageBytes: ByteArray? = null
        if (takeImage?.id != null) {
            val sealed = (transport.readContent(sp.space, ID(takeImage.id!!)) as? ReadResult.Found)?.fetched?.data
            imageBytes = sealed?.let { AnmpBlob.open(takeImage!!.key!!, takeImage!!.id!!, it) }?.takeIf { plainHash(it).contentEquals(takeImage!!.hash!!) }
            if (imageBytes == null) takeImage = null else platform.blobs.write(OWN_IMAGE_SEALED, sealed!!)
        }
        val known = p.settings.associateBy { it.name }
        val takeSettings = remoteSettings.filter { (k, v) -> known[k].let { it == null || (v.changed >= it.ts && B64.encode(v.data) != it.value) } }
        if (takeName != null || takeImage != null || takeSettings.isNotEmpty()) {
            takeImage?.let { img -> if (imageBytes != null) platform.blobs.write(OWN_IMAGE, imageBytes) else platform.blobs.delete(OWN_IMAGE) }
            update { s ->
                var q = s.profile
                takeName?.let { q = q.copy(name = it.data.decodeToString().take(AnmpLinks.MAX_NAME), nameTs = it.changed) }
                takeImage?.let { q = q.copy(imageHash = it.hash?.let(B64::encode), imageTs = it.changed) }
                if (takeSettings.isNotEmpty()) {
                    q = q.copy(settings = q.settings.filterNot { it.name in takeSettings } + takeSettings.map { (k, v) -> SharedSetting(k, B64.encode(v.data), v.changed) })
                }
                s.copy(profile = q)
            }
            emit(CoreEvent.ProfileChanged(takeName?.data?.decodeToString(), takeImage != null, imageBytes, takeSettings.mapValues { it.value.data }))
            // A picture from another own device also goes to this device's contacts.
            if (takeImage != null) sendProfileToContacts()
        }

        // Write back if this device knows something newer, or several entries need to become one.
        val q = state.profile
        val needWrite = entries.size != 1 ||
            (q.name.isNotBlank() && (remoteName == null || q.nameTs > remoteName.changed)) ||
            ((q.imageHash != null || q.imageTs > 0) && (remoteImage == null || q.imageTs > remoteImage.changed)) ||
            q.settings.any { st -> remoteSettings[st.name].let { it == null || st.ts > it.changed } }
        if (!needWrite) return@onCore false
        val image = ownImageForProfile(remoteImage)
        val entry = ProfileEntry(
            name = q.name.takeIf { it.isNotBlank() }?.let { ProfileValue(q.nameTs, it.encodeToByteArray()) },
            image = image?.first,
            settings = q.settings.associate { it.name to ProfileValue(it.ts, B64.decode(it.value)) },
        )
        sp.put(coreCbor.encodeToByteArray(ProfileEntry.serializer(), entry), contents = listOfNotNull(image?.second))
        if (read.entries.isNotEmpty()) sp.delete(read.entries.keys)
        // A replaced picture does not stay as an orphan.
        entries.mapNotNull { it.image?.id }.map(B64::encode).distinct().filter { old -> image?.first?.id?.let(B64::encode) != old }
            .forEach { old -> transport.delete(sp.accessOf().cred, sp.space, ID.parse(old), entry = false) }
        true
    }

    // --- Device space: settings of each own device, by device ID (ADR 0106) ---------------------------------

    /** Device space: from the store secret like the profile space, so every own device and the emergency card reach it. */
    private suspend fun deviceSpace(secret: ByteArray? = platform.secrets.get(StoreKeys.SECRET)): SealedSpace? =
        secret?.let { SealedSpace.of(it, AnmpInfo.DEVICES_SIGN, AnmpInfo.DEVICES_AEAD, net, pool, transport, me.inboxEnvelope) }

    private var deviceWrite: Job? = null

    /**
     * Settings of the app that apply to this device only (name → bytes, opaque to the core): radio, region, postponed
     * notices and the like. Changed values are written to the device space under this device's ID a few seconds later,
     * so that a phone replacing this one can take them over ([takeOverDevice]). Names not in [values] stay.
     */
    suspend fun setDeviceSettings(values: Map<String, ByteArray>): Unit = onCore {
        val known = state.deviceSettings.associateBy { it.name }
        val changed = values.filter { (k, v) -> known[k]?.value != B64.encode(v) }
        if (changed.isEmpty()) return@onCore
        val t = now()
        update { s -> s.copy(deviceSettings = s.deviceSettings.filterNot { it.name in changed } + changed.map { (k, v) -> SharedSetting(k, B64.encode(v), t) }) }
        if (deviceWrite?.isActive != true) {
            deviceWrite = cs.launch {
                delay(DEVICE_WRITE_DELAY)
                runCatching { writeDeviceSettings() }.onFailure { if (it is CancellationException) throw it; log.log("device settings: ${it.message}") }
            }
        }
    }

    /** Settings of this device as known here (name → bytes). */
    suspend fun deviceSettings(): Map<String, ByteArray> = onCore { state.deviceSettings.associate { it.name to B64.decode(it.value) } }

    private fun deviceEntries(read: SealedSpace.Result): Map<String, DeviceEntry> =
        read.entries.mapNotNull { (id, bytes) -> runCatching { coreCbor.decodeFromByteArray(DeviceEntry.serializer(), bytes) }.getOrNull()?.let { id to it } }.toMap()

    /**
     * Writes this device's settings as one entry of the device space and deletes its older entries there. Also deletes
     * entries of devices that are no longer in the own list and unchanged for [DEVICE_ENTRY_KEEP]. `true` if it wrote.
     */
    suspend fun writeDeviceSettings(): Boolean = onCore {
        opened.await()
        if (revokedHere) return@onCore false
        val sp = deviceSpace() ?: return@onCore false
        val read = sp.read()
        if (!read.answered) return@onCore false
        val entries = deviceEntries(read)
        val mine = B64.decode(me.deviceId)
        val own = entries.filterValues { it.device.contentEquals(mine) }
        val current = state.deviceSettings.associate { it.name to ProfileValue(it.ts, B64.decode(it.value)) }
        val newest = own.values.maxByOrNull { it.changed }
        val same = newest != null && own.size == 1 && newest.settings.keys == current.keys &&
            current.all { (k, v) -> newest.settings[k]?.let { it.changed == v.changed && it.data.contentEquals(v.data) } == true }
        val listed = _ownDevices.value.map { it.deviceId }.toSet()
        val stale = if (listed.isEmpty()) emptyList() else entries.filter { (_, e) ->
            B64.encode(e.device) !in listed && !e.device.contentEquals(mine) && now() - e.changed > DEVICE_ENTRY_KEEP
        }.keys
        if (stale.isNotEmpty()) sp.delete(stale)
        if (same || current.isEmpty()) return@onCore false
        sp.put(coreCbor.encodeToByteArray(DeviceEntry.serializer(), DeviceEntry(mine, now(), current)))
        if (own.isNotEmpty()) sp.delete(own.keys)
        true
    }

    /**
     * Other own devices with settings in the device space, newest first, for „Ersetzt dieses Handy eins deiner Geräte?“
     * (ADR 0106 §7). Also devices already removed from the own list, until their entry expires. `null` if no relay answered.
     */
    suspend fun storedDevices(): List<StoredDevice>? = onCore {
        opened.await()
        val sp = deviceSpace() ?: return@onCore emptyList()
        val read = sp.read()
        if (!read.answered) return@onCore null
        val mine = B64.decode(me.deviceId)
        deviceEntries(read).values.filterNot { it.device.contentEquals(mine) }
            .groupBy { B64.encode(it.device) }
            .map { (id, list) -> list.maxBy { it.changed }.let { StoredDevice(id, it.changed, it.settings.keys.sorted()) } }
            .sortedByDescending { it.changed }
    }

    /**
     * This device replaces the own device [deviceId] (ADR 0106 §7): its settings become this device's (they count as
     * changed now), are written under this device's ID, and the old device's entries are deleted. Returns the settings
     * taken over, `null` if nothing is stored for [deviceId] or no relay answered. The old device stays in the own list;
     * removing it is up to the app (with the device lock).
     */
    suspend fun takeOverDevice(deviceId: String): Map<String, ByteArray>? = onCore {
        opened.await()
        if (deviceId == me.deviceId) return@onCore null
        val sp = deviceSpace() ?: return@onCore null
        val read = sp.read()
        if (!read.answered) return@onCore null
        val theirs = deviceEntries(read).filterValues { B64.encode(it.device) == deviceId }
        val newest = theirs.values.maxByOrNull { it.changed } ?: return@onCore null
        val t = now()
        val taken = newest.settings.mapValues { it.value.data }
        update { s -> s.copy(deviceSettings = s.deviceSettings.filterNot { it.name in taken } + taken.map { (k, v) -> SharedSetting(k, B64.encode(v), t) }) }
        writeDeviceSettings()
        sp.delete(theirs.keys)
        taken
    }

    /** Device space under the previous store secret into the current one (ADR 0042 §6); `true` once it is empty there. */
    private suspend fun moveOldDeviceSpace(old: ByteArray): Boolean {
        val from = deviceSpace(old) ?: return true
        val to = deviceSpace() ?: return false
        val r = from.read()
        if (!r.answered) return false
        val mine = B64.decode(me.deviceId)
        deviceEntries(r).values.filterNot { it.device.contentEquals(mine) }
            .forEach { to.put(coreCbor.encodeToByteArray(DeviceEntry.serializer(), it)) }
        writeDeviceSettings()
        if (r.entries.isNotEmpty()) from.delete(r.entries.keys)
        return true
    }

    /**
     * The own picture as written to the profile space, with its sealed content. Reuses the content on the relays when it
     * is the same picture ([there]), so that its ID stays; `null` image ID = generated picture.
     */
    private suspend fun ownImageForProfile(there: ProfileImage?): Pair<ProfileImage, Pair<ID, ByteArray>?>? {
        val p = state.profile
        val hash = p.imageHash
        if (hash == null) return if (p.imageTs > 0) ProfileImage(p.imageTs) to null else null
        val plain = platform.blobs.read(OWN_IMAGE) ?: return null
        val sealedThere = platform.blobs.read(OWN_IMAGE_SEALED)
        if (there?.id != null && there.hash?.let(B64::encode) == hash && sealedThere != null && ID.of(sealedThere).bytes.contentEquals(there.id)) {
            return ProfileImage(p.imageTs, there.id, there.key, there.hash) to (ID(there.id) to sealedThere)
        }
        val key = AnmpBlob.newKey()
        val sealed = AnmpBlob.seal(key, plain)
        platform.blobs.write(OWN_IMAGE_SEALED, sealed.bytes)
        return ProfileImage(p.imageTs, sealed.id, key, B64.decode(hash)) to (ID(sealed.id) to sealed.bytes)
    }

    // --- Deleting everything (ADR 0046) -------------------------------------------------------------

    /**
     * Removes the person from the network before the app deletes this installation: leaves every circle (the others are
     * told), forgets every contact (their grants are revoked), removes the own other devices (they see themselves
     * removed and stop) and deletes the personal store, the password space, the state space and what lay in the own
     * mailbox. The Kern-space of an emergency card stays (only the code reaches it) but leads to nothing any more.
     * Messages others already received stay with them; own objects in circle spaces expire there.
     *
     * Checks first that a relay answers ([EraseResult.Offline]: nothing was changed). Then waits up to [wait] until every
     * queued write is at one relay at least; [EraseResult.Partly] if some are not: keep this core running until
     * [undeliveredWrites] is 0, otherwise circles and contacts may never learn of it. Afterwards this core sends nothing any more: stop it
     * and delete the installation.
     */
    suspend fun eraseEverything(wait: Duration = ERASE_WAIT): EraseResult {
        val inbox = onCore {
            opened.await()
            if (store.list() == null) return@onCore null
            mailboxObjects(ownAccess())
        } ?: return EraseResult.Offline
        onCore {
            // From here on nothing that arrives is taken in: what goes to the person is opened here too (ADR 0107).
            erasing = true
            pushTarget.value?.let { registerWatch(it, remove = true) }
            pushTarget.value = null
            state.circles.forEach { leaveCircle(it.id) }
            // Channels: own ones revoke their device addresses (record deleted, sender grant revoked), like leaving.
            state.channels.forEach { leaveChannel(it.id) }
            state.members.filter { it.circleId == MemberState.CONTACTS }.map { it.person }.distinct().forEach { removeContact(it) }
            // Every grant still open, above all the one of the contact link (ADR 0084): nobody writes to this mailbox any more,
            // and contacts notice it by the refusal (ADR 0046, addendum 15.11.2026).
            revoke("")
            // Last: the removed devices get one copy of the new chain in their slot of the own mailbox (kept below).
            own?.let { cur ->
                val i = cur.deviceIds.indexOfFirst { it.contentEquals(me.device.deviceId) }
                val others = cur.deviceIds.filterIndexed { j, _ -> j != i }
                if (i >= 0 && others.isNotEmpty() && !revokedHere) changeOwnList(listOf(cur.list.devices[i]), cur.list.revoked + others)
            }
        }
        // Enough once every write is at one holder at least; the relays copy it among themselves (ANDP §5.3).
        val sent = withTimeoutOrNull(wait) { while (undeliveredWrites() > 0) delay(ERASE_POLL) } != null
        // Circles we were the last in: their stores go now, the installation is deleted afterwards.
        cleanUpStores()
        onCore {
            store.list()?.forEach { store.delete(it.file) }
            vaultSpace()?.let { sp -> sp.delete(sp.read().entries.keys) }
            profileSpace()?.let { sp ->
                val r = sp.read()
                r.entries.values.mapNotNull { runCatching { coreCbor.decodeFromByteArray(ProfileEntry.serializer(), it).image?.id }.getOrNull() }
                    .forEach { id -> transport.delete(sp.accessOf().cred, sp.space, ID(id), entry = false) }
                sp.delete(r.entries.keys)
            }
            deviceSpace()?.let { sp -> sp.delete(sp.read().entries.keys) }
            platform.secrets.get(STATE_SECRET)?.let { secret -> stateSpace(secret).let { sp -> sp.delete(sp.read().entries.keys) } }
            val a = ownAccess()
            inbox.forEach { (id, entry) -> transport.delete(a.cred, a.space, id, entry) }
            revokedHere = true
            storedAlerts.value = emptyList()
            platform.blobs.delete(ALERTS_BLOB)
        }
        return if (sent) EraseResult.Done else EraseResult.Partly
    }

    /** Entries of [a]'s space at its holders and the contents they refer to (`true`: entry). */
    private suspend fun mailboxObjects(a: SpaceAccess): Map<ID, Boolean> {
        val found = mutableMapOf<ID, Boolean>()
        for (relay in net.readOrder(a.name).take(4)) {
            val client = pool.client(relay) ?: continue
            try {
                var after = ""
                while (true) {
                    val page = client.list(a.cred, a.space, ListOptions(after, 0, 200))
                    for (o in page.objects) {
                        found[o.id] = true
                        o.refs.forEach { r -> found.getOrPut(r.id) { false } }
                    }
                    if (page.next.isEmpty() || page.next == after) break
                    after = page.next
                }
                pool.ok(relay.node)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AndpHttpException) {
                pool.ok(relay.node)
            } catch (_: Exception) {
            }
        }
        return found
    }

    /** Pending writes (for tests and the "Verbindungen" view). */
    suspend fun pendingWrites(): Int = transport.pending().size

    /**
     * Pending writes that no relay has taken yet (entry, receipt or a content without any holder). After
     * [eraseEverything] gave [EraseResult.Partly], the app waits for 0 before it deletes the installation; the
     * writes go on in the background, also after a restart.
     */
    suspend fun undeliveredWrites(): Int =
        transport.pending().count { j -> (listOfNotNull(j.entry, j.hold) + j.contents).any { it.done.isEmpty() } }

    /** [mid] from the app if it is a valid ANMP message ID, else a new one. */
    private fun messageIdOf(mid: String?): ByteArray =
        mid?.let { runCatching { B64.decode(it) }.getOrNull() }?.takeIf { it.size == Anmp.ID_BYTES } ?: Anmp.newMessageId()

    /** Circle ID: 16 random bytes, b64u (stays the same across key changes). */
    private fun randomId(): String = B64.encode(AnmpKeys.randomKey().copyOf(16))

    companion object {
        /** How long [sendStats] waits for the network list after the start. */
        private val STATS_LIST_WAIT = 30.seconds

        const val STATE_KEY = "core.core.state"

        /** How long a contact request waits for an answer (ADR 0116): like the grant of a contact link. */
        private val REQUEST_LIFETIME = 30.days

        /** [MemberState.addedAt] passed to [upsertMember] for a new contact whose time is not known: stays 0. */
        private const val ADDED_UNKNOWN = -1L

        /** Seed of the stream key and key of the stream tags (ADR 0072), in the secret store, only on this device. */
        const val STREAM_SEED = "core.stream.seed"
        const val STREAM_TAG_KEY = "core.stream.tag"

        /** Beyond two ping intervals, how long a silent stream may stay before it counts as broken. */
        const val STREAM_IDLE_SLACK_SECONDS = 15L

        /** A stream that ended sooner counts as a failure of its home relay. */
        const val STREAM_SHORT_MILLIS = 60_000L
        const val SEEN_KEY = "core.seen"
        private const val OWN_IMAGE = "core/own-image"

        /** Sealed copy of the own picture as it lies in the profile space (ADR 0050). */
        private const val OWN_IMAGE_SEALED = "core/own-image-profile"

        /** Topic of the hint to the own other devices that the profile changed (ADR 0050). */
        const val PROFILE_TOPIC = "anmp/profile"
        private const val MAX_SEEN = 5_000

        /** Object IDs the notification extension already reported, so another holder does not report them again. */
        private const val WAKE_SEEN_KEY = "core.wake.seen"
        /** Own circle messages, kept for deleting on the relays (ADR 0068). */
        private const val SENT_KEY = "core.sent"
        private const val SENT_MAX = 500
        private const val SENT_DAYS = 3
        private const val RECEIVED_KEY = "core.received"
        private const val RECEIVED_MAX = 2000
        private const val RECEIVED_DAYS = 30

        /** Kinds whose entries stay on the relays without expiry (ADR 0088 §1). */
        internal val PERMANENT_KINDS = setOf(
            AnmpKind.CIRCLE, AnmpKind.DIRECT, AnmpKind.REACT, AnmpKind.DELETE, AnmpKind.MEMBER, AnmpKind.META,
            AnmpKind.PROFILE, AnmpKind.LEFT, AnmpKind.REKEY, AnmpKind.REMOVED,
        )
        private const val WAKE_SEEN_MAX = 300
        private val WAKE_RECEIPT_TIMEOUT = 5.seconds
        private const val MAX_TRACKED = 1_000
        private const val MAX_ATTACHMENTS = 5
        /** Link preview (ADR 0069): title and site, description. */
        private const val LINK_TITLE_MAX = 120
        private val LOGIN_TIMEOUT = 20.seconds
        private const val LINK_DESCRIPTION_MAX = 200
        private const val MAX_HISTORY_PARTS = 500
        private const val MAX_FORWARDS = 50
        private const val FORWARD_REF = "fwd:"

        /** Alerts kept on this device, sealed with the local storage key (ADR 0062). */
        private const val ALERTS_BLOB = "core/alerts"
        private const val ALERTS_AAD = "local-v1:alerts"

        /** At most this many alerts are kept; the oldest go first. */
        private const val MAX_ALERTS = 2_000
        private val ALERT_SAVE_DELAY = 500.milliseconds

        /** Ref of the write of a device address record; the ingress ID follows. */
        const val INGRESS_REF = "ingress:"

        /** Longest label of a device address. */
        private const val MAX_LABEL = 60

        /** Revoked device addresses remembered per channel against snapshots of own devices. */
        private const val MAX_REVOKED_INGRESS = 100

        /** Alerts up to a minute older than the channel on this device still notify (clocks of sender and device). */
        private const val HISTORY_SLACK_MS = 60_000L

        /** Longest pause of the alert clock (ms). */
        private const val ALERT_CLOCK_MAX = 3_600_000L

        /** After "Link zurücksetzen" the old record of a device address is deleted after this time (the new one is newer anyway). */
        private val OLD_RECORD_DELAY = kotlin.time.Duration.parse("60s")

        /** Ref of a write into a circle store ([circleStorePut]); the file ID follows. */
        const val CIRCLE_REF_PREFIX = "cstore:"
        private const val OWN_SLOT = "own:"
        private const val PAIR_OWNER = "core.pair.owner"
        private const val STATE_SECRET = "core.state.secret"
        private const val PENDING_CODE = "core.card.pending"
        private const val RECOVER_SYNC = "core.recover.sync"
        private val KERN_TIMEOUT = kotlin.time.Duration.parse("60s")
        private val ERASE_WAIT = kotlin.time.Duration.parse("30s")

        /** So long [webPushKey] waits for the network list after the start. */
        private val WEB_PUSH_KEY_WAIT = kotlin.time.Duration.parse("10s")
        private val ERASE_POLL = kotlin.time.Duration.parse("250ms")
        private const val PAIR_SECRET = "core.pair.secret"
        private val PAIR_LIFETIME = kotlin.time.Duration.parse("1h")

        /** How long the store of a circle left as the last member is tried to be emptied (ms, 30 days). */
        private const val STORE_CLEANUP_KEEP = 30L * 24 * 3600 * 1000

        /** Attachments stay 30 days with their entry (ADR 0088): an old entry with younger ones is not deleted yet. */
        private const val ATTACHMENT_KEEP_S = 30L * 24 * 3600

        /** Entry of a device no longer in the own list stays this long after its last change (ADR 0106 §2). */
        private const val DEVICE_ENTRY_KEEP = 90L * 24 * 3600 * 1000

        /** Device settings are written this long after a change, so that a burst of changes makes one entry. */
        private val DEVICE_WRITE_DELAY = 5.seconds

        /** How long a left circle or removed contact is remembered against snapshots (ms, 90 days). */
        private const val TOMBSTONE_KEEP = 90L * 24 * 3600 * 1000

        /** Size of a history part before sealing (ADR 0014). */
        const val HISTORY_PART_BYTES = 1_000_000

        /** Grants start a minute in the past so that a slightly earlier clock elsewhere does not refuse them. */
        private const val GRANT_BACKDATE = 60L

        /** Purposes of our mailbox grants for channels (ADR 0062 §11): in the links of an own channel, and back to the owner of a subscribed one. */
        private const val JOIN_PURPOSE = "channel-join:"
        private const val SUBSCRIBED_PURPOSE = "channel:"

        /** Lifetime of these grants: two years, renewed when less than one is left. */
        private val JOIN_GRANT_LIFETIME = kotlin.time.Duration.parse("730d")
        private val JOIN_GRANT_RENEW = kotlin.time.Duration.parse("365d")
        private val SEEN_FLUSH = kotlin.time.Duration.parse("10s")

        const val WHY_REMOVE = "remove"
        const val WHY_RESET = "reset"
        const val WHY_LEFT = "left"
        const val WHY_DEVICE = "device"

        private val historySerializer = ListSerializer(ByteArraySerializer())

        /** Plaintext hash (ANMP): first 16 bytes of SHA-256. */
        internal fun plainHash(data: ByteArray): ByteArray = ID.of(data).bytes.copyOf(16)
    }
}

/** A recipient device and the mailbox of its person; [legacy]: the device's app predates ADR 0042 (old envelope key). */
internal class Target(val peer: Peer, val inbox: String, val legacy: Boolean, val mailbox: Boolean = false)

/** Hand-over to an added device (ADR 0042 §5), sealed with the one-time pairing secret. */
@Serializable
internal class Handover(
    @CborLabel(0) val proof: ByteArray,
    @CborLabel(1) val person: ByteArray,
    @CborLabel(2) val inbox: ByteArray,
    @CborLabel(3) val store: ByteArray? = null,
    @CborLabel(4) val extras: Map<String, ByteArray> = emptyMap(),
    @CborLabel(5) val state: ByteArray? = null,
)

/** Entry of the password space: the app's sealed vault, when it changed (ms) and the app's key for it. */
@Serializable
internal class VaultEntry(
    @CborLabel(0) val changed: Long,
    @CborLabel(1) val data: ByteArray,
    @CborLabel(2) val key: ByteArray? = null,
)

/** Entry of the device space (ADR 0106): settings of the device [device] (16 bytes), written at [changed] ms. */
@Serializable
internal class DeviceEntry(
    @CborLabel(0) val device: ByteArray,
    @CborLabel(1) val changed: Long,
    @CborLabel(2) val settings: Map<String, ProfileValue> = emptyMap(),
)

/** Entry of the profile space (ADR 0050): name, picture and settings of the person, each with when it changed. */
@Serializable
internal class ProfileEntry(
    @CborLabel(0) val name: ProfileValue? = null,
    @CborLabel(1) val image: ProfileImage? = null,
    @CborLabel(2) val settings: Map<String, ProfileValue> = emptyMap(),
)

@Serializable
internal class ProfileValue(
    @CborLabel(0) val changed: Long,
    @CborLabel(1) val data: ByteArray,
)

/** Own picture in the profile space: content [id] sealed with [key], plaintext [hash]; no [id] = generated picture. */
@Serializable
internal class ProfileImage(
    @CborLabel(0) val changed: Long,
    @CborLabel(1) val id: ByteArray? = null,
    @CborLabel(2) val key: ByteArray? = null,
    @CborLabel(3) val hash: ByteArray? = null,
)

/** Message a circle or direct message answers (ADR 0092): its ID and the tag of its author, both b64u; `null` if none. */
internal fun replyOf(c: AnmpContent): Pair<String, String>? {
    val ref = c.ref?.takeIf { it.size == Anmp.ID_BYTES } ?: return null
    val from = c.refFrom?.takeIf { it.size == Anmp.TAG_BYTES } ?: return null
    return B64.encode(ref) to B64.encode(from)
}
