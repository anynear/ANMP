package app.anynear.anmp.core

import app.anynear.andp.Grant
import app.anynear.andp.GrantBody
import app.anynear.andp.Rights
import app.anynear.anmp.Anmp
import app.anynear.anmp.AnmpCircleKeys
import app.anynear.anmp.AnmpContent
import app.anynear.anmp.AnmpHeader
import app.anynear.anmp.AnmpKind
import app.anynear.anmp.AnmpRadio
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Radio inbox of ADR 0051: anyone uplinks, readers filter by route tag, fresh writer per write. */
class RadioInboxTest {
    private suspend fun World.configWithInbox(): AnmpCoreConfig {
        val owner = testKey("radio inbox owner")
        val now = Clock.System.now().epochSeconds
        val grant = Grant.issue(owner, GrantBody(owner.key, Rights(1) + Rights(2) + Rights(4), null, null, now + 86_400, now - 60))
        val base = net.config()
        return AnmpCoreConfig(base.networkId, base.networkKey, seedUrl = base.seedUrl, timings = base.timings,
            radioInboxSpace = owner.key.toString(), radioInboxGrant = grant.toString())
    }

    private suspend fun publicPacket(route: ByteArray, text: String): ByteArray {
        val keys = AnmpCircleKeys.derive(ByteArray(32) { 5 })
        return Anmp.encode(AnmpHeader(AnmpKind.CIRCLE, ByteArray(8) { 1 }, epoch = 0, route = route), AnmpContent(id = Anmp.newMessageId(), ts = 1, text = text), "c", keys.cipher)
    }

    @Test
    fun strangerUplinksAndReaderFindsOwnTag() = world {
        val config = configWithInbox()
        val uplinker = device("Fremder", config = config)
        val reader = device("Clara", config = config)
        assertTrue(uplinker.core.hasRadioInbox)

        val mine = AnmpRadio.routeTag(AnmpRadio.circleSecret(ByteArray(32) { 5 }), AnmpRadio.hourOf(Clock.System.now().epochSeconds))
        val other = ByteArray(8) { 9 }
        val packet = publicPacket(mine, "Bin im Wald")
        assertTrue(uplinker.core.uplinkRadioPacket(packet))
        assertTrue(uplinker.core.uplinkRadioPacket(publicPacket(other, "nicht für Clara")))
        assertTrue(uplinker.core.uplinkRadioPacket(packet), "the same packet again is no error")

        val got = reader.core.pollRadioInbox { it.contentEquals(mine) }
        assertEquals(1, got.size)
        assertContentEquals(packet, got.single().packet)
        assertTrue(reader.core.pollRadioInbox { it.contentEquals(mine) }.isEmpty(), "each packet once")

        // Every write had its own writer key, also the repeated one.
        val space = config.radioInboxSpace
        val writers = net.relays.flatMap { it.puts }.filter { it.space.toString() == space }.map { it.writer }.toSet()
        assertEquals(3, writers.size)
    }

    @Test
    fun onlyPublicRadioPackets() = world {
        val config = configWithInbox()
        val d = device("Ben", config = config)
        val keys = AnmpCircleKeys.derive(ByteArray(32) { 5 })
        val oldForm = Anmp.encode(AnmpHeader(AnmpKind.CIRCLE, ByteArray(8), epoch = 0), AnmpContent(id = Anmp.newMessageId(), text = "x"), "c", keys.cipher)
        assertFalse(d.core.uplinkRadioPacket(oldForm), "no route tag")
        assertFalse(d.core.uplinkRadioPacket(Anmp.encodeOpen(AnmpHeader(AnmpKind.ASK, ByteArray(8)))), "ask is never stored")
        assertFalse(d.core.uplinkRadioPacket("hello".encodeToByteArray()))
    }

    @Test
    fun withoutSetupNothingHappens() = world {
        val d = device("Anna")
        assertFalse(d.core.hasRadioInbox)
        assertFalse(d.core.uplinkRadioPacket(publicPacket(ByteArray(8), "x")))
        assertTrue(d.core.pollRadioInbox { true }.isEmpty())
    }
}
