package app.anynear.anmp.core.radio

import app.anynear.anmp.AnmpInfo
import app.anynear.anmp.core.RadioGateway
import app.anynear.anmp.core.radio.GatewaySetup.Step
import app.anynear.anmp.core.radio.RadioProfile.DeviceConfigSnapshot
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.User
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every radio on Wi-Fi is a gateway (ADR 0054 §5b); [GatewaySetup.plan] says what to write and in which order. */
class GatewaySetupTest {
    private val gateway = RadioGateway("mqtt.anynear.net", 1883, false, "gw", "open", "msh/EU_868")

    @Test
    fun radioOnWifiBecomesGateway() {
        val steps = GatewaySetup.plan(snapshot(wifi = true), gateway)
        assertEquals(listOf("uplink 0", "uplink 1", "mqtt"), steps.map(::label))
        val mqtt = assertIs<Step.Mqtt>(steps.last()).mqtt
        assertTrue(mqtt.enabled)
        assertEquals("mqtt.anynear.net", mqtt.address)
        assertEquals("gw", mqtt.username)
        assertEquals("open", mqtt.password)
        assertEquals("msh/EU_868", mqtt.root)
        assertTrue(mqtt.encryption_enabled)
        assertEquals(false, mqtt.json_enabled)
        assertEquals(false, mqtt.map_reporting_enabled)
        assertEquals(false, mqtt.proxy_to_client_enabled)
        assertEquals(false, mqtt.tls_enabled)
        steps.filterIsInstance<Step.Uplink>().forEach {
            assertEquals(true, it.channel.settings?.uplink_enabled)
            assertEquals(false, it.channel.settings?.downlink_enabled, "downlink only with phase 5")
        }
    }

    @Test
    fun foreignChannelsGetNoUplink() {
        val steps = GatewaySetup.plan(snapshot(wifi = true, extra = listOf(channel(2, "Mesh Hessen", Channel.Role.SECONDARY))), gateway)
        assertEquals(listOf("uplink 0", "uplink 1", "mqtt"), steps.map(::label))
    }

    /** Slot 0 private (own key), the default channel on slot 1: only slot 1 and `anynear` go up (tablet test 15.11.2026). */
    @Test
    fun privatePrimaryIsNeverUploaded() {
        val channels = listOf(
            channel(0, "k3vin", Channel.Role.PRIMARY, psk = PRIVATE),
            channel(1, "", Channel.Role.SECONDARY),
            channel(3, AnmpInfo.RADIO_CHANNEL_NAME, Channel.Role.SECONDARY, psk = PRIVATE),
        )
        val steps = GatewaySetup.plan(snapshot(wifi = true, channels = channels), gateway)
        assertEquals(listOf("uplink 1", "uplink 3", "mqtt"), steps.map(::label))
        assertEquals(listOf(1, 3), GatewaySetup.gatewayChannels(channels, RadioProfile.lora(null)).map { it.index })
    }

    /** An uplink an earlier version set on the private slot 0 is switched off again, the rest stays as it is. */
    @Test
    fun wrongUplinkOnPrivateChannelGoesOff() {
        val channels = listOf(
            channel(0, "k3vin", Channel.Role.PRIMARY, uplink = true, psk = PRIVATE),
            channel(1, "", Channel.Role.SECONDARY),
            channel(3, AnmpInfo.RADIO_CHANNEL_NAME, Channel.Role.SECONDARY, uplink = true, psk = PRIVATE),
        )
        val steps = GatewaySetup.plan(snapshot(wifi = true, channels = channels, mqtt = GatewaySetup.mqtt(null, gateway)), gateway)
        assertEquals(listOf("uplink 0", "uplink 1"), steps.map(::label))
        val (off, on) = steps.map { assertIs<Step.Uplink>(it).channel.settings!! }
        assertEquals(false, off.uplink_enabled)
        assertEquals(false, off.downlink_enabled)
        assertEquals(PRIVATE, off.psk, "the rest of the channel stays")
        assertEquals("k3vin", off.name)
        assertEquals(true, on.uplink_enabled)
    }

    /** Leaving the gateway role switches the uplink off on every channel, also on one set wrongly before. */
    @Test
    fun backOnBluetoothSwitchesWrongUplinkOffToo() {
        val channels = listOf(
            channel(0, "k3vin", Channel.Role.PRIMARY, uplink = true, psk = PRIVATE),
            channel(1, "", Channel.Role.SECONDARY, uplink = true),
        )
        val steps = GatewaySetup.plan(snapshot(wifi = false, channels = channels, mqtt = GatewaySetup.mqtt(null, gateway)), gateway)
        assertEquals(listOf("uplink 0", "uplink 1", "mqtt"), steps.map(::label))
    }

