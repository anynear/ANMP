package app.anynear.anmp.core

import app.anynear.andp.Grant
import app.anynear.andp.GrantBody
import app.anynear.andp.Rights
import app.anynear.anmp.Anmp
import app.anynear.anmp.AnmpAliveState
import app.anynear.anmp.AnmpCareState
import app.anynear.anmp.AnmpKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/** The radio way in the core (ADR 0051, 0057), over a radio in memory. */
class RadioWayTest {
    private suspend fun World.inboxConfig(): AnmpCoreConfig {
        val owner = testKey("radio inbox owner")
        val now = Clock.System.now().epochSeconds
        val grant = Grant.issue(owner, GrantBody(owner.key, Rights(1) + Rights(2) + Rights(4), null, null, now + 86_400, now - 60))
        val base = net.config()
        return AnmpCoreConfig(base.networkId, base.networkKey, seedUrl = base.seedUrl, timings = base.timings,
            radioInboxSpace = owner.key.toString(), radioInboxGrant = grant.toString())
    }

    private suspend fun World.circleOf(vararg devices: Device): CircleState {
        val circle = devices.first().core.createCircle("Familie")
        val link = assertNotNull(devices.first().core.inviteLink(circle.id))
        for (d in devices.drop(1)) assertIs<JoinResult.Joined>(d.core.joinCircle(link))
        for (a in devices) for (b in devices) if (a != b) a.await<CoreEvent.MemberUpdated>(30.seconds) { it.member.deviceId == b.deviceId && it.member.circleId == circle.id }
        return circle
    }

    /** Radio of this device, listening before it returns (the core collects asynchronously). */
    private suspend fun Device.radio(air: FakeAir, host: FakeRadioHost = FakeRadioHost()): FakeRadio =
        air.radio(name).also { start(it, host) }

    private suspend fun Device.start(radio: FakeRadio, host: FakeRadioHost, timings: RadioTimings = TEST_RADIO_TIMINGS) {
        core.startRadio(radio, host, timings)
        eventually { radio.frames.subscriptionCount.value > 0 }
    }

    private fun newMid() = app.anynear.andp.B64.encode(Anmp.newMessageId())

    @Test
    fun radioRule() {
        for (kind in listOf(AnmpKind.CIRCLE, AnmpKind.DIRECT, AnmpKind.REACT, AnmpKind.DELETE, AnmpKind.READ, AnmpKind.RECEIPT, AnmpKind.ALIVE, AnmpKind.CARE, AnmpKind.ASK, AnmpKind.PATRON, AnmpKind.PICKUP)) {
            assertTrue(kind.radio, "${kind.conv} over radio")
        }
        for (kind in listOf(AnmpKind.MEMBER, AnmpKind.META, AnmpKind.PROFILE, AnmpKind.WANT, AnmpKind.HAVE, AnmpKind.HISTORY, AnmpKind.LEFT, AnmpKind.REKEY, AnmpKind.REMOVED, AnmpKind.SYNC)) {
            assertFalse(kind.radio, "${kind.conv} not over radio")
        }
    }

    @Test
    fun circleMessageReactionAndStranger() = world {
        val a = device("Anna")
        val b = device("Ben")
        val c = device("Carl")
        val circle = circleOf(a, b)
        val air = FakeAir()
        a.radio(air)
        b.radio(air)
        c.radio(air)

        val mid = newMid()
        val packet = assertNotNull(a.core.radioTextPacket(circle.id, null, mid, 1_700_000_000_000, "Bin im Wald"))
        assertTrue(Anmp.fitsRadio(packet))
        assertNotNull(a.core.sendRadioPacket(packet))
        val got = b.await<CoreEvent.RadioMessage> { it.mid == mid }
        assertEquals("Bin im Wald", got.text)
        assertEquals(Conversation.Circle(circle.id), got.conversation)
        assertEquals(a.deviceId, got.fromDevice)
        assertEquals("Anna", got.fromName)
        assertEquals(1_700_000_000_000, got.timestampMillis)
        assertFalse(got.relayed)
        assertTrue(got.fromNode != 0)

        assertNotNull(b.core.radioReact(circle.id, null, a.info.tag, mid, "👍", 1_700_000_001_000))
        val r = a.await<CoreEvent.Reaction> { it.targetMid == mid }
        assertEquals("👍", r.emoji)
        assertEquals(b.deviceId, r.fromDevice)
        assertEquals(a.info.tag, r.targetTag)

        // Carl is in no circle with them: he hears the packets but cannot place them.
        assertTrue(c.none<CoreEvent.RadioMessage>(1.seconds))
        assertTrue(c.none<CoreEvent.Reaction>(1.seconds))
    }

