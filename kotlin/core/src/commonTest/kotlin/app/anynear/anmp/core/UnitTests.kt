package app.anynear.anmp.core

import app.anynear.andp.B64
import app.anynear.andp.Grant
import app.anynear.andp.GrantBody
import app.anynear.andp.Hash
import app.anynear.andp.NetworkList
import app.anynear.andp.Rights
import app.anynear.andp.networkId
import app.anynear.anmp.Anmp
import app.anynear.anmp.AnmpContent
import app.anynear.anmp.AnmpKeys
import app.anynear.anmp.AnmpKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LinksTest {
    private val network = B64.encode(ByteArray(32) { 1 })

    @Test
    fun inviteRoundTrip() {
        val invite = Invite(network, "c1", "Familie Müller 👨‍👩‍👧", B64.encode(ByteArray(32) { 2 }), 3, 1761200000000, B64.encode(ByteArray(16) { 4 }), byteArrayOf(9, 9), "Anna")
        val link = AnmpLinks.inviteLink("anynear.app", invite)
        assertTrue(link.startsWith("https://anynear.app/k#"))
        val back = assertNotNull(AnmpLinks.parseInvite("Tritt bei: $link !", "anynear.app"))
        assertEquals(invite.circleId, back.circleId)
        assertEquals(invite.name, back.name)
        assertEquals(invite.secret, back.secret)
        assertEquals(3, back.epoch)
        assertEquals(invite.epochBy, back.epochBy)
        assertEquals(network, back.networkId)
        assertContentEquals(byteArrayOf(9, 9), back.radio)
        assertEquals("Anna", back.inviter)
        // Another host, truncated fragment or another version: no invitation.
        assertNull(AnmpLinks.parseInvite(link.replace("anynear.net", "example.org"), "anynear.net"))
        assertNull(AnmpLinks.parseInvite(link.dropLast(10), "anynear.net"))
        assertNull(AnmpLinks.parseInvite("https://anynear.app/k#abc", "anynear.net"))
    }

    @Test
    fun inviteWithoutInviterReadsAsEmpty() {
        // Links of older apps have no field 9; a blank name is left out of the link.
        val old = Invite(network, "c1", "Familie", B64.encode(ByteArray(32) { 2 }), 0, 0, "", null)
        assertEquals("", assertNotNull(AnmpLinks.parseInvite(AnmpLinks.inviteLink("anynear.app", old), "anynear.app")).inviter)
        val blank = Invite(network, "c1", "Familie", B64.encode(ByteArray(32) { 2 }), 0, 0, "", null, "  ")
        assertEquals(AnmpLinks.inviteLink("anynear.app", old), AnmpLinks.inviteLink("anynear.app", blank))
    }

    @Test
    fun contactRoundTripChecksGrantAndDevice() = runTest {
        val owner = testKey("inbox")
        val device = testKey("device")
        val g = Grant.issue(owner, GrantBody(owner.key, Rights.WRITE, null, null, 2_000_000_000, 1_000))
        val card = ContactCard(network, "Anna", B64.encode(AnmpKeys.deviceIdOf(device.key.bytes)), device.key.toString(), B64.encode(ByteArray(32) { 5 }), owner.key.toString(), g.toString(), null)
        val link = AnmpLinks.contactLink("anynear.net", card)
        val back = assertNotNull(AnmpLinks.parseContact(link, "anynear.net"))
        assertEquals(card.deviceId, back.deviceId)
        assertEquals(card.inbox, back.inbox)
        assertEquals(g.toString(), back.grant)
        // A grant for another space is refused.
        val other = Grant.issue(device, GrantBody(device.key, Rights.WRITE, null, null, 2_000_000_000, 1_000))
        val bad = AnmpLinks.contactLink("anynear.net", ContactCard(network, "Anna", card.deviceId, card.signKey, card.publicKey, card.inbox, other.toString(), null))
        assertNull(AnmpLinks.parseContact(bad, "anynear.net"))
    }
}

class KeyChangeTest {
    private val base = CircleState("c", "K", B64.encode(ByteArray(32)), epoch = 1, epochTs = 100, epochBy = "b")

    @Test
    fun nextEpochSwitches() {
        val r = KeyChange("x", 2, 200, "new", null, viaPrevious = false).apply(base, 999)
        val c = assertIs<KeyChange.Outcome.Switched>(r).circle
        assertEquals("new", c.secret)
        assertEquals(base.secret, c.previousSecret)
        assertEquals(2, c.epoch)
    }

