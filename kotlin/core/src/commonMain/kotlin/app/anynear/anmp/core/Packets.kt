package app.anynear.anmp.core

import app.anynear.andp.B64
import app.anynear.anmp.Anmp
import app.anynear.anmp.AnmpCipher
import app.anynear.anmp.AnmpContent
import app.anynear.anmp.AnmpEncoded
import app.anynear.anmp.AnmpHeader
import app.anynear.anmp.AnmpKeys
import app.anynear.anmp.AnmpKind
import app.anynear.anmp.AnmpMessage
import app.anynear.anmp.AnmpVerifier

/** A recipient installation of a direct packet: device ID and X25519 key (b64u). */
internal class Peer(val deviceId: String, val publicKey: String) {
    val deviceBytes: ByteArray get() = B64.decode(deviceId)
    val keyBytes: ByteArray get() = B64.decode(publicKey)
    val tag: ByteArray get() = Anmp.tagOf(deviceBytes)
}

/** An opened packet with the sender data the internet rule guarantees (signed, device ID from content 9). */
internal class Opened(
    val message: AnmpMessage,
    val fromDevice: String,
    val fromKey: String,
    val fromSignKey: String?,
    /** Radio packet forwarded into the personal mailbox (ADR 0022): unsigned, opened with the pair key. */
    val forwarded: Boolean,
) {
    val kind: AnmpKind get() = message.header.kind
    val content: AnmpContent get() = message.content
    val toUs: Boolean get() = message.header.to != null
}

/** Builds and opens ANMP packets of the internet path (spec/anmp.md "Kinds", "Encryption and signature"). */
internal object Packets {
    /**
     * Encodes a signed internet packet. Without [to] it is encrypted with the circle key ([circle]), with [to]
     * with the device pair key; [scope] is the circle ID in a circle space, `""` in a personal mailbox.
     */
    suspend fun encode(me: CoreIdentity, kind: AnmpKind, content: AnmpContent, scope: String, circle: CircleKeys?, to: Peer?): AnmpEncoded {
        val header = AnmpHeader(
            kind,
            me.tag,
            to = to?.tag,
            senderKey = to?.let { me.device.publicKey },
            senderDevice = to?.let { me.device.deviceId },
        )
        val cipher: AnmpCipher = if (to != null) me.device.directCipher(to.deviceBytes, to.keyBytes) else requireNotNull(circle) { "circle packet without circle key" }.anmp.cipher
        return Anmp.encodeFull(header, content, scope, cipher, me.device)
    }

    /**
     * Opens a packet from a space. A packet with `to` must be for us. Without fields 4/5 in the header it was
     * forwarded from radio (ADR 0022) and is opened with the pair key of the member known by its tag
     * ([memberByTag]), without signature. Everything else needs a valid signature by content field 9.
     */
    suspend fun open(
        me: CoreIdentity,
        packet: ByteArray,
        scope: String,
        circle: CircleKeys?,
        memberByTag: (ByteArray) -> Peer?,
    ): Opened? {
        val h = Anmp.peek(packet) ?: return null
        val to = h.to
        // Direct packets are for this device or, from a new contact, for the person's mailbox (ADR 0084).
        val toMailbox = to != null && to.contentEquals(me.mailbox.tag)
        if (to != null && !to.contentEquals(me.tag) && !toMailbox) return null
        if (to != null && (h.senderKey == null || h.senderDevice == null)) {
            if (toMailbox) return null
            if (scope != Anmp.DIRECT_SCOPE) return null
            val peer = memberByTag(h.from) ?: return null
            if (h.kind !in FORWARDED_KINDS) return null
            val cipher = runCatching { me.device.directCipher(peer.deviceBytes, peer.keyBytes) }.getOrNull() ?: return null
            val m = Anmp.decode(packet, scope, cipher, AnmpVerifier.none, requireSignature = false) ?: return null
            return Opened(m, peer.deviceId, peer.publicKey, null, forwarded = true)
        }
        val cipher: AnmpCipher = if (toMailbox) {
            runCatching { me.mailbox.directCipher(h.senderDevice!!, h.senderKey!!) }.getOrNull() ?: return null
        } else if (to != null) {
            runCatching { me.device.directCipher(h.senderDevice!!, h.senderKey!!) }.getOrNull() ?: return null
        } else {
            circle?.anmp?.cipher ?: return null
        }
        val m = Anmp.decode(packet, scope, cipher, AnmpVerifier.fromContent, requireSignature = true) ?: return null
        return checkSender(m, forwarded = false)
    }

    /** Opened packet from a history part (ADR 0014): signature by content field 9 in [scope]. */
    suspend fun openHistory(opened: ByteArray, scope: String): Opened? {
        val m = Anmp.decodeOpened(opened, scope, AnmpVerifier.fromContent, requireSignature = true) ?: return null
        return checkSender(m, forwarded = false)
    }

    private suspend fun checkSender(m: AnmpMessage, forwarded: Boolean): Opened? {
        val sk = m.content.signKey ?: return null
        val key = (m.content.key ?: m.header.senderKey)?.takeIf { it.size == Anmp.KEY_BYTES } ?: return null
        if (m.header.senderKey != null && !m.header.senderKey!!.contentEquals(key)) return null
        val device = AnmpKeys.deviceIdOf(sk)
        return Opened(m, B64.encode(device), B64.encode(key), B64.encode(sk), forwarded)
    }

    /** Kinds another app at a shared radio puts into our mailbox (ADR 0022). */
    val FORWARDED_KINDS = setOf(AnmpKind.DIRECT, AnmpKind.REACT, AnmpKind.DELETE, AnmpKind.READ)
}
