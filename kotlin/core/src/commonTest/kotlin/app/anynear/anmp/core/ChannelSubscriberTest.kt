package app.anynear.anmp.core

import app.anynear.anmp.AnmpAlerts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/** Subscribers of a channel (ADR 0062 §11): visible to the owner, removed by a new read key for the others. */
class ChannelSubscriberTest {
    @Test
    fun ownerSeesSubscribersAndRemovesOneSilently() = world {
        val a = device("Anna")
        val tablet = addTablet(a)
        val b = device("Ben")
        val c = device("Clara")
        val ch = a.core.createChannel("Haus")
        val link = assertNotNull(a.core.channelLink(ch.id))
        assertTrue(assertNotNull(b.core.previewLink(link)).seenByOwner)

        assertIs<ChannelJoinResult.Joined>(b.core.joinChannel(link))
        assertIs<ChannelJoinResult.Joined>(c.core.joinChannel(link))
        assertTrue(b.core.channel(ch.id)!!.seenByOwner)
        eventually { a.core.channel(ch.id)?.subscribers?.map { it.name }?.toSet() == setOf("Ben", "Clara") }
        assertEquals(setOf(b.info.deviceId, c.info.deviceId), a.core.channel(ch.id)!!.subscribers.map { it.deviceId }.toSet())
        // The list travels to the own other device.
        eventually { tablet.core.channel(ch.id)?.subscribers?.size == 2 }

        // Removed from the tablet: Clara gets the new key, Ben does not.
        val oldKeyClara = c.core.channel(ch.id)!!.grant
        assertTrue(tablet.core.removeSubscriber(ch.id, b.info.deviceId))
        assertFalse(tablet.core.removeSubscriber(ch.id, b.info.deviceId))
        assertEquals(listOf("Clara"), tablet.core.channel(ch.id)!!.subscribers.map { it.name })
        eventually { c.core.channel(ch.id)?.grant != oldKeyClara }
        eventually { a.core.channel(ch.id)?.subscribers?.map { it.name } == listOf("Clara") }
        eventually { a.core.channel(ch.id)?.grant == tablet.core.channel(ch.id)?.grant }
        assertEquals(tablet.core.channel(ch.id)!!.grant, c.core.channel(ch.id)!!.grant)
        assertNotEquals(tablet.core.channel(ch.id)!!.grant, b.core.channel(ch.id)!!.grant)

        val sent = assertNotNull(a.core.sendAlert(ch.id, AnmpAlerts.WARNING, null, "Nach dem Entfernen"))
        c.await<CoreEvent.Alert> { it.item.id == sent.id }
        assertTrue(b.none<CoreEvent.Alert>(3.seconds) { it.item.id == sent.id })

        // Ben's old link no longer works for announcing himself again.
        b.core.leaveChannel(ch.id)
        assertIs<ChannelJoinResult.Joined>(b.core.joinChannel(link))
        delay(2.seconds)
        assertEquals(listOf("Clara"), a.core.channel(ch.id)!!.subscribers.map { it.name })

        // Clara leaves: she disappears from the list.
        c.core.leaveChannel(ch.id)
        eventually { a.core.channel(ch.id)?.subscribers?.isEmpty() == true }
    }

    @Test
    fun curatedAndResetLinksStayAnonymous() = world {
        val a = device("Anna")
        val b = device("Ben")
        val ch = a.core.createChannel("Haus")
        assertIs<ChannelJoinResult.Joined>(b.core.joinChannel(assertNotNull(a.core.channelLink(ch.id))))
        eventually { a.core.channel(ch.id)?.subscribers?.size == 1 }
        // Reset: everyone loses access, the list starts empty; whoever takes the new link comes back.
        assertNotNull(a.core.resetChannelLink(ch.id))
        assertTrue(a.core.channel(ch.id)!!.subscribers.isEmpty())
        assertIs<ChannelJoinResult.Joined>(b.core.joinChannel(assertNotNull(a.core.channelLink(ch.id))))
        eventually { a.core.channel(ch.id)?.subscribers?.map { it.name } == listOf("Ben") }
    }
}
