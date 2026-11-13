package app.anynear.anmp.core

import app.anynear.andp.Credentials
import app.anynear.andp.Grant
import app.anynear.andp.ID
import app.anynear.andp.Key
import app.anynear.andp.ListOptions
import app.anynear.andp.Meta
import app.anynear.andp.AndpHttpException
import app.anynear.andp.Placement
import app.anynear.andp.SigningKey
import app.anynear.anmp.Anmp
import app.anynear.anmp.AnmpRadio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Packet from the radio inbox: public form bytes and the ANDP time it was written (Unix seconds). */
class RadioInboxPacket(val packet: ByteArray, val ts: Long)

/**
 * The radio inbox of ADR 0051: a ANDP space with a grant for anyone. Every entry is one radio packet in the public
 * form, unchanged. Writing is best effort without a queue (others hear the same packet and write it too); every
 * write uses a fresh writer key, so relays cannot link the uplinks of one device. Reading lists the space with one
 * cursor per relay and filters by route tag on the device. Route tags are those of the hour the entry was written.
 */
internal class RadioInbox(
    private val space: Key,
    private val grant: Grant,
    private val platform: AnmpPlatform,
    private val net: NetworkDirectory,
    private val pool: RelayPool,
) {
    private val lock = Mutex()
    private val cursors = mutableMapOf<String, String>()
    private val seen = LinkedHashMap<String, Long>()

    /**
     * Writes [packet] if it is a radio packet in the public form. `true` once at least one holder took it. The
     * caller remembers what it uplinked; equal bytes give the same object anyway.
     */
    suspend fun put(packet: ByteArray): Boolean {
        if (!isPublicForm(packet)) return false
        val writer = SigningKey.generate()
        val ts = platform.clock.nowMillis() / 1000
        val meta = Meta.signData(writer, space, packet, ts, until = ts + LIFETIME_SECONDS, placement = Placement.SPACE, grant = grant)
        var stored = false
        for (relay in net.holders(space.toString())) {
            val client = pool.client(relay) ?: continue
            try {
                client.put(Credentials(writer, grant), meta, packet)
                pool.ok(relay.node)
                stored = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: AndpHttpException) {
                pool.ok(relay.node)
                platform.log.log("radio inbox: put at ${relay.site} refused ${e.status}")
            } catch (e: Exception) {
                pool.failed(relay.node)
            }
        }
        return stored
    }

    /**
     * New packets since the last call (per relay cursor) whose route tag passes [wanted], at most once each.
     * The first call per relay starts at the beginning of the space (entries live [LIFETIME_SECONDS]).
     */
    suspend fun poll(wanted: (routeTag: ByteArray) -> Boolean): List<RadioInboxPacket> = lock.withLock {
        val out = mutableListOf<RadioInboxPacket>()
        val writer = reader ?: SigningKey.generate().also { reader = it }
        for (relay in net.holders(space.toString())) {
            val client = pool.client(relay) ?: continue
            try {
                var after = cursors[relay.node.toString()].orEmpty()
                while (true) {
                    val page = client.list(Credentials(writer, grant), space, ListOptions(after, 0, PAGE))
                    for (o in page.objects) {
                        val id = o.id.toString()
                        if (id in seen || o.placement != Placement.SPACE || o.size > Anmp.RADIO_MAX_BYTES) continue
                        val bytes = o.inline() ?: client.get(Credentials(writer, grant), space, o.id).data
                        // Relays verify writes; the device checks that the bytes are the object.
                        if (ID.of(bytes).toString() != id || !isPublicForm(bytes)) continue
                        remember(id, o.ts)
                        val route = Anmp.peek(bytes)?.route ?: continue
                        if (wanted(route)) out += RadioInboxPacket(bytes, o.ts)
                    }
                    if (page.next.isEmpty() || page.next == after) break
                    after = page.next
                }
                cursors[relay.node.toString()] = after
                pool.ok(relay.node)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AndpHttpException) {
                pool.ok(relay.node)
            } catch (e: Exception) {
                pool.failed(relay.node)
            }
        }
        out.sortedByDescending { it.ts }
    }

    private var reader: SigningKey? = null

    private fun remember(id: String, ts: Long) {
        seen[id] = ts
        if (seen.size > MAX_SEEN) seen.keys.take(seen.size - MAX_SEEN).forEach { seen.remove(it) }
    }

    companion object {
        /** Entries live 30 days, the longest relays keep client objects (ANDP `max_until`); for longer outages (ADR 0051). */
        const val LIFETIME_SECONDS = 30L * 24 * 3600

        private const val PAGE = 200
        private const val MAX_SEEN = 20_000

        /** A ANMP packet over radio with a route tag (public form), not open, at most 233 bytes. */
        fun isPublicForm(packet: ByteArray): Boolean = AnmpRadio.isPublicForm(packet)

        /** Space and grant from their wire forms; `null` if either is missing or invalid. */
        fun parse(space: String, grant: String): Pair<Key, Grant>? {
            if (space.isEmpty() || grant.isEmpty()) return null
            val k = runCatching { Key.parse(space) }.getOrNull() ?: return null
            val g = runCatching { Grant.parse(grant) }.getOrNull() ?: return null
            return k to g
        }
    }
}
