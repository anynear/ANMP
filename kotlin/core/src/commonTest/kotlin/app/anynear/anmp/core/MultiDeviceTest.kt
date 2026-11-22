package app.anynear.anmp.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Several devices of one person (ADR 0042): one mailbox, direct messages to all devices, copies and snapshots. */
class MultiDeviceTest {
    @Test
    fun tabletIsTheSamePersonAndGetsEverything() = world {
        val phone = device("Anna")
        val ben = device("Ben")
        assertIs<AddContactResult.Added>(agreeContact(ben.core, phone.core))
        eventually { phone.core.canWriteTo(ben.deviceId) && ben.core.canWriteTo(phone.deviceId) }
        val circle = phone.core.createCircle("Familie")
        assertIs<JoinResult.Joined>(ben.core.joinCircle(phone.core.inviteLink(circle.id)!!))
        eventually { phone.core.membersOf(circle.id).any { it.deviceId == ben.deviceId } }

        val tablet = addTablet(phone)
        assertEquals(phone.info.personId, tablet.info.personId)
        assertEquals(phone.info.inbox, tablet.info.inbox)
        assertEquals(2, phone.core.ownDevices.value.size)

        // The snapshot brings circle and contact; Ben learns the tablet as a device of Anna.
        tablet.await<CoreEvent.CircleUpdated> { it.circle.id == circle.id }
        eventually { tablet.core.membersOf(MemberState.CONTACTS).any { it.person == ben.info.personId } }
        eventually { ben.core.membersOf(circle.id).any { it.deviceId == tablet.deviceId && it.personId == phone.info.personId } }
        eventually { ben.core.membersOf(MemberState.CONTACTS).any { it.deviceId == tablet.deviceId } }

        // Ben writes once; both devices of Anna get it in the same conversation.
        val out = assertNotNull(ben.core.sendDirect(phone.info.personId, "Hallo Anna"))
        val onPhone = phone.await<CoreEvent.Message> { it.mid == out.mid }
        val onTablet = tablet.await<CoreEvent.Message> { it.mid == out.mid }
        assertEquals(Conversation.Direct(ben.info.personId), onPhone.conversation)
        assertEquals(onPhone.conversation, onTablet.conversation)

        // Anna answers on the phone: Ben gets it, the tablet gets a copy in the conversation with Ben.
        eventually { phone.core.canWriteTo(ben.info.personId) }
        val reply = assertNotNull(phone.core.sendDirect(ben.info.personId, "Hallo Ben"))
        ben.await<CoreEvent.Message> { it.mid == reply.mid && it.fromPerson == phone.info.personId }
        val copy = tablet.await<CoreEvent.Message> { it.mid == reply.mid }
        assertEquals(Conversation.Direct(ben.info.personId), copy.conversation)
        assertEquals(phone.info.personId, copy.fromPerson)

        // Read on the phone is read on the tablet.
        phone.core.confirmRead(ben.deviceId, out.mid, onPhone.conversation)
        tablet.await<CoreEvent.ReadElsewhere> { out.mid in it.mids && it.conversation == onPhone.conversation }
        ben.await<CoreEvent.Receipt> { out.mid in it.mids && it.read }
    }

    @Test
    fun firstMessageFromTheLinkReachesAnotherDeviceWhileTheLinkDeviceIsOff() = world {
        // ADR 0084: the link comes from the phone, which is off; only the tablet of the same person runs.
        val phone = device("Support")
        val link = phone.core.ownContactLink()
        assertNotNull(phone.core.parseContact(link)?.mailboxKey)
        val tablet = addTablet(phone)
        phone.core.stop()
        devices -= phone

        val anna = device("Anna")
        assertIs<AddContactResult.Added>(agreeContact(anna.core, tablet.core, link))
        val out = assertNotNull(anna.core.sendDirect(phone.deviceId, "Neue Anfrage"))
        val got = tablet.await<CoreEvent.Message> { it.mid == out.mid }
        assertEquals(Conversation.Direct(anna.info.personId), got.conversation)

        // The tablet answers the receipt and its profile: Anna learns the device list and writes per device from then on.
        anna.await<CoreEvent.Receipt> { out.mid in it.mids }
        eventually { anna.core.membersOf(MemberState.CONTACTS).any { it.deviceId == tablet.deviceId } }
        eventually { tablet.core.canWriteTo(anna.info.personId) }
        val reply = assertNotNull(tablet.core.sendDirect(anna.info.personId, "Wir melden uns"))
        anna.await<CoreEvent.Message> { it.mid == reply.mid }
    }

    @Test
    fun removedTabletStopsAndContactsForgetIt() = world {
        val phone = device("Anna")
        val ben = device("Ben")
        assertIs<AddContactResult.Added>(agreeContact(ben.core, phone.core))
        eventually { phone.core.canWriteTo(ben.deviceId) && ben.core.canWriteTo(phone.deviceId) }
        val tablet = addTablet(phone)
        eventually { ben.core.membersOf(MemberState.CONTACTS).any { it.deviceId == tablet.deviceId } }

        assertFalse(tablet.core.revoked.value)
        assertTrue(phone.core.removeOwnDevice(tablet.deviceId))
        tablet.await<CoreEvent.Revoked>()
        assertTrue(tablet.core.revoked.value)
        eventually { ben.core.membersOf(MemberState.CONTACTS).none { it.deviceId == tablet.deviceId } }
        val out = assertNotNull(ben.core.sendDirect(phone.info.personId, "Nur noch Handy"))
        phone.await<CoreEvent.Message> { it.mid == out.mid }
        assertTrue(tablet.none<CoreEvent.Message>(kotlin.time.Duration.parse("2s")) { it.mid == out.mid })

        // Opened again: the app learns at once that this device is out, so it can delete everything here.
        tablet.core.stop()
        devices -= tablet
        val again = AnmpCore(scope, net.config(TEST_TIMINGS), AnmpPlatform(tablet.secrets, tablet.store, tablet.blobs, connector = net.connector, log = CoreLog { }), tablet.host)
        again.start()
        assertTrue(again.revoked.value)
        assertFalse(phone.core.revoked.value)
        again.stop()
    }

