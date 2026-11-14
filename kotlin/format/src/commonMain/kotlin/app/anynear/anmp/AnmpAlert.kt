package app.anynear.anmp

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel
import kotlin.js.JsExport

/**
 * Body of an alert (ADR 0062 §3, kind "Meldung"), CBOR with integer keys. [until] absent means [ts] + 24 hours;
 * [area] only in curated channels. Limits and rules: [AnmpAlerts.isValid].
 */
@JsExport
@Serializable
class AnmpAlertBody(
    /** Random, [AnmpAlerts.ID_BYTES]; the same on every path. */
    @CborLabel(0) val id: ByteArray,
    /** Unix seconds. */
    @CborLabel(1) val ts: Long,
    /** [AnmpAlerts.INFO], [AnmpAlerts.WARNING] or [AnmpAlerts.ALARM]. */
    @CborLabel(2) val prio: Int,
    @CborLabel(3) val title: String? = null,
    /** Required and always written, also as `""` (allowed only with an attachment). */
    @CborLabel(4) val text: String,
    /** Context URL, `http` or `https` only. */
    @CborLabel(5) val url: String? = null,
    /** Unix seconds. */
    @CborLabel(6) val until: Long? = null,
    /** ID of the alert this one replaces. */
    @CborLabel(7) val replaces: ByteArray? = null,
    /** Display name of the source, e.g. "NINA (BBK)". */
    @CborLabel(8) val source: String? = null,
    /** Prefixes of German regional keys (`06` Hessen, `06412` Frankfurt); curated channels only. */
    @CborLabel(9) val area: List<String>? = null,
    @CborLabel(10) val attachments: List<AnmpAttachment>? = null,
    /** All clear / lifted: of the alert named in [replaces], or of the incident [key]. */
    @CborLabel(11) val ended: Boolean = false,
    /**
     * Incident ([AnmpAlerts.alertKey], [AnmpAlerts.ID_BYTES]): alerts of the same signer with the same key in a channel
     * are one incident; the newest counts, [ended] closes it (ADR 0062 §4).
     */
    @CborLabel(12) val key: ByteArray? = null,
) {
    /** End of validity in Unix seconds: [until], or [ts] + 24 hours. */
    val expires: Long get() = until ?: (ts + AnmpAlerts.DEFAULT_LIFETIME)
}

/** An opened alert: body, its [bodyBytes] as signed, the Ed25519 public key of the [signer] and the [signed] map. */
@JsExport
class AnmpAlert(val body: AnmpAlertBody, val signer: ByteArray, val bodyBytes: ByteArray, val signed: ByteArray)

/**
 * Channel invitation (`https://<host>/a#…`, ADR 0062): [space] of the channel (b64u owner key), its [name], the
 * private X25519 [readKey] and the read grant for anyone in wire form ([grant], as in ANDP headers). [curated] for
 * channels run by anynear.
 */
@JsExport
class AnmpChannelInvite(
    /** Network ID, 32 bytes. */
    val networkId: ByteArray,
    val space: String,
    val name: String,
    val readKey: ByteArray,
    val grant: String,
    val curated: Boolean = false,
    /** Way to announce oneself to the owner (ADR 0062 §11); only in links of own channels. */
    val join: AnmpChannelJoin? = null,
)

/**
 * Way to announce oneself to the owner of a channel (ADR 0062 §11): the owner device that made the link ([device],
 * 16 bytes, and its X25519 [key]), the space of its personal mailbox ([inbox]) and a write grant for it ([grant], CBOR
 * `[body, sig]`).
 */
@JsExport
@Serializable
class AnmpChannelJoin(
    @CborLabel(0) val device: ByteArray,
    @CborLabel(1) val key: ByteArray,
    @CborLabel(2) val inbox: ByteArray,
    @CborLabel(3) val grant: ByteArray,
) {
    val isValid: Boolean get() = device.size == Anmp.DEVICE_BYTES && key.size == Anmp.KEY_BYTES && inbox.size == Anmp.KEY_BYTES && grant.isNotEmpty()
}

