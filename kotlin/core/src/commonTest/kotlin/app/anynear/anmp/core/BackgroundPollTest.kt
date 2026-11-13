package app.anynear.anmp.core

import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class BackgroundPollTest {
    /** Spaces listed since the counters were cleared, each with the number of relays that saw a list for it. */
    private suspend fun World.relaysPerSpace(): Map<String, Int> {
        net.relays.forEach { it.lists.clear() }
        delay(3.seconds)
        return net.relays.flatMap { r -> r.lists.map { it.toString() }.distinct() }.groupingBy { it }.eachCount()
    }

    @Test
    fun inTheBackgroundEverySpaceIsListedAtOneRelay() = world {
        // Polling in the background as without the stream (ADR 0072): relays do not offer it here.
        net.relays.forEach { it.stream = false }
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        eventually { a.core.canWriteTo(b.deviceId) && b.core.canWriteTo(a.deviceId) }
        a.core.setBackground(true)

        b.core.setBackground(true)
        delay(1.seconds)
        val lean = relaysPerSpace()
        // Anna lists the same circle; only her own inbox and Ben's are certain to be listed by one device alone.
        assertTrue(lean.isNotEmpty())
        assertTrue(lean.values.all { it == 1 }, "background: $lean")

        b.core.setBackground(false)
        delay(1.seconds)
        val full = relaysPerSpace()
        assertTrue(full.values.any { it > 1 }, "visible: $full")
    }

    @Test
    fun inTheBackgroundMessagesStillArriveAlsoWhenTheListedRelayFails() = world {
        // Polling in the background as without the stream (ADR 0072): relays do not offer it here.
        net.relays.forEach { it.stream = false }
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        eventually { a.core.canWriteTo(b.deviceId) && b.core.canWriteTo(a.deviceId) }
        b.core.setBackground(true)

        val first = assertNotNull(a.core.sendDirect(b.deviceId, "Hallo"))
        b.await<CoreEvent.Message>(10.seconds) { it.mid == first.mid }

        // The relays Ben alone lists in the background go away (Anna, visible, lists her spaces and the circle at
        // every holder, so a space listed at one relay only is one of Ben's own): the next holder takes over.
        delay(1.seconds)
        val bensOwn = relaysPerSpace().filterValues { it == 1 }.keys
        val listed = net.relays.filter { r -> r.lists.any { it.toString() in bensOwn } }
        assertEquals(true, listed.size in 1..2, "Ben's own spaces at ${listed.size} relays")
        listed.forEach { it.offline = true }

        val second = assertNotNull(a.core.sendDirect(b.deviceId, "Noch da?"))
        b.await<CoreEvent.Message>(15.seconds) { it.mid == second.mid }
    }

    @Test
    fun inTheBackgroundAnEmptyListWaitsBeforeTheNextRound() = world {
        // Polling in the background as without the stream (ADR 0072): relays do not offer it here.
        net.relays.forEach { it.stream = false }
        val a = device("Anna")
        val b = device("Ben", TEST_TIMINGS.copy(backgroundPollInterval = 3.seconds))
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        eventually { a.core.canWriteTo(b.deviceId) && b.core.canWriteTo(a.deviceId) }
        b.core.setBackground(true)
        delay(1.seconds)

        // Spaces only Ben lists (Anna, visible, lists hers and the circle at every holder).
        val bensOwn = relaysPerSpace().filterValues { it == 1 }.keys
        net.relays.forEach { it.lists.clear() }
        delay(7.seconds)
        val rounds = bensOwn.associateWith { space -> net.relays.sumOf { r -> r.lists.count { it.toString() == space } } }
        // A long poll of 1 s without the pause would be about 7 rounds.
        assertTrue(rounds.values.all { it in 1..3 }, "rounds: $rounds")

        // Visible again: at once, not only after the pause.
        b.core.setBackground(false)
        val out = assertNotNull(a.core.sendDirect(b.deviceId, "Wieder da?"))
        b.await<CoreEvent.Message>(2.seconds) { it.mid == out.mid }
    }
}
