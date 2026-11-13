package app.anynear.anmp.core

import app.anynear.andp.Credentials
import app.anynear.andp.Key
import app.anynear.andp.ListOptions
import app.anynear.andp.Meta
import app.anynear.andp.NetworkList
import app.anynear.andp.AndpClient
import app.anynear.andp.AndpHttpException
import app.anynear.andp.Placement
import app.anynear.andp.Relay
import app.anynear.andp.SignedList
import app.anynear.andp.SigningKey
import app.anynear.andp.networkId
import app.anynear.andp.pinned
import app.anynear.anmp.AnmpKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The core against the Go test relay of the private repository ANDP-Peer (internal/cmd/testrelay, in-memory relay over real TLS with
 * pinning, no SNI). One instance: the test relay has a fixed node key, so several instances cannot form one
 * network. Revocations are not checked by the Go test relay; that part is covered with the Kotlin fake (FakeNet).
 * Runs where `go` is installed (ANMP_REQUIRE_GO=1 makes a missing Go an error).
 */
class GoRelayCoreTest {
    @Serializable
    private class Started(val relay: Relay, val list: SignedList)

    private fun goAvailable(): Boolean = try {
        ProcessBuilder("go", "version").redirectErrorStream(true).start().waitFor(30, TimeUnit.SECONDS)
    } catch (e: Exception) {
        false
    }

    private class Node(val name: String, val core: AnmpCore, val secrets: MemorySecretStore, val store: MemoryKeyValueStore, val blobs: MemoryBlobStore, val host: TestHost) {
        val events = MutableStateFlow<List<CoreEvent>>(emptyList())
        lateinit var deviceId: String

        suspend inline fun <reified T : CoreEvent> await(crossinline predicate: (T) -> Boolean = { true }): T =
            withTimeout(30.seconds) { events.first { l -> l.any { it is T && predicate(it) } } }.first { it is T && predicate(it) } as T
    }

