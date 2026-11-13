package app.anynear.anmp.core.firmware

import kotlinx.coroutines.delay

/**
 * A serial line to the radio (USB-serial chip such as CP2102 or CH340), as the ROM loader of an ESP32 needs it:
 * bytes in both directions, speed, and the two control lines DTR and RTS that boards wire to EN (reset) and IO0
 * (boot mode). `true` means the line is asserted (electrically low), as in esptool and usb-serial-for-android.
 */
interface SerialLine {
    suspend fun write(bytes: ByteArray)

    /** Reads what is there, waits at most [timeoutMs]; empty when nothing came. */
    suspend fun read(timeoutMs: Int): ByteArray

    suspend fun setBaud(baud: Int)

    suspend fun setLines(dtr: Boolean, rts: Boolean)
}

/** One piece written to the flash: [bytes] at [offset]. */
class FlashImage(val offset: Long, val bytes: ByteArray)

/** Chip families the loader knows, named like `mcu` in Meshtastic's `mt.json`. */
enum class EspChip(val mcu: String) {
    ESP32("esp32"),
    ESP32_S2("esp32s2"),
    ESP32_S3("esp32s3"),
    ESP32_C3("esp32c3"),
    ESP32_C6("esp32c6"),
    ;

    companion object {
        /** The chip named [mcu] (`esp32s3` …); `null` if the loader does not know it. */
        fun of(mcu: String): EspChip? = entries.firstOrNull { it.mcu == mcu }
    }
}

class EspLoaderException(message: String) : Exception(message)

/**
 * What the ROM loader tells about an empty radio: the chip and, on the ESP32-S3, flash and PSRAM built into the chip
 * package (eFuse; `0` = none built in, `null` = not read). Together with the USB connection this narrows down the
 * model, because an empty radio does not name itself.
 */
data class EspInfo(val chip: EspChip, val embeddedFlashMb: Int? = null, val psramMb: Int? = null)

/**
 * The ROM loader protocol of the ESP32 (the one esptool and flasher.meshtastic.org use; Espressif's "Serial Protocol"
 * documentation): SLIP frames, commands with a checksum over the data, compressed writing (`FLASH_DEFL_*`) and an MD5
 * check by the chip itself. Without the esptool stub; the ROM of all listed chips writes compressed data itself.
 * The loader sits in the chip's ROM: whatever goes wrong here, the next attempt starts from scratch.
 *
 * [deflate] compresses with zlib (header included), as the ROM expects; it comes from the platform (Android
 * `Deflater`, browser `CompressionStream("deflate")`). [md5Hex] checks against the chip's MD5; the default is [Md5].
 */
