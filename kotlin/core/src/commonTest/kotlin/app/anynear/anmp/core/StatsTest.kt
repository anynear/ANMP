package app.anynear.anmp.core

import app.anynear.anmp.AnmpStats
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Daily usage report (ADR 0045): signed, counted values, both devices of a person at the same relay. */
class StatsTest {
    @Test
    fun devicesOfOnePersonReportToTheSameRelay() = world {
        val phone = device("Anna")
        phone.core.createCircle("Familie")
        val tablet = addTablet(phone)
        eventually { tablet.core.ownDevices.value.size == 2 }

        assertTrue(phone.core.sendStats("android", "0.4.2", 1, emptyMap()))
        assertTrue(tablet.core.sendStats("ios", "0.4.2-beta.3", 0, emptyMap()))

        val holders = net.relays.filter { it.statsReports.isNotEmpty() }
        assertEquals(1, holders.size, "both reports at one relay")
        val (a, b) = holders.single().statsReports.map { assertNotNull(AnmpStats.verify(it)) }
        assertContentEquals(a.person, b.person)
        assertNotEquals(a.key.toList(), b.key.toList())
        assertEquals("android", a.platform)
        assertEquals(1, a.radios)
        assertEquals(2, a.devices)
        assertEquals(1, a.circles)
        assertEquals("ios", b.platform)
        assertEquals("0.4.2", b.version)
    }

    @Test
    fun nextRelayWhenOneIsDown() = world {
        val phone = device("Anna")
        assertTrue(phone.core.sendStats("android", "0.4.2", 0, emptyMap()))
        val first = net.relays.single { it.statsReports.isNotEmpty() }
        first.offline = true
        assertTrue(phone.core.sendStats("android", "0.4.2", 0, emptyMap()))
        assertEquals(1, net.relays.count { it !== first && it.statsReports.isNotEmpty() })
    }

    @Test
    fun switchReportCarriesOnlyTheChange() = world {
        val phone = device("Anna")
        assertTrue(phone.core.sendStatsSwitch("android", on = false))
        val report = net.relays.flatMap { it.statsReports }.single()
        val sw = assertNotNull(AnmpStats.verifySwitch(report))
        assertEquals(false, sw.on)
        assertEquals("android", sw.platform)
        assertEquals(null, AnmpStats.verify(report))
    }
}
