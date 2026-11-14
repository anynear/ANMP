package app.anynear.anmp.core

import app.anynear.andp.B64
import app.anynear.andp.Credentials
import app.anynear.andp.Grant
import app.anynear.andp.GrantBody
import app.anynear.andp.Key
import app.anynear.andp.Meta
import app.anynear.andp.AndpHttpException
import app.anynear.andp.Placement
import app.anynear.andp.PutOptions
import app.anynear.andp.Rights
import app.anynear.andp.SigningKey
import app.anynear.anmp.AnmpAlertBody
import app.anynear.anmp.AnmpAlerts
import app.anynear.anmp.AnmpChannelInvite
import app.anynear.anmp.AnmpIngressRecord
import app.anynear.anmp.AnmpKeys
import app.anynear.anmp.AnmpSpaceKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Channels and alerts (ADR 0062): owner, subscribers, device addresses as the relay uses them, foreign writers. */
class ChannelFlowTest {
    private fun nowS() = Clock.System.now().epochSeconds

    /** Reads the record of a device address like the relay does: space and key from the token, newest owner entry (same `ts`: listed later). */
    private suspend fun World.ingressRecord(i: Ingress): AnmpIngressRecord {
        val token = assertNotNull(AnmpAlerts.tokenBytes(i.url.substringAfterLast('/')))
        val space = Key(AnmpAlerts.ingressSpace(token).publicKey)
        var record: AnmpIngressRecord? = null
        eventually {
            record = net.holders(space.toString()).flatMap { it.entries(space) }.filter { it.meta.writer == space }
                .maxWithOrNull(compareBy({ it.meta.ts }, { it.seq }))?.let { AnmpAlerts.openIngress(token, it.data) }
            record != null
        }
        return record!!
    }

    /** Writes [packet] into the channel space as [writer] with [grant], at every holder; `true` if one took it. */
    private suspend fun World.put(space: Key, writer: SigningKey, grant: Grant?, packet: ByteArray, alarm: Boolean = false): Boolean {
        val ts = nowS()
        val meta = Meta.signData(writer, space, packet, ts, until = ts + 86_400, placement = Placement.SPACE, grant = grant)
        var ok = false
        for (r in net.holders(space.toString())) {
            try {
                net.connector.connect(r.relay)!!.put(Credentials(writer, grant), meta, packet, PutOptions(alarm = alarm))
                ok = true
            } catch (e: AndpHttpException) {
                // refused
            }
        }
        return ok
    }

    private fun body(text: String, prio: Int = AnmpAlerts.WARNING, title: String? = null) =
        AnmpAlertBody(AnmpKeys.randomKey().copyOf(AnmpAlerts.ID_BYTES), nowS(), prio, title, text)

