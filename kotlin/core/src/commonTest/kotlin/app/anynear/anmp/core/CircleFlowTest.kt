package app.anynear.anmp.core

import app.anynear.andp.ID
import app.anynear.andp.Key
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CircleFlowTest {
    @Test
    fun circleMessageWithAttachmentReceiptsAndReactions() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        val link = assertNotNull(a.core.inviteLink(circle.id))
        assertTrue(link.startsWith("https://anynear.app/k#"))
        // Links on the technical domain stay valid (ADR 0061 decision 4).
        assertNotNull(b.core.parseInvite(link.replace("anynear.app", "anynear.net")))
        assertIs<JoinResult.Joined>(b.core.joinCircle("Komm dazu: $link"))

        // Both see each other through their announcements, with the circle grant for the personal mailbox.
        b.await<CoreEvent.MemberUpdated> { it.member.deviceId == a.deviceId && it.member.circleId == circle.id }
        a.await<CoreEvent.MemberUpdated> { it.member.deviceId == b.deviceId && it.member.circleId == circle.id }
        eventually { a.core.canWriteTo(b.deviceId) && b.core.canWriteTo(a.deviceId) }

        val photo = ByteArray(200_000) { (it % 251).toByte() }
        val att = a.core.prepareAttachment(photo, "jpg", "bild.jpg", 640, 480)
        val out = assertNotNull(a.core.sendCircle(circle.id, "Hallo Kreis", listOf(att)))
        a.await<CoreEvent.Stored> { it.ref == out.mid }

        val msg = b.await<CoreEvent.Message> { it.mid == out.mid }
        assertEquals("Hallo Kreis", msg.text)
        assertEquals(a.deviceId, msg.fromDevice)
        assertEquals("Anna", msg.fromName)
        assertEquals(Conversation.Circle(circle.id), msg.conversation)
        assertNotNull(msg.opened)
        val got = b.core.fetchAttachment(msg.space, msg.attachments.single())
        assertContentEquals(photo, assertIs<BlobResult.Found>(got).plaintext)

        // The message stays without expiry and carries no refs; a receipt entry (kind hold) carries the refs to its
        // content with the content's expiry (ADR 0088 §2, ADR 0035 §4). The content lies at the holders of its own hash.
        val space = Key.parse(msg.space)
        val entries = net.holders(msg.space).first().entries(space)
        val hold = entries.first { e -> e.meta.refs.any { it.id == ID(msg.attachments.single().id) } }
        assertEquals(1, hold.meta.refs.size)
        assertTrue(hold.meta.until > 0)
        val content = assertNotNull(net.holding(space, ID(msg.attachments.single().id)).first().get(space, ID(msg.attachments.single().id)))
        assertEquals(hold.meta.until, content.meta.until)
        // Circle messages wake (watch), announcements and the receipt do not.
        val woke = net.relays.flatMap { it.notifiedPuts }.map { it.id }.toSet()
        val entry = entries.single { it.meta.id in woke }
        assertEquals(0L, entry.meta.until)
        assertTrue(entry.meta.refs.isEmpty())
        assertTrue(hold.meta.id !in woke)
        assertTrue(entries.none { e -> e.meta.until == 0L && e.meta.refs.isNotEmpty() })
        val contentHolders = net.holding(space, ID(msg.attachments.single().id)).map { it.relay.node }
        assertEquals(net.holders(ID(msg.attachments.single().id).toString()).map { it.relay.node }.toSet(), contentHolders.toSet())

        // Receipt into Anna's personal mailbox ("angekommen"), then read and a reaction.
        val receipt = a.await<CoreEvent.Receipt> { !it.read && out.mid in it.mids }
        assertEquals(b.deviceId, receipt.fromDevice)
        b.core.confirmRead(a.deviceId, out.mid)
        a.await<CoreEvent.Receipt> { it.read && out.mid in it.mids }
        assertTrue(b.core.react(Conversation.Circle(circle.id), a.info.tag, out.mid, "👍"))
        val r = a.await<CoreEvent.Reaction> { it.targetMid == out.mid }
        assertEquals("👍", r.emoji)
        assertEquals(b.deviceId, r.fromDevice)
    }

    @Test
    fun appChosenMessageIdAndTime() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        a.await<CoreEvent.MemberUpdated> { it.member.deviceId == b.deviceId }
        eventually { a.core.canWriteTo(b.deviceId) && b.core.canWriteTo(a.deviceId) }

        // The app sends the same message over radio first, so the ID must be the same on every path.
        val mid = "AAECAwQFBgc"
        val out = assertNotNull(a.core.sendCircle(circle.id, "mit eigener ID", mid = mid, timestampMillis = 1_700_000_000_000))
        assertEquals(mid, out.mid)
        val msg = b.await<CoreEvent.Message> { it.mid == mid }
        assertEquals(1_700_000_000_000, msg.timestampMillis)

        val direct = assertNotNull(a.core.sendDirect(b.deviceId, "direkt", mid = "BwYFBAMCAQA"))
        assertEquals("BwYFBAMCAQA", direct.mid)
        b.await<CoreEvent.Message> { it.mid == "BwYFBAMCAQA" && it.conversation == Conversation.Direct(a.info.personId) }

        // An invalid ID is replaced by a new one.
        val other = assertNotNull(a.core.sendCircle(circle.id, "kaputte ID", mid = "zu-kurz"))
        assertEquals(11, other.mid.length)
        assertTrue(other.mid != "zu-kurz")
    }

    @Test
    fun renameAndHistoryForNewMember() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        a.await<CoreEvent.MemberUpdated> { it.member.deviceId == b.deviceId }
        eventually { a.core.canWriteTo(b.deviceId) }
        keep(a, circle.id, a.core.sendCircle(circle.id, "eins"))
        keep(a, circle.id, a.core.sendCircle(circle.id, "zwei"))
        b.await<CoreEvent.Message> { it.text == "zwei" }
        assertTrue(a.core.renameCircle(circle.id, "Meine Familie"))
        b.await<CoreEvent.CircleRenamed> { it.name == "Meine Familie" && it.byDevice == a.deviceId }

        // Clara joins later and gets the whole history from a member (ADR 0014), checked by device signature.
        val c = device("Clara")
        c.core.joinCircle(a.core.inviteLink(circle.id)!!)
        val loaded = c.await<CoreEvent.HistoryLoaded>(30.seconds())
        assertEquals(circle.id, loaded.circleId)
        val texts = c.all<CoreEvent.Message>().filter { it.history }.map { it.text }
        assertTrue("eins" in texts && "zwei" in texts, "history: $texts")
        assertEquals(false, c.core.circle(circle.id)!!.historyPending)
        // The parts are deleted after reading with the scoped delete grant.
        eventually { net.relays.sumOf { it.tombstoneCount() } > 0 }
    }

    @Test
    fun removeMemberChangesKeyAndRevokesCircleGrant() = world {
        val a = device("Anna")
        val b = device("Ben")
        val c = device("Clara")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        c.core.joinCircle(a.core.inviteLink(circle.id)!!)
        eventually { a.core.membersOf(circle.id).size == 2 && b.core.membersOf(circle.id).size == 2 && c.core.membersOf(circle.id).size == 2 }
        eventually { b.core.canWriteTo(a.deviceId) && c.core.canWriteTo(a.deviceId) }

        a.core.removeMember(circle.id, b.deviceId)
        // Ben announces once more with the old key before he learns of the removal.
        b.core.announce(circle.id)
        a.await<CoreEvent.KeyChanged> { it.kind == CoreEvent.KIND_YOU_REMOVED && it.removedDevice == b.deviceId }
        b.await<CoreEvent.KeyChanged> { it.kind == CoreEvent.KIND_REMOVED_YOU }
        c.await<CoreEvent.KeyChanged> { it.kind == CoreEvent.KIND_SWITCHED && it.removedDevice == b.deviceId }
        assertEquals(a.core.circle(circle.id)!!.secret, c.core.circle(circle.id)!!.secret)
        assertTrue(b.core.circle(circle.id)!!.removed)
        // His late announcement in the previous space does not make him a member again.
        eventually { c.core.membersOf(circle.id).none { it.deviceId == b.deviceId } }
        val seen = c.all<CoreEvent.MemberUpdated>().count { it.member.deviceId == b.deviceId }
        assertTrue(c.none<CoreEvent.MemberUpdated>(2.seconds()) { false })
        assertEquals(seen, c.all<CoreEvent.MemberUpdated>().count { it.member.deviceId == b.deviceId })
        assertTrue(c.core.membersOf(circle.id).none { it.deviceId == b.deviceId })
        assertTrue(a.core.membersOf(circle.id).none { it.deviceId == b.deviceId })

        // Clara still reaches Anna in the new key; Ben does not read it.
        val out = assertNotNull(c.core.sendCircle(circle.id, "ohne Ben"))
        a.await<CoreEvent.Message> { it.mid == out.mid }
        assertTrue(b.none<CoreEvent.Message>(2.seconds()) { it.mid == out.mid })

        // Ben kept Anna's old circle grant; it is revoked, so his direct message is refused by the holders.
        val annaInbox = Key.parse(a.info.inbox)
        eventually { net.holders(a.info.inbox).all { it.revokedIn(annaInbox) > 0 } }
        val sent = b.core.sendDirect(a.deviceId, "noch da?")
        if (sent != null) b.await<CoreEvent.SendFailed> { it.ref == sent.mid }
        assertTrue(a.none<CoreEvent.Message>(2.seconds()) { it.text == "noch da?" })
    }

    @Test
    fun leavingMakesAMemberRotate() = world {
        val a = device("Anna")
        val b = device("Ben")
        val c = device("Clara")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        c.core.joinCircle(a.core.inviteLink(circle.id)!!)
        eventually { a.core.membersOf(circle.id).size == 2 && b.core.membersOf(circle.id).size == 2 && c.core.membersOf(circle.id).size == 2 }
        val epoch = a.core.circle(circle.id)!!.epoch
        c.core.leaveCircle(circle.id)
        assertEquals(null, c.core.circle(circle.id))
        a.await<CoreEvent.MemberLeft> { it.deviceId == c.deviceId }
        eventually(20.seconds()) { (a.core.circle(circle.id)!!.epoch > epoch) && a.core.circle(circle.id)!!.secret == b.core.circle(circle.id)!!.secret }
    }
}

private fun Int.seconds() = kotlin.time.Duration.parse("${this}s")