    /**
     * Signs of life and the helpers' answers go over radio like over the internet (ADR 0083 §7): with the relays down the
     * helper still learns the plan, the owner sees that it arrived, and the answer comes back. Confirmations of a plan go
     * over radio at most every 15 minutes; the end always.
     */
    @Test
    fun shortLabelGoesAlongOverRadio() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = circleOf(a, b)
        val air = FakeAir()
        a.radio(air)
        b.radio(air)
        net.relays.forEach { it.offline = true }
        val now = Clock.System.now().toEpochMilliseconds()
        val plan = assertNotNull(a.core.startAlive(circle.id, "Wandern", until = now + 3_600_000L, quiet = AliveLogic.MIN_QUIET, motion = true))
        b.await<CoreEvent.AliveChanged>(30.seconds) { it.planId == plan.id }
        assertEquals("Wandern", b.core.aliveStatus(plan.id)?.plan?.label)
        assertTrue(assertNotNull(b.core.aliveStatus(plan.id)).plan.motion)
    }

    @Test
    fun signOfLifeAndCareOverRadio() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = circleOf(a, b)
        val air = FakeAir()
        a.radio(air)
        b.radio(air)
        net.relays.forEach { it.offline = true }

        val now = Clock.System.now().toEpochMilliseconds()
        val label = "Allein im Wald, zurück gegen sieben Uhr"
        val plan = assertNotNull(a.core.startAlive(circle.id, label, until = now + 8 * 3_600_000L, quiet = AliveLogic.MIN_QUIET))
        b.await<CoreEvent.AliveChanged>(30.seconds) { it.planId == plan.id }
        val seen = assertNotNull(b.core.aliveStatus(plan.id))
        assertEquals(a.info.personId, seen.plan.owner)
        assertEquals(circle.id, seen.plan.circleId)
        assertEquals(AnmpAliveState.START, seen.plan.state)
        assertEquals(plan.due / 1000, seen.plan.due / 1000)
        // The whole plan with this label does not fit into a radio packet: the label stays behind until the internet.
        assertEquals("", seen.plan.label)
        // Ben's radio receipt reaches Anna without internet (ADR 0083 §5.1).
        eventually { a.core.aliveStatus(plan.id)?.plan?.heard?.any { it.person == b.info.personId } == true }
        val sent = air.sent.count { (_, p) -> Anmp.peek(p)?.kind == AnmpKind.ALIVE }
        assertEquals(1, sent)
        assertTrue(air.sent.filter { (_, p) -> Anmp.peek(p)?.kind == AnmpKind.ALIVE }.all { (_, p) -> Anmp.fitsRadio(p) })

        // A tap right after the start does not go over radio again.
        assertTrue(a.core.confirmAlive(plan.id))
        delay(500.milliseconds)
        assertEquals(sent, air.sent.count { (_, p) -> Anmp.peek(p)?.kind == AnmpKind.ALIVE })

        assertTrue(b.core.careAlive(plan.id, AnmpCareState.LOOKING))
        eventually { a.core.aliveStatus(plan.id)?.plan?.care?.any { it.person == b.info.personId && it.state == AnmpCareState.LOOKING } == true }

        assertTrue(a.core.endAlive(plan.id))
        eventually { b.core.aliveStatus(plan.id)?.plan?.state == AnmpAliveState.END }

        // Back online, the copies from the internet change nothing: the newer state stays.
        net.relays.forEach { it.offline = false }
        eventually { b.core.aliveStatus(plan.id)?.plan?.label == label }
        assertEquals(AnmpAliveState.END, b.core.aliveStatus(plan.id)?.plan?.state)
    }

    /**
     * Ben is removed from the circle but still Anna's contact, so she knows his device. With the previous circle key he
     * must not write into the circle over radio any more, neither as himself nor as Clara (ADR 0021).
     */
    @Test
    fun removedMemberCannotWriteIntoTheCircleOverRadio() = world {
        val a = device("Anna")
        val b = device("Ben")
        val c = device("Clara")
        val circle = circleOf(a, b, c)
        b.core.addContact(a.core.ownContactLink()) // circle members need no request (ADR 0116)
        a.await<CoreEvent.MemberUpdated>(30.seconds) { it.member.deviceId == b.deviceId && it.member.circleId == MemberState.CONTACTS }
        val before = assertNotNull(b.core.circle(circle.id))
        a.core.removeMember(circle.id, b.deviceId)
        b.await<CoreEvent.KeyChanged>(30.seconds) { it.kind == CoreEvent.KIND_REMOVED_YOU }
        eventually { c.core.membersOf(circle.id).none { it.deviceId == b.deviceId } }
        val air = FakeAir()
        a.radio(air)
        b.radio(air)
        c.radio(air)

        // His app no longer offers the circle for radio; an attacker builds the packet with the kept key himself.
        assertNull(b.core.radioTextPacket(circle.id, null, newMid(), Clock.System.now().toEpochMilliseconds(), "x"))
        val mid = newMid()
        val content = app.anynear.anmp.AnmpContent(id = app.anynear.andp.B64.decode(mid), ts = Clock.System.now().epochSeconds, text = "Ich bin noch da")
        assertNotNull(b.core.sendRadioPacket(assertNotNull(b.core.radioKeys).circlePacket(AnmpKind.CIRCLE, before, content)))
        assertTrue(a.none<CoreEvent.RadioMessage>(2.seconds) { it.mid == mid })
        assertTrue(c.none<CoreEvent.RadioMessage>(1.seconds) { it.mid == mid })

        // The remaining members still reach each other over radio with the new key.
        val ok = newMid()
        c.core.sendRadioPacket(assertNotNull(c.core.radioTextPacket(circle.id, null, ok, Clock.System.now().toEpochMilliseconds(), "Wir zwei")))
        assertEquals(c.deviceId, a.await<CoreEvent.RadioMessage>(30.seconds) { it.mid == ok }.fromDevice)
    }

    @Test
    fun directMessageGetsReceiptAndRead() = world {
        val a = device("Anna")
        val b = device("Ben")
        agreeContact(b.core, a.core)
        a.await<CoreEvent.MemberUpdated>(30.seconds) { it.member.deviceId == b.deviceId && it.member.circleId == MemberState.CONTACTS }
        val air = FakeAir()
        a.radio(air)
        b.radio(air)

        val mid = newMid()
        val packet = assertNotNull(a.core.radioTextPacket(null, b.deviceId, mid, 1_700_000_000_000, "Hallo Ben"))
        a.core.sendRadioPacket(packet)
        val got = b.await<CoreEvent.RadioMessage>(30.seconds) { it.mid == mid }
        // As over the internet: the person once known, the device before that.
        assertTrue(assertIs<Conversation.Direct>(got.conversation).personId in setOf(a.info.personId, a.deviceId))

        // The receipt goes back over radio by itself, once even if the packet is heard again.
        val receipt = a.await<CoreEvent.Receipt>(30.seconds) { mid in it.mids && !it.read }
        assertTrue(receipt.viaRadio)
        // It says how the message came here, not only how the receipt travelled (ADR 0058).
        assertEquals(app.anynear.anmp.AnmpArrived.RADIO, receipt.arrived)
        assertEquals(b.deviceId, receipt.fromDevice)
        a.core.sendRadioPacket(packet)
        b.await<CoreEvent.RadioMessage>(30.seconds, predicate = { it.mid == mid && b.all<CoreEvent.RadioMessage>().size == 2 })
        assertTrue(a.none<CoreEvent.Receipt>(1.seconds) { a.all<CoreEvent.Receipt>().count { r -> mid in r.mids && !r.read } > 1 })

        assertNotNull(b.core.radioRead(a.deviceId, listOf(mid)))
        val read = a.await<CoreEvent.Receipt>(30.seconds) { mid in it.mids && it.read }
        assertTrue(read.viaRadio)
    }

    @Test
    fun radioMessageTellsTheSendersNode() = world {
        val a = device("Anna")
        val b = device("Ben")
        agreeContact(b.core, a.core)
        a.await<CoreEvent.MemberUpdated>(30.seconds) { it.member.deviceId == b.deviceId && it.member.circleId == MemberState.CONTACTS }
        b.await<CoreEvent.MemberUpdated>(30.seconds) { it.member.deviceId == a.deviceId }
        // Anna's radio changes without internet: only the radio packet can tell Ben (ADR 0058).
        a.core.setProfile("Anna", node = 0x1234abcdL)
        assertTrue(b.core.members.value.none { it.deviceId == a.deviceId && it.node == 0x1234abcdL })
        val air = FakeAir()
        a.radio(air)
        b.radio(air)

        val mid = newMid()
        a.core.sendRadioPacket(assertNotNull(a.core.radioTextPacket(null, b.deviceId, mid, 1_700_000_000_000, "Hallo Ben")))
        b.await<CoreEvent.RadioMessage>(30.seconds) { it.mid == mid }
        eventually { b.core.members.value.any { it.deviceId == a.deviceId && it.node == 0x1234abcdL } }

        // A text that fills the packet still goes, then without the node: text wins.
        val long = "x".repeat(152)
        assertNotNull(a.core.radioTextPacket(null, b.deviceId, newMid(), 1_700_000_000_000, long))
    }

    @Test
    fun nothingWithoutRadio() = world {
        val a = device("Anna")
        assertNull(a.core.radioTextPacket(null, a.deviceId, newMid(), 0, "x"))
        assertNull(a.core.radioRead(a.deviceId, listOf(newMid())))
        val air = FakeAir()
        val radio = a.radio(air)
        val circle = a.core.createCircle("Familie")
        assertTrue(a.core.radioTextLimit(circle.id) > 50)
        radio.ready.value = false
        assertNull(a.core.radioRead(a.deviceId, listOf(newMid())), "unknown device")
    }

    /**
     * Clara has only radio and asks; Ben has internet and becomes her patron (ADR 0051 §5a). Anna's message for Clara
     * lies in the radio inbox; Ben brings it to Clara over radio. Anna and Clara do not hear each other.
     */
    @Test
    fun patronBringsMessageFromRadioInbox() = world {
        val config = inboxConfig()
        val a = device("Anna", config = config)
        val b = device("Ben", config = config)
        val c = device("Clara", config = config)
        val circle = circleOf(a, c)
        val air = FakeAir()
        val ra = a.radio(air)
        val rb = b.radio(air)
        val clara = FakeRadioHost(online = false)
        val rc = air.radio("Clara")
        air.link(rb, rc)
        air.link(ra, rb)

        val mid = newMid()
        val packet = assertNotNull(a.core.radioTextPacket(circle.id, null, mid, Clock.System.now().toEpochMilliseconds(), "Abendessen um sieben"))
        assertTrue(a.core.uplinkRadioPacket(packet))

        c.start(rc, clara)
        val got = c.await<CoreEvent.RadioMessage>(30.seconds) { it.mid == mid }
        assertEquals("Abendessen um sieben", got.text)
        eventually { c.core.servedByPatron.value }
        // Ben is in no circle with them: he carried the packet without placing it.
        assertTrue(b.none<CoreEvent.RadioMessage>(1.seconds))
    }

    /** ADR 0057 step 5: the queue waits for the radio, survives a restart and sends again what the radio never took. */
    @Test
    fun queueWaitsForTheRadioAndKeepsMessagesOverRestarts() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = circleOf(a, b)
        val air = FakeAir()
        b.radio(air)
        val mid = newMid()
        // Before the radio way runs (just after a restart), the message waits in the store.
        a.core.queueRadioText(circle.id, null, mid, 1_700_000_000_000, "Komme später")
        assertTrue(a.store.getString(RadioQueue.KEY).orEmpty().contains(mid))

        val ra = air.radio("Anna")
        ra.ready.value = false
        // The radio did not take the first try at all; the second goes out.
        ra.answers.clear()
        ra.answers += listOf(RadioSendState.FAILED)
        ra.answers += listOf(RadioSendState.HANDED, RadioSendState.RELAYED)
        val hostA = FakeRadioHost()
        a.start(ra, hostA)
        assertTrue(b.none<CoreEvent.RadioMessage>(500.milliseconds))
        ra.ready.value = true

        assertEquals("Komme später", b.await<CoreEvent.RadioMessage> { it.mid == mid }.text)
        a.await<CoreEvent.RadioSend> { it.mid == mid && it.state == RadioSendState.RELAYED }
        assertEquals(2, air.sent.count { it.first == ra }, "sent again because the radio never took it")
        // It stays until a receipt (ADR 0090), then it goes.
        assertTrue(a.store.getString(RadioQueue.KEY).orEmpty().contains(mid))
        hostA.unwanted += mid
        eventually { a.store.getString(RadioQueue.KEY) == null }
    }

    @Test
    fun queueDropsWhatIsNoLongerWanted() = world {
        val a = device("Anna")
        val circle = a.core.createCircle("Familie")
        val air = FakeAir()
        val ra = air.radio("Anna")
        ra.ready.value = false
        val host = FakeRadioHost()
        a.start(ra, host)
        val old = newMid()
        val fresh = newMid()
        host.unwanted += old
        a.core.queueRadioText(circle.id, null, old, 1_700_000_000_000, "längst übers Internet")
        a.core.queueRadioText(circle.id, null, fresh, 1_700_000_001_000, "noch gewollt")
        ra.ready.value = true
        a.await<CoreEvent.RadioSend> { it.mid == fresh && it.state == RadioSendState.HANDED }
        assertEquals(1, air.sent.size)
        assertTrue(a.all<CoreEvent.RadioSend>().none { it.mid == old })
    }

    @Test
    fun onlineTheInternetGoesFirst() = world {
        val a = device("Anna")
        val circle = a.core.createCircle("Familie")
        val air = FakeAir()
        val ra = air.radio("Anna")
        val host = FakeRadioHost(online = true)
        a.core.startRadio(ra, host, RadioTimings(internetFirst = 400.milliseconds, hopRetry = kotlin.time.Duration.parse("1h"), tick = 100.milliseconds))
        val arrived = newMid()
        val waiting = newMid()
        a.core.queueRadioText(circle.id, null, arrived, 1_700_000_000_000, "kam übers Internet an")
        a.core.queueRadioText(circle.id, null, waiting, 1_700_000_001_000, "keine Quittung")
        assertTrue(a.none<CoreEvent.RadioSend>(200.milliseconds), "nothing over radio while the internet may still deliver")
        // The receipt over the internet came in time: that one never goes over radio.
        host.unwanted += arrived
        a.await<CoreEvent.RadioSend> { it.mid == waiting && it.state == RadioSendState.HANDED }
        assertEquals(1, air.sent.size)
        assertEquals(RadioHops.FIRST, air.hopLimits.single())
        assertTrue(a.all<CoreEvent.RadioSend>().none { it.mid == arrived })
    }

    @Test
    fun withoutReceiptOnceMoreWithSevenHopsAndRememberIt() = world {
        val a = device("Anna")
        val circle = a.core.createCircle("Familie")
        val air = FakeAir()
        val ra = air.radio("Anna")
        val host = FakeRadioHost(online = false)
        a.core.startRadio(ra, host, RadioTimings(internetFirst = 400.milliseconds, hopRetry = 300.milliseconds, tick = 100.milliseconds))
        // Hop limits of the sends of [mid], in order (asks for a patron go out too and do not count).
        fun hopsOf(mid: String) = a.all<CoreEvent.RadioSend>().filter { it.mid == mid && it.state == RadioSendState.QUEUED }.map { air.hopsOf[it.packetId] }
        val first = newMid()
        // Without internet at once, with 5 hops; no receipt → once more with 7, then nothing more.
        a.core.queueRadioText(circle.id, null, first, 1_700_000_000_000, "Hallo?")
        eventually { hopsOf(first).size >= 2 }
        assertEquals(listOf(RadioHops.FIRST, RadioHops.MAX), hopsOf(first).take(2))
        // 5 was not enough: 7 is kept for this circle at once, even without a receipt for the second try (a member
        // that is never reachable must not make every message go twice).
        eventually { a.store.getString(RadioHops.KEY).orEmpty().contains(""""hops":7""") }
        assertTrue(a.none<CoreEvent.RadioSend>(500.milliseconds) { it.mid == first && hopsOf(first).size > 2 }, "no third try")

        val next = newMid()
        a.core.queueRadioText(circle.id, null, next, 1_700_000_002_000, "Noch da?")
        eventually { hopsOf(next).isNotEmpty() }
        assertEquals(RadioHops.MAX, hopsOf(next).first(), "starts with 7")
        // Still no receipt: with 7 there is nothing higher, so it goes only once.
        assertTrue(a.none<CoreEvent.RadioSend>(800.milliseconds) { hopsOf(next).size > 1 }, "only once")
    }

    @Test
    fun circleMessageGetsReceiptOverRadioWithOneHopMore() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = circleOf(a, b)
        val air = FakeAir()
        val ra = a.radio(air)
        val rb = b.radio(air)

        val mid = newMid()
        ra.broadcast(assertNotNull(a.core.radioTextPacket(circle.id, null, mid, 1_700_000_000_000, "Alle da?")), 3)
        b.await<CoreEvent.RadioMessage>(30.seconds) { it.mid == mid }
        // A sender with only radio sees who got it, in a circle too (ADR 0058).
        val receipt = a.await<CoreEvent.Receipt>(30.seconds) { mid in it.mids && !it.read }
        assertTrue(receipt.viaRadio)
        assertEquals(b.deviceId, receipt.fromDevice)
        assertEquals(app.anynear.anmp.AnmpArrived.RADIO, receipt.arrived)
        // The message came with 3 hops; the receipt goes back with one more.
        val index = air.sent.indexOfLast { it.first == rb }
        assertEquals(4, air.hopLimits[index])
    }

    @Test
    fun receiptsWaitForTheRadioAndGoTogether() = world {
        val a = device("Anna")
        val b = device("Ben")
        agreeContact(b.core, a.core)
        a.await<CoreEvent.MemberUpdated>(30.seconds) { it.member.deviceId == b.deviceId && it.member.circleId == MemberState.CONTACTS }
        val air = FakeAir()
        a.radio(air)
        val rb = air.radio(b.name)
        b.start(rb, FakeRadioHost(), RadioTimings(hopRetry = kotlin.time.Duration.parse("1h"), internetFirst = kotlin.time.Duration.ZERO, receiptDelay = 1.seconds))

        val first = newMid()
        val second = newMid()
        a.core.sendRadioPacket(assertNotNull(a.core.radioTextPacket(null, b.deviceId, first, 1_700_000_000_000, "Eins")))
        a.core.sendRadioPacket(assertNotNull(a.core.radioTextPacket(null, b.deviceId, second, 1_700_000_001_000, "Zwei")))
        b.await<CoreEvent.RadioMessage>(30.seconds) { it.mid == second }
        b.await<CoreEvent.RadioMessage>(30.seconds) { it.mid == first }
        // Ben's phone loses its radio before the receipts go: they wait instead of getting lost.
        rb.ready.value = false
        assertTrue(a.none<CoreEvent.Receipt>(2.seconds))
        rb.ready.value = true
        val receipt = a.await<CoreEvent.Receipt>(30.seconds) { !it.read }
        assertEquals(setOf(first, second), receipt.mids.toSet(), "one packet for both")
        assertEquals(1, air.sent.count { it.first == rb })
    }

    @Test
    fun directMessageGoesOnceToEachDeviceOfThePerson() = world {
        val a = device("Anna")
        val b = device("Ben")
        val c = device("Carl")
        agreeContact(b.core, a.core)
        agreeContact(c.core, a.core)
        a.await<CoreEvent.MemberUpdated>(30.seconds) { it.member.deviceId == b.deviceId && it.member.circleId == MemberState.CONTACTS }
        a.await<CoreEvent.MemberUpdated>(30.seconds) { it.member.deviceId == c.deviceId && it.member.circleId == MemberState.CONTACTS }
        val air = FakeAir()
        val ra = air.radio(a.name)
        a.start(ra, FakeRadioHost(online = false))
        b.radio(air)
        c.radio(air)

        // The same message for two devices: one entry each, not one for the message (a person with phone and tablet).
        val mid = newMid()
        a.core.queueRadioText(null, b.deviceId, mid, 1_700_000_000_000, "Hallo")
        a.core.queueRadioText(null, c.deviceId, mid, 1_700_000_000_000, "Hallo")
        a.core.queueRadioText(null, c.deviceId, mid, 1_700_000_000_000, "Hallo")
        b.await<CoreEvent.RadioMessage>(30.seconds) { it.mid == mid }
        c.await<CoreEvent.RadioMessage>(30.seconds) { it.mid == mid }
        assertEquals(2, a.all<CoreEvent.RadioSend>().count { it.mid == mid && it.state == RadioSendState.QUEUED })
    }

    @Test
    fun directMessageOverRadioReachesEveryDeviceOfThePersonOnce() = world {
        // ADR 0107: to a person with a mailbox recipient one radio packet reaches phone and tablet.
        val phone = device("Anna")
        val tablet = addTablet(phone)
        eventually { tablet.core.ownDevices.value.size == 2 }
        val ben = device("Ben")
        agreeContact(ben.core, phone.core)
        eventually { ben.core.membersOf(MemberState.CONTACTS).count { it.person == phone.info.personId } == 2 }
        eventually { phone.core.membersOf(MemberState.CONTACTS).any { it.person == ben.info.personId } }
        eventually { tablet.core.membersOf(MemberState.CONTACTS).any { it.person == ben.info.personId } }
        val air = FakeAir()
        val rb = air.radio(ben.name)
        ben.start(rb, FakeRadioHost(online = false))
        phone.radio(air)
        tablet.radio(air)

        // The app queues it once per device; the core sends one entry for the person.
        val mid = newMid()
        ben.core.queueRadioText(null, phone.deviceId, mid, 1_700_000_000_000, "Hallo Anna")
        ben.core.queueRadioText(null, tablet.deviceId, mid, 1_700_000_000_000, "Hallo Anna")
        phone.await<CoreEvent.RadioMessage>(30.seconds) { it.mid == mid && it.fromDevice == ben.deviceId }
        tablet.await<CoreEvent.RadioMessage>(30.seconds) { it.mid == mid && it.fromDevice == ben.deviceId }
        assertEquals(1, ben.all<CoreEvent.RadioSend>().count { it.mid == mid && it.state == RadioSendState.QUEUED })
    }

    @Test
    fun directMessageHeardOnlyOverRadioReachesTheOtherOwnDevices() = world {
        // ADR 0106 §6, 0107 §4: the phone heard it over radio; it writes an own copy, so the tablet gets it too.
        val phone = device("Anna", TEST_TIMINGS.copy(radioCopyWait = 1.seconds))
        val tablet = addTablet(phone)
        eventually { tablet.core.ownDevices.value.size == 2 }
        val ben = device("Ben")
        agreeContact(ben.core, phone.core)
        eventually { ben.core.membersOf(MemberState.CONTACTS).count { it.person == phone.info.personId } == 2 }
        eventually { phone.core.membersOf(MemberState.CONTACTS).any { it.person == ben.info.personId } }
        eventually { tablet.core.membersOf(MemberState.CONTACTS).any { it.person == ben.info.personId } }
        val air = FakeAir()
        ben.start(air.radio(ben.name), FakeRadioHost(online = false))
        phone.radio(air)

        val mid = newMid()
        ben.core.queueRadioText(null, phone.deviceId, mid, 1_700_000_000_000, "Nur Funk")
        phone.await<CoreEvent.RadioMessage>(30.seconds) { it.mid == mid }
        val copy = tablet.await<CoreEvent.Message>(30.seconds) { it.mid == mid }
        assertEquals(ben.deviceId, copy.fromDevice)
        assertEquals(Conversation.Direct(ben.info.personId), copy.conversation)
    }

    private val retryTimings = RadioTimings(
        internetFirst = kotlin.time.Duration.ZERO,
        hopRetry = 200.milliseconds,
        tick = 100.milliseconds,
        receiptDelay = 100.milliseconds,
        retryGaps = listOf(300.milliseconds, 300.milliseconds, 300.milliseconds, 300.milliseconds),
        retryWaitApp = 50.milliseconds to 100.milliseconds,
        retryWaitRadio = 50.milliseconds to 100.milliseconds,
        retryBetween = 50.milliseconds,
        retryCleanup = 200.milliseconds,
    )

    @Test
    fun secondTryIsKeptOverRestarts() = world {
        val a = device("Anna")
        val circle = a.core.createCircle("Familie")
        val air = FakeAir()
        val ra = air.radio("Anna")
        a.core.startRadio(ra, FakeRadioHost(online = false), RadioTimings(internetFirst = kotlin.time.Duration.ZERO, hopRetry = kotlin.time.Duration.parse("1h")))
        val mid = newMid()
        a.core.queueRadioText(circle.id, null, mid, 1_700_000_000_000, "Hallo?")
        a.await<CoreEvent.RadioSend> { it.mid == mid && it.state == RadioSendState.QUEUED }
        // The try with 7 hops waits in the store, not only in memory (ADR 0090).
        eventually { a.store.getString(RadioQueue.KEY).orEmpty().let { it.contains(mid) && it.contains(""""escalate":true""") } }
    }

    @Test
    fun waitingMessageGoesAgainWhenTheTargetsAppIsHeard() = world {
        val a = device("Anna")
        val b = device("Ben")
        agreeContact(b.core, a.core)
        a.await<CoreEvent.MemberUpdated>(30.seconds) { it.member.deviceId == b.deviceId && it.member.circleId == MemberState.CONTACTS }
        b.await<CoreEvent.MemberUpdated>(30.seconds) { it.member.deviceId == a.deviceId }
        val air = FakeAir()
        val ra = air.radio(a.name)
        val rb = air.radio(b.name)
        a.start(ra, FakeRadioHost(online = false), retryTimings)
        b.start(rb, FakeRadioHost(online = false), retryTimings)
        b.core.setProfile("Ben", node = rb.node.toLong())

        // Ben's phone is away from his radio: both tries go nowhere, then the message waits.
        rb.ready.value = false
        val mid = newMid()
        a.core.queueRadioText(null, b.deviceId, mid, 1_700_000_000_000, "Bist du da?")
        eventually { a.all<CoreEvent.RadioSend>().count { it.mid == mid && it.state == RadioSendState.QUEUED } == 2 }
        assertTrue(a.none<CoreEvent.RadioSend>(800.milliseconds) { a.all<CoreEvent.RadioSend>().count { r -> r.mid == mid && r.state == RadioSendState.QUEUED } > 2 }, "no retry without a trigger")

        // Ben is back and sends something: Anna learns his radio (field 10) and hears his app, then sends again.
        rb.ready.value = true
        repeat(2) { i ->
            rb.broadcast(assertNotNull(b.core.radioTextPacket(null, a.deviceId, newMid(), 1_700_000_001_000 + i, "Bin wieder da")), 3)
            delay(400.milliseconds)
        }
        assertEquals("Bist du da?", b.await<CoreEvent.RadioMessage>(30.seconds) { it.mid == mid }.text)
        a.await<CoreEvent.Receipt>(30.seconds) { mid in it.mids && it.viaRadio }
    }

    @Test
    fun waitingMessageGoesAgainOnAnAnsweredRadioCheckButNotTooOften() = world {
        val a = device("Anna")
        val b = device("Ben")
        agreeContact(b.core, a.core)
        a.await<CoreEvent.MemberUpdated>(30.seconds) { it.member.deviceId == b.deviceId && it.member.circleId == MemberState.CONTACTS }
        val air = FakeAir()
        val ra = air.radio(a.name)
        val host = FakeRadioHost(online = false)
        a.start(ra, host, retryTimings.let { RadioTimings(internetFirst = it.internetFirst, hopRetry = it.hopRetry, retryGaps = listOf(300.milliseconds, 1.seconds.times(3600)), retryWaitApp = it.retryWaitApp, retryWaitRadio = it.retryWaitRadio, retryCleanup = it.retryCleanup) })
        // Ben's radio is known from an earlier message over the internet (field 10, ADR 0058).
        b.core.setProfile("Ben", node = 4242)
        eventually { b.core.canWriteTo(a.deviceId) }
        b.core.sendDirect(a.deviceId, "Mein Funkgerät")
        eventually { a.core.members.value.any { it.deviceId == b.deviceId && it.node == 4242L } }

        val mid = newMid()
        a.core.queueRadioText(null, b.deviceId, mid, 1_700_000_000_000, "Hallo")
        fun sends() = a.all<CoreEvent.RadioSend>().count { it.mid == mid && it.state == RadioSendState.QUEUED }
        eventually { sends() == 2 }
        delay(400.milliseconds)
        // An answered radio check of Ben's radio: one retry.
        a.core.radioHeard(4242)
        eventually { sends() == 3 }
        // Heard again at once: the next gap (an hour here) is not over, nothing goes.
        delay(400.milliseconds)
        ra.heardFlow.emit(RadioHeard(4242, hops = 1))
        assertTrue(a.none<CoreEvent.RadioSend>(800.milliseconds) { sends() > 3 })
        // A radio of someone else does not count either.
        a.core.radioHeard(777)
        assertTrue(a.none<CoreEvent.RadioSend>(500.milliseconds) { sends() > 3 })
    }

    @Test
    fun packetThatWaitedInTheRadioUsesTheHourItWasReceived() {
        val now = 1_762_000_000L
        assertEquals(now - 7200, RadioWay.lateSeconds((now - 7200) * 1000, now))
        assertNull(RadioWay.lateSeconds(0, now), "radio without time")
        assertNull(RadioWay.lateSeconds(now * 1000, now), "same hour: the table of now has it")
        assertNull(RadioWay.lateSeconds((now + 3600) * 1000, now), "in the future")
        assertNull(RadioWay.lateSeconds((now - 8L * 24 * 3600) * 1000, now), "too old")
    }

    @Test
    fun withoutTriggerTwiceMoreOnTheOffChanceWithSevenHops() = world {
        val a = device("Anna")
        val circle = a.core.createCircle("Familie")
        val air = FakeAir()
        val ra = air.radio("Anna")
        a.core.startRadio(ra, FakeRadioHost(online = false), RadioTimings(
            internetFirst = kotlin.time.Duration.ZERO,
            hopRetry = 200.milliseconds,
            blindRetryAfter = listOf(300.milliseconds, 300.milliseconds),
            retryWaitRadio = 50.milliseconds to 100.milliseconds,
            retryCleanup = 200.milliseconds,
        ))
        fun hopsOf(mid: String) = a.all<CoreEvent.RadioSend>().filter { it.mid == mid && it.state == RadioSendState.QUEUED }.map { air.hopsOf[it.packetId] }
        val mid = newMid()
        a.core.queueRadioText(circle.id, null, mid, 1_700_000_000_000, "Hört mich wer?")
        // 5, 7 (ADR 0087), then twice on the off chance with 7 (ADR 0090), then nothing.
        eventually { hopsOf(mid).size >= 4 }
        assertEquals(listOf(RadioHops.FIRST, RadioHops.MAX, RadioHops.MAX, RadioHops.MAX), hopsOf(mid).take(4))
        assertTrue(a.none<CoreEvent.RadioSend>(1.seconds) { hopsOf(mid).size > 4 }, "at most two tries on the off chance")
        // It still waits for a trigger (up to 7 days), stored.
        assertTrue(a.store.getString(RadioQueue.KEY).orEmpty().contains(""""blindRetries":2"""))
    }

    @Test
    fun answerOverRadio() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = circleOf(a, b)
        val air = FakeAir()
        a.radio(air)
        b.radio(air)

        val first = newMid()
        val mid = newMid()
        val packet = assertNotNull(a.core.radioTextPacket(circle.id, null, mid, 1_700_000_000_000, "Ja, gern", first, b.info.tag))
        assertTrue(Anmp.fitsRadio(packet))
        assertNotNull(a.core.sendRadioPacket(packet))
        val got = b.await<CoreEvent.RadioMessage> { it.mid == mid }
        assertEquals(first, got.replyTo)
        assertEquals(b.info.tag, got.replyFrom)

        // Text wins: the longest text that fits still goes, then without the reference.
        val long = "x".repeat(a.core.radioTextLimit(circle.id))
        val mid2 = newMid()
        assertNotNull(a.core.sendRadioPacket(assertNotNull(a.core.radioTextPacket(circle.id, null, mid2, 1_700_000_000_000, long, first, b.info.tag))))
        assertNull(b.await<CoreEvent.RadioMessage> { it.mid == mid2 }.replyTo)
    }
}
