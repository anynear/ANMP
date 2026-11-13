package app.anynear.anmp.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Clock

/** Radio in memory (ADR 0057): who hears whom is set with [link]; everyone hears everyone if nothing is set. */
internal class FakeAir {
    private val radios = mutableListOf<FakeRadio>()
    private val reach = mutableSetOf<Pair<FakeRadio, FakeRadio>>()
    private var packetIds = 0

    /** All packets that went out, in order. */
    val sent = mutableListOf<Pair<FakeRadio, ByteArray>>()

    /** Hop limit of each packet in [sent], same order (`null`: the radio's own). */
    val hopLimits = mutableListOf<Int?>()

    /** Hop limit per packet ID. */
    val hopsOf = mutableMapOf<Int, Int?>()

    fun radio(name: String): FakeRadio = FakeRadio(name, this).also { radios += it }

    /** [a] and [b] hear each other; from the first call on, only linked radios hear each other. */
    fun link(a: FakeRadio, b: FakeRadio) {
        reach += a to b
        reach += b to a
    }

    private fun hears(from: FakeRadio, to: FakeRadio) = to != from && (reach.isEmpty() || (from to to) in reach)

    suspend fun send(from: FakeRadio, payload: ByteArray, hopLimit: Int?): RadioSent {
        val id = ++packetIds
        sent += from to payload
        hopLimits += hopLimit
        hopsOf[id] = hopLimit
        val frame = RadioFrame(payload, fromNode = from.node, packetId = id, atMillis = Clock.System.now().toEpochMilliseconds(), hops = hopLimit, snr = 5f)
        radios.filter { hears(from, it) && it.ready.value }.forEach { it.frames.emit(frame) }
        return RadioSent(id)
    }

    private var nodes = 0

    fun nextNode() = ++nodes
}

internal class FakeRadio(val name: String, private val air: FakeAir) : RadioPort {
    val node = air.nextNode()
    override val ready = MutableStateFlow(true)
    val frames = MutableSharedFlow<RadioFrame>(extraBufferCapacity = 256)
    override val incoming: Flow<RadioFrame> = frames

    /** What the radio reports after each send, in turn; the last list repeats. */
    val answers = ArrayDeque(listOf(listOf(RadioSendState.HANDED, RadioSendState.RELAYED)))

    /** Other packets heard (ADR 0090), e.g. telemetry of another radio. */
    val heardFlow = MutableSharedFlow<RadioHeard>(extraBufferCapacity = 16)
    override val heard: Flow<RadioHeard> = heardFlow

    override suspend fun broadcast(payload: ByteArray, hopLimit: Int?): RadioSent? {
        if (!ready.value) return null
        val sent = air.send(this, payload, hopLimit)
        val states = if (answers.size > 1) answers.removeFirst() else answers.first()
        return RadioSent(sent.packetId, flowOf(*states.toTypedArray()))
    }
}

/** Settings of a test device; [online] can be switched off to make it ask patrons. */
internal class FakeRadioHost(var online: Boolean = true, var serve: Boolean = true, val unwanted: MutableSet<String> = mutableSetOf()) : RadioHost {
    override fun online(): Boolean = online

    override fun mayServeOthers(): Boolean = serve

    override fun stillWanted(mid: String): Boolean = mid !in unwanted
}

internal val TEST_RADIO_TIMINGS = RadioTimings(
    offlineBeforeAsk = kotlin.time.Duration.ZERO,
    askEvery = kotlin.time.Duration.parse("2s"),
    askAnswerWait = kotlin.time.Duration.parse("4s"),
    pollEvery = kotlin.time.Duration.parse("200ms"),
    patronWaitMin = kotlin.time.Duration.parse("50ms"),
    patronWaitMax = kotlin.time.Duration.parse("100ms"),
    tick = kotlin.time.Duration.parse("100ms"),
    // Existing tests send at once and never escalate; the tests of ADR 0087 set their own.
    internetFirst = kotlin.time.Duration.ZERO,
    hopRetry = kotlin.time.Duration.parse("1h"),
    receiptDelay = kotlin.time.Duration.parse("100ms"),
    retryCleanup = kotlin.time.Duration.parse("200ms"),
)
