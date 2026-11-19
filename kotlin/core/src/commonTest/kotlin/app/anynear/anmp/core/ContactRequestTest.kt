package app.anynear.anmp.core

import app.anynear.andp.B64
import app.anynear.anmp.Anmp
import app.anynear.anmp.AnmpContent
import app.anynear.anmp.AnmpDevice
import app.anynear.anmp.AnmpHeader
import app.anynear.anmp.AnmpIntro
import app.anynear.anmp.AnmpIntroCard
import app.anynear.anmp.AnmpIntros
import app.anynear.anmp.AnmpKeys
import app.anynear.anmp.AnmpKind
import app.anynear.anmp.AnmpSpaceKey
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Contacts only with consent (ADR 0116): requests through the contact link and introductions. */
class ContactRequestTest {
    @Test
    fun contactLinkBecomesARequestUntilTheOwnerAgrees() = world {
        val anna = device("Anna")
        val ben = device("Ben")
        assertIs<AddContactResult.Added>(ben.core.addContact(anna.core.ownContactLink()))
        assertEquals(anna.deviceId, ben.core.sentContactRequests().single().deviceId)

        // Ben writes at once; Anna gets it with the request, not as a contact.
        val out = assertNotNull(ben.core.sendDirect(anna.deviceId, "Hallo, hier ist Ben aus dem Chor"))
        val held = anna.await<CoreEvent.Message> { it.mid == out.mid }
        assertTrue(held.fromRequest)
        eventually { anna.core.contactRequests().any { it.person == ben.info.personId } }
        assertTrue(anna.core.membersOf(MemberState.CONTACTS).none { it.person == ben.info.personId })

        assertTrue(anna.core.acceptContactRequest(ben.info.personId))
        eventually { anna.core.membersOf(MemberState.CONTACTS).any { it.person == ben.info.personId } }
        assertTrue(anna.core.contactRequests().isEmpty())
        // Ben's request is answered by Anna's profile.
        eventually { ben.core.sentContactRequests().isEmpty() }
        val reply = assertNotNull(anna.core.sendDirect(ben.info.personId, "Hallo Ben"))
        ben.await<CoreEvent.Message> { it.mid == reply.mid }
    }

    @Test
    fun declinedRequestStaysSilentAndDoesNotComeBack() = world {
        val anna = device("Anna")
        val ben = device("Ben")
        ben.core.addContact(anna.core.ownContactLink())
        eventually { anna.core.contactRequests().any { it.person == ben.info.personId } }
        anna.core.declineContactRequest(ben.info.personId)
        assertTrue(anna.core.contactRequests().isEmpty())

        val again = assertNotNull(ben.core.sendDirect(anna.deviceId, "Noch einmal"))
        assertTrue(anna.none<CoreEvent.Message>(3.seconds) { it.mid == again.mid })
        assertTrue(anna.core.contactRequests().isEmpty())
        assertTrue(anna.core.membersOf(MemberState.CONTACTS).none { it.person == ben.info.personId })
        // Ben learns nothing: his request is still open.
        assertEquals(1, ben.core.sentContactRequests().size)
    }

    @Test
    fun publishedLinkStillAddsAtOnce() = world {
        val support = device("anynear Support")
        val anna = device("Anna")
        anna.core.addContact(support.core.publicContactLink())
        val out = assertNotNull(anna.core.sendDirect(support.deviceId, "Frage"))
        val msg = support.await<CoreEvent.Message> { it.mid == out.mid }
        assertFalse(msg.fromRequest)
        eventually { support.core.membersOf(MemberState.CONTACTS).any { it.person == anna.info.personId } }
        assertTrue(support.core.contactRequests().isEmpty())
    }

