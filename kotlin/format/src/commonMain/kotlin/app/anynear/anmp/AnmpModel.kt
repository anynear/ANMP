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
    /**
     * Where the sender's person is, for one other person ([AnmpContent.place]; ADR 0121 §3, §5): the level it shares at,
     * a point or an area, or the request to be shown the reader's place. To every device of the person, never to a circle;
     * also over radio, seldom (ADR 0121 §13).
     */
    POSITION(30, "position"),
    /**
     * A person calls its helpers, or a helper answers ([AnmpContent.sos]; ADR 0083 §2–§4, ADR 0121 §13): into a circle
     * or to single persons, in a circle also over radio.
     */
    SOS(31, "sos"),
    ;

    /** `true` for kinds that are sent open, as header without ciphertext ([Anmp.encodeOpen]). */
    val open: Boolean get() = this == ASK || this == PATRON

    /**
     * Radio rule (ADR 0015, 0057): `true` for kinds that may go over radio. Radio carries what people write (messages,
     * reactions, read and delivery receipts), signs of life and their answers (ADR 0083) and the patron exchange (ADR 0051);
     * everything else waits for the internet.
     */
    val radio: Boolean get() = this == CIRCLE || this == DIRECT || this == REACT || this == DELETE || this == READ || this == RECEIPT || this == ASK || this == PATRON || this == PICKUP ||
            this == ALIVE || this == CARE || this == SOS || this == POSITION

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
    /**
     * The sender passed on a message it got („Weiterleiten“, ADR 0118); only written as `true`, without any origin.
     * Circle and direct messages, internet only.
     */
    @CborLabel(38) val passedOn: Boolean? = null,
    /** In a profile: the sender asks to become a contact (ADR 0116 §7); the reader decides, nothing is added before. */
    @CborLabel(39) val ask: Boolean? = null,
    /** In a direct message: a contact introduced to the reader (ADR 0116 §2). */
    @CborLabel(40) val intro: AnmpIntro? = null,
    /** In a direct message: the reader of an introduction wants the contact (to the introducer), or the forwarded request (to the introduced person) (ADR 0116 §3). */
    @CborLabel(41) val introReply: AnmpIntroReply? = null,
    /** Where the sender's person is and how much of it the reader sees (kind position, ADR 0121 §3). */
    @CborLabel(42) val place: AnmpPlace? = null,
    /** In a circle or direct message: a meeting point, the question „Wo bist du?“ or the answer to it (ADR 0121 §8). */
    @CborLabel(43) val spot: AnmpSpot? = null,
    /** A call for the helpers or a helper's answer to it (kind sos, ADR 0083 §2–§4); also over radio. */
    @CborLabel(44) val sos: AnmpSos? = null,
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
        passedOn = passedOn,
        ask = ask,
        intro = intro,
        introReply = introReply,
    )
}

/**
 * Body of an introduction (ADR 0116 §2), signed by the person key of [by]: the person [person] (named [name], as it calls
 * itself) is introduced to the person [to]. [id]: 16 random bytes; [until]: seconds since 1970. No mailbox, device or key
 * of [person]: the reader cannot reach it with this.
 */
@JsExport
@Serializable
class AnmpIntroCard(
    @CborLabel(0) val id: ByteArray,
    @CborLabel(1) val until: Long,
    @CborLabel(2) val person: ByteArray,
    @CborLabel(3) val name: String,
    @CborLabel(4) val to: ByteArray,
    @CborLabel(5) val by: ByteArray,
)


@JsExport
@Serializable
class AnmpIntroReply(
    /** The signed card ([AnmpIntro.card]) this answers. */
    @CborLabel(0) val card: ByteArray,
    /** Contact link (`https://…/c#…`) of the person that wants the contact, the introduced person adds it on „Zustimmen“. */
    @CborLabel(1) val contact: String,
)

/** Introduction in a direct message: [card] = CBOR `[body, sig]` of [AnmpIntroCard] ([AnmpIntros]); [image]: picture of the introduced person (`jpg`, in the space of the message). */
@JsExport
@Serializable
class AnmpIntro(
    @CborLabel(0) val card: ByteArray,
    @CborLabel(1) val image: AnmpAttachment? = null,
)

