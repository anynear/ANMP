package app.anynear.anmp.core.firmware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `radios.json`, recognising an empty radio and the choice of model. Invented test values, no real list. */
class RadiosTest {
    private val catalogText = """
        {"version":1,"radios":[
          {"id":"heltec-v4","name":"Heltec V4","hw_model":"HELTEC_V4","mcu":"esp32s3","image":"https://anynear.app/img/v4.webp",
           "use":{"de":"Für unterwegs","en":"On the move"},"match":{"usb":["303a:1001"],"embedded_flash_mb":16,"psram_mb":2}},
          {"id":"heltec-v3","name":"Heltec V3","hw_model":"HELTEC_V3","mcu":"esp32s3",
           "use":{"de":"Günstig","en":"Cheap"},"match":{"usb":["10c4:ea60"],"embedded_flash_mb":8,"psram_mb":0},"later":true},
          {"id":"t1000e","name":"T1000-E","hw_model":"TRACKER_T1000_E","mcu":"nrf52840","use":{"de":"Schlüsselbund","en":"Key ring"},
           "match":{"usb":["2886"]}}
        ]}
    """.trimIndent()

    private val list = FirmwareList(
        releases = listOf(uf2("TRACKER_T1000_E"), uf2("RAK4631")),
        usbInstalls = listOf(install("HELTEC_V3"), install("HELTEC_WSL_V3"), install("HELTEC_V4"), install("T_DECK"), install("TBEAM", "esp32")),
    )

    @Test
    fun readsRadiosJson() {
        val catalog = assertNotNull(RadioCatalog.parse(catalogText))
        assertEquals(listOf("HELTEC_V4", "HELTEC_V3", "TRACKER_T1000_E"), catalog.radios.map { it.hwModel })
        assertEquals("Für unterwegs", catalog.radios.first().use["de"])
        assertEquals(RadioMatch(listOf("10c4:ea60"), 8, 0), catalog.radios[1].match)
        assertNull(RadioCatalog.parse("""{"version":2,"radios":[]}"""), "neueres Format nicht falsch lesen")
        assertNull(RadioCatalog.parse("kein json"))
        assertNull(RadioCatalog.parse("""{"version":1,"radios":[{"id":"x"}]}"""), "Pflichtfelder fehlen")
        assertEquals("https://anynear.app/v1/radios.json", RadioCatalog.URL)
    }

    @Test
    fun emptyHeltecV3IsRecognisedAndPreselected() {
        val catalog = RadioCatalog.parse(catalogText)
        val s = selectRadios(catalog, list, EspInfo(EspChip.ESP32_S3, 8, 0), 0x10C4, 0xEA60)
        assertEquals("HELTEC_V3", s.preselected?.hwModel)
        assertEquals(listOf("HELTEC_V4", "HELTEC_V3"), s.recommended.map { it.hwModel }, "empfohlen, nur was für den Chip da ist")
        assertEquals(listOf("HELTEC_WSL_V3", "T_DECK"), s.others.map { it.hwModel }, "der Rest ganz, ohne anderen Chip")
        assertTrue(s.all.all { it.install != null })
    }

    @Test
    fun otherMemoryOrUsbChipIsNotTakenForAHeltecV3() {
        val catalog = RadioCatalog.parse(catalogText)
        // Native USB and 8 MB PSRAM (e.g. T-Deck): nothing suggested, last connected first among the others.
        val tdeck = selectRadios(catalog, list, EspInfo(EspChip.ESP32_S3, 0, 8), 0x303A, 0x1001, lastHwModel = "T_DECK")
        assertNull(tdeck.preselected)
        assertEquals("T_DECK", tdeck.others.first().hwModel)
        // CP2102 but PSRAM: not a Heltec V3 either.
        assertNull(selectRadios(catalog, list, EspInfo(EspChip.ESP32_S3, 8, 8), 0x10C4, 0xEA60).preselected)
        // Flash not readable (other chip than the S3): the USB IDs alone still point to it.
        assertEquals("HELTEC_V3", selectRadios(catalog, list, EspInfo(EspChip.ESP32_S3), 0x10C4, 0xEA60).preselected?.hwModel)
    }

    @Test
    fun withoutRadiosJsonTheBuiltInValuesStillRecognise() {
        val s = selectRadios(null, list, EspInfo(EspChip.ESP32_S3, 8, 0), 0x10C4, 0xEA60)
        assertEquals(emptyList(), s.recommended, "nichts als empfohlen zeigen ohne radios.json")
        assertEquals(listOf("HELTEC_V3", "HELTEC_WSL_V3"), s.recognised.map { it.hwModel })
        assertEquals(listOf("HELTEC_V3", "HELTEC_WSL_V3", "HELTEC_V4", "T_DECK"), s.others.map { it.hwModel })
        // The last connected one goes first among the recognised.
        assertEquals("HELTEC_WSL_V3", selectRadios(null, list, EspInfo(EspChip.ESP32_S3, 8, 0), 0x10C4, 0xEA60, "HELTEC_WSL_V3").preselected?.hwModel)
    }

    @Test
    fun nrf52GetsUf2FilesAndIsRecognisedByUsbAlone() {
        val catalog = RadioCatalog.parse(catalogText)
        val s = selectRadios(catalog, list, info = null, vendorId = 0x2886, productId = 0x0057)
        assertEquals(listOf("TRACKER_T1000_E"), s.recommended.map { it.hwModel })
        assertEquals(listOf("RAK4631"), s.others.map { it.hwModel })
        assertEquals("TRACKER_T1000_E", s.preselected?.hwModel)
        assertNotNull(s.preselected?.uf2)
        assertNull(s.preselected?.install)
    }

    @Test
    fun nothingKnownShowsEverything() {
        val s = selectRadios(RadioCatalog.parse(catalogText), list, info = null)
        assertNull(s.preselected)
        assertEquals(listOf("HELTEC_V4", "HELTEC_V3", "TRACKER_T1000_E"), s.recommended.map { it.hwModel })
        assertEquals(listOf("HELTEC_WSL_V3", "RAK4631", "TBEAM", "T_DECK"), s.others.map { it.hwModel })
    }

    @Test
    fun anEmptyMatchRecognisesNothing() {
        val radio = RecommendedRadio("x", "X", "HELTEC_V3", "esp32s3", match = RadioMatch())
        assertFalse(UsbModelHints.fits(radio, EspInfo(EspChip.ESP32_S3, 8, 0), 0x10C4, 0xEA60))
        assertFalse(UsbModelHints.fits(radio.copy(match = null), EspInfo(EspChip.ESP32_S3, 8, 0), 0x10C4, 0xEA60))
        assertTrue(UsbModelHints.fits(radio.copy(match = RadioMatch(listOf("10C4"))), null, 0x10C4, 0x1234), "nur Hersteller, groß geschrieben")
        assertFalse(UsbModelHints.fits(radio.copy(match = RadioMatch(listOf("10c4:ea60"))), null, 0, 0), "USB unbekannt")
    }

    private fun install(hwModel: String, mcu: String = "esp32s3") = UsbInstallRelease(
        hwModel, "2.7.26", mcu, 0x800000,
        listOf(UsbInstallFile("${FirmwareDownloads.DOWNLOAD_PREFIX}f/$hwModel.factory.bin", 10, "00".repeat(32), 0)),
    )

    private fun uf2(hwModel: String) =
        FirmwareRelease(hwModel, "2.7.26", FirmwarePath.USB, "${FirmwareDownloads.DOWNLOAD_PREFIX}f/$hwModel.uf2", 10, "00".repeat(32))
}
