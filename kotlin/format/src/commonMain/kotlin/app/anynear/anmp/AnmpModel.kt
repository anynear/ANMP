package app.anynear.anmp

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel
import kotlin.js.JsExport

/**
 * Signs with an Ed25519 key: [AnmpDevice], [AnmpSpaceKey] or an app key store.
 * Interfaces with suspend members are not exported to JS (Kotlin 2.4 bridge bug); JS uses [AnmpJs].
 */
interface AnmpSigner {
    suspend fun sign(data: ByteArray): ByteArray
}

/** Checks a packet signature with the sender's key from [header] or [content]. Not exported to JS. */
interface AnmpVerifier {
    suspend fun verify(header: AnmpHeader, content: AnmpContent, data: ByteArray, signature: ByteArray): Boolean

    companion object {
        /** Verifier from a function. */
        fun of(block: suspend (header: AnmpHeader, content: AnmpContent, data: ByteArray, signature: ByteArray) -> Boolean): AnmpVerifier =
            object : AnmpVerifier {
                override suspend fun verify(header: AnmpHeader, content: AnmpContent, data: ByteArray, signature: ByteArray) = block(header, content, data, signature)
            }

        /** Accepts only signatures by [publicKey] (Ed25519, e.g. from the member directory). */
        fun withKey(publicKey: ByteArray): AnmpVerifier = of { _, _, data, signature -> AnmpCrypto.edVerify(publicKey, data, signature) }

        /**
         * Internet rule: key from content field 9; the device ID derived from it must match header `from` and,
         * if present, header `senderDevice`.
         */
        val fromContent: AnmpVerifier = of { header, content, data, signature ->
            val signKey = content.signKey
            if (signKey == null || signKey.size != Anmp.KEY_BYTES) return@of false
            val device = AnmpKeys.deviceIdOf(signKey)
            if (!Anmp.tagOf(device).contentEquals(header.from)) return@of false
            if (header.senderDevice != null && !header.senderDevice.contentEquals(device)) return@of false
            AnmpCrypto.edVerify(signKey, data, signature)
        }

        /** Rejects every signature (for unsigned-only paths). */
        val none: AnmpVerifier = of { _, _, _, _ -> false }

        /** Accepts every signature. Only for tests or when the caller checks the sender itself. */
        val any: AnmpVerifier = of { _, _, _, _ -> true }
    }
}

/** AEAD of a packet: circle key or device pair key. [AnmpAead] is the AES-256-GCM implementation. Not exported to JS. */
interface AnmpCipher {
    /** nonce(12) || AES-256-GCM ciphertext || tag(16). */
    suspend fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray

    /** `null` if tampered or not for this key. */
    suspend fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray?
}

/** Kind of a message. [code] is on the wire and never changes; [conv] is the name in the radio rule (ADR 0015). */
@JsExport
enum class AnmpKind(val code: Int, val conv: String) {
    CIRCLE(1, "circle"),
    DIRECT(2, "direct"),
    REACT(3, "react"),
    READ(4, "read"),
    RECEIPT(5, "receipt"),
    MEMBER(10, "member"),
    META(11, "meta"),
    PROFILE(12, "profile"),
    WANT(13, "want"),
    HAVE(14, "have"),
    HISTORY(15, "history"),
    LEFT(16, "left"),
    REKEY(17, "rekey"),
    REMOVED(18, "removed"),
    /** State for the own other devices (ADR 0042). */
    SYNC(19, "sync"),
    /** Request of a device that has only radio, open, header only (ADR 0051). */
    ASK(20, "ask"),
    /** Answer of a patron to an [ASK], open, header only (ADR 0051). */
    PATRON(21, "patron"),
    /** Pickup keys for one patron, sealed to its one-time key (ADR 0051 §5a). */
    PICKUP(22, "pickup"),
    /** Subscriber of a shared channel announces itself to the owner (ADR 0062 §11). */
    SUBSCRIBE(23, "subscribe"),
    /** Subscriber left a shared channel (ADR 0062 §11). */
    UNSUBSCRIBE(24, "unsubscribe"),
    /** New read key of a channel for one remaining subscriber after a removal, signed by the owner key (ADR 0062 §11). */
    CHANNEL_KEY(25, "channel-key"),
    /** The author withdraws an own message for everyone ([AnmpContent.ref], [AnmpContent.refFrom]; ADR 0068). */
    DELETE(26, "delete"),
    /** Sign of life of the sender in a circle: plan, confirmation or end ([AnmpContent.alive]; ADR 0083 §5). */
    ALIVE(27, "alive"),
    /** A helper answers a sign of life: looking after it, away or back ([AnmpContent.care]; ADR 0083 §4, §5.3). */
    CARE(28, "care"),
    /**
     * Receipt entry that carries the refs to the contents of a message without expiry, with the contents' expiry, so
     * they count on the space's quota only while they live (ADR 0088). Readers drop it.
     */
    HOLD(29, "hold"),
    ;

