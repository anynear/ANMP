package app.anynear.anmp.core

import app.anynear.anmp.AnmpAlive
import app.anynear.anmp.AnmpAliveState
import app.anynear.anmp.AnmpCareState
import app.anynear.anmp.AnmpPosition
import kotlinx.serialization.Serializable
import kotlin.js.JsExport
import kotlin.math.roundToInt

/**
 * A sign of life in a circle or for one person ([peer]) (ADR 0083 §5), as this device knows it: an own one ([owner] is the own person)
 * or one we watch as a helper. Times in ms; [due], [start] and [until] by the sender's clock. Kept in the core state.
 */
@JsExport
@Serializable
data class AlivePlan(
    /** Plan ID (b64u), the message ID of its start. */
    val id: String,
    val circleId: String,
    /** Person whose sign of life it is: the person of the device that sent its start. */
    val owner: String,
    val label: String = "",
    val start: Long,
    val until: Long,
    /** The next sign of life comes at the latest then. */
    val due: Long,
    /** After [due]: first a hint, then an alarm at the helpers. */
    val hint: Long,
    val alarm: Long,
    /** How long the phone may lie still before it asks. */
    val quiet: Long,
    val motion: Boolean = false,
    /** [AnmpAliveState] of the last packet. */
    val state: Int,
    /** Sender time of the last packet. */
    val lastAt: Long,
    /** Local time it arrived (own plans: sent). */
    val receivedAt: Long = 0,
    val battery: Int? = null,
    /** Last confirmation by a tap or movement (sender time); `lastAt` of the start before the first one. */
    val confirmedAt: Long = 0,
    /** Answers of helpers, newest per person. */
    val care: List<AliveCare> = emptyList(),
    /** Own plans: helpers whose devices confirmed receipt, newest per person. */
    val heard: List<AliveCare> = emptyList(),
    /** Own plans: message IDs of the last packets sent, to match receipts. */
    internal val sentMids: List<String> = emptyList(),
    /** Own plans: packets sent since the last receipt. */
    val unconfirmed: Int = 0,
    /**
     * Where the owner's phone is (ADR 0121 §10). Own plans: the place the app handed in last ([AnmpCore.aliveSetPosition]),
     * sent only with „nicht bestätigt“. Plans of others: the place of the newest packet; gone with the next packet
     * that carries none.
     */
    val position: AlivePosition? = null,
    /**
     * Plans of others: the relays released the alarm the owner's phone had left with them (ADR 0083 §5.7), and nothing
     * newer came from the phone: it is off or has no network. Gone with the next sign of life.
     */
    val guard: Boolean = false,
    /** Own plans: the alarms this phone left at the relays and has not seen deleted yet, as `space/entry`, newest last. */
    internal val guards: List<String> = emptyList(),
    /** Own plans: where the phone was before [position], oldest first, for the call the helpers get if nobody confirms. */
    internal val trail: List<AlivePosition> = emptyList(),
    /**
     * A sign of life for one person instead of a circle ([circleId] is empty then): the other person, i.e. the helper
     * of an own plan and the owner of one we watch. `null` for a plan in a circle.
     */
    val peer: String? = null,
    /**
     * Plans for one person: the secret (b64u, 32 bytes) of the space where the owner's phone leaves the alarm for the
     * relays and the helper reads it ([app.anynear.anmp.AnmpAlive.watch]).
     */
    internal val watch: String? = null,
) {
    val ended: Boolean get() = state == AnmpAliveState.END || state == AnmpAliveState.TIMEOUT
}

/**
 * A place as the apps use it (ADR 0121 §10): [latitude] and [longitude] in degrees, [at] when the device located it
 * (ms), [accuracy] in metres if known. On the wire it is rounded to about 10 m ([AnmpPosition]).
 */
@JsExport
@Serializable
data class AlivePosition(val latitude: Double, val longitude: Double, val at: Long, val accuracy: Int? = null) {
    /** On earth and located at some time; anything else is not taken. */
    val valid: Boolean
        get() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0 && at > 0

