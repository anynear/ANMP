package app.anynear.anmp.core.firmware

/**
 * Which chip sits behind a USB device, judged by its USB IDs only (before anything answers): radios without
 * Meshtastic say nothing about themselves, but the way they hang on the cable tells the family.
 */
enum class UsbFamily {
    /** ESP32: USB-serial bridge (CP210x, CH34x, FTDI, PL2303) or Espressif's own USB. Installed over the ROM loader. */
    ESP32,

    /** nRF52 (Adafruit bootloader, Seeed, Nordic): installed by copying a `.uf2` onto its drive. */
    NRF52,

    /** RP2040 (Raspberry Pi): likewise a `.uf2` onto its drive. */
    RP2040,

    UNKNOWN;

    /** Installed by copying onto a USB drive (bootloader), not over the ROM loader. */
    val usesDrive: Boolean get() = this == NRF52 || this == RP2040

    /** Does a chip named [mcu] (`esp32s3`, `nrf52840`, `rp2040` …) belong to this family? [UNKNOWN] takes every chip. */
    fun fits(mcu: String): Boolean = when (this) {
        ESP32 -> mcu.startsWith("esp32")
        NRF52 -> mcu.startsWith("nrf52")
        RP2040 -> mcu.startsWith("rp2040")
        UNKNOWN -> true
    }

    companion object {
        fun of(vendorId: Int, productId: Int): UsbFamily = when (vendorId) {
            ESPRESSIF, SILABS, QINHENG, FTDI, PROLIFIC -> ESP32
            ADAFRUIT, SEEED, NORDIC -> NRF52
            RASPBERRY_PI -> RP2040
            else -> UNKNOWN
        }

        /**
         * Espressif's own USB (USB-Serial/JTAG of ESP32-S3/C3/C6, or the ROM's USB in download mode): resetting into
         * the ROM loader needs another order of the control lines than a bridge chip with the classic circuit.
         */
        fun nativeEspUsb(vendorId: Int): Boolean = vendorId == ESPRESSIF

        const val ESPRESSIF = 0x303A
        const val SILABS = 0x10C4
        const val QINHENG = 0x1A86
        const val FTDI = 0x0403
        const val PROLIFIC = 0x067B
        const val ADAFRUIT = 0x239A
        const val SEEED = 0x2886
        const val NORDIC = 0x1915
        const val RASPBERRY_PI = 0x2E8A
    }
}

/** The USB-serial chip of a board, from the vendor ID of the device on the cable. */
enum class UsbBridge {
    CP210X,
    CH34X,
    FTDI,

    /** Espressif's own USB, no extra chip. */
    NATIVE,
    OTHER;

    companion object {
        fun of(vendorId: Int): UsbBridge = when (vendorId) {
            UsbFamily.SILABS -> CP210X
            UsbFamily.QINHENG -> CH34X
            UsbFamily.FTDI -> FTDI
            UsbFamily.ESPRESSIF -> NATIVE
            else -> OTHER
        }
    }
}

/** USB IDs as `radios.json` writes them in `match.usb`: four lowercase hex digits each, `10c4:ea60`. */
fun usbId(vendorId: Int, productId: Int): String = hex4(vendorId) + ":" + hex4(productId)

private fun hex4(v: Int) = (v and 0xFFFF).toString(16).padStart(4, '0')
