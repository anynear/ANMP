package app.anynear.anmp.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** “Not there” creates keys, “not readable” never does (SecretStore contract). */
class IdentityTest {
    private class Unreadable : RuntimeException("locked")

    /** Wraps [inner]; while [locked], every read throws like a keychain before the first unlock. */
    private class LockableSecrets(val inner: SecretStore) : SecretStore {
        var locked = false
        val writes = mutableListOf<String>()

        override fun get(key: String): ByteArray? = if (locked) throw Unreadable() else inner.get(key)

        override fun put(key: String, value: ByteArray?) {
            writes += key
            inner.put(key, value)
        }
    }

    private val identityKeys = listOf(CoreIdentity.SIGN, CoreIdentity.X, CoreIdentity.INBOX, CoreIdentity.PERSON, CoreIdentity.ROOT)

    @Test
    fun firstStartCreatesAllKeysAndLaterStartsKeepThem() = runTest {
        val secrets = MemorySecretStore()
        val first = CoreIdentity.loadOrCreate(secrets)
        val stored = identityKeys.associateWith { assertNotNull(secrets.get(it)) }
        val second = CoreIdentity.loadOrCreate(secrets)
        assertEquals(first.deviceId, second.deviceId)
        assertEquals(first.inbox, second.inbox)
        identityKeys.forEach { assertContentEquals(stored.getValue(it), secrets.get(it)) }
    }

    @Test
    fun unreadableStoreStopsTheStartAndWritesNothing() = runTest {
        val secrets = LockableSecrets(MemorySecretStore())
        val before = CoreIdentity.loadOrCreate(secrets)
        secrets.writes.clear()
        secrets.locked = true
        assertFailsWith<Unreadable> { CoreIdentity.loadOrCreate(secrets) }
        assertEquals(emptyList(), secrets.writes)
        secrets.locked = false
        assertEquals(before.deviceId, CoreIdentity.loadOrCreate(secrets).deviceId)
    }

    @Test
    fun coreOpenWithUnreadableStoreWritesNoKey() = runTest {
        val net = FakeNet.create()
        val secrets = LockableSecrets(MemorySecretStore())
        val first = AnmpCore(backgroundScope, net.config(), AnmpPlatform(secrets, MemoryKeyValueStore(), MemoryBlobStore(), connector = net.connector)).open()
        secrets.writes.clear()
        secrets.locked = true
        val locked = AnmpCore(backgroundScope, net.config(), AnmpPlatform(secrets, MemoryKeyValueStore(), MemoryBlobStore(), connector = net.connector))
        assertFailsWith<Unreadable> { locked.open() }
        assertEquals(emptyList(), secrets.writes)
        secrets.locked = false
        val again = AnmpCore(backgroundScope, net.config(), AnmpPlatform(secrets, MemoryKeyValueStore(), MemoryBlobStore(), connector = net.connector)).open()
        assertEquals(first.deviceId, again.deviceId)
    }

    @Test
    fun unreadableStoreOnFirstStartCreatesNothing() = runTest {
        val secrets = LockableSecrets(MemorySecretStore()).apply { locked = true }
        assertFailsWith<Unreadable> { CoreIdentity.loadOrCreate(secrets) }
        assertEquals(emptyList(), secrets.writes)
    }
}
