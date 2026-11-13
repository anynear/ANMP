package app.anynear.anmp

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsTest {
    private val ts = 1_760_000_000L

    private suspend fun body(radios: Int = 1) =
        AnmpStats.body(Fixtures.signA, Fixtures.personSeed, ts, "ios", "0.4.2", radios, 9, listOf("nina", "nina"), 0, 12)

    @Test
    fun capsCounts() = runTest {
        val b = body(radios = 7)
        assertEquals(AnmpStats.MAX_RADIOS, b.radios)
        assertEquals(AnmpStats.MAX_CHANNELS, b.channels)
        assertEquals(1, b.devices)
        assertEquals(AnmpStats.MAX_CIRCLES, b.circles)
        assertEquals(listOf("nina"), b.curated)
        assertTrue(AnmpStats.isValid(b))
    }

    @Test
    fun signsAndVerifies() = runTest {
        val b = body()
        val report = AnmpStats.sign(b, Fixtures.signA)
        assertTrue(report.size <= AnmpStats.MAX_REPORT_BYTES)
        val opened = assertNotNull(AnmpStats.verify(report))
        assertContentEquals(b.person, opened.person)
        assertEquals("2025-10-09", opened.day)
    }

    @Test
    fun rejectsTamperedReport() = runTest {
        val report = AnmpStats.sign(body(), Fixtures.signA)
        for (i in report.indices) {
            val t = report.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertNull(AnmpStats.verify(t), "byte $i flipped is accepted")
        }
    }

    @Test
    fun sameMarkerOnEveryDeviceOfAPerson() = runTest {
        val a = AnmpStats.body(Fixtures.signA, Fixtures.personSeed, ts, "android", "0.4.2", 0, 0, emptyList(), 2, 1)
        val b = AnmpStats.body(Fixtures.signB, Fixtures.personSeed, ts, "web", "0.4.2", 0, 0, emptyList(), 2, 1)
        assertContentEquals(a.person, b.person)
        assertFalse(a.key.contentEquals(b.key))
        val nextDay = AnmpStats.body(Fixtures.signA, Fixtures.personSeed, ts + AnmpStats.DAY_SECONDS, "android", "0.4.2", 0, 0, emptyList(), 2, 1)
        assertFalse(a.person.contentEquals(nextDay.person))
        assertFalse(a.key.contentEquals(nextDay.key))
    }

    @Test
    fun versions() {
        assertEquals("0.4.2", AnmpStats.versionOf("0.4.2"))
        assertEquals("1.2.3", AnmpStats.versionOf("1.2.3.abc"))
        assertEquals("other", AnmpStats.versionOf("1.2"))
        assertEquals("other", AnmpStats.versionOf(null))
    }

    @Test
    fun days() {
        assertEquals("1970-01-01", AnmpStats.dayOf(0))
        assertEquals("1969-12-31", AnmpStats.dayOf(-1))
        assertEquals("2000-02-29", AnmpStats.dayOf(951_782_400))
        assertEquals("2026-11-05", AnmpStats.dayOf(1_793_836_800))
    }

    @Test
    fun switchReports() = runTest {
        val off = AnmpStats.signSwitch(Fixtures.signA, ts, "web", on = false)
        val opened = assertNotNull(AnmpStats.verifySwitch(off))
        assertFalse(opened.on)
        assertEquals("web", opened.platform)
        assertNull(AnmpStats.verify(off), "a switch report is no daily report")
        val on = AnmpStats.signSwitch(Fixtures.signA, ts, "web", on = true)
        assertTrue(assertNotNull(AnmpStats.verifySwitch(on)).on)
        for (i in off.indices) {
            val t = off.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertNull(AnmpStats.verifySwitch(t), "byte $i flipped is accepted")
        }
    }
}