    @Test
    fun ownerSubscriberDeviceAddressAndForeignWriters() = world {
        val a = device("Anna")
        val b = device("Ben")
        val c = a.core.createChannel("Haus")
        assertTrue(c.owner)
        val link = assertNotNull(a.core.channelLink(c.id))
        assertTrue(link.startsWith("https://anynear.app/a#"))
        assertEquals("Haus", b.core.parseChannelLink(link)?.name)
        assertIs<ChannelJoinResult.Joined>(b.core.joinChannel("Abonnier das: $link"))
        assertIs<ChannelJoinResult.AlreadyJoined>(b.core.joinChannel(link))
        assertIs<ChannelJoinResult.AlreadyJoined>(a.core.joinChannel(link))
        val sub = assertNotNull(b.core.channel(c.id))
        assertFalse(sub.owner)
        assertFalse(sub.curated)
        assertNull(b.core.channelLink(c.id))
        assertNull(b.core.sendAlert(c.id, AnmpAlerts.INFO, null, "darf ich nicht"))

        // The owner sends an alarm with a picture and a context URL; it wakes with alarm priority.
        val photo = ByteArray(50_000) { (it % 251).toByte() }
        val att = a.core.prepareAttachment(photo, "jpg", "garten.jpg", 640, 480)
        val sent = assertNotNull(a.core.sendAlert(c.id, AnmpAlerts.ALARM, "Rauchmelder", "Rauch im Keller", "https://cam.example/", listOf(att)))
        assertEquals(AlertStatus.ACTIVE, sent.status)
        a.await<CoreEvent.Stored> { it.ref == sent.id }
        val got = b.await<CoreEvent.Alert> { it.item.id == sent.id }.item
        assertEquals(c.id, got.channelId)
        assertEquals("Rauch im Keller", got.text)
        assertEquals("Rauchmelder", got.title)
        assertEquals("https://cam.example/", got.url)
        assertEquals(AnmpAlerts.ALARM, got.prio)
        assertEquals(Key.parse(c.id).bytes.toHex(), got.sender)
        assertEquals(got.ts + 86_400, got.until)
        val blob = b.core.fetchAlertAttachment(got, got.attachments.single())
        assertContentEquals(photo, assertIs<BlobResult.Found>(blob).plaintext)
        assertTrue(net.relays.flatMap { it.alarmPuts }.any { it.space == Key.parse(c.id) })
        // The owner keeps its own alert, without a notification for itself.
        assertTrue(a.core.alerts.value.any { it.id == sent.id })
        assertTrue(a.none<CoreEvent.Alert>(500.milliseconds) { it.item.id == sent.id })

        // A device address: the relay reads the record with the token and sends as the address's own sender.
        val ingress = assertNotNull(a.core.createIngress(c.id, "Home Assistant"))
        assertTrue(ingress.url.startsWith("https://alerts.anynear.app/"))
        assertEquals(ingress.id, a.core.channel(c.id)!!.ingress.single().id)
        val record = ingressRecord(ingress)
        assertEquals(c.id, record.space)
        assertEquals("Home Assistant", record.label)
        val sender = SigningKey.fromSeed(record.senderSeed)
        assertEquals(ingress.id, sender.key.bytes.toHex())
        val senderGrant = Grant.parse(record.grant)
        val space = Key.parse(c.id)
        val fromDevice = body("Bewegung im Garten", AnmpAlerts.ALARM)
        assertTrue(put(space, sender, senderGrant, AnmpAlerts.encode(fromDevice, AnmpSpaceKey.fromPrivateKey(record.senderSeed), record.readPublicKey), alarm = true))
        val viaAddress = b.await<CoreEvent.Alert> { it.item.id == fromDevice.id.toHex() }.item
        assertEquals(ingress.id, viaAddress.sender)
        a.await<CoreEvent.Alert> { it.item.id == fromDevice.id.toHex() }

        // Foreign writers: without a grant the relays refuse; a sender key signing for someone else is dropped.
        val stranger = testKey("stranger")
        val strangerKey = AnmpSpaceKey.fromPrivateKey(stranger.seed)
        assertFalse(put(space, stranger, null, AnmpAlerts.encode(body("fremd"), strangerKey, record.readPublicKey)))
        val forged = body("im Namen eines anderen")
        assertTrue(put(space, sender, senderGrant, AnmpAlerts.encode(forged, strangerKey, record.readPublicKey)))
        // An owner-signed alert old enough is dropped too (rule 3).
        val old = AnmpAlertBody(AnmpKeys.randomKey().copyOf(8), nowS() - 31L * 86_400, AnmpAlerts.INFO, text = "alt")
        assertTrue(put(space, sender, senderGrant, AnmpAlerts.encode(old, AnmpSpaceKey.fromPrivateKey(record.senderSeed), record.readPublicKey)))
        // Regional keys belong only into curated channels: here the alert is dropped as a whole.
        val regional = AnmpAlertBody(AnmpKeys.randomKey().copyOf(AnmpAlerts.ID_BYTES), nowS(), AnmpAlerts.WARNING, text = "Hochwasser", area = listOf("06412"))
        assertTrue(put(space, sender, senderGrant, AnmpAlerts.encode(regional, AnmpSpaceKey.fromPrivateKey(record.senderSeed), record.readPublicKey)))
        val marker = body("danach")
        assertTrue(put(space, sender, senderGrant, AnmpAlerts.encode(marker, AnmpSpaceKey.fromPrivateKey(record.senderSeed), record.readPublicKey)))
        b.await<CoreEvent.Alert> { it.item.id == marker.id.toHex() }
        assertTrue(
            b.all<CoreEvent.Alert>().none {
                it.item.id == forged.id.toHex() || it.item.id == old.id.toHex() || it.item.id == regional.id.toHex() || it.item.text == "fremd"
            },
        )

        // The same alert again (another object, e.g. written twice) is shown once.
        assertTrue(put(space, sender, senderGrant, AnmpAlerts.encode(marker, AnmpSpaceKey.fromPrivateKey(record.senderSeed), record.readPublicKey)))
        val again = body("noch eins")
        assertTrue(put(space, sender, senderGrant, AnmpAlerts.encode(again, AnmpSpaceKey.fromPrivateKey(record.senderSeed), record.readPublicKey)))
        b.await<CoreEvent.Alert> { it.item.id == again.id.toHex() }
        assertEquals(1, b.all<CoreEvent.Alert>().count { it.item.id == marker.id.toHex() })

        // All clear: replaces the alarm and ends it.
        val clear = assertNotNull(a.core.sendAlert(c.id, AnmpAlerts.INFO, null, "Entwarnung", replaces = sent.id, ended = true))
        b.await<CoreEvent.Alert> { it.item.id == clear.id }
        eventually {
            val list = b.core.alerts.value
            list.first { it.id == sent.id }.let { it.status == AlertStatus.ENDED && it.replacedBy == clear.id } &&
                list.first { it.id == clear.id }.status == AlertStatus.ENDED
        }

        // Expiry: valid for two seconds.
        val short = assertNotNull(a.core.sendAlert(c.id, AnmpAlerts.WARNING, null, "kurz", until = nowS() + 2))
        b.await<CoreEvent.Alert> { it.item.id == short.id }
        eventually { b.core.alerts.value.firstOrNull { it.id == short.id }?.status == AlertStatus.EXPIRED }
        assertEquals(b.core.alerts.value.map { it.ts }.sortedDescending(), b.core.alerts.value.map { it.ts })

        // Revoking the device address: its record is gone and the relays refuse its grant.
        assertTrue(a.core.revokeIngress(c.id, ingress.id))
        assertTrue(a.core.channel(c.id)!!.ingress.isEmpty())
        eventually { net.holders(space.toString()).all { it.revokedIn(space) > 0 } }
        assertFalse(put(space, sender, senderGrant, AnmpAlerts.encode(body("nach dem Widerruf"), AnmpSpaceKey.fromPrivateKey(record.senderSeed), record.readPublicKey)))

        // Leaving: state and alerts of the channel are gone on this device.
        b.core.leaveChannel(c.id)
        assertNull(b.core.channel(c.id))
        eventually { b.core.alerts.value.none { it.channelId == c.id } }
    }

