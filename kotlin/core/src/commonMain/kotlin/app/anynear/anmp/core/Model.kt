package app.anynear.anmp.core

import kotlinx.serialization.Serializable
import kotlin.js.JsExport

/**
 * A conversation: a circle chat or a direct chat with one person (ADR 0042). [Direct.personId] is the person ID
 * (b64u, 16 bytes); for a partner whose app sends no key chain yet it is its device ID (same form).
 */
@JsExport
sealed class Conversation {
    data class Circle(val circleId: String) : Conversation()

    data class Direct(val personId: String) : Conversation()
}

/**
 * A blob reference (picture, or an attachment found through `have`): object [id] and blob [key] (b64u, 32
 * bytes each), [space] that holds it, [hash] of the plaintext (16 bytes, b64u). Same as ANMP `image`.
 */
@JsExport
@Serializable
data class ImageRef(val id: String, val key: String, val space: String, val hash: String? = null)

/**
 * A circle (ADR 0004, 0012). Same fields as the app's `Circle`. Over radio all circles share the one channel
 * (ADR 0051).
 */
@JsExport
@Serializable
data class CircleState(
    /** Stays the same across key changes; in the invitation link. */
    val id: String,
    val name: String,
    /** Circle secret, 32 bytes b64u. */
    val secret: String,
    /** Someone removed us: history stays readable, writing is no longer possible. */
    val removed: Boolean = false,
    /** Key epoch (ADR 0012): number of the change that created [secret], 0 = since founding. */
    val epoch: Int = 0,
    val epochTs: Long = 0,
    val epochBy: String = "",
    /** Device removed by this change; no counter change from it is accepted. */
    val epochRemoved: String? = null,
    /** Secret before the last change; its space is read until [graceUntil]. */
    val previousSecret: String? = null,
    val graceUntil: Long = 0,
    val removedEpoch: Int = 0,
    val removedTs: Long = 0,
    val removedBy: String = "",
    val nameTs: Long = 0,
    val nameBy: String = "",
    val image: ImageRef? = null,
    val imageTs: Long = 0,
    val imageBy: String = "",
    /** The places of the circle (ADR 0121 §21), with time (ms) and device of the last change of the list. */
    val places: List<CirclePlace> = emptyList(),
    val placesTs: Long = 0,
    val placesBy: String = "",
    val joinedAt: Long = 0,
    /** Joined by invitation, history not yet complete (ADR 0014). */
    val historyPending: Boolean = false,
) {
    override fun toString(): String = "CircleState($id, $name, ***)"

    companion object {
        /** Newer change by time, ties by device ID. */
        fun isNewer(ts: Long, by: String, thanTs: Long, thanBy: String): Boolean = ts > thanTs || (ts == thanTs && by > thanBy)

        /** Of two simultaneous key changes the earlier wins (ties: smaller device ID). */
        fun isEarlier(ts: Long, by: String, thanTs: Long, thanBy: String): Boolean = ts < thanTs || (ts == thanTs && by < thanBy)
    }
}

/**
 * A member of a circle or a contact ([circleId] = [CONTACTS]). [publicKey] is the X25519 key (pinned: the
 * first seen key of a device wins), [signKey] the Ed25519 key, [inbox] the space of its personal mailbox.
 * Whether we may write there depends on a grant from it ([AnmpCore.canWriteTo], ADR 0035 §2).
 */
@JsExport
@Serializable
data class MemberState(
    val circleId: String,
    val deviceId: String,
    val name: String,
    val publicKey: String,
    val signKey: String? = null,
    val node: Long = 0,
    val image: ImageRef? = null,
    val imageTs: Long = 0,
    val inbox: String? = null,
    /** Person of this device (ADR 0042); `null` while its app sends no key chain, then the device is the person. */
    val personId: String? = null,
    /** X25519 key of the person's mailbox recipient from the contact link (ADR 0084), b64u; `null` if unknown. */
    val mailboxKey: String? = null,
    /**
     * When the person became a contact (ms), for contacts ([CONTACTS]) only: the app sorts a contact without messages
     * by it. Every device of a person keeps the time of its first one; 0 = unknown (added before this was kept).
     */
    val addedAt: Long = 0,
) {
    /** Person ID, or the device ID for a device without key chain. */
    val person: String get() = personId ?: deviceId

    companion object {
        /** Pseudo circle of direct contacts. */
        const val CONTACTS = "~contacts"
    }
}