/**
 * Record of a device address (ADR 0062 §4), sealed into the ingress space of its token: the channel [space], the
 * public read key, the Ed25519 seed of the address's own sender key, its write grant (wire form) and the label.
 */
@JsExport
@Serializable
class AnmpIngressRecord(
    @CborLabel(0) val v: Int,
    @CborLabel(1) val space: String,
    @CborLabel(2) val readPublicKey: ByteArray,
    @CborLabel(3) val senderSeed: ByteArray,
    @CborLabel(4) val grant: String,
    /** Omitted on the wire when empty. */
    @CborLabel(5) val label: String = "",
)

/**
 * Alerts and channels (ADR 0062). An alert is its own packet `AA\x01`: the [AnmpAlertBody] signed by a sender
 * ([sign]) and sealed to the channel's public read key with a one-time X25519 key ([seal]), so that machines (the
 * relay's device address) can make one and only holders of the read key can open it. Spec: `spec/anmp.md`,
 * "Alerts and channels".
 */
@JsExport
object AnmpAlerts {
    /** "AA", version v1. */
    val MAGIC = byteArrayOf(0x41, 0x41, 0x01)

    /** "AI", version v1: sealed record of a device address. */
    val INGRESS_MAGIC = byteArrayOf(0x41, 0x49, 0x01)

    const val VERSION = 1

    const val INFO = 1
    const val WARNING = 2
    const val ALARM = 3

    const val ID_BYTES = 8
    const val MAX_TITLE_BYTES = 200
    const val MAX_TEXT_BYTES = 4096
    const val MAX_ATTACHMENTS = 5

    /** Largest attachment (plaintext): 10 MiB. */
    const val MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024

    /** Validity of an alert without `until`: 24 hours. */
    const val DEFAULT_LIFETIME = 86_400L

    /** How long alerts are kept and accepted: 30 days. */
    const val KEEP_SECONDS = 30L * 86_400

    /** How far in the future `ts` may lie: 10 minutes. */
    const val MAX_FUTURE_SECONDS = 600L

    /** `until` of read and sender grants: 1.1.2100. */
    const val GRANT_UNTIL = 4_102_444_800L

    /** Device address token: 32 random bytes, b64u without padding (43 characters). */
    const val TOKEN_BYTES = 32

    const val MAX_NAME = 40

    /** Longest incident key before hashing (UTF-8 bytes). */
    const val MAX_KEY_BYTES = 200

    /**
     * `true` if the body keeps the rules of ADR 0062 §3: sizes (also at most [MAX_ATTACHMENT_BYTES] per attachment),
     * priority, URL scheme, text or attachment. Whether `area` may be set depends on the channel: [isValidIn].
     */
    fun isValid(b: AnmpAlertBody): Boolean {
        if (b.id.size != ID_BYTES || b.ts < 0 || b.prio !in INFO..ALARM) return false
        if ((b.title?.encodeToByteArray()?.size ?: 0) > MAX_TITLE_BYTES || b.text.encodeToByteArray().size > MAX_TEXT_BYTES) return false
        val attachments = b.attachments.orEmpty()
        if (b.text.isEmpty() && attachments.isEmpty()) return false
        if (attachments.size > MAX_ATTACHMENTS || attachments.any { it.id.size != Anmp.BLOB_ID_BYTES || it.key.size != AnmpBlob.KEY_BYTES }) return false
        if (attachments.any { it.size !in 0..MAX_ATTACHMENT_BYTES }) return false
        if (b.url != null && !isWebUrl(b.url)) return false
        if (b.until != null && b.until < 0) return false
        if (b.replaces != null && b.replaces.size != ID_BYTES) return false
        if (b.key != null && b.key.size != ID_BYTES) return false
        return true
    }

    /**
     * [isValid] for a channel that is [curated] or not: regional keys (`area`) belong only into curated channels; in
     * any other channel an alert with `area` is invalid as a whole (ADR 0062).
     */
    fun isValidIn(b: AnmpAlertBody, curated: Boolean): Boolean = isValid(b) && (curated || b.area.isNullOrEmpty())