    @Test
    fun linksOfOtherNetworksBrokenOnesAndCuratedChannels() = world {
        val a = device("Anna")
        val c = a.core.createChannel("Warnungen")
        val state = assertNotNull(a.core.channel(c.id))
        val config = net.config()
        val curatedLink = AnmpAlerts.channelLink(
            config.linkHost,
            AnmpChannelInvite(config.networkId.bytes, c.id, "Warnungen", AnmpKeys.decodeOrNull(state.readKey)!!, state.grant, curated = true),
        )
        // The flag alone does not make a channel curated.
        val b = device("Ben")
        assertIs<ChannelJoinResult.Joined>(b.core.joinChannel(curatedLink))
        assertFalse(b.core.channel(c.id)!!.curated)
        // Listed in the configuration, it is.
        val withCurated = AnmpCoreConfig(
            config.networkId, config.networkKey, seedUrl = config.seedUrl, timings = config.timings, radioInboxSpace = "", radioInboxGrant = "",
            radioSoftwareSpace = "", radioSoftwareGrant = "", curatedChannels = listOf(curatedLink),
        )
        val d = device("Dora", config = withCurated)
        assertIs<ChannelJoinResult.Joined>(d.core.joinChannel(curatedLink))
        assertTrue(d.core.channel(c.id)!!.curated)

        // Regional keys count only where the channel is curated: Dora takes the alert, Ben drops it.
        val ingress = assertNotNull(a.core.createIngress(c.id, "Warndienst"))
        val record = ingressRecord(ingress)
        val regional = AnmpAlertBody(AnmpKeys.randomKey().copyOf(AnmpAlerts.ID_BYTES), nowS(), AnmpAlerts.WARNING, text = "Hochwasser", area = listOf("06412"))
        assertTrue(put(Key.parse(c.id), SigningKey.fromSeed(record.senderSeed), Grant.parse(record.grant),
            AnmpAlerts.encode(regional, AnmpSpaceKey.fromPrivateKey(record.senderSeed), record.readPublicKey)))
        assertEquals(listOf("06412"), d.await<CoreEvent.Alert> { it.item.id == regional.id.toHex() }.item.area)
        assertTrue(b.none<CoreEvent.Alert>(2.seconds) { it.item.id == regional.id.toHex() })

        val foreign = AnmpAlerts.channelLink(config.linkHost, AnmpChannelInvite(ByteArray(32) { 9 }, c.id, "x", AnmpKeys.decodeOrNull(state.readKey)!!, state.grant))
        assertEquals(ChannelJoinResult.ForeignNetwork, d.core.joinChannel(foreign))
        assertEquals(ChannelJoinResult.Invalid, d.core.joinChannel("https://anynear.app/a#AAAA"))
        // A grant that is no read grant of this channel's owner.
        val other = testKey("other owner")
        val wrong = Grant.issue(other, GrantBody(other.key, Rights.READ + Rights.LIST, null, null, nowS() + 3600, nowS() - 60))
        val wrongLink = AnmpAlerts.channelLink(config.linkHost, AnmpChannelInvite(config.networkId.bytes, c.id, "x", ByteArray(32), wrong.toString()))
        assertNull(d.core.parseChannelLink(wrongLink))
        assertEquals(ChannelJoinResult.Invalid, d.core.joinChannel(wrongLink))
    }

