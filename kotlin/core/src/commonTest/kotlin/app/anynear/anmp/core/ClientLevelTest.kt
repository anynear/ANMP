package app.anynear.anmp.core

import kotlin.test.Test
import kotlin.test.assertFalse

/** Minimum version (ANDP §5.2, ADR 0061 decision 13): if the relays ask for a higher client level, the core reports it. */
class ClientLevelTest {
    @Test
    fun updateNeededFollowsClientsMin() = world {
        net.relays.forEach { it.minClient = AnmpCoreConfig.CLIENT_LEVEL }
        val a = device("Anna")
        eventually { net.relays.any { it.infoCalls > 0 } }
        assertFalse(a.core.updateNeeded.value, "gleicher Level: kein Update nötig")

        net.relays.forEach { it.minClient = AnmpCoreConfig.CLIENT_LEVEL + 1 }
        a.core.refreshClientLevel()
        eventually { a.core.updateNeeded.value }
    }
}
