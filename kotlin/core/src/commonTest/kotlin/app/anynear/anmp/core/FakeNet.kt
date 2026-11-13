package app.anynear.anmp.core

import app.anynear.andp.Auth
import app.anynear.andp.Hash
import app.anynear.andp.ID
import app.anynear.andp.Clients
import app.anynear.andp.Info
import app.anynear.andp.Key
import app.anynear.andp.ListPage
import app.anynear.andp.Meta
import app.anynear.andp.NetworkList
import app.anynear.andp.ObjectState
import app.anynear.andp.Andp
import app.anynear.andp.AndpClient
import app.anynear.andp.AndpException
import app.anynear.andp.AndpHeaders
import app.anynear.andp.AndpPaths
import app.anynear.andp.Placements
import app.anynear.andp.Policy
import app.anynear.andp.RadioHead
import app.anynear.andp.Receipt
import app.anynear.andp.Relay
import app.anynear.andp.RelayState
import app.anynear.andp.Revoked
import app.anynear.andp.Rights
import app.anynear.andp.SignedList
import app.anynear.andp.SigningKey
import app.anynear.andp.Tombstone
import app.anynear.andp.WatchRequest
import app.anynear.andp.StreamInfo
import app.anynear.andp.bytesToHex
import app.anynear.andp.watchIdForHexToken
import app.anynear.andp.networkId
import app.anynear.anmp.AnmpKeys
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.time.Clock

internal suspend fun testKey(label: String) = SigningKey.fromSeed(AnmpKeys.sha256("ANMP core test: $label".encodeToByteArray()))

private fun nowS() = Clock.System.now().epochSeconds

/**
 * A network of in-memory relays behind a Ktor mock engine, built from the ANDP relay-side helpers. Unlike the Go
 * test relay it checks revocations (space holders, ANDP §4.3), holds long polls and answers 404 with holders.
 * No repair between relays: what a test removes stays removed until a client puts it back.
 */
internal class FakeNet private constructor(val netKey: SigningKey, val relays: List<FakeRelay>, signed: SignedList, val list: NetworkList) {
    /** The list every relay and the starting point hand out; [publish] replaces it. */
    var signed: SignedList = signed
        private set

    /** The radio list every relay hands out (ANDP §5.9); `null`: relays answer 404. */
    var radio: SignedList? = null
        private set

    /** Hands out a radio list with [content] (fields besides the head, as JSON text without braces) and [issued]. */
    suspend fun publishRadio(content: String, issued: Long) {
        val text = "{\"v\":1,\"id\":\"${list.id}\",\"network\":\"${netKey.key}\",\"issued\":$issued,$content}"
        radio = RadioHead.sign(netKey, text)
    }

    /** Hands out [next], signed by the network key, from now on (a newer list, e.g. with a relay set active). */
    suspend fun publish(next: NetworkList) {
        signed = next.sign(netKey)
    }

    val http = HttpClient(MockEngine { r -> handle(this, r) })

    val connector = object : RelayConnector {
        override fun connect(relay: Relay): AndpClient = AndpClient.webPki(relay, http)

        override fun connectUrl(baseUrl: String): AndpClient = AndpClient.forUrl(baseUrl, http)
    }

    // Without the network's radio inbox and radio software: tests that need them set up their own.
    fun config(timings: CoreTimings = TEST_TIMINGS) =
        AnmpCoreConfig(
            list.id, netKey.key, seedUrl = "https://seed.test", timings = timings, radioInboxSpace = "", radioInboxGrant = "",
            radioSoftwareSpace = "", radioSoftwareGrant = "",
        )

    fun relay(node: Key): FakeRelay = relays.first { it.node.key == node }

    /** Holders of [key] by placement (ANDP §6). */
    fun holders(key: String): List<FakeRelay> = Placements.place(key, list.relays).chosen.map { relay(it.node) }

    /** Relays that hold object [id] of [space]. */
    suspend fun holding(space: Key, id: ID): List<FakeRelay> = relays.filter { it.has(space, id) }

