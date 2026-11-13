package app.anynear.anmp.core

import app.anynear.andp.B64
import app.anynear.andp.Grant
import app.anynear.andp.Key
import app.anynear.andp.Meta
import app.anynear.andp.Rights
import app.anynear.anmp.AnmpAlert
import app.anynear.anmp.AnmpAlertBody
import app.anynear.anmp.AnmpAlerts
import app.anynear.anmp.AnmpAttachment
import kotlinx.serialization.Serializable
import kotlin.js.JsExport

/**
 * A channel (ADR 0062): [id] is its space (b64u owner key). [owner]: we hold the owner key and may send, share the
 * link and create device addresses ([ingress]). [curated]: run by anynear (e.g. NINA). Keys stay in the core. Channels
 * reach every own device and the state space with the snapshot (ADR 0042 §4), like circles.
 */
@JsExport
@Serializable
data class ChannelState(
    val id: String,
    val name: String,
    val owner: Boolean,
    val curated: Boolean = false,
    val ingress: List<Ingress> = emptyList(),
    /** Private X25519 read key, b64u. */
    val readKey: String,
    /** Read grant for anyone (read, list), wire form. */
    val grant: String,
    /** Ed25519 seed of the owner key, b64u; only at the owner. */
    val ownerSeed: String? = null,
    /** When the person subscribed or created it (ms), on whichever own device; travels with the snapshot. */
    val joinedAt: Long = 0,
    /** Last rename and last read key (reset link, new link) in ms, so that the newer one wins between own devices. */
    internal val nameTs: Long = 0,
    internal val keyTs: Long = 0,
    /** IDs of revoked device addresses, so that a snapshot of an own device does not bring them back. */
    internal val revokedIngress: List<String> = emptyList(),
    /** When this device took it (ms); alerts older than that are history here ([CoreEvent.Alert.history]). */
    internal val addedHere: Long = 0,
    /** Own channels: subscribers that announced themselves (ADR 0062 §11), oldest first. */
    val subscribers: List<ChannelSubscriber> = emptyList(),
    /** Own channels: removed or left subscribers, so that a snapshot of an own device does not bring them back. */
    internal val unsubscribed: List<SubscriberGone> = emptyList(),
    /** Subscribed channels: the way to the owner from the link (ADR 0062 §11); `null` for curated and older links. */
    internal val join: ChannelJoin? = null,
    /** Subscribed channels: when this device last announced itself to the owner (ms), 0 = not yet. */
    internal val announced: Long = 0,
) {
    /** Subscribed channel whose owner sees this person in the list of subscribers (ADR 0062 §11). */
    val seenByOwner: Boolean get() = !owner && join != null

    override fun toString(): String = "ChannelState($id, $name, owner=$owner, curated=$curated, subscribers=${subscribers.size}, ***)"
}

/**
 * A subscriber of an own channel (ADR 0062 §11): [deviceId] (b64u) and [name] as it announced itself, [since] its first
 * and [ts] its last announcement (ms). Key, mailbox and grant let the owner give it a new read key.
 */
@JsExport
@Serializable
data class ChannelSubscriber(
    val deviceId: String,
    val name: String,
    val since: Long,
    val ts: Long,
    internal val publicKey: String,
    internal val inbox: String,
    /** Write grant for [inbox], wire form. */
    internal val grant: String,
) {
    override fun toString(): String = "ChannelSubscriber($deviceId, ***)"
}

/** Way to the owner of a subscribed channel (ADR 0062 §11): owner device, its X25519 key, mailbox space, write grant (CBOR); all b64u. */
@JsExport
@Serializable
data class ChannelJoin(internal val deviceId: String, internal val publicKey: String, internal val inbox: String, internal val grant: String) {
    override fun toString(): String = "ChannelJoin(***)"
}

/** A removed or left subscriber of an own channel: device ID and when (ms). */
@JsExport
@Serializable
data class SubscriberGone(internal val id: String, internal val ts: Long)

/**
 * A device address of an own channel (ADR 0062 §4). [id] is the hex public key of its sender key (as
 * [AlertItem.sender]), [url] the address to give to devices, [created] ms. [token], [grantHash] and [entryId] let
 * the owner show, revoke and rewrite it.
 */
@JsExport
@Serializable
data class Ingress(
    val id: String,
    val label: String,
    val url: String,
    val created: Long,
    val token: String,
    val grantHash: String,
    val entryId: String,
    /** Seed of the sender key (b64u) and its grant (wire form), so that the owner can write the record again with a new read key. */
    internal val senderSeed: String,
    internal val senderGrant: String,
) {
    override fun toString(): String = "Ingress($id, $label, ***)"
}

