package app.anynear.anmp.core

import app.anynear.anmp.AnmpAliveState
import app.anynear.anmp.AnmpCareState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR 0083 §1, §5: Lebenszeichen in einem frei gewählten Kreis zwischen zwei Personen. */
class AliveFlowTest {
    private class ShiftClock : CoreClock {
        var shift = 0L

        override fun nowMillis(): Long = CoreClock.SYSTEM.nowMillis() + shift
    }

    private suspend fun World.pair(clock: CoreClock = CoreClock.SYSTEM): Triple<Device, Device, CircleState> {
        val a = device("Person", clock = clock)
        val b = device("Helfer")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        a.await<CoreEvent.MemberUpdated> { it.member.deviceId == b.deviceId }
        eventually { a.core.canWriteTo(b.deviceId) && b.core.canWriteTo(a.deviceId) }
        return Triple(a, b, circle)
    }

    @Test
    fun everyMemberStartsAnOwnSignOfLifeInTheCircleItChooses() = world {
        val (a, b, circle) = pair()
        val now = CoreClock.SYSTEM.nowMillis()
        assertNull(a.core.startAlive("unbekannt", "Unterwegs", until = now + 3_600_000L, quiet = AliveLogic.MIN_QUIET))
        val ofA = assertNotNull(a.core.startAlive(circle.id, "Unterwegs", until = now + 3_600_000L, quiet = AliveLogic.MIN_QUIET))
        val ofB = assertNotNull(b.core.startAlive(circle.id, "Allein arbeiten", until = now + 3_600_000L, quiet = AliveLogic.MIN_QUIET))
        // Each watches the other's; the own one is the own.
        eventually { b.core.aliveStatus(ofA.id) != null && a.core.aliveStatus(ofB.id) != null }
        assertEquals(a.info.personId, b.core.aliveStatus(ofA.id)!!.plan.owner)
        assertTrue(!b.core.aliveStatus(ofA.id)!!.own)
        assertEquals(b.info.personId, a.core.aliveStatus(ofB.id)!!.plan.owner)
        assertTrue(a.core.aliveStatus(ofA.id)!!.own)
        // A helper cannot confirm or end someone else's sign of life.
        assertTrue(!b.core.confirmAlive(ofA.id))
        assertTrue(!b.core.endAlive(ofA.id))
    }

    @Test
    fun theNotificationExtensionOfTheHelperSeesTheStart() = world {
        val (a, b, circle) = pair()
        // Ben's iPhone is locked: only the notification extension runs (ADR 0117).
        b.core.stop()
        val nse = AnmpCore(scope, net.config(), AnmpPlatform(b.secrets, b.store, b.blobs, connector = net.connector))
        nse.open()
        val now = CoreClock.SYSTEM.nowMillis()
        val plan = assertNotNull(a.core.startAlive(circle.id, "Unterwegs", until = now + 3_600_000L, quiet = AliveLogic.MIN_QUIET))
        val found = mutableListOf<CoreEvent.AliveWake>()
        eventually { found += nse.wakeFetch().filterIsInstance<CoreEvent.AliveWake>(); found.isNotEmpty() }
        val wake = found.single()
        assertEquals(circle.id, wake.circleId)
        assertEquals(a.info.personId, wake.owner)
        assertEquals(AnmpAliveState.START, wake.alive.state)
        assertEquals("Unterwegs", wake.alive.label)
        assertEquals(plan.id, app.anynear.andp.B64.encode(wake.alive.plan))
        // The extension stores nothing: the plan is not known to the core yet.
        assertNull(nse.aliveStatus(plan.id))
    }

