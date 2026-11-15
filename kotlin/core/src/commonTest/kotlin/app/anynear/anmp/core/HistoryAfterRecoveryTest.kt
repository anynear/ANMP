package app.anynear.anmp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * After recovering with the emergency card the circle history comes from another member (ADR 0014). Messages of the old
 * phone stay the person's own, also once the old phone is removed (revoked), spec "Persons and devices" rule 4.
 */
class HistoryAfterRecoveryTest {
    private suspend fun World.scenario(removeOld: Boolean) {
        val phone = device("Anna")
        val support = device("Support")
        val code = assertNotNull(phone.core.createEmergencyCard())
        assertTrue(phone.core.confirmEmergencyCard(code))
        val circle = phone.core.createCircle("Support: Frage")
        assertIs<JoinResult.Joined>(support.core.joinCircle(phone.core.inviteLink(circle.id)!!))
        eventually { phone.core.membersOf(circle.id).any { it.deviceId == support.deviceId } }
        keep(phone, circle.id, phone.core.sendCircle(circle.id, "eins"))
        support.await<CoreEvent.Message> { it.text == "eins" }
        keep(support, circle.id, support.core.sendCircle(circle.id, "antwort"))
        phone.await<CoreEvent.Message> { it.text == "antwort" }
        keep(phone, circle.id, phone.core.sendCircle(circle.id, "zwei"))
        support.await<CoreEvent.Message> { it.text == "zwei" }
        kotlinx.coroutines.delay(1500)
        phone.core.stop()
        devices -= phone

        val fresh = device("Neu")
        var result: RecoverResult = RecoverResult.Later
        eventually { result = fresh.core.recoverWithCode(code); result != RecoverResult.Later }
        assertIs<RecoverResult.Recovered>(result)
        fresh.core.stop()
        devices -= fresh
        val again = device("Anna", secrets = fresh.secrets)
        assertEquals(phone.info.personId, again.info.personId)
        if (removeOld) assertTrue(again.core.removeOwnDevice(phone.deviceId))
        again.await<CoreEvent.HistoryLoaded>(Duration.parse("60s")) { it.circleId == circle.id }
        val history = again.all<CoreEvent.Message>().filter { it.history }
        assertEquals(setOf("eins", "zwei"), history.filter { it.fromPerson == again.info.personId }.map { it.text }.toSet())
        assertTrue(history.filter { it.text == "antwort" }.all { it.fromPerson == support.info.personId })
        assertEquals(again.info.personId, again.core.personOfDevice(phone.deviceId))
    }

    @Test fun ownMessagesStayOwn() = world { scenario(removeOld = false) }

    @Test fun ownMessagesStayOwnAfterRemovingTheOldPhone() = world { scenario(removeOld = true) }
}
