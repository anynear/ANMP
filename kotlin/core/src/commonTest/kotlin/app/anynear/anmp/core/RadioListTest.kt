package app.anynear.anmp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Content of the radio list (ADR 0054 §1b): regions, presets, communities; something unknown drops only that entry. */
class RadioListTest {
    // Grobes Viereck um Hessen, ein zweites um Bayern.
    private val text = """
        {"v":1,"id":"x","network":"y","issued":1762300000000,"future":{"a":1},
         "regions":[
          {"name":"Hessen","area":[[49.4,7.7],[51.7,7.7],[51.7,10.3],[49.4,10.3]],"preset":"SHORT_SLOW",
           "communities":[
            {"name":"Mesh Sample","channel":"Mesh Sample","psk":"AQ","preset":"SHORT_SLOW","operator":"example.org","source":"https://example.org"},
            {"name":"zu lang","channel":"Mesh Sample 2","psk":"AQ","preset":"SHORT_SLOW"},
            {"name":"falscher Schlüssel","channel":"X","psk":"AQID","preset":"SHORT_SLOW"}]},
          {"name":"Neu","area":[[0,0],[1,0],[1,1]],"preset":"VERY_NEW_PRESET"},
          {"name":"Bayern","area":[[47.2,8.9],[50.6,8.9],[50.6,13.9],[47.2,13.9]],"preset":"LONG_FAST","channel0":false}
         ]}
    """.trimIndent()

    @Test
    fun parse() {
        val l = RadioList.parse(text)
        assertEquals(1762300000000, l.issued)
        assertEquals(listOf("Hessen", "Bayern"), l.regions.map { it.name }, "unbekanntes Preset: Region übersprungen")
        val hessen = l.regions[0]
        assertTrue(hessen.channel0, "Vorgabe an")
        assertEquals(listOf("Mesh Sample"), hessen.communities.map { it.name }, "ungültige Communities übersprungen")
        assertEquals(1, hessen.communities[0].psk.size)
        assertEquals(false, l.regions[1].channel0)
    }

    @Test
    fun repeatedKey() {
        // A repeated key anywhere rejects the whole list (ANDP §1), as ANDP's check of the head does.
        assertFailsWith<Exception> { RadioList.parse(text.replace("\"issued\":", "\"issued\":1,\"issued\":")) }
        assertFailsWith<Exception> { RadioList.parse(text.replace("\"channel0\":false", "\"channel0\":false,\"channel0\":true")) }
    }

    @Test
    fun regionAt() {
        val l = RadioList.parse(text)
        assertEquals("Hessen", l.regionAt(50.11, 8.68)?.name, "Frankfurt")
        assertEquals("Bayern", l.regionAt(48.14, 11.58)?.name, "München")
        assertEquals("Hessen", l.regionAt(50.0, 9.5)?.name, "in beiden Vierecken: die erste Region gilt")
        assertNull(l.regionAt(52.52, 13.40), "Berlin liegt in keiner Region")
    }

    @Test
    fun invalid() {
        assertFailsWith<IllegalArgumentException> { RadioList.parse("""{"v":1}""") }
        assertFailsWith<IllegalArgumentException> { RadioList.parse("[]") }
        assertEquals(0, RadioList.parse("""{"issued":1,"regions":[{"name":"x","area":[[0,0],[1,1]],"preset":"LONG_FAST"}]}""").regions.size)
        assertEquals(0, RadioList.parse("""{"issued":1,"regions":[{"name":"x","area":[[0,0],[1,1],[91,0]],"preset":"LONG_FAST"}]}""").regions.size)
    }
}
