// JavaScript facade for setting up a radio over USB in the browser (ADR 0094, WebSerial in Chrome and Edge): the
// software list and radios.json, the choice of model, and the ESP32 ROM loader on a serial line the page provides.
// Same code as in the app; only the line, zlib (CompressionStream) and downloads (fetch) come from the browser.
@file:OptIn(DelicateCoroutinesApi::class)

package app.anynear.anmp.core.firmware

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlin.js.Promise

/**
 * The serial line to the radio, from the page (WebSerial `SerialPort`). Bytes are `Int8Array` (Kotlin `ByteArray`);
 * `new Uint8Array(b.buffer, b.byteOffset, b.length)` turns them into what `writer.write` wants, and [read] may also
 * resolve to a `Uint8Array`.
 */
@JsExport
interface WebSerialLine {
    fun write(bytes: ByteArray): Promise<Unit>

    /** What is there, waiting at most [timeoutMs]; an empty array when nothing came (do not reject on timeout). */
    fun read(timeoutMs: Int): Promise<ByteArray>

    /** New speed. WebSerial cannot change it on an open port: close and open again with `baudRate`. */
    fun setBaud(baud: Int): Promise<Unit>

    /** `setSignals({ dataTerminalReady: dtr, requestToSend: rts })`; `true` = asserted, as in esptool. */
    fun setSignals(dtr: Boolean, rts: Boolean): Promise<Unit>
}

/** What the ROM loader read: [chip] like `mcu` (`esp32s3` …), flash and PSRAM in the chip in MB, `-1` = unknown. */
@JsExport
class WebEspInfo(val chip: String, val embeddedFlashMb: Int, val psramMb: Int) {
    internal fun toInfo(): EspInfo? = EspChip.of(chip)?.let { EspInfo(it, embeddedFlashMb.takeIf { v -> v >= 0 }, psramMb.takeIf { v -> v >= 0 }) }

    internal companion object {
        fun of(info: EspInfo) = WebEspInfo(info.chip.mcu, info.embeddedFlashMb ?: -1, info.psramMb ?: -1)
    }
}

/** A recommended radio from `radios.json`; [use] gives its "good for" line in a language (`de`, `en`). */
@JsExport
class WebRadio(val id: String, val name: String, val hwModel: String, val mcu: String, val image: String?, private val uses: Array<Array<String>>) {
    fun use(language: String): String? = uses.firstOrNull { it[0] == language }?.get(1) ?: uses.firstOrNull { it[0] == "en" }?.get(1)

    internal companion object {
        fun of(r: RecommendedRadio) = WebRadio(r.id, r.name, r.hwModel, r.mcu, r.image, r.use.map { (k, v) -> arrayOf(k, v) }.toTypedArray())
    }
}

/** `radios.json`, read. */
@JsExport
class WebRadioCatalog internal constructor(internal val catalog: RadioCatalog) {
    val radios: Array<WebRadio> get() = catalog.radios.map(WebRadio::of).toTypedArray()
}

/** The signed software list (ADR 0056), opened and checked; from [app.anynear.anmp.core.AnmpCoreWeb.radioSoftware]. */
@JsExport
class WebFirmwareList internal constructor(internal val list: FirmwareList) {
    /** Meshtastic tag of the list, e.g. `v2.7.26.54e0d8d`. */
    val release: String? get() = list.release

    /** Models with an image for the first install over USB on an ESP32. */
    fun installModels(): Array<String> = list.usbInstallsFor().map { it.hwModel }.distinct().toTypedArray()
}

/**
 * One model to choose. [viaSerial]: Meshtastic goes on over [WebEspLoader.install]; otherwise [uf2Url] is the file
 * for the drive of an nRF52/RP2040 (the page offers it to save). [radio]: its `radios.json` entry when recommended.
 */
@JsExport
class WebRadioOption(
    val hwModel: String,
    val version: String,
    val viaSerial: Boolean,
    val uf2Url: String?,
    val recommended: Boolean,
    val recognised: Boolean,
    val radio: WebRadio?,
)

