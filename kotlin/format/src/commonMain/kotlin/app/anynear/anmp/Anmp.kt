package app.anynear.anmp

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.cbor.CborArray
import kotlinx.serialization.cbor.CborLabel
import kotlin.js.JsExport

/**
 * ANMP – anynear Message Protocol, version v1 (ADR 0021, 0034). One packet format for every path (internet,
 * radio). Spec: `spec/anmp.md`, `spec/anmp.cddl`; vectors: `vectors/anmp-v1.json`.
 *
 * Packet: `50 4D 01` + CBOR `[h, b]`. `h` is the CBOR [AnmpHeader] in clear, `b` is AES-256-GCM over the CBOR
 * array `[c]` or `[c, s]` with AAD [aad]. `c` is the CBOR [AnmpContent], `s` the Ed25519 signature over
 * [signingBytes]. The scope binds a packet to its place (circle ID, or `""` for direct) and is not in the packet.
 */
@JsExport
object Anmp {
    /** "PM", version v1. */
    val MAGIC = byteArrayOf(0x41, 0x4E, 0x01)

    /** ANMP tag of an app installation: first 8 bytes of the device ID. */
    const val TAG_BYTES = 8

    /** Message ID, random, the same on every path. */
    const val ID_BYTES = 8

    /** Device ID (ADR 0013). */
    const val DEVICE_BYTES = 16

    /** Public X25519 / Ed25519 key. */
    const val KEY_BYTES = 32

    /** ID of a blob (attachment, image, history part): SHA-256 of its encrypted bytes (ADR 0035 §11). */
    const val BLOB_ID_BYTES = 32

    const val SIGNATURE_BYTES = 64

    /** Nonce + tag of AES-256-GCM. */
    const val SEAL_OVERHEAD = AnmpCrypto.NONCE_BYTES + AnmpCrypto.TAG_BYTES

    /** Payload limit of a Meshtastic packet. */
    const val RADIO_MAX_BYTES = 233

    /** Scope of direct packets (personal mailbox, radio direct). */
    const val DIRECT_SCOPE = ""

    /** Route tag in the public form over radio (ADR 0051). */
    const val ROUTE_TAG_BYTES = 8

    /** Shortened route tag in an [AnmpKind.ASK]. */
    const val ASK_TAG_BYTES = 6

    /** Most route tags in one [AnmpKind.ASK]: own device and up to 7 circles. */
    const val ASK_MAX_TAGS = 8

    internal val cbor = Cbor {
        preferCborLabelsOverNames = true
        ignoreUnknownKeys = true
        encodeDefaults = false
        alwaysUseByteString = true
        useDefiniteLengthEncoding = true
    }

    /** ANMP tag of a device ID (16 bytes raw). */
    fun tagOf(deviceId: ByteArray): ByteArray {
        require(deviceId.size >= TAG_BYTES) { "device ID too short" }
        return deviceId.copyOf(TAG_BYTES)
    }

    /** New random message ID. */
    fun newMessageId(): ByteArray = AnmpCrypto.random(ID_BYTES)

    /** Builds a packet. [signer] signs with the sender's device key; `null` = unsigned (radio only, ADR 0021). */
    @JsExport.Ignore
    suspend fun encode(
        header: AnmpHeader,
        content: AnmpContent,
        scope: String,
        cipher: AnmpCipher,
        signer: AnmpSigner? = null,
    ): ByteArray = encodeFull(header, content, scope, cipher, signer).packet

    /** Like [encode], also returns the opened packet for the history ([decodeOpened]). */
    @JsExport.Ignore
    suspend fun encodeFull(
        header: AnmpHeader,
        content: AnmpContent,
        scope: String,
        cipher: AnmpCipher,
        signer: AnmpSigner? = null,
    ): AnmpEncoded {
        require(header.from.size == TAG_BYTES) { "from must have $TAG_BYTES bytes" }
        require(header.to == null || header.to.size == TAG_BYTES) { "to must have $TAG_BYTES bytes" }
        require(header.route == null || header.route.size == ROUTE_TAG_BYTES) { "route must have $ROUTE_TAG_BYTES bytes" }
        require(!header.kind.open && header.kind != AnmpKind.PICKUP) { "${header.kind.conv} has its own encoding" }
        val h = encodeHeader(header)
        val c = encodeContent(content)
        val s = signer?.sign(signingBytes(scope, h, c))
        s?.let { require(it.size == SIGNATURE_BYTES) { "signature must have $SIGNATURE_BYTES bytes" } }
        val b = cipher.seal(cbor.encodeToByteArray(Inner.serializer(), Inner(c, s)), aad(scope, h))
        return AnmpEncoded(
            MAGIC + cbor.encodeToByteArray(Packet.serializer(), Packet(h, b)),
            cbor.encodeToByteArray(Opened.serializer(), Opened(h, c, s)),
        )
    }

