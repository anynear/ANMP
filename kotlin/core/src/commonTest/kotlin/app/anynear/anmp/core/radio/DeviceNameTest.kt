package app.anynear.anmp.core.radio

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Device names within the 24-byte limit of Meshtastic 2.8 (firmware commit `c2bcec93d`). */
class DeviceNameTest {
    private fun bytes(s: String) = s.encodeToByteArray().size

    @Test
    fun longNameFitsTheLimitOfFirmware28() {
        assertEquals(24, DeviceName.LONG_NAME_MAX_BYTES)
        assertEquals(10, DeviceName.SUFFIX_MAX_BYTES)
        val longest = DeviceName.longName("x".repeat(DeviceName.SUFFIX_MAX_BYTES))
        assertEquals(24, bytes(longest))
        assertTrue(DeviceName.isValidSuffix("Wohnzimmer"))
        assertFalse(DeviceName.isValidSuffix("Fluffington"))
        assertFalse(DeviceName.isValidSuffix("Küchentür")) // 11 bytes because of the umlaut
    }

    @Test
    fun everySuggestionFits() {
        for (node in listOf(0, 7, 4711, 0x9E9D1998.toInt(), -1) + (0 until 2000).map { it * 7919 }) {
            val suffix = DeviceName.derived(node)
            assertTrue(DeviceName.isValidSuffix(suffix), suffix)
            assertTrue(bytes(DeviceName.longName(suffix)) <= 24, suffix)
            assertEquals(DeviceName.longName(suffix), DeviceName.clamped(DeviceName.longName(suffix)))
        }
        repeat(500) { seed -> assertTrue(DeviceName.isValidSuffix(DeviceName.random(Random(seed)))) }
    }

    @Test
    fun suggestionsStillSpreadAndAvoidNamesNearby() {
        val node = 0x9E9D1998.toInt()
        assertTrue(DeviceName.derived(node).endsWith(" 1998"))
        val words = (0 until 20).map { DeviceName.derived(node + it).substringBefore(' ') }.toSet()
        assertTrue(words.size > 10)
        val first = DeviceName.derived(node)
        val second = DeviceName.derived(node, taken = listOf(DeviceName.longName(first)))
        assertTrue(first != second)
        assertTrue(DeviceName.isValidSuffix(second))
    }

    @Test
    fun clampedBehavesLikeTheFirmware() {
        // Observed on a Heltec V3 after updating 2.7.26 → 2.8.1 (16.11.2026).
        assertEquals("anynear.app | Fluffingto", DeviceName.clamped("anynear.app | Fluffington"))
        assertEquals("anynear.app | Küche", DeviceName.clamped("anynear.app | Küche"))
        // A two-byte character cut apart: its first byte becomes `?`.
        assertEquals("anynear.app | Kuchentü", DeviceName.clamped("anynear.app | Kuchentü")) // exactly 23 bytes
        assertEquals("anynear.app | Kuchentuu?", DeviceName.clamped("anynear.app | Kuchentuuü"))
        // A four-byte emoji cut after two bytes: both become `?`.
        assertEquals("anynear.app | abcdefgh??", DeviceName.clamped("anynear.app | abcdefgh😀"))
        assertTrue(bytes(DeviceName.clamped("anynear.app | " + "ä".repeat(20))) <= 24)
    }

    @Test
    fun storedNameCountsAsWrittenEvenWhenShortened() {
        val expected = DeviceName.longName("Küche")
        assertTrue(DeviceName.isStored(expected, expected))
        assertTrue(DeviceName.isStored("anynear.app | Fluffingto", "anynear.app | Fluffington"))
        assertFalse(DeviceName.isStored("anynear.app | Fluffin", "anynear.app | Fluffington"))
        assertFalse(DeviceName.isStored("anynear.app | Garten", expected))
        assertFalse(DeviceName.isStored(null, expected))
    }

    @Test
    fun nameShortenedByTheFirmwareStaysCurrent() {
        // A name from before the limit, shortened by 2.8, is not rewritten (no setup prompt); its own part fits.
        val stored = "anynear.app | Fluffingto"
        assertTrue(DeviceName.isCurrentName(stored))
        assertEquals("Fluffingto", DeviceName.suffixOf(stored))
        // On a 2.7 radio the long name is still whole; the editable part is cut to what fits.
        assertEquals("Fluffingto", DeviceName.suffixOf("anynear.app | Fluffington"))
        assertEquals("Rotkehlche", DeviceName.suffixOf("PINA Rotkehlchen 1998"))
        assertEquals("äääää", DeviceName.suffixOf("PINA " + "ä".repeat(17)))
    }
}
