package app.anynear.anmp.core

import app.anynear.anmp.AnmpAlive
import app.anynear.anmp.AnmpAliveState
import app.anynear.anmp.AnmpCareState
import app.anynear.anmp.AnmpPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR 0083 §5: Fristen eines Lebenszeichens ohne Netz. */
class AliveLogicTest {
    private val min = 60_000L
    private val plan = AlivePlan(
        id = "p", circleId = "c", owner = "owner", start = 0, until = 8 * 60 * min, due = 20 * min, hint = 5 * min, alarm = 10 * min,
        quiet = 20 * min, motion = true, state = AnmpAliveState.START, lastAt = 0,
    )

    private fun helper(p: AlivePlan, now: Long) = AliveLogic.status(p, own = false, me = "helper", now = now)

    @Test
    fun helperGoesFromRunningToHintToAlarm() {
        assertEquals(AliveStatus.RUNNING, helper(plan, 24 * min).phase)
        assertEquals(25 * min, helper(plan, 24 * min).nextAt)
        val hint = helper(plan, 25 * min)
        assertEquals(AliveStatus.HINT, hint.phase)
        assertEquals(AliveStatus.REASON_UNREACHABLE, hint.reason)
        assertEquals(30 * min, hint.nextAt)
        assertEquals(AliveStatus.ALARM, helper(plan, 30 * min).phase)
        assertEquals(0, helper(plan, 30 * min).nextAt)
    }

    /** ADR 0083 §5.7: the alarm the relays released is the alarm of the deadline, also on a clock that is behind. */
    @Test
    fun aReleasedAlarmOfTheRelaysIsTheAlarmOfTheDeadline() {
        val released = plan.copy(guard = true)
        val early = helper(released, 27 * min)
        assertEquals(AliveStatus.ALARM, early.phase)
        assertEquals(AliveStatus.REASON_UNREACHABLE, early.reason)
        assertEquals(helper(plan, 31 * min).phase, helper(released, 31 * min).phase)
        assertEquals(AliveStatus.REASON_MISSED, helper(released.copy(state = AnmpAliveState.MISSED), 27 * min).reason)
        // Ended, or a helper who is away: no alarm.
        assertEquals(AliveStatus.ENDED, helper(released.copy(state = AnmpAliveState.END), 31 * min).phase)
        val away = released.copy(care = listOf(AliveCare("helper", AnmpCareState.AWAY, 1 * min)))
        assertEquals(AliveStatus.RUNNING, helper(away, 31 * min).phase)
        assertEquals(30 * 60L, AliveLogic.guardAt(20 * 60L, 10 * 60))
    }

    @Test
    fun missedSaysWhyAndEndedStaysQuiet() {
        val missed = plan.copy(state = AnmpAliveState.MISSED, lastAt = 23 * min)
        assertEquals(AliveStatus.REASON_MISSED, helper(missed, 26 * min).reason)
        assertEquals(AliveStatus.ENDED, helper(plan.copy(state = AnmpAliveState.END), 60 * min).phase)
        assertEquals(AliveStatus.ENDED, helper(plan.copy(state = AnmpAliveState.TIMEOUT), 60 * min).phase)
    }

    @Test
    fun anAwayHelperGetsNothingAndLookingCountsOnlyAfterTheLastSign() {
        val away = plan.copy(care = listOf(AliveCare("helper", AnmpCareState.AWAY, 1)))
        assertEquals(AliveStatus.RUNNING, helper(away, 40 * min).phase)
        val looking = plan.copy(lastAt = 10 * min, care = listOf(AliveCare("other", AnmpCareState.LOOKING, 5 * min), AliveCare("x", AnmpCareState.LOOKING, 11 * min)))
        assertEquals(listOf("x"), helper(looking, 40 * min).looking)
    }

