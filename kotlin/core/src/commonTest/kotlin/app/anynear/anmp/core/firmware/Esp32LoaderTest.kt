package app.anynear.anmp.core.firmware

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ROM loader protocol against a simulated ESP32-S3 ROM: SLIP, checksum, compressed writing in blocks, MD5 check.
 * Compression is the identity here; the fake ROM keeps the bytes as they come. Says nothing about real hardware.
 */
class Esp32LoaderTest {
    private fun loader(rom: FakeRom) = Esp32Loader(rom, deflate = { it })

    @Test
    fun slipEscapesFrameBytes() {
        assertContentEquals(
            byteArrayOf(0xC0.toByte(), 0x01, 0xDB.toByte(), 0xDC.toByte(), 0xDB.toByte(), 0xDD.toByte(), 0xC0.toByte()),
            Esp32Loader.slip(byteArrayOf(0x01, 0xC0.toByte(), 0xDB.toByte())),
        )
        assertEquals(0xEF xor 0x01 xor 0x02, Esp32Loader.checksum(byteArrayOf(0x01, 0x02)))
    }

    @Test
    fun connectsDetectsChipAndWritesAllImages() = runTest {
        val rom = FakeRom()
        val loader = loader(rom)
        assertEquals(EspChip.ESP32_S3, loader.connect())
        val factory = ByteArray(3000) { (it * 7).toByte() }
        val erase = ByteArray(0x700) { 0xFF.toByte() }
        val progress = mutableListOf<Float>()
        loader.write(listOf(FlashImage(0x9000, erase), FlashImage(0, factory)), flashSize = 8L shl 20) { progress += it }
        assertContentEquals(factory, ByteArray(factory.size) { rom.flash[it.toLong()]!! })
        assertEquals(0xFF.toByte(), rom.flash[0x9000L + 0x6FF])
        assertTrue(rom.bauds.contains(Esp32Loader.FAST_BAUD))
        assertEquals(1f, progress.last())
        // 3000 bytes in blocks of 0x400: three data commands for the second image.
        assertEquals(2 + 3, rom.commands.count { it == Esp32Loader.FLASH_DEFL_DATA })
    }

    @Test
    fun readsTheMemoryBuiltIntoAnS3() = runTest {
        // ESP32-S3FN8 (Heltec V3): flash cap 1 = 8 MB in bits 27–29 of word 3, no PSRAM.
        val heltec = loader(FakeRom(efuse3 = 1L shl 27))
        heltec.connect()
        assertEquals(EspInfo(EspChip.ESP32_S3, embeddedFlashMb = 8, psramMb = 0), heltec.info)
        // ESP32-S3R8: no flash in the chip, PSRAM cap 1 = 8 MB in bits 3–4 of word 4.
        val r8 = loader(FakeRom(efuse4 = 1L shl 3))
        r8.connect()
        assertEquals(EspInfo(EspChip.ESP32_S3, embeddedFlashMb = 0, psramMb = 8), r8.info)
    }

    @Test
    fun chipAlreadyInTheLoaderNeedsNoReset() = runTest {
        val rom = FakeRom(startsInLoader = true)
        assertEquals(EspChip.ESP32_S3, loader(rom).connect())
        assertTrue(rom.lineChanges.isEmpty(), "BOOT gedrückt beim Einstecken: kein Reset, der es wieder starten würde")
    }

    @Test
    fun nativeUsbNeedsEsptoolsOrderOfTheLines() = runTest {
        // Empty ESP32-S3 on its own USB (Heltec V4, T-Beam Supreme …): the classic order alone does not reach the loader.
        assertFailsWith<EspLoaderException> { loader(FakeRom(usbJtag = true)).connect() }
        val rom = FakeRom(usbJtag = true)
        assertEquals(EspChip.ESP32_S3, loader(rom).connect(nativeUsb = true))
        // IO0 latched (DTR), then RTS up before DTR drops.
        assertEquals(listOf(false to false, true to false, true to true, false to true), rom.lineChanges.take(4))
    }

    @Test
    fun bridgeChipsStillGetTheClassicReset() = runTest {
        val rom = FakeRom()
        assertEquals(EspChip.ESP32_S3, loader(rom).connect(nativeUsb = false))
        assertEquals(false to true, rom.lineChanges.first(), "EN low, IO0 high")
    }

    @Test
    fun wrongChecksumFromTheChipStops() = runTest {
        val rom = FakeRom(corrupt = true)
        val loader = loader(rom)
        loader.connect()
        assertFailsWith<EspLoaderException> { loader.write(listOf(FlashImage(0, ByteArray(10))), flashSize = 4L shl 20) }
    }
}