/** A grant this installation issued for its personal mailbox (or a circle). [purpose]: `contact:<device>`, `circle:<id>:<epoch>`, `link`, `history:<id>`. */
@Serializable
internal data class IssuedGrant(val purpose: String, val grant: String, val hash: String, val until: Long, val revoked: Boolean = false)

/**
 * A grant someone gave us for its personal mailbox [inbox]: from a contact link (`*`), a member announcement
 * (`*` for the circle, [circleId]) or personally ([personal], grantee = our key). [until], [issued] in seconds.
 */
@Serializable
internal data class PeerGrant(
    val deviceId: String,
    val inbox: String,
    val grant: String,
    val until: Long,
    val issued: Long,
    val personal: Boolean,
    val circleId: String? = null,
    /** From the contact's own profile (its contact grant): preferred for direct writes over circle, channel or link grants. */
    val contact: Boolean = false,
)

/** Own profile, sent with announcements: name, radio node, own picture ([imageHash], [imageTs] ms). */
@Serializable
internal data class OwnProfile(
    val name: String = "",
    val node: Long = 0,
    val imageHash: String? = null,
    val imageTs: Long = 0,
    /** When the name last changed on any own device (ms, ADR 0050); 0 = before the profile space. */
    val nameTs: Long = 0,
    /** Settings of the app that apply to the person (ADR 0050), opaque to the core. */
    val settings: List<SharedSetting> = emptyList(),
    /** [imageTs] of the picture last sent to the contacts without a common circle; older there = send again at start. */
    val imageSentTs: Long = 0,
)

/** One setting of the person: [value] b64u as the app gave it, [ts] when it last changed (ms). */
@Serializable
internal data class SharedSetting(val name: String, val value: String, val ts: Long)

/** Uploaded copy of the own or a circle picture in one space; [sealed] names the blob in the [BlobStore]. */
@Serializable
internal data class UploadedImage(val hash: String, val space: String, val ref: ImageRef)

/**
 * Key chain of another person as last accepted (ADR 0042): [proof] b64u, its [n] and [seq], every device ID ever
 * revoked (union of all proofs), [conflict] when two different lists with the same [n] and [seq] were seen.
 */
@Serializable
internal data class PersonState(
    val personId: String,
    val proof: String,
    val n: Long,
    val seq: Long,
    val revoked: List<String> = emptyList(),
    val conflict: Boolean = false,
)

/** Something the own devices no longer have: a left circle or a removed contact person, with time (ms). */
@Serializable
internal data class Tombstone(val id: String, val ts: Long)

/**
 * Circle store still to be emptied on the relays (ADR 0049 §5): the last member left, with the circle [secret] and when
 * (ms). Kept until every file is deleted or [AnmpCore] gives up after a while.
 */
@Serializable
internal data class StoreCleanup(val secret: String, val since: Long)

