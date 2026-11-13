package app.anynear.anmp

import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.XDH
import kotlin.js.JsExport

/** AES-256-GCM key as [AnmpCipher]: circle key, device pair key or blob key. */
@JsExport
class AnmpAead private constructor(
    private val key: AES.GCM.Key,
    private val fixedNonce: ByteArray?,
) : AnmpCipher {
    override suspend fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        AnmpCrypto.seal(key, plaintext, associatedData, fixedNonce)

    override suspend fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray? =
        AnmpCrypto.open(key, sealed, associatedData)

    companion object {
        suspend fun of(raw: ByteArray): AnmpAead = AnmpAead(AnmpCrypto.aesKey(raw), null)

        /** Fixed nonce, only for test vectors. Never reuse a nonce with real data. */
        internal suspend fun withFixedNonce(raw: ByteArray, nonce: ByteArray): AnmpAead = AnmpAead(AnmpCrypto.aesKey(raw), nonce.copyOf())
    }
}

/**
 * Ed25519 owner key of a ANDP space. [space] is the Base64url public key (ANDP §2). Used for the circle space
 * ([AnmpCircleKeys.space]) and for the personal mailbox, whose random key pair the app creates and keeps (ADR 0035 §11).
 */
@JsExport
class AnmpSpaceKey private constructor(
    private val signer: EdDSA.PrivateKey,
    /** Raw Ed25519 public key, 32 bytes. */
    val publicKey: ByteArray,
) : AnmpSigner {
    val space: String = spaceOf(publicKey)

    override suspend fun sign(data: ByteArray): ByteArray = AnmpCrypto.edSign(signer, data)

    companion object {
        /** Owner key from a raw Ed25519 private key (32-byte seed). */
        suspend fun fromPrivateKey(seed: ByteArray): AnmpSpaceKey {
            val signer = AnmpCrypto.edPrivate(seed)
            return AnmpSpaceKey(signer, AnmpCrypto.edPublicRaw(signer))
        }

        /** Space of an Ed25519 public key: Base64url without padding (43 characters). */
        fun spaceOf(publicKey: ByteArray): String {
            require(publicKey.size == Anmp.KEY_BYTES) { "Ed25519 public key must have 32 bytes" }
            return b64u.encode(publicKey)
        }
    }
}

/** Keys derived from a circle secret (32 bytes). */
@JsExport
class AnmpCircleKeys private constructor(
    /** Circle key for packets and the space envelope. */
    val cipher: AnmpAead,
    /** Raw circle key (HKDF `anmp/v1/chat-aead`). */
    val aeadKey: ByteArray,
    /** Owner key of the circle space (HKDF `anmp/v1/mailbox-sign`, formerly circle mailbox). */
    val spaceKey: AnmpSpaceKey,
    /** Envelope of packets stored in the circle space (HKDF `anmp/v1/env`). */
    val envelope: AnmpEnvelope,
) {
    val space: String get() = spaceKey.space

    companion object {
        const val SECRET_BYTES = 32

        suspend fun derive(secret: ByteArray): AnmpCircleKeys {
            require(secret.size == SECRET_BYTES) { "circle secret must have 32 bytes" }
            val aead = AnmpCrypto.hkdf(secret, AnmpInfo.CHAT_AEAD)
            val seed = AnmpCrypto.hkdf(secret, AnmpInfo.MAILBOX_SIGN)
            return AnmpCircleKeys(AnmpAead.of(aead), aead, AnmpSpaceKey.fromPrivateKey(seed), AnmpEnvelope.circle(secret))
        }
    }
}

/**
 * Identity of one app installation (ADR 0013): Ed25519 device key (device ID derived from it) and X25519 key for
 * direct packets. Creating and storing the private keys is up to the app.
 */