class Esp32Loader(
    private val line: SerialLine,
    private val deflate: suspend (ByteArray) -> ByteArray,
    private val md5Hex: (ByteArray) -> String = Md5::hex,
    private val log: (String) -> Unit = {},
) {
    private val input = SlipReader()
    var chip: EspChip? = null
        private set

    /** Chip and built-in memory, read right after connecting. */
    var info: EspInfo? = null
        private set

    /**
     * Restart into the ROM loader over DTR/RTS and sync. Tries a few times. First without a reset: a chip started with
     * the BOOT button held (or an empty one) already waits in the loader. [nativeUsb]: Espressif's own USB
     * (USB-Serial/JTAG), which turns the control lines into reset and boot mode in another order than the classic
     * circuit of a bridge chip; then both orders take turns.
     */
    suspend fun connect(nativeUsb: Boolean = false): EspChip {
        line.setBaud(ROM_BAUD)
        input.clear()
        drain()
        if (sync(attempts = 2)) return detected()
        repeat(CONNECT_ATTEMPTS) { attempt ->
            line.setBaud(ROM_BAUD)
            if (nativeUsb && attempt % 2 == 0) resetIntoLoaderUsbJtag() else resetIntoLoader(longPause = if (nativeUsb) attempt % 4 == 3 else attempt % 2 == 1)
            input.clear()
            drain()
            if (sync()) return detected()
        }
        throw EspLoaderException("kein ROM-Lader (Gerät nicht im Lademodus)")
    }

    private suspend fun detected(): EspChip {
        val detected = detectChip()
        chip = detected
        info = runCatching { readMemory(detected) }.getOrNull() ?: EspInfo(detected)
        log("ROM-Lader: ${detected.mcu}, Flash im Chip ${info?.embeddedFlashMb ?: "?"} MB, PSRAM ${info?.psramMb ?: "?"} MB")
        return detected
    }

    /**
     * ESP32-S3: flash and PSRAM in the package from eFuse block 1 (as esptool's `get_flash_cap`/`get_psram_cap`):
     * word 3 bits 27–29 flash (1 = 8 MB, 2 = 4 MB), word 4 bits 3–4 PSRAM (1 = 8 MB, 2 = 2 MB). Other chips: unknown.
     */
    private suspend fun readMemory(chip: EspChip): EspInfo {
        if (chip != EspChip.ESP32_S3) return EspInfo(chip)
        val word3 = command(READ_REG, le32(S3_EFUSE_BLOCK1 + 12)).value
        val word4 = command(READ_REG, le32(S3_EFUSE_BLOCK1 + 16)).value
        val flash = when ((word3 shr 27).toInt() and 7) {
            0 -> 0
            1 -> 8
            2 -> 4
            else -> null
        }
        val psram = when ((word4 shr 3).toInt() and 3) {
            0 -> 0
            1 -> 8
            2 -> 2
            else -> null
        }
        return EspInfo(chip, flash, psram)
    }

    /**
     * Writes [images] to the flash, each compressed, checks each with the chip's MD5 and restarts the device into the
     * new software. [onProgress] gets 0..1 over all bytes. [flashSize] is the size announced to the ROM.
     */
    suspend fun write(images: List<FlashImage>, flashSize: Long, onProgress: (Float) -> Unit = {}) {
        val current = chip ?: throw EspLoaderException("nicht verbunden")
        command(SPI_ATTACH, ByteArray(8))
        command(SPI_SET_PARAMS, le32(0) + le32(flashSize) + le32(64 * 1024) + le32(4 * 1024) + le32(256) + le32(0xFFFF))
        changeBaud(FAST_BAUD)
        val total = images.sumOf { it.bytes.size.toLong() }.coerceAtLeast(1)
        var done = 0L
        for (image in images) {
            writeOne(current, image) { written -> onProgress(((done + written).toFloat() / total).coerceIn(0f, 1f)) }
            done += image.bytes.size
            val md5 = flashMd5(image.offset, image.bytes.size)
            val want = md5Hex(image.bytes)
            if (!md5.equals(want, ignoreCase = true)) throw EspLoaderException("Prüfsumme an 0x${image.offset.toString(16)} passt nicht")
        }
        // Leave the loader without reboot from the ROM, then restart over EN like esptool's hard reset.
        runCatching { command(FLASH_DEFL_END, le32(1)) }
        hardReset()
        onProgress(1f)
    }

    suspend fun hardReset() {
        line.setLines(dtr = false, rts = true)
        delay(100)
        line.setLines(dtr = false, rts = false)
    }

    private suspend fun resetIntoLoader(longPause: Boolean) {
        // IO0 high, EN low (reset) …
        line.setLines(dtr = false, rts = true)
        delay(100)
        // … then EN high with IO0 low: the chip starts in the loader.
        line.setLines(dtr = true, rts = false)
        delay(if (longPause) 450 else 50)
        line.setLines(dtr = false, rts = false)
    }

    /**
     * Into the loader over USB-Serial/JTAG, in esptool's order (`USBJTAGSerialReset`): IO0 low first, then RTS up
     * before DTR goes down, so that the chip resets while the boot mode is still latched.
     */
    private suspend fun resetIntoLoaderUsbJtag() {
        line.setLines(dtr = false, rts = false)
        delay(100)
        line.setLines(dtr = true, rts = false)
        delay(100)
        line.setLines(dtr = true, rts = true)
        line.setLines(dtr = false, rts = true)
        delay(100)
        line.setLines(dtr = false, rts = false)
    }

    private suspend fun sync(attempts: Int = SYNC_ATTEMPTS): Boolean {
        val payload = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }
        repeat(attempts) {
            line.write(slip(packet(SYNC, payload)))
            val reply = runCatching { response(SYNC, timeoutMs = 100) }.getOrNull()
            if (reply != null) {
                // The ROM answers a sync several times; read the rest away.
                repeat(7) { runCatching { response(SYNC, timeoutMs = 100) } }
                return true
            }
        }
        return false
    }

    private suspend fun detectChip(): EspChip {
        // Newer chips report their id in GET_SECURITY_INFO; the original ESP32 does not know the command.
        val info = runCatching { command(GET_SECURITY_INFO, ByteArray(0)) }.getOrNull()
        if (info != null && info.data.size >= 20) {
            when (u32(info.data, 12)) {
                2L -> return EspChip.ESP32_S2
                5L -> return EspChip.ESP32_C3
                9L -> return EspChip.ESP32_S3
                13L -> return EspChip.ESP32_C6
            }
        }
        val magic = command(READ_REG, le32(CHIP_DETECT_MAGIC_REG)).value
        return when (magic) {
            0x00f01d83L -> EspChip.ESP32
            0x000007c6L -> EspChip.ESP32_S2
            0x00000009L -> EspChip.ESP32_S3
            0x6921506fL, 0x1b31506fL, 0x4881606fL, 0x4361606fL -> EspChip.ESP32_C3
            0x2ce0806fL -> EspChip.ESP32_C6
            else -> throw EspLoaderException("unbekannter Chip 0x${magic.toString(16)}")
        }
    }

    private suspend fun changeBaud(baud: Int) {
        // The ROM takes the new speed and answers at the old one; then both switch.
        val ok = runCatching { command(CHANGE_BAUDRATE, le32(baud.toLong()) + le32(0)) }.isSuccess
        if (!ok) {
            log("ROM-Lader: Geschwindigkeit bleibt $ROM_BAUD")
            return
        }
        line.setBaud(baud)
        delay(50)
        input.clear()
        drain()
    }

    private suspend fun writeOne(chip: EspChip, image: FlashImage, onWritten: (Long) -> Unit) {
        val compressed = deflate(image.bytes)
        val blocks = (compressed.size + BLOCK - 1) / BLOCK
        val begin = le32(image.bytes.size.toLong()) + le32(blocks.toLong()) + le32(BLOCK.toLong()) + le32(image.offset)
        // All ROMs after the original ESP32 expect a fifth word (encrypted: no).
        val params = if (chip == EspChip.ESP32) begin else begin + le32(0)
        val eraseTimeout = (ERASE_MS_PER_MB * (image.bytes.size / (1024.0 * 1024.0))).toInt().coerceAtLeast(3_000)
        command(FLASH_DEFL_BEGIN, params, timeoutMs = eraseTimeout)
        for (seq in 0 until blocks) {
            val from = seq * BLOCK
            val chunk = compressed.copyOfRange(from, minOf(from + BLOCK, compressed.size))
            val data = le32(chunk.size.toLong()) + le32(seq.toLong()) + le32(0) + le32(0) + chunk
            command(FLASH_DEFL_DATA, data, checksum = checksum(chunk), timeoutMs = BLOCK_TIMEOUT_MS)
            onWritten((image.bytes.size.toLong() * (seq + 1)) / blocks)
        }
    }

    private suspend fun flashMd5(offset: Long, size: Int): String {
        val timeout = (MD5_MS_PER_MB * (size / (1024.0 * 1024.0))).toInt().coerceAtLeast(3_000)
        val reply = command(SPI_FLASH_MD5, le32(offset) + le32(size.toLong()) + le32(0) + le32(0), timeoutMs = timeout)
        // The ROM answers with 32 hex characters, followed by the status bytes.
        return reply.data.copyOfRange(0, 32).decodeToString()
    }

    private class Reply(val value: Long, val data: ByteArray)

    private suspend fun command(op: Int, data: ByteArray, checksum: Int = 0, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Reply {
        line.write(slip(packet(op, data, checksum)))
        return response(op, timeoutMs)
    }

    private suspend fun response(op: Int, timeoutMs: Int): Reply {
        repeat(RESPONSE_SKIP) {
            val frame = input.next(line, timeoutMs) ?: throw EspLoaderException("keine Antwort auf 0x${op.toString(16)}")
            if (frame.size < 8 || frame[0] != 0x01.toByte() || (frame[1].toInt() and 0xFF) != op) return@repeat
            val size = u16(frame, 2)
            val value = u32(frame, 4)
            val body = frame.copyOfRange(8, minOf(frame.size, 8 + size))
            // ROM: four status bytes at the end, the first is the status, the second the error.
            if (body.size >= STATUS_BYTES && body[body.size - STATUS_BYTES] != 0.toByte()) {
                throw EspLoaderException("Fehler ${body[body.size - STATUS_BYTES + 1].toInt() and 0xFF} bei 0x${op.toString(16)}")
            }
            return Reply(value, body.copyOfRange(0, maxOf(0, body.size - STATUS_BYTES)))
        }
        throw EspLoaderException("keine passende Antwort auf 0x${op.toString(16)}")
    }

    private suspend fun drain() {
        while (line.read(10).isNotEmpty()) Unit
    }

    /** Collects SLIP frames from the line. */
    private class SlipReader {
        private val pending = ArrayDeque<Byte>()
        private var frame = ArrayList<Byte>()
        private var inFrame = false
        private var escape = false

        fun clear() {
            pending.clear()
            frame = ArrayList()
            inFrame = false
            escape = false
        }

        suspend fun next(line: SerialLine, timeoutMs: Int): ByteArray? {
            var waited = 0
            while (true) {
                while (pending.isNotEmpty()) {
                    val b = pending.removeFirst()
                    when {
                        b == END && !inFrame -> inFrame = true
                        b == END && frame.isEmpty() -> Unit
                        b == END -> {
                            val out = frame.toByteArray()
                            frame = ArrayList()
                            inFrame = false
                            return out
                        }
                        !inFrame -> Unit
                        escape -> {
                            escape = false
                            frame.add(if (b == ESC_END) END else if (b == ESC_ESC) ESC else b)
                        }
                        b == ESC -> escape = true
                        else -> frame.add(b)
                    }
                }
                if (waited >= timeoutMs) return null
                val step = minOf(READ_STEP_MS, timeoutMs - waited).coerceAtLeast(1)
                val got = line.read(step)
                waited += step
                got.forEach { pending.addLast(it) }
            }
        }
    }

    companion object {
        const val ROM_BAUD = 115_200
        const val FAST_BAUD = 460_800

        internal const val SYNC = 0x08
        internal const val READ_REG = 0x0A
        internal const val SPI_SET_PARAMS = 0x0B
        internal const val SPI_ATTACH = 0x0D
        internal const val CHANGE_BAUDRATE = 0x0F
        internal const val FLASH_DEFL_BEGIN = 0x10
        internal const val FLASH_DEFL_DATA = 0x11
        internal const val FLASH_DEFL_END = 0x12
        internal const val SPI_FLASH_MD5 = 0x13
        internal const val GET_SECURITY_INFO = 0x14

        private const val CHIP_DETECT_MAGIC_REG = 0x40001000L

        /** ESP32-S3 eFuse: EFUSE_RD_MAC_SPI_SYS (block 1), esptool `EFUSE_BLOCK1_ADDR`. */
        internal const val S3_EFUSE_BLOCK1 = 0x60007044L
        private const val STATUS_BYTES = 4

        /** Block size of the ROM for compressed data (esptool `FLASH_WRITE_SIZE` without stub). */
        internal const val BLOCK = 0x400
        private const val CONNECT_ATTEMPTS = 6
        private const val SYNC_ATTEMPTS = 5
        private const val RESPONSE_SKIP = 16
        private const val DEFAULT_TIMEOUT_MS = 3_000
        private const val BLOCK_TIMEOUT_MS = 5_000
        private const val ERASE_MS_PER_MB = 30_000
        private const val MD5_MS_PER_MB = 8_000
        private const val READ_STEP_MS = 50

        private const val END: Byte = 0xC0.toByte()
        private const val ESC: Byte = 0xDB.toByte()
        private const val ESC_END: Byte = 0xDC.toByte()
        private const val ESC_ESC: Byte = 0xDD.toByte()

        /** Request: direction 0, command, length, checksum (only for data commands), data. */
        internal fun packet(op: Int, data: ByteArray, checksum: Int = 0): ByteArray =
            byteArrayOf(0x00, op.toByte()) + le16(data.size) + le32(checksum.toLong() and 0xFFFFFFFFL) + data

        /** Checksum of the ROM: XOR of all data bytes, seeded with 0xEF. */
        internal fun checksum(data: ByteArray): Int = data.fold(0xEF) { acc, b -> acc xor (b.toInt() and 0xFF) }

        internal fun slip(bytes: ByteArray): ByteArray {
            val out = ArrayList<Byte>(bytes.size + 8)
            out.add(END)
            for (b in bytes) {
                when (b) {
                    END -> { out.add(ESC); out.add(ESC_END) }
                    ESC -> { out.add(ESC); out.add(ESC_ESC) }
                    else -> out.add(b)
                }
            }
            out.add(END)
            return out.toByteArray()
        }

        internal fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

        internal fun le32(v: Long) = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
        )

        private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        private fun u32(b: ByteArray, at: Int): Long =
            (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
                ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

        /** MD5 in hex, the same on every platform (also in the browser, where WebCrypto has no MD5). */
        fun md5Hex(bytes: ByteArray): String = Md5.hex(bytes)
    }
}