/**
 * Sign of life (ADR 0083 §5) of the sender's person, in a circle it chose. Every packet carries the whole plan, so a helper
 * who missed the start still knows it. Times in seconds since 1970 by the sender's clock, durations in seconds.
 * [plan]: ID of the plan ([Anmp.ID_BYTES]), the message ID of its start; [state]: [AnmpAliveState];
 * [due]: the next sign of life comes at the latest then; [hint], [alarm]: after [due], helpers first get a hint, then
 * an alarm; [quiet]: how long the phone may lie still before it asks; [motion]: movement counts as a sign of life;
 * [until]: latest end; [label]: name of the template; [battery]: percent, if known; [position]: where the phone
 * is, only in a packet that says nobody confirmed and only if the person allowed it (ADR 0121 §10). Label 11 is reserved.
 * [guard]: the phone wrote this packet ahead and the relays kept it back until [due] + [alarm] (ADR 0083 §5.7): it
 * says that nothing came from the phone since, and readers drop it if they know a newer packet of the plan.
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
    @CborLabel(12) val position: AnmpPosition? = null,
    @CborLabel(13) val guard: Boolean = false,
    /**
     * In a packet that says nobody confirmed and in a guard: the sender's phone raises the call for the helpers itself
     * when the alarm is due ([AnmpSos.plan]). A reader that only runs when a packet wakes it then waits for the call.
     */
    @CborLabel(14) val calls: Boolean = false,
    /**
     * Only for a sign of life that goes to one person: a random secret (32 bytes) both sides derive a space of their
     * own from, like a circle from its secret. The sender leaves the guard there, where it can delete it again, and
     * the helper reads it there (ADR 0083 §5.7, ADR 0121 §23).
     */
    @CborLabel(15) val watch: ByteArray? = null,
)

/**
 * A person calls its helpers (kind sos, ADR 0083 §2–§4, ADR 0121 §13). [id] names the call; every packet of the person
 * carries its whole state. [state]: [RAISED] it runs (the first packet and every update), [ENDED] the person gave the
 * all-clear, [CARING] a helper says it looks after it (sent by the helper; the other fields are empty then).
 * [position] is where the person is now, [trail] where it was before (oldest first, at most [MAX_TRAIL]), [battery] in
 * percent, [start] when the call began (seconds since 1970). [plan]: the sign of life the call comes from, when nobody
 * confirmed it; [id] is then [aliveId] of that plan and its `due`, so that the helpers' own deadline, the phone and the
 * alarm the phone left at the relays all name the same call.
 */
@JsExport
@Serializable
class AnmpSos(
    @CborLabel(0) val id: ByteArray,
    @CborLabel(1) val state: Int,
    @CborLabel(2) val position: AnmpPosition? = null,
    @CborLabel(3) val battery: Int? = null,
    @CborLabel(4) val trail: List<AnmpPosition>? = null,
    @CborLabel(5) val start: Long? = null,
    @CborLabel(6) val plan: ByteArray? = null,
    /** [READY] only: whether a call of the sender carries its precise place (ADR 0125 §8). */
    @CborLabel(7) val precise: Boolean? = null,
) {
    val plausible: Boolean
        get() = id.size == 8 && state in RAISED..READY && (battery == null || battery in 0..100) &&
            (trail == null || trail.size <= MAX_TRAIL) && (start == null || start > 0) && (plan == null || plan.size == 8) &&
            (state == READY) == (precise != null)

    companion object {
        const val RAISED = 1
        const val ENDED = 2
        const val CARING = 3

        /**
         * Being someone's contact for calls needs a yes, once (ADR 0121 §25): [ASK] the sender asks the readers to be
         * its contacts, [AGREE] and [REFUSE] are a reader's answer, sent to the asker alone; a later [REFUSE] takes a
         * yes back. Only field 0 is set, a random ID.
         */
        const val ASK = 4
        const val AGREE = 5
        const val REFUSE = 6

        /** The asker no longer counts the reader as its contact for calls (ADR 0123 §3); sent to the reader alone. */
        const val DROPPED = 7

        /**
         * The asker tells a contact whether its calls carry its precise place (field 7, ADR 0125 §8), so that nobody
         * relies on a place that will not come; sent to the contact alone, after its yes and whenever it changes.
         */
        const val READY = 8
        const val MAX_TRAIL = 10

        /**
         * ID of the call the sign of life [plan] turns into when the alarm of its [due] (seconds) is due: the first
         * 8 bytes of SHA-256(`anmp/v1/alive-sos` ‖ plan ‖ due as 8 bytes big-endian).
         */
        @JsExport.Ignore
        suspend fun aliveId(plan: ByteArray, due: Long): ByteArray {
            require(plan.size == Anmp.ID_BYTES) { "plan ID must have ${Anmp.ID_BYTES} bytes" }
            val d = ByteArray(8) { (due ushr (8 * (7 - it))).toByte() }
            return AnmpCrypto.sha256(AnmpInfo.ALIVE_SOS.encodeToByteArray() + plan + d).copyOf(Anmp.ID_BYTES)
        }
    }
}