    @Test
    fun defaultChannelLikeTheFirmware() {
        val shortSlow = RadioProfile.lora(null)
        assertTrue(DefaultChannel.isDefault(channel(0, "", Channel.Role.PRIMARY), shortSlow))
        assertTrue(DefaultChannel.isDefault(channel(2, "ShortSlow", Channel.Role.SECONDARY), shortSlow), "name of the preset")
        assertTrue(DefaultChannel.isDefault(channel(2, "", Channel.Role.SECONDARY), null), "empty name without preset")
        assertEquals(false, DefaultChannel.isDefault(channel(2, "ShortSlow", Channel.Role.SECONDARY), null))
        assertEquals(false, DefaultChannel.isDefault(channel(2, "LongFast", Channel.Role.SECONDARY), shortSlow), "name of another preset")
        assertEquals(false, DefaultChannel.isDefault(channel(0, "k3vin", Channel.Role.PRIMARY), shortSlow), "own name")
        assertEquals(false, DefaultChannel.isDefault(channel(0, "", Channel.Role.PRIMARY, psk = PRIVATE), shortSlow), "own key")
        assertEquals(false, DefaultChannel.isDefault(channel(0, "", Channel.Role.PRIMARY, psk = ByteString.EMPTY), shortSlow), "unencrypted")
        assertEquals(false, DefaultChannel.isDefault(channel(2, "", Channel.Role.DISABLED), shortSlow))
        val custom = shortSlow.newBuilder().also { it.use_preset = false }.build()
        assertTrue(DefaultChannel.isDefault(channel(0, "Custom", Channel.Role.PRIMARY), custom))
        // Two default channels: the primary one counts.
        assertEquals(4, DefaultChannel.index(listOf(channel(1, "", Channel.Role.SECONDARY), channel(4, "", Channel.Role.PRIMARY)), shortSlow))
        assertEquals(1, DefaultChannel.index(listOf(channel(0, "k3vin", Channel.Role.PRIMARY, psk = PRIVATE), channel(1, "", Channel.Role.SECONDARY)), shortSlow))
        assertNull(DefaultChannel.index(listOf(channel(0, "k3vin", Channel.Role.PRIMARY, psk = PRIVATE)), shortSlow))
    }

    @Test
    fun gatewayInLineWritesNothing() {
        val done = snapshot(wifi = true, uplink = true, mqtt = GatewaySetup.mqtt(null, gateway))
        assertEquals(emptyList(), GatewaySetup.plan(done, gateway))
    }

    @Test
    fun newLoginIsWrittenAgain() {
        val old = snapshot(wifi = true, uplink = true, mqtt = GatewaySetup.mqtt(null, gateway))
        val steps = GatewaySetup.plan(old, gateway.copy(password = "new"))
        assertEquals(listOf("mqtt"), steps.map(::label))
        assertEquals("new", assertIs<Step.Mqtt>(steps.single()).mqtt.password)
    }

    @Test
    fun ownPortAndTlsGoIntoTheAddress() {
        val steps = GatewaySetup.plan(snapshot(wifi = true, uplink = true), gateway.copy(port = 1884))
        assertEquals("mqtt.anynear.net:1884", assertIs<Step.Mqtt>(steps.last()).mqtt.address)
        val tls = GatewaySetup.plan(snapshot(wifi = true, uplink = true), gateway.copy(port = 8883, tls = true))
        assertEquals("mqtt.anynear.net", assertIs<Step.Mqtt>(tls.last()).mqtt.address)
        assertEquals(true, assertIs<Step.Mqtt>(tls.last()).mqtt.tls_enabled)
    }

    /** ADR 0054 §2: the firmware switches `ignore_mqtt` back on with every new region, so the region comes first. */
    @Test
    fun nothingBeforeRegionAndPreset() {
        val otherRegion = RadioProfile.lora(null).newBuilder().also { it.region = Config.LoRaConfig.RegionCode.US }.build()
        assertEquals(emptyList(), GatewaySetup.plan(snapshot(wifi = true, lora = otherRegion), gateway))
        val otherPreset = RadioProfile.lora(null).newBuilder().also { it.modem_preset = Config.LoRaConfig.ModemPreset.LONG_FAST }.build()
        assertEquals(emptyList(), GatewaySetup.plan(snapshot(wifi = true, lora = otherPreset), gateway))
    }