    /** `true` for kinds that are sent open, as header without ciphertext ([Anmp.encodeOpen]). */
    val open: Boolean get() = this == ASK || this == PATRON

    /**
     * Radio rule (ADR 0015, 0057): `true` for kinds that may go over radio. Radio carries what people write (messages,
     * reactions, read and delivery receipts), signs of life and their answers (ADR 0083) and the patron exchange (ADR 0051);
     * everything else waits for the internet.
     */
    val radio: Boolean get() = this == CIRCLE || this == DIRECT || this == REACT || this == DELETE || this == READ || this == RECEIPT || this == ASK || this == PATRON || this == PICKUP ||
            this == ALIVE || this == CARE

    companion object {
        fun of(code: Int): AnmpKind? = entries.firstOrNull { it.code == code }

        fun ofConv(conv: String): AnmpKind? = entries.firstOrNull { it.conv == conv }
    }
}

/**
 * Header in clear: kind, sender tag, recipient tag (direct only), circle key epoch (radio). [senderKey] (X25519)
 * and [senderDevice] (device ID) only for direct packets over the internet, to derive the device pair key.
 *
 * Public form over radio (ADR 0051): [from] is the hourly sender tag, [route] the hourly route tag of the
 * recipient device or circle, [to] is absent. [askTags] and [since] only in [AnmpKind.ASK], [waiting] only in
 * [AnmpKind.PATRON].
 */
@JsExport
class AnmpHeader(
    val kind: AnmpKind,
    val from: ByteArray,
    val to: ByteArray? = null,
    val epoch: Int? = null,
    val senderKey: ByteArray? = null,
    val senderDevice: ByteArray? = null,
    val route: ByteArray? = null,
    val since: Long? = null,
    val askTags: Array<ByteArray>? = null,
    val waiting: Int? = null,
    /** Hour up to which a [AnmpKind.PICKUP] reaches (ADR 0051 §5a). */
    val hour: Long? = null,
)

@JsExport
class AnmpEncoded(val packet: ByteArray, val opened: ByteArray)

/** Opened message. [opened]: header, content and signature without encryption, see [Anmp.decodeOpened]. */
@JsExport
class AnmpMessage(val header: AnmpHeader, val content: AnmpContent, val signed: Boolean, val opened: ByteArray)

