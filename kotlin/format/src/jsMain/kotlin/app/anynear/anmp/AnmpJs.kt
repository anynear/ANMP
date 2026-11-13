package app.anynear.anmp

import kotlin.js.JsExport

/**
 * JS entry points for packets (TypeScript: Promise-returning). Uses concrete keys instead of the
 * [AnmpCipher] / [AnmpSigner] / [AnmpVerifier] interfaces, which are not exported.
 */
@JsExport
object AnmpJs {
    /** [Anmp.encode]; [signer] `null` = unsigned. */
    suspend fun encode(header: AnmpHeader, content: AnmpContent, scope: String, cipher: AnmpAead, signer: AnmpDevice?): ByteArray =
        Anmp.encode(header, content, scope, cipher, signer)

    /** [Anmp.encodeFull]. */
    suspend fun encodeFull(header: AnmpHeader, content: AnmpContent, scope: String, cipher: AnmpAead, signer: AnmpDevice?): AnmpEncoded =
        Anmp.encodeFull(header, content, scope, cipher, signer)

    /**
     * [Anmp.decode]. [signKey]: Ed25519 key of the expected sender (radio, member directory); `null` = internet rule
     * ([AnmpVerifier.fromContent]).
     */
    suspend fun decode(packet: ByteArray, scope: String, cipher: AnmpAead, signKey: ByteArray?, requireSignature: Boolean): AnmpMessage? =
        Anmp.decode(packet, scope, cipher, verifier(signKey), requireSignature)

    /** [Anmp.decodeOpened], verifier as in [decode]. */
    suspend fun decodeOpened(bytes: ByteArray, scope: String, signKey: ByteArray?, requireSignature: Boolean): AnmpMessage? =
        Anmp.decodeOpened(bytes, scope, verifier(signKey), requireSignature)

    /** Ed25519 check of [signature] over [data]. */
    suspend fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean = AnmpCrypto.edVerify(publicKey, data, signature)

    /** Wraps a JS array for [AnmpContent.ids] and other list fields. */
    fun <T> list(items: Array<T>): List<T> = items.toList()

    private fun verifier(signKey: ByteArray?) = if (signKey == null) AnmpVerifier.fromContent else AnmpVerifier.withKey(signKey)
}
