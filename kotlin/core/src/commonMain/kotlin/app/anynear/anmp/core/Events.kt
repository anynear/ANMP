package app.anynear.anmp.core

import app.anynear.anmp.AnmpAttachment
import app.anynear.anmp.AnmpLink
import kotlin.js.JsExport

/**
 * What the core reports to the app ([AnmpCore.events]). The app keeps messages, statuses, notifications and
 * texts; the core reports facts. IDs are b64u: device IDs 22 characters, message IDs ([mid]) 11, tags 11.
 */
@JsExport
sealed class CoreEvent {
    /**
     * A circle or direct message. [objectId]: ANDP object ID of the entry (or `history:…`, `radio:…`), unique per
     * delivery; duplicates across paths are recognized by ([fromDevice], [mid]) (ANMP "Receipt"). [opened] is the
     * opened, signed packet of a circle message, to hand on to new members (ADR 0014). [forwarded]: a radio packet
     * someone put into our personal mailbox (ADR 0022). [history]: from the history of a newly joined circle.
     */
    class Message(
        val conversation: Conversation,
        val objectId: String,
        /** Space the message (and its attachments) lies in, for [AnmpCore.fetchAttachment]. */
        val space: String,
        val mid: String?,
        val fromDevice: String,
        val fromName: String,
        val fromNode: Long,
        val text: String,
        val timestampMillis: Long,
        val attachments: List<AnmpAttachment> = emptyList(),
        /** Plaintext hash of the sender's own picture and its time (ms), circle picture hash (ADR 0014). */
        val imageHash: String? = null,
        val imageTs: Long = 0,
        val circleImageHash: String? = null,
        val opened: ByteArray? = null,
        val forwarded: Boolean = false,
        val history: Boolean = false,
        /** Person of [fromDevice] (ADR 0042); equal to [IdentityInfo.personId] for a message from an own other device. */
        val fromPerson: String = fromDevice,
        /** Preview of the first web link, made by the sender (ADR 0069); its [AnmpLink.url] appears in [text]. */
        val link: AnmpLink? = null,
        /** Answer to the message with this ID of the installation with tag [replyFrom] (ADR 0092); both b64u or both `null`. */
        val replyTo: String? = null,
        val replyFrom: String? = null,
        /** The sender passed on a message it got („Weitergeleitet“, content field 38, ADR 0118); no origin. */
        val passedOn: Boolean = false,
        /** A meeting point, „Wo bist du?“ or the answer to it (ADR 0121 §8). */
        val spot: MessageSpot? = null,
        /** A contact introduced to us in this message (ADR 0116); [text] is only its stand-in for older apps. */
        val intro: Intro? = null,
        /** From a person that asks to become a contact and is not yet one (ADR 0116 §7): no notification, shown with the request. */
        val fromRequest: Boolean = false,
    ) : CoreEvent()

    /**
     * Something changed in [AnmpCore.contactRequests] or [AnmpCore.sentContactRequests] (ADR 0116). [ended]: a request of
     * ours that ended at its time without an answer (person ID), so the app can say that it was not answered.
     */
    class ContactRequestsChanged(val ended: String? = null) : CoreEvent()

    /**
     * The person [toPerson] wants the contact [person] (named [name]) we introduced (ADR 0116 §3); the request is passed
     * on to [person]. Whether it agrees, we do not learn.
     */
    class IntroAccepted(val introId: String, val toPerson: String, val person: String, val name: String) : CoreEvent()

    /**
     * Reaction [emoji] (`""` = withdrawn) by [fromDevice] on message [targetMid] of the installation with tag [targetTag]
     * (ADR 0018). [objectId] only from [AnmpCore.wakeFetch], to match the `o` of the wake-up.
     */
    class Reaction(
        val conversation: Conversation,
        val fromDevice: String,
        val targetTag: String,
        val targetMid: String,
        val emoji: String,
        val timestampMillis: Long,
        val objectId: String = "",
    ) : CoreEvent()

    /**
     * The author withdrew the message [targetMid] of the installation with tag [targetTag] for everyone (ADR 0068).
     * The core checked that [fromDevice] belongs to the same person as the author. Can arrive before the message.
     */
    class Deleted(
        val conversation: Conversation,
        val fromDevice: String,
        val targetTag: String,
        val targetMid: String,
        val timestampMillis: Long,
    ) : CoreEvent()

    /**
     * [fromDevice] received ([read] = `false`) or read our messages [mids] (ADR 0003, 0019), over radio if [viaRadio].
     * [arrived]: on which paths they arrived there, bits of [app.anynear.anmp.AnmpArrived]; 0 if the receipt does not say
     * (older apps, ADR 0058).
     */
    class Receipt(val fromDevice: String, val mids: List<String>, val read: Boolean, val viaRadio: Boolean = false, val arrived: Int = 0) : CoreEvent()