@JsExport
class AnmpDevice private constructor(
    private val signer: EdDSA.PrivateKey,
    private val xdh: XDH.PrivateKey,
    private val xRaw: ByteArray,
    /** Ed25519 public key, 32 bytes. */
    val signKey: ByteArray,
    /** X25519 public key, 32 bytes. */
    val publicKey: ByteArray,
    /** Device ID, 16 bytes. */
    val deviceId: ByteArray,
) : AnmpSigner {
    /** Device ID as Base64url (22 characters). */
    val deviceIdText: String get() = b64u.encode(deviceId)

    /** ANMP tag (first 8 bytes of the device ID). */
    val tag: ByteArray get() = Anmp.tagOf(deviceId)

    override suspend fun sign(data: ByteArray): ByteArray = AnmpCrypto.edSign(signer, data)

    /** Device pair key with [peerDeviceId] / [peerPublicKey]; the same in both directions. */
    suspend fun directKey(peerDeviceId: ByteArray, peerPublicKey: ByteArray): ByteArray =
        AnmpKeys.directKey(AnmpCrypto.xShared(xdh, peerPublicKey), deviceId, peerDeviceId)

    suspend fun directCipher(peerDeviceId: ByteArray, peerPublicKey: ByteArray): AnmpAead = AnmpAead.of(directKey(peerDeviceId, peerPublicKey))

    /** Key for data that stays on this device (HKDF `anmp/v1/local-storage` over the X25519 private key). */
    suspend fun localStorageKey(): ByteArray = AnmpKeys.localStorageKey(xRaw)

    override fun toString(): String = "AnmpDevice($deviceIdText)"

    companion object {
        /** From raw private keys: Ed25519 seed and X25519 scalar, 32 bytes each. */
        suspend fun of(signPrivateKey: ByteArray, xPrivateKey: ByteArray): AnmpDevice {
            val signer = AnmpCrypto.edPrivate(signPrivateKey)
            val xdh = AnmpCrypto.xPrivate(xPrivateKey)
            val signKey = AnmpCrypto.edPublicRaw(signer)
            return AnmpDevice(signer, xdh, xPrivateKey.copyOf(), signKey, AnmpCrypto.xPublicRaw(xdh), AnmpKeys.deviceIdOf(signKey))
        }
    }
}

/**
 * Mailbox recipient (ADR 0084): an X25519 key derived from the owner seed of a personal mailbox, which every device of
 * the person holds. A direct packet to it ([tag] in `to`) can be opened by every device of the person, also one that
 * joined after the sender got the contact link. Its ID works like a device ID in the device pair key.
 */
@JsExport
class AnmpMailbox private constructor(
    private val xdh: XDH.PrivateKey,
    /** X25519 public key, 32 bytes (contact link field 8). */
    val publicKey: ByteArray,
    /** Mailbox recipient ID, 16 bytes. */
    val id: ByteArray,
) {
    /** ANMP tag (first 8 bytes of [id]). */
    val tag: ByteArray get() = Anmp.tagOf(id)

    /** Pair key with the sender device [peerDeviceId] / [peerPublicKey]; the same the sender gets from [AnmpDevice.directKey]. */
    suspend fun directKey(peerDeviceId: ByteArray, peerPublicKey: ByteArray): ByteArray =
        AnmpKeys.directKey(AnmpCrypto.xShared(xdh, peerPublicKey), id, peerDeviceId)

    suspend fun directCipher(peerDeviceId: ByteArray, peerPublicKey: ByteArray): AnmpAead = AnmpAead.of(directKey(peerDeviceId, peerPublicKey))

    companion object {
        /** From the owner seed of the personal mailbox (32 bytes). */
        suspend fun of(ownerSeed: ByteArray): AnmpMailbox {
            require(ownerSeed.size == Anmp.KEY_BYTES) { "mailbox owner seed must have 32 bytes" }
            val xdh = AnmpCrypto.xPrivate(AnmpCrypto.hkdf(ownerSeed, AnmpInfo.MAILBOX_X))
            val space = AnmpSpaceKey.fromPrivateKey(ownerSeed).publicKey
            return AnmpMailbox(xdh, AnmpCrypto.xPublicRaw(xdh), AnmpKeys.mailboxIdOf(space))
        }
    }
}

