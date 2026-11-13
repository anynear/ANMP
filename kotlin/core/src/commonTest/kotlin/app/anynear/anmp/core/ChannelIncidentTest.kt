package app.anynear.anmp.core

import app.anynear.andp.B64
import app.anynear.anmp.AnmpAlertBody
import app.anynear.anmp.AnmpAlerts
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Incidents by `key` (ADR 0062 §4): newest counts, `ended` closes, a later alert opens a new one, quiet updates. */
class ChannelIncidentTest {
    private val now = 1_760_000_000L
    private val senderA = "aa".repeat(32)
    private val senderB = "bb".repeat(32)

    private fun stored(n: Int, ts: Long, prio: Int, key: ByteArray?, ended: Boolean = false, sender: String = senderA) =
        StoredAlert(
            "c1",
            B64.encode(AnmpAlerts.encodeBody(AnmpAlertBody(ByteArray(8) { n.toByte() }, ts, prio, text = "m$n", key = key, ended = ended))),
            sender,
            ts * 1000,
        )

    private fun id(n: Int) = ByteArray(8) { n.toByte() }.toHex()

    @Test
    fun newestOfAnIncidentReplacesTheOlderOnes() = runTest {
        val k = AnmpAlerts.alertKey("keller-wasser")
        val list = listOf(stored(1, now, AnmpAlerts.ALARM, k), stored(2, now + 10, AnmpAlerts.ALARM, k))
        val items = ChannelRules.items(list, now * 1000 + 20_000).associateBy { it.id }
        assertEquals(id(2), items.getValue(id(1)).replacedBy)
        assertNull(items.getValue(id(2)).replacedBy)
        assertEquals(AlertStatus.ACTIVE, items.getValue(id(2)).status)
        assertEquals(k.toHex(), items.getValue(id(2)).key)
    }

    @Test
    fun allClearEndsTheIncidentAndTheNextAlertOpensANewOne() = runTest {
        val k = AnmpAlerts.alertKey("keller-wasser")
        val list = listOf(
            stored(1, now, AnmpAlerts.ALARM, k),
            stored(2, now + 10, AnmpAlerts.INFO, k, ended = true),
            stored(3, now + 20, AnmpAlerts.ALARM, k),
        )
        val items = ChannelRules.items(list, now * 1000 + 30_000).associateBy { it.id }
        assertEquals(AlertStatus.ENDED, items.getValue(id(1)).status)
        assertEquals(id(2), items.getValue(id(1)).replacedBy)
        assertEquals(AlertStatus.ENDED, items.getValue(id(2)).status)
        assertNull(items.getValue(id(2)).replacedBy)
        assertEquals(AlertStatus.ACTIVE, items.getValue(id(3)).status)
        assertNull(items.getValue(id(3)).replacedBy)
    }

    @Test
    fun sameKeyOfAnotherSenderIsAnotherIncident() = runTest {
        val k = AnmpAlerts.alertKey("keller-wasser")
        val list = listOf(stored(1, now, AnmpAlerts.ALARM, k), stored(2, now + 10, AnmpAlerts.INFO, k, ended = true, sender = senderB))
        val items = ChannelRules.items(list, now * 1000 + 20_000).associateBy { it.id }
        assertEquals(AlertStatus.ACTIVE, items.getValue(id(1)).status)
        assertNull(items.getValue(id(1)).replacedBy)
    }

    @Test
    fun onlyNewIncidentsHigherPriorityAndAllClearsNotify() = runTest {
        val k = AnmpAlerts.alertKey("keller-wasser")
        val first = stored(1, now, AnmpAlerts.WARNING, k)
        val repeat = stored(2, now + 10, AnmpAlerts.WARNING, k)
        val higher = stored(3, now + 20, AnmpAlerts.ALARM, k)
        val clear = stored(4, now + 30, AnmpAlerts.INFO, k, ended = true)
        val again = stored(5, now + 40, AnmpAlerts.WARNING, k)
        val all = listOf(first, repeat, higher, clear, again)
        assertFalse(ChannelRules.quiet(all, first))
        assertTrue(ChannelRules.quiet(all, repeat))
        assertFalse(ChannelRules.quiet(all, higher))
        assertFalse(ChannelRules.quiet(all, clear))
        assertFalse(ChannelRules.quiet(all, again))
        assertFalse(ChannelRules.quiet(listOf(stored(6, now, AnmpAlerts.INFO, null), stored(7, now + 1, AnmpAlerts.INFO, null)), stored(7, now + 1, AnmpAlerts.INFO, null)))
    }
}
