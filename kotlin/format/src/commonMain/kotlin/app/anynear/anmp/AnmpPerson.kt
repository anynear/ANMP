package app.anynear.anmp

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborArray
import kotlinx.serialization.cbor.CborLabel
import kotlin.js.JsExport

/**
 * Person certificate (ADR 0042): the root key confirms which person key currently signs the device list.
 * [n] counts the person keys of this root; a higher [n] replaces the person key (theft). [ts] in ms.
 */
@JsExport
@Serializable
class AnmpPersonCert(
    @CborLabel(0) val root: ByteArray,
    @CborLabel(1) val person: ByteArray,
    @CborLabel(2) val n: Long = 0,
    @CborLabel(3) val ts: Long = 0,
)

/** One device of a person: Ed25519 key (device ID derived from it), X25519 key, name, time added (ms), radio node. */
@JsExport
@Serializable
class AnmpDeviceEntry(
    @CborLabel(0) val signKey: ByteArray,
    @CborLabel(1) val key: ByteArray,
    @CborLabel(2) val name: String? = null,
    @CborLabel(3) val added: Long = 0,
    @CborLabel(4) val node: Long? = null,
)

/**
 * Device list of a person (ADR 0042), signed by the person key of certificate [n]. [seq] grows with every change,
 * [inbox] is the space of the one personal mailbox, [revoked] lists device IDs (16 bytes) that never come back.
 */
@JsExport
@Serializable
class AnmpDeviceList(
    @CborLabel(0) val root: ByteArray,
    @CborLabel(1) val n: Long = 0,
    @CborLabel(2) val seq: Long = 0,
    @CborLabel(3) val ts: Long = 0,
    @CborLabel(4) val inbox: ByteArray,
    @CborLabel(5) val devices: List<AnmpDeviceEntry>,
    @CborLabel(6) val revoked: List<ByteArray> = emptyList(),
)

/** State of a person's key chain named in a packet: certificate number [n] and list [seq]. */
@JsExport
@CborArray
@Serializable
class AnmpPersonRef(
    @CborLabel(0) val n: Long,
    @CborLabel(1) val seq: Long,
)

/**
 * A verified person: [root] key, [personId] (16 bytes), [cert], [list], the device IDs of [list] in the same order,
 * and [proof] (CBOR `{0 cert, 1 list}`, each `[body, sig]`) to pass on unchanged.
 */
@JsExport
class AnmpPerson internal constructor(
    val root: ByteArray,
    val personId: ByteArray,
    val cert: AnmpPersonCert,
    val list: AnmpDeviceList,
    val deviceIds: List<ByteArray>,
    val proof: ByteArray,
    /** Signed certificate and list as in [proof], to build a new list on the same certificate. */
    val certSigned: ByteArray,
    val listSigned: ByteArray,
) {
    val personIdText: String get() = b64u.encode(personId)

    val ref: AnmpPersonRef get() = AnmpPersonRef(cert.n, list.seq)

    /** Device entry of [deviceId], `null` if it is not (or no longer) in the list. */
    fun device(deviceId: ByteArray): AnmpDeviceEntry? = deviceIds.indexOfFirst { it.contentEquals(deviceId) }.takeIf { it >= 0 }?.let { list.devices[it] }

    fun isRevoked(deviceId: ByteArray): Boolean = list.revoked.any { it.contentEquals(deviceId) }
}

/**
 * State of a person for its own other devices (kind sync, ADR 0042): own name, circles with secret, contacts, channels
 * (ADR 0062) and what was left or removed. A full snapshot; the receiver takes what it does not have and drops what is
 * [gone].
 */
@JsExport
@Serializable
class AnmpSync(
    /** Time of the snapshot, ms. */
    @CborLabel(0) val ts: Long,
    @CborLabel(1) val name: String? = null,
    @CborLabel(2) val circles: List<AnmpSyncCircle> = emptyList(),
    @CborLabel(3) val contacts: List<AnmpSyncContact> = emptyList(),
    @CborLabel(4) val gone: List<AnmpSyncGone> = emptyList(),
    @CborLabel(5) val channels: List<AnmpSyncChannel> = emptyList(),
)

/** A circle as in the invitation link: ID, name, secret, key epoch with time (ms) and device. */
@JsExport
@Serializable
class AnmpSyncCircle(
    @CborLabel(0) val id: String,
    @CborLabel(1) val name: String,
    @CborLabel(2) val secret: ByteArray,
    @CborLabel(3) val epoch: Int = 0,
    @CborLabel(4) val epochTs: Long = 0,
    @CborLabel(5) val epochBy: ByteArray? = null,
)