    @Test
    fun pairingByQrHandsOverThePerson() = world {
        val phone = device("Anna")
        val circle = phone.core.createCircle("Familie")
        val fresh = device("Tablet")
        val link = fresh.core.pairingOffer("Tablet")
        assertTrue(link.startsWith("https://anynear.app/p#"))
        // ADR 0093: both devices show the same 6-digit comparison code; another new device would show another one.
        val code = assertNotNull(fresh.core.ownPairingCode())
        assertTrue(code.length == 6 && code.all { it.isDigit() }, code)
        assertEquals(code, phone.core.pairingCode(link))
        val other = device("Fremd")
        val otherLink = other.core.pairingOffer("Tablet")
        assertTrue(phone.core.pairingCode(otherLink) != code)
        assertEquals(null, phone.core.pairingCode("https://anynear.app/k#kein-kopplungslink"))
        val result = assertIs<PairResult.Added>(phone.core.acceptPairing(link, mapOf("pv" to ByteArray(32) { 5 })))
        assertEquals(fresh.deviceId, result.device.deviceId)
        val ownState = assertNotNull(fresh.secrets.get(AnmpCore.STATE_KEY))
        val extras = assertNotNull(fresh.core.awaitPairing(kotlin.time.Duration.parse("15s")))
        assertEquals(5, extras["pv"]?.first()?.toInt())
        // On the phone the running core saves its state once more before the app restarts, with the chain it made for
        // itself before the hand-over (emulator test 12.11.2026): the restart must still be the person.
        fresh.secrets.put(AnmpCore.STATE_KEY, ownState)

        // The app starts the core again with the handed-over keys.
        fresh.core.stop()
        devices -= fresh
        val tablet = device("Anna", secrets = fresh.secrets)
        assertEquals(fresh.deviceId, tablet.deviceId)
        assertEquals(phone.info.personId, tablet.info.personId)
        assertEquals(phone.info.inbox, tablet.info.inbox)
        tablet.await<CoreEvent.CircleUpdated> { it.circle.id == circle.id }
        assertIs<PairResult.AlreadyAdded>(phone.core.acceptPairing(link))
    }

    @Test
    fun afterTheftANewPersonKeyLocksTheStolenDeviceOut() = world {
        val phone = device("Anna")
        val ben = device("Ben")
        assertIs<AddContactResult.Added>(agreeContact(ben.core, phone.core))
        eventually { phone.core.canWriteTo(ben.deviceId) && ben.core.canWriteTo(phone.deviceId) }
        val circle = phone.core.createCircle("Familie")
        assertIs<JoinResult.Joined>(ben.core.joinCircle(phone.core.inviteLink(circle.id)!!))
        // The key change goes to the members the phone knows; Ben is one only once his announcement has arrived.
        eventually { phone.core.membersOf(circle.id).any { it.deviceId == ben.deviceId } }
        val stolen = addTablet(phone, "Tablet")
        val laptop = addTablet(phone, "Laptop")
        eventually { ben.core.membersOf(MemberState.CONTACTS).count { it.person == phone.info.personId } == 3 }
        val oldInbox = phone.info.inbox

        assertTrue(phone.core.renewPersonKey(listOf(stolen.deviceId)))
        val newInbox = phone.core.identity.value!!.inbox
        assertTrue(newInbox != oldInbox)
        assertEquals(phone.info.personId, phone.core.identity.value!!.personId)

        // The laptop gets the new keys, the stolen tablet is out; Ben follows to the new mailbox without a question.
        eventually { laptop.core.identity.value!!.inbox == newInbox }
        stolen.await<CoreEvent.Revoked>()
        eventually { ben.core.membersOf(MemberState.CONTACTS).none { it.deviceId == stolen.deviceId } }
        eventually { ben.core.canWriteTo(phone.info.personId) && ben.core.membersOf(MemberState.CONTACTS).any { it.person == phone.info.personId && it.inbox == newInbox } }
        val out = assertNotNull(ben.core.sendDirect(phone.info.personId, "Neuer Schlüssel?"))
        phone.await<CoreEvent.Message> { it.mid == out.mid }
        laptop.await<CoreEvent.Message> { it.mid == out.mid }
        assertTrue(stolen.none<CoreEvent.Message>(kotlin.time.Duration.parse("2s")) { it.mid == out.mid })

        // The circle has a new key without the stolen tablet.
        eventually { (ben.core.circle(circle.id)?.epoch ?: 0) > 0 }
        assertTrue(stolen.core.circle(circle.id)?.let { it.removed || it.epoch == 0 } ?: true)

        // It keeps its old chain, but knows after a restart that it is out.
        assertTrue(stolen.core.revoked.value)
        stolen.core.stop()
        devices -= stolen
        val again = AnmpCore(scope, net.config(TEST_TIMINGS), AnmpPlatform(stolen.secrets, stolen.store, stolen.blobs, connector = net.connector, log = CoreLog { }), stolen.host)
        again.start()
        assertTrue(again.revoked.value)
        again.stop()
    }

