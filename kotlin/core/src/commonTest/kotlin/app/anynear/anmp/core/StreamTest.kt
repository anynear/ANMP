package app.anynear.anmp.core

import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Background reception over the stream of a home relay (ADR 0072, ANDP §10.2, §18). */
class StreamTest {
    private suspend fun World.familyOfTwo(): Pair<Device, Device> {
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        eventually { a.core.canWriteTo(b.deviceId) && b.core.canWriteTo(a.deviceId) }
        return a to b
    }

    @Test
    fun inTheBackgroundTheStreamReplacesPolling() = world {
        val (a, b) = familyOfTwo()
        b.core.setBackground(true)
        eventually { b.core.streamStats.value.connects == 1 }
        delay(1.seconds)
        val out = assertNotNull(a.core.sendDirect(b.deviceId, "Über den Stream"))
        // 15 s like the other stream tests: on slow CI machines the message sometimes took longer than 5 s.
        b.await<CoreEvent.Message>(15.seconds) { it.mid == out.mid }
        val st = b.core.streamStats.value
        assertEquals(1, st.connects, "$st")
        assertEquals(0, st.missed, "$st")
        assertTrue(st.drops.isEmpty(), "$st")
    }

    @Test
    fun benListsNothingWhileTheStreamIsQuiet() = world {
        val a = device("Anna")
        a.core.setBackground(true)
        eventually { a.core.streamStats.value.connects == 1 }
        delay(1.seconds)
        net.relays.forEach { it.lists.clear() }
        delay(3.seconds)
        assertEquals(0, net.relays.sumOf { it.lists.size }, "lists while the stream is up")
    }

    @Test
    fun aBrokenHomeRelayHandsOverAndTheCatchUpBringsWhatWasMissed() = world {
        val (a, b) = familyOfTwo()
        b.core.setBackground(true)
        eventually { b.core.streamStats.value.connects == 1 }
        val home = net.relays.first { it.relay.site == b.core.streamStats.value.home }

        // The home relay goes away: its stream ends, it does not answer any more.
        home.offline = true
        home.dropStreams()
        val out = assertNotNull(a.core.sendDirect(b.deviceId, "Während der Übergabe"))
        b.await<CoreEvent.Message>(15.seconds) { it.mid == out.mid }
        eventually { b.core.streamStats.value.connects >= 2 }
        val st = b.core.streamStats.value
        assertTrue(st.homeSwitches >= 1, "home switches: $st")
        assertTrue(st.home != home.relay.site, "still at the broken relay: $st")
        assertTrue(st.drops.values.sum() >= 1, "drops: $st")
    }

    @Test
    fun withoutClientsTheBackgroundPollsAsBefore() = world {
        net.relays.forEach { it.streamClients = false }
        val (a, b) = familyOfTwo()
        b.core.setBackground(true)
        val out = assertNotNull(a.core.sendDirect(b.deviceId, "Ohne Stream"))
        b.await<CoreEvent.Message>(10.seconds) { it.mid == out.mid }
        assertEquals(0, b.core.streamStats.value.connects)
        assertEquals(0, net.relays.sumOf { it.streamOpens })
    }

    @Test
    fun backInTheForegroundPollingStartsAgainAtOnce() = world {
        val (a, b) = familyOfTwo()
        b.core.setBackground(true)
        eventually { b.core.streamStats.value.connects == 1 }
        b.core.setBackground(false)
        val out = assertNotNull(a.core.sendDirect(b.deviceId, "Wieder da"))
        b.await<CoreEvent.Message>(3.seconds) { it.mid == out.mid }
    }

    @Test
    fun switchingClientsOnReachesARunningAppWithoutRestart() = world {
        net.relays.forEach { it.streamClients = false }
        val (a, b) = familyOfTwo()
        b.core.setBackground(true)
        delay(1.seconds)
        assertEquals(0, b.core.streamStats.value.connects)
        // The network switches the stream on; the app sees it at its next look (streamRecheck), not after an hour.
        net.relays.forEach { it.streamClients = true }
        eventually(10.seconds) { b.core.streamStats.value.connects == 1 }
        val out = assertNotNull(a.core.sendDirect(b.deviceId, "Jetzt über den Stream"))
        b.await<CoreEvent.Message>(5.seconds) { it.mid == out.mid }
    }
}