    private suspend fun handle(scope: MockRequestHandleScope, r: HttpRequestData): HttpResponseData {
        val host = r.url.host
        if (host == "seed.test") return scope.respond(AndpJsonText.encodeToString(SignedList.serializer(), signed), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        val relay = relays.firstOrNull { it.relay.host == host } ?: throw IllegalStateException("unknown host $host")
        if (relay.offline) throw IllegalStateException("relay ${relay.relay.site} offline")
        return relay.handle(scope, r, this)
    }

    companion object {
        suspend fun create(n: Int = 4): FakeNet {
            val netKey = testKey("network")
            val nodes = (0 until n).map { testKey("node-$it") }
            val relays = nodes.mapIndexed { i, k ->
                Relay("r$i", "192.0.2.${10 + i}:443", Hash.of(byteArrayOf(i.toByte())), "r$i.relay.test", k.key, "op$i", RelayState.ACTIVE)
            }
            val list = NetworkList(1, networkId(netKey.key), netKey.key, 1000, relays = relays)
            val signed = list.sign(netKey)
            return FakeNet(netKey, nodes.mapIndexed { i, k -> FakeRelay(k, relays[i]) }, signed, list)
        }
    }
}

internal val AndpJsonText = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

@Serializable
private class StatusBody(val ids: List<ID>)

@Serializable
private class StatusAnswer(val objects: Map<String, ObjectState>)

@Serializable
private class NotFound(val error: String, val holders: List<Relay>)

internal class FakeRelay(val node: SigningKey, val relay: Relay) {
    class Stored(val meta: Meta, val data: ByteArray, val seq: Long)

    private val lock = Mutex()
    private val objects = mutableMapOf<String, Stored>()
    private val tombstones = mutableMapOf<String, Tombstone>()
    private val revoked = mutableSetOf<String>()
    private val seq = MutableStateFlow(0L)
    val watches = mutableListOf<WatchRequest>()
    val puts = mutableListOf<Meta>()
    val notifiedPuts = mutableListOf<Meta>()

    /** Entries written with `ANDP-Notify: alarm` (ADR 0062). */
    val alarmPuts = mutableListOf<Meta>()

    /** Offers the stream extension (ANDP §18) and whether apps shall use it (`stream.clients`). */
    var stream = true
    var streamClients = true

    /** Open streams by token, and how often one was opened. */
    private val streams = mutableMapOf<String, ByteChannel>()
    var streamOpens = 0
    private val streamWrite = Mutex()

    /** Ends every open stream, as a relay restart or a broken connection would. */
    suspend fun dropStreams() = lock.withLock {
        streams.values.forEach { it.close() }
        streams.clear()
    }

    /** Hands a `new` event to the open stream of [token] (as `peer/notify` would); false without stream. */
    suspend fun deliver(token: String, tag: String, alarm: Boolean): Boolean {
        val ch = lock.withLock { streams[token] } ?: return false
        // A ByteChannel takes one writer at a time; holders deliver concurrently.
        return runCatching { streamWrite.withLock { ch.writeStringUtf8("event: new\ndata: {\"tags\":[\"$tag\"],\"alarm\":$alarm}\n\n") } }.isSuccess
    }

    /** Simulated outage: every request fails like a broken connection. */
    var offline = false

    /** Announced in /andp/v1/info. */
    var policy = Policy.DEFAULT

    /** `clients.min` announced in /andp/v1/info (ANDP §5.2); `null` leaves the field out. */
    var minClient: Int? = null

    /** Spaces of every list request, in order (to count long polls). */
    val lists = mutableListOf<Key>()

    /** Number of GET /andp/v1/info. */
    var infoCalls = 0

    /** Bodies of POST /stats/v1 (ADR 0045). */
    val statsReports = mutableListOf<ByteArray>()

    private val isRevoked = Revoked { space, hash -> "$space/$hash" in revoked }

    suspend fun has(space: Key, id: ID) = lock.withLock { objects.containsKey("$space/$id") }

    suspend fun get(space: Key, id: ID): Stored? = lock.withLock { objects["$space/$id"] }

    /** Simulates a lost copy (disk failure). */
    suspend fun lose(space: Key, id: ID) = lock.withLock { objects.remove("$space/$id") }

    suspend fun tombstoneCount(): Int = lock.withLock { tombstones.size }

    suspend fun revokedIn(space: Key): Int = lock.withLock { revoked.count { it.startsWith("$space/") } }

    suspend fun entries(space: Key): List<Stored> = lock.withLock { objects.values.filter { it.meta.space == space && it.meta.isEntry } }