/**
 * The choice: [recommended] first (order of `radios.json`), [others] as the full rest; [preselected] is the
 * `hwModel` of the recognised radio or `null` when nothing fits.
 */
@JsExport
class WebRadioSelection(val recommended: Array<WebRadioOption>, val others: Array<WebRadioOption>, val preselected: String?)

/**
 * The ROM loader of an ESP32 on [WebSerialLine], connected ([RadioSetupWeb.connect]). The chip waits in the loader until
 * [install] or [restart].
 */
@JsExport
class WebEspLoader internal constructor(
    private val loader: Esp32Loader,
    val info: WebEspInfo,
    private val download: suspend (FirmwareRelease) -> ByteArray?,
) {
    /**
     * Loads the image for [hwModel] from [list], checks every file against the list, writes it and restarts the
     * radio. [onProgress] gets (`download`, -1) while loading and (`write`, 0..1) while writing. Resolves to `done`,
     * `unknown` (no image for this model and chip), `download` (a file could not be loaded or did not match; nothing
     * written) or `write` (writing failed; try again, the ROM loader is always there).
     */
    fun install(list: WebFirmwareList, hwModel: String, onProgress: (String, Double) -> Unit): Promise<String> = GlobalScope.promise {
        val install = list.list.usbInstallsFor(info.chip).firstOrNull { it.hwModel == hwModel } ?: return@promise UNKNOWN
        onProgress(DOWNLOAD, -1.0)
        val images = fetchUsbInstall(install, download) ?: return@promise DOWNLOAD
        try {
            loader.write(images, install.flashSize) { onProgress(WRITE, it.toDouble()) }
            DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WRITE
        }
    }

    /** Leaves the loader and starts what is on the radio (EN over RTS). */
    fun restart(): Promise<Unit> = GlobalScope.promise { loader.hardReset() }

    companion object {
        const val DONE = "done"
        const val UNKNOWN = "unknown"
        const val DOWNLOAD = "download"
        const val WRITE = "write"
    }
}

/** Entry for the page: load, choose, connect. */
@JsExport
object RadioSetupWeb {
    /** `https://anynear.app/v1/radios.json`. */
    val RADIOS_URL: String = RadioCatalog.URL

    /** Loads `radios.json` from [url]; `null` if it cannot be loaded or read. */
    fun loadRadios(url: String): Promise<WebRadioCatalog?> = GlobalScope.promise {
        fetchBytes(url)?.decodeToString()?.let(RadioCatalog::parse)?.let(::WebRadioCatalog)
    }

    /** Reads `radios.json` from [text]; `null` if it is none. */
    fun parseRadios(text: String): WebRadioCatalog? = RadioCatalog.parse(text)?.let(::WebRadioCatalog)

    /** Family by USB IDs (`usbVendorId`, `usbProductId` of `port.getInfo()`): `esp32`, `nrf52`, `rp2040` or `unknown`. */
    fun usbFamily(vendorId: Int, productId: Int): String = UsbFamily.of(vendorId, productId).name.lowercase()

    /**
     * Filters for `navigator.serial.requestPort`: the USB vendors of radios ([UsbFamily]), as `{ usbVendorId }`
     * objects.
     */
    fun portFilters(): Array<dynamic> = VENDORS.map { v ->
        val filter: dynamic = js("({})")
        filter.usbVendorId = v
        filter
    }.toTypedArray()

    /**
     * The choice of model. [info] from [WebEspLoader.info] (`null` before connecting or when it is no ESP32), USB IDs
     * from the port (0 = unknown), [lastHwModel] preferred among equals.
     */
    fun select(catalog: WebRadioCatalog?, list: WebFirmwareList, info: WebEspInfo?, vendorId: Int, productId: Int, lastHwModel: String?): WebRadioSelection {
        val s = selectRadios(catalog?.catalog, list.list, info?.toInfo(), vendorId, productId, lastHwModel)
        val recognised = s.recognised.map { it.hwModel }.toSet()
        fun web(o: RadioOption) = WebRadioOption(
            o.hwModel,
            o.install?.version ?: o.uf2?.version ?: "",
            o.install != null,
            o.uf2?.url,
            o.recommended,
            o.hwModel in recognised,
            o.radio?.let(WebRadio::of),
        )
        return WebRadioSelection(s.recommended.map(::web).toTypedArray(), s.others.map(::web).toTypedArray(), s.preselected?.hwModel)
    }