/** Persisted state of the core, in the [SecretStore] (contains circle secrets and grants). */
@Serializable
internal data class CoreState(
    val circles: List<CircleState> = emptyList(),
    val members: List<MemberState> = emptyList(),
    val issued: List<IssuedGrant> = emptyList(),
    val grants: List<PeerGrant> = emptyList(),
    val profile: OwnProfile = OwnProfile(),
    /** Settings of the app that apply to this device only (ADR 0106), opaque to the core; kept in the device space. */
    val deviceSettings: List<SharedSetting> = emptyList(),
    val uploads: List<UploadedImage> = emptyList(),
    /** Circles for which a history index came: do not ask again. */
    val historyOffered: List<String> = emptyList(),
    /** Key chains of other persons (ADR 0042). */
    val persons: List<PersonState> = emptyList(),
    /** Own key chain, b64u of [app.anynear.anmp.AnmpPersons.proof]. */
    val ownProof: String? = null,
    /** Left circles and removed contacts, so that a snapshot of an own device does not bring them back. */
    val tombstones: List<Tombstone> = emptyList(),
    /** When the emergency card was confirmed (ms); 0 = no card yet, the root key is still on this device. */
    val cardTs: Long = 0,
    /** Removed by an own device with a new person key, whose chain this device does not keep (ADR 0042 §6). */
    val revoked: Boolean = false,
    /** Own and subscribed channels (ADR 0062). */
    val channels: List<ChannelState> = emptyList(),
    /** Circle stores of circles we left as the last member, still to be emptied on the relays. */
    val storeCleanups: List<StoreCleanup> = emptyList(),
    /**
     * Store secret before the last person key change (ADR 0042 §6), b64u: its store, password and profile spaces are
     * moved into those of the new secret and emptied; dropped once that is done or after [previousStoreSince] + 30 days.
     */
    val previousStore: String? = null,
    val previousStoreSince: Long = 0,
    /** The own mailbox was moved once to the form to the person (ADR 0107 §6): old per-device entries re-written. */
    val mailboxMigrated: Boolean = false,
    /** The app's own direct history was written as own copies once (ADR 0107 §6, [AnmpCore.keepOwnHistory]). */
    val historyKept: Boolean = false,
    /** The own mailbox was read through once on this device (ADR 0107 §8). */
    val mailboxRead: Boolean = false,
    /** Circles whose space was read through once on this device (ADR 0107 §8). */
    val circlesRead: List<String> = emptyList(),
    /** Own signs of life and those we watch (ADR 0083 §5). */
    val alive: List<AlivePlan> = emptyList(),
    /** What other persons share of their place with us, one entry per person (ADR 0121 §3). */
    val places: List<PersonPlace> = emptyList(),
    /** Per person what we last told it of level and end (`level/until`), so that radio carries a change at once (ADR 0121 §13). */
    val placesSent: Map<String, String> = emptyMap(),
    /** Calls for the helpers: the own one and those of persons we help (kind sos, ADR 0083 §2). */
    val sos: List<SosCall> = emptyList(),
    /** Contacts for calls (ADR 0121 §25): whom we asked and what they said; who asked us; whose calls we agreed to. */
    val sosContacts: List<SosContact> = emptyList(),
    val sosAsks: List<SosAsk> = emptyList(),
    val sosAgreed: List<String> = emptyList(),
    /** Whether calls of the persons we said yes to carry their precise place, as each said last (ADR 0125 §8). */
    val sosReady: List<SosReady> = emptyList(),
    /** Whether the own calls carry the precise place, as this device knows it, and what each contact was told. */
    val sosPreciseOwn: Boolean? = null,
    val sosPreciseTold: Map<String, Boolean> = emptyMap(),
    /** „Standort freigeben“ (ADR 0123 §2), one entry per person, from the cards in the direct messages. */
    val sharings: List<PlaceSharing> = emptyList(),
    /** Persons whose mailbox refuses us (every holder 403), with since when (ms): person ID → time (ADR 0046). */
    val unreachable: Map<String, Long> = emptyMap(),
    /** Persons asking to become a contact (ADR 0116), not yet answered. */
    val requests: List<ContactRequest> = emptyList(),
    /** Persons we asked to become a contact (contact link or introduction, ADR 0116), until they agree or [SentRequest.until]. */
    val sentRequests: List<SentRequest> = emptyList(),
)