/** Simulated ROM: answers SYNC only after a reset into the loader, keeps what is written, answers MD5. */
/**
 * [usbJtag]: Espressif's own USB instead of the classic circuit; it starts the loader when RTS goes up while DTR is
 * still up and then DTR drops (esptool's order). [startsInLoader]: started with BOOT held, waits already.
 */
internal class FakeRom(
    val chipId: Long = 9,
    val corrupt: Boolean = false,
    val usbJtag: Boolean = false,
    startsInLoader: Boolean = false,
    /** eFuse block 1 words 3 and 4 of an ESP32-S3 (flash and PSRAM in the package). */
    val efuse3: Long = 0,
    val efuse4: Long = 0,
) : SerialLine {
    val flash = HashMap<Long, Byte>()
    val bauds = mutableListOf<Int>()
    val commands = mutableListOf<Int>()
    var inLoader = startsInLoader
    val lineChanges = mutableListOf<Pair<Boolean, Boolean>>()
    private var dtr = false
    private var rts = false
    private val out = ArrayDeque<Byte>()
    private val incoming = ArrayList<Byte>()
    private var writeOffset = 0L
    private var written = 0L

    override suspend fun setBaud(baud: Int) {
        bauds += baud
    }

    override suspend fun setLines(dtr: Boolean, rts: Boolean) {
        lineChanges += dtr to rts
        if (usbJtag) {
            if (this.dtr && this.rts && !dtr && rts) inLoader = true
        } else if (this.rts && !rts) {
            // EN released (rts false) while IO0 is held low (dtr true): starts in the loader.
            inLoader = dtr
        }
        this.dtr = dtr
        this.rts = rts
    }

    override suspend fun read(timeoutMs: Int): ByteArray {
        val n = minOf(out.size, 64)
        return ByteArray(n) { out.removeFirst() }
    }

    override suspend fun write(bytes: ByteArray) {
        for (b in bytes) {
            if (b == 0xC0.toByte()) {
                if (incoming.isNotEmpty()) handle(unslip(incoming.toByteArray()))
                incoming.clear()
            } else {
                incoming += b
            }
        }
    }

    private fun handle(p: ByteArray) {
        if (!inLoader) return
        val op = p[1].toInt() and 0xFF
        val data = p.copyOfRange(8, p.size)
        commands += op
        when (op) {
            Esp32Loader.SYNC -> reply(op)
            Esp32Loader.READ_REG -> reply(
                op,
                value = when (u32(data, 0)) {
                    Esp32Loader.S3_EFUSE_BLOCK1 + 12 -> efuse3
                    Esp32Loader.S3_EFUSE_BLOCK1 + 16 -> efuse4
                    else -> 0
                },
            )
            Esp32Loader.GET_SECURITY_INFO -> reply(op, body = ByteArray(12) + le32(chipId) + ByteArray(4))
            Esp32Loader.FLASH_DEFL_BEGIN -> {
                writeOffset = u32(data, 12)
                written = 0
                // ESP32-S3 sends five words.
                reply(op, ok = data.size == 20)
            }
            Esp32Loader.FLASH_DEFL_DATA -> {
                val len = u32(data, 0).toInt()
                val chunk = data.copyOfRange(16, 16 + len)
                val sum = u32(p, 4).toInt()
                val okSum = sum == Esp32Loader.checksum(chunk)
                chunk.forEach { flash[writeOffset + written++] = it }
                reply(op, ok = okSum)
            }
            Esp32Loader.SPI_FLASH_MD5 -> {
                val at = u32(data, 0)
                val size = u32(data, 4).toInt()
                val bytes = ByteArray(size) { flash[at + it] ?: 0xFF.toByte() }
                val md5 = if (corrupt) "0".repeat(32) else Esp32Loader.md5Hex(bytes)
                reply(op, body = md5.encodeToByteArray())
            }
            else -> reply(op)
        }
    }

    private fun reply(op: Int, ok: Boolean = true, body: ByteArray = ByteArray(0), value: Long = 0) {
        val status = byteArrayOf(if (ok) 0 else 1, if (ok) 0 else 0x05, 0, 0)
        val all = body + status
        val frame = byteArrayOf(0x01, op.toByte()) + byteArrayOf(all.size.toByte(), (all.size shr 8).toByte()) + le32(value) + all
        Esp32Loader.slip(frame).forEach { out.addLast(it) }
    }

    private fun unslip(b: ByteArray): ByteArray {
        val r = ArrayList<Byte>()
        var esc = false
        for (x in b) {
            when {
                esc -> { r += if (x == 0xDC.toByte()) 0xC0.toByte() else 0xDB.toByte(); esc = false }
                x == 0xDB.toByte() -> esc = true
                else -> r += x
            }
        }
        return r.toByteArray()
    }

    private fun le32(v: Long) = Esp32Loader.le32(v)
    private fun u32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
}