/** Encrypted content. All fields optional; which a kind uses is in `spec/anmp.md`. Unknown fields are skipped. */
@JsExport
@Serializable
class AnmpContent(
    /** Message ID ([Anmp.ID_BYTES]). */
    @CborLabel(0) val id: ByteArray? = null,
    /** Sender time, seconds since 1970. */
    @CborLabel(1) val ts: Long = 0,
    @CborLabel(2) val text: String? = null,
    /** Message ID a reaction or receipt refers to. */
    @CborLabel(3) val ref: ByteArray? = null,
    /** Tag of the author of [ref]. */
    @CborLabel(4) val refFrom: ByteArray? = null,
    /** Reaction; empty = withdrawn. */
    @CborLabel(5) val emoji: String? = null,
    /** Message IDs of a read or delivery receipt. */
    @CborLabel(6) val ids: List<ByteArray>? = null,
    // From here internet only (radio rule, ADR 0015), except 10 and 32 (ADR 0058).
    @CborLabel(7) val name: String? = null,
    /** Sender X25519 public key. */
    @CborLabel(8) val key: ByteArray? = null,
    /** Sender Ed25519 public key; the device ID is derived from it. */
    @CborLabel(9) val signKey: ByteArray? = null,
    /** Meshtastic node number (unsigned); over radio in circle and direct messages when the text leaves room (ADR 0058). */
    @CborLabel(10) val node: Long? = null,
    @CborLabel(11) val attachments: List<AnmpAttachment>? = null,
    /** Own picture (member, profile). */
    @CborLabel(12) val image: AnmpImage? = null,
    /** Time of the last picture change, ms. */
    @CborLabel(13) val imageTs: Long? = null,
    /** Plaintext hash of the own picture (first 16 bytes of SHA-256), not the blob ID. */
    @CborLabel(14) val imageHash: ByteArray? = null,
    /** Plaintext hash of the circle picture (16 bytes). */
    @CborLabel(15) val circleImageHash: ByteArray? = null,
    @CborLabel(16) val meta: AnmpMeta? = null,
    /** want/have: wanted plaintext hash (16 bytes). */
    @CborLabel(17) val wantHash: ByteArray? = null,
    /** have: where it is. */
    @CborLabel(18) val haveRef: AnmpImage? = null,
    /** member: needs the history. */
    @CborLabel(19) val needsHistory: Boolean? = null,
    @CborLabel(20) val history: AnmpHistory? = null,
    @CborLabel(21) val rekey: AnmpRekey? = null,
    @CborLabel(22) val removed: AnmpRemoved? = null,
    /** Space of the sender's personal mailbox (raw Ed25519 public key, 32 bytes), so the reader can answer (core on ANDP). */
    @CborLabel(23) val inbox: ByteArray? = null,
    /** ANDP grant (CBOR `[body, sig]`) that lets the reader write into [inbox] (ADR 0035 §2). */
    @CborLabel(24) val grant: ByteArray? = null,
    /** Key chain of the sender's person ([AnmpPersons.proof]): in member, profile and to own devices (ADR 0042). */
    @CborLabel(25) val person: ByteArray? = null,
    /** Person ID (16 bytes) of the conversation partner: copies and read state for the own other devices. */
    @CborLabel(26) val peer: ByteArray? = null,
    /** State of the sender's key chain; a reader with an older one asks for [person]. */
    @CborLabel(27) val personRef: AnmpPersonRef? = null,
    /** Circle ID of a read state for the own other devices. */
    @CborLabel(28) val circle: String? = null,
    /** State for the own other devices (kind sync). */
    @CborLabel(29) val sync: AnmpSync? = null,
    /** New keys of the person for the own other devices after a person key change (kind sync, ADR 0042 §6). */
    @CborLabel(30) val keys: AnmpSyncKeys? = null,
    /** Data of the app for the own other devices (kind sync), e.g. the direct history for an added device. */
    @CborLabel(31) val ownData: AnmpOwnData? = null,
    /** Delivery receipt: paths the confirmed messages arrived on, bits of [AnmpArrived] (ADR 0058); also over radio. */
    @CborLabel(32) val arrived: Int? = null,
    /** Channel of a subscribe, unsubscribe or channel key (ADR 0062 §11). */
    @CborLabel(33) val channel: AnmpChannelRef? = null,
    /** Preview of the first web link in [text], made by the sender (ADR 0069); internet only. */
    @CborLabel(34) val link: AnmpLink? = null,
    /** Sign of life (kind alive, ADR 0083 §5); also over radio. */
    @CborLabel(35) val alive: AnmpAlive? = null,
    /** Answer of a helper (kind care, ADR 0083 §4); also over radio. */
    @CborLabel(36) val care: AnmpCare? = null,
    /**
     * Device ID (16 bytes) of the original sender, only in an own copy of a packet received from someone else (ADR 0107):
     * a received message written into the own mailbox by an own device (migrated, or heard only over radio).
     */
    @CborLabel(37) val author: ByteArray? = null,
) {
    /**
     * This content as an own copy in the conversation with the person [peer] (person ID, content 26), written by the own
     * device with the keys [key] (X25519) and [signKey] (Ed25519) (ADR 0107). [author]: device ID of the original sender
     * of a received packet, `null` for a packet the own person sent. What only proves the original sender (its keys,
     * chain, grant, mailbox) is left out: the own device vouches for the copy.
     */
    fun asCopy(peer: ByteArray, author: ByteArray?, key: ByteArray, signKey: ByteArray): AnmpContent = AnmpContent(
        id = id,
        ts = ts,
        text = text,
        ref = ref,
        refFrom = refFrom,
        emoji = emoji,
        ids = ids,
        name = name,
        key = key,
        signKey = signKey,
        node = node,
        attachments = attachments,
        image = image,
        imageTs = imageTs,
        imageHash = imageHash,
        circleImageHash = circleImageHash,
        meta = meta,
        wantHash = wantHash,
        haveRef = haveRef,
        needsHistory = needsHistory,
        history = history,
        rekey = rekey,
        removed = removed,
        inbox = null,
        grant = null,
        person = null,
        peer = peer,
        personRef = null,
        circle = circle,
        sync = sync,
        keys = keys,
        ownData = ownData,
        arrived = arrived,
        channel = channel,
        link = link,
        alive = alive,
        care = care,
        author = author,
    )
}

