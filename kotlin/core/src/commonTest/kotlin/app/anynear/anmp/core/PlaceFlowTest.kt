package app.anynear.anmp.core

import app.anynear.andp.B64
import app.anynear.anmp.AnmpPlace
import app.anynear.anmp.AnmpSpot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR 0121 §3–§5: what a person shares of its place goes to single persons, never to the circle. */
class PlaceFlowTest {
    private suspend fun World.three(clock: CoreClock = CoreClock.SYSTEM): Triple<Device, Device, Device> {
        val a = device("Anna", clock = clock)
        val b = device("Ben")
        val c = device("Tom")
        val circle = a.core.createCircle("Familie")
        val link = a.core.inviteLink(circle.id)!!
        b.core.joinCircle(link)
        c.core.joinCircle(link)
        eventually { a.core.canWriteTo(b.deviceId) && a.core.canWriteTo(c.deviceId) && b.core.canWriteTo(a.deviceId) }
        return Triple(a, b, c)
    }

    @Test
    fun aPlaceReachesOnlyThePersonItIsSharedWith() = world {
        val (a, b, c) = three()
        val now = CoreClock.SYSTEM.nowMillis()
        val point = AlivePosition(50.110649, 8.682127, at = now - 60_000L, accuracy = 15)
        assertTrue(a.core.sharePlace(b.info.personId, AnmpPlace.EXACT, point))
        b.await<CoreEvent.PlaceChanged> { it.person == a.info.personId }
        val seen = b.core.places().single()
        assertEquals(AnmpPlace.EXACT, seen.level)
        assertEquals(50.1106, seen.position!!.latitude, 0.00001)
        assertNull(seen.radius)
        // Tom is in the same circle and knows nothing: the place is not in the circle space.
        assertTrue(c.core.places().isEmpty())

        // An area: the centre and how wide it is, never a point.
        assertTrue(a.core.sharePlace(b.info.personId, AnmpPlace.AREA, AlivePosition(50.11, 8.68, at = now), radius = 1500, untilMillis = now + 3_600_000L))
        eventually { b.core.places().single().level == AnmpPlace.AREA }
        val area = b.core.places().single()
        assertEquals(1500, area.radius)
        assertEquals((now + 3_600_000L) / 1000, area.until / 1000)
        assertTrue(!area.over(now))
        assertTrue(area.over(now + 3_600_001L + 1000))

        // At a place the person named: the name and since when go along with the point.
        assertTrue(a.core.sharePlace(b.info.personId, AnmpPlace.EXACT, point, label = "Zu Hause", sinceMillis = now - 3_600_000L))
        eventually { b.core.places().single().label == "Zu Hause" }
        assertEquals((now - 3_600_000L) / 1000, b.core.places().single().since / 1000)
        // The phone could not locate itself and repeats only the level: the last point stays, with its name.
        val before = b.core.places().single()
        assertTrue(a.core.sharePlace(b.info.personId, AnmpPlace.EXACT, null, label = "Zu Hause", untilMillis = now + 7_200_000L))
        eventually { b.core.places().single().until / 1000 == (now + 7_200_000L) / 1000 }
        assertEquals(before.position, b.core.places().single().position)
        assertEquals("Zu Hause", b.core.places().single().label)
        // With a direct message instead of a packet of its own: the reader takes it the same way.
        val later = AlivePosition(50.2, 8.7, at = CoreClock.SYSTEM.nowMillis(), accuracy = 10)
        assertNotNull(a.core.sendDirect(b.info.personId, "Bin gleich da", place = PlaceShare(AnmpPlace.EXACT, later)))
        eventually { b.core.places().single().position?.latitude?.let { kotlin.math.abs(it - 50.2) < 0.001 } == true }
        // Back to „wenn etwas ist“: the level stays visible, the place is gone.
        assertTrue(a.core.sharePlace(b.info.personId, AnmpPlace.WHEN))
        eventually { b.core.places().single().level == AnmpPlace.WHEN }
        assertNull(b.core.places().single().position)
    }

