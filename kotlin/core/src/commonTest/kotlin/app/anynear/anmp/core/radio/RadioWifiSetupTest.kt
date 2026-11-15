package app.anynear.anmp.core.radio

import app.anynear.anmp.AnmpInfo
import app.anynear.anmp.core.RadioGateway
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class RadioWifiSetupTest {
    private val gateway = RadioGateway("mqtt.anynear.net", 1883, false, "gw", "secret", "msh/EU_868")

    /**
     * A radio fresh from installing: no region, Bluetooth, default channel. It keeps what is written and, like the
     * firmware, switches `ignore_mqtt` back on whenever the region changes.
     */
    private class FakeRadio(var hasWifi: Boolean = true) : RadioAdmin {
        var lora: Config.LoRaConfig = Config.LoRaConfig.Builder().build()
        var network: Config.NetworkConfig = Config.NetworkConfig.Builder().build()
        var owner: User = User.Builder().also { it.long_name = "Meshtastic 1998"; it.id = "!9e9d1998" }.build()
        var mqtt: ModuleConfig.MQTTConfig = ModuleConfig.MQTTConfig.Builder().build()
        val modules = mutableMapOf<String, ModuleConfig>()
        val configs = mutableMapOf<String, Config>()
        var channels = listOf(
            Channel.Builder().also { it.index = 0; it.role = Channel.Role.PRIMARY; it.settings = ChannelSettings.Builder().also { s -> s.psk = DefaultChannel.PSK }.build() }.build(),
            Channel.Builder().also {
                it.index = 1
                it.role = Channel.Role.SECONDARY
                it.settings = ChannelSettings.Builder().also { s -> s.name = AnmpInfo.RADIO_CHANNEL_NAME }.build()
            }.build(),
        )
        val sessions = mutableListOf<List<RadioWrite>>()
        var net = RadioNetStatus(wifi = true, mqtt = true)

        override suspend fun read(): RadioState = RadioState(
            0x9e9d1998.toInt(),
            DeviceMetadata.Builder().also { it.hasWifi = hasWifi }.build(),
            RadioProfile.DeviceConfigSnapshot(
                configs.values.toList() + config { it.lora = lora } + config { it.network = network },
                modules.values.toList() + module { it.mqtt = mqtt },
                channels,
                owner,
            ),
        )

        override suspend fun write(state: RadioState, writes: List<RadioWrite>): Boolean {
            sessions += writes
            for (w in writes) when (w) {
                is RadioWrite.Owner -> owner = w.user
                is RadioWrite.SetChannel -> channels = channels.map { if (it.index == w.channel.index) w.channel else it }
                is RadioWrite.SetModule -> w.module.mqtt?.let { mqtt = it } ?: run {
                    modules[if (w.module.telemetry != null) "t" else "n"] = w.module
                }
                is RadioWrite.SetConfig -> when {
                    w.config.lora != null -> {
                        val newRegion = w.config.lora!!.region != lora.region
                        lora = w.config.lora!!.let { if (newRegion) it.newBuilder().also { b -> b.ignore_mqtt = true }.build() else it }
                    }
                    w.config.network != null -> network = w.config.network!!
                    w.config.position != null -> configs["p"] = w.config
                    w.config.device != null -> configs["d"] = w.config
                }
            }
            return true
        }

        override suspend fun netStatus(state: RadioState) = net
    }

    private val fast = RadioWifiSetup.Timing(restart = 1.milliseconds, network = 10.milliseconds, poll = 1.milliseconds)

    @Test
    fun freshRadioGetsProfileWifiAndGatewayInThreePasses() = runTest {
        val radio = FakeRadio()
        val result = RadioWifiSetup(radio, gateway, timing = fast).run("Zuhause", "pass1234")

        val done = assertIs<RadioWifiSetup.Result.Done>(result)
        assertEquals(true, done.wifi)
        assertEquals(true, done.mqtt)
        assertEquals(2, radio.sessions.size, "profile and Wi-Fi, then the bridge; the third pass finds nothing")
        assertTrue(done.name.startsWith(DeviceName.PREFIX))

        // Pass 1: the radio profile last, Wi-Fi with name and password, no bridge yet.
        val first = radio.sessions[0]
        assertTrue(first.any { it is RadioWrite.Owner })
        assertTrue(first.last().let { it is RadioWrite.SetConfig && it.config.lora?.region == Config.LoRaConfig.RegionCode.EU_868 })
        assertTrue(first.none { it is RadioWrite.SetModule && it.module.mqtt != null })

        // Pass 2: ignore_mqtt off, uplink on channel 0 and `anynear`, MQTT module last.
        val second = radio.sessions[1]
        assertTrue(second.last().let { it is RadioWrite.SetModule && it.module.mqtt?.enabled == true })
        assertEquals(2, second.count { it is RadioWrite.SetChannel })

        assertEquals(true, radio.network.wifi_enabled)
        assertEquals("Zuhause", radio.network.wifi_ssid)
        assertEquals("pass1234", radio.network.wifi_psk)
        assertEquals("mqtt.anynear.net", radio.mqtt.address)
        assertEquals(false, radio.lora.ignore_mqtt)
        assertTrue(radio.channels.all { it.settings?.uplink_enabled == true && it.settings?.downlink_enabled == false })
        assertTrue(RadioProfile.deviations(radio.read().snapshot).isEmpty())
    }

    @Test
    fun areaOfTheListIsWritten() = runTest {
        val europe = RadioTarget(Config.LoRaConfig.RegionCode.EU_868, Config.LoRaConfig.ModemPreset.LONG_FAST, 0f, "Europa")
        val radio = FakeRadio()
        assertIs<RadioWifiSetup.Result.Done>(RadioWifiSetup(radio, gateway, timing = fast, target = europe).run("Zuhause", "pass1234"))
        assertEquals(Config.LoRaConfig.ModemPreset.LONG_FAST, radio.lora.modem_preset)
        assertEquals(0f, radio.lora.override_frequency)
        assertTrue(RadioProfile.deviations(radio.read().snapshot, europe).isEmpty())
        assertEquals("msh/EU_868", radio.mqtt.root)
        // In another LoRa region the root of the list does not fit: no gateway.
        val us = RadioTarget(Config.LoRaConfig.RegionCode.US, Config.LoRaConfig.ModemPreset.LONG_FAST, 0f, "Nordamerika")
        val other = FakeRadio()
        val done = assertIs<RadioWifiSetup.Result.Done>(RadioWifiSetup(other, gateway, timing = fast, target = us).run("Zuhause", "pass1234"))
        assertEquals(Config.LoRaConfig.RegionCode.US, other.lora.region)
        assertEquals(false, other.mqtt.enabled)
        assertTrue(done.wifi == true)
    }

    @Test
    fun withoutGatewayOnlyProfileAndWifi() = runTest {
        val radio = FakeRadio()
        val done = assertIs<RadioWifiSetup.Result.Done>(RadioWifiSetup(radio, null, timing = fast).run("Zuhause", "pass1234"))
        assertNull(done.mqtt)
        assertEquals(false, radio.mqtt.enabled)
        assertEquals(true, radio.network.wifi_enabled)
        // ignore_mqtt belongs to the radio profile, gateway or not.
        assertEquals(false, radio.lora.ignore_mqtt)
    }

    @Test
    fun radioWithoutWifiIsLeftAlone() = runTest {
        val radio = FakeRadio(hasWifi = false)
        assertEquals(RadioWifiSetup.Result.NoWifi, RadioWifiSetup(radio, gateway, timing = fast).run("Zuhause", "pass1234"))
        assertTrue(radio.sessions.isEmpty())
    }

    @Test
    fun wrongPasswordShowsAsNotInWifi() = runTest {
        val radio = FakeRadio().also { it.net = RadioNetStatus(wifi = false, mqtt = false) }
        val done = assertIs<RadioWifiSetup.Result.Done>(RadioWifiSetup(radio, gateway, timing = fast).run("Zuhause", "falsch"))
        assertEquals(false, done.wifi)
    }

    @Test
    fun existingNameStays() = runTest {
        val radio = FakeRadio().also { it.owner = RadioProfile.owner(it.owner, "Küche") }
        val done = assertIs<RadioWifiSetup.Result.Done>(RadioWifiSetup(radio, gateway, timing = fast).run("Zuhause", "pass1234"))
        assertEquals(DeviceName.longName("Küche"), done.name)
        assertTrue(radio.sessions.flatten().none { it is RadioWrite.Owner })
    }

    @Test
    fun framesSurviveLogTextAndSplitReads() {
        val payload = byteArrayOf(1, 2, 3, MeshFrames.START1, 5)
        val frame = MeshFrames.frame(payload)
        val noise = "INFO | boot\r\n".encodeToByteArray()
        val decoder = MeshFrames.Decoder()
        val bytes = noise + frame + noise + frame
        val got = bytes.toList().chunked(3).flatMap { decoder.feed(it.toByteArray()) }
        assertEquals(2, got.size)
        got.forEach { assertTrue(it.contentEquals(payload)) }
    }
}
