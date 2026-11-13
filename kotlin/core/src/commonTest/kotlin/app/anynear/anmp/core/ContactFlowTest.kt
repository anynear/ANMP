package app.anynear.anmp.core

import app.anynear.andp.B64
import app.anynear.andp.Credentials
import app.anynear.andp.ID
import app.anynear.andp.Key
import app.anynear.andp.Meta
import app.anynear.andp.AndpClient
import app.anynear.andp.AndpHttpException
import app.anynear.andp.Placement
import app.anynear.anmp.Anmp
import app.anynear.anmp.AnmpContent
import app.anynear.anmp.AnmpDevice
import app.anynear.anmp.AnmpHeader
import app.anynear.anmp.AnmpKind
import kotlin.test.Test
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class ContactFlowTest {
    @Test
    fun publishedContactLinkLastsAYearAndCanBeWithdrawn() = world {
        val support = device("anynear Support")
        val link = support.core.publicContactLink()
        val card = assertNotNull(support.core.parseContact(link))
        val until = app.anynear.andp.Grant.parse(card.grant).body.until
        val now = Clock.System.now().epochSeconds
        assertTrue(until - now > 360L * 24 * 3600, "grant for a year")
        assertEquals(link, support.core.publicContactLink())

        val anna = device("Anna")
        assertIs<AddContactResult.Added>(anna.core.addContact(link))
        val out = assertNotNull(anna.core.sendDirect(support.deviceId, "Frage"))
        support.await<CoreEvent.Message> { it.mid == out.mid }

        // Withdrawn: the next published link carries a new grant; the short contact link is not touched.
        val short = support.core.ownContactLink()
        support.core.revokePublicContactLink()
        // A grant issued in the same second would have the same bytes, hence the same (revoked) hash.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { delay(1_100) }
        val again = assertNotNull(support.core.parseContact(support.core.publicContactLink()))
        assertTrue(again.grant != card.grant)
        assertEquals(short, support.core.ownContactLink())
    }

    @Test
    fun contactLinkDirectMessagesBothWays() = world {
        val a = device("Anna")
        val b = device("Ben")
        val link = a.core.ownContactLink()
        assertTrue(link.startsWith("https://anynear.app/c#"))
        val added = assertIs<AddContactResult.Added>(b.core.addContact(link))
        assertEquals(a.deviceId, added.contact.deviceId)
        assertTrue(b.core.canWriteTo(a.deviceId))

        // Ben's profile with a personal grant reaches Anna; she can answer without a link from him.
        a.await<CoreEvent.MemberUpdated> { it.member.deviceId == b.deviceId && it.member.circleId == MemberState.CONTACTS }
        eventually { a.core.canWriteTo(b.deviceId) }

        val out = assertNotNull(b.core.sendDirect(a.deviceId, "Hallo Anna", listOf(b.core.prepareAttachment(ByteArray(5000) { 7 }, "pdf", "brief.pdf"))))
        val msg = a.await<CoreEvent.Message> { it.mid == out.mid }
        assertEquals(Conversation.Direct(b.info.personId), msg.conversation)
        assertEquals(a.info.inbox, msg.space)
        assertContentEquals(ByteArray(5000) { 7 }, assertIs<BlobResult.Found>(a.core.fetchAttachment(msg.space, msg.attachments.single())).plaintext)
        val receipt = b.await<CoreEvent.Receipt> { out.mid in it.mids && !it.read }
        assertEquals(app.anynear.anmp.AnmpArrived.INTERNET, receipt.arrived)

        val reply = assertNotNull(a.core.sendDirect(b.deviceId, "Hallo Ben"))
        b.await<CoreEvent.Message> { it.mid == reply.mid && it.text == "Hallo Ben" }
        assertTrue(a.core.react(Conversation.Direct(b.deviceId), b.info.tag, out.mid, "❤️"))
        b.await<CoreEvent.Reaction> { it.emoji == "❤️" && it.targetMid == out.mid }

        // The personal grant of Anna for Ben names Ben's key; the link grant is for anyone.
        assertEquals(AddContactResult.Own, a.core.addContact(link))
        assertIs<AddContactResult.AlreadyKnown>(b.core.addContact(link))
    }

    @Test
    fun knownRadioNodeSurvivesPacketsWithoutNode() = world {
        val a = device("Anna")
        val b = device("Ben")
        b.core.addContact(a.core.ownContactLink())
        b.core.setProfile("Ben", node = 1234)
        val first = assertNotNull(b.core.sendDirect(a.deviceId, "mit Funk"))
        a.await<CoreEvent.Message> { it.mid == first.mid }
        eventually { a.core.membersOf(MemberState.CONTACTS).single { it.deviceId == b.deviceId }.node == 1234L }

        // Radio not connected for a moment: the packet carries no node (hardware test 02.11.2026).
        b.core.setProfile("Ben", node = 0)
        val second = assertNotNull(b.core.sendDirect(a.deviceId, "ohne Funk"))
        a.await<CoreEvent.Message> { it.mid == second.mid }
        assertEquals(1234L, a.core.membersOf(MemberState.CONTACTS).single { it.deviceId == b.deviceId }.node)
    }

    @Test
    fun settingsBeforeStartDoNotWipeTheState() = world {
        val a = device("Anna")
        val b = device("Ben")
        b.core.addContact(a.core.ownContactLink())
        eventually { b.core.membersOf(MemberState.CONTACTS).any { it.deviceId == a.deviceId } }
        b.core.stop()

        // Restart: the app sets the person's settings while the core is still opening (hardware test 02.11.2026).
        val again = AnmpCore(scope, net.config(TEST_TIMINGS), AnmpPlatform(b.secrets, b.store, b.blobs, connector = net.connector, log = CoreLog { }), b.host)
        val early = scope.launch { again.setSettings(mapOf("theme" to "dark".encodeToByteArray())) }
        delay(50)
        again.start()
        early.join()
        assertTrue(again.membersOf(MemberState.CONTACTS).any { it.deviceId == a.deviceId }, "contact survives the restart")
        assertEquals("dark", again.settings()["theme"]?.decodeToString())
        again.stop()
    }

    @Test
    fun strangerWithoutGrantIsRefused() = world {
        val a = device("Anna")
        val stranger = testKey("stranger")
        val inbox = Key.parse(a.info.inbox)
        val data = "spam".encodeToByteArray()
        val now = Clock.System.now().epochSeconds
        val meta = Meta.signData(stranger, inbox, data, now, now + 3600, Placement.SPACE)
        val holder = net.list.relays.first { it.node == net.holders(inbox.toString()).first().relay.node }
        val e = assertFailsWith<AndpHttpException> { AndpClient.webPki(holder, net.http).put(Credentials(stranger), meta, data) }
        assertEquals(403, e.status)
        // Without a contact or member the core does not even try.
        assertNull(device("Ben").core.sendDirect(a.deviceId, "hallo"))
    }

    @Test
    fun foreignNetworkLinksAreRefused() = world {
        val a = device("Anna")
        val link = a.core.ownContactLink()
        val other = FakeNet.create(2)
        val foreign = AnmpCore(scope, AnmpCoreConfig(app.anynear.andp.Hash.of(byteArrayOf(9)), other.netKey.key, timings = TEST_TIMINGS), AnmpPlatform(MemorySecretStore(), MemoryKeyValueStore(), MemoryBlobStore(), connector = other.connector))
        foreign.open()
        assertEquals(AddContactResult.ForeignNetwork, foreign.addContact(link))
        val circle = a.core.createCircle("X")
        assertEquals(JoinResult.ForeignNetwork, foreign.joinCircle(a.core.inviteLink(circle.id)!!))
    }

    @Test
    fun forwardedRadioPacketArrives() = world {
        val a = device("Anna")
        val b = device("Ben")
        val c = device("Clara")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        c.core.joinCircle(a.core.inviteLink(circle.id)!!)
        eventually { b.core.canWriteTo(c.deviceId) && c.core.membersOf(circle.id).any { it.deviceId == a.deviceId } }

        // Anna sends a direct radio packet to Clara (no sender key and device in the header, unsigned).
        val anna = AnmpDevice.of(a.secrets.get("core.device.sign")!!, a.secrets.get("core.device.x")!!)
        val clara = AnmpDevice.of(c.secrets.get("core.device.sign")!!, c.secrets.get("core.device.x")!!)
        val mid = Anmp.newMessageId()
        val packet = Anmp.encode(
            AnmpHeader(AnmpKind.DIRECT, anna.tag, to = clara.tag),
            AnmpContent(id = mid, ts = Clock.System.now().epochSeconds, text = "über Funk"),
            Anmp.DIRECT_SCOPE, anna.directCipher(clara.deviceId, clara.publicKey),
        )
        // Ben's app sits at the shared radio and puts it unchanged into Clara's mailbox (ADR 0022).
        assertTrue(b.core.forwardRadioPacket(packet))
        val msg = c.await<CoreEvent.Message> { it.forwarded }
        assertEquals("über Funk", msg.text)
        assertEquals(a.deviceId, msg.fromDevice)
        assertEquals(B64.encode(mid), msg.mid)
        // A packet for ourselves or an unknown installation is not forwarded.
        assertTrue(!c.core.forwardRadioPacket(packet))
    }

    @Test
    fun wantHaveFindsAPicture() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        eventually { a.core.membersOf(circle.id).isNotEmpty() && b.core.membersOf(circle.id).isNotEmpty() }
        val picture = ByteArray(3000) { 3 }
        val hash = B64.encode(AnmpCore.plainHash(picture))
        a.host.shared[hash] = picture
        assertTrue(b.core.want(Conversation.Circle(circle.id), hash))
        val have = b.await<CoreEvent.Have> { it.hash == hash }
        assertContentEquals(picture, assertIs<BlobResult.Found>(b.core.fetchImage(have.ref)).plaintext)
    }

    @Test
    fun ownPictureGoesWithTheAnnouncement() = world {
        val a = device("Anna")
        val b = device("Ben")
        val picture = ByteArray(4000) { 5 }
        a.core.setOwnImage(picture)
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        val m = b.await<CoreEvent.MemberUpdated> { it.member.deviceId == a.deviceId && it.member.image != null }
        val ref = m.member.image!!
        assertEquals(b.core.circle(circle.id)!!.let { CircleKeys.of(it.secret).space.toString() }, ref.space)
        assertContentEquals(picture, assertIs<BlobResult.Found>(b.core.fetchImage(ref)).plaintext)
    }

    @Test
    fun appsPutMissingEntriesBack() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        eventually { b.core.membersOf(circle.id).isNotEmpty() }
        val space = CircleKeys.of(a.core.circle(circle.id)!!.secret).space
        val holders = net.holders(space.toString())
        // One holder is offline while Anna writes, then comes back empty-handed for this entry.
        holders[0].offline = true
        val out = assertNotNull(a.core.sendCircle(circle.id, "nachreichen"))
        b.await<CoreEvent.Message> { it.mid == out.mid }
        a.core.stop()
        holders[0].offline = false
        val id = holders[1].entries(space).last().meta.id
        assertTrue(!holders[0].has(space, id))
        // Ben reaches the holder for reseedAfter without it delivering the entry: he puts it there, re-signed as owner.
        eventually(20.seconds) { holders[0].has(space, id) }
        assertEquals(space, holders[0].get(space, id)!!.meta.writer)
    }

    @Test
    fun pendingWritesSurviveARestart() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        eventually { a.core.membersOf(circle.id).isNotEmpty() }
        net.relays.forEach { it.offline = true }
        val out = assertNotNull(a.core.sendCircle(circle.id, "später"))
        eventually { a.core.pendingWrites() > 0 }
        a.core.stop()
        net.relays.forEach { it.offline = false }
        // Same stores, new core: the pending write goes out.
        val again = AnmpCore(scope, net.config(), AnmpPlatform(a.secrets, a.store, a.blobs, connector = net.connector))
        again.start()
        b.await<CoreEvent.Message>(20.seconds) { it.mid == out.mid }
    }
}