    /**
     * Builds an open packet (kinds [AnmpKind.ASK] and [AnmpKind.PATRON], ADR 0051): header only, `b` is empty.
     * Patrons must read it without any key.
     */
    fun encodeOpen(header: AnmpHeader): ByteArray {
        require(header.kind.open) { "${header.kind.conv} is not sent open" }
        require(header.from.size == TAG_BYTES) { "from must have $TAG_BYTES bytes" }
        val tags = header.askTags.orEmpty()
        require(tags.size <= ASK_MAX_TAGS && tags.all { it.size == ASK_TAG_BYTES }) { "at most $ASK_MAX_TAGS ask tags of $ASK_TAG_BYTES bytes" }
        return MAGIC + cbor.encodeToByteArray(Packet.serializer(), Packet(encodeHeader(header), ByteArray(0)))
    }

    /** Packet `[h, b]` with `b` = [cipher] over [payload], AAD with [scope]; for kinds with their own content (pickup). */
    internal suspend fun encodeSealed(header: AnmpHeader, payload: ByteArray, scope: String, cipher: AnmpCipher): ByteArray {
        val h = encodeHeader(header)
        return MAGIC + cbor.encodeToByteArray(Packet.serializer(), Packet(h, cipher.seal(payload, aad(scope, h))))
    }

    /** Opens [encodeSealed]; `null` if invalid. */
    internal suspend fun openSealed(packet: ByteArray, scope: String, cipher: AnmpCipher): Pair<AnmpHeader, ByteArray>? {
        val (p, header) = parse(packet) ?: return null
        val plain = cipher.open(p.b, aad(scope, p.h)) ?: return null
        return header to plain
    }

    /** Reads only the header, to choose the key. `null` if it is no valid ANMP packet. */
    fun peek(packet: ByteArray): AnmpHeader? = parse(packet)?.second

    /**
     * Opens a packet. [verify] checks a signature against the sender's key; `false` (also: sender unknown)
     * rejects the packet. With [requireSignature] only signed packets are accepted. `null` if invalid.
     */
    @JsExport.Ignore
    suspend fun decode(
        packet: ByteArray,
        scope: String,
        cipher: AnmpCipher,
        verify: AnmpVerifier,
        requireSignature: Boolean,
    ): AnmpMessage? {
        val (p, header) = parse(packet) ?: return null
        if (header.kind.open || header.kind == AnmpKind.PICKUP) return null
        val plain = cipher.open(p.b, aad(scope, p.h)) ?: return null
        val inner = runCatching { cbor.decodeFromByteArray(Inner.serializer(), plain) }.getOrNull() ?: return null
        return check(Opened(p.h, inner.c, inner.s), header, scope, verify, requireSignature)
    }

    /** Checks an opened packet ([AnmpEncoded.opened]), e.g. from the history (ADR 0014). Same rules as [decode]. */
    @JsExport.Ignore
    suspend fun decodeOpened(bytes: ByteArray, scope: String, verify: AnmpVerifier, requireSignature: Boolean): AnmpMessage? {
        val o = runCatching { cbor.decodeFromByteArray(Opened.serializer(), bytes) }.getOrNull() ?: return null
        val header = header(o.h) ?: return null
        return check(o, header, scope, verify, requireSignature)
    }

    private suspend fun check(o: Opened, header: AnmpHeader, scope: String, verify: AnmpVerifier, requireSignature: Boolean): AnmpMessage? {
        val content = runCatching { cbor.decodeFromByteArray(AnmpContent.serializer(), o.c) }.getOrNull() ?: return null
        val signature = o.s
        if (signature == null && requireSignature) return null
        if (signature != null) {
            if (signature.size != SIGNATURE_BYTES || !verify.verify(header, content, signingBytes(scope, o.h, o.c), signature)) return null
        }
        return AnmpMessage(header, content, signed = signature != null, opened = cbor.encodeToByteArray(Opened.serializer(), o))
    }

    /** `true` if [packet] starts like a ANMP packet (before decoding). */
    fun isPmp(packet: ByteArray): Boolean = packet.size > MAGIC.size && MAGIC.indices.all { packet[it] == MAGIC[it] }

    fun fitsRadio(packet: ByteArray): Boolean = fitsRadioSize(packet.size)

    fun fitsRadioSize(size: Int): Boolean = size <= RADIO_MAX_BYTES

    /** Exact packet size without keys: ciphertext and signature have fixed lengths. */
    fun size(header: AnmpHeader, content: AnmpContent, signed: Boolean): Int {
        val h = encodeHeader(header).size
        val c = encodeContent(content).size
        // Without signature the inner array has one element.
        val inner = 1 + bstrSize(c) + if (signed) bstrSize(SIGNATURE_BYTES) else 0
        return MAGIC.size + 1 + bstrSize(h) + bstrSize(inner + SEAL_OVERHEAD)
    }

