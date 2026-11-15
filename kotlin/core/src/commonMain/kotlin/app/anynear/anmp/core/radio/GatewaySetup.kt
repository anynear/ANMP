package app.anynear.anmp.core.radio

import app.anynear.anmp.AnmpInfo
import app.anynear.anmp.core.RadioGateway
import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config
import org.meshtastic.proto.ModuleConfig

/**
 * Every radio on Wi-Fi is a gateway of the radio bridge (ADR 0054 §5b, ADR 0094 §3): it uploads what it hears on
 * the default channel ([DefaultChannel], „channel 0“, on whatever slot) and on `anynear` to the anynear MQTT server,
 * and nothing else. Address and login come from the signed radio list
 * ([RadioGateway]); without them, or on Bluetooth, the radio is no gateway. Not part of the radio profile (ADR 0006):
 * the app keeps these values in line silently on every connection, without a question.
 */
object GatewaySetup {
    /** Domain of the anynear MQTT servers; an MQTT setting there is ours and may be switched off again. */
    private const val OWN_DOMAIN = "anynear.net"

    /** One write, in the order of [plan]. */
    sealed interface Step {
        /** Uplink on, downlink off (until phase 5) on this channel. */
        data class Uplink(val channel: Channel) : Step

        /** `ok_to_mqtt` on and `ignore_mqtt` off, once region and preset are in place (ADR 0054 §2). */
        data class LoraMqtt(val lora: Config.LoRaConfig) : Step

        /** The MQTT module itself; the radio restarts afterwards, so it is the last step. */
        data class Mqtt(val mqtt: ModuleConfig.MQTTConfig) : Step

        /** The same as a plain write, for a radio on the cable ([RadioWifiSetup]). */
        val write: RadioWrite
            get() = when (this) {
                is Uplink -> RadioWrite.SetChannel(channel)
                is LoraMqtt -> RadioWrite.SetConfig(config { it.lora = lora })
                is Mqtt -> RadioWrite.SetModule(module { it.mqtt = mqtt })
            }
    }

    /** Does the root of [gateway] belong to the LoRa region of [target] (`msh/EU_868` for EU_868, ADR 0054 §5b)? */
    fun servesRegion(gateway: RadioGateway, target: RadioTarget): Boolean =
        gateway.root.substringAfterLast('/') == target.region.name

    /**
     * What to write so that the radio is a gateway exactly when it is on Wi-Fi and the radio list names one. Empty:
     * nothing to do. While region or preset still deviate from the radio profile nothing is written: the profile comes
     * first, and only after it `ignore_mqtt` (the firmware switches it back on with every new region).
     */
    fun plan(snapshot: RadioProfile.DeviceConfigSnapshot, gateway: RadioGateway?, target: RadioTarget = RadioTarget.DEFAULT): List<Step> {
        // The server takes only the root it lists (`msh/EU_868`); a radio in another LoRa region is no gateway for now.
        val wanted = gateway != null && snapshot.network?.wifi_enabled == true && servesRegion(gateway, target)
        val current = snapshot.mqtt
        if (!wanted) {
            // Only switch off what is ours; an MQTT setting of someone else stays as it is.
            if (current == null || !current.enabled || !isOwn(current.address)) return emptyList()
            return buildList {
                // Every channel: a private one may have been switched on wrongly by an earlier app version.
                snapshot.channels.filter(::mqttOn).forEach { add(Step.Uplink(withUplink(it, on = false))) }
                add(Step.Mqtt(current.newBuilder().also { it.enabled = false }.build()))
            }
        }
        gateway!!
        val deviations = RadioProfile.deviations(snapshot, target)
        if (deviations.any { it in RadioProfile.LORA_PARTS }) return emptyList()
        return buildList {
            val ours = gatewayChannels(snapshot.channels, snapshot.lora).map { it.index }.toSet()
            snapshot.channels.filter { it.role != Channel.Role.DISABLED }.forEach {
                val own = it.index in ours
                // Ours: uplink on, downlink off. Any other channel (private, of another app) never goes to our server.
                if (own && (it.settings?.uplink_enabled != true || it.settings?.downlink_enabled == true)) add(Step.Uplink(withUplink(it, on = true)))
                if (!own && mqttOn(it)) add(Step.Uplink(withUplink(it, on = false)))
            }
            if (ProfileDeviation.MQTT in deviations) add(Step.LoraMqtt(RadioProfile.mqtt(snapshot.lora)))
            val target = mqtt(current, gateway)
            if (!matches(current, target)) add(Step.Mqtt(target))
        }
    }

    /** Target MQTT module: our server, encrypted packets only, no JSON, no map reports, the radio's own Wi-Fi. */
    fun mqtt(current: ModuleConfig.MQTTConfig?, gateway: RadioGateway): ModuleConfig.MQTTConfig =
        (current?.newBuilder() ?: ModuleConfig.MQTTConfig.Builder()).also {
            it.enabled = true
            it.address = gateway.address
            it.username = gateway.user
            it.password = gateway.password
            it.root = gateway.root
            it.tls_enabled = gateway.tls
            it.encryption_enabled = true
            it.json_enabled = false
            it.map_reporting_enabled = false
            it.proxy_to_client_enabled = false
        }.build()

    private fun matches(current: ModuleConfig.MQTTConfig?, target: ModuleConfig.MQTTConfig): Boolean =
        current != null && current.enabled == target.enabled && current.address == target.address &&
            current.username == target.username && current.password == target.password && current.root == target.root &&
            current.tls_enabled == target.tls_enabled && current.encryption_enabled == target.encryption_enabled &&
            current.json_enabled == target.json_enabled && current.map_reporting_enabled == target.map_reporting_enabled &&
            current.proxy_to_client_enabled == target.proxy_to_client_enabled

    /**
     * The default channel ([DefaultChannel], „channel 0“, on whatever slot) and the radio channel `anynear`: the
     * channels the anynear MQTT server takes. A private primary channel is not among them.
     */
    fun gatewayChannels(channels: List<Channel>, lora: Config.LoRaConfig?): List<Channel> {
        val default = DefaultChannel.index(channels, lora)
        return channels.filter {
            it.index == default || (it.role == Channel.Role.SECONDARY && it.settings?.name == AnmpInfo.RADIO_CHANNEL_NAME)
        }
    }

    private fun mqttOn(channel: Channel): Boolean =
        channel.settings?.uplink_enabled == true || channel.settings?.downlink_enabled == true

    private fun withUplink(channel: Channel, on: Boolean): Channel {
        val settings = channel.settings ?: org.meshtastic.proto.ChannelSettings.Builder().build()
        return channel.newBuilder().also {
            it.settings = settings.newBuilder().also { s ->
                s.uplink_enabled = on
                s.downlink_enabled = false
            }.build()
        }.build()
    }

    /** Host of an MQTT address (`host` or `host:port`) is under [OWN_DOMAIN]. */
    internal fun isOwn(address: String): Boolean {
        val host = address.substringBefore(':').lowercase()
        return host == OWN_DOMAIN || host.endsWith(".$OWN_DOMAIN")
    }
}