/**
 * A person that asks to become a contact (ADR 0116): through our contact link, or introduced by the contact
 * [introducedBy] (person ID, named [introducedByName]). Nothing is added and no grant issued before [AnmpCore.acceptContactRequest].
 * [person]: person ID, or the device ID while its app sends no key chain. [ts], [until] in ms.
 */
@JsExport
@Serializable
data class ContactRequest(
    val person: String,
    val deviceId: String,
    val name: String,
    val publicKey: String,
    val signKey: String? = null,
    val inbox: String? = null,
    /** Write grant for [inbox] (b64u of CBOR `[body, sig]`) from its packet or contact link. */
    val grant: String? = null,
    val mailboxKey: String? = null,
    val node: Long = 0,
    val image: ImageRef? = null,
    val imageTs: Long = 0,
    val introducedBy: String? = null,
    val introducedByName: String? = null,
    /** ID of the introduction (b64u), so that a forwarded request is taken once. */
    val introId: String? = null,
    val ts: Long = 0,
    val until: Long = 0,
)

/**
 * A request of ours (ADR 0116): we added [name] through its link (then [deviceId] is its device) or accepted an
 * introduction of it by [introducedBy]. Ends when something of the person arrives, or silently at [until] (ms).
 */
@JsExport
@Serializable
data class SentRequest(
    val person: String,
    val name: String,
    val deviceId: String? = null,
    val introducedBy: String? = null,
    val ts: Long = 0,
    val until: Long = 0,
)

/** A device of a person: device ID, name, time added (ms), radio node (ADR 0042). */
@JsExport
@Serializable
data class DeviceInfo(
    val deviceId: String,
    val name: String,
    val added: Long = 0,
    val node: Long = 0,
    /** The device moved its old mailbox entries and history to the person (ADR 0107 §6). */
    val moved: Boolean = false,
)

/**
 * What a device has not read through once yet (ADR 0107 §8): its own mailbox ([mailbox], direct conversations) and
 * the circles [circles] (IDs). The app shows that messages are still loading.
 */
data class HistoryLoading(val mailbox: Boolean, val circles: List<String>) {
    val any: Boolean get() = mailbox || circles.isNotEmpty()
}

/**
 * One direct message of the app's own history, for [AnmpCore.keepOwnHistory] (ADR 0107 §6): in the conversation with
 * the person [peerPerson] (person ID, or a device ID of it), [author] the sender's device for a received one, `null` for one the own person sent.
 */
class OwnHistoryItem(
    val peerPerson: String,
    val mid: String,
    val tsMillis: Long,
    val text: String,
    val author: String?,
    val attachments: List<app.anynear.anmp.AnmpAttachment> = emptyList(),
    val replyTo: String? = null,
    val replyFrom: String? = null,
)

/**
 * An own device with settings in the device space (ADR 0106): [deviceId] b64u, [changed] when it last wrote them (ms),
 * [names] of the settings stored for it.
 */
data class StoredDevice(val deviceId: String, val changed: Long, val names: List<String>)

/** Result of adding an own device by its pairing link (ADR 0042 §5). */
@JsExport
sealed class PairResult {
    data class Added(val device: DeviceInfo) : PairResult()

    /** The device is already in the list. */
    data class AlreadyAdded(val device: DeviceInfo) : PairResult()

    /** Removed once; it never comes back. */
    data object Revoked : PairResult()

    /** At most 16 devices. */
    data object Full : PairResult()

    data object ForeignNetwork : PairResult()

    data object Invalid : PairResult()
}

/**
 * The password vault from the relays: [data] as the app put it (already sealed with its own key), [changed] ms, [key]
 * the app's key for it, if a device put one there.
 */
@JsExport
sealed class VaultResult {
    class Found(val data: ByteArray, val changed: Long, val key: ByteArray? = null) : VaultResult()

    /** The relays answered: no vault yet. */
    data object Empty : VaultResult()

    /** No relay reachable now. */
    data object Later : VaultResult()
}

