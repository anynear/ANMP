package app.anynear.anmp.core

import app.anynear.andp.B64
import app.anynear.anmp.AnmpPlace
import app.anynear.anmp.AnmpSos
import app.anynear.anmp.AnmpSpot
import kotlinx.serialization.Serializable
import kotlin.js.JsExport

/**
 * What another person shares of its place with us (kind position, ADR 0121 §3, §4), as last heard. [level] is what we
 * may expect ([AnmpPlace.OFF] … [AnmpPlace.EXACT]). [position] is where it is: with [radius] (metres) the centre of an
 * area it is somewhere in, without it the point; `null`: nothing to show now. [until] (ms, 0 = until changed) is when
 * the level ends; [at] the sender time of the packet (ms).
 */
@JsExport
@Serializable
data class PersonPlace(
    val person: String,
    val level: Int,
    val position: AlivePosition? = null,
    val radius: Int? = null,
    val until: Long = 0,
    val at: Long = 0,
    /** The name the person gave the place it is at (e.g. „Zu Hause“), and since when (ms); `null` and 0 without one. */
    val label: String? = null,
    val since: Long = 0,
) {
    /** `true` once [until] has passed at [now]: the app shows nothing of it any more. */
    fun over(now: Long): Boolean = until in 1..now
}

/**
 * A place of a circle as the apps use it (ADR 0121 §21): „Zu Hause“, „Garten“. Every member knows it; a reader's app
 * names it when someone whose exact place it sees is there. [id] b64u.
 */
@JsExport
@Serializable
data class CirclePlace(val id: String, val name: String, val latitude: Double, val longitude: Double, val radius: Int = 150)

/**
 * What the own person shows one person of its place, as [AnmpCore.sharePlace] sends it: the level and, if there is
 * something to show, [position] (with [radius] the centre of an area), the end of the level and the name of a saved
 * place. Rides along with a direct message ([AnmpCore.sendDirect], [AnmpCore.queueRadioText]) instead of a packet of
 * its own (ADR 0121 §20). Times in ms.
 */
@JsExport
@Serializable
data class PlaceShare(
    val level: Int,
    val position: AlivePosition? = null,
    val radius: Int? = null,
    val untilMillis: Long = 0,
    val label: String? = null,
    val sinceMillis: Long = 0,
) {
    /** The wire form; `null` if the values are out of range. */
    internal fun toWire(): AnmpPlace? {
        if (position != null && !position.valid) return null
        val name = label?.trim()?.take(AnmpPlace.MAX_LABEL)?.takeIf { it.isNotEmpty() && position != null }
        return AnmpPlace(
            level, position?.toWire(), radius.takeIf { position != null }, untilMillis.takeIf { it > 0 }?.let { it / 1000 },
            label = name, since = sinceMillis.takeIf { it > 0 && name != null }?.let { it / 1000 },
        ).takeIf { it.plausible }
    }
}

/**
 * A place in a conversation as the apps use it (ADR 0121 §8): [kind] is [AnmpSpot.MEET], [AnmpSpot.ASK] or
 * [AnmpSpot.HERE]; [position] for a meeting point and an answer; [label] the name of a meeting point.
 */
@JsExport
@Serializable
data class MessageSpot(
    val kind: Int,
    val position: AlivePosition? = null,
    val label: String? = null,
    /** [AnmpSpot.ALL]: until when everyone who joins shares, ms. */
    val until: Long? = null,
) {
    internal fun toWire(): AnmpSpot = AnmpSpot(kind, position?.takeIf { it.valid }?.toWire(), label?.take(AnmpSpot.MAX_LABEL), until?.let { it / 1000 })

    companion object {
        internal fun of(s: AnmpSpot?): MessageSpot? =
            s?.takeIf { it.plausible }?.let { MessageSpot(it.kind, AlivePosition.of(it.position), it.label, it.until?.let { u -> u * 1000 }) }
    }
}

/**
 * A call for the helpers as the apps use it (kind sos, ADR 0083 §2–§4): the own one, or one of a person we help.
 * [circles] and [persons] are where the own call went; for someone else's [via] is the circle it came through
 * (`null`: it came to us directly). [caring]: person IDs of helpers who said they look after it. Times in ms.
 *
 * [plan]: the call comes from this sign of life, because nobody confirmed it (ADR 0083 §5.4); [reason] then says why,
 * as in [AliveStatus]: [AliveStatus.REASON_MISSED] the phone asked in vain, [AliveStatus.REASON_UNREACHABLE] nothing
 * came from the phone any more. A call raised by hand has neither.
 */
@JsExport
@Serializable
data class SosCall(
    val id: String,
    val owner: String,
    val own: Boolean,
    val start: Long,
    val lastAt: Long,
    val ended: Boolean = false,
    val position: AlivePosition? = null,
    val battery: Int? = null,
    val trail: List<AlivePosition> = emptyList(),
    val caring: List<String> = emptyList(),
    val circles: List<String> = emptyList(),
    val persons: List<String> = emptyList(),
    val via: String? = null,
    /** Own call: person IDs whose device confirmed that it got it (ADR 0003). */
    val arrived: List<String> = emptyList(),
    /** Own call: the IDs of the packets sent for it, to match the receipts. */
    internal val sentMids: List<String> = emptyList(),
    val plan: String? = null,
    val reason: Int = 0,
) {
    internal fun toWire(state: Int): AnmpSos = AnmpSos(
        B64.decode(id), state, position?.takeIf { it.valid && state == AnmpSos.RAISED }?.toWire(), battery.takeIf { state == AnmpSos.RAISED },
        trail.takeIf { state == AnmpSos.RAISED }?.filter { it.valid }?.takeLast(AnmpSos.MAX_TRAIL)?.map { it.toWire() }?.takeIf { it.isNotEmpty() }, start / 1000,
        plan?.takeIf { state != AnmpSos.CARING }?.let { B64.decode(it) },
    )
}
