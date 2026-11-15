package app.anynear.anmp.core

import app.anynear.andp.B64
import app.anynear.anmp.AnmpDevice
import app.anynear.anmp.AnmpKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertNotNull

/** Short times so that a test with real time runs in seconds. */
internal val TEST_TIMINGS = CoreTimings(
    longPollSeconds = 1,
    minPollInterval = 50.milliseconds,
    backgroundPollInterval = 1.seconds,
    streamRetryMin = 50.milliseconds,
    streamRetryMax = 200.milliseconds,
    streamRecheck = 2.seconds,
    backoffMin = 50.milliseconds,
    backoffMax = 400.milliseconds,
    writeRetry = RetryPlan(listOf(50.milliseconds, 100.milliseconds, 200.milliseconds), 400.milliseconds),
    receiptBatch = 100.milliseconds,
    dutyFirst = 100.milliseconds,
    dutyStep = 300.milliseconds,
    dutyMax = 1.seconds,
    reannounceDelay = 100.milliseconds,
    leaveRotateDelay = 200.milliseconds,
    reseedAfter = 1.seconds,
    reseedCheck = 200.milliseconds,
    historyRetryMin = 200.milliseconds,
    historyRetryMax = 1.seconds,
)

/** One installation in a test: core, its stores, received events and its message history (as the app keeps it). */
internal class Device(val name: String, val core: AnmpCore, val secrets: MemorySecretStore, val store: MemoryKeyValueStore, val blobs: MemoryBlobStore, val host: TestHost) {
    val events = MutableStateFlow<List<CoreEvent>>(emptyList())
    lateinit var info: IdentityInfo

    val deviceId: String get() = info.deviceId

    suspend inline fun <reified T : CoreEvent> await(timeout: Duration = 15.seconds, crossinline predicate: (T) -> Boolean = { true }): T =
        withTimeout(timeout) { events.first { list -> list.any { it is T && predicate(it) } } }.first { it is T && predicate(it) } as T

    suspend inline fun <reified T : CoreEvent> none(within: Duration, crossinline predicate: (T) -> Boolean = { true }): Boolean =
        withTimeoutOrNull(within) { events.first { list -> list.any { it is T && predicate(it) } } } == null

    inline fun <reified T : CoreEvent> all(): List<T> = events.value.filterIsInstance<T>()
}

/** Keeps opened circle packets like the app's MessageStore does, for the history of new members. */
internal class TestHost : AnmpCoreHost {
    val history = mutableMapOf<String, MutableList<ByteArray>>()
    val shared = mutableMapOf<String, ByteArray>()

    override suspend fun circleHistory(circleId: String): List<ByteArray> = history[circleId].orEmpty()

    override suspend fun sharedContent(conversation: Conversation, hash: String, fromDevice: String): ByteArray? = shared[hash]
}

internal class World(val net: FakeNet, val scope: CoroutineScope) {
    val devices = mutableListOf<Device>()

    /** [secrets]: given ones, e.g. copied from another device like a backup does. */
    suspend fun device(
        name: String,
        timings: CoreTimings = TEST_TIMINGS,
        secrets: MemorySecretStore = MemorySecretStore(),
        config: AnmpCoreConfig = net.config(timings),
        clock: CoreClock = CoreClock.SYSTEM,
        /** Given ones keep the state of an earlier start of the same installation (an app update). */
        store: MemoryKeyValueStore = MemoryKeyValueStore(),
        blobs: MemoryBlobStore = MemoryBlobStore(),
    ): Device {
        val host = TestHost()
        val core = AnmpCore(scope, config, AnmpPlatform(secrets, store, blobs, clock = clock, connector = net.connector, log = CoreLog { }), host)
        val d = Device(name, core, secrets, store, blobs, host)
        scope.launch {
            core.events.collect { e ->
                if (e is CoreEvent.Message && e.conversation is Conversation.Circle && e.opened != null) {
                    host.history.getOrPut((e.conversation as Conversation.Circle).circleId) { mutableListOf() } += e.opened!!
                }
                d.events.update { it + e }
            }
        }
        d.info = core.start()
        core.setProfile(name)
        devices += d
        return d
    }

    /** Adds a tablet to [phone] the way the hand-over does: own device keys, the person's keys copied, chain given. */
    suspend fun addTablet(phone: Device, name: String = "Tablet"): Device {
        val secrets = MemorySecretStore()
        val sign = AnmpKeys.randomKey()
        val x = AnmpKeys.randomKey()
        val d = AnmpDevice.of(sign, x)
        secrets.put(CoreIdentity.SIGN, sign)
        secrets.put(CoreIdentity.X, x)
        for (k in listOf(CoreIdentity.PERSON, CoreIdentity.INBOX, StoreKeys.SECRET)) secrets.put(k, phone.secrets.get(k))
        val proof = assertNotNull(phone.core.addOwnDevice(B64.encode(d.signKey), B64.encode(d.publicKey), name))
        secrets.put(CoreIdentity.PROOF, B64.decode(proof))
        return device(phone.name, secrets = secrets)
    }

    /** Records an own circle message for the history, as the app does. */
    fun keep(d: Device, circleId: String, out: Outgoing?) {
        out?.opened?.let { d.host.history.getOrPut(circleId) { mutableListOf() } += it }
    }
}

internal fun world(relays: Int = 4, block: suspend World.() -> Unit) = runTest(timeout = 120.seconds) {
    withContext(Dispatchers.Default) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val w = World(FakeNet.create(relays), scope)
        try {
            w.block()
        } finally {
            w.devices.forEach { runCatching { it.core.stop() } }
            scope.cancel()
        }
    }
}

/** Polls [condition] until it holds or [timeout]. */
internal suspend fun eventually(timeout: Duration = 15.seconds, condition: suspend () -> Boolean) {
    withTimeout(timeout) {
        while (!condition()) kotlinx.coroutines.delay(50)
    }
}
