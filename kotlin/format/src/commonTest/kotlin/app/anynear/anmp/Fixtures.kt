package app.anynear.anmp

/** Invented test inputs, also used for vectors/anmp-v1.json. */
internal object Fixtures {
    val circleSecret = bytes(32) { it }
    const val CIRCLE_ID = "circle-1"
    val signA = bytes(32) { 0x40 + it }
    val xA = bytes(32) { 0x60 + it }
    val signB = bytes(32) { 0x80 + it }
    val xB = bytes(32) { 0xA0 + it }
    val inboxOwner = bytes(32) { 0xC0 + it }
    val rootSeed = bytes(32) { 0xD0 + it }
    val personSeed = bytes(32) { 0xE0 + it }
    val personSeed2 = bytes(32) { 0x10 + it }
    /** Fixed nonce number [n] (last byte); each vector uses its own, GCM nonces are never reused. */
    fun nonce(n: Int) = bytes(12) { if (it == 11) n else 0xF0 + it }
    val blobKey = bytes(32) { 0x20 + it }
    val messageId = bytes(8) { 0xA0 + it }
    val tagA = bytes(8) { it + 1 }
    val tagB = bytes(8) { it + 0x11 }

    // Alerts and channels (ADR 0062).
    val channelOwner = bytes(32) { 0x30 + it }
    val readKey = bytes(32) { 0x50 + it }
    val ephKey = bytes(32) { 0x70 + it }
    val ephKey2 = bytes(32) { 0x78 + it }
    val senderSeed = bytes(32) { 0x90 + it }
    val token = bytes(32) { 0xB0 + it }
    val alertId = bytes(8) { 0xC0 + it }
    val networkId = bytes(32) { 0x01 }

    // One-time X25519 keys of the patron exchange (ADR 0051 §5a).
    val askerEph = bytes(32) { 0x08 + it }
    val patronEph = bytes(32) { 0x28 + it }

    /**
     * Grants of the owner of [channelOwner] (ANDP Go `IssueGrant`, issued 1759999940, until 4102444800): read and list
     * for anyone, and write for the sender key of [senderSeed]. Ed25519 is deterministic, so ANDP Kotlin gives the same.
     */
    const val READ_GRANT = "glgypAFYIIuwThwbg93fMR9bzd98UO3jwIAvR-x5bioTHPQSmNnzAgMFGvSGVwAGGmjnd8RYQBvbvwm2ozX3oa1NXUCmwXWCG77s_PEYGaNB-2zs15jxyUBvl7qm0rz0eX4X_Yu_PySmmF9WdsZXHryxZ-R3FQg"
    const val SENDER_GRANT = "glhVpQFYIIuwThwbg93fMR9bzd98UO3jwIAvR-x5bioTHPQSmNnzAgQDWCDS65k7YxQ1KOcNzcfrnKIQEN12m-lSUMLFur-tFdRY4gUa9IZXAAYaaOd3xFhAlUmS8rVdPZCVAZl5hNF86NHVsRMCzSBz_j3qv3erVm5HV2thaqXKKBCCYkyfMGsVRv5ziwkX-ybVawybfSf2CA"

    suspend fun deviceA() = AnmpDevice.of(signA, xA)

    suspend fun deviceB() = AnmpDevice.of(signB, xB)

    private fun bytes(n: Int, f: (Int) -> Int) = ByteArray(n) { f(it).toByte() }
}

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

internal fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Writes the vectors file when UPDATE_VECTORS=1 (JVM only). `true` if written. */
internal expect fun writeVectorsIfRequested(json: String): Boolean

/** False where Ed25519 signing adds randomness (Apple CryptoKit): signatures still verify, but differ from the vectors. */
internal expect val signaturesAreDeterministic: Boolean
