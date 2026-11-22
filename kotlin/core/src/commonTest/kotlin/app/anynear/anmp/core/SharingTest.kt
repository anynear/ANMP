package app.anynear.anmp.core

import app.anynear.anmp.AnmpPlace
import app.anynear.anmp.AnmpSpot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ADR 0123: a sharing of the place is a question and a yes; either side ends it for both. */
class SharingTest {
    private val t = 1_800_000_000_000L
    private val none = PlaceSharing("anna")

    @Test
    fun aYesMakesItActiveAtTheLevelAsked() {
        val asked = none.take(byUs = true, kind = AnmpSpot.SHARE, level = AnmpPlace.EXACT, at = t)
        assertTrue(asked.open && !asked.active && asked.askedByUs)
        val yes = asked.take(byUs = false, kind = AnmpSpot.SHARED, level = AnmpPlace.EXACT, at = t + 1000)
        assertEquals(AnmpPlace.EXACT, yes.level)
        assertTrue(!yes.open)
        // An older app answered „Nur ungefähr“: the lower level counts.
        assertEquals(AnmpPlace.AREA, asked.take(false, AnmpSpot.SHARED, AnmpPlace.AREA, t + 1000).level)
    }

    @Test
    fun aNoOrAWithdrawalKeepsWhatHeld() {
        val active = none.take(true, AnmpSpot.SHARE, AnmpPlace.AREA, t).take(false, AnmpSpot.SHARED, AnmpPlace.AREA, t + 1000)
        // Asking for more: the sharing at „Ungefähr“ holds meanwhile, and after a no.
        val more = active.take(true, AnmpSpot.SHARE, AnmpPlace.EXACT, t + 2000)
        assertEquals(AnmpPlace.AREA, more.level)
        assertTrue(more.open)
        val no = more.take(false, AnmpSpot.SHARED, 0, t + 3000)
        assertEquals(AnmpPlace.AREA, no.level)
        assertTrue(!no.open)
        // The same no read again changes nothing; it does not end the sharing.
        assertEquals(no, no.take(false, AnmpSpot.SHARED, 0, t + 3000))
        // Withdrawn: only the own question goes, and only by the one who asked.
        assertEquals(more, more.take(false, AnmpSpot.WITHDRAWN, null, t + 3000))
        val back = more.take(true, AnmpSpot.WITHDRAWN, null, t + 3000)
        assertEquals(AnmpPlace.AREA, back.level)
        assertTrue(!back.open)
    }

    @Test
    fun eitherSideEndsItForBothAndAnOlderCardChangesNothing() {
        val active = none.take(false, AnmpSpot.SHARE, AnmpPlace.EXACT, t).take(true, AnmpSpot.SHARED, AnmpPlace.EXACT, t + 1000)
        assertEquals(AnmpPlace.EXACT, active.level)
        val ended = active.take(false, AnmpSpot.ENDED, null, t + 2000)
        assertTrue(!ended.active && !ended.open)
        assertEquals(ended, ended.take(false, AnmpSpot.SHARED, AnmpPlace.EXACT, t + 1000))
        // Both asked before either answered: the lower level is agreed at once.
        val both = none.take(true, AnmpSpot.SHARE, AnmpPlace.EXACT, t).take(false, AnmpSpot.SHARE, AnmpPlace.AREA, t + 500)
        assertEquals(AnmpPlace.AREA, both.level)
        // An older app lowers or ends without a question.
        assertEquals(AnmpPlace.AREA, active.take(false, AnmpSpot.SHARED, AnmpPlace.AREA, t + 2000).level)
        assertEquals(0, active.take(false, AnmpSpot.SHARED, 0, t + 2000).level)
    }

    private suspend fun World.pair(): Pair<Device, Device> {
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        eventually { a.core.canWriteTo(b.deviceId) && b.core.canWriteTo(a.deviceId) }
        return a to b
    }

