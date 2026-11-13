package app.anynear.anmp.core

import app.anynear.andp.B64
import app.anynear.andp.Credentials
import app.anynear.andp.Grant
import app.anynear.andp.Key
import app.anynear.andp.SigningKey
import app.anynear.anmp.Anmp
import app.anynear.anmp.AnmpCircleKeys
import app.anynear.anmp.AnmpDevice
import app.anynear.anmp.AnmpEnvelope
import app.anynear.anmp.AnmpInfo
import app.anynear.anmp.AnmpKeys
import app.anynear.anmp.AnmpMailbox
import app.anynear.anmp.AnmpSpaceKey

/**
 * Identity of this installation: device keys (ADR 0013, `format`), the ANDP writer key (the same Ed25519 key as
 * the device, so grants name the key from the contact card), the owner key of the personal mailbox (ADR 0035 §11),
 * which all devices of the person share, and the keys of the person (ADR 0042): [person] signs the device list,
 * [root] the person certificate. All belong into backup and move (ADR 0023, 0027): secret store keys [SIGN], [X],
 * [INBOX], [PERSON], [ROOT]. [root] stays on the device until the Kern-space holds it (ADR 0042 §2, not built yet).
 */
internal class CoreIdentity private constructor(
    val device: AnmpDevice,
    /** Same seed as [device]: signs ANDP requests and objects as grantee. */
    val writer: SigningKey,
    /** Owner of the personal mailbox. */
    val inboxOwner: SigningKey,
    val inboxEnvelope: AnmpEnvelope,
    /** Envelope of entries written before ADR 0042 (key from the device's X25519 key); read only. */
    val legacyInboxEnvelope: AnmpEnvelope,
    val person: AnmpSpaceKey,
    val root: AnmpSpaceKey?,
    /** Mailbox recipient from the mailbox owner seed (ADR 0084): every device of the person opens packets to it. */
    val mailbox: AnmpMailbox,
) {
    val deviceId: String = device.deviceIdText
    val tag: ByteArray get() = device.tag
    val publicKey: String = B64.encode(device.publicKey)
    val signKey: String = B64.encode(device.signKey)
    val inbox: Key get() = inboxOwner.key

    companion object {
        const val SIGN = "core.device.sign"
        const val X = "core.device.x"
        const val INBOX = "core.inbox.owner"
        const val PERSON = "core.person.key"
        const val ROOT = "core.person.root"

        /** Chain handed over to an added device before its first start (ADR 0042 §5). */
        const val PROOF = "core.person.proof"

        /**
         * Loads the keys and creates the missing ones. A key [SecretStore.get] cannot read throws through, so that
         * a start before the first unlock never writes new keys over the existing ones.
         */
        suspend fun loadOrCreate(secrets: SecretStore): CoreIdentity {
            fun keyOf(name: String): ByteArray = secrets.get(name)?.takeIf { it.size == 32 } ?: AnmpKeys.randomKey().also { secrets.put(name, it) }
            val sign = keyOf(SIGN)
            val x = keyOf(X)
            val inbox = keyOf(INBOX)
            // A device added later gets the person key but no root (ADR 0042 §5); only a first installation creates one.
            val hadPerson = secrets.get(PERSON)?.size == 32
            val person = keyOf(PERSON)
            val root = secrets.get(ROOT)?.takeIf { it.size == 32 } ?: if (hadPerson) null else keyOf(ROOT)
            val device = AnmpDevice.of(sign, x)
            val owner = SigningKey.fromSeed(inbox)
            return CoreIdentity(
                device, SigningKey.fromSeed(sign), owner, AnmpEnvelope.inbox(owner.key.bytes), AnmpEnvelope.inbox(device.publicKey),
                AnmpSpaceKey.fromPrivateKey(person), root?.let { AnmpSpaceKey.fromPrivateKey(it) }, AnmpMailbox.of(inbox),
            )
        }
    }
}

/** Keys of one circle secret: ANMP keys from `format` and the same space key as ANDP signer. */
internal class CircleKeys private constructor(val anmp: AnmpCircleKeys, val owner: SigningKey) {
    val space: Key get() = owner.key

    companion object {
        private val cache = mutableMapOf<String, CircleKeys>()

        suspend fun of(secret: String): CircleKeys {
            cache[secret]?.let { return it }
            val raw = B64.decode(secret)
            val anmp = AnmpCircleKeys.derive(raw)
            val owner = SigningKey.fromSeed(AnmpKeys.hkdf(raw, AnmpInfo.MAILBOX_SIGN))
            check(owner.key == Key(anmp.spaceKey.publicKey)) { "circle space key mismatch" }
            return CircleKeys(anmp, owner).also { if (cache.size < 64) cache[secret] = it }
        }
    }
}

/**
 * A space the core reads or writes. [signer] writes objects (owner key, or our writer key with [grant]);
 * [owner] is set when we hold the owner key (circle members, own mailbox), which allows re-signing objects
 * of others for the fallback of ADR 0028 §6b.
 */
internal class SpaceAccess(
    val space: Key,
    val signer: SigningKey,
    val grant: Grant?,
    /** Envelope of ANMP packets; `null` in spaces whose entries are no ANMP packets (channels, device addresses, ADR 0062). */
    val envelope: AnmpEnvelope?,
    val owner: SigningKey?,
) {
    val cred: Credentials get() = Credentials(signer, grant)
    val name: String get() = space.toString()

    /** Puts a ANMP packet into the envelope of this space. */
    suspend fun seal(packet: ByteArray): ByteArray = requireNotNull(envelope) { "space without envelope" }.seal(packet, name)
}

/** Where an entry was listed: a circle space (current or previous key), the own personal mailbox or a channel. */
internal sealed class Listed {
    abstract val access: SpaceAccess

    /** ANMP scope: circle ID in a circle space, `""` in the personal mailbox. */
    abstract val scope: String

    class Circle(val circleId: String, val keys: CircleKeys, val previous: Boolean, override val access: SpaceAccess) : Listed() {
        override val scope: String get() = circleId
    }

    class Inbox(override val access: SpaceAccess) : Listed() {
        override val scope: String get() = Anmp.DIRECT_SCOPE
    }

    /** A channel (ADR 0062): its entries are alert packets sealed to [readKey], without envelope. */
    class Channel(val channelId: String, val readKey: ByteArray, override val access: SpaceAccess) : Listed() {
        override val scope: String get() = Anmp.DIRECT_SCOPE
    }
}
