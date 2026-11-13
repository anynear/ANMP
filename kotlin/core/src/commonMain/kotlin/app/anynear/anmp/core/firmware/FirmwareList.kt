package app.anynear.anmp.core.firmware

import app.anynear.andp.Key
import app.anynear.andp.Sig
import app.anynear.andp.SigningBytes
import app.anynear.anmp.AnmpKeys
import app.anynear.anmp.core.AnmpCore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a release gets onto the radio (ADR 0056 §1): over Wi-Fi or Bluetooth (ESP32), nRF52 DFU, or by cable. */
@Serializable
enum class FirmwarePath {
    @SerialName("esp32-wifi") ESP32_WIFI,
    @SerialName("esp32-ble") ESP32_BLE,
    @SerialName("nrf52-dfu") NRF52_DFU,
    @SerialName("usb") USB,
}

/**
 * An approved release for a device model (ADR 0056 §1). [url] is the download link at Meshtastic, [size] and
 * [sha256] (hex) are exactly the file from the Meshtastic release. [minAppBuild]: smallest app build number that can
 * install this release.
 */
@Serializable
data class FirmwareRelease(
    @SerialName("hw_model") val hwModel: String,
    val version: String,
    val path: FirmwarePath,
    val url: String,
    val size: Long,
    val sha256: String,
    @SerialName("min_app_build") val minAppBuild: Long? = null,
)

/**
 * Newer boot program (bootloader) for an nRF52 board, copied as `.uf2` onto the bootloader's drive. Recognised
 * by [boardId] from INFO_UF2.TXT; a bootloader fits only exactly this board.
 */
@Serializable
data class BootloaderRelease(
    @SerialName("board_id") val boardId: String,
    @SerialName("hw_models") val hwModels: List<String> = emptyList(),
    val version: String,
    val url: String,
    val size: Long,
    val sha256: String,
) {
    /** For loading via [fetchVerified], which checks size and SHA-256. */
    fun asDownload() = FirmwareRelease(boardId, version, FirmwarePath.USB, url, size, sha256)
}

/**
 * First install of Meshtastic over USB on an ESP32 (ADR 0094 §2): files with their flash address and the areas
 * erased beforehand (nvs, otadata). [flashSize]: end of the last partition.
 */
@Serializable
data class UsbInstallRelease(
    @SerialName("hw_model") val hwModel: String,
    val version: String,
    val mcu: String,
    @SerialName("flash_size") val flashSize: Long,
    val files: List<UsbInstallFile> = emptyList(),
    val erase: List<FlashRegion> = emptyList(),
)

@Serializable
data class UsbInstallFile(val url: String, val size: Long, val sha256: String, val offset: Long)

@Serializable
data class FlashRegion(val offset: Long, val size: Long)

/** The list without envelope; [issued] (ms) decides which of several lists applies, [release] is the Meshtastic tag. */
@Serializable
data class FirmwareList(
    val issued: Long = 0,
    val release: String? = null,
    val releases: List<FirmwareRelease> = emptyList(),
    /** Separate field (rule 3 of the relay): older apps skip it instead of discarding the list. */
    val bootloaders: List<BootloaderRelease> = emptyList(),
    /** Separate field like [bootloaders]: complete images for the first install over USB (ADR 0094). */
    @SerialName("usb_installs") val usbInstalls: List<UsbInstallRelease> = emptyList(),
) {
    /** The `.uf2` for the cable ([FirmwarePath.USB]) for [hwModel] or all models, only with links at Meshtastic, by model. */
    fun usbReleases(hwModel: String? = null): List<FirmwareRelease> = releases
        .filter { it.path == FirmwarePath.USB && (hwModel == null || it.hwModel == hwModel) && it.url.startsWith(FirmwareDownloads.DOWNLOAD_PREFIX) }
        .sortedBy { it.hwModel }

    /** Boot programs, only with a `.uf2` link at [FirmwareDownloads.BOOTLOADER_PREFIX]. */
    fun usableBootloaders(): List<BootloaderRelease> =
        bootloaders.filter { it.url.startsWith(FirmwareDownloads.BOOTLOADER_PREFIX) && it.url.endsWith(".uf2") }

    /**
     * Complete images for the first install over USB (ADR 0094 §2), for the chip [mcu] or all, only with links at
     * Meshtastic and the first file at address 0, by model.
     */
    fun usbInstallsFor(mcu: String? = null): List<UsbInstallRelease> = usbInstalls
        .filter { u -> (mcu == null || u.mcu == mcu) && u.files.isNotEmpty() && u.files.first().offset == 0L && u.files.all { it.url.startsWith(FirmwareDownloads.DOWNLOAD_PREFIX) } }
        .sortedBy { it.hwModel }
}

/** What INFO_UF2.TXT on the drive of an nRF52 says about its bootloader. */
data class Uf2Info(val version: String?, val boardId: String?) {
    companion object {
        /** e.g. "UF2 Bootloader 0.9.1-5-g488711a lib/nrfx (v2.0.0) …" and "Board-ID: nRF52840-T1000-E-v1". */
        fun parse(text: String): Uf2Info {
            val lines = text.lines().map { it.trim() }
            val version = lines.firstNotNullOfOrNull { line ->
                line.takeIf { it.startsWith("UF2 Bootloader") }?.removePrefix("UF2 Bootloader")?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }
            }
            val board = lines.firstNotNullOfOrNull { line ->
                line.takeIf { it.startsWith("Board-ID:") }?.removePrefix("Board-ID:")?.trim()?.takeIf { it.isNotEmpty() }
            }
            return Uf2Info(version, board)
        }
    }
}

