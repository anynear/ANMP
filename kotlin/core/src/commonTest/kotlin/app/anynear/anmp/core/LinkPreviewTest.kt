package app.anynear.anmp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkPreviewTest {
    @Test
    fun circleContactAndChannelLinksBeforeAndAfter() = world {
        val a = device("Anna")
        val b = device("Ben")

        val circle = a.core.createCircle("Familie")
        val invite = a.core.inviteLink(circle.id)!!
        val before = assertNotNull(b.core.previewLink(invite))
        assertEquals(LinkPreview.CIRCLE, before.kind)
        assertEquals("Familie", before.name)
        assertFalse(before.joined)
        assertNull(before.target)
        assertIs<JoinResult.Joined>(b.core.joinCircle(invite))
        val after = assertNotNull(b.core.previewLink(invite))
        assertTrue(after.joined)
        assertEquals(circle.id, after.target)

        val contact = a.core.ownContactLink()
        val own = assertNotNull(a.core.previewLink(contact))
        assertEquals(LinkPreview.CONTACT, own.kind)
        assertTrue(own.self)
        assertFalse(assertNotNull(b.core.previewLink(contact)).joined)
        assertIs<AddContactResult.Added>(b.core.addContact(contact))
        val known = assertNotNull(b.core.previewLink(contact))
        assertTrue(known.joined)
        assertFalse(known.self)
        assertNotNull(known.target)

        val channel = a.core.createChannel("Haus")
        val channelLink = a.core.channelLink(channel.id)!!
        val sub = assertNotNull(b.core.previewLink(channelLink))
        assertEquals(LinkPreview.CHANNEL, sub.kind)
        assertEquals("Haus", sub.name)
        assertFalse(sub.joined)
        assertIs<ChannelJoinResult.Joined>(b.core.joinChannel(channelLink))
        assertTrue(assertNotNull(b.core.previewLink(channelLink)).joined)

        val tablet = device("Tablet")
        val pair = tablet.core.pairingOffer("Tablet")
        val device = assertNotNull(a.core.previewLink(pair))
        assertEquals(LinkPreview.DEVICE, device.kind)
        assertEquals("Tablet", device.name)
        assertFalse(device.joined)
        assertFalse(device.self)
        assertTrue(assertNotNull(tablet.core.previewLink(pair)).self)
        assertNull(b.core.previewLink("https://anynear.app/p#kaputt"))

        assertNull(b.core.previewLink("https://example.org/k#abc"))
        assertNull(b.core.previewLink("https://anynear.app/k#kaputt"))
    }
}