    @Test
    fun aContactKnownByItsChainCanWriteAfterAKeyChange() = world {
        val phone = device("Anna")
        val ben = device("Ben")
        assertIs<AddContactResult.Added>(agreeContact(ben.core, phone.core))
        eventually { phone.core.canWriteTo(ben.deviceId) && ben.core.canWriteTo(phone.deviceId) }
        // Ben's chain is known: the profile with the picture goes to his mailbox recipient, its grant under that purpose.
        eventually { phone.core.membersOf(MemberState.CONTACTS).any { it.person == ben.info.personId } }
        phone.core.setOwnImage(ByteArray(100) { 1 })
        eventually { ben.core.membersOf(MemberState.CONTACTS).any { it.deviceId == phone.deviceId && it.image != null } }

        assertTrue(phone.core.renewPersonKey(emptyList()))
        val newInbox = phone.core.identity.value!!.inbox
        // The grant for the old mailbox is not handed out again: Ben gets one for the new mailbox and can write there.
        eventually { ben.core.canWriteTo(phone.info.personId) && ben.core.membersOf(MemberState.CONTACTS).any { it.person == phone.info.personId && it.inbox == newInbox } }
        val out = assertNotNull(ben.core.sendDirect(phone.info.personId, "Neuer Schlüssel?"))
        phone.await<CoreEvent.Message> { it.mid == out.mid }
    }

    /**
     * Two devices stolen at once (ADR 0042 §6): neither gets the new circle key; the store secret is
     * new on every remaining device, files and passwords move over and the old spaces are emptied, so the thieves read
     * nothing there any more.
     */
    @Test
    fun afterTheftEveryStolenDeviceIsOutAndTheStoreMoves() = world {
        val phone = device("Anna")
        val ben = device("Ben")
        val circle = phone.core.createCircle("Familie")
        assertIs<JoinResult.Joined>(ben.core.joinCircle(phone.core.inviteLink(circle.id)!!))
        eventually { phone.core.membersOf(circle.id).any { it.deviceId == ben.deviceId } }
        val tablet = addTablet(phone, "Tablet")
        val laptop = addTablet(phone, "Laptop")
        val watch = addTablet(phone, "Watch")
        for (d in listOf(tablet, laptop, watch)) eventually { ben.core.membersOf(circle.id).any { it.deviceId == d.deviceId } }
        phone.core.storePut("f1", ByteArray(500) { 1 }, "Pass.pdf", "pdf", added = 1)
        phone.core.vaultPut(ByteArray(40) { 2 }, changed = 1000, key = ByteArray(32) { 7 })
        val oldStore = assertNotNull(phone.secrets.get(StoreKeys.SECRET))
        eventually { tablet.core.storeList()?.any { it.file == "f1" } == true }

        assertTrue(phone.core.renewPersonKey(listOf(tablet.deviceId, laptop.deviceId)))
        val newStore = assertNotNull(phone.secrets.get(StoreKeys.SECRET))
        assertFalse(newStore.contentEquals(oldStore))
        // The device that stays gets the new store secret; the stolen ones keep the old one.
        eventually { watch.secrets.get(StoreKeys.SECRET)?.contentEquals(newStore) == true }
        assertTrue(tablet.secrets.get(StoreKeys.SECRET)!!.contentEquals(oldStore))

        // File and passwords are in the new spaces, the old ones are empty.
        eventually { phone.core.storeList()?.any { it.file == "f1" } == true }
        eventually { watch.core.storeList()?.any { it.file == "f1" } == true }
        val vault = assertIs<VaultResult.Found>(phone.core.vaultGet())
        assertEquals(1000, vault.changed)
        eventually { tablet.core.storeList()?.isEmpty() == true }
        eventually { tablet.core.vaultGet() is VaultResult.Empty }

        // The circle changed its key; neither stolen device has it.
        eventually { (ben.core.circle(circle.id)?.epoch ?: 0) > 0 }
        val secret = ben.core.circle(circle.id)!!.secret
        eventually { watch.core.circle(circle.id)?.secret == secret }
        assertTrue(tablet.core.circle(circle.id)?.secret != secret)
        assertTrue(laptop.core.circle(circle.id)?.secret != secret)
        eventually { ben.core.membersOf(circle.id).none { it.deviceId == tablet.deviceId || it.deviceId == laptop.deviceId } }
        // The phone itself sent the new key to neither (an honest stolen app would ignore it, a modified one not).
        assertTrue(phone.core.membersOf(circle.id).none { it.deviceId == tablet.deviceId || it.deviceId == laptop.deviceId })
    }

    /**
     * Root key still on the phone, card made but not yet confirmed: the new person key must not land in a state space the
     * thief can read. The state secret is new and the waiting card points to it.
     */
    @Test
    fun renewingWithTheRootKeyAlsoRenewsTheStateSecret() = world {
        val phone = device("Anna")
        val code = assertNotNull(phone.core.createEmergencyCard())
        val tablet = addTablet(phone, "Tablet")
        val oldState = assertNotNull(phone.secrets.get("core.state.secret"))
        assertTrue(phone.core.renewPersonKey(listOf(tablet.deviceId)))
        val newState = assertNotNull(phone.secrets.get("core.state.secret"))
        assertFalse(newState.contentEquals(oldState))
        kotlinx.coroutines.delay(1500)

        // The card still brings the person back, with the new person key.
        val fresh = device("Neu")
        var result: RecoverResult = RecoverResult.Later
        eventually { result = fresh.core.recoverWithCode(code); result != RecoverResult.Later }
        assertIs<RecoverResult.Recovered>(result)
        assertTrue(fresh.secrets.get(CoreIdentity.PERSON)!!.contentEquals(phone.secrets.get(CoreIdentity.PERSON)!!))
    }