/** Decoded channel link (`https://anynear.app/a#…`). Whoever has it can read the channel, not send. */
@JsExport
class ChannelInvite(
    val networkId: String,
    /** Space of the channel. */
    val channelId: String,
    val name: String,
    /** Flag of the link; the core counts a channel as curated only if the configuration lists it. */
    val curated: Boolean,
    internal val readKey: ByteArray,
    internal val grant: Grant,
    internal val join: ChannelJoin? = null,
) {
    /** The owner will see who subscribes (ADR 0062 §11). */
    val seenByOwner: Boolean get() = join != null && !curated
}

/** Result of [AnmpCore.joinChannel]. */
@JsExport
sealed class ChannelJoinResult {
    data class Joined(val channel: ChannelState) : ChannelJoinResult()

    data class AlreadyJoined(val channel: ChannelState) : ChannelJoinResult()

    /** The link belongs to another network (ADR 0035 §6). */
    data object ForeignNetwork : ChannelJoinResult()

    data object Invalid : ChannelJoinResult()
}

/** Status of an alert, computed: lifted by an all clear ([ENDED]), past its `until` ([EXPIRED]), else [ACTIVE]. */
@JsExport
enum class AlertStatus { ACTIVE, ENDED, EXPIRED }

/**
 * An accepted alert (ADR 0062 §3): checked, without duplicates. IDs and [sender] are hex; times in Unix seconds
 * except [received] (ms). [until] is the end of validity (`ts` + 24 h if the sender gave none). [replacedBy]: hex
 * ID of a newer alert in the same channel that replaces this one. Attachments are loaded with
 * [AnmpCore.fetchAlertAttachment]. [key]: hex incident of the sender (ADR 0062 §4), `null` for a single alert.
 */
@JsExport
class AlertItem(
    val channelId: String,
    val id: String,
    val ts: Long,
    val prio: Int,
    val title: String?,
    val text: String,
    val url: String?,
    val until: Long,
    val replaces: String?,
    val ended: Boolean,
    val source: String?,
    val area: List<String>,
    val attachments: List<AnmpAttachment>,
    val sender: String,
    val status: AlertStatus,
    val replacedBy: String?,
    val received: Long,
    val key: String? = null,
) {
    override fun toString(): String = "AlertItem($channelId, $id, prio=$prio, $status)"
}

/** An accepted alert as the core keeps it (30 days): the body bytes as signed, decoded again when loaded. */
@Serializable
internal data class StoredAlert(
    val channelId: String,
    /** b64u body bytes. */
    val body: String,
    /** Hex Ed25519 public key of the signer. */
    val sender: String,
    /** ms. */
    val received: Long,
) {
    @kotlinx.serialization.Transient
    private var parsed: AnmpAlertBody? = null

    fun bodyOrNull(): AnmpAlertBody? = parsed ?: runCatching { AnmpAlerts.decodeBody(B64.decode(body)) }.getOrNull()?.also { parsed = it }

    val key: String get() = channelId + "/" + (bodyOrNull()?.id?.toHex() ?: body)
}

/** Rules of ADR 0062 §2 "Annehmen" and the status of §6, without network and state. */
internal object ChannelRules {
    /**
     * Rule 2: [signer] is the owner of [space], or the entry was written by [signer] with a grant the owner issued,
     * with `write`, grantee = signer, valid at the entry's time. The grant signature is checked here; relays checked
     * it, revocations and write time when they took the entry.
     */
    suspend fun maySend(space: Key, signer: ByteArray, meta: Meta): Boolean {
        if (signer.contentEquals(space.bytes)) return true
        val key = runCatching { Key(signer) }.getOrNull() ?: return false
        val g = meta.grant ?: return false
        val b = g.body
        if (meta.writer != key || b.space != space || !b.rights.has(Rights.WRITE) || b.grantee != key || b.scope != null) return false
        if (meta.ts < b.issued || meta.ts > b.until) return false
        return runCatching { g.verifySig() }.isSuccess
    }

    /** Rule 3: at most 10 minutes in the future, at most 30 days old. */
    fun fresh(ts: Long, nowS: Long): Boolean = ts <= nowS + AnmpAlerts.MAX_FUTURE_SECONDS && ts >= nowS - AnmpAlerts.KEEP_SECONDS