    @Test
    fun publishedLinkAddsAtOnceOnAnotherOwnDeviceToo() = world {
        // The link is made on the phone; a request through it arrives while only the tablet of the same person runs.
        val phone = device("anynear Support")
        val tablet = addTablet(phone)
        val link = phone.core.publicContactLink()
        val grant = assertNotNull(phone.core.parseContact(link)).grant
        // Grants are made per second: a link of the tablet in a later second only has this grant if it came by sync.
        delay(1.5.seconds)
        // The tablet learns the grant from the snapshot (sync field 7); its own link for publishing then carries it too.
        eventually { tablet.core.parseContact(tablet.core.publicContactLink())?.grant == grant }
        phone.core.stop()
        devices -= phone

        val anna = device("Anna")
        assertIs<AddContactResult.Added>(anna.core.addContact(link))
        val out = assertNotNull(anna.core.sendDirect(phone.deviceId, "Neue Anfrage: Test"))
        val msg = tablet.await<CoreEvent.Message> { it.mid == out.mid }
        assertFalse(msg.fromRequest)
        eventually { tablet.core.membersOf(MemberState.CONTACTS).any { it.person == anna.info.personId } }
        assertTrue(tablet.core.contactRequests().isEmpty())
    }

    @Test
    fun withdrawingThePublishedLinkReachesTheOtherOwnDevices() = world {
        val phone = device("anynear Support")
        val tablet = addTablet(phone)
        val grant = assertNotNull(phone.core.parseContact(phone.core.publicContactLink())).grant
        delay(1.5.seconds)
        eventually { tablet.core.parseContact(tablet.core.publicContactLink())?.grant == grant }

        phone.core.revokePublicContactLink()
        // The tablet no longer hands out the withdrawn grant, and the phone does not take it back from the tablet.
        eventually { tablet.core.parseContact(tablet.core.publicContactLink())?.grant != grant }
        assertTrue(phone.core.parseContact(phone.core.publicContactLink())?.grant != grant)
    }

    @Test
    fun introductionNeedsBothToAgree() = world {
        val anna = device("Anna")
        val ben = device("Ben")
        val ilse = device("Ilse")
        connect(anna, ben)
        connect(anna, ilse)

        // Anna introduces Ilse to Ben: a card in their chat, nothing to reach Ilse with.
        val out = assertNotNull(anna.core.introduce(ilse.info.personId, ben.info.personId, "Kontakt: Ilse"))
        val msg = ben.await<CoreEvent.Message> { it.mid == out.mid }
        val intro = assertNotNull(msg.intro)
        assertEquals(ilse.info.personId, intro.person)
        assertEquals("Ilse", intro.name)
        assertEquals(anna.info.personId, intro.byPerson)
        assertTrue(ben.core.membersOf(MemberState.CONTACTS).none { it.person == ilse.info.personId })

        // Ben wants her: Anna passes it on, Ilse gets a request naming Anna.
        assertIs<AddContactResult.Requested>(ben.core.acceptIntro(intro))
        assertEquals(ilse.info.personId, ben.core.sentContactRequests().single().person)
        anna.await<CoreEvent.IntroAccepted> { it.toPerson == ben.info.personId && it.person == ilse.info.personId }
        eventually { ilse.core.contactRequests().any { it.person == ben.info.personId } }
        val req = ilse.core.contactRequests().single()
        assertEquals(anna.info.personId, req.introducedBy)
        assertEquals("Anna", req.introducedByName)
        assertTrue(ilse.core.membersOf(MemberState.CONTACTS).none { it.person == ben.info.personId })

        // Ilse agrees: both are contacts and can write.
        assertTrue(ilse.core.acceptContactRequest(ben.info.personId))
        // At once, before Ben's chain arrives: the chat opened on agreeing is writable (ADR 0116).
        assertTrue(ilse.core.canWriteTo(ben.info.personId))
        eventually { ben.core.membersOf(MemberState.CONTACTS).any { it.person == ilse.info.personId } }
        eventually { ben.core.sentContactRequests().isEmpty() }
        val hi = assertNotNull(ben.core.sendDirect(ilse.info.personId, "Hallo Ilse"))
        val got = ilse.await<CoreEvent.Message> { it.mid == hi.mid }
        assertFalse(got.fromRequest)
    }

    @Test
    fun introductionCardIsOnlyForItsReader() = world {
        val anna = device("Anna")
        val ben = device("Ben")
        val ilse = device("Ilse")
        val eve = device("Eve")
        connect(anna, ben)
        connect(anna, ilse)
        val out = assertNotNull(anna.core.introduce(ilse.info.personId, ben.info.personId, "Kontakt: Ilse"))
        val intro = assertNotNull(ben.await<CoreEvent.Message> { it.mid == out.mid }.intro)
        // Someone else with the card cannot use it.
        assertEquals(AddContactResult.Invalid, eve.core.acceptIntro(intro))
    }