    @Test
    fun resetLinkCutsOffOldSubscribersAndKeepsDeviceAddresses() = world {
        val a = device("Anna")
        val b = device("Ben")
        val c = a.core.createChannel("Haus")
        b.core.joinChannel(a.core.channelLink(c.id)!!)
        val ingress = assertNotNull(a.core.createIngress(c.id, "Server"))
        val before = ingressRecord(ingress)

        val reset = assertNotNull(a.core.resetChannelLink(c.id))
        assertFalse(reset.readKey == c.readKey)
        assertEquals(ingress.url, reset.ingress.single().url)
        // The relay finds the newest record with the new read key.
        eventually { !ingressRecord(ingress).readPublicKey.contentEquals(before.readPublicKey) }
        val space = Key.parse(c.id)
        eventually { net.holders(space.toString()).all { it.revokedIn(space) > 0 } }

        // A new subscriber with the new link gets the next alert, the old one does not.
        val d = device("Dora")
        assertIs<ChannelJoinResult.Joined>(d.core.joinChannel(a.core.channelLink(c.id)!!))
        val sent = assertNotNull(a.core.sendAlert(c.id, AnmpAlerts.INFO, null, "nach dem Zurücksetzen"))
        d.await<CoreEvent.Alert> { it.item.id == sent.id }
        assertTrue(b.none<CoreEvent.Alert>(2.seconds) { it.item.id == sent.id })
    }

    @Test
    fun wakeFetchReportsAlertsWithPriority() = world {
        val a = device("Anna")
        val b = device("Ben")
        val c = a.core.createChannel("Haus")
        b.core.joinChannel(a.core.channelLink(c.id)!!)
        b.core.setPushTarget("00ff00ff", "app.anynear", sandbox = true)
        val space = Key.parse(c.id)
        eventually { net.holders(space.toString()).all { r -> r.watches.any { it.space == space } } }
        b.core.stop()

        val sent = assertNotNull(a.core.sendAlert(c.id, AnmpAlerts.ALARM, "Wasser", "Wassermelder Keller"))
        a.await<CoreEvent.Stored> { it.ref == sent.id }
        val nse = AnmpCore(scope, net.config(), AnmpPlatform(b.secrets, b.store, b.blobs, connector = net.connector))
        nse.open()
        val found = nse.wakeFetch().filterIsInstance<CoreEvent.Alert>()
        val alert = found.single { it.item.id == sent.id }.item
        assertEquals(AnmpAlerts.ALARM, alert.prio)
        assertEquals("Wassermelder Keller", alert.text)
    }

