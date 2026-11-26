package app.anynear.anmp.core.radio

import app.anynear.anmp.core.RadioGateway
import app.anynear.anmp.core.RadioList
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Radio settings per area of the radio list (ADR 0054 §1b): profile, silent change, gateway region. */
class RadioTargetTest {
    private val list = RadioList.parse(
        """{"issued":1,"regions":[
            {"name":"Hessen","area":[[49.4,7.7],[51.7,7.7],[51.7,10.3],[49.4,10.3]],"lora":"EU_868","preset":"SHORT_SLOW","frequency":869.525},
            {"name":"Europa","area":[[34,-25],[72,-25],[72,24],[34,24]],"lora":"EU_868","preset":"LONG_FAST"},
            {"name":"Nordamerika","area":[[14,-170],[72,-170],[72,-52],[14,-52]],"lora":"US","preset":"LONG_FAST"},
            {"name":"Zukunft","area":[[0,0],[1,0],[1,1]],"lora":"EU_868","preset":"LONG_FAST"}]}""",
    )
    private val europe = RadioTarget.of(list.region("Europa")!!)!!
    private val us = RadioTarget.of(list.region("Nordamerika")!!)!!
    private val known = list.regions.mapNotNull(RadioTarget::of)

    @Test
    fun hessenIsTheDefaultProfile() {
        assertEquals(RadioTarget.DEFAULT.copy(area = "Hessen"), RadioTarget.of(list.region("Hessen")!!))
        assertEquals("EU_868, ShortSlow", RadioTarget.DEFAULT.label)
        assertEquals("US, LongFast", us.label)
    }

    @Test
    fun loraFollowsTheArea() {
        val lora = RadioProfile.lora(null, us)
        assertEquals(Config.LoRaConfig.RegionCode.US, lora.region)
        assertEquals(Config.LoRaConfig.ModemPreset.LONG_FAST, lora.modem_preset)
        assertEquals(0f, lora.override_frequency, "default slot of region and preset")
        assertEquals(0, lora.tx_power)
        assertEquals(Hessenmesh.HOP_LIMIT, lora.hop_limit)
        assertEquals(emptySet(), RadioProfile.deviations(snapshot(lora), us) - ProfileDeviation.NAME)
        // The firmware reports the limit of the region instead of 0.
        assertEquals(emptySet(), RadioProfile.deviations(snapshot(lora.newBuilder().also { it.tx_power = 30 }.build()), us) - ProfileDeviation.NAME)
        assertEquals(setOf(ProfileDeviation.TX_POWER), RadioProfile.deviations(snapshot(lora.newBuilder().also { it.tx_power = 27 }.build()), us) - ProfileDeviation.NAME)
        // The radio profile writes the area.
        val writes = RadioProfile.writes(snapshot(RadioProfile.lora(null)), RadioProfile.deviations(snapshot(RadioProfile.lora(null)), us), null, us)
        assertEquals(Config.LoRaConfig.RegionCode.US, (writes.last() as RadioWrite.SetConfig).config.lora?.region)
    }

    @Test
    fun radioOnAnotherAreaChangesWithoutAsking() {
        val hessen = snapshot(RadioProfile.lora(null))
        val d = RadioProfile.deviations(hessen, europe)
        assertEquals(setOf(ProfileDeviation.MODEM, ProfileDeviation.FREQUENCY), d)
        assertTrue(RadioProfile.isAreaChange(hessen, d, known))
        val farAway = snapshot(RadioProfile.lora(null).newBuilder().also { it.tx_power = 27 }.build())
        assertTrue(RadioProfile.isAreaChange(farAway, RadioProfile.deviations(farAway, us), known))
    }

    @Test
    fun otherDeviationsStillAsk() {
        val own = snapshot(RadioProfile.lora(null).newBuilder().also { it.modem_preset = Config.LoRaConfig.ModemPreset.MEDIUM_FAST }.build())
        assertFalse(RadioProfile.isAreaChange(own, RadioProfile.deviations(own, europe), known), "preset no area uses")
        val hops = snapshot(RadioProfile.lora(null).newBuilder().also { it.hop_limit = 5 }.build())
        assertFalse(RadioProfile.isAreaChange(hops, RadioProfile.deviations(hops, europe), known), "another deviation")
        val foreign = snapshot(RadioProfile.lora(null), name = "Meshtastic 1234")
        assertFalse(RadioProfile.isAreaChange(foreign, RadioProfile.deviations(foreign, europe) - ProfileDeviation.NAME, known), "not set up for the app")
        val router = snapshot(RadioProfile.lora(null), role = Config.DeviceConfig.Role.ROUTER)
        assertFalse(RadioProfile.isAreaChange(router, setOf(ProfileDeviation.MODEM, ProfileDeviation.FREQUENCY), known), "distributor")
        val mqttOnly = snapshot(RadioProfile.lora(null, europe).newBuilder().also { it.ignore_mqtt = true }.build())
        assertFalse(RadioProfile.isAreaChange(mqttOnly, RadioProfile.deviations(mqttOnly, europe), known), "only MQTT")
    }

    @Test
    fun gatewayOnlyInTheRegionOfItsRoot() {
        val gateway = RadioGateway("mqtt.anynear.net", 1883, false, "gw", "open", "msh/EU_868")
        assertTrue(GatewaySetup.servesRegion(gateway, europe))
        assertFalse(GatewaySetup.servesRegion(gateway, us))
        assertEquals(emptyList(), GatewaySetup.plan(snapshot(RadioProfile.lora(null, us), wifi = true), gateway, us))
        assertTrue(GatewaySetup.plan(snapshot(RadioProfile.lora(null, europe), wifi = true), gateway, europe).isNotEmpty())
    }

    @Test
    fun unknownRegionIsNoTarget() {
        val l = RadioList.parse("""{"issued":1,"regions":[{"name":"X","area":[[0,0],[1,0],[1,1]],"lora":"EU_868","preset":"SHORT_TURBO"}]}""")
        // SHORT_TURBO is known here; a region code the Meshtastic messages lack would give null.
        assertEquals(Config.LoRaConfig.ModemPreset.SHORT_TURBO, RadioTarget.of(l.region("X")!!)?.preset)
        assertNull(l.region("Y"))
    }

    private fun snapshot(
        lora: Config.LoRaConfig,
        wifi: Boolean = false,
        name: String = "anynear.app | Fuchs 27",
        role: Config.DeviceConfig.Role = Config.DeviceConfig.Role.CLIENT,
    ) = RadioProfile.DeviceConfigSnapshot(
        configs = listOf(
            Config.Builder().also { it.lora = lora }.build(),
            Config.Builder().also { it.device = RadioProfile.device(null).newBuilder().also { d -> d.role = role }.build() }.build(),
            Config.Builder().also { it.position = RadioProfile.position(null) }.build(),
            Config.Builder().also { it.network = Config.NetworkConfig.Builder().also { n -> n.wifi_enabled = wifi }.build() }.build(),
        ),
        modules = listOf(ModuleConfig.Builder().also { it.telemetry = RadioProfile.telemetry(null) }.build()),
        channels = listOf(
            Channel.Builder().also { c ->
                c.index = 0
                c.role = Channel.Role.PRIMARY
                c.settings = ChannelSettings.Builder().build()
            }.build(),
        ),
        owner = User.Builder().also { it.long_name = name }.build(),
    )
}