    /**
     * A sign of life changed (ADR 0083 §5): new packet, answer of a helper, receipt for an own one, or ended. Read it
     * with [AnmpCore.aliveStatus]; [AnmpCore.aliveNextAt] says when to look again without news.
     */
    class AliveChanged(val planId: String, val circleId: String) : CoreEvent()

    /** What [person] shares of its place with us changed (ADR 0121 §3); read it with [AnmpCore.places]. */
    class PlaceChanged(val person: String) : CoreEvent()

    /** The places of the circle changed (ADR 0121 §21); read them with [AnmpCore.circlePlaces]. */
    class CirclePlacesChanged(val circleId: String) : CoreEvent()

    /** A call for the helpers changed (ADR 0083 §2): raised, updated, answered or ended; read it with [AnmpCore.sosCalls]. */
    class SosChanged(val id: String, val owner: String) : CoreEvent()

    /** [person] asks us to be its contact for calls (ADR 0121 §25); read the open ones with [AnmpCore.sosAsks]. */
    class SosAsked(val person: String, val circleId: String?) : CoreEvent()

    /** Someone we asked answered, or we answered someone: [AnmpCore.sosContacts], [AnmpCore.sosAgreed]. */
    class SosContactsChanged(val person: String) : CoreEvent()

    /**
     * A call for the helpers, found by the iOS notification extension ([AnmpCore.wakeFetch], ADR 0117): raised by hand,
     * or from a sign of life nobody confirmed ([plan], ADR 0083 §5.4). Only reported, not stored. Only calls that count:
     * running, not confirmed since, not for a helper who said it cannot look. [id] is the call, [circleId] the circle
     * it came through (`null`: directly).
     */
    class SosWake(
        val objectId: String,
        val id: String,
        val circleId: String?,
        val owner: String,
        val plan: String?,
        val timestampMillis: Long,
    ) : CoreEvent()

    /** [person] asks to be shown where we are (ADR 0121 §4.4). Nothing is answered by itself. */
    class PlaceAsked(val person: String, val timestampMillis: Long) : CoreEvent()

    /**
     * A sign of life of another person, found by the iOS notification extension ([AnmpCore.wakeFetch], ADR 0117). Only
     * reported, not stored: the app takes the packet over when it fetches itself. [owner] is the person of the plan,
     * [timestampMillis] the time of the packet, [objectId] the ANDP object of the wake-up. [call]: the ID of the call
     * for the helpers this packet's alarm is ([AnmpCore.sosCalls], ADR 0083 §5.4), for a packet that says nobody
     * confirmed and for an alarm the relays released; otherwise `null`.
     */
    class AliveWake(
        val objectId: String,
        val circleId: String,
        val owner: String,
        val alive: app.anynear.anmp.AnmpAlive,
        val timestampMillis: Long,
        val call: String? = null,
    ) : CoreEvent()

    /**
     * A circle or direct message over radio (ADR 0021, 0051, 0057), heard or from the radio inbox ([relayed]).
     * [fromNode] is the sender's address in the radio network (0 from the radio inbox); [packetKey] tells packets heard
     * twice apart. Duplicates across paths are recognized by ([fromDevice], [mid]) as for [Message].
     */
    /**
     * State of an own message [mid] queued for the radio ([AnmpCore.queueRadioText]); [packetId] as the radio network
     * names the packet (0 if unknown).
     */
    class RadioSend(val mid: String, val packetId: Int, val state: RadioSendState) : CoreEvent()

    class RadioMessage(
        val conversation: Conversation,
        val mid: String,
        val fromDevice: String,
        val fromName: String,
        val fromNode: Int,
        val packetKey: Int,
        val text: String,
        val timestampMillis: Long,
        val relayed: Boolean,
        /** As in [Message] (ADR 0092); over radio only if it fitted next to the text. */
        val replyTo: String? = null,
        val replyFrom: String? = null,
        /** As in [Message] (ADR 0121 §8); over radio only if it fitted. */
        val spot: MessageSpot? = null,
    ) : CoreEvent()

    /** An own other device read [mids] in [conversation] (ADR 0042): mark them read here, too. */
    class ReadElsewhere(val conversation: Conversation, val mids: List<String>) : CoreEvent()

    /**
     * The devices of a person changed (ADR 0042): [added] and [removed] device IDs with names of [added]. For the own
     * person ([own]) the app asks "Warst du das?" about devices it did not add itself.
     */
    class DevicesChanged(val personId: String, val own: Boolean, val added: List<DeviceInfo>, val removed: List<String>) : CoreEvent()