    @Test
    fun eraseEverythingRemovesChannelsAlertsAndDeviceAddresses() = world {
        val a = device("Anna")
        val b = device("Ben")
        val own = a.core.createChannel("Haus")
        val other = b.core.createChannel("Nachbarn")
        a.core.joinChannel(b.core.channelLink(other.id)!!)
        val ingress = assertNotNull(a.core.createIngress(own.id, "Server"))
        val record = ingressRecord(ingress)
        val sent = assertNotNull(b.core.sendAlert(other.id, AnmpAlerts.WARNING, null, "vor dem Löschen"))
        a.await<CoreEvent.Alert> { it.item.id == sent.id }
        eventually { a.blobs.names.contains("core/alerts") }
        val wakeKeys = net.list.relays.flatMap { r -> listOf("core.cursor.${other.id}.${r.node}", "core.wake.${other.id}.${r.node}") }
        eventually { wakeKeys.any { a.store.getString(it) != null } }

        assertEquals(EraseResult.Done, a.core.eraseEverything())

        assertTrue(a.core.channels.value.isEmpty())
        assertTrue(a.core.alerts.value.isEmpty())
        // Sender seeds and tokens lived only in the channel state; the sealed alerts are gone and stay gone.
        val state = a.secrets.get(AnmpCore.STATE_KEY)!!.decodeToString()
        assertFalse(state.contains(ingress.token) || state.contains(own.readKey))
        kotlinx.coroutines.delay(1_000)
        assertFalse(a.blobs.names.contains("core/alerts"))
        assertTrue(wakeKeys.all { a.store.getString(it) == null })
        // The device address of the own channel is revoked: its sender grant no longer writes, its record is deleted.
        val space = Key.parse(own.id)
        assertFalse(
            put(space, SigningKey.fromSeed(record.senderSeed), Grant.parse(record.grant),
                AnmpAlerts.encode(body("nach dem Löschen"), AnmpSpaceKey.fromPrivateKey(record.senderSeed), record.readPublicKey)),
        )
        val token = AnmpAlerts.tokenBytes(ingress.url.substringAfterLast('/'))!!
        val ingressSpace = Key(AnmpAlerts.ingressSpace(token).publicKey)
        eventually { net.holders(ingressSpace.toString()).all { it.entries(ingressSpace).isEmpty() } }
    }

    @Test
    fun alertsSurviveARestart() = world {
        val a = device("Anna")
        val b = device("Ben")
        val c = a.core.createChannel("Haus")
        b.core.joinChannel(a.core.channelLink(c.id)!!)
        val sent = assertNotNull(a.core.sendAlert(c.id, AnmpAlerts.WARNING, null, "bleibt"))
        b.await<CoreEvent.Alert> { it.item.id == sent.id }
        b.core.stop()
        // Kept sealed: the blob store holds no plaintext.
        for (name in b.blobs.names) assertFalse(b.blobs.read(name)?.decodeToString()?.contains("bleibt") == true, name)
        val again = AnmpCore(scope, net.config(), AnmpPlatform(b.secrets, b.store, b.blobs, connector = net.connector))
        again.open()
        eventually { again.alerts.value.any { it.id == sent.id && it.text == "bleibt" } }
        assertFailsWith<IllegalArgumentException> { a.core.sendAlert(c.id, 7, null, "falsche Priorität") }
    }

    /** An alert by the owner of [c] (from [owner]'s state) with a time in the past, written straight into the channel space. */
    private suspend fun World.oldAlert(owner: Device, c: ChannelState, text: String, ageSeconds: Long): String {
        val seed = B64.decode(owner.core.channel(c.id)!!.ownerSeed!!)
        val b = AnmpAlertBody(AnmpKeys.randomKey().copyOf(AnmpAlerts.ID_BYTES), nowS() - ageSeconds, AnmpAlerts.INFO, null, text, until = nowS() + 3600)
        val readPub = AnmpAlerts.readPublicKey(B64.decode(owner.core.channel(c.id)!!.readKey))
        assertTrue(put(Key.parse(c.id), SigningKey.fromSeed(seed), null, AnmpAlerts.encode(b, AnmpSpaceKey.fromPrivateKey(seed), readPub)))
        return b.id.toHex()
    }

