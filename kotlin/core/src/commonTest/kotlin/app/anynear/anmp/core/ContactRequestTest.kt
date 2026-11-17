package app.anynear.anmp.core

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

    /** Anna and Ben as contacts the new way: Ben adds Anna's link, Anna agrees. */
    private suspend fun connect(a: Device, b: Device) {
        b.core.addContact(a.core.ownContactLink())
        eventually { a.core.contactRequests().any { it.person == b.info.personId } }
        assertTrue(a.core.acceptContactRequest(b.info.personId))
        eventually { b.core.sentContactRequests().none { it.deviceId == a.deviceId } }
        eventually { a.core.canWriteTo(b.deviceId) }
    }
}