    /** Data an own other device sent with [AnmpCore.sendOwnData] ([topic] as given there, [ts] ms). */
    class OwnData(val topic: String, val data: ByteArray, val fromDevice: String, val ts: Long) : CoreEvent()

    /**
     * Another own device changed the profile (ADR 0050): the new [name] (`null`: unchanged), the new picture if
     * [imageChanged] ([image] `null` = generated one) and the [settings] that changed.
     */
    class ProfileChanged(val name: String?, val imageChanged: Boolean, val image: ByteArray?, val settings: Map<String, ByteArray>) : CoreEvent()

    /** This device was removed from the own device list: it stops sending (ADR 0042 §6). */
    data object Revoked : CoreEvent()

    /** Two different device lists of [personId] with the same number were seen (ADR 0042, risks). */
    class PersonConflict(val personId: String) : CoreEvent()

    /** An entry of ours is stored at [relays] relays (receipts, ANDP §5.3). [ref]: message ID or object ID. */
    class Stored(val ref: String, val relays: Int) : CoreEvent()

    /** No relay took the write in time or every relay refused it. */
    class SendFailed(val ref: String) : CoreEvent()

    /**
     * Every relay refused a direct message to [personId] for lack of a right: most likely the person deleted everything
     * (ADR 0046) and took its grants back. Ends with [ContactReachable] once something of the person arrives again.
     */
    class ContactUnreachable(val personId: String, val since: Long) : CoreEvent()

    /** [personId], marked by [ContactUnreachable], wrote us again or was removed. */
    class ContactReachable(val personId: String) : CoreEvent()

    /** A member or contact appeared or changed ([member].circleId = [MemberState.CONTACTS] for contacts). */
    class MemberUpdated(val member: MemberState, val isNew: Boolean) : CoreEvent()

    /** A member left the circle. */
    class MemberLeft(val circleId: String, val deviceId: String, val name: String) : CoreEvent()

    /** Circle state changed (name, picture, key, removed). */
    class CircleUpdated(val circle: CircleState) : CoreEvent()

    /** Circle deleted from this device (left, or removed and the grace period is over). */
    class CircleGone(val circleId: String) : CoreEvent()

    /** [byDevice] renamed the circle to [name]; only for changes after we joined. */
    class CircleRenamed(val circleId: String, val byDevice: String, val name: String) : CoreEvent()

    /** [byDevice] changed ([removed] = `false`) or removed the circle picture. */
    class CircleImageChanged(val circleId: String, val byDevice: String, val removed: Boolean) : CoreEvent()

    /**
     * Key change in a circle (ADR 0012). [kind]: `switched` (new key; [removedDevice] removed, or [why] `reset`),
     * `removed_you`, `readmitted`, or for our own change `you_removed` / `you_reset`.
     */
    class KeyChanged(
        val circleId: String,
        val kind: String,
        val byDevice: String,
        val byName: String,
        val removedDevice: String?,
        val removedName: String?,
        val why: String?,
    ) : CoreEvent()

    /** The history of a joined circle is complete; its messages came as [Message] with `history = true`. */
    class HistoryLoaded(val circleId: String, val count: Int) : CoreEvent()

    /** A contact changed its picture (personal mailbox, kind `profile`). */
    class Profile(val deviceId: String, val image: ImageRef?, val imageTs: Long) : CoreEvent()

    /**
     * A new alert in a channel (ADR 0062), checked and not seen before: for notifications. [AnmpCore.alerts] holds it
     * too; from [AnmpCore.wakeFetch] it is not stored yet and may come again from the app's own fetch (same [AlertItem.id]).
     * [history]: the alert is older than the channel on this device (just subscribed, or come with the snapshot of an own
     * device or the emergency card): show it, do not notify. [quiet]: it only updates an open incident with the same or
     * a lower priority (ADR 0062 §4): show it, do not notify either.
     */
    class Alert(val item: AlertItem, val history: Boolean = false, val quiet: Boolean = false) : CoreEvent()

    /** Someone answered our `want`: the blob with plaintext [hash] is under [ref] (ADR 0014). */
    class Have(val hash: String, val ref: ImageRef) : CoreEvent()

    companion object {
        const val KIND_SWITCHED = "switched"
        const val KIND_REMOVED_YOU = "removed_you"
        const val KIND_READMITTED = "readmitted"
        const val KIND_YOU_REMOVED = "you_removed"
        const val KIND_YOU_RESET = "you_reset"
    }
}

/**
 * Result of [AnmpCore.wakeFetchResult]: what a wake-up found to show, and whether every space answered at at least one
 * holder and every new entry could be read ([complete]). Only a complete empty result means "nothing to show".
 */
class WakeFetch(val events: List<CoreEvent>, val complete: Boolean)