    internal fun toWire(): AnmpPosition = AnmpPosition(
        (latitude * AnmpPosition.SCALE).roundToInt(), (longitude * AnmpPosition.SCALE).roundToInt(), at / 1000,
        accuracy?.coerceIn(0, AnmpPosition.MAX_ACC),
    )

    internal companion object {
        /** The place of a packet; `null` if it carries none or one out of range. */
        fun of(p: AnmpPosition?): AlivePosition? = p?.takeIf { it.plausible }?.let {
            AlivePosition(it.lat.toDouble() / AnmpPosition.SCALE, it.lon.toDouble() / AnmpPosition.SCALE, it.at * 1000, it.acc)
        }
    }
}

/** A helper's answer ([AnmpCareState]) or, in [AlivePlan.heard], a receipt ([state] 0), with time (ms). */
@JsExport
@Serializable
data class AliveCare(val person: String, val state: Int, val ts: Long)

/**
 * What a sign of life means right now (ADR 0083 §5.2–5.4), from [AliveLogic.status]. [phase]: one of the constants;
 * [reason] for [HINT] and [ALARM]; [nextAt]: when the phase changes next without new packets (ms, 0 = never);
 * [looking]: persons who said „Ich schaue nach“ since the last sign of life; [away]: helpers who are not available;
 * [notArriving] (own plans): the last two packets got no receipt.
 */
@JsExport
data class AliveStatus(
    val plan: AlivePlan,
    val own: Boolean,
    val phase: Int,
    val reason: Int,
    val nextAt: Long,
    val looking: List<String>,
    val away: List<String>,
    val notArriving: Boolean,
) {
    companion object {
        /** Running; for an own plan: nothing to do until [nextAt]. */
        const val RUNNING = 1

        /** Own plan: ask „Alles gut?“. */
        const val ASK = 2

        /** Helper: quiet hint „hat noch nicht bestätigt“. */
        const val HINT = 3

        /** Helper: loud alarm. */
        const val ALARM = 4

        const val ENDED = 5

        const val REASON_NONE = 0

        /** The phone said nobody confirmed. */
        const val REASON_MISSED = 1

        /** Nothing came any more. */
        const val REASON_UNREACHABLE = 2
    }
}

/** Rules of a sign of life, without network or clock (ADR 0083 §5). */
@JsExport
object AliveLogic {
    /** Shortest time before asking; radio carries at most one sign of life per 15 minutes (ADR 0083 §7). */
    const val MIN_QUIET = 15 * 60_000L

    /** Longest time before asking: once a day. */
    const val MAX_QUIET = 24 * 3_600_000L

    /** Longest plan. */
    const val MAX_LENGTH = 400 * 24 * 3_600_000L

    const val DEFAULT_HINT = 5 * 60_000L

    const val DEFAULT_ALARM = 10 * 60_000L

    /** The phone asks again after this, louder. */
    const val ASK_AGAIN = 2 * 60_000L

    /**
     * When the relays release the alarm an own phone leaves with every sign of life (ADR 0083 §5.7): when the helpers'
     * own deadline rings, [alarm] after [due]. Seconds, as on the wire.
     */
    fun guardAt(due: Long, alarm: Int): Long = due + alarm

    /** An alarm that would be released sooner than this after it is written is not left at the relays. */
    const val GUARD_MIN_AHEAD = 30_000L

    /** How long a released alarm stays at the relays for helpers who were offline. */
    const val GUARD_KEPT = 7 * 24 * 3_600_000L

    /** Movement counts again only after half the quiet time, so a moving phone does not send all the time. */
    fun movingWorthSending(plan: AlivePlan, now: Long): Boolean = now - plan.lastAt >= plan.quiet / 2

    /** When an own phone reports „nicht bestätigt“: after it asked twice, before the helpers' hint. */
    fun missedAt(plan: AlivePlan): Long = plan.due + minOf(ASK_AGAIN + 60_000L, plan.hint / 2)

    /** Next due time after a confirmation at [now]: quiet time later, never after the end. */
    fun nextDue(now: Long, quiet: Long, until: Long): Long = minOf(now + quiet, until)

    /** Ended plans and stale ones (a day after their end) are kept a week, then dropped. */
    fun stale(plan: AlivePlan, now: Long): Boolean = now > plan.until + 7 * 24 * 3_600_000L

