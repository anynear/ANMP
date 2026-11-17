package app.anynear.anmp.core

import app.anynear.andp.B64
import app.anynear.andp.Grant
import app.anynear.andp.Hash
import app.anynear.andp.Key
import app.anynear.anmp.AnmpKeys
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.cbor.CborLabel
import kotlin.js.JsExport

/** CBOR as in ANMP: integer keys, definite lengths, byte strings, defaults omitted. */
internal val coreCbor = Cbor {
    preferCborLabelsOverNames = true
    ignoreUnknownKeys = true
    encodeDefaults = false
    alwaysUseByteString = true
    useDefiniteLengthEncoding = true
}

/** Decoded invitation link (`https://anynear.app/k#…`). The secret makes whoever has the link a member. */
@JsExport
class Invite(
    val networkId: String,
    val circleId: String,
    val name: String,
    /** Circle secret, b64u. */
    val secret: String,
    val epoch: Int,
    val epochTs: Long,
    val epochBy: String,
    /** Opaque radio contact of the inviter (app-defined, e.g. Meshtastic `SharedContact`). */
    val radio: ByteArray?,
    /** Name of whoever created the link, for „Eingeladen von …“; `""` in links of older apps. */
    val inviter: String = "",
)

/** Decoded contact link (`https://anynear.app/c#…`). Only public data; the grant lets the reader write into [inbox]. */
@JsExport
class ContactCard(
    val networkId: String,
    val name: String,
    val deviceId: String,
    val signKey: String,
    /** X25519 key, b64u. */
    val publicKey: String,
    /** Space of the personal mailbox, b64u. */
    val inbox: String,
    /** Write grant for [inbox] (b64u of CBOR `[body, sig]`). */
    val grant: String,
    val radio: ByteArray?,
    /** X25519 key of the mailbox recipient (ADR 0084), b64u; `null` in links of older apps. */
    val mailboxKey: String? = null,
)

/**
 * Decoded pairing link (`https://anynear.app/p#…`, ADR 0042 §5), shown as QR by a new device: its device keys, a
 * pickup space it owns with a write grant for anyone, and the one-time [secret] that seals the hand-over.
 */
@JsExport
class PairOffer(
    val networkId: String,
    val deviceId: String,
    val signKey: String,
    val publicKey: String,
    val space: String,
    val grant: String,
    val secret: String,
    val name: String,
)

/**
 * Invitation and contact links (ADR 0036 §2). The format sits in the fragment, which browsers never send to the
 * server: `https://<host>/k#` or `/c#` followed by b64u of a CBOR map with integer keys (spec/anmp.md, "Core on ANDP").
 */
@JsExport
object AnmpLinks {
    const val VERSION = 1

    const val MAX_NAME = 40

    @Serializable
    internal class InviteWire(
        @CborLabel(0) val v: Int,
        @CborLabel(1) val network: ByteArray,
        @CborLabel(2) val id: String,
        @CborLabel(3) val name: String,
        @CborLabel(4) val secret: ByteArray,
        @CborLabel(5) val epoch: Int = 0,
        @CborLabel(6) val epochTs: Long = 0,
        @CborLabel(7) val epochBy: ByteArray? = null,
        @CborLabel(8) val radio: ByteArray? = null,
        @CborLabel(9) val inviter: String? = null,
    )

    @Serializable
    internal class ContactWire(
        @CborLabel(0) val v: Int,
        @CborLabel(1) val network: ByteArray,
        @CborLabel(2) val name: String,
        @CborLabel(3) val signKey: ByteArray,
        @CborLabel(4) val key: ByteArray,
        @CborLabel(5) val inbox: ByteArray,
        @CborLabel(6) val grant: ByteArray,
        @CborLabel(7) val radio: ByteArray? = null,
        @CborLabel(8) val mailboxKey: ByteArray? = null,
    )

    @Serializable
    internal class PairWire(
        @CborLabel(0) val v: Int,
        @CborLabel(1) val network: ByteArray,
        @CborLabel(2) val signKey: ByteArray,
        @CborLabel(3) val key: ByteArray,
        @CborLabel(4) val space: ByteArray,
        @CborLabel(5) val grant: ByteArray,
        @CborLabel(6) val secret: ByteArray,
        @CborLabel(7) val name: String? = null,
    )

    fun pairLink(host: String, offer: PairOffer): String {
        val wire = PairWire(
            VERSION, B64.decode(offer.networkId), B64.decode(offer.signKey), B64.decode(offer.publicKey), B64.decode(offer.space),
            Grant.parse(offer.grant).encode(), B64.decode(offer.secret), offer.name.take(MAX_NAME).takeIf { it.isNotEmpty() },
        )
        return "https://$host/p#" + B64.encode(coreCbor.encodeToByteArray(PairWire.serializer(), wire))
    }