    private fun MockRequestHandleScope.json(text: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
        respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.fail(status: Int, code: String): HttpResponseData =
        json("{\"error\":\"$code\",\"message\":\"$code\"}", HttpStatusCode.fromValue(status))

    private suspend fun auth(r: HttpRequestData): Auth? = try {
        val values = r.headers.getAll(AndpHeaders.AUTHORIZATION).orEmpty()
        if (values.size != 1) null else Auth.parse(values[0]).also { it.verify(r.method.value, r.url.encodedPath, nowS()) }
    } catch (e: AndpException) {
        null
    }

    suspend fun handle(scope: MockRequestHandleScope, r: HttpRequestData, net: FakeNet): HttpResponseData = with(scope) {
        val path = r.url.encodedPath
        val body = r.body.toByteArray()
        when {
            path == AndpPaths.INFO -> json(run {
                infoCalls++
                val ext = listOf(Andp.EXTENSION_WATCH, Andp.EXTENSION_WEBPUSH) + if (stream) listOf(Andp.EXTENSION_STREAM) else emptyList()
                AndpJsonText.encodeToString(Info.serializer(), Info(node.key, net.list.network, policy, ext, FAKE_VAPID_KEY, minClient?.let { Clients(it) }, if (stream) StreamInfo(180, streamClients) else null))
            })
            path == AndpPaths.STREAM -> {
                val a = auth(r) ?: return fail(401, "unauthorized")
                if (!stream) return fail(404, "no_route")
                val token = bytesToHex(a.key.bytes)
                val ch = ByteChannel(autoFlush = true)
                lock.withLock {
                    streams.put(token, ch)?.close()
                    streamOpens++
                }
                ch.writeStringUtf8("event: ping\ndata: {}\n\n")
                respond(ch, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
            path == AndpPaths.NET -> json(AndpJsonText.encodeToString(SignedList.serializer(), net.signed))
            path == AndpPaths.RADIO -> net.radio?.let { json(AndpJsonText.encodeToString(SignedList.serializer(), it)) } ?: fail(404, "not_found")
            path == AndpPaths.WATCH -> {
                val a = auth(r) ?: return fail(401, "unauthorized")
                val req = AndpJsonText.decodeFromString(WatchRequest.serializer(), body.decodeToString())
                try {
                    a.authorize(req.space, Rights.LIST, null, nowS(), isRevoked)
                } catch (e: AndpException) {
                    return fail(403, "forbidden")
                }
                // Like the relay: posting again refreshes, `remove` deletes the watch of that target for that space.
                lock.withLock {
                    watches.removeAll { it.space == req.space && it.target.token == req.target.token }
                    if (!req.remove) watches += req
                }
                respond("", HttpStatusCode.NoContent)
            }
            path == AndpClient.STATS_PATH -> {
                lock.withLock { statsReports += body }
                respond("", HttpStatusCode.NoContent)
            }
            path.startsWith(Andp.PATH_PREFIX + "/s/") -> space(r, path.removePrefix(Andp.PATH_PREFIX + "/s/").split("/"), body, net)
            else -> fail(404, "no_route")
        }
    }

    private suspend fun MockRequestHandleScope.space(r: HttpRequestData, parts: List<String>, body: ByteArray, net: FakeNet): HttpResponseData {
        val space = Key.parse(parts[0])
        if (parts.size == 1) {
            val a = auth(r) ?: return fail(401, "unauthorized")
            try {
                a.authorize(space, Rights.LIST, null, nowS(), isRevoked)
            } catch (e: AndpException) {
                return fail(403, "forbidden")
            }
            lock.withLock { lists += space }
            val after = r.url.parameters["after"]?.toLongOrNull() ?: 0
            val wait = r.url.parameters["wait"]?.toLongOrNull() ?: 0
            suspend fun items() = lock.withLock { objects.values.filter { it.meta.space == space && it.meta.isEntry && it.seq > after }.sortedBy { it.seq } }
            var list = items()
            if (list.isEmpty() && wait > 0) {
                withTimeoutOrNull(wait * 1000) { seq.first { it > after && items().isNotEmpty() } }
                list = items()
            }
            val page = ListPage(list.map { it.meta.listed(it.seq, it.data) }, (list.maxOfOrNull { it.seq } ?: after).toString())
            return json(AndpJsonText.encodeToString(ListPage.serializer(), page))
        }
        if (parts[1] == "status") {
            val a = auth(r) ?: return fail(401, "unauthorized")
            try {
                a.authorize(space, Rights.READ, null, nowS(), isRevoked)
            } catch (e: AndpException) {
                return fail(403, "forbidden")
            }
            val ids = AndpJsonText.decodeFromString(StatusBody.serializer(), body.decodeToString()).ids
            val out = lock.withLock { ids.associate { it.toString() to if (objects.containsKey("$space/$it")) ObjectState.HERE else ObjectState.MISSING } }
            return json(AndpJsonText.encodeToString(StatusAnswer.serializer(), StatusAnswer(out)))
        }
        val id = ID.parse(parts[1])
        val k = "$space/$id"
        when (r.method.value) {
            "PUT" -> {
                val a = auth(r) ?: return fail(401, "unauthorized")
                val m = try {
                    Meta.fromHeaders(space, id, body.size.toLong()) { r.headers.getAll(it).orEmpty() }.also { it.checkData(body) }
                } catch (e: AndpException) {
                    return fail(400, "bad_request")
                }
                try {
                    m.checkClientWrite(a, nowS())
                    // Space holders check revocations; content holders do not (ANDP §7).
                    m.verify(if (m.isEntry) isRevoked else null)
                } catch (e: AndpException) {
                    return fail(403, "forbidden")
                }
                val notify = r.headers[AndpHeaders.NOTIFY] != AndpHeaders.NOTIFY_NONE
                lock.withLock {
                    if (tombstones.containsKey(k)) return fail(410, "gone")
                    puts += m
                    if (notify && m.isEntry) notifiedPuts += m
                    if (notify && m.isEntry && r.headers[AndpHeaders.NOTIFY] == AndpHeaders.NOTIFY_ALARM) alarmPuts += m
                    if (!objects.containsKey(k)) objects[k] = Stored(m, body, seq.value + 1).also { seq.value = it.seq }
                    m.revokes?.let { revoked += "$space/$it" }
                }
                // watch target relay (ANDP §10.2): tell the home relay of each watch, except the writer's own.
                if (notify && m.isEntry && stream) {
                    val skip = r.headers[AndpHeaders.WATCH_SKIP].orEmpty()
                    val alarm = r.headers[AndpHeaders.NOTIFY] == AndpHeaders.NOTIFY_ALARM
                    for (w in lock.withLock { watches.filter { it.space == space && it.target.type == Andp.WATCH_TYPE_RELAY } }) {
                        if (skip.isNotEmpty() && watchIdForHexToken(w.target.token) == skip) continue
                        net.relays.firstOrNull { it.node.key.toString() == w.target.node }?.deliver(w.target.token, w.target.tag, alarm)
                    }
                }
                return json(AndpJsonText.encodeToString(Receipt.serializer(), Receipt.sign(node, space, id, m.until)))
            }
            "GET" -> {
                val o = lock.withLock { objects[k] }
                if (o == null) {
                    if (lock.withLock { tombstones.containsKey(k) }) return fail(410, "gone")
                    val holders = net.holding(space, id).filter { it.node.key != node.key }.map { it.relay }
                    return json(AndpJsonText.encodeToString(NotFound.serializer(), NotFound(Andp.ERROR_NOT_FOUND, holders)), HttpStatusCode.NotFound)
                }
                if (o.meta.isEntry) {
                    val a = auth(r) ?: return fail(401, "unauthorized")
                    try {
                        a.authorize(space, Rights.READ, id, nowS(), isRevoked)
                    } catch (e: AndpException) {
                        return fail(403, "forbidden")
                    }
                }
                return respond(ByteReadChannel(o.data), HttpStatusCode.OK, headersOf(*o.meta.headers().map { it.first to listOf(it.second) }.toTypedArray()))
            }
            "DELETE" -> {
                val a = auth(r) ?: return fail(401, "unauthorized")
                val t = try {
                    Tombstone.fromRequest(a, space, id) { r.headers.getAll(it).orEmpty() }.also { it.verify(isRevoked) }
                } catch (e: AndpException) {
                    return fail(403, "forbidden")
                }
                lock.withLock {
                    tombstones[k] = t
                    objects.remove(k)
                }
                return respond("", HttpStatusCode.NoContent)
            }
        }
        return fail(405, "method")
    }
}

/** VAPID key the fake relays announce in their info (ANDP §10.1). */
internal const val FAKE_VAPID_KEY = "BFakeVapidKey"
