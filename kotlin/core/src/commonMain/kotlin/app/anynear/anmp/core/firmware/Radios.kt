package app.anynear.anmp.core.firmware

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What tells an empty radio apart on the cable, from `radios.json`: USB IDs of the board (`10c4:ea60`, or only the
 * vendor `10c4`), and on an ESP32-S3 flash and PSRAM built into the chip (eFuse). Missing values are not checked.
 */
@Serializable
data class RadioMatch(
    val usb: List<String> = emptyList(),
    @SerialName("embedded_flash_mb") val embeddedFlashMb: Int? = null,
    @SerialName("psram_mb") val psramMb: Int? = null,
)

/**
 * A recommended radio from `https://anynear.app/v1/radios.json`. [hwModel] connects it with the software list,
 * [mcu] names the chip like `mcu` there, [use] is the short "good for" line per language (`de`, `en`), [image] a link
 * to its picture. Without [match] it is recommended but never recognised on the cable.
 */
@Serializable
data class RecommendedRadio(
    val id: String,
    val name: String,
    @SerialName("hw_model") val hwModel: String,
    val mcu: String,
    val image: String? = null,
    val use: Map<String, String> = emptyMap(),
    val match: RadioMatch? = null,
)

/** `radios.json`: recommended radios in the order the page shows them, most recommended first. */
@Serializable
data class RadioCatalog(val version: Int = 1, val radios: List<RecommendedRadio> = emptyList()) {
    companion object {
        /** Where the website publishes the list. */
        const val URL = "https://anynear.app/v1/radios.json"

        /** Format version this code reads; a larger one is skipped rather than misread. */
        const val VERSION = 1

        private val json = Json { ignoreUnknownKeys = true }

        /** Reads `radios.json`; `null` if it is no such list or of a newer format. Unknown fields are skipped. */
        fun parse(text: String): RadioCatalog? = try {
            json.decodeFromString(serializer(), text).takeIf { it.version == VERSION }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Recognising the model of an empty radio. It does not name itself; what can be read is the chip, the flash and PSRAM
 * built into the chip (eFuse) and the USB IDs of the board. Radios whose `match` fits all of that are the candidates,
 * in the order of the list. Several boards share chip and USB chip (Heltec V3 and Wireless Stick Lite V3), so this
 * narrows down, it never proves.
 */
object UsbModelHints {
    /**
     * Until `radios.json` can be loaded: the boards whose values are taken from the manufacturer's data sheet, only to
     * recognise them, never shown as recommended.
     */
    val FALLBACK: List<RecommendedRadio> = listOf(
        // Heltec WiFi LoRa 32 V3: ESP32-S3FN8 (8 MB flash in the chip, no PSRAM), CP2102.
        RecommendedRadio("heltec-v3", "Heltec V3", "HELTEC_V3", "esp32s3", match = RadioMatch(listOf("10c4:ea60"), 8, 0)),
        // Heltec Wireless Stick Lite V3: same chip and USB chip, without a display.
        RecommendedRadio("heltec-wsl-v3", "Heltec Wireless Stick Lite V3", "HELTEC_WSL_V3", "esp32s3", match = RadioMatch(listOf("10c4:ea60"), 8, 0)),
    )

    /**
     * Does [radio] fit what was read: USB IDs [vendorId]/[productId] (0: unknown) and, if the ROM loader answered,
     * [info]. Needs at least one value that actually fits, so an empty `match` recognises nothing.
     */
    fun fits(radio: RecommendedRadio, info: EspInfo?, vendorId: Int, productId: Int): Boolean {
        val m = radio.match ?: return false
        var evidence = false
        if (m.usb.isNotEmpty()) {
            if (vendorId == 0) return false
            val full = usbId(vendorId, productId)
            val vendor = full.substringBefore(':')
            if (m.usb.none { it.lowercase() == full || it.lowercase() == vendor }) return false
            evidence = true
        }
        if (info != null) {
            if (info.chip.mcu != radio.mcu) return false
            if (m.embeddedFlashMb != null && info.embeddedFlashMb != null) {
                if (m.embeddedFlashMb != info.embeddedFlashMb) return false
                evidence = true
            }
            if (m.psramMb != null && info.psramMb != null) {
                if (m.psramMb != info.psramMb) return false
                evidence = true
            }
        } else if (UsbFamily.of(vendorId, productId).let { it != UsbFamily.UNKNOWN && !it.fits(radio.mcu) }) {
            return false
        }
        return evidence
    }

    /** Of [radios], those that fit, in their order. */
    fun recognise(radios: List<RecommendedRadio>, info: EspInfo?, vendorId: Int, productId: Int): List<RecommendedRadio> =
        radios.filter { fits(it, info, vendorId, productId) }
}

/**
 * One model to choose: [install] puts Meshtastic on an ESP32 over the ROM loader, [uf2] goes onto the drive of an nRF52
 * or RP2040; at least one of them is there. [radio]: its entry in `radios.json`, if recommended.
 */
data class RadioOption(val hwModel: String, val install: UsbInstallRelease?, val uf2: FirmwareRelease?, val radio: RecommendedRadio?) {
    val recommended: Boolean get() = radio != null
}

/**
 * The choice of model for a radio without Meshtastic. [recommended]: the recommended radios that can be installed
 * here, in the order of `radios.json`; [others]: every other model of the software list, recognised and last
 * connected first, then by name. [recognised]: what fits the radio on the cable, most likely first; [preselected]
 * is its first, or `null` when nothing fits (then the person picks, nothing is suggested).
 */
data class RadioSelection(
    val recommended: List<RadioOption>,
    val others: List<RadioOption>,
    val recognised: List<RadioOption>,
    val preselected: RadioOption?,
) {
    /** Everything, recommended first. */
    val all: List<RadioOption> get() = recommended + others
}

/**
 * Builds the choice from the recommended radios ([catalog], `null` while `radios.json` cannot be loaded), what was read
 * on the cable ([info] from the ROM loader, `null` if it did not answer or it is no ESP32; USB IDs, 0 = unknown) and
 * the software [list]. With [info] only ESP32 images for that chip count; for a family that installs over a drive
 * only `.uf2` files; otherwise both. [lastHwModel]: model of the last connected radio, preferred among equals.
 */
fun selectRadios(
    catalog: RadioCatalog?,
    list: FirmwareList,
    info: EspInfo?,
    vendorId: Int = 0,
    productId: Int = 0,
    lastHwModel: String? = null,
): RadioSelection {
    val family = UsbFamily.of(vendorId, productId)
    val installs = if (family.usesDrive) emptyList() else list.usbInstallsFor(info?.chip?.mcu).filter { family.fits(it.mcu) }
    val uf2s = if (info != null || family == UsbFamily.ESP32) emptyList() else list.usbReleases()
    val models = (installs.map { it.hwModel } + uf2s.map { it.hwModel }).distinct().sorted()
    val radios = catalog?.radios.orEmpty()
    fun option(model: String) = RadioOption(
        model,
        installs.firstOrNull { it.hwModel == model },
        uf2s.firstOrNull { it.hwModel == model },
        radios.firstOrNull { it.hwModel == model },
    )

    val recommended = radios.map { it.hwModel }.distinct().filter { it in models }.map(::option)
    // Without radios.json only the built-in values recognise anything; they are never shown as recommended.
    val recognisedModels = UsbModelHints.recognise(catalog?.radios ?: UsbModelHints.FALLBACK, info, vendorId, productId)
        .map { it.hwModel }.distinct().filter { it in models }
        .sortedByDescending { it == lastHwModel }
    val recognised = recognisedModels.map { m -> recommended.firstOrNull { it.hwModel == m } ?: option(m) }
    val others = models.filter { m -> recommended.none { it.hwModel == m } }
        .sortedWith(compareByDescending<String> { it in recognisedModels }.thenByDescending { it == lastHwModel })
        .map(::option)
    return RadioSelection(recommended, others, recognised, recognised.firstOrNull())
}