    @Test
    fun staleAndRacesFollowTheEarlierRule() {
        assertIs<KeyChange.Outcome.Ignored>(KeyChange("x", 1, 50, "s", null, viaPrevious = false).apply(base, 0))
        // Simultaneous change through the previous space: the earlier one wins.
        assertIs<KeyChange.Outcome.Switched>(KeyChange("a", 1, 50, "s", null, viaPrevious = true).apply(base, 0))
        assertIs<KeyChange.Outcome.Ignored>(KeyChange("z", 1, 150, "s", null, viaPrevious = true).apply(base, 0))
    }

    @Test
    fun removedAndReadmitted() {
        val removed = assertIs<KeyChange.Outcome.Removed>(KeyChange("x", 2, 200, null, null, viaPrevious = false).apply(base, 999)).circle
        assertTrue(removed.removed)
        assertEquals(2, removed.removedEpoch)
        val back = KeyChange("y", 2, 150, "again", null, viaPrevious = true).apply(removed, 999)
        assertFalse(assertIs<KeyChange.Outcome.Readmitted>(back).circle.removed)
    }
}

class DutyTest {
    @Test
    fun rankIsTheSameEverywhere() = runTest {
        val d = Duty(CoreTimings())
        val members = listOf("a", "b", "c")
        val ranks = members.map { me -> d.rank("x", me, members - me) }
        assertEquals(setOf(0, 1, 2), ranks.toSet())
        assertEquals(CoreTimings().dutyFirst, d.delayFor(0))
        assertEquals(CoreTimings().dutyStep * 2, d.delayFor(2))
    }
}

class PacketsTest {
    @Test
    fun circleAndDirectRoundTrip() = runTest {
        val a = CoreIdentity.loadOrCreate(MemorySecretStore())
        val b = CoreIdentity.loadOrCreate(MemorySecretStore())
        val keys = CircleKeys.of(B64.encode(AnmpKeys.randomKey()))
        val content = AnmpContent(id = Anmp.newMessageId(), ts = 1, text = "hi", name = "A", key = a.device.publicKey, signKey = a.device.signKey, inbox = a.inbox.bytes)
        val circle = Packets.encode(a, AnmpKind.CIRCLE, content, "c1", keys, null)
        val o = assertNotNull(Packets.open(b, circle.packet, "c1", keys) { null })
        assertEquals(a.deviceId, o.fromDevice)
        assertContentEquals(a.inbox.bytes, o.content.inbox)
        // Another circle ID (scope) does not open.
        assertNull(Packets.open(b, circle.packet, "c2", keys) { null })

        val direct = Packets.encode(a, AnmpKind.DIRECT, content, Anmp.DIRECT_SCOPE, null, Peer(b.deviceId, b.publicKey))
        assertNotNull(Packets.open(b, direct.packet, Anmp.DIRECT_SCOPE, null) { null })
        // Not for a third installation.
        val c = CoreIdentity.loadOrCreate(MemorySecretStore())
        assertNull(Packets.open(c, direct.packet, Anmp.DIRECT_SCOPE, null) { null })
        // The envelope binds the entry to its space.
        val env = keys.anmp.envelope.seal(circle.packet, keys.space.toString())
        assertNull(keys.anmp.envelope.open(env, a.inbox.toString()))
    }

    @Test
    fun circleSpaceKeyMatchesFormat() = runTest {
        val secret = B64.encode(AnmpKeys.randomKey())
        val k = CircleKeys.of(secret)
        assertEquals(k.anmp.space, k.space.toString())
    }
}

class RelayPoolTest {
    @Test
    fun takesARaisedObjectLimitWithoutRestart() = runTest {
        val net = FakeNet.create(3)
        var now = 1_000_000L
        val platform = AnmpPlatform(MemorySecretStore(), MemoryKeyValueStore(), MemoryBlobStore(), clock = { now }, connector = net.connector)
        val pool = RelayPool(platform)
        net.relays.forEach { it.policy = it.policy.copy(maxObjectBytes = 1000) }
        net.relays.forEach { pool.info(it.relay) }
        assertEquals(1000, assertFailsWith<TooLargeException> { pool.checkObjectSize(2000) }.limit)

        // The relays take more now: a larger object fetches the infos again at once.
        net.relays.forEach { it.policy = it.policy.copy(maxObjectBytes = 5000) }
        pool.checkObjectSize(2000)
        assertEquals(5000, pool.maxObjectBytes())

        // Other values follow after an hour, without a larger object.
        net.relays.forEach { it.policy = it.policy.copy(maxUntil = 60) }
        net.relays.forEach { pool.info(it.relay) }
        assertNotEquals(60, pool.maxUntil())
        now += RelayPool.INFO_MAX_AGE_MILLIS
        net.relays.forEach { pool.info(it.relay) }
        assertEquals(60, pool.maxUntil())

        // An unreachable relay keeps its last answer.
        net.relays.forEach { it.offline = true }
        now += RelayPool.INFO_MAX_AGE_MILLIS
        assertNotNull(pool.info(net.relays.first().relay))
    }
}