/**
 * A place in a conversation (ADR 0121 §8), shown as a card that opens the map. [kind]: [MEET] a meeting point for
 * everyone in the conversation ([position], optional [label]); [ASK] „Wo bist du?“, without a position; [HERE] the
 * sender's place as the answer; [ALL] the invitation to share for a time, which says nothing about a place. Field 2 of the content carries a text for apps that do not know the field. A [MEET] or
 * [HERE] without a plausible position and an unknown kind are ignored, the message stays.
 */
@JsExport
@Serializable
class AnmpSpot(
    @CborLabel(0) val kind: Int,
    @CborLabel(1) val position: AnmpPosition? = null,
    @CborLabel(2) val label: String? = null,
    /** [ALL]: until when everyone who joins shares its place, seconds since 1970. */
    @CborLabel(3) val until: Long? = null,
    /** [SHARE]: the level asked for; [SHARED]: the level agreed to, 0 for no. Levels as in [AnmpPlace]. */
    @CborLabel(4) val level: Int? = null,
) {
    val plausible: Boolean
        get() = when (kind) {
            MEET, HERE, ARRIVED -> position?.plausible == true
            SHARE -> level == AnmpPlace.AREA || level == AnmpPlace.EXACT
            SHARED -> level == AnmpPlace.OFF || level == AnmpPlace.AREA || level == AnmpPlace.EXACT
            ENDED, WITHDRAWN -> true
            ASK -> true
            ALL -> until != null && until > 0
            else -> false
        } && (label == null || label.length <= MAX_LABEL)

    companion object {
        const val MEET = 1
        const val ASK = 2
        const val HERE = 3

        /** „Alle teilen“ (ADR 0121 §13): the sender invites everyone in the circle to share its place until [until]. */
        const val ALL = 4

        /** The sender arrived where it was going (ADR 0121 §26): [position] the place, [label] its name if it has one. */
        const val ARRIVED = 5

        /**
         * „Standort freigeben“ (ADR 0123 §2): the sender asks the reader that both see each other's place for good, at
         * [level]. Nothing goes out before the yes; then both show each other that level.
         */
        const val SHARE = 6

        /**
         * The answer to [SHARE]: [level] the level asked for as a yes, 0 for no. Older apps also sent it without a question
         * to lower or end what both agreed on; a reader takes that as the new level, 0 as the end.
         */
        const val SHARED = 7

        /** The sender ends the sharing with the reader, for both (ADR 0123 §2); also an open question. */
        const val ENDED = 8

        /** The sender withdraws its own open question [SHARE] (ADR 0123 §1). */
        const val WITHDRAWN = 9
        const val MAX_LABEL = 60
    }
}

/**
 * What a person shares of its place with the reader (kind position, ADR 0121 §3, §4). [level] is what the reader may
 * expect ([OFF], [WHEN], [AREA], [EXACT]; every level includes the ones before). [position] is the place right now: with
 * [radius] (metres) the centre of a fixed area the person is somewhere in, without it the point itself. No [position]:
 * nothing to show now (level [OFF] or [WHEN], or the sharing ended). [until]: seconds since 1970 until when the level
 * holds, missing = until changed. [ask]: the sender asks to be shown the reader's place (§4.4); such a packet says
 * nothing about the sender's own place. [label] and [since]: the name of a place the person saved and since when it is
 * there; readers show it instead of a place name and do not notify about it.
 */
