package app.anynear.anmp.core.firmware

/**
 * MD5 (RFC 1321), only to compare with what the ESP32 ROM reports after writing (`SPI_FLASH_MD5`). Not for anything
 * security related: the files themselves are checked with SHA-256 against the signed list. In pure Kotlin, because
 * WebCrypto in the browser has no MD5.
 */
object Md5 {
    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    /** floor(abs(sin(i + 1)) * 2^32), as in RFC 1321. */
    private val K = IntArray(64) { i ->
        val v = kotlin.math.abs(kotlin.math.sin((i + 1).toDouble())) * 4294967296.0
        v.toLong().toInt()
    }

    fun digest(data: ByteArray): ByteArray {
        val bitLength = data.size.toLong() * 8
        val padded = ByteArray(((data.size + 8) / 64 + 1) * 64)
        data.copyInto(padded)
        padded[data.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 8 + i] = (bitLength ushr (8 * i)).toByte()

        var a0 = 0x67452301
        var b0 = 0xefcdab89.toInt()
        var c0 = 0x98badcfe.toInt()
        var d0 = 0x10325476
        val m = IntArray(16)
        for (block in padded.indices step 64) {
            for (j in 0 until 16) {
                val at = block + j * 4
                m[j] = (padded[at].toInt() and 0xFF) or ((padded[at + 1].toInt() and 0xFF) shl 8) or
                    ((padded[at + 2].toInt() and 0xFF) shl 16) or ((padded[at + 3].toInt() and 0xFF) shl 24)
            }
            var a = a0
            var b = b0
            var c = c0
            var d = d0
            for (i in 0 until 64) {
                val f: Int
                val g: Int
                when (i / 16) {
                    0 -> { f = (b and c) or (b.inv() and d); g = i }
                    1 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                    2 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
                }
                val sum = a + f + K[i] + m[g]
                a = d
                d = c
                c = b
                b += sum.rotateLeft(S[i])
            }
            a0 += a
            b0 += b
            c0 += c
            d0 += d
        }
        val out = ByteArray(16)
        intArrayOf(a0, b0, c0, d0).forEachIndexed { w, v -> for (i in 0 until 4) out[w * 4 + i] = (v ushr (8 * i)).toByte() }
        return out
    }

    fun hex(data: ByteArray): String = digest(data).toHex()
}

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