    /** `http://` or `https://` (scheme case-insensitive). */
    fun isWebUrl(url: String): Boolean = url.length > 8 && (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))

    /**
     * Incident field of a body from the key a sender chose (e.g. `keller-wasser`, a Prometheus fingerprint): the first
     * [ID_BYTES] of SHA-256(`anmp/v1/alert-key` ‖ key). Fails on an empty key or one over [MAX_KEY_BYTES].
     */
    @JsExport.Ignore
    suspend fun alertKey(key: String): ByteArray {
        val raw = key.encodeToByteArray()
        require(raw.isNotEmpty() && raw.size <= MAX_KEY_BYTES) { "alert key must have 1 to $MAX_KEY_BYTES bytes" }
        return AnmpCrypto.sha256(AnmpInfo.ALERT_KEY.encodeToByteArray() + raw).copyOf(ID_BYTES)
    }

    /** CBOR of a body; fails if it breaks [isValid]. */
    fun encodeBody(body: AnmpAlertBody): ByteArray {
        require(isValid(body)) { "invalid alert body" }
        return Anmp.cbor.encodeToByteArray(AnmpAlertBody.serializer(), body)
    }

    /** Parses and checks a body; `null` if malformed or invalid. */
    fun decodeBody(bytes: ByteArray): AnmpAlertBody? =
        runCatching { Anmp.cbor.decodeFromByteArray(AnmpAlertBody.serializer(), bytes) }.getOrNull()?.takeIf { isValid(it) }

    /** `anmp/v1/alert` ‖ body bytes. */
    fun signingBytes(bodyBytes: ByteArray): ByteArray = AnmpInfo.ALERT.encodeToByteArray() + bodyBytes

    /** `signed` = CBOR `{0: body bytes, 1: signer, 2: sig}` with sig = Ed25519 over [signingBytes]. */
    @JsExport.Ignore
    suspend fun sign(body: AnmpAlertBody, signer: AnmpSpaceKey): ByteArray {
        val b = encodeBody(body)
        return Anmp.cbor.encodeToByteArray(Signed.serializer(), Signed(b, signer.publicKey, signer.sign(signingBytes(b))))
    }

    /**
     * Seals [signed] to the public read key: `AA\x01` ‖ eph.pub ‖ nonce ‖ AES-256-GCM(key, signed, AAD `AA\x01` ‖
     * eph.pub), key = HKDF(X25519(eph, read), `anmp/v1/alert-seal` ‖ eph.pub ‖ read.pub). [ephPrivate] and [nonce]
     * only for test vectors; otherwise both are random.
     */
    @JsExport.Ignore
    suspend fun seal(signed: ByteArray, readPublicKey: ByteArray, ephPrivate: ByteArray? = null, nonce: ByteArray? = null): ByteArray {
        require(readPublicKey.size == Anmp.KEY_BYTES) { "read key must have ${Anmp.KEY_BYTES} bytes" }
        val eph = AnmpCrypto.xPrivate(ephPrivate ?: AnmpCrypto.random(Anmp.KEY_BYTES))
        val ephPublic = AnmpCrypto.xPublicRaw(eph)
        val key = sealKey(AnmpCrypto.xShared(eph, readPublicKey), ephPublic, readPublicKey)
        return MAGIC + ephPublic + AnmpCrypto.seal(AnmpCrypto.aesKey(key), signed, MAGIC + ephPublic, nonce)
    }

    /** Signs and seals in one step. */
    @JsExport.Ignore
    suspend fun encode(body: AnmpAlertBody, signer: AnmpSpaceKey, readPublicKey: ByteArray): ByteArray = seal(sign(body, signer), readPublicKey)

    /**
     * Opens an alert with the private read key: decrypts, checks the signature and the body. `null` if anything
     * fails. Whether the signer may send into the channel is the caller's business (ADR 0062 §2, "Annehmen").
     */
    @JsExport.Ignore
    suspend fun open(packet: ByteArray, readPrivateKey: ByteArray): AnmpAlert? {
        if (!isAlert(packet) || packet.size < MAGIC.size + Anmp.KEY_BYTES + Anmp.SEAL_OVERHEAD) return null
        val ephPublic = packet.copyOfRange(MAGIC.size, MAGIC.size + Anmp.KEY_BYTES)
        val read = runCatching { AnmpCrypto.xPrivate(readPrivateKey) }.getOrNull() ?: return null
        val readPublic = AnmpCrypto.xPublicRaw(read)
        val shared = runCatching { AnmpCrypto.xShared(read, ephPublic) }.getOrNull() ?: return null
        val key = AnmpCrypto.aesKey(sealKey(shared, ephPublic, readPublic))
        val signed = AnmpCrypto.open(key, packet.copyOfRange(MAGIC.size + Anmp.KEY_BYTES, packet.size), MAGIC + ephPublic) ?: return null
        return verify(signed)
    }

    /** Checks a `signed` map: signature and body. `null` if invalid. */
    @JsExport.Ignore
    suspend fun verify(signed: ByteArray): AnmpAlert? {
        val s = runCatching { Anmp.cbor.decodeFromByteArray(Signed.serializer(), signed) }.getOrNull() ?: return null
        if (s.signer.size != Anmp.KEY_BYTES || s.sig.size != Anmp.SIGNATURE_BYTES) return null
        if (!AnmpCrypto.edVerify(s.signer, signingBytes(s.body), s.sig)) return null
        val body = decodeBody(s.body) ?: return null
        return AnmpAlert(body, s.signer, s.body, signed)
    }

    /** `true` if [packet] starts like an alert packet. */
    fun isAlert(packet: ByteArray): Boolean = packet.size > MAGIC.size && MAGIC.indices.all { packet[it] == MAGIC[it] }

    /** Public X25519 key of a private read key. */
    @JsExport.Ignore
    suspend fun readPublicKey(readPrivateKey: ByteArray): ByteArray = AnmpCrypto.xPublicRaw(AnmpCrypto.xPrivate(readPrivateKey))

    private suspend fun sealKey(shared: ByteArray, ephPublic: ByteArray, readPublic: ByteArray): ByteArray =
        AnmpCrypto.hkdf(shared, AnmpInfo.ALERT_SEAL.encodeToByteArray() + ephPublic + readPublic)

    // --- Channel link ----------------------------------------------------------------------------

    /** `https://<host>/a#` + b64u of the CBOR map `{0 v, 1 network, 2 space, 3 name, 4 read key, 5 grant, ? 6 curated, ? 7 join}`. */
    fun channelLink(host: String, invite: AnmpChannelInvite): String {
        val wire = ChannelWire(VERSION, invite.networkId, invite.space, invite.name.take(MAX_NAME), invite.readKey, invite.grant, invite.curated, invite.join?.takeIf { it.isValid })
        return "https://$host/a#" + b64u.encode(Anmp.cbor.encodeToByteArray(ChannelWire.serializer(), wire))
    }

    /**
     * Reads a channel link, also inside text. Checks the structure only; whether the grant is a read grant of the
     * owner of [AnmpChannelInvite.space] is the caller's business (the format has no ANDP).
     */
    fun parseChannelLink(text: String, host: String): AnmpChannelInvite? {
        val m = Regex(Regex.escape(host) + "/a/?#([A-Za-z0-9_-]+)").find(text) ?: return null
        val raw = runCatching { b64u.decode(m.groupValues[1]) }.getOrNull() ?: return null
        val w = runCatching { Anmp.cbor.decodeFromByteArray(ChannelWire.serializer(), raw) }.getOrNull() ?: return null
        if (w.v != VERSION || w.network.size != 32 || w.readKey.size != Anmp.KEY_BYTES || w.grant.isEmpty()) return null
        val space = runCatching { b64u.decode(w.space) }.getOrNull()
        if (space?.size != Anmp.KEY_BYTES || b64u.encode(space) != w.space) return null
        return AnmpChannelInvite(w.network, w.space, w.name.trim().take(MAX_NAME), w.readKey, w.grant, w.curated, w.join?.takeIf { it.isValid })
    }

    /** Most subscribers an owner keeps in the list of a channel (ADR 0062 §11). */
    const val MAX_SUBSCRIBERS = 1000

    /** `anmp/v1/channel-key` ‖ space (UTF-8) ‖ `0x00` ‖ read key (32) ‖ grant (UTF-8): signed by the owner key (ADR 0062 §11). */
    fun channelKeySigningBytes(space: String, readKey: ByteArray, grant: String): ByteArray =
        AnmpInfo.CHANNEL_KEY.encodeToByteArray() + space.encodeToByteArray() + byteArrayOf(0) + readKey + grant.encodeToByteArray()

    fun isChannelLink(text: String, host: String): Boolean = text.contains("$host/a")

    // --- Device address (ingress) ----------------------------------------------------------------

    /** New token of a device address, b64u (43 characters). */
    fun newToken(): String = b64u.encode(AnmpCrypto.random(TOKEN_BYTES))

    /** Raw token bytes; `null` unless it is b64u of [TOKEN_BYTES] bytes. */
    fun tokenBytes(token: String): ByteArray? =
        runCatching { b64u.decode(token) }.getOrNull()?.takeIf { it.size == TOKEN_BYTES && b64u.encode(it) == token }

    /** `https://<host>/<token>`. */
    fun ingressUrl(host: String, token: String): String = "https://$host/$token"

    /** Owner key of the ingress space: Ed25519 seed HKDF(raw token, `anmp/v1/ingress-sign`). */
    @JsExport.Ignore
    suspend fun ingressSeed(token: ByteArray): ByteArray {
        require(token.size == TOKEN_BYTES) { "token must have $TOKEN_BYTES bytes" }
        return AnmpCrypto.hkdf(token, AnmpInfo.INGRESS_SIGN)
    }

    @JsExport.Ignore
    suspend fun ingressSpace(token: ByteArray): AnmpSpaceKey = AnmpSpaceKey.fromPrivateKey(ingressSeed(token))

    /** `AI\x01` ‖ nonce ‖ AES-256-GCM(HKDF(raw token, `anmp/v1/ingress-aead`), record, AAD `AI\x01`). [nonce] only for vectors. */
    @JsExport.Ignore
    suspend fun sealIngress(token: ByteArray, record: AnmpIngressRecord, nonce: ByteArray? = null): ByteArray {
        require(token.size == TOKEN_BYTES) { "token must have $TOKEN_BYTES bytes" }
        val plain = Anmp.cbor.encodeToByteArray(AnmpIngressRecord.serializer(), record)
        return INGRESS_MAGIC + AnmpCrypto.seal(AnmpCrypto.aesKey(AnmpCrypto.hkdf(token, AnmpInfo.INGRESS_AEAD)), plain, INGRESS_MAGIC, nonce)
    }

    /** Opens an ingress record; `null` if it is none, tampered or for another token. */
    @JsExport.Ignore
    suspend fun openIngress(token: ByteArray, bytes: ByteArray): AnmpIngressRecord? {
        if (token.size != TOKEN_BYTES || bytes.size <= INGRESS_MAGIC.size || INGRESS_MAGIC.indices.any { bytes[it] != INGRESS_MAGIC[it] }) return null
        val key = AnmpCrypto.aesKey(AnmpCrypto.hkdf(token, AnmpInfo.INGRESS_AEAD))
        val plain = AnmpCrypto.open(key, bytes.copyOfRange(INGRESS_MAGIC.size, bytes.size), INGRESS_MAGIC) ?: return null
        val r = runCatching { Anmp.cbor.decodeFromByteArray(AnmpIngressRecord.serializer(), plain) }.getOrNull() ?: return null
        if (r.v != VERSION || r.readPublicKey.size != Anmp.KEY_BYTES || r.senderSeed.size != 32) return null
        return r
    }

    @Serializable
    internal class Signed(
        @CborLabel(0) val body: ByteArray,
        @CborLabel(1) val signer: ByteArray,
        @CborLabel(2) val sig: ByteArray,
    )

    @Serializable
    internal class ChannelWire(
        @CborLabel(0) val v: Int,
        @CborLabel(1) val network: ByteArray,
        @CborLabel(2) val space: String,
        @CborLabel(3) val name: String,
        @CborLabel(4) val readKey: ByteArray,
        @CborLabel(5) val grant: String,
        @CborLabel(6) val curated: Boolean = false,
        @CborLabel(7) val join: AnmpChannelJoin? = null,
    )
}
