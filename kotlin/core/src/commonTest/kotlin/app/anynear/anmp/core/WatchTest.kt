package app.anynear.anmp.core

import app.anynear.andp.Key
import app.anynear.anmp.AnmpArrived
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WatchTest {
    @Test
    fun watchPerSpaceAndFetchInTheWakeUp() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        eventually { a.core.membersOf(circle.id).isNotEmpty() && b.core.canWriteTo(a.deviceId) }

        // Ben's iPhone registers its APNs token for its mailbox and the circle space at their holders (ANDP §10).
        b.core.setPushTarget("00ff00ff", "app.anynear", sandbox = true)
        val circleSpace = CircleKeys.of(b.core.circle(circle.id)!!.secret).space
        val inbox = Key.parse(b.info.inbox)
        eventually {
            net.holders(inbox.toString()).all { r -> r.watches.any { it.space == inbox } } &&
                net.holders(circleSpace.toString()).all { r -> r.watches.any { it.space == circleSpace && it.target.env == "sandbox" } }
        }

        // Receipts and announcements do not wake (ANDP-Notify: none); messages do.
        val out = assertNotNull(a.core.sendCircle(circle.id, "weck mich"))
        b.await<CoreEvent.Message> { it.mid == out.mid }
        val notified = net.relays.flatMap { it.notifiedPuts }
        assertTrue(notified.isNotEmpty())
        assertTrue(notified.all { it.space == circleSpace || it.space == inbox })

        // The app is suspended; the notification extension opens the same stores in its own process and fetches once.
        b.core.stop()
        val nse = AnmpCore(scope, net.config(), AnmpPlatform(b.secrets, b.store, b.blobs, connector = net.connector))
        nse.open()
        val direct = assertNotNull(a.core.sendDirect(b.deviceId, "direkt"))
        a.await<CoreEvent.Stored> { it.ref == direct.mid }
        val found = nse.wakeFetch().filterIsInstance<CoreEvent.Message>()
        assertTrue(found.any { it.mid == direct.mid && it.text == "direkt" }, "wake: ${found.map { it.text }}")
        // A second wake-up does not report the same message again.
        assertEquals(emptyList(), nse.wakeFetch().filterIsInstance<CoreEvent.Message>().filter { it.mid == direct.mid })
        // Nothing new, and every space answered: the wake-up had nothing to show (ADR 0117).
        val again = nse.wakeFetchResult()
        assertTrue(again.complete)
        assertEquals(emptyList(), again.events.filterIsInstance<CoreEvent.Message>())
        // No relay answers: also nothing found, but not complete, a message may be waiting.
        net.relays.forEach { it.offline = true }
        val offline = nse.wakeFetchResult()
        assertEquals(false, offline.complete)
        assertEquals(emptyList(), offline.events)
        net.relays.forEach { it.offline = false }
    }

    @Test
    fun wakeFetchConfirmsReceiptWhileTheAppSleeps() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        eventually { a.core.membersOf(circle.id).isNotEmpty() && b.core.canWriteTo(a.deviceId) }
        eventually { a.core.canWriteTo(b.deviceId) }

        // Ben's phone is locked: only the notification extension runs. Anna sees "arrived" without Ben opening the app.
        b.core.stop()
        val nse = AnmpCore(scope, net.config(), AnmpPlatform(b.secrets, b.store, b.blobs, connector = net.connector))
        nse.open()
        val direct = assertNotNull(a.core.sendDirect(b.deviceId, "direkt"))
        val inCircle = assertNotNull(a.core.sendCircle(circle.id, "im Kreis"))
        a.await<CoreEvent.Stored> { it.ref == direct.mid }
        a.await<CoreEvent.Stored> { it.ref == inCircle.mid }
        val found = nse.wakeFetch().filterIsInstance<CoreEvent.Message>()
        assertTrue(found.any { it.mid == direct.mid } && found.any { it.mid == inCircle.mid })
        for (mid in listOf(direct.mid, inCircle.mid)) {
            val receipt = a.await<CoreEvent.Receipt> { !it.read && mid in it.mids }
            assertEquals(b.deviceId, receipt.fromDevice)
            assertEquals(AnmpArrived.INTERNET, receipt.arrived)
        }
    }

    @Test
    fun wakeReportsAnObjectOnceAlthoughAHolderGetsItLater() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        eventually { a.core.membersOf(circle.id).isNotEmpty() && b.core.canWriteTo(a.deviceId) }
        b.core.stop()
        val nse = AnmpCore(scope, net.config(), AnmpPlatform(b.secrets, b.store, b.blobs, connector = net.connector))
        nse.open()

        // "Sent" counts from the first relay: one holder gets the message only after the first wake-up.
        val space = CircleKeys.of(a.core.circle(circle.id)!!.secret).space
        val holders = net.holders(space.toString())
        holders[0].offline = true
        val out = assertNotNull(a.core.sendCircle(circle.id, "einmal"))
        a.await<CoreEvent.Stored> { it.ref == out.mid }
        assertEquals(1, nse.wakeFetch().filterIsInstance<CoreEvent.Message>().count { it.mid == out.mid })
        holders[0].offline = false
        val id = holders[1].entries(space).last().meta.id
        eventually(20.seconds) { holders[0].has(space, id) }
        assertEquals(emptyList(), nse.wakeFetch().filterIsInstance<CoreEvent.Message>().filter { it.mid == out.mid })
    }

    @Test
    fun webPushTargetForTheBrowser() = world {
        val b = device("Ben")
        // The web client asks the relays for their VAPID key and registers its subscription (ANDP §10.1, ADR 0060).
        assertEquals(FAKE_VAPID_KEY, b.core.webPushKey())
        val endpoint = "https://fcm.googleapis.com/fcm/send/abc:def"
        b.core.setWebPushTarget(endpoint)
        val inbox = Key.parse(b.info.inbox)
        val hex = endpoint.encodeToByteArray().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        eventually {
            net.holders(inbox.toString()).all { r -> r.watches.any { it.space == inbox && it.target.type == "webpush" && it.target.token == hex } }
        }
        // Deregistering removes it again.
        b.core.setWebPushTarget(null)
        eventually { net.relays.none { r -> r.watches.any { it.target.type == "webpush" } } }
    }

    @Test
    fun directReactionOnTheirMessageWakesAndIsFetched() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        eventually { a.core.membersOf(circle.id).isNotEmpty() && b.core.canWriteTo(a.deviceId) }
        eventually { a.core.canWriteTo(b.deviceId) && b.core.canWriteTo(a.deviceId) }

        val out = assertNotNull(b.core.sendDirect(a.deviceId, "Bin gleich da"))
        a.await<CoreEvent.Message> { it.mid == out.mid }
        val inCircle = assertNotNull(b.core.sendCircle(circle.id, "Hallo Kreis"))
        a.await<CoreEvent.Message> { it.mid == inCircle.mid }
        b.core.stop()
        fun notified() = net.relays.sumOf { it.notifiedPuts.size }

        // In a circle the holders cannot tell whose message it is: no wake-up for everyone.
        var before = notified()
        assertTrue(a.core.react(Conversation.Circle(circle.id), b.info.tag, inCircle.mid, "👍"))
        assertEquals(before, notified())

        // Directly on Ben's message: wakes Ben; the extension reads the reaction.
        before = notified()
        assertTrue(a.core.react(Conversation.Direct(b.info.personId), b.info.tag, out.mid, "❤️"))
        eventually { notified() > before }
        val nse = AnmpCore(scope, net.config(), AnmpPlatform(b.secrets, b.store, b.blobs, connector = net.connector))
        nse.open()
        val reactions = nse.wakeFetch().filterIsInstance<CoreEvent.Reaction>()
        val direct = reactions.single { it.targetMid == out.mid }
        assertEquals("❤️", direct.emoji)
        assertEquals(b.info.tag, direct.targetTag)
        assertTrue(direct.objectId.isNotEmpty())
        assertTrue(reactions.any { it.targetMid == inCircle.mid && it.emoji == "👍" }, "circle reaction found along the way")
    }

    @Test
    fun oneWakeUpPerMailboxForAPersonWithTwoDevices() = world {
        val phone = device("Anna")
        val ben = device("Ben")
        assertIs<AddContactResult.Added>(agreeContact(ben.core, phone.core))
        eventually { phone.core.canWriteTo(ben.deviceId) && ben.core.canWriteTo(phone.deviceId) }
        val tablet = addTablet(phone)
        eventually { ben.core.membersOf(MemberState.CONTACTS).any { it.deviceId == tablet.deviceId } }
        val inbox = Key.parse(phone.info.inbox)
        fun wakes() = net.relays.flatMap { it.notifiedPuts }.filter { it.space == inbox }.map { it.id }.distinct().size

        // Each device of Anna gets its own entry, but only one of them wakes: both watch the same mailbox.
        val before = wakes()
        val out = assertNotNull(ben.core.sendDirect(phone.info.personId, "Einmal klingeln"))
        phone.await<CoreEvent.Message> { it.mid == out.mid }
        tablet.await<CoreEvent.Message> { it.mid == out.mid }
        eventually { wakes() > before }
        assertEquals(before + 1, wakes())

        // A reaction on Anna's message, too.
        eventually { phone.core.canWriteTo(ben.info.personId) }
        val reply = assertNotNull(phone.core.sendDirect(ben.info.personId, "Hallo Ben"))
        ben.await<CoreEvent.Message> { it.mid == reply.mid }
        val beforeReaction = wakes()
        assertTrue(ben.core.react(Conversation.Direct(phone.info.personId), phone.info.tag, reply.mid, "👍"))
        eventually { wakes() > beforeReaction }
        assertEquals(beforeReaction + 1, wakes())
    }
}