    @Test
    fun answeredOnOneDeviceIsGoneOnTheOther() = world {
        val anna = device("Anna")
        val tablet = addTablet(anna)
        val ben = device("Ben")
        val clara = device("Clara")
        ben.core.addContact(anna.core.ownContactLink())
        clara.core.addContact(anna.core.ownContactLink())
        eventually { tablet.core.contactRequests().map { it.person }.toSet() == setOf(ben.info.personId, clara.info.personId) }
        eventually { anna.core.contactRequests().size == 2 }

        // Agreed on the phone: the tablet takes Ben as contact; declined on the phone: the tablet drops Clara.
        assertTrue(anna.core.acceptContactRequest(ben.info.personId))
        anna.core.declineContactRequest(clara.info.personId)
        eventually { tablet.core.contactRequests().isEmpty() }
        eventually { tablet.core.membersOf(MemberState.CONTACTS).any { it.person == ben.info.personId } }
        assertTrue(tablet.core.membersOf(MemberState.CONTACTS).none { it.person == clara.info.personId })
    }

    @Test
    fun ownOtherDeviceLetsInTheAnswerWithoutARequest() = world {
        // Ben accepts an introduction on the phone; Ilse agrees while only his tablet runs. The tablet knows Ben asked
        // (snapshot) and takes Ilse as contact instead of showing „Ilse möchte dein Kontakt sein“.
        val anna = device("Anna")
        val ben = device("Ben")
        val tablet = addTablet(ben)
        val ilse = device("Ilse")
        connect(anna, ben)
        connect(anna, ilse)
        val out = assertNotNull(anna.core.introduce(ilse.info.personId, ben.info.personId, "Kontakt: Ilse"))
        val intro = assertNotNull(ben.await<CoreEvent.Message> { it.mid == out.mid }.intro)
        assertIs<AddContactResult.Requested>(ben.core.acceptIntro(intro))
        eventually { tablet.core.sentContactRequests().any { it.person == ilse.info.personId } }
        ben.core.stop()
        devices -= ben

        eventually { ilse.core.contactRequests().any { it.person == ben.info.personId } }
        assertTrue(ilse.core.acceptContactRequest(ben.info.personId))
        eventually { tablet.core.membersOf(MemberState.CONTACTS).any { it.person == ilse.info.personId } }
        assertTrue(tablet.core.contactRequests().isEmpty())
        assertTrue(tablet.all<CoreEvent.ContactRequestsChanged>().none { it.ended != null })
        eventually { tablet.core.sentContactRequests().none { it.person == ilse.info.personId } }
        eventually { tablet.core.canWriteTo(ilse.info.personId) }
    }

    @Test
    fun contactKeepsWhenItWasAddedOnEveryOwnDevice() = world {
        // The app sorts a contact without messages by this time (ADR 0008).
        val anna = device("Anna")
        val ben = device("Ben")
        val before = CoreClock.SYSTEM.nowMillis()
        connect(anna, ben)
        eventually { ben.core.membersOf(MemberState.CONTACTS).any { it.person == anna.info.personId } }
        val after = CoreClock.SYSTEM.nowMillis()
        val added = anna.core.membersOf(MemberState.CONTACTS).single { it.person == ben.info.personId }.addedAt
        assertTrue(added in before..after, "Anna: $added")
        val annaAtBen = ben.core.membersOf(MemberState.CONTACTS).single { it.person == anna.info.personId }.addedAt
        assertTrue(annaAtBen in before..after, "Ben: $annaAtBen")

        // A new own device takes it from the snapshot, not the time it got it; later updates keep it.
        kotlinx.coroutines.delay(20)
        val tablet = addTablet(anna)
        eventually { tablet.core.membersOf(MemberState.CONTACTS).any { it.person == ben.info.personId } }
        assertEquals(added, tablet.core.membersOf(MemberState.CONTACTS).single { it.person == ben.info.personId }.addedAt)
        val out = assertNotNull(ben.core.sendDirect(anna.info.personId, "Hallo"))
        tablet.await<CoreEvent.Message> { it.mid == out.mid }
        assertEquals(added, tablet.core.membersOf(MemberState.CONTACTS).single { it.person == ben.info.personId }.addedAt)
        assertEquals(added, anna.core.membersOf(MemberState.CONTACTS).single { it.person == ben.info.personId }.addedAt)
    }

