package app.anynear.anmp

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.XDH
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlin.io.encoding.Base64

/** Provider per platform: JDK (BouncyCastle on Android), CryptoKit/Apple on iOS, WebCrypto on JS. */
internal expect val anmpProvider: CryptographyProvider

/** Base64url without padding, as used for IDs and spaces. */
internal val b64u: Base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

/** Thin wrapper around cryptography-kotlin. No own primitives. */
internal object AnmpCrypto {
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16

    suspend fun hkdf(secret: ByteArray, info: String): ByteArray = hkdf(secret, info.encodeToByteArray())

    /** HKDF with binary info (UTF-8 label followed by keys, e.g. the alert seal). */
    suspend fun hkdf(secret: ByteArray, info: ByteArray): ByteArray =
        anmpProvider.get(HKDF)
            .secretDerivation(SHA256, 256.bits, AnmpInfo.SALT.encodeToByteArray(), info)
            .deriveSecretToByteArray(secret)

    suspend fun sha256(data: ByteArray): ByteArray = anmpProvider.get(SHA256).hasher().hash(data)

    fun random(size: Int): ByteArray = CryptographyRandom.nextBytes(size)

    suspend fun aesKey(raw: ByteArray): AES.GCM.Key {
        require(raw.size == 32) { "AES-256 key must have 32 bytes" }
        return anmpProvider.get(AES.GCM).keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, raw)
    }

    /** nonce(12) || ciphertext || tag(16). [nonce] only for test vectors. */
    @OptIn(DelicateCryptographyApi::class)
    suspend fun seal(key: AES.GCM.Key, plaintext: ByteArray, aad: ByteArray, nonce: ByteArray? = null): ByteArray {
        if (nonce == null) return key.cipher().encrypt(plaintext, aad)
        require(nonce.size == NONCE_BYTES) { "nonce must have $NONCE_BYTES bytes" }
        return nonce + key.cipher().encryptWithIv(nonce, plaintext, aad)
    }

    suspend fun open(key: AES.GCM.Key, sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (sealed.size < NONCE_BYTES + TAG_BYTES) return null
        return runCatching { key.cipher().decrypt(sealed, aad) }.getOrNull()
    }

    suspend fun edPrivate(seed: ByteArray): EdDSA.PrivateKey {
        require(seed.size == 32) { "Ed25519 private key must have 32 bytes" }
        return anmpProvider.get(EdDSA).privateKeyDecoder(EdDSA.Curve.Ed25519).decodeFromByteArray(EdDSA.PrivateKey.Format.RAW, seed)
    }

    suspend fun edPublicRaw(key: EdDSA.PrivateKey): ByteArray = key.getPublicKey().encodeToByteArray(EdDSA.PublicKey.Format.RAW)

    suspend fun edSign(key: EdDSA.PrivateKey, data: ByteArray): ByteArray = key.signatureGenerator().generateSignature(data)

    suspend fun edVerify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean = runCatching {
        if (publicKey.size != 32 || signature.size != 64) return false
        anmpProvider.get(EdDSA).publicKeyDecoder(EdDSA.Curve.Ed25519)
            .decodeFromByteArray(EdDSA.PublicKey.Format.RAW, publicKey)
            .signatureVerifier().tryVerifySignature(data, signature)
    }.getOrDefault(false)

    suspend fun xPrivate(raw: ByteArray): XDH.PrivateKey {
        require(raw.size == 32) { "X25519 private key must have 32 bytes" }
        return anmpProvider.get(XDH).privateKeyDecoder(XDH.Curve.X25519).decodeFromByteArray(XDH.PrivateKey.Format.RAW, raw)
    }

    suspend fun xPublicRaw(key: XDH.PrivateKey): ByteArray = key.getPublicKey().encodeToByteArray(XDH.PublicKey.Format.RAW)

    suspend fun xShared(key: XDH.PrivateKey, peerPublic: ByteArray): ByteArray {
        require(peerPublic.size == 32) { "X25519 public key must have 32 bytes" }
        val peer = anmpProvider.get(XDH).publicKeyDecoder(XDH.Curve.X25519).decodeFromByteArray(XDH.PublicKey.Format.RAW, peerPublic)
        return key.sharedSecretGenerator().generateSharedSecretToByteArray(peer)
    }
}