    @Test
    fun bothSidesAndTheOwnDevicesKeepTheSameState() = world {
        val (a, b) = pair()
        val tablet = addTablet(b)
        eventually { a.core.canWriteTo(tablet.deviceId) && tablet.core.canWriteTo(a.deviceId) }
        assertNotNull(a.core.sendDirect(b.info.personId, "Standort freigeben?", spot = MessageSpot(AnmpSpot.SHARE, level = AnmpPlace.EXACT)))
        assertTrue(a.core.sharing(b.info.personId).askedByUs)
        eventually { b.core.sharing(a.info.personId).asked == AnmpPlace.EXACT && tablet.core.sharing(a.info.personId).asked == AnmpPlace.EXACT }
        assertTrue(!b.core.sharing(a.info.personId).askedByUs)

        // Ben says yes on the tablet: his phone learns it from the own copy, Anna from the answer.
        assertNotNull(tablet.core.sendDirect(a.info.personId, "Einverstanden", spot = MessageSpot(AnmpSpot.SHARED, level = AnmpPlace.EXACT)))
        eventually { a.core.sharing(b.info.personId).level == AnmpPlace.EXACT }
        eventually { b.core.sharing(a.info.personId).level == AnmpPlace.EXACT }
        assertEquals(listOf(b.info.personId), a.core.sharings().map { it.person })

        // Anna ends it: over for everyone.
        assertNotNull(a.core.sendDirect(b.info.personId, "Beendet", spot = MessageSpot(AnmpSpot.ENDED)))
        eventually { !b.core.sharing(a.info.personId).active && !tablet.core.sharing(a.info.personId).active }
        assertTrue(a.core.sharings().isEmpty())
    }

    @Test
    fun aSharingTheAppKeptIsTakenOnce() = world {
        val (a, b) = pair()
        assertTrue(a.core.adoptSharing(b.info.personId, AnmpPlace.AREA))
        assertEquals(AnmpPlace.AREA, a.core.sharing(b.info.personId).level)
        assertTrue(!a.core.adoptSharing(b.info.personId, AnmpPlace.EXACT))
        assertTrue(!a.core.adoptSharing(a.info.personId, AnmpPlace.EXACT))
    }

    @Test
    fun theAskerEndsBeingAContactForCalls() = world {
        val (a, b) = pair()
        assertTrue(a.core.askSosContacts(emptyList(), listOf(b.info.personId)))
        eventually { b.core.sosAsks().any { it.person == a.info.personId } }
        assertTrue(b.core.answerSosAsk(a.info.personId, true))
        eventually { a.core.sosContacts().firstOrNull { it.person == b.info.personId }?.state == SosContact.AGREED }
        // Anna no longer counts Ben: his yes is over, he is not rung for her calls any more.
        assertTrue(a.core.endSosContact(b.info.personId))
        assertTrue(a.core.sosContacts().isEmpty())
        eventually { b.core.sosAgreed().isEmpty() }
        assertTrue(!a.core.endSosContact(b.info.personId))
    }

    @Test
    fun whoTheAskerEndedIsNotAskedAgainThroughTheCircle() = world {
        val a = device("Anna")
        val b = device("Ben")
        val c = device("Tom")
        val circle = a.core.createCircle("Familie")
        val link = a.core.inviteLink(circle.id)!!
        b.core.joinCircle(link)
        c.core.joinCircle(link)
        eventually { a.core.canWriteTo(b.deviceId) && a.core.canWriteTo(c.deviceId) }
        assertTrue(a.core.askSosContacts(listOf(circle.id), emptyList()))
        eventually { b.core.sosAsks().isNotEmpty() && c.core.sosAsks().isNotEmpty() }
        assertTrue(a.core.endSosContact(b.info.personId))
        eventually { b.core.sosAsks().isEmpty() }
        // Asked again on purpose, Ben left out: only Tom gets the question, one by one.
        assertTrue(a.core.askSosContacts(listOf(circle.id), emptyList(), again = true, except = listOf(b.info.personId)))
        eventually { a.core.sosContacts().any { it.person == c.info.personId } }
        assertTrue(a.core.sosContacts().none { it.person == b.info.personId })
        kotlinx.coroutines.delay(1000)
        assertTrue(b.core.sosAsks().isEmpty())
    }
}
