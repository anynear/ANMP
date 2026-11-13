package app.anynear.anmp.core.firmware

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The browser facade under Node: CompressionStream, byte arrays from the page, the loader over a [WebSerialLine]. */
class RadioSetupWebTest {
    @Test
    fun deflateIsZlibTheRomCanRead() = runTest {
        val data = ByteArray(5000) { (it % 7).toByte() }
        val packed = browserDeflate(data)
        assertEquals(0x78, packed[0].toInt() and 0xFF, "zlib-Kopf")
        val inflate: (ByteArray) -> Promise<dynamic> =
            js("(function (b) { return new Response(new Blob([b]).stream().pipeThrough(new DecompressionStream('deflate'))).arrayBuffer(); })")
        assertContentEquals(data, asByteArray(inflate(packed).await()))
    }

    @Test
    fun bytesFromThePageCompareRight() {
        val fromPage: dynamic = js("new Uint8Array([0xC0, 0x01, 0xDB])")
        assertEquals(0xC0.toByte(), asByteArray(fromPage)[0])
        assertEquals(0, asByteArray(null).size)
    }

    @Test
    fun connectsAndInstallsOverTheLineOfThePage() = runTest {
        val rom = FakeRom(efuse3 = 1L shl 27)
        val loader = assertNotNull(RadioSetupWeb.connect(PageLine(rom), 0x10C4, null).await())
        assertEquals("esp32s3", loader.info.chip)
        assertEquals(8, loader.info.embeddedFlashMb)
        assertEquals(0, loader.info.psramMb)

        // Same values through the facade's choice.
        val image = ByteArray(1000) { it.toByte() }
        val sha = "a8af099bf2e878609558dbf69d8f88f4a31040a8cf84b549a0cfa912f12ffc3f"
        val list = WebFirmwareList(FirmwareList(usbInstalls = listOf(
            UsbInstallRelease("HELTEC_V3", "2.7.26", "esp32s3", 0x800000, listOf(UsbInstallFile("${FirmwareDownloads.DOWNLOAD_PREFIX}v3.bin", 1000, sha, 0))),
        )))
        val selection = RadioSetupWeb.select(null, list, loader.info, 0x10C4, 0xEA60, null)
        assertEquals("HELTEC_V3", selection.preselected)
        assertEquals(0, selection.recommended.size)

        // Writing with the compression of the fake ROM (none), the download from the test.
        val plain = WebEspLoader(Esp32Loader(rom, deflate = { it }).also { it.connect() }, loader.info) { image.copyOf() }
        val steps = mutableListOf<String>()
        assertEquals(WebEspLoader.DONE, plain.install(list, "HELTEC_V3") { step, _ -> steps += step }.await())
        assertContentEquals(image, ByteArray(image.size) { rom.flash[it.toLong()]!! })
        assertEquals(listOf("download", "write"), steps.distinct())
        assertEquals(WebEspLoader.UNKNOWN, plain.install(list, "T_DECK") { _, _ -> }.await())
        val broken = WebEspLoader(Esp32Loader(rom, deflate = { it }).also { it.connect() }, loader.info) { ByteArray(1000) }
        assertEquals(WebEspLoader.DOWNLOAD, broken.install(list, "HELTEC_V3") { _, _ -> }.await())
    }

    @Test
    fun noLoaderMeansNull() = runTest {
        // Native USB that only answers to esptool's order is reached; a bridge whose chip never enters the loader is not.
        val silent = object : WebSerialLine {
            override fun write(bytes: ByteArray): Promise<Unit> = Promise.resolve(Unit)
            override fun read(timeoutMs: Int): Promise<ByteArray> = Promise.resolve(ByteArray(0))
            override fun setBaud(baud: Int): Promise<Unit> = Promise.resolve(Unit)
            override fun setSignals(dtr: Boolean, rts: Boolean): Promise<Unit> = Promise.resolve(Unit)
        }
        assertNull(RadioSetupWeb.connect(silent, 0x10C4, null).await())
    }

    @Test
    fun radiosJsonThroughTheFacade() {
        val catalog = assertNotNull(RadioSetupWeb.parseRadios(
            """{"version":1,"radios":[{"id":"v3","name":"Heltec V3","hw_model":"HELTEC_V3","mcu":"esp32s3","use":{"de":"Günstig","en":"Cheap"}}]}""",
        ))
        assertEquals("Günstig", catalog.radios.single().use("de"))
        assertEquals("Cheap", catalog.radios.single().use("fr"))
        assertEquals("esp32", RadioSetupWeb.usbFamily(0x10C4, 0xEA60))
        assertEquals("nrf52", RadioSetupWeb.usbFamily(0x239A, 0x8029))
        assertEquals(9, RadioSetupWeb.portFilters().size)
    }

    @Test
    fun uf2OnlyFromTheListAndOnlyFromAllowedLinks() = runTest {
        // A model without a .uf2 in the list, and a link outside the allowed downloads: nothing is loaded at all.
        val list = WebFirmwareList(FirmwareList(releases = listOf(
            FirmwareRelease("TRACKER_T1000_E", "2.7.26", FirmwarePath.USB, "https://example.org/evil.uf2", 10, "00"),
        )))
        assertNull(RadioSetupWeb.fetchUf2(list, "HELTEC_V3", null).await())
        assertNull(RadioSetupWeb.fetchUf2(list, "TRACKER_T1000_E", "https://example.org/evil.uf2").await())
    }

    /** The page's line around the simulated ROM; reads come back as `Uint8Array` like WebSerial gives them. */
    private class PageLine(private val rom: FakeRom) : WebSerialLine {
        override fun write(bytes: ByteArray): Promise<Unit> = promiseOf { rom.write(bytes) }

        override fun read(timeoutMs: Int): Promise<ByteArray> = promiseOf {
            val b = rom.read(timeoutMs)
            val toUint8: (ByteArray) -> ByteArray = js("(function (b) { return new Uint8Array(b.buffer, b.byteOffset, b.length); })")
            toUint8(b)
        }

        override fun setBaud(baud: Int): Promise<Unit> = promiseOf { rom.setBaud(baud) }

        override fun setSignals(dtr: Boolean, rts: Boolean): Promise<Unit> = promiseOf { rom.setLines(dtr, rts) }

        @OptIn(DelicateCoroutinesApi::class)
        private fun <T> promiseOf(block: suspend () -> T): Promise<T> = GlobalScope.promise { block() }
    }
}
