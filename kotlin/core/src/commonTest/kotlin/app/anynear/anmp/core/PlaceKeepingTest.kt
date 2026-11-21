package app.anynear.anmp.core

import app.anynear.anmp.AnmpPlace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaceKeepingTest {
    private val point = AlivePosition(50.08, 8.24, at = 1_000_000L, accuracy = 20)
    private val known = PersonPlace("p", AnmpPlace.EXACT, point, null, 0, 1_000_000L, "Zu Hause", 900_000L)

    @Test
    fun aRepeatWithoutAPointKeepsTheLastPoint() {
        val told = PersonPlace("p", AnmpPlace.EXACT, null, null, 0, 5_000_000L)
        val kept = PlaceKeeping.merge(known, told)
        assertEquals(point, kept.position)
        assertEquals("Zu Hause", kept.label)
        assertEquals(900_000L, kept.since)
        // The message is newer, the point keeps its own time.
        assertEquals(5_000_000L, kept.at)
    }

    @Test
    fun anAreaKeepsItsRadius() {
        val area = known.copy(level = AnmpPlace.AREA, radius = 1500, label = null)
        val kept = PlaceKeeping.merge(area, PersonPlace("p", AnmpPlace.AREA, null, null, 0, 5_000_000L))
        assertEquals(1500, kept.radius)
        assertEquals(point, kept.position)
    }

    @Test
    fun aLowerLevelEndsThePoint() {
        val told = PersonPlace("p", AnmpPlace.WHEN, null, null, 0, 5_000_000L)
        assertNull(PlaceKeeping.merge(known, told).position)
    }

    @Test
    fun aNewPointReplacesTheOldOne() {
        val newer = AlivePosition(50.1, 8.3, at = 5_000_000L)
        val told = PersonPlace("p", AnmpPlace.EXACT, newer, null, 0, 5_000_000L)
        assertEquals(told, PlaceKeeping.merge(known, told))
    }

    @Test
    fun withoutAnyKnownPointNothingIsMadeUp() {
        val told = PersonPlace("p", AnmpPlace.EXACT, null, null, 0, 5_000_000L)
        assertEquals(told, PlaceKeeping.merge(null, told))
        assertEquals(told, PlaceKeeping.merge(known.copy(position = null), told))
    }
}