    /** Reads a pairing link; the grant must be for the pickup space (signature checked when writing). */
    suspend fun parsePair(text: String, host: String): PairOffer? {
        val w = fragment(text, host, "p")?.let { runCatching { coreCbor.decodeFromByteArray(PairWire.serializer(), it) }.getOrNull() } ?: return null
        if (w.v != VERSION || w.network.size != Hash.SIZE || w.signKey.size != 32 || w.key.size != 32 || w.space.size != 32 || w.secret.size != 32) return null
        val grant = runCatching { Grant.decode(w.grant) }.getOrNull() ?: return null
        if (grant.body.space != Key(w.space)) return null
        return PairOffer(
            B64.encode(w.network), B64.encode(AnmpKeys.deviceIdOf(w.signKey)), B64.encode(w.signKey), B64.encode(w.key), B64.encode(w.space),
            grant.toString(), B64.encode(w.secret), w.name.orEmpty().trim().take(MAX_NAME),
        )
    }

    fun isPairLink(text: String, host: String): Boolean = text.contains("$host/p")

    fun inviteLink(host: String, invite: Invite): String {
        val wire = InviteWire(
            VERSION, B64.decode(invite.networkId), invite.circleId, invite.name.take(MAX_NAME), B64.decode(invite.secret),
            invite.epoch, invite.epochTs, invite.epochBy.takeIf { it.isNotEmpty() }?.let { B64.decode(it) }, invite.radio,
            invite.inviter.trim().take(MAX_NAME).takeIf { it.isNotEmpty() },
        )
        return "https://$host/k#" + B64.encode(coreCbor.encodeToByteArray(InviteWire.serializer(), wire))
    }

    fun contactLink(host: String, card: ContactCard): String {
        val wire = ContactWire(
            VERSION, B64.decode(card.networkId), card.name.take(MAX_NAME), B64.decode(card.signKey), B64.decode(card.publicKey),
            B64.decode(card.inbox), B64.decode(card.grant), card.radio, card.mailboxKey?.let(B64::decode),
        )
        return "https://$host/c#" + B64.encode(coreCbor.encodeToByteArray(ContactWire.serializer(), wire))
    }

    /** Reads an invitation link, also inside text as messengers paste it. `null` if none or broken. */
    fun parseInvite(text: String, host: String): Invite? {
        val w = fragment(text, host, "k")?.let { runCatching { coreCbor.decodeFromByteArray(InviteWire.serializer(), it) }.getOrNull() } ?: return null
        if (w.v != VERSION || w.network.size != Hash.SIZE || w.secret.size != 32 || w.id.isEmpty() || w.id.length > 64) return null
        if (w.epoch < 0 || (w.epochBy != null && w.epochBy.size != 16)) return null
        return Invite(B64.encode(w.network), w.id, w.name.trim().take(MAX_NAME), B64.encode(w.secret), w.epoch, w.epochTs, w.epochBy?.let(B64::encode).orEmpty(), w.radio,
            w.inviter.orEmpty().trim().take(MAX_NAME),
        )
    }

    /** Reads a contact link. The grant must be a structurally valid grant for the given inbox (signature checked by [AnmpCore]). */
    suspend fun parseContact(text: String, host: String): ContactCard? {
        val w = fragment(text, host, "c")?.let { runCatching { coreCbor.decodeFromByteArray(ContactWire.serializer(), it) }.getOrNull() } ?: return null
        if (w.v != VERSION || w.network.size != Hash.SIZE || w.signKey.size != 32 || w.key.size != 32 || w.inbox.size != 32) return null
        val grant = runCatching { Grant.decode(w.grant) }.getOrNull() ?: return null
        if (grant.body.space != Key(w.inbox)) return null
        val device = AnmpKeys.deviceIdOf(w.signKey)
        return ContactCard(
            B64.encode(w.network), w.name.trim().take(MAX_NAME), B64.encode(device), B64.encode(w.signKey), B64.encode(w.key),
            B64.encode(w.inbox), grant.toString(), w.radio, w.mailboxKey?.takeIf { it.size == 32 }?.let(B64::encode),
        )
    }

    fun isInviteLink(text: String, host: String): Boolean = text.contains("$host/k")

    fun isContactLink(text: String, host: String): Boolean = text.contains("$host/c")

    private fun fragment(text: String, host: String, kind: String): ByteArray? {
        val m = Regex(Regex.escape(host) + "/" + kind + "/?#([A-Za-z0-9_-]+)").find(text) ?: return null
        return runCatching { B64.decode(m.groupValues[1]) }.getOrNull()
    }
}

/**
 * Result of [AnmpCore.previewLink]. [kind] is [CIRCLE], [CONTACT] or [CHANNEL]; [joined]: already in the circle, the
 * contact is known, the channel is subscribed; [self]: own contact link; [curated]: flag of a channel link;
 * [target] opens what is already there (circle ID, person ID or channel ID), otherwise `null`.
 */
@JsExport
class LinkPreview(
    val kind: String,
    val name: String,
    val joined: Boolean,
    val self: Boolean = false,
    val curated: Boolean = false,
    val target: String? = null,
    /** Channel: the owner will see who subscribes (ADR 0062 §11). */
    val seenByOwner: Boolean = false,
    /** Circle: name of whoever created the invitation; `""` if the link does not say. */
    val inviter: String = "",
) {
    companion object {
        const val CIRCLE = "circle"
        const val CONTACT = "contact"
        const val CHANNEL = "channel"
        /** Pairing link of a new own device (`/p#`); [name] is the device name. */
        const val DEVICE = "device"
    }
}