    @Test
    fun ignoreMqttAfterRegionThenMqttModuleLast() {
        val ignoring = RadioProfile.lora(null).newBuilder().also { it.ignore_mqtt = true }.build()
        val steps = GatewaySetup.plan(snapshot(wifi = true, uplink = true, lora = ignoring), gateway)
        assertEquals(listOf("lora", "mqtt"), steps.map(::label))
        val lora = assertIs<Step.LoraMqtt>(steps.first()).lora
        assertEquals(false, lora.ignore_mqtt)
        assertEquals(true, lora.config_ok_to_mqtt)
        assertEquals(Config.LoRaConfig.RegionCode.EU_868, lora.region, "the region stays as it is")
    }

    @Test
    fun bluetoothRadioIsNoGateway() {
        assertEquals(emptyList(), GatewaySetup.plan(snapshot(wifi = false), gateway))
    }

    @Test
    fun backOnBluetoothSwitchesOwnGatewayOff() {
        val wasGateway = snapshot(wifi = false, uplink = true, mqtt = GatewaySetup.mqtt(null, gateway))
        val steps = GatewaySetup.plan(wasGateway, gateway)
        assertEquals(listOf("uplink 0", "uplink 1", "mqtt"), steps.map(::label))
        steps.filterIsInstance<Step.Uplink>().forEach { assertEquals(false, it.channel.settings?.uplink_enabled) }
        assertEquals(false, assertIs<Step.Mqtt>(steps.last()).mqtt.enabled)
    }

    @Test
    fun withoutGatewayInTheListOwnGatewayGoesOff() {
        val wasGateway = snapshot(wifi = true, uplink = true, mqtt = GatewaySetup.mqtt(null, gateway))
        assertEquals(false, assertIs<Step.Mqtt>(GatewaySetup.plan(wasGateway, null).last()).mqtt.enabled)
    }

    @Test
    fun foreignMqttStaysWhenNoGateway() {
        val foreign = GatewaySetup.mqtt(null, gateway.copy(host = "mqtt.example.org"))
        assertEquals(emptyList(), GatewaySetup.plan(snapshot(wifi = false, uplink = true, mqtt = foreign), gateway))
        assertEquals(emptyList(), GatewaySetup.plan(snapshot(wifi = true, uplink = true, mqtt = foreign), null))
    }

    @Test
    fun ownDomain() {
        assertTrue(GatewaySetup.isOwn("mqtt.anynear.net"))
        assertTrue(GatewaySetup.isOwn("MQTT.anynear.net:1884"))
        assertEquals(false, GatewaySetup.isOwn("mqtt.anynear.net.example.org"))
        assertEquals(false, GatewaySetup.isOwn("notanynear.net"))
    }

    private fun label(step: Step) = when (step) {
        is Step.Uplink -> "uplink ${step.channel.index}"
        is Step.LoraMqtt -> "lora"
        is Step.Mqtt -> "mqtt"
    }

    private fun snapshot(
        wifi: Boolean,
        uplink: Boolean = false,
        mqtt: ModuleConfig.MQTTConfig? = null,
        lora: Config.LoRaConfig = RadioProfile.lora(null),
        extra: List<Channel> = emptyList(),
        channels: List<Channel>? = null,
    ) = DeviceConfigSnapshot(
        configs = listOf(
            Config.Builder().also { it.lora = lora }.build(),
            Config.Builder().also { it.device = RadioProfile.device(null) }.build(),
            Config.Builder().also { it.network = Config.NetworkConfig.Builder().also { n -> n.wifi_enabled = wifi }.build() }.build(),
        ),
        modules = listOfNotNull(
            ModuleConfig.Builder().also { it.telemetry = RadioProfile.telemetry(null) }.build(),
            mqtt?.let { m -> ModuleConfig.Builder().also { it.mqtt = m }.build() },
        ),
        channels = channels ?: (listOf(
            channel(0, "", Channel.Role.PRIMARY, uplink),
            channel(1, AnmpInfo.RADIO_CHANNEL_NAME, Channel.Role.SECONDARY, uplink, PRIVATE),
        ) + extra),
        owner = User.Builder().also { it.long_name = "anynear.app | Fuchs 27" }.build(),
    )

    private fun channel(index: Int, name: String, role: Channel.Role, uplink: Boolean = false, psk: ByteString = DefaultChannel.PSK) = Channel.Builder().also { c ->
        c.index = index
        c.role = role
        c.settings = ChannelSettings.Builder().also {
            it.name = name
            it.psk = psk
            it.uplink_enabled = uplink
        }.build()
    }.build()

    private companion object {
        val PRIVATE: ByteString = ByteArray(32) { 7 }.toByteString()
    }
}