    /**
     * ADR 0093: a backup brings everything except the device keys. The restored phone makes its own, adds itself to the
     * list as a new device; the old phone sees an unknown device („Warst du das?“), circle and contacts learn it, and
     * the old phone can be locked out without locking out the new one.
     */
    @Test
    fun restoredBackupIsANewDevice() = world {
        val phone = device("Anna")
        val ben = device("Ben")
        assertIs<AddContactResult.Added>(agreeContact(ben.core, phone.core))
        eventually { phone.core.canWriteTo(ben.deviceId) && ben.core.canWriteTo(phone.deviceId) }
        val circle = phone.core.createCircle("Familie")
        assertIs<JoinResult.Joined>(ben.core.joinCircle(phone.core.inviteLink(circle.id)!!))
        eventually { phone.core.membersOf(circle.id).any { it.deviceId == ben.deviceId } }
        kotlinx.coroutines.delay(500)

        // The backup: all secrets of the phone except its device keys.
        val restored = MemorySecretStore()
        phone.secrets.keys().filter { it != CoreIdentity.SIGN && it != CoreIdentity.X }.forEach { restored.put(it, phone.secrets.get(it)) }
        val fresh = device("Anna", secrets = restored)
        assertTrue(fresh.deviceId != phone.deviceId)
        assertEquals(phone.info.personId, fresh.info.personId)
        eventually { fresh.core.ownDevices.value.map { it.deviceId }.toSet() == setOf(phone.deviceId, fresh.deviceId) }

        // The old phone learns a device it did not add: the app asks „Warst du das?“.
        phone.await<CoreEvent.DevicesChanged>(kotlin.time.Duration.parse("30s")) { it.own && it.added.any { d -> d.deviceId == fresh.deviceId } }
        // Contacts and the circle learn the new device.
        eventually { ben.core.membersOf(MemberState.CONTACTS).any { it.deviceId == fresh.deviceId } }
        eventually { ben.core.membersOf(circle.id).any { it.deviceId == fresh.deviceId } }
        val out = assertNotNull(ben.core.sendCircle(circle.id, "Hallo neues Handy"))
        fresh.await<CoreEvent.Message> { it.mid == out.mid }

        // Stolen old phone: the restored one locks it out (root key still in the backup here) and stays in.
        assertTrue(fresh.core.renewPersonKey(listOf(phone.deviceId)))
        phone.await<CoreEvent.Revoked>()
        assertFalse(fresh.core.revoked.value)
    }

    @Test
    fun recoveryCodeRoundTrip() {
        val code = RecoveryCode.newCode()
        val text = RecoveryCode.format(code)
        assertEquals(27 + 6, text.length)
        assertTrue(RecoveryCode.parse(text)!!.contentEquals(code))
        assertTrue(RecoveryCode.parse(text.lowercase().replace("-", " "))!!.contentEquals(code))
        val typo = text.replaceRange(0, 1, if (text[0] == '1') "2" else "1")
        assertEquals(null, RecoveryCode.parse(typo))
    }

    @Test
    fun emergencyCardBringsThePersonBackAndLocksTheLostPhone() = world {
        val phone = device("Anna")
        val ben = device("Ben")
        assertIs<AddContactResult.Added>(agreeContact(ben.core, phone.core))
        eventually { phone.core.canWriteTo(ben.deviceId) && ben.core.canWriteTo(phone.deviceId) }
        val circle = phone.core.createCircle("Familie")
        assertIs<JoinResult.Joined>(ben.core.joinCircle(phone.core.inviteLink(circle.id)!!))
        eventually { ben.core.membersOf(circle.id).any { it.deviceId == phone.deviceId } }
        phone.core.setRecoveryExtras(mapOf("pv" to ByteArray(32) { 9 }))

        assertEquals(null, phone.core.pendingEmergencyCard())
        val code = assertNotNull(phone.core.createEmergencyCard())
        // Until it is confirmed, the same code can be shown again (dialog closed in between).
        assertEquals(code, phone.core.pendingEmergencyCard())
        assertTrue(!phone.core.confirmEmergencyCard("0000-0000-0000-0000-0000-0000-000"))
        assertEquals(code, phone.core.pendingEmergencyCard())
        assertTrue(phone.core.confirmEmergencyCard(code))
        assertEquals(null, phone.core.pendingEmergencyCard())
        assertTrue(phone.core.hasEmergencyCard)
        assertEquals(null, phone.secrets.get(CoreIdentity.ROOT))
        // Extras without the password key (e.g. after a restart) keep the key that is already there.
        phone.core.setRecoveryExtras(mapOf("name" to "Anna".encodeToByteArray()))
        // A change after the card still reaches the state space.
        val later = phone.core.createCircle("Nachbarn")
        kotlinx.coroutines.delay(1500)

        // Phone lost: a new phone with the code.
        val fresh = device("Neu")
        assertEquals(RecoverResult.Invalid, fresh.core.recoverWithCode("falsch"))
        // Fresh device: until a relay answered, the answer is Later.
        var result: RecoverResult = RecoverResult.Later
        eventually { result = fresh.core.recoverWithCode(code); result != RecoverResult.Later }
        val r = assertIs<RecoverResult.Recovered>(result)
        assertEquals(listOf(phone.deviceId), r.devices.map { it.deviceId })
        assertEquals(9, r.extras["pv"]?.first()?.toInt())
        assertEquals("Anna", r.extras["name"]?.decodeToString())
        fresh.core.stop()
        devices -= fresh
        val again = device("Anna", secrets = fresh.secrets)
        assertEquals(phone.info.personId, again.info.personId)
        again.await<CoreEvent.CircleUpdated> { it.circle.id == circle.id }
        again.await<CoreEvent.CircleUpdated> { it.circle.id == later.id }
        eventually { ben.core.membersOf(MemberState.CONTACTS).any { it.deviceId == again.deviceId } }

        // Lock the lost phone for good: new person key with the code.
        assertTrue(again.core.renewPersonKey(listOf(phone.deviceId), code))
        phone.await<CoreEvent.Revoked>()
        eventually { ben.core.membersOf(MemberState.CONTACTS).none { it.deviceId == phone.deviceId } }
        // Ben writes only once the grant for the new mailbox is there (it comes with the profile of the new phone).
        eventually { ben.core.canWriteTo(again.info.personId) }
        val out = assertNotNull(ben.core.sendDirect(again.info.personId, "Wieder da?"))
        again.await<CoreEvent.Message> { it.mid == out.mid }
    }

