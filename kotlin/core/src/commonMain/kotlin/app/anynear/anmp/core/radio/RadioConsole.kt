package app.anynear.anmp.core.radio

import app.anynear.anmp.core.firmware.SerialLine
import kotlinx.coroutines.delay
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.NetworkConnectionStatus
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Frames of Meshtastic's stream API (serial line and TCP port 4403): `0x94 0xC3`, length in two bytes (big endian),
 * then a `ToRadio` or `FromRadio` message. Bytes outside a frame are the radio's text log and are dropped.
 */
object MeshFrames {
    const val START1 = 0x94.toByte()
    const val START2 = 0xC3.toByte()
    const val MAX_PAYLOAD = 512

    fun frame(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "zu groß: ${payload.size}" }
        return byteArrayOf(START1, START2, (payload.size shr 8).toByte(), payload.size.toByte()) + payload
    }

    /** Collects bytes as they come and hands out every complete frame's payload. */
    class Decoder {
        private var buffer = ByteArray(0)

        fun feed(bytes: ByteArray): List<ByteArray> {
            buffer += bytes
            val out = mutableListOf<ByteArray>()
            var at = 0
            while (true) {
                while (at < buffer.size && buffer[at] != START1) at++
                if (buffer.size - at < 4) break
                if (buffer[at + 1] != START2) {
                    at++
                    continue
                }
                val size = ((buffer[at + 2].toInt() and 0xFF) shl 8) or (buffer[at + 3].toInt() and 0xFF)
                if (size > MAX_PAYLOAD) {
                    at++
                    continue
                }
                if (buffer.size - at < 4 + size) break
                out += buffer.copyOfRange(at + 4, at + 4 + size)
                at += 4 + size
            }
            buffer = buffer.copyOfRange(at, buffer.size)
            return out
        }

        fun clear() {
            buffer = ByteArray(0)
        }
    }
}

/** What the radio reported on connect: its node number, what it is and its whole configuration. */
class RadioState(
    val nodeNum: Int,
    val metadata: DeviceMetadata?,
    val snapshot: RadioProfile.DeviceConfigSnapshot,
) {
    val hasWifi: Boolean get() = metadata?.hasWifi == true
}

/** Whether the radio got into the Wi-Fi and, as a gateway, to the MQTT server (Meshtastic `DeviceConnectionStatus`). */
data class RadioNetStatus(val wifi: Boolean, val mqtt: Boolean)

/** One value to write, the same in the app and in the browser. */
sealed interface RadioWrite {
    data class Owner(val user: User) : RadioWrite
    data class SetConfig(val config: Config) : RadioWrite
    data class SetModule(val module: ModuleConfig) : RadioWrite
    data class SetChannel(val channel: Channel) : RadioWrite

    fun admin(): AdminMessage = when (this) {
        is Owner -> admin { it.set_owner = user }
        is SetConfig -> admin { it.set_config = config }
        is SetModule -> admin { it.set_module_config = module }
        is SetChannel -> admin { it.set_channel = channel }
    }
}

/** An admin message, built. */
internal fun admin(set: (AdminMessage.Builder) -> Unit): AdminMessage = AdminMessage.Builder().also(set).build()

internal fun config(set: (Config.Builder) -> Unit): Config = Config.Builder().also(set).build()

internal fun module(set: (ModuleConfig.Builder) -> Unit): ModuleConfig = ModuleConfig.Builder().also(set).build()

/** What the radio does through [RadioConsole]; a fake in the tests. */
interface RadioAdmin {
    /** Asks for the whole configuration; waits for the radio (it may be restarting). `null`: no answer. */
    suspend fun read(): RadioState?

    /** Writes [writes] in one edit session; the radio saves and may restart. `false`: it did not take them. */
    suspend fun write(state: RadioState, writes: List<RadioWrite>): Boolean

    /** Wi-Fi and MQTT as the radio sees them; `null` if it does not answer. */
    suspend fun netStatus(state: RadioState): RadioNetStatus?
}

/**
 * Meshtastic over a serial line ([SerialLine], in the browser WebSerial): the radio's stream API, nothing of our own.
 * Settings go as admin messages to the radio itself, with the session key it hands out (firmware 2.5 and later).
 */