    fun status(plan: AlivePlan, own: Boolean, me: String, now: Long): AliveStatus {
        // Packets carry seconds; an answer in the same second as the last sign of life still counts.
        val looking = plan.care.filter { it.state == AnmpCareState.LOOKING && it.ts / 1000 >= plan.lastAt / 1000 }.map { it.person }
        val away = plan.care.filter { it.state == AnmpCareState.AWAY }.map { it.person }
        if (plan.ended || now > plan.until + 24 * 3_600_000L) {
            return AliveStatus(plan, own, AliveStatus.ENDED, AliveStatus.REASON_NONE, 0, looking, away, false)
        }
        if (own) {
            // Erst warnen, wenn auch das letzte Paket nach einer Minute keine Bestätigung hat: Bestätigungen kommen gesammelt.
            val notArriving = plan.unconfirmed >= 2 && now - plan.receivedAt >= NOT_ARRIVING_AFTER
            val (phase, next) = when {
                now >= plan.until -> AliveStatus.ASK to 0L
                now < plan.due -> AliveStatus.RUNNING to plan.due
                // After „nicht bestätigt“ the next step is the call for the helpers, when their alarm is due.
                plan.state == AnmpAliveState.MISSED -> AliveStatus.ASK to (if (now < plan.due + plan.alarm) plan.due + plan.alarm else plan.until)
                else -> AliveStatus.ASK to missedAt(plan).coerceAtLeast(now)
            }
            return AliveStatus(plan, true, phase, AliveStatus.REASON_NONE, if (now >= plan.until) 0 else minOf(next, plan.until), looking, away, notArriving)
        }
        val reason = if (plan.state == AnmpAliveState.MISSED) AliveStatus.REASON_MISSED else AliveStatus.REASON_UNREACHABLE
        // A helper who said „Ich kann gerade nicht“ gets neither hint nor alarm; the owner knows (ADR 0083 §5.3).
        if (me in away) return AliveStatus(plan, false, AliveStatus.RUNNING, AliveStatus.REASON_NONE, 0, looking, away, false)
        // The relays released the phone's own alarm (ADR 0083 §5.7): it is the same alarm as the one of this device's
        // deadline, also if this device's clock is a little behind.
        if (plan.guard) return AliveStatus(plan, false, AliveStatus.ALARM, reason, 0, looking, away, false)
        return when {
            plan.state == AnmpAliveState.MISSED && now >= plan.due + plan.hint -> alarmOrHint(plan, now, reason, looking, away)
            now < plan.due + plan.hint -> AliveStatus(plan, false, AliveStatus.RUNNING, AliveStatus.REASON_NONE, plan.due + plan.hint, looking, away, false)
            else -> alarmOrHint(plan, now, reason, looking, away)
        }
    }

    private fun alarmOrHint(plan: AlivePlan, now: Long, reason: Int, looking: List<String>, away: List<String>): AliveStatus =
        if (now < plan.due + plan.alarm) {
            AliveStatus(plan, false, AliveStatus.HINT, reason, plan.due + plan.alarm, looking, away, false)
        } else {
            AliveStatus(plan, false, AliveStatus.ALARM, reason, 0, looking, away, false)
        }

    /** Plausible packet from the wire. */
    internal fun valid(a: AnmpAlive): Boolean {
        if (a.plan.size != app.anynear.anmp.Anmp.ID_BYTES) return false
        if (a.start <= 0 || a.until < a.start || a.due < a.start || a.due > a.until) return false
        if ((a.until - a.start) * 1000 > MAX_LENGTH) return false
        if (a.hint <= 0 || a.alarm < a.hint || a.alarm > 86_400) return false
        if (a.quiet < 60 || a.quiet * 1000L > MAX_QUIET) return false
        return (a.label?.length ?: 0) <= MAX_LABEL
    }

    const val MAX_LABEL = 40

    /** So lange darf die Empfangsbestätigung des letzten eigenen Pakets dauern, bevor die App „kommt nicht an“ zeigt. */
    const val NOT_ARRIVING_AFTER = 60_000L
}
