package app.anynear.anmp.core

import app.anynear.andp.AndpClient
import app.anynear.andp.Relay
import kotlin.time.Clock

/*
 * Platform interfaces the app plugs in (ADR 0036: storage, key store and push stay in the app). They match
 * the app's own interfaces (`data/SecretStore.kt`, `data/KeyValueStore.kt`) so that adapters are one line.
 */

/**
 * Protected storage for secrets: device keys, owner key of the personal mailbox, circle secrets, grants.
 *
 * “Not there” and “not readable” are different: the core creates a missing device key, but must never replace
 * one it only could not read (iOS keychain before the first unlock after power-on, a busy Android Keystore).
 * That would give the device a new identity for good.
 */
interface SecretStore {
    /**
     * The stored value, or `null` only if there is none (or it can never be read again, e.g. its platform key is
     * gone). Throws if the store cannot read it right now; the core then stops what it is doing and creates nothing.
     */
    fun get(key: String): ByteArray?

    /** `null` removes the entry. */
    fun put(key: String, value: ByteArray?)
}

/** Small persistent key-value storage for non-secret state: cursors, pending writes, network list, seen IDs. */
interface KeyValueStore {
    fun getString(key: String): String?

    /** `null` removes the entry. */
    fun putString(key: String, value: String?)
}

/**
 * Files for encrypted objects the core still has to write (attachments, pictures, history parts) and for
 * pending entries. Holds only ciphertext. Names are plain ASCII (`[A-Za-z0-9_./-]`).
 */
interface BlobStore {
    suspend fun read(name: String): ByteArray?

    suspend fun write(name: String, bytes: ByteArray)

    suspend fun delete(name: String)
}

/** Wall clock in Unix milliseconds. */
fun interface CoreClock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM: CoreClock = CoreClock { Clock.System.now().toEpochMilliseconds() }
    }
}

/**
 * Opens a [AndpClient] for a relay of the network list. Apps on Android, iOS and JVM connect to the IP
 * address with the pinned TLS key, browsers to the DNS name with Web PKI (ANDP §12); see [platformConnector].
 * Tests plug in a mock engine. `null`: this relay cannot be reached from this platform (e.g. no host in JS).
 */
interface RelayConnector {
    fun connect(relay: Relay): AndpClient?

    /** Client for the starting point of the network list (e.g. `https://anynear.net`), Web PKI. */
    fun connectUrl(baseUrl: String): AndpClient
}

/** The connector of this platform: pinned on Android, iOS and JVM, Web PKI in JS. */
expect fun platformConnector(): RelayConnector

/** Debug log without message content, keys or secrets. */
fun interface CoreLog {
    fun log(line: String)

    companion object {
        val NONE: CoreLog = CoreLog { }
    }
}

/** Everything the core needs from the platform. */
class AnmpPlatform(
    val secrets: SecretStore,
    val store: KeyValueStore,
    val blobs: BlobStore,
    val clock: CoreClock = CoreClock.SYSTEM,
    val connector: RelayConnector = platformConnector(),
    val log: CoreLog = CoreLog.NONE,
)

/** In-memory [SecretStore], for tests and the web client before it has a storage. */
class MemorySecretStore : SecretStore {
    private val map = mutableMapOf<String, ByteArray>()

    override fun get(key: String): ByteArray? = map[key]?.copyOf()

    override fun put(key: String, value: ByteArray?) {
        if (value == null) map.remove(key) else map[key] = value.copyOf()
    }

    /** Names of the stored secrets, e.g. to copy them like a backup does. */
    fun keys(): Set<String> = map.keys.toSet()
}

/** In-memory [KeyValueStore]. */
class MemoryKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()

    override fun getString(key: String): String? = map[key]

    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

/** In-memory [BlobStore]. */
class MemoryBlobStore : BlobStore {
    private val map = mutableMapOf<String, ByteArray>()

    override suspend fun read(name: String): ByteArray? = map[name]?.copyOf()

    override suspend fun write(name: String, bytes: ByteArray) {
        map[name] = bytes.copyOf()
    }

    override suspend fun delete(name: String) {
        map.remove(name)
    }

    val names: Set<String> get() = map.keys.toSet()
}