/** A contact device: name, Ed25519 and X25519 key, mailbox space, key chain of its person if known. */
@JsExport
@Serializable
class AnmpSyncContact(
    @CborLabel(0) val name: String,
    @CborLabel(1) val signKey: ByteArray,
    @CborLabel(2) val key: ByteArray,
    @CborLabel(3) val inbox: ByteArray? = null,
    @CborLabel(4) val person: ByteArray? = null,
)

/**
 * A channel (ADR 0062): space, name, private read key, read grant (wire form), when it was subscribed or created (ms),
 * curated flag of its link. Own channels also carry the owner seed and the device addresses. [nameTs] and [keyTs] (ms)
 * date the last rename and the last read key (reset link); [revoked] lists the IDs of revoked device addresses.
 */
@JsExport
@Serializable
class AnmpSyncChannel(
    @CborLabel(0) val id: String,
    @CborLabel(1) val name: String,
    @CborLabel(2) val readKey: ByteArray,
    @CborLabel(3) val grant: String,
    @CborLabel(4) val joinedAt: Long = 0,
    @CborLabel(5) val curated: Boolean = false,
    @CborLabel(6) val ownerSeed: ByteArray? = null,
    @CborLabel(7) val nameTs: Long = 0,
    @CborLabel(8) val keyTs: Long = 0,
    @CborLabel(9) val ingress: List<AnmpSyncIngress> = emptyList(),
    @CborLabel(10) val revoked: List<String> = emptyList(),
    /** Own channels: subscribers that announced themselves (ADR 0062 §11). */
    @CborLabel(11) val subscribers: List<AnmpSyncSubscriber> = emptyList(),
    /** Own channels: removed or left subscribers (device ID b64u, ms), so that a snapshot does not bring them back. */
    @CborLabel(12) val unsubscribed: List<AnmpSyncGone> = emptyList(),
    /** Subscribed channels: the way to the owner from the link (ADR 0062 §11). */
    @CborLabel(13) val join: AnmpChannelJoin? = null,
)

/** A subscriber of an own channel: device ID, name, X25519 key, mailbox space and write grant for it, first and last announcement (ms). */
@JsExport
@Serializable
class AnmpSyncSubscriber(
    @CborLabel(0) val device: ByteArray,
    @CborLabel(1) val name: String = "",
    @CborLabel(2) val key: ByteArray,
    @CborLabel(3) val inbox: ByteArray,
    @CborLabel(4) val grant: ByteArray,
    @CborLabel(5) val since: Long = 0,
    @CborLabel(6) val ts: Long = 0,
)

/** A device address of an own channel: raw token, label, time created (ms), sender seed and grant, ID of its record entry. */
@JsExport
@Serializable
class AnmpSyncIngress(
    @CborLabel(0) val token: ByteArray,
    @CborLabel(1) val label: String = "",
    @CborLabel(2) val created: Long = 0,
    @CborLabel(3) val senderSeed: ByteArray,
    @CborLabel(4) val senderGrant: String,
    @CborLabel(5) val entryId: String = "",
)

/**
 * Private keys of the person for its own devices after a person key change: person key, mailbox owner, store secret and
 * the secret of the state space (ADR 0042 §2).
 */
@JsExport
@Serializable
class AnmpSyncKeys(
    @CborLabel(0) val person: ByteArray,
    @CborLabel(1) val inbox: ByteArray,
    @CborLabel(2) val store: ByteArray? = null,
    @CborLabel(3) val state: ByteArray? = null,
)

/** Data of the app for the own devices: [topic] chosen by the app, the bytes in sealed [parts] (like history parts), [ts] ms. */
@JsExport
@Serializable
class AnmpOwnData(
    @CborLabel(0) val topic: String,
    @CborLabel(1) val parts: List<AnmpHistoryPart>,
    @CborLabel(2) val ts: Long = 0,
)

/** A circle ID, person ID or channel ID that was left or removed at [ts] (ms). */
@JsExport
@Serializable
class AnmpSyncGone(
    @CborLabel(0) val id: String,
    @CborLabel(1) val ts: Long,
)

/** Root key → person key → device list (ADR 0042). Signatures are Ed25519; no own primitives. */
@JsExport
object AnmpPersons {
    /** Upper bound of devices in one list. */
    const val MAX_DEVICES = 16

    /** Upper bound of revoked device IDs in one list. */
    const val MAX_REVOKED = 256

    /** Person ID: first 16 bytes of SHA-256(`anmp/v1/person\n` || root public key). Stays across person key changes. */
    suspend fun personIdOf(root: ByteArray): ByteArray {
        require(root.size == Anmp.KEY_BYTES) { "root key must have 32 bytes" }
        return AnmpCrypto.sha256((AnmpInfo.PERSON + "\n").encodeToByteArray() + root).copyOf(Anmp.DEVICE_BYTES)
    }