    @Test
    fun channelsReachAnOwnDeviceAndLeavingEndsThemEverywhere() = world {
        val a = device("Anna")
        val b = device("Ben")
        val own = a.core.createChannel("Haus")
        val ingress = assertNotNull(a.core.createIngress(own.id, "Server"))
        val other = b.core.createChannel("Nachbarn")
        val old = oldAlert(b, other, "von gestern", 3_600)
        assertIs<ChannelJoinResult.Joined>(a.core.joinChannel(b.core.channelLink(other.id)!!))
        // Older than the subscription: shown, not to be notified.
        assertTrue(a.await<CoreEvent.Alert> { it.item.id == old }.history)

        // The tablet gets both channels with the snapshot: the own one with owner key and device address.
        val tablet = addTablet(a)
        eventually { tablet.core.channel(own.id) != null && tablet.core.channel(other.id) != null }
        val ownThere = tablet.core.channel(own.id)!!
        assertTrue(ownThere.owner)
        assertEquals(ingress.id, ownThere.ingress.single().id)
        assertEquals(ingress.url, ownThere.ingress.single().url)
        assertEquals(own.joinedAt, ownThere.joinedAt)
        // Same channel, read key and grant; the way to the owner names the device that made the link (ADR 0062 §11).
        val fromPhone = assertNotNull(b.core.parseChannelLink(assertNotNull(a.core.channelLink(own.id))))
        val linkTablet = assertNotNull(b.core.parseChannelLink(assertNotNull(tablet.core.channelLink(own.id))))
        assertEquals(fromPhone.channelId, linkTablet.channelId)
        assertContentEquals(fromPhone.readKey, linkTablet.readKey)
        assertEquals(fromPhone.grant.toString(), linkTablet.grant.toString())
        assertTrue(fromPhone.seenByOwner && linkTablet.seenByOwner)
        assertFalse(tablet.core.channel(other.id)!!.owner)
        // It starts listing at once; what was there before comes as history.
        assertTrue(tablet.await<CoreEvent.Alert> { it.item.id == old }.history)

        // New alerts reach the tablet in both channels; the tablet sends in the own channel, the phone gets it.
        val fresh = assertNotNull(b.core.sendAlert(other.id, AnmpAlerts.WARNING, null, "jetzt"))
        assertFalse(tablet.await<CoreEvent.Alert> { it.item.id == fresh.id }.history)
        val fromTablet = assertNotNull(tablet.core.sendAlert(own.id, AnmpAlerts.INFO, null, "vom Tablet"))
        assertFalse(a.await<CoreEvent.Alert> { it.item.id == fromTablet.id }.history)
        // The device address works the same for both: alerts it sends reach the tablet.
        val record = ingressRecord(ingress)
        val viaAddress = body("über die Adresse")
        assertTrue(put(Key.parse(own.id), SigningKey.fromSeed(record.senderSeed), Grant.parse(record.grant),
            AnmpAlerts.encode(viaAddress, AnmpSpaceKey.fromPrivateKey(record.senderSeed), record.readPublicKey)))
        tablet.await<CoreEvent.Alert> { it.item.id == viaAddress.id.toHex() }

        // Renaming and revoking on the tablet reach the phone; revoked stays revoked.
        assertTrue(tablet.core.renameChannel(own.id, "Haus am See"))
        eventually { a.core.channel(own.id)?.name == "Haus am See" }
        val second = assertNotNull(a.core.createIngress(own.id, "Kamera"))
        eventually { tablet.core.channel(own.id)?.ingress?.size == 2 }
        assertTrue(tablet.core.revokeIngress(own.id, ingress.id))
        eventually { a.core.channel(own.id)?.ingress?.map { it.id } == listOf(second.id) }
        assertFalse(put(Key.parse(own.id), SigningKey.fromSeed(record.senderSeed), Grant.parse(record.grant),
            AnmpAlerts.encode(body("widerrufen"), AnmpSpaceKey.fromPrivateKey(record.senderSeed), record.readPublicKey)))

        // A reset link on the phone reaches the tablet: same read key, so it still opens new alerts.
        val reset = assertNotNull(a.core.resetChannelLink(own.id))
        eventually { tablet.core.channel(own.id)?.readKey == reset.readKey }

        // Leaving on the tablet ends the subscription on the phone too, and the snapshot does not bring it back.
        tablet.core.leaveChannel(other.id)
        eventually { a.core.channel(other.id) == null }
        tablet.core.leaveChannel(own.id)
        eventually { a.core.channel(own.id) == null }
        kotlinx.coroutines.delay(1_000)
        assertNull(a.core.channel(other.id))
        assertNull(tablet.core.channel(other.id))
        assertNull(tablet.core.channel(own.id))
        assertTrue(a.core.alerts.value.none { it.channelId == other.id })
        // Subscribing again afterwards counts: newer than the leave.
        assertIs<ChannelJoinResult.Joined>(a.core.joinChannel(b.core.channelLink(other.id)!!))
        eventually { tablet.core.channel(other.id) != null }
    }