    @Test
    fun twoDevicesRenamingAtOnceEndWithTheSameList() = world {
        val phone = device("Anna")
        val tablet = addTablet(phone)
        eventually { tablet.core.ownDevices.value.size == 2 }
        coroutineScopeBoth(phone, tablet)
        eventually(kotlin.time.Duration.parse("20s")) {
            val a = phone.core.ownDevices.value
            val b = tablet.core.ownDevices.value
            a == b && a.map { it.name }.toSet() == setOf("Handy", "Tablet")
        }
    }

    private suspend fun coroutineScopeBoth(phone: Device, tablet: Device) = kotlinx.coroutines.coroutineScope {
        launch { phone.core.setOwnDeviceName("Handy") }
        launch { tablet.core.setOwnDeviceName("Tablet") }
    }

    @Test
    fun ownDataReachesTheOtherDevice() = world {
        val phone = device("Anna")
        val tablet = addTablet(phone)
        eventually { tablet.core.ownDevices.value.size == 2 }
        val big = ByteArray(2_500_000) { (it % 251).toByte() }
        assertTrue(phone.core.sendOwnData("history", big, tablet.deviceId))
        val got = tablet.await<CoreEvent.OwnData>(kotlin.time.Duration.parse("30s")) { it.topic == "history" }
        assertTrue(got.data.contentEquals(big))
        assertEquals(phone.deviceId, got.fromDevice)
    }

    @Test
    fun passwordVaultIsSharedAndNewestWins() = world {
        val phone = device("Anna")
        val tablet = addTablet(phone)
        // Fresh device: until a relay answered, the answer is Later, not Empty.
        eventually { tablet.core.vaultGet() == VaultResult.Empty }
        phone.core.vaultPut(byteArrayOf(1, 2, 3), 1000, key = ByteArray(32) { 7 })
        eventually { (tablet.core.vaultGet() as? VaultResult.Found)?.data?.contentEquals(byteArrayOf(1, 2, 3)) == true }
        assertEquals(7, (tablet.core.vaultGet() as VaultResult.Found).key?.first()?.toInt())
        // Written without the key: the key of the newest entry stays.
        tablet.core.vaultPut(byteArrayOf(4), 2000)
        eventually { (phone.core.vaultGet() as? VaultResult.Found)?.changed == 2000L }
        val found = phone.core.vaultGet() as VaultResult.Found
        assertTrue(found.data.contentEquals(byteArrayOf(4)))
        assertEquals(7, found.key?.first()?.toInt())
    }

    @Test
    fun profileReachesTheOtherDeviceAndChangesAreMerged() = world {
        val phone = device("Anna")
        val tablet = addTablet(phone)
        eventually { tablet.core.ownDevices.value.size == 2 }
        // Renaming on the phone reaches the tablet.
        phone.core.setProfile("Anna B.", renamed = true)
        val renamed = tablet.await<CoreEvent.ProfileChanged>(kotlin.time.Duration.parse("30s")) { it.name != null }
        assertEquals("Anna B.", renamed.name)
        // Picture on the phone, a setting on the tablet: both stay, on both devices.
        val picture = ByteArray(200_000) { (it % 13).toByte() }
        phone.core.setOwnImage(picture)
        tablet.core.setSettings(mapOf("dark_mode" to "SYSTEM".encodeToByteArray()))
        tablet.core.setSettings(mapOf("dark_mode" to "DARK".encodeToByteArray()))
        val pic = tablet.await<CoreEvent.ProfileChanged>(kotlin.time.Duration.parse("30s")) { it.imageChanged }
        assertTrue(pic.image!!.contentEquals(picture))
        eventually { phone.core.settings()["dark_mode"]?.decodeToString() == "DARK" }
        eventually { !phone.core.profileSync() && !tablet.core.profileSync() }
        assertEquals("DARK", tablet.core.settings()["dark_mode"]?.decodeToString())
        // Back to the generated picture also reaches the tablet.
        phone.core.setOwnImage(null)
        val generated = tablet.await<CoreEvent.ProfileChanged>(kotlin.time.Duration.parse("30s")) { it.imageChanged && it.image == null }
        assertEquals(null, generated.image)
    }

    @Test
    fun aProfileChangeWithoutRelaysGoesOutOnceTheyAnswer() = world {
        val phone = device("Anna")
        val tablet = addTablet(phone)
        eventually { tablet.core.ownDevices.value.size == 2 }
        // Renamed while no relay answers (offline, or right after the start without a network list): it used to stay
        // on the phone until the next change.
        net.relays.forEach { it.offline = true }
        phone.core.setProfile("Anna B.", renamed = true)
        delay(1.seconds)
        net.relays.forEach { it.offline = false }
        tablet.await<CoreEvent.ProfileChanged>(30.seconds) { it.name == "Anna B." }
    }

    @Test
    fun theOldProfileStaysUntilTheNewOneIsStored() = world {
        val phone = device("Anna")
        val tablet = addTablet(phone)
        eventually { tablet.core.ownDevices.value.size == 2 }
        phone.core.setProfile("Anna B.", renamed = true)
        tablet.await<CoreEvent.ProfileChanged>(30.seconds) { it.name != null }
        eventually { !phone.core.profileSync() && !tablet.core.profileSync() }
        // The picture upload hangs at every relay; the entry waits for it.
        val picture = ByteArray(200_000) { (it % 13).toByte() }
        net.relays.forEach { r -> r.holdPut = { _, size -> size > 100_000 } }
        phone.core.setOwnImage(picture)
        delay(1.seconds)
        // Meanwhile the old entry is still there: the tablet finds one entry with nothing new and writes nothing (it
        // used to find the space emptied and write its own, older profile), and is not told before the write is done.
        assertFalse(tablet.core.profileSync())
        assertTrue(tablet.none<CoreEvent.ProfileChanged>(1.seconds) { it.imageChanged })
        net.relays.forEach { it.releasePuts() }
        val pic = tablet.await<CoreEvent.ProfileChanged>(30.seconds) { it.imageChanged }
        assertTrue(pic.image!!.contentEquals(picture))
    }

