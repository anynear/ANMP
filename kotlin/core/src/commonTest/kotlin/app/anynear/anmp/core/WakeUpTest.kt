package app.anynear.anmp.core

import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class WakeUpTest {
    @Test
    fun wakeUpEndsAPollWaitingInABackoff() = world {
        val a = device("Anna")
        // Ben's backoff stands for a phone asleep: after a broken connection the next try would come much later, for
        // fetching and for his own writes (ADR 0090), whose success would otherwise restart his lists.
        val b = device("Ben", TEST_TIMINGS.copy(backoffMin = 5.minutes, backoffMax = 5.minutes, writeRetry = RetryPlan(emptyList(), 5.minutes)))
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        eventually { a.core.canWriteTo(b.deviceId) && b.core.canWriteTo(a.deviceId) }

        // All relays away for longer than a long poll: every poll of Ben fails and waits out its backoff.
        net.relays.forEach { it.offline = true }
        delay(2.seconds)
        net.relays.forEach { it.offline = false }

        val out = assertNotNull(a.core.sendDirect(b.deviceId, "Bist du da?"))
        a.await<CoreEvent.Stored> { it.ref == out.mid }
        assertTrue(b.none<CoreEvent.Message>(2.seconds) { it.mid == out.mid }, "arrived without wake-up")

        b.core.wakeUp()
        b.await<CoreEvent.Message>(10.seconds) { it.mid == out.mid }
    }

    @Test
    fun wakeUpAsksForTheNetworkListAgain() = world {
        // listRefresh stays at its 6 hours: only the wake-up can bring the newer list within the test.
        val a = device("Anna", TEST_TIMINGS.copy(listRefreshSoon = 0.seconds))
        eventually { a.core.relays.value.known.size == net.list.relays.size }

        val fewer = net.list.copy(issued = net.list.issued + 1, relays = net.list.relays.dropLast(1))
        net.publish(fewer)
        delay(1.seconds)
        assertEquals(net.list.relays.size, a.core.relays.value.known.size, "list taken without wake-up")

        a.core.wakeUp()
        eventually { a.core.relays.value.known.size == fewer.relays.size }
    }
}
