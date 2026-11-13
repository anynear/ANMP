package app.anynear.anmp

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonTest {
    private suspend fun chain(
        n: Long = 0,
        personSeed: ByteArray = Fixtures.personSeed,
        devices: List<AnmpDevice>? = null,
        revoked: List<ByteArray> = emptyList(),
        certN: Long = n,
    ): ByteArray {
        val root = AnmpSpaceKey.fromPrivateKey(Fixtures.rootSeed)
        val person = AnmpSpaceKey.fromPrivateKey(personSeed)
        val inbox = AnmpSpaceKey.fromPrivateKey(Fixtures.inboxOwner)
        val ds = devices ?: listOf(Fixtures.deviceA(), Fixtures.deviceB())
        val cert = AnmpPersons.certify(root, AnmpPersonCert(root.publicKey, person.publicKey, certN, 1_760_000_000_000))
        val list = AnmpPersons.signList(
            person,
            AnmpDeviceList(root.publicKey, n, 1, 1_760_000_000_000, inbox.publicKey, ds.map { AnmpDeviceEntry(it.signKey, it.publicKey, "Handy") }, revoked),
        )
        return AnmpPersons.proof(cert, list)
    }

    @Test
    fun validChainVerifies() = runTest {
        val p = assertNotNull(AnmpPersons.verify(chain()))
        val a = Fixtures.deviceA()
        assertContentEquals(AnmpPersons.personIdOf(AnmpSpaceKey.fromPrivateKey(Fixtures.rootSeed).publicKey), p.personId)
        assertEquals(2, p.deviceIds.size)
        assertNotNull(p.device(a.deviceId))
        assertEquals(1, p.ref.seq)
    }

    @Test
    fun personIdStaysWithANewPersonKey() = runTest {
        val first = assertNotNull(AnmpPersons.verify(chain()))
        val second = assertNotNull(AnmpPersons.verify(chain(n = 1, personSeed = Fixtures.personSeed2)))
        assertContentEquals(first.personId, second.personId)
        assertTrue(second.cert.n > first.cert.n)
    }

    @Test
    fun listOfAnotherCertificateIsRejected() = runTest {
        assertNull(AnmpPersons.verify(chain(n = 0, certN = 1)))
    }

    @Test
    fun listSignedByAnotherKeyIsRejected() = runTest {
        val root = AnmpSpaceKey.fromPrivateKey(Fixtures.rootSeed)
        val person = AnmpSpaceKey.fromPrivateKey(Fixtures.personSeed)
        val thief = AnmpSpaceKey.fromPrivateKey(Fixtures.personSeed2)
        val a = Fixtures.deviceA()
        val cert = AnmpPersons.certify(root, AnmpPersonCert(root.publicKey, person.publicKey))
        val list = AnmpPersons.signList(thief, AnmpDeviceList(root.publicKey, inbox = root.publicKey, devices = listOf(AnmpDeviceEntry(a.signKey, a.publicKey))))
        assertNull(AnmpPersons.verify(AnmpPersons.proof(cert, list)))
    }

    @Test
    fun deviceListedAndRevokedIsRejected() = runTest {
        val a = Fixtures.deviceA()
        assertNull(AnmpPersons.verify(chain(devices = listOf(a), revoked = listOf(a.deviceId))))
        val b = Fixtures.deviceB()
        val p = assertNotNull(AnmpPersons.verify(chain(devices = listOf(a), revoked = listOf(b.deviceId))))
        assertTrue(p.isRevoked(b.deviceId))
        assertNull(p.device(b.deviceId))
    }

    @Test
    fun emptyListAndGarbageAreRejected() = runTest {
        assertNull(AnmpPersons.verify(chain(devices = emptyList())))
        assertNull(AnmpPersons.verify(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun contentCarriesPersonFields() = runTest {
        val proof = chain()
        val c = AnmpContent(person = proof, peer = ByteArray(16) { 7 }, personRef = AnmpPersonRef(0, 1))
        val back = assertNotNull(Anmp.decodeContent(Anmp.encodeContent(c)))
        assertContentEquals(proof, back.person)
        assertEquals(1, back.personRef?.seq)
        assertEquals(16, back.peer?.size)
    }
}
