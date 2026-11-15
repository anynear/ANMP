package app.anynear.anmp.core.radio

import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config

/**
 * The Meshtastic default channel, „channel 0“ of ADR 0054 §1, on whatever slot it is: key `AQ==` and no name of its
 * own, i.e. empty or the name of the preset. The same test as the firmware (`Channels::isDefaultChannel`), so it is
 * the channel every radio with default settings hears. Slot 0 is often a private channel of its own (another key or
 * name): that one is never channel 0 in the sense of anynear, it is neither sent on nor uploaded.
 */
object DefaultChannel {
    /** Meshtastic default key: one byte `1` stands for the publicly known key. */
    val PSK: ByteString = byteArrayOf(1).toByteString()

    /** Name the firmware uses for a channel without a name (`DisplayFormatters::getModemPresetDisplayName`). */
    fun presetName(lora: Config.LoRaConfig): String {
        if (!lora.use_preset) return "Custom"
        return when (lora.modem_preset) {
            Config.LoRaConfig.ModemPreset.SHORT_TURBO -> "ShortTurbo"
            Config.LoRaConfig.ModemPreset.SHORT_SLOW -> "ShortSlow"
            Config.LoRaConfig.ModemPreset.SHORT_FAST -> "ShortFast"
            Config.LoRaConfig.ModemPreset.MEDIUM_SLOW -> "MediumSlow"
            Config.LoRaConfig.ModemPreset.MEDIUM_FAST -> "MediumFast"
            Config.LoRaConfig.ModemPreset.LONG_SLOW -> "LongSlow"
            Config.LoRaConfig.ModemPreset.LONG_FAST -> "LongFast"
            Config.LoRaConfig.ModemPreset.LONG_TURBO -> "LongTurbo"
            Config.LoRaConfig.ModemPreset.LONG_MODERATE -> "LongMod"
            else -> "Invalid"
        }
    }

    /**
     * Is [channel] the default channel? [lora] gives the preset; without it only an empty name counts (then the name
     * is the one of the preset in any case).
     */
    fun isDefault(channel: Channel, lora: Config.LoRaConfig?): Boolean {
        if (channel.role == Channel.Role.DISABLED) return false
        val settings = channel.settings ?: return false
        if (settings.psk != PSK) return false
        if (settings.name.isEmpty()) return true
        return lora != null && settings.name == presetName(lora)
    }

    /** Slot of the default channel; the primary channel first if there are several. `null`: the radio has none. */
    fun index(channels: List<Channel>, lora: Config.LoRaConfig?): Int? =
        channels.filter { isDefault(it, lora) }
            .minWithOrNull(compareBy<Channel> { it.role != Channel.Role.PRIMARY }.thenBy { it.index })?.index
}
