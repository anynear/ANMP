package app.anynear.anmp.core

import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Delivery plan of writes (ADR 0090). */
class DeliveryPlanTest {
    @Test
    fun planStepsThenSteady() {
        val plan = CoreTimings().writeRetry
        assertEquals(listOf(10.seconds, 30.seconds, 1.minutes, 2.minutes, 5.minutes, 15.minutes, 15.minutes), (1..7).map(plan::waitAfter))
    }

    @Test
    fun triesAreKeptAndTheNetworkBackSendsAtOnce() = world {
        // After the first failure the plan waits 10 minutes: only a wake-up gets the message out within the test.
        val a = device("Anna", TEST_TIMINGS.copy(writeRetry = RetryPlan(listOf(50.milliseconds), 10.minutes)))
        val circle = a.core.createCircle("Familie")
        net.relays.forEach { it.offline = true }
        val out = assertNotNull(a.core.sendCircle(circle.id, "Später"))
        eventually { a.store.getString(Transport.STORE_KEY).orEmpty().contains(""""tries":2""") }
        delay(300.milliseconds)
        assertTrue(a.none<CoreEvent.Stored>(500.milliseconds) { it.ref == out.mid })

        net.relays.forEach { it.offline = false }
        a.core.networkChanged()
        a.await<CoreEvent.Stored>(5.seconds) { it.ref == out.mid }
    }

    @Test
    fun deletedMessageIsWithdrawnFromBothQueues() = world {
        val a = device("Anna", TEST_TIMINGS.copy(writeRetry = RetryPlan(listOf(50.milliseconds), 10.minutes)))
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(assertNotNull(a.core.inviteLink(circle.id)))
        eventually { a.core.members.value.any { it.deviceId == b.deviceId } }
        val air = FakeAir()
        val ra = air.radio("Anna")
        ra.ready.value = false
        a.core.startRadio(ra, FakeRadioHost(online = false), TEST_RADIO_TIMINGS)

        // No network, no radio: the message waits on both ways; then it is deleted (for me or for everyone, ADR 0068).
        net.relays.forEach { it.offline = true }
        val out = assertNotNull(a.core.sendCircle(circle.id, "Doch nicht"))
        a.core.queueRadioText(circle.id, null, out.mid, 1_700_000_000_000, "Doch nicht")
        eventually { a.store.getString(Transport.STORE_KEY).orEmpty().contains(""""tries":""") }
        a.core.cancelSend(out.mid)
        assertTrue(a.store.getString(RadioQueue.KEY).orEmpty().isEmpty())

        // Both ways are back: nothing of it goes out.
        net.relays.forEach { it.offline = false }
        a.core.networkChanged()
        ra.ready.value = true
        assertTrue(a.none<CoreEvent.Stored>(2.seconds) { it.ref == out.mid })
        assertTrue(b.none<CoreEvent.Message>(1.seconds) { it.mid == out.mid })
        // Only asks for a patron go over radio, not the message.
        assertTrue(a.all<CoreEvent.RadioSend>().none { it.mid == out.mid })
        assertTrue(a.store.getString(Transport.STORE_KEY).orEmpty().let { !it.contains(out.mid) })
    }
}