    @Test
    fun theHelperLearnsThePlanAndTheOwnerSeesThatItArrived() = world {
        val (a, b, circle) = pair()
        val now = CoreClock.SYSTEM.nowMillis()
        val plan = assertNotNull(a.core.startAlive(circle.id, "Allein arbeiten", until = now + 8 * 3_600_000L, quiet = 20 * 60_000L, motion = true))
        assertEquals(circle.id, plan.circleId)

        b.await<CoreEvent.AliveChanged> { it.planId == plan.id }
        val seen = assertNotNull(b.core.aliveStatus(plan.id))
        assertEquals(AliveStatus.RUNNING, seen.phase)
        assertEquals("Allein arbeiten", seen.plan.label)
        assertEquals(a.info.personId, seen.plan.owner)
        assertTrue(seen.plan.motion)
        assertEquals(plan.due / 1000, seen.plan.due / 1000)

        // „Läuft · <Helfer> weiß Bescheid“ only after the helper's receipt (ADR 0083 §5.1).
        eventually { a.core.aliveStatus(plan.id)?.plan?.heard?.any { it.person == b.info.personId } == true }
        assertEquals(0, a.core.aliveStatus(plan.id)!!.plan.unconfirmed)

        // Without news the helper first gets a hint, then an alarm: nothing came, so „nicht erreichbar“.
        val due = seen.plan.due
        assertEquals(AliveStatus.HINT, b.core.aliveStatus(plan.id, due + 6 * 60_000L)!!.phase)
        val alarm = b.core.aliveStatus(plan.id, due + 11 * 60_000L)!!
        assertEquals(AliveStatus.ALARM, alarm.phase)
        assertEquals(AliveStatus.REASON_UNREACHABLE, alarm.reason)

        // A tap moves the due time; the helper follows.
        assertTrue(a.core.confirmAlive(plan.id))
        eventually { b.core.aliveStatus(plan.id)?.plan?.state == AnmpAliveState.OK }
        assertTrue(b.core.aliveStatus(plan.id)!!.plan.due >= due)
        // Movement right after a tap is not worth a packet.
        assertTrue(!a.core.confirmAlive(plan.id, moving = true))

        assertTrue(a.core.endAlive(plan.id))
        eventually { b.core.aliveStatus(plan.id)?.phase == AliveStatus.ENDED }
        assertTrue(b.core.aliveStatuses().none { it.plan.id == plan.id })
    }

    @Test
    fun aPhoneThatAskedInVainSaysSoAndTheHelperSeesWhy() = world {
        val clock = ShiftClock()
        val (a, b, circle) = pair(clock)
        val now = clock.nowMillis()
        // Ask at once, hint after one minute: the phone reports „nicht bestätigt“ after half of it.
        val plan = assertNotNull(a.core.startAlive(circle.id, "Unterwegs", until = now + 3_600_000L, quiet = AliveLogic.MIN_QUIET, firstDue = now, hint = 60_000L))
        b.await<CoreEvent.AliveChanged> { it.planId == plan.id }
        assertEquals(AliveStatus.ASK, a.core.aliveStatus(plan.id)!!.phase)

        clock.shift = 35_000L
        a.core.aliveTick()
        eventually { b.core.aliveStatus(plan.id)?.plan?.state == AnmpAliveState.MISSED }
        val st = b.core.aliveStatus(plan.id, plan.due + 61_000L)!!
        assertEquals(AliveStatus.HINT, st.phase)
        assertEquals(AliveStatus.REASON_MISSED, st.reason)
        assertEquals(AliveStatus.ALARM, b.core.aliveStatus(plan.id, plan.due + 11 * 60_000L)!!.phase)
    }

    @Test
    fun helpersAnswerAndAnAwayHelperGetsNoAlarm() = world {
        val (a, b, circle) = pair()
        val now = CoreClock.SYSTEM.nowMillis()
        val plan = assertNotNull(a.core.startAlive(circle.id, "Allein zu Hause", until = now + 3_600_000L, quiet = 30 * 60_000L))
        b.await<CoreEvent.AliveChanged> { it.planId == plan.id }

        assertTrue(b.core.careAlive(plan.id, AnmpCareState.LOOKING))
        eventually { a.core.aliveStatus(plan.id)?.looking == listOf(b.info.personId) }

        assertTrue(b.core.careAlive(plan.id, AnmpCareState.AWAY))
        eventually { a.core.aliveStatus(plan.id)?.away == listOf(b.info.personId) }
        val due = b.core.aliveStatus(plan.id)!!.plan.due
        assertEquals(AliveStatus.RUNNING, b.core.aliveStatus(plan.id, due + 20 * 60_000L)!!.phase)

        assertTrue(b.core.careAlive(plan.id, AnmpCareState.BACK))
        eventually { a.core.aliveStatus(plan.id)?.away?.isEmpty() == true }
        assertEquals(AliveStatus.ALARM, b.core.aliveStatus(plan.id, due + 20 * 60_000L)!!.phase)
    }

    @Test
    fun aNewStartEndsTheOldOne() = world {
        val (a, b, circle) = pair()
        val now = CoreClock.SYSTEM.nowMillis()
        val first = assertNotNull(a.core.startAlive(circle.id, "Unterwegs", until = now + 7_200_000L, quiet = 30 * 60_000L))
        val second = assertNotNull(a.core.startAlive(circle.id, "Allein arbeiten", until = now + 7_200_000L, quiet = 20 * 60_000L))
        eventually { b.core.aliveStatus(first.id)?.phase == AliveStatus.ENDED && b.core.aliveStatus(second.id)?.phase == AliveStatus.RUNNING }
        assertEquals(listOf(second.id), a.core.aliveStatuses().map { it.plan.id })
    }
}
