package app.anynear.anmp.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR 0068 (deleting messages) and ADR 0069 (link previews). */
class DeleteAndLinkTest {
    @Test
    fun authorDeletesInCircleAndDirect() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        a.await<CoreEvent.MemberUpdated> { it.member.deviceId == b.deviceId }
        eventually { a.core.canWriteTo(b.deviceId) && b.core.canWriteTo(a.deviceId) }

        val out = assertNotNull(a.core.sendCircle(circle.id, "Hallo"))
        b.await<CoreEvent.Message> { it.mid == out.mid }
        assertTrue(a.core.deleteMessage(Conversation.Circle(circle.id), a.info.tag, out.mid))
        val d = b.await<CoreEvent.Deleted> { it.targetMid == out.mid }
        assertEquals(Conversation.Circle(circle.id), d.conversation)
        assertEquals(a.info.tag, d.targetTag)
        assertEquals(a.deviceId, d.fromDevice)

        val direct = assertNotNull(a.core.sendDirect(b.deviceId, "Nur für dich"))
        b.await<CoreEvent.Message> { it.mid == direct.mid }
        assertTrue(a.core.deleteMessage(Conversation.Direct(b.info.personId), a.info.tag, direct.mid))
        b.await<CoreEvent.Deleted> { it.targetMid == direct.mid && it.conversation == Conversation.Direct(a.info.personId) }
    }

    @Test
    fun deletingInACircleRemovesEntryAndAttachmentFromTheRelays() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        a.await<CoreEvent.MemberUpdated> { it.member.deviceId == b.deviceId }
        eventually { a.core.canWriteTo(b.deviceId) }

        val att = a.core.prepareAttachment(ByteArray(4000) { 9 }, "jpg", "bild.jpg", 64, 64)
        val out = assertNotNull(a.core.sendCircle(circle.id, "mit Bild", listOf(att)))
        val msg = b.await<CoreEvent.Message> { it.mid == out.mid }
        val space = app.anynear.andp.Key.parse(msg.space)
        val blob = app.anynear.andp.ID(msg.attachments.single().id)
        eventually { net.holding(space, blob).isNotEmpty() }
        suspend fun entryOf() = net.relays.flatMap { r -> r.entries(space) }.filter { e -> e.meta.refs.any { it.id == blob } }
        assertTrue(entryOf().isNotEmpty())

        assertTrue(a.core.deleteMessage(Conversation.Circle(circle.id), a.info.tag, out.mid))
        b.await<CoreEvent.Deleted> { it.targetMid == out.mid }
        eventually { entryOf().isEmpty() && net.holding(space, blob).isEmpty() }
    }

    @Test
    fun messagesStayWithoutExpiryAndTheReceiverDeletesInItsMailbox() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        eventually { a.core.canWriteTo(b.deviceId) && b.core.canWriteTo(a.deviceId) }

        // ADR 0088: the direct message stays without expiry, the receipt for it expires.
        val direct = assertNotNull(a.core.sendDirect(b.deviceId, "Bleibt"))
        val msg = b.await<CoreEvent.Message> { it.mid == direct.mid }
        val space = app.anynear.andp.Key.parse(msg.space)
        val id = app.anynear.andp.ID.parse(msg.objectId)
        suspend fun copies() = net.relays.mapNotNull { it.get(space, id) }
        eventually { copies().isNotEmpty() }
        assertTrue(copies().all { it.meta.until == 0L })
        a.await<CoreEvent.Receipt> { direct.mid in it.mids }
        val inboxA = net.relays.flatMap { r -> r.entries(app.anynear.andp.Key.parse(a.info.inbox)) }
        assertTrue(inboxA.any { it.meta.until > 0L }, "receipts expire")

        // Anna may not delete in Ben's mailbox; Ben does it after her valid delete (ADR 0088 §3).
        assertTrue(a.core.deleteMessage(Conversation.Direct(b.info.personId), a.info.tag, direct.mid))
        b.await<CoreEvent.Deleted> { it.targetMid == direct.mid }
        eventually { copies().isEmpty() }
    }

    @Test
    fun othersCannotDeleteAMessage() = world {
        val a = device("Anna")
        val b = device("Ben")
        val c = device("Carla")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        c.core.joinCircle(a.core.inviteLink(circle.id)!!)
        a.await<CoreEvent.MemberUpdated> { it.member.deviceId == c.deviceId }
        eventually { b.core.canWriteTo(c.deviceId) && c.core.canWriteTo(b.deviceId) }

        val out = assertNotNull(a.core.sendCircle(circle.id, "von Anna"))
        c.await<CoreEvent.Message> { it.mid == out.mid }
        // Ben tries to delete Anna's message: Carla ignores it. Then Anna deletes: Carla takes that.
        assertTrue(b.core.deleteMessage(Conversation.Circle(circle.id), a.info.tag, out.mid))
        assertTrue(a.core.deleteMessage(Conversation.Circle(circle.id), a.info.tag, out.mid))
        val d = c.await<CoreEvent.Deleted> { it.targetMid == out.mid }
        assertEquals(a.deviceId, d.fromDevice)
    }

    @Test
    fun linkPreviewOnlyForASentLink() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        a.await<CoreEvent.MemberUpdated> { it.member.deviceId == b.deviceId }
        eventually { a.core.canWriteTo(b.deviceId) }

        val picture = ByteArray(3000) { 3 }
        val image = a.core.prepareAttachment(picture, "jpg", width = 600, height = 315)
        val url = "https://example.org/artikel"
        val link = PreparedLink(url, "Ein Artikel", "Kurz beschrieben", "example.org", image)
        val out = assertNotNull(a.core.sendCircle(circle.id, "Schau mal: $url", link = link))
        val msg = b.await<CoreEvent.Message> { it.mid == out.mid }
        val got = assertNotNull(msg.link)
        assertEquals(url, got.url)
        assertEquals("Ein Artikel", got.title)
        assertEquals("example.org", got.site)
        assertTrue(msg.attachments.isEmpty())
        val img = assertNotNull(got.image)
        assertContentEquals(picture, assertIs<BlobResult.Found>(b.core.fetchAttachment(msg.space, img)).plaintext)

        // A preview for a link that is not in the text is ignored.
        val other = assertNotNull(a.core.sendCircle(circle.id, "ohne Link", link = PreparedLink("https://example.org/x", "Falsch")))
        val plain = b.await<CoreEvent.Message> { it.mid == other.mid }
        assertNull(plain.link)
        assertFalse(plain.text.contains("https"))
    }
}
