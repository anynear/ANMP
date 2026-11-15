package app.anynear.anmp.core.radio

import app.anynear.anmp.core.RadioGateway
import kotlinx.coroutines.delay
import org.meshtastic.proto.Config
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * „Stationär im WLAN“ for a radio on the cable (ADR 0094 §3, ADR 0095, ADR 0054 §5b), in the browser: radio profile,
 * name, Wi-Fi and, when the radio list names one, the gateway values, the same as the app writes them. Several passes,
 * each one edit session with a restart: first profile and Wi-Fi, then (once region and preset are in place) the values
 * of the radio bridge, at last a check that nothing is left. Then it asks the radio whether it got into the Wi-Fi.
 */
class RadioWifiSetup(
    private val admin: RadioAdmin,
    private val gateway: RadioGateway?,
    private val log: (String) -> Unit = {},
    private val timing: Timing = Timing(),
    /** Radio settings of the area (ADR 0054 §1b); without one the default of ADR 0006. */
    private val target: RadioTarget = RadioTarget.DEFAULT,
) {
    class Timing(
        /** Passes at most: profile and Wi-Fi, bridge, check, and one more in case a write did not hold. */
        val passes: Int = 4,
        /** Time for the radio to save and restart after an edit session. */
        val restart: Duration = 8.seconds,
        /** How long to wait for the radio to get into the Wi-Fi (and to the MQTT server). */
        val network: Duration = 60.seconds,
        val poll: Duration = 3.seconds,
    )

    enum class Phase { CONNECTING, WRITING, CHECKING, NETWORK }

    sealed interface Result {
        /** Everything written. [wifi]: in the Wi-Fi; [mqtt]: reached the server (only with a gateway); `null`: unknown. */
        data class Done(val name: String, val wifi: Boolean?, val mqtt: Boolean?) : Result

        /** No Meshtastic answered on the cable. */
        data object NoAnswer : Result

        /** The radio has no Wi-Fi (nRF52, or a model without it). */
        data object NoWifi : Result

        /** The radio did not keep what was written. */
        data object Incomplete : Result
    }

    suspend fun run(ssid: String, password: String, onPhase: (Phase) -> Unit = {}): Result {
        onPhase(Phase.CONNECTING)
        var wifiWritten = false
        var last: RadioState? = null
        for (pass in 0 until timing.passes) {
            val state = admin.read() ?: return Result.NoAnswer
            if (pass == 0 && !state.hasWifi) return Result.NoWifi
            last = state
            val writes = writes(state, ssid, password, gateway, wifiWritten, target)
            if (writes.isEmpty()) break
            onPhase(if (pass == 0) Phase.WRITING else Phase.CHECKING)
            log("Durchgang ${pass + 1}: ${writes.size} Werte")
            if (!admin.write(state, writes)) return Result.Incomplete
            wifiWritten = true
            delay(timing.restart)
            last = null
        }
        val state = last ?: return Result.Incomplete
        val name = state.snapshot.owner?.long_name.orEmpty()
        onPhase(Phase.NETWORK)
        val mark = TimeSource.Monotonic.markNow()
        var status: RadioNetStatus? = null
        while (mark.elapsedNow() < timing.network) {
            status = admin.netStatus(state) ?: status
            if (status != null && status.wifi && (gateway == null || status.mqtt)) break
            delay(timing.poll)
        }
        return Result.Done(name, wifi = status?.wifi, mqtt = status?.mqtt?.takeIf { gateway != null })
    }

    companion object {
        /**
         * What this pass writes; empty when the radio is set up. Wi-Fi name and password always in the first pass
         * (the radio does not show the password back), later only if name or switch differ.
         */
        fun writes(
            state: RadioState,
            ssid: String,
            password: String,
            gateway: RadioGateway?,
            wifiWritten: Boolean,
            target: RadioTarget = RadioTarget.DEFAULT,
        ): List<RadioWrite> {
            val snap = state.snapshot
            val owner = snap.owner?.long_name
            val suffix = DeviceName.suffixOf(owner) ?: DeviceName.derived(state.nodeNum)
            val deviations = RadioProfile.deviations(snap, target) - ProfileDeviation.NAME
            val profile = RadioProfile.writes(snap, deviations, nameSuffix = suffix.takeIf { !DeviceName.isCurrentName(owner) }, target = target)
            val net = snap.network
            val network = if (!wifiWritten || net?.wifi_enabled != true || net.wifi_ssid != ssid) {
                val wifi = (net?.newBuilder() ?: Config.NetworkConfig.Builder()).also {
                    it.wifi_enabled = true
                    it.wifi_ssid = ssid
                    it.wifi_psk = password
                }.build()
                RadioWrite.SetConfig(config { it.network = wifi })
            } else {
                null
            }
            // The bridge after the profile: GatewaySetup plans nothing while region or preset still differ.
            val bridge = GatewaySetup.plan(snap, gateway, target).map { it.write }
            val lora = profile.filter { it is RadioWrite.SetConfig && it.config.lora != null }
            return buildList {
                addAll(profile - lora.toSet())
                network?.let(::add)
                addAll(bridge.filter { !(it is RadioWrite.SetConfig && it.config.lora != null && lora.isNotEmpty()) && it !is RadioWrite.SetModule })
                addAll(lora.ifEmpty { bridge.filter { it is RadioWrite.SetConfig && it.config.lora != null } })
                addAll(bridge.filterIsInstance<RadioWrite.SetModule>())
            }
        }
    }
}