    @Test
    fun pictureFromTheProfileSpaceReachesContacts() = world {
        val phone = device("Anna")
        val tablet = addTablet(phone)
        eventually { tablet.core.ownDevices.value.size == 2 }
        val ben = device("Ben")
        assertIs<AddContactResult.Added>(agreeContact(ben.core, tablet.core))
        eventually { ben.core.membersOf(MemberState.CONTACTS).any { it.deviceId == tablet.deviceId } }
        // Set on the phone: the tablet learns it from the profile space and sends it on to its contacts.
        val picture = ByteArray(5000) { 9 }
        phone.core.setOwnImage(picture)
        tablet.await<CoreEvent.ProfileChanged>(kotlin.time.Duration.parse("30s")) { it.imageChanged }
        var ref: ImageRef? = null
        eventually { ref = ben.core.membersOf(MemberState.CONTACTS).firstOrNull { it.deviceId == tablet.deviceId }?.image; ref != null }
        assertTrue(assertIs<BlobResult.Found>(ben.core.fetchImage(ref!!)).plaintext.contentEquals(picture))
    }

    @Test
    fun emergencyCardBringsDirectMessagesBack() = world {
        // ADR 0107: direct messages go to the person's mailbox recipient, so a phone back from the card reads them.
        val phone = device("Anna")
        val ben = device("Ben")
        assertIs<AddContactResult.Added>(agreeContact(ben.core, phone.core))
        eventually { phone.core.canWriteTo(ben.deviceId) && ben.core.canWriteTo(phone.deviceId) }
        eventually { ben.core.membersOf(MemberState.CONTACTS).any { it.person == phone.info.personId } }
        eventually { phone.core.membersOf(MemberState.CONTACTS).any { it.person == ben.info.personId } }
        val fromBen = assertNotNull(ben.core.sendDirect(phone.info.personId, "Hallo Anna"))
        phone.await<CoreEvent.Message> { it.mid == fromBen.mid }
        val fromAnna = assertNotNull(phone.core.sendDirect(ben.info.personId, "Hallo Ben"))
        ben.await<CoreEvent.Message> { it.mid == fromAnna.mid }
        val code = assertNotNull(phone.core.createEmergencyCard())
        assertTrue(phone.core.confirmEmergencyCard(code))
        // The phone is gone only after its state reached the relays (slower on iOS).
        eventually { phone.core.pendingWrites() == 0 }
        phone.core.stop()
        devices -= phone

        val fresh = device("Neu")
        var result: RecoverResult = RecoverResult.Later
        eventually { result = fresh.core.recoverWithCode(code); result != RecoverResult.Later }
        assertIs<RecoverResult.Recovered>(result)
        fresh.core.stop()
        devices -= fresh
        val again = device("Anna", secrets = fresh.secrets)
        // Both directions of the conversation come back: Ben's message and Anna's own copy.
        val got = again.await<CoreEvent.Message>(kotlin.time.Duration.parse("60s")) { it.mid == fromBen.mid }
        assertEquals(Conversation.Direct(ben.info.personId), got.conversation)
        val own = again.await<CoreEvent.Message>(kotlin.time.Duration.parse("60s")) { it.mid == fromAnna.mid }
        assertEquals(Conversation.Direct(ben.info.personId), own.conversation)
    }

    @Test
    fun updateMovesOldDirectMessagesToThePerson() = world {
        // Before ADR 0107: direct messages sealed per device. After the update the phone moves them (§6), so a phone
        // back from the emergency card reads them too.
        val legacy = net.config(legacyDirect = true)
        val phone = device("Anna", config = legacy)
        val ben = device("Ben", config = legacy)
        assertIs<AddContactResult.Added>(agreeContact(ben.core, phone.core))
        eventually { phone.core.canWriteTo(ben.deviceId) && ben.core.canWriteTo(phone.deviceId) }
        eventually { ben.core.membersOf(MemberState.CONTACTS).any { it.person == phone.info.personId } }
        eventually { phone.core.membersOf(MemberState.CONTACTS).any { it.person == ben.info.personId } }
        val fromBen = assertNotNull(ben.core.sendDirect(phone.info.personId, "Alt von Ben"))
        phone.await<CoreEvent.Message> { it.mid == fromBen.mid }
        val fromAnna = assertNotNull(phone.core.sendDirect(ben.info.personId, "Alt von Anna"))
        ben.await<CoreEvent.Message> { it.mid == fromAnna.mid }
        val code = assertNotNull(phone.core.createEmergencyCard())
        assertTrue(phone.core.confirmEmergencyCard(code))
        eventually { phone.core.pendingWrites() == 0 }

        // The update: same installation, new version.
        phone.core.stop()
        devices -= phone
        val updated = device("Anna", secrets = phone.secrets, store = phone.store, blobs = phone.blobs)
        // An installation that ran before shows no loading notice.
        assertFalse(updated.core.loading.value.any)
        eventually { updated.core.migrateMailbox() }
        // Anna's own message lay only in Ben's mailbox: the app hands its history over once.
        val mine = OwnHistoryItem(ben.info.personId, fromAnna.mid, 1_700_000_000_000, "Alt von Anna", author = null)
        eventually { updated.core.keepOwnHistory(listOf(mine)) }
        // Moved and history kept: the phone notes it in its entry of the device list.
        eventually { updated.core.ownDevices.value.single { it.deviceId == updated.deviceId }.moved }
        eventually { updated.core.pendingWrites() == 0 }
        updated.core.stop()
        devices -= updated

        val fresh = device("Neu")
        var result: RecoverResult = RecoverResult.Later
        eventually { result = fresh.core.recoverWithCode(code); result != RecoverResult.Later }
        assertIs<RecoverResult.Recovered>(result)
        fresh.core.stop()
        devices -= fresh
        val again = device("Anna", secrets = fresh.secrets)
        val got = again.await<CoreEvent.Message>(kotlin.time.Duration.parse("60s")) { it.mid == fromBen.mid }
        assertEquals(Conversation.Direct(ben.info.personId), got.conversation)
        assertEquals(ben.deviceId, got.fromDevice)
        assertEquals(ben.info.personId, got.fromPerson)
        val own = again.await<CoreEvent.Message>(kotlin.time.Duration.parse("60s")) { it.mid == fromAnna.mid }
        assertEquals(Conversation.Direct(ben.info.personId), own.conversation)
        assertEquals(phone.info.personId, own.fromPerson)
        // Back from the card it showed the loading notice until the own mailbox was read once (fast here); now it is gone.
        eventually { !again.core.loading.value.mailbox }
        // The one back from the card has nothing to move and says so in its entry.
        assertTrue(again.core.ownDevices.value.single { it.deviceId == again.deviceId }.moved)
        // Written once, though the phone ran the move twice (at start and here).
        kotlinx.coroutines.delay(2_000)
        assertEquals(1, again.all<CoreEvent.Message>().count { it.mid == fromBen.mid })
    }