/** Version numbers of the Meshtastic firmware such as `2.5.6.d55c08d`: only the numbers at the front count. */
object FirmwareVersion {
    fun parse(version: String): List<Int>? {
        val numbers = version.trim().removePrefix("v").split('.', '-').takeWhile { part -> part.isNotEmpty() && part.all { it.isDigit() } }
        return numbers.takeIf { it.size >= 2 }?.map { it.toIntOrNull() ?: return null }
    }

    /** Is [candidate] certainly newer than [installed]? Unreadable never counts as newer (never an older release, §4). */
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(installed) ?: return false
        return compare(a, b) > 0
    }

    /** From here on, ESP32 radios can be updated over Wi-Fi or Bluetooth (`ota_request`, MeshtasticOTA). */
    val ESP32_OTA_SINCE = listOf(2, 7, 18)

    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }
}

/** Envelope as with the network list (ANDP §5.8): the exact list text and the signature over it. */
@Serializable
private data class SignedFirmwareList(val list: String, val sig: String)

/** Domain of the signature over the list, in the style of ANDP ([SigningBytes.of]). */
const val FIRMWARE_LIST_DOMAIN = "anmp/v1/radio-software"

private val FirmwareJson = Json { ignoreUnknownKeys = true }

/** Of several lists, the validly signed one with the largest `issued`. */
suspend fun newestFirmwareList(bodies: List<String>, key: Key): FirmwareList? =
    bodies.mapNotNull { openFirmwareList(it, key) }.maxByOrNull { it.issued }

/** Opens the signed list; `null` if it is not signed by [key] or not readable. */
suspend fun openFirmwareList(body: String, key: Key): FirmwareList? = try {
    val signed = FirmwareJson.decodeFromString(SignedFirmwareList.serializer(), body)
    val sig = Sig.parse(signed.sig)
    if (key.verify(SigningBytes.of(FIRMWARE_LIST_DOMAIN, signed.list), sig)) {
        FirmwareJson.decodeFromString(FirmwareList.serializer(), signed.list)
    } else {
        null
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/**
 * The newest validly signed list from the space on the relays (ADR 0056 §1a); `null` while the space is not set up
 * or no relay answered.
 */
suspend fun AnmpCore.radioSoftwareList(): FirmwareList? {
    val key = radioSoftwareKey?.let { runCatching { Key.parse(it) }.getOrNull() } ?: return null
    val bodies = radioSoftwareLists() ?: return null
    return newestFirmwareList(bodies.map { it.decodeToString() }, key)
}

/** Where files may come from, and loading them with the check against the list. */
object FirmwareDownloads {
    /** Loading happens only from here: Meshtastic stores every file of a release individually there (§1a). */
    const val DOWNLOAD_PREFIX = "https://meshtastic.github.io/"

    /** Boot programs (bootloaders) only from here, as Meshtastic recommends them. */
    const val BOOTLOADER_PREFIX = "https://github.com/adafruit/Adafruit_nRF52_Bootloader/releases/download/"

    /** May [url] be loaded at all? */
    fun allowed(url: String): Boolean = url.startsWith(DOWNLOAD_PREFIX) || url.startsWith(BOOTLOADER_PREFIX)

    /** Do [bytes] match size and SHA-256 of [release]? */
    suspend fun matches(release: FirmwareRelease, bytes: ByteArray): Boolean =
        bytes.size.toLong() == release.size && AnmpKeys.sha256(bytes).toHex() == release.sha256.lowercase()
}

/**
 * Loads the file of [release] with [download] and checks size and SHA-256; `null` if it cannot be obtained or does not
 * match. A link outside [FirmwareDownloads.allowed] is not even loaded.
 */
suspend fun fetchVerified(release: FirmwareRelease, download: suspend (FirmwareRelease) -> ByteArray?): ByteArray? {
    if (!FirmwareDownloads.allowed(release.url)) return null
    val bytes = download(release) ?: return null
    return bytes.takeIf { FirmwareDownloads.matches(release, it) }
}

/**
 * Downloads and checks every file of [install] and returns what is written in this order: first the erased areas
 * (as 0xFF), then the files. `null` if a file is missing or does not match.
 */
suspend fun fetchUsbInstall(install: UsbInstallRelease, download: suspend (FirmwareRelease) -> ByteArray?): List<FlashImage>? {
    val erased = install.erase.map { FlashImage(it.offset, ByteArray(it.size.toInt()) { 0xFF.toByte() }) }
    val files = install.files.map { f ->
        val bytes = fetchVerified(FirmwareRelease(install.hwModel, install.version, FirmwarePath.USB, f.url, f.size, f.sha256), download) ?: return null
        FlashImage(f.offset, bytes)
    }
    return erased + files
}