/** Key derivation and identifiers of ANMP version v1. */
@JsExport
object AnmpKeys {
    /** Alphabet of the circle channels of the PYRSA app (no 0, 1, I, O); only to recognize them for removal. */
    private const val LEGACY_CHANNEL_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** Meshtastic limit for channel names. */
    const val CHANNEL_NAME_MAX = 11

    /** 32 random bytes, e.g. a circle secret, blob key or private key. */
    fun randomKey(): ByteArray = AnmpCrypto.random(32)

    /** HKDF-SHA256, salt `anmp`, 32 bytes. */
    suspend fun hkdf(secret: ByteArray, info: String): ByteArray = AnmpCrypto.hkdf(secret, info)

    /**
     * Comparison code of a pairing (ADR 0093): 6 digits from HKDF(one-time [secret] ‖ Ed25519 [signKey] of the new
     * device, `anmp/v1/pair-code`), first 4 bytes big-endian modulo 1 000 000, with leading zeros.
     */
    suspend fun pairCode(secret: ByteArray, signKey: ByteArray): String {
        val h = hkdf(secret + signKey, AnmpInfo.PAIR_CODE)
        val n = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
        return (n % 1_000_000).toString().padStart(6, '0')
    }

    suspend fun sha256(data: ByteArray): ByteArray = AnmpCrypto.sha256(data)

    /** Device ID: first 16 bytes of SHA-256(`anmp/v1/device\n` || Ed25519 public key). */
    suspend fun deviceIdOf(signKey: ByteArray): ByteArray {
        require(signKey.size == Anmp.KEY_BYTES) { "Ed25519 public key must have 32 bytes" }
        return AnmpCrypto.sha256((AnmpInfo.DEVICE + "\n").encodeToByteArray() + signKey).copyOf(Anmp.DEVICE_BYTES)
    }

    /** Mailbox recipient ID: first 16 bytes of SHA-256(`anmp/v1/mailbox\n` || mailbox space public key) (ADR 0084). */
    suspend fun mailboxIdOf(spacePublicKey: ByteArray): ByteArray {
        require(spacePublicKey.size == Anmp.KEY_BYTES) { "mailbox space key must have 32 bytes" }
        return AnmpCrypto.sha256((AnmpInfo.MAILBOX + "\n").encodeToByteArray() + spacePublicKey).copyOf(Anmp.DEVICE_BYTES)
    }

    /** Device ID from Base64url text; `null` if invalid. */
    suspend fun deviceIdOfText(signKey: String): ByteArray? = decodeOrNull(signKey)?.takeIf { it.size == Anmp.KEY_BYTES }?.let { deviceIdOf(it) }

    /** Device pair key: HKDF(shared, `anmp/v1/direct|` + both device IDs as Base64url, sorted, joined with `|`). */
    suspend fun directKey(shared: ByteArray, deviceA: ByteArray, deviceB: ByteArray): ByteArray {
        val pair = listOf(b64u.encode(deviceA), b64u.encode(deviceB)).sorted().joinToString("|")
        return AnmpCrypto.hkdf(shared, AnmpInfo.DIRECT + "|" + pair)
    }

    /** Local storage key from the raw X25519 private key. */
    suspend fun localStorageKey(xPrivateKey: ByteArray): ByteArray = AnmpCrypto.hkdf(xPrivateKey, AnmpInfo.LOCAL_STORAGE)

    /**
     * `true` for a circle radio channel of the PYRSA app (`PYRSA-` and 5 characters, before ADR 0051). There are no
     * circle channels in ANMP; apps only recognize these names to remove them (ADR 0061 decision 6).
     */
    fun isLegacyChannelName(name: String?): Boolean =
        name != null &&
            name.length == AnmpInfo.CHANNEL_PREFIX.length + 5 &&
            name.startsWith(AnmpInfo.CHANNEL_PREFIX) &&
            name.substring(AnmpInfo.CHANNEL_PREFIX.length).all { it in LEGACY_CHANNEL_ALPHABET }

