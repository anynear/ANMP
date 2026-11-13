package app.anynear.anmp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR 0092: answers carry fields 3 and 4 (message ID and author tag), on the internet and over radio. */
class ReplyTest {
    @Test
    fun answerInCircleAndDirectCarriesTheReference() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        a.await<CoreEvent.MemberUpdated> { it.member.deviceId == b.deviceId }
        eventually { a.core.canWriteTo(b.deviceId) && b.core.canWriteTo(a.deviceId) }

        val first = assertNotNull(a.core.sendCircle(circle.id, "Wer kommt mit?"))
        b.await<CoreEvent.Message> { it.mid == first.mid }
        val answer = assertNotNull(b.core.sendCircle(circle.id, "Ich!", replyTo = first.mid, replyFrom = a.info.tag))
        val got = a.await<CoreEvent.Message> { it.mid == answer.mid }
        assertEquals(first.mid, got.replyTo)
        assertEquals(a.info.tag, got.replyFrom)

        val direct = assertNotNull(a.core.sendDirect(b.deviceId, "Und du?", replyTo = answer.mid, replyFrom = b.info.tag))
        val d = b.await<CoreEvent.Message> { it.mid == direct.mid }
        assertEquals(answer.mid, d.replyTo)
        assertEquals(b.info.tag, d.replyFrom)

        // Without a valid reference nothing goes along.
        val plain = assertNotNull(a.core.sendDirect(b.deviceId, "Ohne", replyTo = "kaputt", replyFrom = b.info.tag))
        assertNull(b.await<CoreEvent.Message> { it.mid == plain.mid }.replyTo)
    }
}