class NetworkDirectoryTest {
    @Test
    fun takesOnlyNewerListsOfTheConfiguredNetwork() = runTest {
        val net = FakeNet.create(3)
        val platform = AnmpPlatform(MemorySecretStore(), MemoryKeyValueStore(), MemoryBlobStore(), connector = net.connector)
        val dir = NetworkDirectory(net.config(), platform, RelayPool(platform))
        assertTrue(dir.accept(net.signed))
        assertEquals(3, dir.active.size)
        assertFalse(dir.accept(net.signed))
        val newer = net.list.copy(issued = 2000, relays = net.list.relays.take(2)).sign(net.netKey)
        assertTrue(dir.accept(newer))
        assertEquals(2, dir.active.size)
        // A list signed by another key, or of another network, is refused.
        val stranger = testKey("other network")
        val foreign = NetworkList(1, networkId(stranger.key), stranger.key, 3000, relays = net.list.relays).sign(stranger)
        assertFalse(dir.accept(foreign))
        // Saved and loaded again.
        val again = NetworkDirectory(net.config(), platform, RelayPool(platform))
        again.loadSaved()
        assertEquals(2000, again.list.value?.issued)
    }

    @Test
    fun appUpdateWithNewKeyTrustsTheBuiltInKey() = runTest {
        // Saved list of the old key, no hand-over list seen; the update brings the new key (same network ID).
        val net = FakeNet.create(2)
        val newKey = testKey("new network key")
        val platform = AnmpPlatform(MemorySecretStore(), MemoryKeyValueStore(), MemoryBlobStore(), connector = net.connector)
        assertTrue(NetworkDirectory(net.config(), platform, RelayPool(platform)).accept(net.signed))
        val updated = AnmpCoreConfig(net.list.id, newKey.key, seedUrl = "https://seed.test", timings = TEST_TIMINGS)
        val dir = NetworkDirectory(updated, platform, RelayPool(platform))
        dir.loadSaved()
        assertEquals(net.list.issued, dir.list.value?.issued)
        assertFalse(dir.accept(net.list.copy(network = newKey.key, issued = net.list.issued).sign(newKey)), "not newer")
        assertTrue(dir.accept(net.list.copy(network = newKey.key, issued = 2000).sign(newKey)))
        assertEquals(newKey.key, dir.list.value?.network)
        // The old key alone no longer counts after that.
        assertFalse(dir.accept(net.list.copy(issued = 3000).sign(net.netKey)))
    }

    @Test
    fun keyHandOverKeepsTheNetworkId() = runTest {
        val net = FakeNet.create(2)
        val next = testKey("next network key")
        val platform = AnmpPlatform(MemorySecretStore(), MemoryKeyValueStore(), MemoryBlobStore(), connector = net.connector)
        val dir = NetworkDirectory(net.config(), platform, RelayPool(platform))
        assertTrue(dir.accept(net.list.copy(issued = 1500, next = next.key).sign(net.netKey)))
        assertTrue(dir.accept(net.list.copy(issued = 1600, network = next.key).sign(next)))
        assertEquals(next.key, dir.list.value?.network)
        val reload = NetworkDirectory(net.config(), platform, RelayPool(platform))
        reload.loadSaved()
        assertEquals(1600, reload.list.value?.issued)
    }

    @Test
    fun untilIsCappedByPolicy() {
        assertEquals(1000L + 10, untilFor(1_000_000, 30.seconds, 10))
        assertEquals(1000L + 30, untilFor(1_000_000, 30.seconds, 0))
    }

    @Test
    fun hashIsUsed() {
        assertEquals(32, Hash.of(byteArrayOf()).bytes.size)
    }
}