    /** Certificate signed by the root key: CBOR `[body, sig]`, sig over `anmp/v1/person-cert\n` || body. */
    @JsExport.Ignore
    suspend fun certify(root: AnmpSigner, cert: AnmpPersonCert): ByteArray = sign(root, AnmpInfo.PERSON_CERT, Anmp.cbor.encodeToByteArray(AnmpPersonCert.serializer(), cert))

    /** Device list signed by the person key: CBOR `[body, sig]`, sig over `anmp/v1/devices\n` || body. */
    @JsExport.Ignore
    suspend fun signList(person: AnmpSigner, list: AnmpDeviceList): ByteArray = sign(person, AnmpInfo.DEVICES, Anmp.cbor.encodeToByteArray(AnmpDeviceList.serializer(), list))

    /** Proof to put into packets and links: CBOR `{0 cert, 1 list}`. */
    fun proof(cert: ByteArray, list: ByteArray): ByteArray = Anmp.cbor.encodeToByteArray(Proof.serializer(), Proof(cert, list))

    /** Checks certificate and list of [proof]; `null` if anything does not hold. Which proof is newest is up to the reader. */
    suspend fun verify(proof: ByteArray): AnmpPerson? {
        val p = runCatching { Anmp.cbor.decodeFromByteArray(Proof.serializer(), proof) }.getOrNull() ?: return null
        val cert = verifyCert(p.cert) ?: return null
        val list = verifyList(p.list, cert) ?: return null
        val ids = list.devices.map { AnmpKeys.deviceIdOf(it.signKey) }
        if (ids.distinctBy { it.toHex() }.size != ids.size) return null
        if (ids.any { id -> list.revoked.any { it.contentEquals(id) } }) return null
        return AnmpPerson(cert.root, personIdOf(cert.root), cert, list, ids, proof(p.cert, p.list), p.cert, p.list)
    }

    /** Checks a signed certificate on its own. */
    suspend fun verifyCert(signed: ByteArray): AnmpPersonCert? {
        val s = signed(signed) ?: return null
        val cert = runCatching { Anmp.cbor.decodeFromByteArray(AnmpPersonCert.serializer(), s.body) }.getOrNull() ?: return null
        if (cert.root.size != Anmp.KEY_BYTES || cert.person.size != Anmp.KEY_BYTES || cert.n < 0) return null
        return cert.takeIf { AnmpCrypto.edVerify(cert.root, signingBytes(AnmpInfo.PERSON_CERT, s.body), s.sig) }
    }

    /** Checks a signed list against the person key of [cert]. */
    suspend fun verifyList(signed: ByteArray, cert: AnmpPersonCert): AnmpDeviceList? {
        val s = signed(signed) ?: return null
        val list = runCatching { Anmp.cbor.decodeFromByteArray(AnmpDeviceList.serializer(), s.body) }.getOrNull() ?: return null
        if (!list.root.contentEquals(cert.root) || list.n != cert.n || list.seq < 0) return null
        if (list.inbox.size != Anmp.KEY_BYTES || list.devices.isEmpty() || list.devices.size > MAX_DEVICES || list.revoked.size > MAX_REVOKED) return null
        if (list.devices.any { it.signKey.size != Anmp.KEY_BYTES || it.key.size != Anmp.KEY_BYTES }) return null
        if (list.revoked.any { it.size != Anmp.DEVICE_BYTES }) return null
        return list.takeIf { AnmpCrypto.edVerify(cert.person, signingBytes(AnmpInfo.DEVICES, s.body), s.sig) }
    }

    /** `domain\n` || body. */
    fun signingBytes(domain: String, body: ByteArray): ByteArray = (domain + "\n").encodeToByteArray() + body

    private suspend fun sign(signer: AnmpSigner, domain: String, body: ByteArray): ByteArray {
        val sig = signer.sign(signingBytes(domain, body))
        require(sig.size == Anmp.SIGNATURE_BYTES) { "signature must have ${Anmp.SIGNATURE_BYTES} bytes" }
        return Anmp.cbor.encodeToByteArray(Signed.serializer(), Signed(body, sig))
    }

    private fun signed(bytes: ByteArray): Signed? =
        runCatching { Anmp.cbor.decodeFromByteArray(Signed.serializer(), bytes) }.getOrNull()?.takeIf { it.sig.size == Anmp.SIGNATURE_BYTES }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @CborArray
    @Serializable
    internal class Signed(
        @CborLabel(0) val body: ByteArray,
        @CborLabel(1) val sig: ByteArray,
    )

    @Serializable
    internal class Proof(
        @CborLabel(0) val cert: ByteArray,
        @CborLabel(1) val list: ByteArray,
    )
}
