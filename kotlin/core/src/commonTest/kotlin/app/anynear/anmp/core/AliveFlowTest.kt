package app.anynear.anmp.core

import app.anynear.anmp.AnmpAliveState
import app.anynear.anmp.AnmpCareState
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
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

    /** For tests that let the relays release an alarm long before its time: the helper takes it as due. */
    private val patient = TEST_TIMINGS.copy(aliveGuardEarly = 1.hours)

    private suspend fun World.pair(clock: CoreClock = CoreClock.SYSTEM, helper: CoreTimings = TEST_TIMINGS, helperClock: CoreClock = CoreClock.SYSTEM): Triple<Device, Device, CircleState> {
        val a = device("Person", clock = clock)
        val b = device("Helfer", timings = helper, clock = helperClock)
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

    /** ADR 0121 §10: the place goes along only with „nicht bestätigt“ and is gone again once the person confirmed. */
    @Test
    fun thePlaceLeavesThePhoneOnlyWhenNobodyConfirmed() = world {
        val clock = ShiftClock()
        val (a, b, circle) = pair(clock)
        val now = clock.nowMillis()
        val plan = assertNotNull(a.core.startAlive(circle.id, "Unterwegs", until = now + 3_600_000L, quiet = AliveLogic.MIN_QUIET, firstDue = now, hint = 60_000L))
        b.await<CoreEvent.AliveChanged> { it.planId == plan.id }
        // A helper cannot hand in a place for someone else's plan; a place off the earth is not taken.
        val place = AlivePosition(50.110649, 8.682127, at = now - 120_000L, accuracy = 12)
        assertTrue(!b.core.aliveSetPosition(plan.id, place))
        assertTrue(!a.core.aliveSetPosition(plan.id, AlivePosition(91.0, 0.0, at = now)))
        assertTrue(a.core.aliveSetPosition(plan.id, place))
        // Nothing left the phone: the start carried no place, and the helper knows none.
        assertNull(b.core.aliveStatus(plan.id)!!.plan.position)

        clock.shift = 35_000L
        a.core.aliveTick()
        eventually { b.core.aliveStatus(plan.id)?.plan?.state == AnmpAliveState.MISSED }
        val seen = assertNotNull(b.core.aliveStatus(plan.id)!!.plan.position)
        // Rounded to about 10 m on the way.
        assertEquals(50.1106, seen.latitude, 0.00001)
        assertEquals(8.6821, seen.longitude, 0.00001)
        assertEquals(12, seen.accuracy)
        assertEquals((now - 120_000L) / 1000, seen.at / 1000)

        // „Alles gut“ after all: the helper's copy forgets the place, the phone keeps what it was handed.
        assertTrue(a.core.confirmAlive(plan.id))
        eventually { b.core.aliveStatus(plan.id)?.plan?.state == AnmpAliveState.OK }
        assertNull(b.core.aliveStatus(plan.id)!!.plan.position)
        assertEquals(place, a.core.aliveStatus(plan.id)!!.plan.position)

        // Withdrawn: the phone forgets it, so the next „nicht bestätigt“ carries none.
        assertTrue(a.core.aliveSetPosition(plan.id, null))
        assertNull(a.core.aliveStatus(plan.id)!!.plan.position)

        // Handed in again and then „Bin fertig“: the ended plan keeps no place on the phone either.
        assertTrue(a.core.aliveSetPosition(plan.id, place))
        assertTrue(a.core.endAlive(plan.id))
        assertNull(a.core.alivePlans().single { it.id == plan.id }.position)
        assertTrue(!a.core.aliveSetPosition(plan.id, place))
    }

    // --- The watch at the relays (ADR 0083 §5.7) ---

    private suspend fun World.spaceOf(d: Device, circle: CircleState) = CircleKeys.of(d.core.circle(circle.id)!!.secret).space

    /** Every relay releases what it keeps back, as if the time had come. */
    private suspend fun World.releaseAll(): Int = net.relays.sumOf { it.release(net) }

    private suspend fun World.heldAt(space: app.anynear.andp.Key) = net.holders(space.toString()).map { it.held(space) }

    @Test
    fun everySignOfLifeLeavesAnAlarmAtTheRelaysAndTheNextOneReplacesIt() = world {
        val (a, b, circle) = pair()
        val now = CoreClock.SYSTEM.nowMillis()
        val space = spaceOf(a, circle)
        val plan = assertNotNull(a.core.startAlive(circle.id, "Unterwegs", until = now + 3_600_000L, quiet = AliveLogic.MIN_QUIET, hint = 60_000L, alarm = 120_000L))
        b.await<CoreEvent.AliveChanged> { it.planId == plan.id }
        // One alarm at every holder, kept back until the helpers' own deadline would ring.
        eventually { heldAt(space).all { it.size == 1 } }
        val first = heldAt(space).first().single()
        assertEquals(AliveLogic.guardAt(plan.due / 1000, 120), first.notBefore)
        assertTrue(first.meta.until > first.notBefore)
        // It woke nobody and the helper does not see it: for it the plan just runs.
        assertTrue(net.relays.none { r -> r.notifiedPuts.any { it.id == first.meta.id } })
        assertEquals(AliveStatus.RUNNING, b.core.aliveStatus(plan.id)!!.phase)
        assertTrue(!b.core.aliveStatus(plan.id)!!.plan.guard)

        // „Alles gut“: a new alarm for the new due time, the old one is deleted.
        kotlinx.coroutines.delay(1100)
        assertTrue(a.core.confirmAlive(plan.id))
        eventually { heldAt(space).all { h -> h.size == 1 && h.single().meta.id != first.meta.id } }
        val second = heldAt(space).first().single()
        assertTrue(second.notBefore > first.notBefore)
        eventually { net.holders(space.toString()).none { it.has(space, first.meta.id) } }

        // „Bin fertig“: nothing stays behind, so nothing can ring later.
        assertTrue(a.core.endAlive(plan.id))
        eventually { heldAt(space).all { it.isEmpty() } }
        assertEquals(0, releaseAll())
        eventually { b.core.aliveStatus(plan.id)?.phase == AliveStatus.ENDED }
    }

    @Test
    fun aPhoneThatGoesSilentIsReportedByTheRelaysWithItsPlace() = world {
        val (a, b, circle) = pair(helper = patient)
        val now = CoreClock.SYSTEM.nowMillis()
        val space = spaceOf(a, circle)
        val plan = assertNotNull(a.core.startAlive(circle.id, "Unterwegs", until = now + 3_600_000L, quiet = AliveLogic.MIN_QUIET, hint = 60_000L, alarm = 120_000L))
        b.await<CoreEvent.AliveChanged> { it.planId == plan.id }
        val place = AlivePosition(50.110649, 8.682127, at = now - 120_000L, accuracy = 12)
        assertTrue(a.core.aliveSetPosition(plan.id, place))
        kotlinx.coroutines.delay(1100)
        assertTrue(a.core.confirmAlive(plan.id))
        eventually { b.core.aliveStatus(plan.id)?.plan?.state == AnmpAliveState.OK }
        // The confirmation carried no place; the helper knows none.
        assertNull(b.core.aliveStatus(plan.id)!!.plan.position)
        eventually { heldAt(space).all { it.size == 1 } }

        // The phone is switched off. When the time comes, the relays release the alarm and wake the helper.
        a.core.stop()
        val known = b.core.aliveStatus(plan.id)!!.plan
        assertTrue(releaseAll() >= 1)
        eventually { b.core.aliveStatus(plan.id)?.plan?.guard == true }
        val st = b.core.aliveStatus(plan.id)!!
        assertEquals(AliveStatus.ALARM, st.phase)
        assertEquals(AliveStatus.REASON_UNREACHABLE, st.reason)
        val seen = assertNotNull(st.plan.position)
        assertEquals(50.1106, seen.latitude, 0.00001)
        assertEquals(8.6821, seen.longitude, 0.00001)
        assertEquals((now - 120_000L) / 1000, seen.at / 1000)
        // It is the same plan and the same due time as the helper's own deadline: one alarm, not two.
        assertEquals(known.due, st.plan.due)
        assertEquals(known.lastAt, st.plan.lastAt)
        assertEquals(AnmpAliveState.OK, st.plan.state)
        assertEquals(1, b.core.aliveStatuses().count { it.plan.id == plan.id })
    }

    @Test
    fun aReleasedAlarmIsDroppedSilentlyWhenANewerSignOfLifeIsKnown() = world {
        val (a, b, circle) = pair()
        val now = CoreClock.SYSTEM.nowMillis()
        val space = spaceOf(a, circle)
        val plan = assertNotNull(a.core.startAlive(circle.id, "Unterwegs", until = now + 3_600_000L, quiet = AliveLogic.MIN_QUIET, hint = 60_000L, alarm = 120_000L))
        b.await<CoreEvent.AliveChanged> { it.planId == plan.id }
        eventually { heldAt(space).all { it.size == 1 } }
        val old = heldAt(space).first().single().meta.id
        // The deletion of the old alarm does not get through, the newer sign of life does (e.g. it came over radio).
        net.relays.forEach { it.ignoreDeletes = true }
        kotlinx.coroutines.delay(1100)
        assertTrue(a.core.confirmAlive(plan.id))
        eventually { b.core.aliveStatus(plan.id)?.plan?.state == AnmpAliveState.OK }
        eventually { heldAt(space).all { it.size == 2 } }
        // The relays release the old one. A message written after it shows that the helper read past it.
        net.holders(space.toString()).forEach { r -> r.release(net, r.held(space).first { it.meta.id == old }.notBefore) }
        assertTrue(net.holders(space.toString()).all { r -> r.held(space).none { it.meta.id == old } })
        val out = assertNotNull(a.core.sendCircle(circle.id, "bin gleich da"))
        b.await<CoreEvent.Message> { it.mid == out.mid }
        val st = b.core.aliveStatus(plan.id)!!
        assertTrue(!st.plan.guard)
        assertEquals(AnmpAliveState.OK, st.plan.state)
        assertEquals(AliveStatus.RUNNING, st.phase)
        assertNull(st.plan.position)
    }

    @Test
    fun anAlarmARelayReleasesLongBeforeItsTimeRingsNobody() = world {
        val (a, b, circle) = pair()
        val now = CoreClock.SYSTEM.nowMillis()
        val space = spaceOf(a, circle)
        val plan = assertNotNull(a.core.startAlive(circle.id, "Unterwegs", until = now + 3_600_000L, quiet = AliveLogic.MIN_QUIET, hint = 60_000L, alarm = 120_000L))
        b.await<CoreEvent.AliveChanged> { it.planId == plan.id }
        eventually { heldAt(space).all { it.size == 1 } }
        // 17 minutes too early. A message written after it shows that the helper read past it.
        assertTrue(releaseAll() >= 1)
        val out = assertNotNull(a.core.sendCircle(circle.id, "alles ruhig"))
        b.await<CoreEvent.Message> { it.mid == out.mid }
        assertTrue(!b.core.aliveStatus(plan.id)!!.plan.guard)
        assertEquals(AliveStatus.RUNNING, b.core.aliveStatus(plan.id)!!.phase)
    }

    @Test
    fun noAlarmIsLeftAtRelaysThatWouldDeliverItAtOnce() = world {
        // Relays that do not know the extension would hand the alarm out the moment it is written (ANDP §19 rule 3).
        net.relays.forEach { it.notBefore = false }
        val (a, b, circle) = pair()
        val now = CoreClock.SYSTEM.nowMillis()
        val plan = assertNotNull(a.core.startAlive(circle.id, "Unterwegs", until = now + 3_600_000L, quiet = AliveLogic.MIN_QUIET, hint = 60_000L, alarm = 120_000L))
        b.await<CoreEvent.AliveChanged> { it.planId == plan.id }
        assertTrue(a.core.endAlive(plan.id))
        eventually { b.core.aliveStatus(plan.id)?.phase == AliveStatus.ENDED }
        assertEquals(0, net.relays.sumOf { it.notBeforePuts })
        assertTrue(!b.core.aliveStatus(plan.id)!!.plan.guard)
    }

    @Test
    fun theNotificationExtensionRingsForAReleasedAlarmAndStaysQuietForAStaleOne() = world {
        val (a, b, circle) = pair()
        val now = CoreClock.SYSTEM.nowMillis()
        val space = spaceOf(a, circle)
        val plan = assertNotNull(a.core.startAlive(circle.id, "Unterwegs", until = now + 3_600_000L, quiet = AliveLogic.MIN_QUIET, hint = 60_000L, alarm = 120_000L))
        b.await<CoreEvent.AliveChanged> { it.planId == plan.id }
        eventually { heldAt(space).all { it.size == 1 } }
        val old = heldAt(space).first().single().meta.id
        net.relays.forEach { it.ignoreDeletes = true }
        kotlinx.coroutines.delay(1100)
        assertTrue(a.core.aliveSetPosition(plan.id, AlivePosition(50.110649, 8.682127, at = now, accuracy = 12)))
        assertTrue(a.core.confirmAlive(plan.id))
        eventually { b.core.aliveStatus(plan.id)?.plan?.state == AnmpAliveState.OK }
        eventually { heldAt(space).all { it.size == 2 } }
        // Ben's iPhone is locked: only the notification extension runs (ADR 0117). Anna's phone is off.
        b.core.stop()
        a.core.stop()
        val nse = AnmpCore(scope, net.config(patient), AnmpPlatform(b.secrets, b.store, b.blobs, connector = net.connector))
        nse.open()
        nse.wakeFetch()
        // The alarm of the start is stale, Ben knows the confirmation: no sound.
        net.holders(space.toString()).forEach { r -> r.release(net, r.held(space).first { it.meta.id == old }.notBefore) }
        assertTrue(nse.wakeFetch().filterIsInstance<CoreEvent.AliveWake>().none { it.alive.guard })
        // The alarm of the confirmation is the real one.
        releaseAll()
        val found = mutableListOf<CoreEvent.AliveWake>()
        eventually { found += nse.wakeFetch().filterIsInstance<CoreEvent.AliveWake>().filter { it.alive.guard }; found.isNotEmpty() }
        val wake = found.single()
        assertEquals(a.info.personId, wake.owner)
        assertEquals(plan.id, app.anynear.andp.B64.encode(wake.alive.plan))
        assertEquals(AnmpAliveState.MISSED, wake.alive.state)
        assertNotNull(wake.alive.position)
    }

    // --- A sign of life nobody confirmed is a call for the helpers (ADR 0083 §5.4, ADR 0121 §22) ---

    @Test
    fun aPhoneThatAskedInVainCallsTheHelpersWhenTheirAlarmIsDue() = world {
        val clock = ShiftClock()
        val (a, b, circle) = pair(clock)
        val now = clock.nowMillis()
        val plan = assertNotNull(a.core.startAlive(circle.id, "Unterwegs", until = now + 3_600_000L, quiet = AliveLogic.MIN_QUIET, firstDue = now, hint = 60_000L, alarm = 120_000L))
        b.await<CoreEvent.AliveChanged> { it.planId == plan.id }
        val place = AlivePosition(50.110649, 8.682127, at = now - 60_000L, accuracy = 12)
        val before = AlivePosition(50.1, 8.67, at = now - 600_000L)
        assertTrue(a.core.aliveSetPosition(plan.id, place, listOf(before)))

        clock.shift = 35_000L
        a.core.aliveTick()
        eventually { b.core.aliveStatus(plan.id)?.plan?.state == AnmpAliveState.MISSED }
        // „Nicht bestätigt“ alone is no call yet: the helpers first get the quiet hint.
        assertTrue(a.core.sosCalls().isEmpty() && b.core.sosCalls().isEmpty())
        assertEquals(plan.due + 120_000L, a.core.aliveStatus(plan.id)!!.nextAt)

        clock.shift = 125_000L
        a.core.aliveTick()
        val id = a.core.aliveCallId(plan.id, plan.due)
        val own = a.core.sosCalls().single()
        assertEquals(id, own.id)
        assertTrue(own.own && !own.ended)
        assertEquals(plan.id, own.plan)
        assertEquals(listOf(circle.id), own.circles)
        // A second look sends no second call.
        a.core.aliveTick()
        assertEquals(1, a.core.sosCalls().size)

        eventually { b.core.sosCalls().isNotEmpty() }
        val seen = b.core.sosCalls().single()
        assertEquals(id, seen.id)
        assertEquals(plan.id, seen.plan)
        assertEquals(AliveStatus.REASON_MISSED, seen.reason)
        assertEquals(circle.id, seen.via)
        assertEquals(a.info.personId, seen.owner)
        assertEquals(50.1106, assertNotNull(seen.position).latitude, 0.00001)
        assertEquals(1, seen.trail.size)
        // The caller sees with whom it arrived, as for every call.
        eventually { a.core.sosCalls().single().arrived == listOf(b.info.personId) }

        // „Ich kümmere mich“ is „Ich schaue nach“ for those who only know the sign of life.
        assertTrue(b.core.careSos(id))
        eventually { a.core.sosCalls().single().caring == listOf(b.info.personId) }
        // (The helper's clock is behind the shifted one of the phone here, so look at the answer itself.)
        eventually { a.core.aliveStatus(plan.id)?.plan?.care?.any { it.person == b.info.personId && it.state == AnmpCareState.LOOKING } == true }

        // „Alles gut“ on the call is the confirmation of the sign of life: both are fine again.
        assertTrue(a.core.endSos(id))
        assertTrue(a.core.sosCalls().single().ended)
        assertEquals(AnmpAliveState.OK, a.core.aliveStatus(plan.id)!!.plan.state)
        eventually { b.core.sosCalls().single().ended }
        eventually { b.core.aliveStatus(plan.id)?.plan?.state == AnmpAliveState.OK }
    }

    @Test
    fun theNotificationExtensionWaitsForTheCallOfAPhoneThatCallsItself() = world {
        val clock = ShiftClock()
        val (a, b, circle) = pair(clock)
        val now = clock.nowMillis()
        val plan = assertNotNull(a.core.startAlive(circle.id, "Unterwegs", until = now + 3_600_000L, quiet = AliveLogic.MIN_QUIET, firstDue = now, hint = 60_000L, alarm = 120_000L))
        b.await<CoreEvent.AliveChanged> { it.planId == plan.id }
        // Ben's iPhone is locked: only the notification extension runs (ADR 0117).
        b.core.stop()
        val nse = AnmpCore(scope, net.config(), AnmpPlatform(b.secrets, b.store, b.blobs, connector = net.connector))
        nse.open()
        nse.wakeFetch()

        clock.shift = 35_000L
        a.core.aliveTick()
        val missed = mutableListOf<CoreEvent.AliveWake>()
        eventually { missed += nse.wakeFetch().filterIsInstance<CoreEvent.AliveWake>(); missed.isNotEmpty() }
        // „Nicht bestätigt“ says that the phone will call itself: the extension has no reason to ring yet.
        assertEquals(AnmpAliveState.MISSED, missed.single().alive.state)
        assertTrue(missed.single().alive.calls)

        clock.shift = 125_000L
        a.core.aliveTick()
        val calls = mutableListOf<CoreEvent.SosWake>()
        eventually { calls += nse.wakeFetch().filterIsInstance<CoreEvent.SosWake>(); calls.isNotEmpty() }
        val call = calls.single()
        assertEquals(a.core.aliveCallId(plan.id, plan.due), call.id)
        assertEquals(missed.single().call, call.id)
        assertEquals(plan.id, call.plan)
        assertEquals(circle.id, call.circleId)
        assertEquals(a.info.personId, call.owner)
    }

    @Test
    fun theHelpersDeadlineAndTheAlarmOfTheRelaysAreOneCall() = world {
        val late = ShiftClock()
        val (a, b, circle) = pair(helperClock = late)
        val now = CoreClock.SYSTEM.nowMillis()
        val space = spaceOf(a, circle)
        val plan = assertNotNull(a.core.startAlive(circle.id, "Unterwegs", until = now + 3_600_000L, quiet = AliveLogic.MIN_QUIET, hint = 60_000L, alarm = 120_000L))
        b.await<CoreEvent.AliveChanged> { it.planId == plan.id }
        val place = AlivePosition(50.110649, 8.682127, at = now - 60_000L, accuracy = 12)
        assertTrue(a.core.aliveSetPosition(plan.id, place, listOf(AlivePosition(50.1, 8.67, at = now - 600_000L))))
        kotlinx.coroutines.delay(1100)
        assertTrue(a.core.confirmAlive(plan.id))
        eventually { b.core.aliveStatus(plan.id)?.plan?.state == AnmpAliveState.OK }
        eventually { heldAt(space).all { it.size == 1 } }
        a.core.stop()

        // Nothing comes any more. The helper's own deadline runs out: it opens the call itself, without any packet.
        val known = b.core.aliveStatus(plan.id)!!.plan
        b.core.aliveTick()
        assertTrue(b.core.sosCalls().isEmpty())
        // After the quiet hint the helper says „Ich schaue nach“: it already looks after the call that follows.
        late.shift = known.due + 61_000L - now
        assertTrue(b.core.careAlive(plan.id, AnmpCareState.LOOKING))
        late.shift = known.due + known.alarm + 1_000L - now
        b.core.aliveTick()
        assertEquals(listOf(b.info.personId), b.core.sosCalls().single().caring)
        val id = b.core.aliveCallId(plan.id, known.due)
        val local = b.core.sosCalls().single()
        assertEquals(id, local.id)
        assertEquals(AliveStatus.REASON_UNREACHABLE, local.reason)
        assertEquals(plan.id, local.plan)
        assertEquals(circle.id, local.via)
        assertNull(local.position)

        // The relays release what the phone left: the same call, now with the place and where the phone was before.
        assertTrue(releaseAll() >= 1)
        eventually { b.core.sosCalls().single().position != null }
        val full = b.core.sosCalls().single()
        assertEquals(id, full.id)
        assertEquals(50.1106, full.position!!.latitude, 0.00001)
        assertEquals(1, full.trail.size)
        assertTrue(!full.ended)
        b.core.aliveTick()
        assertEquals(1, b.core.sosCalls().size)
    }

    @Test
    fun aHelperWhoCannotLookGetsNoCall() = world {
        val clock = ShiftClock()
        val (a, b, circle) = pair(clock)
        val now = clock.nowMillis()
        val plan = assertNotNull(a.core.startAlive(circle.id, "Unterwegs", until = now + 3_600_000L, quiet = AliveLogic.MIN_QUIET, firstDue = now, hint = 60_000L, alarm = 120_000L))
        b.await<CoreEvent.AliveChanged> { it.planId == plan.id }
        assertTrue(b.core.careAlive(plan.id, AnmpCareState.AWAY))
        eventually { a.core.aliveStatus(plan.id)?.away == listOf(b.info.personId) }
        clock.shift = 35_000L
        a.core.aliveTick()
        clock.shift = 125_000L
        a.core.aliveTick()
        assertEquals(1, a.core.sosCalls().size)
        // A message after the call shows that the helper read past it.
        a.core.sendCircle(circle.id, "Hallo")
        b.await<CoreEvent.Message> { it.text == "Hallo" }
        b.core.aliveTick()
        assertTrue(b.core.sosCalls().isEmpty())
    }

    // --- A sign of life for one person instead of a circle (ADR 0121 §23) ---

    @Test
    fun aSignOfLifeForOnePersonReachesOnlyThatPersonAndTurnsIntoACallForIt() = world {
        val clock = ShiftClock()
        val (a, b, circle) = pair(clock)
        // Carla is in the same circle and must not learn of it.
        val c = device("Carla")
        c.core.joinCircle(a.core.inviteLink(circle.id)!!)
        a.await<CoreEvent.MemberUpdated> { it.member.deviceId == c.deviceId }
        val now = clock.nowMillis()
        // Not for oneself and not for someone unknown.
        assertNull(a.core.startAlive("", "Unterwegs", until = now + 3_600_000L, quiet = AliveLogic.MIN_QUIET, person = a.info.personId))
        val plan = assertNotNull(a.core.startAlive("", "Unterwegs", until = now + 3_600_000L, quiet = AliveLogic.MIN_QUIET, firstDue = now, hint = 60_000L, alarm = 120_000L, person = b.info.personId))
        assertEquals("", plan.circleId)
        assertEquals(b.info.personId, plan.peer)

        b.await<CoreEvent.AliveChanged> { it.planId == plan.id }
        val seen = b.core.aliveStatus(plan.id)!!
        assertTrue(!seen.own)
        assertEquals(a.info.personId, seen.plan.owner)
        assertEquals(a.info.personId, seen.plan.peer)
        assertEquals("", seen.plan.circleId)
        // The owner sees that it arrived, as in a circle.
        eventually { a.core.aliveStatus(plan.id)?.plan?.heard?.any { it.person == b.info.personId } == true }

        // The helper answers; the answer goes back to the person alone.
        assertTrue(b.core.careAlive(plan.id, AnmpCareState.LOOKING))
        eventually { a.core.aliveStatus(plan.id)?.plan?.care?.any { it.person == b.info.personId && it.state == AnmpCareState.LOOKING } == true }

        // Nobody confirms: the call goes to that person directly, not into a circle.
        clock.shift = 35_000L
        a.core.aliveTick()
        eventually { b.core.aliveStatus(plan.id)?.plan?.state == AnmpAliveState.MISSED }
        clock.shift = 125_000L
        a.core.aliveTick()
        val own = a.core.sosCalls().single()
        assertEquals(emptyList(), own.circles)
        assertEquals(listOf(b.info.personId), own.persons)
        eventually { b.core.sosCalls().isNotEmpty() }
        val call = b.core.sosCalls().single()
        assertEquals(own.id, call.id)
        assertEquals(plan.id, call.plan)
        assertNull(call.via)
        assertTrue(b.core.careSos(call.id))
        eventually { a.core.sosCalls().single().caring == listOf(b.info.personId) }

        // „Alles gut“ ends both again.
        assertTrue(a.core.confirmAlive(plan.id))
        eventually { b.core.sosCalls().single().ended }

        // Carla read past all of it and knows nothing.
        a.core.sendCircle(circle.id, "Hallo")
        c.await<CoreEvent.Message> { it.text == "Hallo" }
        assertTrue(c.core.alivePlans().isEmpty() && c.core.sosCalls().isEmpty())
    }

    @Test
    fun aPhoneThatGoesSilentIsReportedToTheOnePersonFromThePlansOwnSpace() = world {
        val (a, b, circle) = pair(helper = patient)
        val now = CoreClock.SYSTEM.nowMillis()
        val plan = assertNotNull(a.core.startAlive("", "Unterwegs", until = now + 3_600_000L, quiet = AliveLogic.MIN_QUIET, hint = 60_000L, alarm = 120_000L, person = b.info.personId))
        b.await<CoreEvent.AliveChanged> { it.planId == plan.id }
        // Both derive the same space from the secret in the packets; nothing of it lies in the circle or the mailbox.
        val space = CircleKeys.of(assertNotNull(a.core.alivePlans().single().watch)).space
        assertEquals(a.core.alivePlans().single().watch, b.core.alivePlans().single().watch)
        assertTrue(space != spaceOf(a, circle))
        eventually { heldAt(space).all { it.size == 1 } }
        assertTrue(heldAt(spaceOf(a, circle)).all { it.isEmpty() })
        val first = heldAt(space).first().single()

        // „Alles gut“: a new alarm, and the old one is deleted, which a writer could not do in someone else's mailbox.
        assertTrue(a.core.aliveSetPosition(plan.id, AlivePosition(50.110649, 8.682127, at = now - 120_000L, accuracy = 12)))
        kotlinx.coroutines.delay(1100)
        assertTrue(a.core.confirmAlive(plan.id))
        eventually { b.core.aliveStatus(plan.id)?.plan?.state == AnmpAliveState.OK }
        eventually { heldAt(space).all { h -> h.size == 1 && h.single().meta.id != first.meta.id } }
        eventually { net.holders(space.toString()).none { it.has(space, first.meta.id) } }

        // The phone is switched off. The relays release the alarm; the one person gets the call, with the place.
        a.core.stop()
        assertTrue(releaseAll() >= 1)
        eventually { b.core.aliveStatus(plan.id)?.plan?.guard == true }
        eventually { b.core.sosCalls().isNotEmpty() }
        val call = b.core.sosCalls().single()
        assertEquals(b.core.aliveCallId(plan.id, b.core.aliveStatus(plan.id)!!.plan.due), call.id)
        assertEquals(AliveStatus.REASON_UNREACHABLE, call.reason)
        assertNull(call.via)
        assertEquals(50.1106, assertNotNull(call.position).latitude, 0.00001)
    }

    @Test
    fun theAlarmOfAPlanForOnePersonIsNotTakenAsADirectPacket() = world {
        val (a, b, _) = pair(helper = patient)
        val now = CoreClock.SYSTEM.nowMillis()
        val plan = assertNotNull(a.core.startAlive("", "Unterwegs", until = now + 3_600_000L, quiet = AliveLogic.MIN_QUIET, hint = 60_000L, alarm = 120_000L, person = b.info.personId))
        b.await<CoreEvent.AliveChanged> { it.planId == plan.id }
        // „Bin fertig“: nothing stays behind in the plan's space, and the helper stops reading it.
        val space = CircleKeys.of(assertNotNull(a.core.alivePlans().single().watch)).space
        eventually { heldAt(space).all { it.size == 1 } }
        assertTrue(a.core.endAlive(plan.id))
        eventually { heldAt(space).all { it.isEmpty() } }
        eventually { b.core.aliveStatus(plan.id)?.phase == AliveStatus.ENDED }
        assertEquals(0, releaseAll())
        assertTrue(b.core.sosCalls().isEmpty())
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