    // --- The card on every way a direct message arrives (not only the mailbox of the receiving device) -------------

    @Test
    fun introductionCardOnTheIntroducersOtherDevice() = world {
        // Anna introduces on the phone: her tablet shows the same card („Du stellst Ilse vor“), not the stand-in text.
        val anna = device("Anna")
        val tablet = addTablet(anna)
        val ben = device("Ben")
        val ilse = device("Ilse")
        connect(anna, ben)
        connect(anna, ilse)
        val out = assertNotNull(anna.core.introduce(ilse.info.personId, ben.info.personId, "Kontakt: Ilse"))
        val copy = tablet.await<CoreEvent.Message> { it.mid == out.mid }
        assertEquals(anna.deviceId, copy.fromDevice)
        val intro = assertNotNull(copy.intro)
        assertEquals(ilse.info.personId, intro.person)
        assertEquals("Ilse", intro.name)
        assertEquals(anna.info.personId, intro.byPerson)
    }

    @Test
    fun introductionCardOnDevicesAddedLater() = world {
        // Both sides pair a new device (a browser) after the introduction: its history keeps the card.
        val anna = device("Anna")
        val ben = device("Ben")
        val ilse = device("Ilse")
        connect(anna, ben)
        connect(anna, ilse)
        val out = assertNotNull(anna.core.introduce(ilse.info.personId, ben.info.personId, "Kontakt: Ilse"))
        ben.await<CoreEvent.Message> { it.mid == out.mid }

        val benWeb = addTablet(ben, "Browser")
        val got = assertNotNull(benWeb.await<CoreEvent.Message> { it.mid == out.mid }.intro)
        assertEquals(ilse.info.personId, got.person)
        assertEquals(anna.info.personId, got.byPerson)
        // The card is one Ben can use there.
        eventually { benWeb.core.canWriteTo(anna.info.personId) }
        assertIs<AddContactResult.Requested>(benWeb.core.acceptIntro(got))

        val annaWeb = addTablet(anna, "Browser")
        val sent = assertNotNull(annaWeb.await<CoreEvent.Message> { it.mid == out.mid }.intro)
        assertEquals(ilse.info.personId, sent.person)
        assertEquals(anna.info.personId, sent.byPerson)
    }

    @Test
    fun introductionCardInAForwardedRadioPacket() = world {
        val anna = device("Anna")
        val ben = device("Ben")
        val ilse = device("Ilse")
        val clara = device("Clara")
        connect(anna, ben)
        connect(anna, ilse)
        connect(anna, clara)
        connect(clara, ben)

        // Anna's radio packet with a card for Ben, put into Ben's mailbox by Clara's app at a shared radio (ADR 0022).
        val annaDevice = AnmpDevice.of(anna.secrets.get(CoreIdentity.SIGN)!!, anna.secrets.get(CoreIdentity.X)!!)
        val benDevice = AnmpDevice.of(ben.secrets.get(CoreIdentity.SIGN)!!, ben.secrets.get(CoreIdentity.X)!!)
        val card = AnmpIntroCard(
            AnmpKeys.randomKey().copyOf(Anmp.DEVICE_BYTES), Clock.System.now().epochSeconds + AnmpIntros.LIFETIME_SECONDS,
            B64.decode(ilse.info.personId), "Ilse", B64.decode(ben.info.personId), B64.decode(anna.info.personId),
        )
        val signed = AnmpIntros.sign(AnmpSpaceKey.fromPrivateKey(anna.secrets.get(CoreIdentity.PERSON)!!), card)
        val mid = Anmp.newMessageId()
        val packet = Anmp.encode(
            AnmpHeader(AnmpKind.DIRECT, annaDevice.tag, to = benDevice.tag),
            AnmpContent(id = mid, ts = Clock.System.now().epochSeconds, text = "Kontakt: Ilse", intro = AnmpIntro(signed)),
            Anmp.DIRECT_SCOPE, annaDevice.directCipher(benDevice.deviceId, benDevice.publicKey),
        )
        assertTrue(clara.core.forwardRadioPacket(packet))
        val msg = ben.await<CoreEvent.Message> { it.mid == B64.encode(mid) }
        assertTrue(msg.forwarded)
        val intro = assertNotNull(msg.intro)
        assertEquals(ilse.info.personId, intro.person)
        assertEquals(anna.info.personId, intro.byPerson)
    }