class RadioConsole(
    private val line: SerialLine,
    private val log: (String) -> Unit = {},
    private val random: Random = Random,
    private val timing: Timing = Timing(),
) : RadioAdmin {
    class Timing(
        /** How long the radio may take to answer at all: the first start after installing takes a while. */
        val answer: Duration = 60.seconds,
        /** Within the configuration, at most this long between two messages. */
        val quiet: Duration = 5.seconds,
        /** Answer to a single admin request. */
        val reply: Duration = 5.seconds,
        /** Between the messages of an edit session, so that the radio keeps up. */
        val pause: Duration = 200.milliseconds,
    )

    private val decoder = MeshFrames.Decoder()
    private val inbox = ArrayDeque<FromRadio>()

    private suspend fun send(message: ToRadio) {
        line.write(MeshFrames.frame(ToRadio.ADAPTER.encode(message)))
    }

    /** Next message from the radio, at most [wait]; `null` if none came. */
    private suspend fun next(wait: Duration): FromRadio? {
        inbox.removeFirstOrNull()?.let { return it }
        val mark = TimeSource.Monotonic.markNow()
        while (true) {
            val left = wait - mark.elapsedNow()
            if (left <= Duration.ZERO) return null
            val bytes = line.read(left.inWholeMilliseconds.toInt().coerceIn(1, 1000))
            if (bytes.isEmpty()) continue
            for (payload in decoder.feed(bytes)) {
                val message = runCatching { FromRadio.ADAPTER.decode(payload) }.getOrNull() ?: continue
                inbox.addLast(message)
            }
            inbox.removeFirstOrNull()?.let { return it }
        }
    }

    override suspend fun read(): RadioState? {
        val mark = TimeSource.Monotonic.markNow()
        while (mark.elapsedNow() < timing.answer) {
            readOnce()?.let { return it }
            delay(1.seconds)
        }
        log("Funkgerät antwortet nicht")
        return null
    }

    private suspend fun readOnce(): RadioState? {
        decoder.clear()
        inbox.clear()
        // Wakes the serial API (as the Meshtastic clients do), then asks for everything.
        line.write(ByteArray(32) { MeshFrames.START2 })
        delay(100.milliseconds)
        val id = random.nextInt(1, Int.MAX_VALUE)
        send(ToRadio.Builder().also { it.want_config_id = id }.build())
        var nodeNum = 0
        var metadata: DeviceMetadata? = null
        val configs = mutableListOf<Config>()
        val modules = mutableListOf<ModuleConfig>()
        val channels = mutableListOf<Channel>()
        val users = mutableMapOf<Int, User>()
        var first = true
        while (true) {
            val m = next(if (first) timing.reply else timing.quiet) ?: return null
            first = false
            m.my_info?.let { nodeNum = it.my_node_num }
            m.metadata?.let { metadata = it }
            m.node_info?.let { n -> n.user?.let { users[n.num] = it } }
            m.config?.let { configs += it }
            m.moduleConfig?.let { modules += it }
            m.channel?.let { channels += it }
            if (m.config_complete_id == id) break
        }
        if (nodeNum == 0) return null
        log("Funkgerät ${nodeNum.toUInt().toString(16)}: ${configs.size} Einstellungen, ${channels.size} Kanäle")
        return RadioState(nodeNum, metadata, RadioProfile.DeviceConfigSnapshot(configs, modules, channels.sortedBy { it.index }, users[nodeNum]))
    }

    /** Sends [admin] to the radio itself; with [reply] waits for its answer to this request. */
    private suspend fun admin(state: RadioState, admin: AdminMessage, reply: Boolean): AdminMessage? {
        val id = random.nextInt(1, Int.MAX_VALUE)
        val data = Data.Builder().also {
            it.portnum = PortNum.ADMIN_APP
            it.payload = AdminMessage.ADAPTER.encode(admin).toByteString()
            it.want_response = reply
        }.build()
        val packet = MeshPacket.Builder().also {
            it.to = state.nodeNum
            it.id = id
            it.channel = 0
            it.decoded = data
        }.build()
        send(ToRadio.Builder().also { it.packet = packet }.build())
        if (!reply) return null
        val mark = TimeSource.Monotonic.markNow()
        while (true) {
            val left = timing.reply - mark.elapsedNow()
            if (left <= Duration.ZERO) return null
            val m = next(left) ?: return null
            val data = m.packet?.decoded ?: continue
            if (data.portnum != PortNum.ADMIN_APP || data.request_id != id) continue
            return runCatching { AdminMessage.ADAPTER.decode(data.payload) }.getOrNull()
        }
    }

    override suspend fun write(state: RadioState, writes: List<RadioWrite>): Boolean {
        if (writes.isEmpty()) return true
        // The session key comes with every answer of the radio; owner is the smallest one.
        val key = admin(state, admin { it.get_owner_request = true }, reply = true)?.session_passkey
        if (key == null) {
            log("kein Sitzungsschlüssel vom Funkgerät")
            return false
        }
        val all = listOf(admin { it.begin_edit_settings = true }) + writes.map { it.admin() } + admin { it.commit_edit_settings = true }
        for (a in all) {
            admin(state, a.newBuilder().also { it.session_passkey = key }.build(), reply = false)
            delay(timing.pause)
        }
        log("geschrieben: ${writes.joinToString { it::class.simpleName ?: "?" }}")
        return true
    }

    override suspend fun netStatus(state: RadioState): RadioNetStatus? {
        val status: NetworkConnectionStatus? =
            admin(state, admin { it.get_device_connection_status_request = true }, reply = true)
                ?.get_device_connection_status_response?.wifi?.status
        return status?.let { RadioNetStatus(wifi = it.is_connected, mqtt = it.is_mqtt_connected) }
    }
}