    @Test
    fun valuesOutOfRangeAreNotSentAndAnAskOnlyAsks() = world {
        val (a, b, _) = three()
        val now = CoreClock.SYSTEM.nowMillis()
        assertTrue(!a.core.sharePlace(b.info.personId, 9))
        assertTrue(!a.core.sharePlace(b.info.personId, AnmpPlace.EXACT, AlivePosition(91.0, 0.0, at = now)))
        assertTrue(!a.core.sharePlace(b.info.personId, AnmpPlace.AREA, AlivePosition(50.0, 8.0, at = now), radius = AnmpPlace.MAX_RADIUS + 1))
        assertTrue(!a.core.sharePlace("unbekannt", AnmpPlace.WHEN))

        assertTrue(b.core.askPlace(a.info.personId))
        val asked = a.await<CoreEvent.PlaceAsked> { it.person == b.info.personId }
        assertNotNull(asked)
        // Asking shows nothing and answers nothing by itself.
        assertTrue(a.core.places().isEmpty())
        assertTrue(b.core.places().isEmpty())

        assertTrue(a.core.sharePlace(b.info.personId, AnmpPlace.EXACT, AlivePosition(50.0, 8.0, at = now)))
        eventually { b.core.places().isNotEmpty() }
        assertTrue(b.core.forgetPlace(a.info.personId))
        assertTrue(b.core.places().isEmpty())
    }

    /** ADR 0121 §8: a meeting point in a circle reaches everyone in it; „Wo bist du?“ and the answer go to one person. */
    @Test
    fun aMeetingPointAndTheQuestionWhereSomeoneIsAreMessages() = world {
        val (a, b, c) = three()
        val now = CoreClock.SYSTEM.nowMillis()
        val circle = a.core.circles.value.single()
        val point = MessageSpot(AnmpSpot.MEET, AlivePosition(50.110649, 8.682127, at = now), "Haupteingang")
        assertNotNull(a.core.sendCircle(circle.id, "Treffpunkt: Haupteingang", spot = point))
        for (d in listOf(b, c)) {
            val m = d.await<CoreEvent.Message> { it.spot != null }
            assertEquals(AnmpSpot.MEET, m.spot!!.kind)
            assertEquals("Haupteingang", m.spot!!.label)
            assertEquals(50.1106, m.spot!!.position!!.latitude, 0.00001)
            assertEquals("Treffpunkt: Haupteingang", m.text)
        }
        assertNotNull(b.core.sendDirect(a.info.personId, "Wo bist du?", spot = MessageSpot(AnmpSpot.ASK)))
        val asked = a.await<CoreEvent.Message> { it.spot?.kind == AnmpSpot.ASK }
        assertNull(asked.spot!!.position)
        assertNotNull(a.core.sendDirect(b.info.personId, "Ich bin hier", spot = MessageSpot(AnmpSpot.HERE, AlivePosition(50.2, 8.7, at = now, accuracy = 8))))
        assertEquals(8, b.await<CoreEvent.Message> { it.spot?.kind == AnmpSpot.HERE }.spot!!.position!!.accuracy)
        // „Alle teilen“: an invitation with an end, without a place; without the end it is none.
        assertNotNull(a.core.sendCircle(circle.id, "Alle teilen bis 23 Uhr", spot = MessageSpot(AnmpSpot.ALL, until = now + 7_200_000L)))
        val invite = b.await<CoreEvent.Message> { it.spot?.kind == AnmpSpot.ALL }
        assertEquals((now + 7_200_000L) / 1000, invite.spot!!.until!! / 1000)
        assertNull(invite.spot!!.position)
        assertNotNull(a.core.sendCircle(circle.id, "ohne Ende", spot = MessageSpot(AnmpSpot.ALL)))
        assertNull(b.await<CoreEvent.Message> { it.text == "ohne Ende" }.spot)
        // A meeting point without a place is not one: the message arrives as plain text.
        assertNotNull(a.core.sendCircle(circle.id, "ohne Ort", spot = MessageSpot(AnmpSpot.MEET)))
        assertNull(b.await<CoreEvent.Message> { it.text == "ohne Ort" }.spot)
    }

    /** ADR 0083 §2–§4: a call for the helpers goes into circles and to single persons; helpers answer; the all-clear ends it. */
    @Test
    fun thePlacesOfACircleAreKnownToItsMembersAndTheNewestListCounts() = world {
        val (a, b, c) = three()
        val circle = a.core.circles.value.single()
        val home = CirclePlace(B64.encode(ByteArray(8) { 1 }), "Zu Hause", 50.1106, 8.6821)
        val garden = CirclePlace(B64.encode(ByteArray(8) { 2 }), "Garten", 50.2, 8.7, radius = 80)
        assertTrue(a.core.setCirclePlaces(circle.id, listOf(home)))
        eventually { b.core.circlePlaces(circle.id).map { it.name } == listOf("Zu Hause") && c.core.circlePlaces(circle.id).size == 1 }
        // Every member may change the list; the newest one counts for all.
        assertTrue(b.core.setCirclePlaces(circle.id, listOf(home, garden)))
        eventually { a.core.circlePlaces(circle.id).size == 2 && c.core.circlePlaces(circle.id).size == 2 }
        assertEquals(80, a.core.circlePlaces(circle.id).first { it.name == "Garten" }.radius)
        // Renaming the circle does not touch them.
        assertTrue(c.core.renameCircle(circle.id, "Familie Neu"))
        eventually { a.core.circles.value.single().name == "Familie Neu" }
        assertEquals(2, a.core.circlePlaces(circle.id).size)
        // Out of range: not taken.
        assertTrue(!a.core.setCirclePlaces(circle.id, listOf(home.copy(radius = 0))))
        assertTrue(!a.core.setCirclePlaces(circle.id, listOf(home.copy(id = "x"))))
        assertTrue(!a.core.setCirclePlaces("nope", listOf(home)))
    }