/**
 * Sign of life (ADR 0083 §5) of the sender's person, in a circle it chose. Every packet carries the whole plan, so a helper
 * who missed the start still knows it. Times in seconds since 1970 by the sender's clock, durations in seconds.
 * [plan]: ID of the plan ([Anmp.ID_BYTES]), the message ID of its start; [state]: [AnmpAliveState];
 * [due]: the next sign of life comes at the latest then; [hint], [alarm]: after [due], helpers first get a hint, then
 * an alarm; [quiet]: how long the phone may lie still before it asks; [motion]: movement counts as a sign of life;
 * [until]: latest end; [label]: name of the template; [battery]: percent, if known.
 */
@JsExport
@Serializable
class AnmpAlive(
    @CborLabel(0) val plan: ByteArray,
    @CborLabel(1) val state: Int,
    @CborLabel(2) val start: Long,
    @CborLabel(3) val until: Long,
    @CborLabel(4) val due: Long,
    @CborLabel(5) val hint: Int,
    @CborLabel(6) val alarm: Int,
    @CborLabel(7) val quiet: Int,
    @CborLabel(8) val motion: Boolean = false,
    @CborLabel(9) val label: String? = null,
    @CborLabel(10) val battery: Int? = null,
)

/** Values of [AnmpAlive.state]. Unknown values are read like [OK]. */
@JsExport
object AnmpAliveState {
    /** Started; helpers learn the plan. */
    const val START = 1

    /** Confirmed by a tap („Alles gut“). */
    const val OK = 2

    /** The phone was moving, which counts as a sign of life (only with [AnmpAlive.motion]). */
    const val MOVING = 3

    /** The phone lives, but nobody confirmed after it asked. */
    const val MISSED = 4

    /** Ended on purpose: all is well. */
    const val END = 5

    /** Battery nearly empty; the phone may go off soon. */
    const val BATTERY = 6

    /** Reached the latest end without being ended. */
    const val TIMEOUT = 7
}

/** Answer of a helper to the sign of life [plan] (ADR 0083 §4, §5.3); [state]: [AnmpCareState]. */
@JsExport
@Serializable
class AnmpCare(
    @CborLabel(0) val plan: ByteArray,
    @CborLabel(1) val state: Int,
)

/** Values of [AnmpCare.state]. */
@JsExport
object AnmpCareState {
    /** „Ich schaue nach“ / „Ich kümmere mich“. */
    const val LOOKING = 1

    /** „Ich kann gerade nicht“: not available for this plan. */
    const val AWAY = 2

    /** Available again. */
    const val BACK = 3
}

/**
 * Channel part of the kinds subscribe, unsubscribe and channel key (ADR 0062 §11). [space] names the channel;
 * [readGrant] (subscribe): SHA-256 of the read grant the subscriber holds, so that the owner takes only subscribers
 * with the current link; [readKey], [grant], [sig] (channel key): new private read key, new read grant (wire form) and
 * the owner key's signature over [AnmpAlerts.channelKeySigningBytes].
 */
@JsExport
@Serializable
class AnmpChannelRef(
    @CborLabel(0) val space: String,
    @CborLabel(1) val readGrant: ByteArray? = null,
    @CborLabel(2) val readKey: ByteArray? = null,
    @CborLabel(3) val grant: String? = null,
    @CborLabel(4) val sig: ByteArray? = null,
)

/** Bits of [AnmpContent.arrived]: on which paths the receiver got the confirmed messages (ADR 0058). */
object AnmpArrived {
    const val INTERNET = 1
    const val RADIO = 2
}