    @Test
    fun ownPlanAsksAtDueAndReportsMissedBeforeTheHelpersHint() {
        val own = AliveLogic.status(plan, own = true, me = "owner", now = 10 * min)
        assertEquals(AliveStatus.RUNNING, own.phase)
        assertEquals(20 * min, own.nextAt)
        val ask = AliveLogic.status(plan, own = true, me = "owner", now = 20 * min)
        assertEquals(AliveStatus.ASK, ask.phase)
        assertTrue(AliveLogic.missedAt(plan) in (plan.due + AliveLogic.ASK_AGAIN)..<(plan.due + plan.hint))
        assertFalse(AliveLogic.status(plan.copy(unconfirmed = 2, receivedAt = 0), own = true, me = "owner", now = 30_000).notArriving)
        assertTrue(AliveLogic.status(plan.copy(unconfirmed = 2, receivedAt = 0), own = true, me = "owner", now = 61_000).notArriving)
    }

    @Test
    fun movementCountsAgainOnlyAfterHalfTheQuietTime() {
        assertFalse(AliveLogic.movingWorthSending(plan, 9 * min))
        assertTrue(AliveLogic.movingWorthSending(plan, 10 * min))
        assertEquals(30 * min, AliveLogic.nextDue(10 * min, 20 * min, 8 * 60 * min))
        assertEquals(8 * 60 * min, AliveLogic.nextDue(470 * min, 20 * min, 8 * 60 * min))
    }

    @Test
    fun implausiblePacketsAreDropped() {
        val ok = AnmpAlive(ByteArray(8), AnmpAliveState.START, 1000, 2000, 1500, 300, 600, 1200)
        assertTrue(AliveLogic.valid(ok))
        assertFalse(AliveLogic.valid(AnmpAlive(ByteArray(7), 1, 1000, 2000, 1500, 300, 600, 1200)))
        assertFalse(AliveLogic.valid(AnmpAlive(ByteArray(8), 1, 1000, 2000, 2500, 300, 600, 1200)))
        assertFalse(AliveLogic.valid(AnmpAlive(ByteArray(8), 1, 1000, 2000, 1500, 600, 300, 1200)))
        assertFalse(AliveLogic.valid(AnmpAlive(ByteArray(8), 1, 1000, 2000, 1500, 300, 600, 10)))
        assertFalse(AliveLogic.valid(AnmpAlive(ByteArray(8), 1, 1000, 2000, 1500, 300, 600, 1200, label = "x".repeat(41))))
    }

    /** ADR 0121 §10: a place is rounded to about 10 m on the wire, keeps its sign, and one off the earth is not taken. */
    @Test
    fun aPlaceIsRoundedKeepsItsSignAndMustBeOnEarth() {
        val wire = AlivePosition(-33.85678, -151.21529, at = 1_700_000_123_456L, accuracy = 250_000).toWire()
        assertEquals(-338_568, wire.lat)
        assertEquals(-1_512_153, wire.lon)
        assertEquals(1_700_000_123L, wire.at)
        assertEquals(AnmpPosition.MAX_ACC, wire.acc)
        assertEquals(AlivePosition(-33.8568, -151.2153, at = 1_700_000_123_000L, accuracy = AnmpPosition.MAX_ACC), AlivePosition.of(wire))

        assertNull(AlivePosition.of(null))
        assertNull(AlivePosition.of(AnmpPosition(900_001, 0, 1)))
        assertNull(AlivePosition.of(AnmpPosition(0, -1_800_001, 1)))
        assertNull(AlivePosition.of(AnmpPosition(0, 0, 0)))
        assertNull(AlivePosition.of(AnmpPosition(0, 0, 1, acc = AnmpPosition.MAX_ACC + 1)))
        assertTrue(AlivePosition(90.0, -180.0, at = 1).valid)
        assertFalse(AlivePosition(Double.NaN, 0.0, at = 1).valid)
        assertFalse(AlivePosition(0.0, 180.5, at = 1).valid)
        assertFalse(AlivePosition(0.0, 0.0, at = 0).valid)
    }
}