    /** ADR 0121 §25: a question nobody answered may have been dropped by an app that did not know it; it is asked again after a day. */
    @Test
    fun aContactWhoDidNotAnswerIsAskedAgainAfterADay() = world {
        val clock = object : CoreClock {
            var shift = 0L

            override fun nowMillis(): Long = CoreClock.SYSTEM.nowMillis() + shift
        }
        val (a, b, c) = three(clock)
        val circle = a.core.circles.value.single()
        assertTrue(a.core.askSosContacts(listOf(circle.id), emptyList()))
        eventually { b.core.sosAsks().size == 1 && c.core.sosAsks().size == 1 }
        assertTrue(b.core.answerSosAsk(a.info.personId, true))
        eventually { a.core.sosContacts().first { it.person == b.info.personId }.state == SosContact.AGREED }

        // Within the day nothing goes out again.
        clock.shift = AnmpCore.SOS_REASK_MILLIS - 60_000L
        assertTrue(!a.core.askSosContacts(listOf(circle.id), emptyList()))
        // After it Tom, who did not answer, is asked again; Ben's yes stays and he is listed once.
        clock.shift = AnmpCore.SOS_REASK_MILLIS + 60_000L
        assertTrue(a.core.askSosContacts(listOf(circle.id), emptyList()))
        assertEquals(SosContact.AGREED, a.core.sosContacts().single { it.person == b.info.personId }.state)
        assertEquals(SosContact.ASKED, a.core.sosContacts().single { it.person == c.info.personId }.state)
        // Asked just now: not again right away.
        assertTrue(!a.core.askSosContacts(listOf(circle.id), emptyList()))
    }

    @Test
    fun beingAContactForCallsNeedsAYesOnce() = world {
        val (a, b, c) = three()
        val circle = a.core.circles.value.single()
        assertTrue(a.core.askSosContacts(listOf(circle.id), emptyList()))
        b.await<CoreEvent.SosAsked> { it.person == a.info.personId }
        eventually { c.core.sosAsks().any { it.person == a.info.personId } }
        assertEquals(circle.id, b.core.sosAsks().single().circleId)
        // Anna sees whom she asked; nobody answered yet.
        assertEquals(setOf(b.info.personId, c.info.personId), a.core.sosContacts().map { it.person }.toSet())
        assertTrue(a.core.sosContacts().all { it.state == SosContact.ASKED })

        // Ben says yes, Tom says no: both answers reach Anna alone.
        assertTrue(b.core.answerSosAsk(a.info.personId, true))
        assertTrue(c.core.answerSosAsk(a.info.personId, false))
        eventually { a.core.sosContacts().first { it.person == b.info.personId }.state == SosContact.AGREED }
        eventually { a.core.sosContacts().first { it.person == c.info.personId }.state == SosContact.REFUSED }
        assertEquals(listOf(a.info.personId), b.core.sosAgreed())
        assertTrue(c.core.sosAgreed().isEmpty())
        assertTrue(b.core.sosAsks().isEmpty() && c.core.sosAsks().isEmpty())
        // Nobody else learned an answer.
        assertTrue(c.core.sosContacts().isEmpty())

        // Asked once: a second time nothing goes out, unless on purpose. Ben then answers by himself.
        assertTrue(!a.core.askSosContacts(listOf(circle.id), emptyList()))
        assertTrue(a.core.askSosContacts(listOf(circle.id), emptyList(), again = true))
        eventually { c.core.sosAsks().size == 1 }
        assertTrue(b.core.sosAsks().isEmpty())

        // Ben takes his yes back.
        assertTrue(b.core.answerSosAsk(a.info.personId, false))
        eventually { a.core.sosContacts().first { it.person == b.info.personId }.state == SosContact.REFUSED }
        assertTrue(b.core.sosAgreed().isEmpty())
        // An answer from someone Anna never asked is nothing.
        assertTrue(a.core.answerSosAsk(b.info.personId, true))
        eventually { a.core.sosAgreed() == listOf(b.info.personId) }
        assertTrue(b.core.sosContacts().isEmpty())
    }