/** Result of recovering with the code of the emergency card (ADR 0042 §6). */
@JsExport
sealed class RecoverResult {
    /** Keys are in place: restart the core. [devices]: the person's devices before, to lock lost ones; [extras] of the app. */
    class Recovered(val devices: List<DeviceInfo>, val extras: Map<String, ByteArray>) : RecoverResult()

    /** Not a valid code (typo, check character). */
    data object Invalid : RecoverResult()

    /** The relays answered, but there is nothing for this code. */
    data object NotFound : RecoverResult()

    /** No relay reachable now. */
    data object Later : RecoverResult()
}

/** Result of [AnmpCore.eraseEverything] (ADR 0046). */
@JsExport
sealed class EraseResult {
    /** Everything is deleted or handed to the relays. */
    data object Done : EraseResult()

    /** Started, but some writes (leaving, the removed devices' notice) were still open when waiting ended. */
    data object Partly : EraseResult()

    /** No relay answered: nothing was changed. */
    data object Offline : EraseResult()
}

/** Result of joining by an invitation link. */
@JsExport
sealed class JoinResult {
    data class Joined(val circle: CircleState) : JoinResult()

    data class AlreadyMember(val circle: CircleState) : JoinResult()

    /** The link belongs to another network (ADR 0035 §6). */
    data object ForeignNetwork : JoinResult()

    data object Invalid : JoinResult()
}

/** Result of adding a contact by a contact link. */
@JsExport
sealed class AddContactResult {
    data class Added(val contact: MemberState) : AddContactResult()

    data class AlreadyKnown(val contact: MemberState) : AddContactResult()

    /** An introduction was accepted (ADR 0116): the introduced person [name] still has to agree. */
    data class Requested(val name: String) : AddContactResult()

    /** Same device ID, other key: not taken over silently. */
    data class KeyMismatch(val name: String) : AddContactResult()

    data object Own : AddContactResult()

    data object ForeignNetwork : AddContactResult()

    data object Invalid : AddContactResult()
}

/** An outgoing message: its ANMP message ID (b64u, 8 bytes) and, for circle messages, the opened packet for the history. */
@JsExport
class Outgoing(val mid: String, val opened: ByteArray?)

/** Result of loading a blob. */
@JsExport
sealed class BlobResult {
    class Found(val plaintext: ByteArray) : BlobResult()

    /** Deleted, expired or nowhere (every asked relay answered). */
    data object Gone : BlobResult()

    /** Not available now (no connection, relays still repairing): try again later. */
    data object Later : BlobResult()
}

/**
 * State of the relays for "Verbindungen": [known] node keys of the active list, [reachable] answered last time,
 * [sites] the site of each relay in [known] (same order, `""` if the list names none), for display.
 */
@JsExport
data class RelayStatus(val known: List<String> = emptyList(), val reachable: List<String> = emptyList(), val sites: List<String> = emptyList())

/**
 * Counters of the stream to the home relay since the app started (ADR 0072 §6), for the log and the checks of the
 * testers: [drops] by reason (`network`, `ping_timeout`, `bye`, `closed`, `rejected_<status>`), [missed] catch-ups
 * that found something no notification had announced, [lastSeconds] how long the last streams lasted.
 */
@kotlinx.serialization.Serializable
data class StreamStats(
    val home: String = "",
    val connects: Int = 0,
    val drops: Map<String, Int> = emptyMap(),
    val homeSwitches: Int = 0,
    val catchUps: Int = 0,
    val missed: Int = 0,
    val lastSeconds: List<Long> = emptyList(),
)

/**
 * A contact introduced in a message (ADR 0116 §2): the person [person] named [name], introduced by [byPerson], valid
 * until [until] (ms). [card]: the signed card (b64u) to hand to [AnmpCore.acceptIntro]. [image]: its picture, in the
 * space of the message.
 */
@JsExport
@Serializable
data class Intro(val id: String, val person: String, val name: String, val byPerson: String, val until: Long, val card: String, val image: app.anynear.anmp.AnmpAttachment? = null)