    @Test
    fun introductionCardInTheWakeUp() = world {
        // The notification extension fetches the message while the app sleeps (ADR 0117): with its card.
        val anna = device("Anna")
        val ben = device("Ben")
        val ilse = device("Ilse")
        connect(anna, ben)
        connect(anna, ilse)
        ben.core.stop()
        devices -= ben
        val nse = AnmpCore(scope, net.config(), AnmpPlatform(ben.secrets, ben.store, ben.blobs, connector = net.connector))
        nse.open()
        val out = assertNotNull(anna.core.introduce(ilse.info.personId, ben.info.personId, "Kontakt: Ilse"))
        anna.await<CoreEvent.Stored> { it.ref == out.mid }
        val found = mutableListOf<CoreEvent.Message>()
        eventually { found += nse.wakeFetch().filterIsInstance<CoreEvent.Message>().filter { it.mid == out.mid }; found.isNotEmpty() }
        val intro = assertNotNull(found.single().intro)
        assertEquals(ilse.info.personId, intro.person)
        assertEquals(anna.info.personId, intro.byPerson)
    }

    /** Anna and Ben as contacts the new way: Ben adds Anna's link, Anna agrees. */
    private suspend fun connect(a: Device, b: Device) {
        b.core.addContact(a.core.ownContactLink())
        eventually { a.core.contactRequests().any { it.person == b.info.personId } }
        assertTrue(a.core.acceptContactRequest(b.info.personId))
        eventually { b.core.sentContactRequests().none { it.deviceId == a.deviceId } }
        eventually { a.core.canWriteTo(b.deviceId) }
    }

    /**
     * Anna removes Ben (her grants for him are revoked) and later adds his link again: Ben is still her contact on his
     * side, takes her new profile as is and can write to her again with the new grant, not the revoked one.
     */
    @Test
    fun removedContactAddedAgainCanWriteAgain() = world {
        val anna = device("Anna")
        val ben = device("Ben")
        // Both add each other's link: no request is left over, each holds a personal grant of the other.
        ben.core.addContact(anna.core.ownContactLink())
        anna.core.addContact(ben.core.ownContactLink())
        eventually { anna.core.canWriteTo(ben.deviceId) && ben.core.canWriteTo(anna.deviceId) }
        eventually { anna.core.sentContactRequests().isEmpty() && ben.core.sentContactRequests().isEmpty() }
        val first = assertNotNull(ben.core.sendDirect(anna.deviceId, "Erste"))
        anna.await<CoreEvent.Message> { it.mid == first.mid }

        anna.core.removeContact(ben.info.personId)
        val annaInbox = app.anynear.andp.Key.parse(anna.info.inbox)
        eventually { net.holders(anna.info.inbox).all { it.revokedIn(annaInbox) > 0 } }
        val second = assertNotNull(ben.core.sendDirect(anna.deviceId, "Zweite"))
        // Refused: the older grant of Anna's link does not count as a way back.
        ben.await<CoreEvent.SendFailed> { it.ref == second.mid }

        // Grants are dated in whole seconds: the new one must be newer than the revoked one.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(1_100) }
        val profiles = ben.all<CoreEvent.Profile>().count { it.deviceId == anna.deviceId }
        assertIs<AddContactResult.Added>(anna.core.addContact(ben.core.ownContactLink()))
        // Ben takes Anna's new profile with its new grant; he is still her contact on his side.
        eventually { ben.all<CoreEvent.Profile>().count { it.deviceId == anna.deviceId } > profiles }
        val third = assertNotNull(ben.core.sendDirect(anna.deviceId, "Dritte"))
        anna.await<CoreEvent.Message>(30.seconds) { it.mid == third.mid }
    }
}