    @Test
    fun anAnswerOnOneOwnDeviceHoldsOnTheOthers() = world {
        val (a, b, _) = three()
        val tablet = addTablet(b)
        val circle = a.core.circles.value.single()
        eventually { a.core.membersOf(circle.id).any { it.deviceId == tablet.deviceId } }
        assertTrue(a.core.askSosContacts(emptyList(), listOf(b.info.personId)))
        eventually { b.core.sosAsks().any { it.person == a.info.personId } && tablet.core.sosAsks().any { it.person == a.info.personId } }

        // Ben says yes in the browser: his phone rings for Anna from now on, and the question is gone there.
        assertTrue(tablet.core.answerSosAsk(a.info.personId, true))
        eventually { b.core.profileSync(); b.core.sosAgreed() == listOf(a.info.personId) }
        assertTrue(b.core.sosAsks().isEmpty())
        eventually { a.core.sosContacts().firstOrNull { it.person == b.info.personId }?.state == SosContact.AGREED }

        // He takes it back on the phone: the browser knows, too.
        assertTrue(b.core.answerSosAsk(a.info.personId, false))
        eventually { tablet.core.profileSync(); tablet.core.sosAgreed().isEmpty() }
    }

    @Test
    fun aCallReachesTheHelpersAndTheySayWhoLooksAfterIt() = world {
        val (a, b, c) = three()
        val d = device("Dora")
        // Dora is a contact of Anna, in no circle with her.
        assertNotNull(d.core.addContact(a.core.publicContactLink()))
        assertNotNull(d.core.sendDirect(a.deviceId, "Hallo"))
        a.await<CoreEvent.Message> { it.text == "Hallo" }
        eventually { a.core.canWriteTo(d.deviceId) && d.core.canWriteTo(a.deviceId) }
        val now = CoreClock.SYSTEM.nowMillis()
        val circle = a.core.circles.value.single()
        val here = AlivePosition(50.110649, 8.682127, at = now, accuracy = 9)
        val before = listOf(AlivePosition(50.10, 8.67, at = now - 600_000L), AlivePosition(50.105, 8.675, at = now - 300_000L))
        val id = assertNotNull(a.core.raiseSos(listOf(circle.id), listOf(d.info.personId, b.info.personId), here, battery = 42, trail = before))
        for (helper in listOf(b, c, d)) {
            eventually { helper.core.sosCalls().any { it.id == id } }
            val call = helper.core.sosCalls().single()
            assertEquals(a.info.personId, call.owner)
            assertTrue(!call.own && !call.ended)
            assertEquals(42, call.battery)
            assertEquals(50.1106, call.position!!.latitude, 0.00001)
            assertEquals(2, call.trail.size)
        }
        // Ben is in the circle, so he got it once, through the circle; Dora directly.
        assertEquals(circle.id, b.core.sosCalls().single().via)
        assertNull(d.core.sosCalls().single().via)
        assertEquals(listOf(d.info.personId), a.core.sosCalls().single().persons)
        // Anna sees with whom it arrived: every helper's phone confirms it.
        eventually { a.core.sosCalls().single().arrived.toSet() == setOf(b.info.personId, c.info.personId, d.info.personId) }

        // A second raise while one runs updates it instead of starting another.
        assertEquals(id, a.core.raiseSos(listOf(circle.id), emptyList(), AlivePosition(50.12, 8.69, at = now + 1000)))
        assertEquals(1, a.core.sosCalls().size)

        assertTrue(b.core.careSos(id))
        eventually { a.core.sosCalls().single().caring == listOf(b.info.personId) }
        // Tom is in the same circle and sees that Ben looks after it.
        eventually { c.core.sosCalls().single().caring == listOf(b.info.personId) }
        assertTrue(d.core.careSos(id))
        eventually { a.core.sosCalls().single().caring.toSet() == setOf(b.info.personId, d.info.personId) }
        // A helper cannot end someone else's call, and the person cannot answer its own.
        assertTrue(!b.core.endSos(id))
        assertTrue(!a.core.careSos(id))

        assertTrue(a.core.endSos(id))
        for (helper in listOf(b, c, d)) eventually { helper.core.sosCalls().single().ended }
        assertTrue(!a.core.updateSos(id, here))
    }
}