    @Test
    fun coreAgainstGoTestRelay() {
        val goDir = File(System.getenv("ANDP_GO_DIR") ?: return)
        if (!goDir.isDirectory || !goAvailable()) {
            if (System.getenv("ANMP_REQUIRE_GO") == "1") error("go and the repository ANDP-Peer (ANDP_PEER_DIR) are required for the integration test")
            println("GoRelayCoreTest: go or ANDP not found, skipped")
            return
        }
        val proc = ProcessBuilder("go", "run", "./internal/cmd/testrelay").directory(goDir).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            val line = proc.inputStream.bufferedReader().readLine() ?: error("test relay did not start")
            val started = coreJson.decodeFromString(Started.serializer(), line)
            runBlocking(Dispatchers.Default) { exercise(started) }
        } finally {
            proc.outputStream.close()
            proc.descendants().forEach { it.destroy() }
            proc.destroy()
            proc.waitFor(10, TimeUnit.SECONDS)
        }
    }

    private suspend fun exercise(s: Started) {
        // The test relay's list also names an unreachable second relay; we sign our own list with only the real one
        // (the network key seed is a published test label of ANDP).
        val netKey = SigningKey.fromSeed(AnmpKeys.sha256("ANDP client test: network".encodeToByteArray()))
        val list = NetworkList(1, networkId(netKey.key), netKey.key, 2000, relays = listOf(s.relay))
        val embedded = coreJson.encodeToString(SignedList.serializer(), list.sign(netKey))
        val config = AnmpCoreConfig(list.id, netKey.key, embedded, seedUrl = "https://127.0.0.1:1", timings = TEST_TIMINGS)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val offline = MutableStateFlow(false)
        val pinned = platformConnector()
        val connector = object : RelayConnector {
            override fun connect(relay: Relay): AndpClient? = if (offline.value) AndpClient.forUrl("https://127.0.0.1:1") else pinned.connect(relay)
            override fun connectUrl(baseUrl: String): AndpClient = pinned.connectUrl(baseUrl)
        }

        suspend fun node(name: String, secrets: MemorySecretStore = MemorySecretStore(), store: MemoryKeyValueStore = MemoryKeyValueStore(), blobs: MemoryBlobStore = MemoryBlobStore()): Node {
            val host = TestHost()
            val core = AnmpCore(scope, config, AnmpPlatform(secrets, store, blobs, connector = connector), host)
            val n = Node(name, core, secrets, store, blobs, host)
            scope.launch {
                core.events.collect { e ->
                    if (e is CoreEvent.Message && e.opened != null) host.history.getOrPut((e.conversation as Conversation.Circle).circleId) { mutableListOf() } += e.opened
                    n.events.update { it + e }
                }
            }
            n.deviceId = core.start().deviceId
            core.setProfile(name)
            return n
        }

        try {
            val a = node("Anna")
            val b = node("Ben")

            // Circle: join by link, announcement, message with attachment (content + refs), receipt.
            val circle = a.core.createCircle("Familie")
            assertIs<JoinResult.Joined>(b.core.joinCircle(a.core.inviteLink(circle.id)!!))
            b.await<CoreEvent.MemberUpdated> { it.member.deviceId == a.deviceId }
            a.await<CoreEvent.MemberUpdated> { it.member.deviceId == b.deviceId }
            eventually(30.seconds) { a.core.canWriteTo(b.deviceId) && b.core.canWriteTo(a.deviceId) }
            val bytes = ByteArray(300_000) { (it * 7).toByte() }
            val out = assertNotNull(a.core.sendCircle(circle.id, "Hallo über Go", listOf(a.core.prepareAttachment(bytes, "jpg"))))
            a.host.history.getOrPut(circle.id) { mutableListOf() } += out.opened!! // the app keeps its own messages too
            val msg = b.await<CoreEvent.Message> { it.mid == out.mid }
            assertContentEquals(bytes, assertIs<BlobResult.Found>(b.core.fetchAttachment(msg.space, msg.attachments.single())).plaintext)
            a.await<CoreEvent.Receipt> { out.mid in it.mids }

            // Direct through a contact link, both ways.
            val c = node("Clara")
            assertIs<AddContactResult.Added>(c.core.addContact(a.core.ownContactLink()))
            eventually(30.seconds) { a.core.canWriteTo(c.deviceId) }
            val d1 = assertNotNull(c.core.sendDirect(a.deviceId, "Hallo Anna"))
            a.await<CoreEvent.Message> { it.mid == d1.mid }
            val d2 = assertNotNull(a.core.sendDirect(c.deviceId, "Hallo Clara"))
            c.await<CoreEvent.Message> { it.mid == d2.mid }
            c.await<CoreEvent.Receipt> { d1.mid in it.mids }

            // A stranger without grant cannot write into Anna's mailbox.
            val inbox = Key.parse(a.core.identity.value!!.inbox)
            val stranger = SigningKey.fromSeed(AnmpKeys.sha256("stranger".encodeToByteArray()))
            val now = Clock.System.now().epochSeconds
            val data = "spam".encodeToByteArray()
            AndpClient.pinned(s.relay).use { client ->
                val e = assertFailsWith<AndpHttpException> { client.put(Credentials(stranger), Meta.signData(stranger, inbox, data, now, now + 600, Placement.SPACE), data) }
                assertEquals(403, e.status)
            }

            // History for a member who joins later, with the parts deleted by the scoped delete grant.
            val later = assertNotNull(a.core.sendCircle(circle.id, "für später"))
            a.host.history.getOrPut(circle.id) { mutableListOf() } += later.opened!!
            b.await<CoreEvent.Message> { it.mid == later.mid }
            val dora = node("Dora")
            dora.core.joinCircle(a.core.inviteLink(circle.id)!!)
            dora.await<CoreEvent.HistoryLoaded> { it.circleId == circle.id }
            val history = dora.events.value.filterIsInstance<CoreEvent.Message>().filter { it.history }.map { it.text }
            assertTrue("für später" in history && "Hallo über Go" in history, "history: $history")

            // Removing Ben writes a revocation entry for the old circle grant into Anna's mailbox.
            a.core.removeMember(circle.id, b.deviceId)
            b.await<CoreEvent.KeyChanged> { it.kind == CoreEvent.KIND_REMOVED_YOU }
            val owner = SigningKey.fromSeed(a.secrets.get("core.inbox.owner")!!)
            eventually(30.seconds) {
                AndpClient.pinned(s.relay).use { client -> client.list(Credentials(owner), inbox, ListOptions()).objects.any { it.revokes != null } }
            }

            // Personal store (ADR 0041): without expiry, readable again, deletable.
            val stored = a.core.storePut("f1", bytes, "Vollmacht.pdf", "pdf", added = 1)
            a.await<CoreEvent.Stored> { it.ref == stored }
            val file = assertNotNull(a.core.storeList()).single()
            assertEquals("Vollmacht.pdf", file.name)
            assertContentEquals(bytes, assertIs<BlobResult.Found>(a.core.storeFetch(file)).plaintext)
            val storeOwner = SigningKey.fromSeed(AnmpKeys.hkdf(a.secrets.get(StoreKeys.SECRET)!!, app.anynear.anmp.AnmpInfo.STORE_SIGN))
            AndpClient.pinned(s.relay).use { client ->
                assertTrue(client.list(Credentials(storeOwner), storeOwner.key, ListOptions()).objects.all { it.until == 0L })
            }
            assertTrue(a.core.storeDelete("f1"))
            eventually(30.seconds) { a.core.storeList()?.isEmpty() == true }

            // Channel (ADR 0062): subscribe by link, alarm by the owner, an alert through a device address as the relay
            // sends it (sender key with the owner's grant), a stranger refused.
            val ch = a.core.createChannel("Haus")
            assertIs<ChannelJoinResult.Joined>(b.core.joinChannel(a.core.channelLink(ch.id)!!))
            val alarm = assertNotNull(a.core.sendAlert(ch.id, app.anynear.anmp.AnmpAlerts.ALARM, "Rauch", "Rauch im Keller"))
            assertEquals(app.anynear.anmp.AnmpAlerts.ALARM, b.await<CoreEvent.Alert> { it.item.id == alarm.id }.item.prio)
            val ingress = assertNotNull(a.core.createIngress(ch.id, "Server"))
            val token = app.anynear.anmp.AnmpAlerts.tokenBytes(ingress.url.substringAfterLast('/'))!!
            val ingressSpace = Key(app.anynear.anmp.AnmpAlerts.ingressSpace(token).publicKey)
            val reader = SigningKey.fromSeed(AnmpKeys.sha256("relay reader".encodeToByteArray()))
            var record: app.anynear.anmp.AnmpIngressRecord? = null
            eventually(30.seconds) {
                // The owner of the ingress space reads it the way a relay would with the derived key.
                val ingressOwner = SigningKey.fromSeed(app.anynear.anmp.AnmpAlerts.ingressSeed(token))
                record = AndpClient.pinned(s.relay).use { client ->
                    client.list(Credentials(ingressOwner), ingressSpace, ListOptions()).objects.firstNotNullOfOrNull { o ->
                        val bytes = o.inline() ?: client.get(Credentials(ingressOwner), ingressSpace, o.id).data
                        app.anynear.anmp.AnmpAlerts.openIngress(token, bytes)
                    }
                }
                record != null
            }
            val rec = record!!
            val sender = SigningKey.fromSeed(rec.senderSeed)
            val chSpace = Key.parse(ch.id)
            val body = app.anynear.anmp.AnmpAlertBody(AnmpKeys.randomKey().copyOf(8), Clock.System.now().epochSeconds, 2, "Server", "Platte voll")
            val packet = app.anynear.anmp.AnmpAlerts.encode(body, app.anynear.anmp.AnmpSpaceKey.fromPrivateKey(rec.senderSeed), rec.readPublicKey)
            val t = Clock.System.now().epochSeconds
            val grant = app.anynear.andp.Grant.parse(rec.grant)
            AndpClient.pinned(s.relay).use { client ->
                client.put(Credentials(sender, grant), Meta.signData(sender, chSpace, packet, t, t + 600, Placement.SPACE, grant = grant), packet, app.anynear.andp.PutOptions(alarm = true))
                val e403 = assertFailsWith<AndpHttpException> { client.put(Credentials(reader), Meta.signData(reader, chSpace, packet, t, t + 600, Placement.SPACE), packet) }
                assertEquals(403, e403.status)
            }
            assertEquals(ingress.id, b.await<CoreEvent.Alert> { it.item.text == "Platte voll" }.item.sender)

            // Pending writes over a restart: written while the relay is unreachable, delivered by a new core.
            offline.value = true
            val e = node("Emil")
            e.core.addContact(a.core.ownContactLink())
            val late = assertNotNull(e.core.sendDirect(a.deviceId, "nach dem Neustart"))
            e.core.stop()
            offline.value = false
            val e2 = node("Emil", e.secrets, e.store, e.blobs)
            a.await<CoreEvent.Message> { it.mid == late.mid }
            e2.core.stop()
            listOf(a, b, c, dora).forEach { it.core.stop() }
        } finally {
            scope.cancel()
        }
    }
}