/**
 * Attachment (ADR 0011): blob [id] (SHA-256 of the encrypted bytes, 32), blob [key], type (`jpg`, `pdf`, `m4a`, `file`),
 * name, size, dimensions, plaintext hash (first 16 bytes of SHA-256, finds equal content across uploads), voice length ms and auto transcript flag (ADR 0030).
 */
@JsExport
@Serializable
class AnmpAttachment(
    @CborLabel(0) val id: ByteArray,
    @CborLabel(1) val key: ByteArray,
    @CborLabel(2) val type: String,
    @CborLabel(3) val name: String? = null,
    @CborLabel(4) val size: Long,
    @CborLabel(5) val width: Int = 0,
    @CborLabel(6) val height: Int = 0,
    @CborLabel(7) val hash: ByteArray? = null,
    @CborLabel(8) val duration: Long = 0,
    @CborLabel(9) val transcribed: Boolean = false,
)

/**
 * Preview of a web link (ADR 0069): [url] must appear in the message text, otherwise readers ignore it. [image] is a
 * small `jpg` attachment in the space of the message; it does not count towards the attachment limit.
 */
@JsExport
@Serializable
class AnmpLink(
    @CborLabel(0) val url: String,
    @CborLabel(1) val title: String? = null,
    @CborLabel(2) val description: String? = null,
    @CborLabel(3) val site: String? = null,
    @CborLabel(4) val image: AnmpAttachment? = null,
)

/** Picture: blob [id] (32), blob [key], [from] = space that holds the blob (32), plaintext hash (16). */
@JsExport
@Serializable
class AnmpImage(
    @CborLabel(0) val id: ByteArray,
    @CborLabel(1) val key: ByteArray,
    @CborLabel(2) val from: ByteArray,
    @CborLabel(3) val hash: ByteArray? = null,
)

/** Circle name and picture, each with time (ms) and device of the last change (ADR 0012). */
@JsExport
@Serializable
class AnmpMeta(
    @CborLabel(0) val name: String,
    @CborLabel(1) val nameTs: Long,
    @CborLabel(2) val nameBy: ByteArray,
    @CborLabel(3) val image: AnmpImage? = null,
    @CborLabel(4) val imageTs: Long,
    @CborLabel(5) val imageBy: ByteArray,
)

/** History index for a new member (ADR 0014). Times in ms. */
@JsExport
@Serializable
class AnmpHistory(
    @CborLabel(0) val circle: String,
    @CborLabel(1) val count: Int,
    @CborLabel(2) val from: Long = 0,
    @CborLabel(3) val to: Long = 0,
    @CborLabel(4) val parts: List<AnmpHistoryPart> = emptyList(),
)

/**
 * One history part: blob [id] (32), blob [key], [from] = space that holds the blob, [hash] = SHA-256
 * of the plaintext, [del] = delete token from before ANDP 1 (optional, not written any more), [grant] = ANDP grant
 * `{delete, grantee: <new member>, scope: <id>}` so the receiver removes the part after reading (ADR 0035 §2).
 */
@JsExport
@Serializable
class AnmpHistoryPart(
    @CborLabel(0) val id: ByteArray,
    @CborLabel(1) val key: ByteArray,
    @CborLabel(2) val from: ByteArray,
    @CborLabel(3) val hash: ByteArray,
    @CborLabel(4) val del: ByteArray? = null,
    @CborLabel(5) val grant: ByteArray? = null,
)

/** New circle secret [key] with [epoch]; [removed] device, [why] (`remove`, `reset`, `left`, `device`), [circle] ID, [ts] ms. */
@JsExport
@Serializable
class AnmpRekey(
    @CborLabel(0) val key: ByteArray,
    @CborLabel(1) val epoch: Int,
    @CborLabel(2) val removed: ByteArray? = null,
    @CborLabel(3) val why: String? = null,
    @CborLabel(4) val circle: String? = null,
    @CborLabel(5) val ts: Long = 0,
)

/** Notice to a removed member: [epoch], circle ID, time ms. */
@JsExport
@Serializable
class AnmpRemoved(
    @CborLabel(0) val epoch: Int,
    @CborLabel(1) val circle: String? = null,
    @CborLabel(2) val ts: Long = 0,
)