    /** How many bytes of text (UTF-8) fit into a radio packet; [content] builds the content for a text. -1 if nothing fits. */
    fun maxTextBytes(header: AnmpHeader, signed: Boolean, content: (text: String) -> AnmpContent): Int {
        fun fits(n: Int) = fitsRadioSize(size(header, content("x".repeat(n)), signed))
        if (!fits(0)) return -1
        var low = 0
        var high = RADIO_MAX_BYTES
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (fits(mid)) low = mid else high = mid - 1
        }
        return low
    }

    /** CBOR of a header (`h`). */
    fun encodeHeader(header: AnmpHeader): ByteArray = cbor.encodeToByteArray(Header.serializer(), headerOf(header))

    /** CBOR of a content (`c`). */
    fun encodeContent(content: AnmpContent): ByteArray = cbor.encodeToByteArray(AnmpContent.serializer(), content)

    /** Parses a content (`c`); `null` if malformed. */
    fun decodeContent(bytes: ByteArray): AnmpContent? = runCatching { cbor.decodeFromByteArray(AnmpContent.serializer(), bytes) }.getOrNull()

    /** `anmp/v1/aad\n` || scope || `\n` || h. */
    fun aad(scope: String, h: ByteArray): ByteArray = (AnmpInfo.AAD + "\n" + scope + "\n").encodeToByteArray() + h

    /** `anmp/v1/sig\n` || scope || `\n` || CBOR([h, c]). */
    fun signingBytes(scope: String, h: ByteArray, c: ByteArray): ByteArray =
        (AnmpInfo.SIG + "\n" + scope + "\n").encodeToByteArray() + cbor.encodeToByteArray(Packet.serializer(), Packet(h, c))

    private fun headSize(n: Int) = when {
        n < 24 -> 1
        n < 256 -> 2
        n < 65536 -> 3
        else -> 5
    }

    private fun bstrSize(n: Int) = headSize(n) + n

    private fun parse(packet: ByteArray): Pair<Packet, AnmpHeader>? {
        if (!isPmp(packet)) return null
        val p = runCatching { cbor.decodeFromByteArray(Packet.serializer(), packet.copyOfRange(MAGIC.size, packet.size)) }.getOrNull() ?: return null
        return p to (header(p.h) ?: return null)
    }

    private fun header(h: ByteArray): AnmpHeader? {
        val raw = runCatching { cbor.decodeFromByteArray(Header.serializer(), h) }.getOrNull() ?: return null
        val kind = AnmpKind.of(raw.k) ?: return null
        if (raw.f.size != TAG_BYTES || (raw.t != null && raw.t.size != TAG_BYTES)) return null
        if ((raw.x != null && raw.x.size != KEY_BYTES) || (raw.d != null && raw.d.size != DEVICE_BYTES)) return null
        if (raw.r != null && raw.r.size != ROUTE_TAG_BYTES) return null
        if (raw.q != null && (raw.q.size > ASK_MAX_TAGS || raw.q.any { it.size != ASK_TAG_BYTES })) return null
        if (raw.n != null && raw.n < 0 || raw.w != null && raw.w < 0 || raw.o != null && raw.o < 0) return null
        return AnmpHeader(kind, raw.f, raw.t, raw.e, raw.x, raw.d, raw.r, raw.n, raw.q?.toTypedArray(), raw.w, raw.o)
    }

    /** Packet `[h, b]`; also the signed array `[h, c]`. */
    @CborArray
    @Serializable
    internal class Packet(
        @CborLabel(0) val h: ByteArray,
        @CborLabel(1) val b: ByteArray,
    )

    /** Encrypted part `[c]` or `[c, s]`. */
    @CborArray
    @Serializable
    internal class Inner(
        @CborLabel(0) val c: ByteArray,
        @CborLabel(1) val s: ByteArray? = null,
    )

    /** Opened packet `[h, c]` or `[h, c, s]`: verifiable without the circle key (history). */
    @CborArray
    @Serializable
    internal class Opened(
        @CborLabel(0) val h: ByteArray,
        @CborLabel(1) val c: ByteArray,
        @CborLabel(2) val s: ByteArray? = null,
    )

    @Serializable
    internal class Header(
        @CborLabel(0) val k: Int,
        @CborLabel(1) val f: ByteArray,
        @CborLabel(2) val t: ByteArray? = null,
        @CborLabel(3) val e: Int? = null,
        @CborLabel(4) val x: ByteArray? = null,
        @CborLabel(5) val d: ByteArray? = null,
        @CborLabel(6) val r: ByteArray? = null,
        @CborLabel(7) val n: Long? = null,
        @CborLabel(8) val q: List<ByteArray>? = null,
        @CborLabel(9) val w: Int? = null,
        @CborLabel(10) val o: Long? = null,
    )

    private fun headerOf(h: AnmpHeader) =
        Header(h.kind.code, h.from, h.to, h.epoch, h.senderKey, h.senderDevice, h.route, h.since, h.askTags?.toList(), h.waiting, h.hour)
}