    /** Base64url without padding. */
    fun encode(bytes: ByteArray): String = b64u.encode(bytes)

    /** Base64url without padding; `null` if invalid. */
    fun decodeOrNull(text: String): ByteArray? = runCatching { b64u.decode(text) }.getOrNull()
}

/**
 * Envelope around a ANMP packet stored as a ANDP entry, so that relays never see the ANMP header:
 * AES-256-GCM(envelope key, packet, AAD = `anmp/v1/env\n` || space), random nonce in front, tag at the end.
 */
@JsExport
class AnmpEnvelope private constructor(private val aead: AnmpAead) {
    suspend fun seal(packet: ByteArray, space: String): ByteArray = aead.seal(packet, aad(space))

    /** `null` if tampered, for another space or another key. */
    suspend fun open(envelope: ByteArray, space: String): ByteArray? = aead.open(envelope, aad(space))

    companion object {
        /** Envelope of the circle space: HKDF(circle secret, `anmp/v1/env`). */
        suspend fun circle(secret: ByteArray): AnmpEnvelope {
            require(secret.size == AnmpCircleKeys.SECRET_BYTES) { "circle secret must have 32 bytes" }
            return AnmpEnvelope(AnmpAead.of(AnmpCrypto.hkdf(secret, AnmpInfo.ENV)))
        }

        /** Envelope of a personal mailbox: HKDF(mailbox space public key, `anmp/v1/inbox`); before ADR 0042 the owner X25519 public key. */
        suspend fun inbox(publicKey: ByteArray): AnmpEnvelope {
            require(publicKey.size == Anmp.KEY_BYTES) { "public key must have 32 bytes" }
            return AnmpEnvelope(AnmpAead.of(AnmpCrypto.hkdf(publicKey, AnmpInfo.INBOX)))
        }

        /** Fixed nonce, only for test vectors. */
        internal suspend fun withFixedNonce(key: ByteArray, nonce: ByteArray) = AnmpEnvelope(AnmpAead.withFixedNonce(key, nonce))

        fun aad(space: String): ByteArray = (AnmpInfo.ENV + "\n" + space).encodeToByteArray()
    }
}

/** Encrypted blob (attachment, picture, history part) with a hash ID (ADR 0035 §11). */
@JsExport
class AnmpSealedBlob(
    /** SHA-256 of [bytes], 32 bytes; equals the ANDP object ID. */
    val id: ByteArray,
    /** nonce(12) || ciphertext || tag(16). */
    val bytes: ByteArray,
) {
    /** [id] as ANDP object ID (Base64url, 43 characters). */
    val idText: String get() = b64u.encode(id)
}

/** Blobs: AES-256-GCM with a random key per blob, AAD `anmp/v1/att`, ID = SHA-256 of the sealed bytes. */
@JsExport
object AnmpBlob {
    const val KEY_BYTES = 32

    fun newKey(): ByteArray = AnmpKeys.randomKey()

    suspend fun seal(key: ByteArray, plaintext: ByteArray): AnmpSealedBlob = sealWith(AnmpAead.of(key), plaintext)

    internal suspend fun sealWith(cipher: AnmpCipher, plaintext: ByteArray): AnmpSealedBlob {
        val bytes = cipher.seal(plaintext, AnmpInfo.ATT.encodeToByteArray())
        return AnmpSealedBlob(AnmpCrypto.sha256(bytes), bytes)
    }

    /** `null` if [bytes] do not hash to [id] or do not open with [key]. */
    suspend fun open(key: ByteArray, id: ByteArray, bytes: ByteArray): ByteArray? {
        if (id.size != Anmp.BLOB_ID_BYTES || !AnmpCrypto.sha256(bytes).contentEquals(id)) return null
        return AnmpAead.of(key).open(bytes, AnmpInfo.ATT.encodeToByteArray())
    }

    suspend fun idOf(bytes: ByteArray): ByteArray = AnmpCrypto.sha256(bytes)
}