    @Test
    fun emergencyCardBringsChannelsBackWithSendingAndDeviceAddresses() = world {
        val a = device("Anna")
        val b = device("Ben")
        val own = a.core.createChannel("Haus")
        val ingress = assertNotNull(a.core.createIngress(own.id, "Server"))
        val record = ingressRecord(ingress)
        assertIs<ChannelJoinResult.Joined>(b.core.joinChannel(a.core.channelLink(own.id)!!))
        val other = b.core.createChannel("Nachbarn")
        assertIs<ChannelJoinResult.Joined>(a.core.joinChannel(b.core.channelLink(other.id)!!))
        val before = assertNotNull(b.core.sendAlert(other.id, AnmpAlerts.INFO, null, "vorher"))
        a.await<CoreEvent.Alert> { it.item.id == before.id }
        val code = assertNotNull(a.core.createEmergencyCard())
        assertTrue(a.core.confirmEmergencyCard(code))

        // Phone lost: a new phone with the code.
        val fresh = device("Neu")
        var result: RecoverResult = RecoverResult.Later
        eventually { result = fresh.core.recoverWithCode(code); result != RecoverResult.Later }
        assertIs<RecoverResult.Recovered>(result)
        fresh.core.stop()
        devices -= fresh
        val again = device("Anna", secrets = fresh.secrets)
        val ownBack = assertNotNull(again.core.channel(own.id))
        assertTrue(ownBack.owner)
        assertEquals(ingress.url, ownBack.ingress.single().url)
        assertFalse(assertNotNull(again.core.channel(other.id)).owner)
        // Alerts are not in the state: the channel space gives them again, as history.
        again.await<CoreEvent.Alert> { it.item.id == before.id }

        // Sending works: Ben, subscribed with the old link, gets it.
        val sent = assertNotNull(again.core.sendAlert(own.id, AnmpAlerts.WARNING, null, "wieder da"))
        b.await<CoreEvent.Alert> { it.item.id == sent.id }
        val after = assertNotNull(b.core.sendAlert(other.id, AnmpAlerts.INFO, null, "nachher"))
        assertFalse(again.await<CoreEvent.Alert> { it.item.id == after.id }.history)
        // Revoking the device address works too: its sender grant no longer writes, its record is gone.
        assertTrue(again.core.revokeIngress(own.id, ingress.id))
        val space = Key.parse(own.id)
        eventually { net.holders(space.toString()).all { it.revokedIn(space) > 0 } }
        assertFalse(put(space, SigningKey.fromSeed(record.senderSeed), Grant.parse(record.grant),
            AnmpAlerts.encode(body("nach dem Widerruf"), AnmpSpaceKey.fromPrivateKey(record.senderSeed), record.readPublicKey)))
        val token = AnmpAlerts.tokenBytes(ingress.url.substringAfterLast('/'))!!
        val ingressSpace = Key(AnmpAlerts.ingressSpace(token).publicKey)
        eventually { net.holders(ingressSpace.toString()).all { it.entries(ingressSpace).isEmpty() } }
    }
}
