package app.anynear.anmp.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ADR 0118: a passed on („weitergeleitet“) message carries field 38, in circle and direct messages and own copies. */
class PassedOnTest {
    @Test
    fun passedOnMessagesCarryTheMark() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        a.await<CoreEvent.MemberUpdated> { it.member.deviceId == b.deviceId }
        eventually { a.core.canWriteTo(b.deviceId) && b.core.canWriteTo(a.deviceId) }

        val plain = assertNotNull(a.core.sendCircle(circle.id, "Selbst geschrieben"))
        assertFalse(b.await<CoreEvent.Message> { it.mid == plain.mid }.passedOn)

        val passed = assertNotNull(a.core.sendCircle(circle.id, "Weitergeleitet", passedOn = true))
        assertTrue(b.await<CoreEvent.Message> { it.mid == passed.mid }.passedOn)

        val direct = assertNotNull(b.core.sendDirect(a.deviceId, "Auch direkt", passedOn = true))
        assertTrue(a.await<CoreEvent.Message> { it.mid == direct.mid }.passedOn)
    }
}