@JsExport
@Serializable
class AnmpPlace(
    @CborLabel(0) val level: Int,
    @CborLabel(1) val position: AnmpPosition? = null,
    @CborLabel(2) val radius: Int? = null,
    @CborLabel(3) val until: Long? = null,
    @CborLabel(4) val ask: Boolean? = null,
    /** The name the person gave the place it is at, e.g. „Zu Hause“ (ADR 0121 §13); only with [position]. */
    @CborLabel(5) val label: String? = null,
    /** Since when the person is at [label], seconds since 1970. */
    @CborLabel(6) val since: Long? = null,
) {
    /** `false` for an unknown level, a place not on earth, an area without a centre or one wider than [MAX_RADIUS]. */
    val plausible: Boolean
        get() = level in OFF..EXACT && (position == null || position.plausible) &&
            (radius == null || (position != null && radius in 1..MAX_RADIUS)) && (until == null || until > 0) &&
            (label == null || (position != null && label.length in 1..MAX_LABEL)) && (since == null || (label != null && since > 0))

    companion object {
        const val OFF = 0
        const val WHEN = 1
        const val AREA = 2
        const val EXACT = 3
        const val MAX_RADIUS = 50_000
        const val MAX_LABEL = 40
    }
}

/**
 * A place on earth (ADR 0121 §10, ADR 0083 §6). [lat], [lon]: degrees × 10 000, north and east positive (about 10 m);
 * [at]: when the device located it, seconds since 1970 by its clock; [acc]: accuracy in metres, if known.
 */
@JsExport
@Serializable
class AnmpPosition(
    @CborLabel(0) val lat: Int,
    @CborLabel(1) val lon: Int,
    @CborLabel(2) val at: Long,
    @CborLabel(3) val acc: Int? = null,
) {
    /** In range: on earth, located at some time, accuracy at most [MAX_ACC] metres. */
    val plausible: Boolean
        get() = lat in -MAX_LAT..MAX_LAT && lon in -MAX_LON..MAX_LON && at > 0 && (acc == null || acc in 0..MAX_ACC)

    companion object {
        /** Degrees × [SCALE] on the wire. */
        const val SCALE = 10_000

        const val MAX_LAT = 90 * SCALE

        const val MAX_LON = 180 * SCALE

        /** Anything less accurate than 100 km says nothing. */
        const val MAX_ACC = 100_000
    }
}

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

/**
 * A place of a circle (ADR 0121 §21): [id] (8 bytes), [name], the point in degrees × [AnmpPosition.SCALE] and the
 * [radius] around it in metres. Every member knows it and may change it.
 */
@JsExport
@Serializable
class AnmpCirclePlace(
    @CborLabel(0) val id: ByteArray,
    @CborLabel(1) val name: String,
    @CborLabel(2) val lat: Int,
    @CborLabel(3) val lon: Int,
    @CborLabel(4) val radius: Int,
) {
    val plausible: Boolean
        get() = id.size == ID_BYTES && name.length in 1..AnmpPlace.MAX_LABEL && lat in -90 * AnmpPosition.SCALE..90 * AnmpPosition.SCALE &&
            lon in -180 * AnmpPosition.SCALE..180 * AnmpPosition.SCALE && radius in 1..MAX_RADIUS

    companion object {
        const val ID_BYTES = 8
        const val MAX_RADIUS = 5_000
        const val MAX_PLACES = 20
    }
}

/**
 * Circle name and picture, each with time (ms) and device of the last change (ADR 0012); and the places of the
 * circle as one list with its time and device ([placesTs] missing: this packet says nothing about them).
 */
@JsExport
@Serializable
class AnmpMeta(
    @CborLabel(0) val name: String,
    @CborLabel(1) val nameTs: Long,
    @CborLabel(2) val nameBy: ByteArray,
    @CborLabel(3) val image: AnmpImage? = null,
    @CborLabel(4) val imageTs: Long,
    @CborLabel(5) val imageBy: ByteArray,
    @CborLabel(6) val places: List<AnmpCirclePlace>? = null,
    @CborLabel(7) val placesTs: Long? = null,
    @CborLabel(8) val placesBy: ByteArray? = null,
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
