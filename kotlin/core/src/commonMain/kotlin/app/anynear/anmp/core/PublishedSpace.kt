package app.anynear.anmp.core

import app.anynear.andp.Credentials
import app.anynear.andp.Grant
import app.anynear.andp.ID
import app.anynear.andp.Key
import app.anynear.andp.ListOptions
import app.anynear.andp.AndpHttpException
import app.anynear.andp.Placement
import app.anynear.andp.SigningKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

/**
 * A space anynear publishes for everyone to read, e.g. the list of software for radios (ADR 0056 §1a): entries written
 * by the owner and listed with a grant for anyone (read, list). The core only fetches and checks that the bytes are
 * the objects; what they mean and whether they are signed is the caller's business.
 */
internal class PublishedSpace(
    private val space: Key,
    private val grant: Grant,
    private val net: NetworkDirectory,
    private val pool: RelayPool,
) {
    private val lock = Mutex()
    private var reader: SigningKey? = null

    /**
     * Bytes of every entry the owner wrote, merged over all holders of the space (one may lag behind after a new
     * publication). `null` if no holder answered.
     */
    suspend fun entries(): List<ByteArray>? = lock.withLock {
        net.await(LIST_WAIT) ?: return@withLock null
        val writer = reader ?: SigningKey.generate().also { reader = it }
        val found = LinkedHashMap<String, ByteArray>()
        var answered = false
        for (relay in net.holders(space.toString())) {
            val client = pool.client(relay) ?: continue
            try {
                var after = ""
                while (true) {
                    val page = client.list(Credentials(writer, grant), space, ListOptions(after, 0, PAGE))
                    for (o in page.objects) {
                        val id = o.id.toString()
                        if (id in found || o.placement != Placement.SPACE || o.writer != space || o.size > MAX_ENTRY_BYTES) continue
                        val bytes = o.inline() ?: client.get(Credentials(writer, grant), space, o.id).data
                        if (ID.of(bytes).toString() == id) found[id] = bytes
                    }
                    if (page.next.isEmpty() || page.next == after) break
                    after = page.next
                }
                answered = true
                pool.ok(relay.node)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AndpHttpException) {
                pool.ok(relay.node)
            } catch (e: Exception) {
                pool.failed(relay.node)
            }
        }
        if (answered) found.values.toList() else null
    }

    companion object {
        private const val PAGE = 200

        /** How long a read waits for the network list right after the start. */
        private val LIST_WAIT = 10.seconds

        /** An entry here is a small signed list; anything larger is not one. */
        const val MAX_ENTRY_BYTES = 1L shl 20
    }
}