    @Test
    fun replacingPhoneTakesOverTheDeviceSettings() = world {
        val phone = device("Anna")
        phone.core.setDeviceSettings(mapOf("radio" to "usb:1".encodeToByteArray(), "region" to "06".encodeToByteArray()))
        eventually { !phone.core.writeDeviceSettings() }
        val code = assertNotNull(phone.core.createEmergencyCard())
        assertTrue(phone.core.confirmEmergencyCard(code))

        val fresh = device("Neu")
        var result: RecoverResult = RecoverResult.Later
        eventually { result = fresh.core.recoverWithCode(code); result != RecoverResult.Later }
        assertIs<RecoverResult.Recovered>(result)
        fresh.core.stop()
        devices -= fresh
        val again = device("Anna", secrets = fresh.secrets)
        again.core.setDeviceSettings(mapOf("hint" to "later".encodeToByteArray()))
        // The new phone sees the old one with its settings, not itself.
        var stored: List<StoredDevice>? = null
        eventually { stored = again.core.storedDevices(); stored?.size == 1 }
        assertEquals(phone.deviceId, stored!!.single().deviceId)
        assertEquals(listOf("radio", "region"), stored!!.single().names)
        // Taking over: the old settings are this phone's now, its own stay, the old entry is gone.
        val taken = assertNotNull(again.core.takeOverDevice(phone.deviceId))
        assertEquals("usb:1", taken["radio"]?.decodeToString())
        val mine = again.core.deviceSettings()
        assertEquals("06", mine["region"]?.decodeToString())
        assertEquals("later", mine["hint"]?.decodeToString())
        eventually { again.core.storedDevices()?.isEmpty() == true }
        // Nothing left to take over from the old phone.
        assertEquals(null, again.core.takeOverDevice(phone.deviceId))
    }

    @Test
    fun emergencyCardBringsProfileAndSettingsBack() = world {
        val phone = device("Anna")
        phone.core.setProfile("Anna B.", renamed = true)
        val picture = ByteArray(80_000) { (it % 7).toByte() }
        phone.core.setOwnImage(picture)
        phone.core.setSettings(mapOf("radio_signature" to "true".encodeToByteArray()))
        phone.core.setSettings(mapOf("radio_signature" to "false".encodeToByteArray()))
        eventually { !phone.core.profileSync() }
        val code = assertNotNull(phone.core.createEmergencyCard())
        assertTrue(phone.core.confirmEmergencyCard(code))

        val fresh = device("Neu")
        var result: RecoverResult = RecoverResult.Later
        eventually { result = fresh.core.recoverWithCode(code); result != RecoverResult.Later }
        assertIs<RecoverResult.Recovered>(result)
        fresh.core.stop()
        devices -= fresh
        // The app starts with an old name and default settings (as from extras written with the card): the newer
        // ones from the profile space win.
        val again = device("Anna", secrets = fresh.secrets)
        again.core.setSettings(mapOf("radio_signature" to "true".encodeToByteArray()))
        eventually { again.core.profileSync(); again.core.settings()["radio_signature"]?.decodeToString() == "false" }
        // Events reach the test's collector asynchronously (on JS only after this coroutine yields).
        again.await<CoreEvent.ProfileChanged> { it.name == "Anna B." }
        again.await<CoreEvent.ProfileChanged> { it.imageChanged && it.image!!.contentEquals(picture) }
        assertEquals("false", again.core.settings()["radio_signature"]?.decodeToString())
    }

    @Test
    fun eraseEverythingRemovesThePersonFromTheNetwork() = world {
        val phone = device("Anna")
        val ben = device("Ben")
        assertIs<AddContactResult.Added>(agreeContact(ben.core, phone.core))
        eventually { phone.core.canWriteTo(ben.deviceId) && ben.core.canWriteTo(phone.deviceId) }
        val circle = phone.core.createCircle("Familie")
        assertIs<JoinResult.Joined>(ben.core.joinCircle(phone.core.inviteLink(circle.id)!!))
        eventually { phone.core.membersOf(circle.id).any { it.deviceId == ben.deviceId } }
        val tablet = addTablet(phone)
        eventually { tablet.core.ownDevices.value.size == 2 }
        val stored = phone.core.storePut("f1", ByteArray(500) { 1 }, "Vollmacht.pdf", "pdf", added = 1)
        phone.await<CoreEvent.Stored> { it.ref == stored }
        eventually { phone.core.vaultGet() == VaultResult.Empty }
        phone.core.vaultPut(byteArrayOf(1, 2, 3), 1000)
        eventually { phone.core.vaultGet() is VaultResult.Found }
        val code = assertNotNull(phone.core.createEmergencyCard())
        assertTrue(phone.core.confirmEmergencyCard(code))

        assertEquals(EraseResult.Done, phone.core.eraseEverything())

        // Circle and contact are gone here; Ben sees Anna leave; the tablet sees itself removed.
        assertTrue(phone.core.circles.value.isEmpty())
        assertTrue(phone.core.members.value.isEmpty())
        ben.await<CoreEvent.MemberLeft> { it.circleId == circle.id && it.deviceId == phone.deviceId }
        tablet.await<CoreEvent.Revoked>()
        // Store, passwords and the state space are empty: the emergency card leads to nothing any more.
        assertEquals(emptyList(), phone.core.storeList())
        assertEquals(VaultResult.Empty, phone.core.vaultGet())
        val fresh = device("Neu")
        var result: RecoverResult = RecoverResult.Later
        eventually { result = fresh.core.recoverWithCode(code); result != RecoverResult.Later }
        assertEquals(RecoverResult.NotFound, result)
    }