    /**
     * Restarts the radio on [line] into the ROM loader and reads chip and memory; `null` if it does not answer there
     * (no ESP32, or hold its BOOT button while plugging in and try again). [vendorId] decides the order of the control
     * lines (Espressif's own USB); [log] gets the steps, may be `null`.
     */
    fun connect(line: WebSerialLine, vendorId: Int, log: ((String) -> Unit)?): Promise<WebEspLoader?> = GlobalScope.promise {
        val loader = Esp32Loader(WebLine(line), ::browserDeflate, log = log ?: {})
        try {
            loader.connect(nativeUsb = UsbFamily.nativeEspUsb(vendorId))
            WebEspLoader(loader, WebEspInfo.of(loader.info ?: return@promise null), ::download)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log?.invoke("ROM-Lader: ${e.message}")
            null
        }
    }

    /**
     * The `.uf2` for the drive of an nRF52/RP2040 ([WebRadioOption.uf2Url]), loaded and checked against size and
     * SHA-256 in [list], like the app does before writing. `null` if [hwModel] has no such file, it cannot be loaded or
     * it does not match: then nothing goes onto the drive. Resolves to the bytes for `FileSystemWritableFileStream`.
     */
    fun fetchUf2(list: WebFirmwareList, hwModel: String, url: String?): Promise<ByteArray?> = GlobalScope.promise {
        val releases = list.list.usbReleases(hwModel)
        val release = releases.firstOrNull { it.url == url } ?: releases.firstOrNull() ?: return@promise null
        fetchVerified(release, ::download)
    }

    private val VENDORS = listOf(
        UsbFamily.ESPRESSIF, UsbFamily.SILABS, UsbFamily.QINHENG, UsbFamily.FTDI, UsbFamily.PROLIFIC,
        UsbFamily.ADAFRUIT, UsbFamily.SEEED, UsbFamily.NORDIC, UsbFamily.RASPBERRY_PI,
    )
}

internal class WebLine(private val web: WebSerialLine) : SerialLine {
    override suspend fun write(bytes: ByteArray) = web.write(bytes).await()

    override suspend fun read(timeoutMs: Int): ByteArray = asByteArray(web.read(timeoutMs).await())

    override suspend fun setBaud(baud: Int) = web.setBaud(baud).await()

    override suspend fun setLines(dtr: Boolean, rts: Boolean) = web.setSignals(dtr, rts).await()
}

/** Any typed array or `ArrayBuffer` from the page as `Int8Array`, so that bytes above 0x7F compare right. */
internal fun asByteArray(value: dynamic): ByteArray {
    if (value == null) return ByteArray(0)
    val view: (dynamic) -> ByteArray = js("(function (v) { return ArrayBuffer.isView(v) ? new Int8Array(v.buffer, v.byteOffset, v.byteLength) : new Int8Array(v); })")
    return view(value)
}

/** zlib (with header) as the ROM expects: `CompressionStream("deflate")`. */
internal suspend fun browserDeflate(bytes: ByteArray): ByteArray {
    val run: (ByteArray) -> Promise<dynamic> =
        js("(function (b) { return new Response(new Blob([b]).stream().pipeThrough(new CompressionStream('deflate'))).arrayBuffer(); })")
    return asByteArray(run(bytes).await())
}

/** Only links allowed by [FirmwareDownloads]; [fetchVerified] checks size and SHA-256 afterwards. */
private suspend fun download(release: FirmwareRelease): ByteArray? = if (FirmwareDownloads.allowed(release.url)) fetchBytes(release.url) else null

private suspend fun fetchBytes(url: String): ByteArray? = try {
    val get: (String) -> Promise<dynamic> =
        js("(function (u) { return fetch(u).then(function (r) { return r.ok ? r.arrayBuffer() : null; }); })")
    val buffer: dynamic = get(url).await()
    if (buffer == null) null else asByteArray(buffer)
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    null
}
