package app.anynear.anmp.core

import kotlin.js.JsExport
import kotlin.math.sqrt

/**
 * A small meaning model for the store (ADR 0078 §5): one vector per word piece, a text is the mean of its pieces. No
 * neural network, pure Kotlin, so Android, iOS and the browser compute the same. Made from
 * `sentence-transformers/static-similarity-mrl-multilingual-v1` (Apache-2.0), cut to 256 values, 8 bit, and to word
 * pieces in Latin script (`tools/doc-vectors/make.py`).
 *
 * File: `ADV1`, u32 dim, u32 count, then per piece u8 length and UTF-8, then count f32 scales, then count×dim i8
 * values; little endian. The app loads it once and passes it to [DocIndex.useMeaning] and [DocRecognizer.recognizeWith].
 */
@JsExport
class DocVectors(bytes: ByteArray) {
    internal val dim: Int
    private val ids: HashMap<String, Int>
    private val scales: FloatArray
    private val values: ByteArray
    private val start: Int

    /** Vectors of the important kinds, made once by [DocRecognizer]. */
    internal var kinds: Map<String, FloatArray>? = null

    init {
        require(bytes.size > 12 && bytes.decodeToString(0, 4) == "ADV1") { "no doc vectors" }
        var p = 4
        fun u32(): Int {
            val v = (bytes[p].toInt() and 0xff) or ((bytes[p + 1].toInt() and 0xff) shl 8) or
                ((bytes[p + 2].toInt() and 0xff) shl 16) or ((bytes[p + 3].toInt() and 0xff) shl 24)
            p += 4
            return v
        }
        dim = u32()
        val count = u32()
        ids = HashMap(count * 2)
        for (i in 0 until count) {
            val len = bytes[p].toInt() and 0xff
            ids[bytes.decodeToString(p + 1, p + 1 + len)] = i
            p += 1 + len
        }
        scales = FloatArray(count) { Float.fromBits(u32()) }
        values = bytes
        start = p
        require(start + count.toLong() * dim <= bytes.size) { "doc vectors cut short" }
    }

    /** Vector of [text], length 1; `null` if no word piece is known. Only the first [MAX_CHARS] characters count. */
    fun embed(text: String): FloatArray? {
        val sum = FloatArray(dim)
        var n = 0
        for (word in words(text.take(MAX_CHARS))) {
            for (piece in pieces(word)) {
                val id = ids[piece] ?: continue
                val s = scales[id]
                val o = start + id * dim
                for (d in 0 until dim) sum[d] += values[o + d] * s
                n++
            }
        }
        if (n == 0) return null
        var norm = 0f
        for (v in sum) norm += v * v
        norm = sqrt(norm)
        if (norm == 0f) return null
        for (d in 0 until dim) sum[d] /= norm
        return sum
    }

    /** Cosine similarity of two vectors from [embed]. */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (d in a.indices) s += a[d] * b[d]
        return s
    }

    /** Greedy longest match as in WordPiece; a word with an unknown part counts as unknown. */
    private fun pieces(word: String): List<String> {
        if (word.length > 100) return emptyList()
        val out = ArrayList<String>(4)
        var i = 0
        while (i < word.length) {
            var end = word.length
            var found: String? = null
            while (end > i) {
                val sub = (if (i > 0) "##" else "") + word.substring(i, end)
                if (sub in ids) {
                    found = sub
                    break
                }
                end--
            }
            if (found == null) return emptyList()
            out += found
            i = end
        }
        return out
    }

    companion object {
        /** At most this much text goes into a vector; the start of a document says most. */
        const val MAX_CHARS = 4000

        /** Lower case, accents removed (as the BERT tokenizer of the model), split at spaces and punctuation. */
        internal fun words(text: String): List<String> {
            val out = ArrayList<String>()
            val b = StringBuilder()
            fun flush() {
                if (b.isNotEmpty()) out += b.toString()
                b.clear()
            }
            for (c in text.lowercase()) {
                val f = plain(c)
                when {
                    f.isWhitespace() || f.isISOControl() -> flush()
                    f.isLetterOrDigit() -> b.append(f)
                    else -> {
                        flush()
                        out += f.toString()
                    }
                }
            }
            flush()
            return out
        }

        /** A letter without its accent („ä“ → „a“), as Unicode NFD without marks for Latin letters. */
        private fun plain(c: Char): Char = when (c) {
            in "àáâãäåāăą" -> 'a'
            in "çćĉċč" -> 'c'
            in "ďđ" -> 'd'
            in "èéêëēĕėęě" -> 'e'
            in "ĝğġģ" -> 'g'
            in "ĥħ" -> 'h'
            in "ìíîïĩīĭįı" -> 'i'
            in "ĵ" -> 'j'
            in "ķ" -> 'k'
            in "ĺļľŀł" -> 'l'
            in "ñńņňŉ" -> 'n'
            in "òóôõöøōŏő" -> 'o'
            in "ŕŗř" -> 'r'
            in "śŝşš" -> 's'
            in "ţťŧ" -> 't'
            in "ùúûüũūŭůűų" -> 'u'
            in "ŵ" -> 'w'
            in "ýÿŷ" -> 'y'
            in "źżž" -> 'z'
            else -> c
        }
    }
}