    @Test
    fun contactsSeeAnErasedPersonAsUnreachable() = world {
        val phone = device("Anna")
        val ben = device("Ben")
        assertIs<AddContactResult.Added>(agreeContact(ben.core, phone.core))
        eventually { phone.core.canWriteTo(ben.deviceId) && ben.core.canWriteTo(phone.deviceId) }
        val hello = assertNotNull(ben.core.sendDirect(phone.deviceId, "Hallo"))
        phone.await<CoreEvent.Message> { it.mid == hello.mid }

        assertEquals(EraseResult.Done, phone.core.eraseEverything())

        // Anna's grants are revoked: every relay refuses Ben's next message, Ben sees Anna as unreachable.
        val lost = assertNotNull(ben.core.sendDirect(phone.deviceId, "Bist du noch da?"))
        ben.await<CoreEvent.SendFailed> { it.ref == lost.mid }
        val gone = ben.await<CoreEvent.ContactUnreachable>()
        eventually { gone.personId in ben.core.unreachable.value }
        // Removing the contact clears the mark.
        ben.core.removeContact(gone.personId)
        ben.await<CoreEvent.ContactReachable> { it.personId == gone.personId }
        eventually { gone.personId !in ben.core.unreachable.value }
    }

    @Test
    fun undeliveredWritesCountsOnlyWhatNoRelayHas() = world {
        val phone = device("Anna")
        val circle = phone.core.createCircle("Familie")
        eventually { phone.core.circles.value.isNotEmpty() }
        net.relays.forEach { it.offline = true }
        assertNotNull(phone.core.sendCircle(circle.id, "später"))
        // Other writes of the start may be open too: at least this one counts while no relay answers.
        eventually { phone.core.undeliveredWrites() >= 1 }
        net.relays.forEach { it.offline = false }
        phone.core.networkChanged()
        eventually { phone.core.undeliveredWrites() == 0 }
    }

    @Test
    fun eraseEverythingWithoutRelaysChangesNothing() = world {
        val phone = device("Anna")
        val circle = phone.core.createCircle("Familie")
        eventually { phone.core.circles.value.isNotEmpty() }
        net.relays.forEach { it.offline = true }
        assertEquals(EraseResult.Offline, phone.core.eraseEverything())
        assertEquals(listOf(circle.id), phone.core.circles.value.map { it.id })
    }

    @Test
    fun ownOtherDeviceGivesTheCircleHistoryToAnAddedDevice() = world {
        val phone = device("Anna")
        val ben = device("Ben")
        val circle = phone.core.createCircle("Familie")
        assertIs<JoinResult.Joined>(ben.core.joinCircle(phone.core.inviteLink(circle.id)!!))
        eventually { phone.core.membersOf(circle.id).any { it.deviceId == ben.deviceId } }
        keep(phone, circle.id, phone.core.sendCircle(circle.id, "eins"))
        keep(phone, circle.id, phone.core.sendCircle(circle.id, "zwei"))
        ben.await<CoreEvent.Message> { it.text == "zwei" }
        // Ben is not there: only the own phone can give the history.
        ben.core.stop()
        devices -= ben

        val tablet = addTablet(phone)
        tablet.await<CoreEvent.CircleUpdated> { it.circle.id == circle.id }
        val loaded = tablet.await<CoreEvent.HistoryLoaded>(kotlin.time.Duration.parse("30s")) { it.circleId == circle.id }
        assertEquals(2, loaded.count)
        val texts = tablet.all<CoreEvent.Message>().filter { it.history }.map { it.text }
        assertTrue("eins" in texts && "zwei" in texts, "history: $texts")
        assertTrue(tablet.all<CoreEvent.Message>().filter { it.history }.all { it.fromPerson == phone.info.personId })
        assertEquals(false, tablet.core.circle(circle.id)!!.historyPending)
    }

    @Test
    fun deviceLinkedBeforeTheContactMayWriteToIt() = world {
        // The tablet exists before Anna scans Ben: Ben must take it as a device of Anna and grant it his mailbox too.
        val phone = device("Anna")
        val tablet = addTablet(phone)
        eventually { tablet.core.ownDevices.value.size == 2 }
        val ben = device("Ben")
        assertIs<AddContactResult.Added>(agreeContact(phone.core, ben.core))
        eventually { phone.core.canWriteTo(ben.info.personId) }
        eventually { tablet.core.canWriteTo(ben.info.personId) }
    }

    @Test
    fun copiesReachAnOwnDeviceWithoutACommonCircle() = world {
        val phone = device("Anna")
        val ben = device("Ben")
        assertIs<AddContactResult.Added>(agreeContact(ben.core, phone.core))
        eventually { phone.core.canWriteTo(ben.deviceId) }
        val tablet = addTablet(phone)
        eventually { tablet.core.ownDevices.value.size == 2 }
        val out = assertNotNull(phone.core.sendDirect(ben.info.personId, "Ohne Kreis"))
        val copy = tablet.await<CoreEvent.Message> { it.mid == out.mid }
        assertEquals(phone.info.personId, copy.fromPerson)
    }
}
