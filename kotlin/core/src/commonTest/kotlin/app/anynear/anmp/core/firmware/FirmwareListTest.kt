package app.anynear.anmp.core.firmware

import app.anynear.andp.Key
import app.anynear.andp.SigningBytes
import app.anynear.andp.SigningKey
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The signed software list (ADR 0056), loading with checks, MD5 and INFO_UF2.TXT. */
class FirmwareListTest {
    private val image = ByteArray(1000) { it.toByte() }

    /** SHA-256 of [image]. */
    private val imageSha256 = "a8af099bf2e878609558dbf69d8f88f4a31040a8cf84b549a0cfa912f12ffc3f"

    @Test
    fun md5MatchesTheKnownValues() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Md5.hex(ByteArray(0)))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Md5.hex("abc".encodeToByteArray()))
        assertEquals("9e107d9d372bb6826bd81d3542a419d6", Md5.hex("The quick brown fox jumps over the lazy dog".encodeToByteArray()))
        // Several blocks and padding into an extra block.
        assertEquals("57edf4a22be3c955ac49da2e2107b67a", Md5.hex("1234567890".repeat(8).encodeToByteArray()))
        assertEquals(Md5.hex(image), Esp32Loader.md5Hex(image))
    }

    @Test
    fun onlyNumbersInFrontCount() {
        assertTrue(FirmwareVersion.isNewer("3.5.7.abcdef0", "3.5.6.d55c08d"))
        assertTrue(FirmwareVersion.isNewer("3.6.0", "3.5.19"))
        assertTrue(FirmwareVersion.isNewer("3.0", "2.9.9.1234567"))
        assertFalse(FirmwareVersion.isNewer("3.5.6.aaaaaaa", "3.5.6.d55c08d"))
        assertFalse(FirmwareVersion.isNewer("3.5.5", "3.5.6"))
        // Unreadable is never newer: rather install nothing than possibly an older version.
        assertFalse(FirmwareVersion.isNewer("neu", "3.5.6"))
        assertFalse(FirmwareVersion.isNewer("3.5.7", ""))
    }

    @Test
    fun listCountsOnlyWithTheRightSignature() = runTest {
        val key = SigningKey.generate()
        val list = """{"releases":[${Json.encodeToString(FirmwareRelease.serializer(), release("3.5.7.1111111"))}]}"""
        assertEquals("3.5.7.1111111", openFirmwareList(signed(key, list), key.key)?.releases?.single()?.version)
        assertNull(openFirmwareList(signed(key, list), SigningKey.generate().key), "fremder Schlüssel")
        val sig = ((Json.parseToJsonElement(signed(key, list)) as JsonObject).getValue("sig") as JsonPrimitive).content
        val tampered = buildJsonObject {
            put("list", list.replace("3.5.7", "3.5.8"))
            put("sig", sig)
        }.toString()
        assertNull(openFirmwareList(tampered, key.key), "veränderte Liste")
        assertNull(openFirmwareList("kein json", key.key))
    }

    /** Envelope as the relay signs it (relay `radiosoftware.Sign`), as in the app before. */
    @Test
    fun listFromTheRelayOpens() = runTest {
        val key = Key.parse("IIWpYe_U1e-LKIq2bNPzOYQTJdu_LMXXGIhnlgq1-Xs")
        val envelope = """{"list":"{\"issued\":1782259200000,\"release\":\"v2.7.26.54e0d8d\",\"releases\":[{\"hw_model\":\"HELTEC_V3\",\"version\":\"2.7.26.54e0d8d\",\"path\":\"esp32-wifi\",\"url\":\"https://meshtastic.github.io/firmware-2.7.26.54e0d8d/firmware-heltec-v3-2.7.26.54e0d8d.bin\",\"size\":2109248,\"sha256\":\"a8af099bf2e878609558dbf69d8f88f4a31040a8cf84b549a0cfa912f12ffc3f\"}]}","sig":"_N41Eh2RIsuTF3K6p02psgkVrJoJU4ztPdSWTLuXMkCpS9YnItFkY8KZyX8LhUSVlXQtJl8CNFZaQtDBnqLsDQ"}"""
        val list = assertNotNull(openFirmwareList(envelope, key))
        assertEquals("v2.7.26.54e0d8d", list.release)
        assertEquals(FirmwarePath.ESP32_WIFI, list.releases.single().path)
        assertNull(openFirmwareList(envelope.replace("2109248", "2109249"), key))
        assertEquals("anmp/v1/radio-software", FIRMWARE_LIST_DOMAIN)
    }

    @Test
    fun newestSignedListCounts() = runTest {
        val key = SigningKey.generate()
        fun list(version: String, issued: Long) = Json.encodeToString(FirmwareList.serializer(), FirmwareList(issued, releases = listOf(release(version))))
        val old = signed(key, list("3.5.7.1111111", 1))
        val new = signed(key, list("3.5.8.1111111", 2))
        val forged = signed(SigningKey.generate(), list("9.9.9.9999999", 3))
        assertEquals(2, newestFirmwareList(listOf(old, new, forged), key.key)?.issued)
    }

    @Test
    fun listWithBootloadersAndUsbInstallsOpens() = runTest {
        val key = SigningKey.generate()
        val bootloader = """{"board_id":"nRF52840-T1000-E-v1","hw_models":["TRACKER_T1000_E"],"version":"0.9.2","url":"https://github.com/adafruit/Adafruit_nRF52_Bootloader/releases/download/0.9.2/update-t1000_e_bootloader-0.9.2_nosd.uf2","size":71168,"sha256":"11f3256ab90899793838fd51ecda1c6d269a8cd88fb852217e1c8760a578d2b2"}"""
        val install = """{"hw_model":"HELTEC_V3","version":"2.7.26.54e0d8d","mcu":"esp32s3","flash_size":8388608,"files":[{"url":"https://meshtastic.github.io/f.factory.bin","size":1000,"sha256":"$imageSha256","offset":0}],"erase":[{"offset":36864,"size":16384}]}"""
        val list = """{"rules":3,"releases":[],"bootloaders":[$bootloader],"usb_installs":[$install],"future":1}"""
        val opened = assertNotNull(openFirmwareList(signed(key, list), key.key))
        assertEquals(listOf("TRACKER_T1000_E"), opened.usableBootloaders().single().hwModels)
        assertEquals(36864, opened.usbInstallsFor("esp32s3").single().erase.single().offset)
        assertEquals(emptyList(), opened.usbInstallsFor("esp32"))
    }

    @Test
    fun onlyImagesAtMeshtasticStartingAtZeroCount() {
        val ok = install("HELTEC_V3")
        val foreign = install("T_DECK", url = "https://example.org/x.bin")
        val notAtZero = install("T_BEAM", offset = 0x1000)
        val list = FirmwareList(usbInstalls = listOf(notAtZero, foreign, ok))
        assertEquals(listOf("HELTEC_V3"), list.usbInstallsFor().map { it.hwModel })
        val uf2 = FirmwareList(releases = listOf(release("2.7.26", "RAK4631", FirmwarePath.USB), release("2.7.26", "T_ECHO", FirmwarePath.USB, url = "https://example.org/a.uf2")))
        assertEquals(listOf("RAK4631"), uf2.usbReleases().map { it.hwModel })
    }

    @Test
    fun downloadsAreCheckedAndForeignLinksNotEvenLoaded() = runTest {
        val loaded = mutableListOf<String>()
        val good = release("2.7.26")
        assertEquals(1000, fetchVerified(good) { loaded += it.url; image.copyOf() }?.size)
        assertNull(fetchVerified(good) { image.copyOf(999) }, "falsche Größe")
        assertNull(fetchVerified(good) { ByteArray(1000) }, "falsche Prüfsumme")
        assertNull(fetchVerified(release("2.7.26", url = "https://example.org/x.bin")) { loaded += it.url; image })
        assertEquals(1, loaded.size)

        val images = assertNotNull(fetchUsbInstall(install("HELTEC_V3")) { image.copyOf() })
        assertEquals(listOf(0x9000L, 0L), images.map { it.offset }, "erst der gelöschte Bereich, dann die Datei")
        assertTrue(images.first().bytes.all { it == 0xFF.toByte() })
        assertNull(fetchUsbInstall(install("HELTEC_V3")) { null })
    }

    @Test
    fun parsesInfoUf2() {
        assertEquals(Uf2Info("0.9.1-5-g488711a", "nRF52840-T1000-E-v1"),
            Uf2Info.parse("UF2 Bootloader 0.9.1-5-g488711a lib/nrfx (v2.0.0) lib/tinyusb (0.12.0)\r\nModel: Seeed T1000-E\r\nBoard-ID: nRF52840-T1000-E-v1\r\n"))
        assertEquals(Uf2Info(null, null), Uf2Info.parse("irgendwas"))
    }

    @Test
    fun familyFromUsbIds() {
        assertEquals(UsbFamily.ESP32, UsbFamily.of(0x10C4, 0xEA60), "Heltec V3: CP2102")
        assertEquals(UsbFamily.ESP32, UsbFamily.of(0x303A, 0x1001), "ESP32-S3 USB-Serial/JTAG")
        assertEquals(UsbFamily.ESP32, UsbFamily.of(0x303A, 0x0002), "ESP32-S3 ROM-Download")
        assertEquals(UsbFamily.ESP32, UsbFamily.of(0x1A86, 0x7522), "CH340K")
        assertEquals(UsbFamily.NRF52, UsbFamily.of(0x239A, 0x8029), "RAK4631/T-Echo")
        assertEquals(UsbFamily.NRF52, UsbFamily.of(0x2886, 0x0057), "T1000-E")
        assertEquals(UsbFamily.RP2040, UsbFamily.of(0x2E8A, 0x0003), "BOOTSEL")
        assertEquals(UsbFamily.UNKNOWN, UsbFamily.of(0x05AC, 0x1234))
        assertTrue(UsbFamily.nativeEspUsb(0x303A))
        assertFalse(UsbFamily.nativeEspUsb(0x10C4))
        assertEquals(UsbBridge.CP210X, UsbBridge.of(0x10C4))
        assertEquals("10c4:ea60", usbId(0x10C4, 0xEA60))
        assertEquals("303a:0002", usbId(0x303A, 0x2))
    }

    private fun release(
        version: String,
        hwModel: String = "HELTEC_V3",
        path: FirmwarePath = FirmwarePath.ESP32_WIFI,
        url: String = "${FirmwareDownloads.DOWNLOAD_PREFIX}firmware-$version/firmware-$hwModel-$version.bin",
    ) = FirmwareRelease(hwModel, version, path, url, image.size.toLong(), imageSha256)

    private fun install(hwModel: String, url: String = "${FirmwareDownloads.DOWNLOAD_PREFIX}f/$hwModel.factory.bin", offset: Long = 0) =
        UsbInstallRelease(hwModel, "2.7.26", "esp32s3", 0x800000, listOf(UsbInstallFile(url, image.size.toLong(), imageSha256, offset)), listOf(FlashRegion(0x9000, 0x10)))

    private suspend fun signed(key: SigningKey, list: String): String = buildJsonObject {
        put("list", list)
        put("sig", key.sign(SigningBytes.of(FIRMWARE_LIST_DOMAIN, list)).toString())
    }.toString()
}
