package app.anynear.anmp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Update rules in the radio list (ADR 0096): minimum with mandatory lead time, unknown fields ignored. */
class UpdateRuleTest {
    private val from = 1_763_650_000_000

    @Test
    fun demand() {
        val r = UpdateRule(min = 1200, from = from)
        assertEquals(UpdateDemand.None, r.demand(1200, from), "aktuell")
        assertEquals(UpdateDemand.Soon(1200, from), r.demand(1199, from - 1), "unter min, vor from")
        assertEquals(UpdateDemand.Required(1200, from), r.demand(1199, from), "unter min, ab from")
        assertEquals(UpdateDemand.None, r.demand(0, from), "unbekannter Build: nie")
    }

    @Test
    fun parse() {
        val l = RadioList.parse(
            """{"v":1,"issued":1,"regions":[],"app":{"hint":1250,"min":1200,"from":$from,"future":true},
               "web":{"min":"600","from":$from}}""",
        )
        assertEquals(UpdateRule(1200, from), l.app, "unbekannte Felder (auch hint) übersprungen")
        assertNull(l.web, "falscher Typ: Block gilt nicht")
        assertNull(RadioList.parse("""{"v":1,"issued":1,"regions":[],"app":{"min":1200}}""").app, "ohne from: nichts")
        assertNull(RadioList.parse("""{"v":1,"issued":1,"regions":[]}""").web)
    }

    @Test
    fun coreTakesSignedRadioList() = world {
        net.publishRadio(""""regions":[],"app":{"min":1200,"from":$from}""", issued = 1000)
        val a = device("Anna")
        a.core.refreshRadioList(force = true)
        eventually { a.core.radioList.value?.app == UpdateRule(1200, from) }

        net.publishRadio(""""regions":[],"app":{"min":1250,"from":$from}""", issued = 2000)
        a.core.refreshRadioList(force = true)
        eventually { a.core.radioList.value?.app?.min == 1250L }

        net.publishRadio(""""regions":[],"app":{"min":9,"from":$from}""", issued = 1500)
        a.core.refreshRadioList(force = true)
        assertEquals(1250L, a.core.radioList.value?.app?.min, "ältere Liste wird nicht genommen")

        net.publishRadio(""""regions":[],"app":{"min":1400,"from":$from}""", issued = 3000)
        a.core.refreshRadioList()
        assertEquals(1250L, a.core.radioList.value?.app?.min, "im Vordergrund höchstens alle 10 Minuten")
    }
}