    /** Items of [stored], newest first, with the status at [nowMillis]. */
    fun items(stored: List<StoredAlert>, nowMillis: Long): List<AlertItem> {
        val bodies = stored.mapNotNull { s -> s.bodyOrNull()?.let { s to it } }
        val endedKeys = bodies.filter { (_, b) -> b.ended && b.replaces != null }.map { (s, b) -> s.channelId + "/" + b.replaces!!.toHex() }.toSet()
        val replacedBy = bodies.filter { (_, b) -> b.replaces != null }
            .groupBy { (s, b) -> s.channelId + "/" + b.replaces!!.toHex() }
            .mapValues { (_, l) -> l.maxBy { it.second.ts }.second.id.toHex() }
            .toMutableMap()
        val endedIncidents = mutableSetOf<String>()
        for (run in incidentRuns(bodies)) {
            val last = run.last()
            run.dropLast(1).forEach { (s, b) -> replacedBy[s.channelId + "/" + b.id.toHex()] = last.second.id.toHex() }
            if (last.second.ended) run.forEach { (s, b) -> endedIncidents += s.channelId + "/" + b.id.toHex() }
        }
        return bodies.map { (s, b) ->
            val key = s.channelId + "/" + b.id.toHex()
            val status = when {
                b.ended || key in endedKeys || key in endedIncidents -> AlertStatus.ENDED
                b.expires * 1000 <= nowMillis -> AlertStatus.EXPIRED
                else -> AlertStatus.ACTIVE
            }
            itemOf(s, b, status, replacedBy[key])
        }.sortedWith(compareByDescending<AlertItem> { it.ts }.thenByDescending { it.received })
    }

    /**
     * Incidents (ADR 0062 §4): alerts with a `key`, grouped by channel, signer and key, oldest first, and cut after
     * every `ended`: an alert after an all clear opens a new incident.
     */
    private fun incidentRuns(bodies: List<Pair<StoredAlert, AnmpAlertBody>>): List<List<Pair<StoredAlert, AnmpAlertBody>>> =
        bodies.filter { it.second.key != null }
            .groupBy { (s, b) -> Triple(s.channelId, s.sender, b.key!!.toHex()) }
            .values.flatMap { group ->
                val runs = mutableListOf(mutableListOf<Pair<StoredAlert, AnmpAlertBody>>())
                for (p in group.sortedWith(compareBy<Pair<StoredAlert, AnmpAlertBody>> { it.second.ts }.thenBy { it.first.received })) {
                    runs.last().add(p)
                    if (p.second.ended) runs.add(mutableListOf())
                }
                runs.filter { it.isNotEmpty() }
            }

    /**
     * `true` if [s] only updates an open incident (ADR 0062 §4): an earlier alert of the same channel, signer and key
     * exists, is not an all clear and has at least the same priority. Such an alert is shown but does not notify;
     * a new incident, a higher priority and an all clear do.
     */
    fun quiet(stored: List<StoredAlert>, s: StoredAlert): Boolean {
        val b = s.bodyOrNull() ?: return false
        val key = b.key ?: return false
        if (b.ended) return false
        val before = stored.asSequence()
            .filter { it.channelId == s.channelId && it.sender == s.sender && it.key != s.key }
            .mapNotNull { o -> o.bodyOrNull()?.takeIf { it.key?.contentEquals(key) == true && it.ts <= b.ts }?.let { o to it } }
            .maxWithOrNull(compareBy<Pair<StoredAlert, AnmpAlertBody>> { it.second.ts }.thenBy { it.first.received })
            ?: return false
        return !before.second.ended && before.second.prio >= b.prio
    }

    fun itemOf(s: StoredAlert, b: AnmpAlertBody, status: AlertStatus, replacedBy: String?) = AlertItem(
        s.channelId, b.id.toHex(), b.ts, b.prio, b.title, b.text, b.url, b.expires, b.replaces?.toHex(), b.ended, b.source,
        b.area.orEmpty(), b.attachments.orEmpty(), s.sender, status, replacedBy, s.received, b.key?.toHex(),
    )

    fun storedOf(channelId: String, a: AnmpAlert, receivedMillis: Long) = StoredAlert(channelId, B64.encode(a.bodyBytes), a.signer.toHex(), receivedMillis)
}

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** `null` unless [this] is lower- or upper-case hex of whole bytes. */
internal fun String.hexOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    return ByteArray(length / 2) { i ->
        val hi = this[2 * i].digitToIntOrNull(16) ?: return null
        val lo = this[2 * i + 1].digitToIntOrNull(16) ?: return null
        ((hi shl 4) or lo).toByte()
    }
}
