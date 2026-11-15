package app.anynear.anmp.core.radio

import app.anynear.anmp.core.RadioRegion
import org.meshtastic.proto.Config
import kotlin.math.abs

/**
 * Radio settings of an area of the radio list (ADR 0054 §1b, „Funkgebiete“): LoRa [region], [preset] of channel 0 and
 * [frequencyMhz] (0 = default slot of region and preset). [area] is the name of the area in the list, `null` for the
 * default of ADR 0006 ([DEFAULT], Meshhessen). The same in the app and in the browser.
 */
data class RadioTarget(
    val region: Config.LoRaConfig.RegionCode,
    val preset: Config.LoRaConfig.ModemPreset,
    val frequencyMhz: Float,
    val area: String? = null,
) {
    /** For people: LoRa region and channel 0 as Meshtastic names it (`EU_868, ShortSlow`). */
    val label: String
        get() = "${region.name}, " + preset.name.split('_').joinToString("") { it.lowercase().replaceFirstChar(Char::uppercase) }

    /** Do these LoRa values of a radio already match region, preset and frequency? */
    fun matches(lora: Config.LoRaConfig?): Boolean = lora != null && lora.region == region && lora.use_preset &&
        lora.modem_preset == preset && abs(lora.override_frequency - frequencyMhz) <= FREQUENCY_TOLERANCE_MHZ

    companion object {
        /** Without a radio list or area: the profile of ADR 0006 (Meshhessen). */
        val DEFAULT = RadioTarget(Config.LoRaConfig.RegionCode.EU_868, Config.LoRaConfig.ModemPreset.SHORT_SLOW, Hessenmesh.FREQUENCY_MHZ)

        /** Settings of an area of the radio list; `null` if the Meshtastic messages here do not know its region or preset. */
        fun of(area: RadioRegion): RadioTarget? {
            val region = Config.LoRaConfig.RegionCode.entries.firstOrNull { it.name == area.lora && it != Config.LoRaConfig.RegionCode.UNSET } ?: return null
            val preset = Config.LoRaConfig.ModemPreset.entries.firstOrNull { it.name == area.preset } ?: return null
            return RadioTarget(region, preset, area.frequency.toFloat(), area.name)
        }

        internal const val FREQUENCY_TOLERANCE_MHZ = 0.001f
    }
}
