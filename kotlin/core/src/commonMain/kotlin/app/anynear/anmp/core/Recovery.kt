package app.anynear.anmp.core

import app.anynear.andp.ID
import app.anynear.andp.Key
import app.anynear.andp.ListOptions
import app.anynear.andp.AndpHttpException
import app.anynear.andp.SigningKey
import app.anynear.anmp.AnmpAead
import app.anynear.anmp.AnmpEnvelope
import app.anynear.anmp.AnmpKeys
import app.anynear.anmp.AnmpSync
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel
import kotlin.js.JsExport

/**
 * The recovery code of the emergency card (ADR 0023, 0042 §2): 128 random bits as 26 Crockford base32 characters
 * (5 bits each, the first character carries 3 bits) plus one check character (value mod 37, Crockford's check
 * alphabet), shown in groups of four. Reading accepts lower case, `O` for 0, `I`/`L` for 1, spaces and dashes.
 */
@JsExport
object RecoveryCode {
    const val BYTES = 16
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val CHECK = ALPHABET + "*~\$=U"

    fun newCode(): ByteArray = AnmpKeys.randomKey().copyOf(BYTES)

    /** `K7QM-2XRA-…`: 27 characters in groups of four. */
    fun format(code: ByteArray): String {
        require(code.size == BYTES) { "recovery code must have $BYTES bytes" }
        val chars = StringBuilder()
        // 128 bits as a number, most significant first: 26 characters, the first one only 3 bits.
        val bits = code.joinToString("") { (it.toInt() and 0xff).toString(2).padStart(8, '0') }.padStart(130, '0')
        for (i in 0 until 26) chars.append(ALPHABET[bits.substring(5 * i, 5 * i + 5).toInt(2)])
        chars.append(CHECK[mod37(code)])
        return chars.chunked(4).joinToString("-")
    }

    /** `null` if the text has the wrong length, unknown characters or a wrong check character. */
    fun parse(text: String): ByteArray? {
        val clean = text.uppercase().filterNot { it == '-' || it.isWhitespace() }
            .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }.joinToString("")
        if (clean.length != 27) return null
        val values = clean.take(26).map { ALPHABET.indexOf(it).takeIf { v -> v >= 0 } ?: return null }
        val bits = values.joinToString("") { it.toString(2).padStart(5, '0') }
        if (bits.take(2) != "00") return null
        val code = bits.drop(2).chunked(8).map { it.toInt(2).toByte() }.toByteArray()
        return code.takeIf { CHECK[mod37(it)] == clean.last() }
    }

    private fun mod37(code: ByteArray): Int = code.fold(0) { acc, b -> (acc * 256 + (b.toInt() and 0xff)) % 37 }
}

/** Entry of the Kern-space: the root key and the state secret, sealed with a key from the recovery code. */
@Serializable
internal class KernEntry(
    @CborLabel(0) val root: ByteArray,
    @CborLabel(1) val state: ByteArray,
    @CborLabel(2) val ts: Long,
)

/** Entry of the state space: what a recovered device needs besides the root key. */
@Serializable
internal class StateEntry(
    @CborLabel(0) val ts: Long,
    @CborLabel(1) val proof: ByteArray,
    @CborLabel(2) val person: ByteArray,
    @CborLabel(3) val inbox: ByteArray,
    @CborLabel(4) val store: ByteArray? = null,
    @CborLabel(5) val sync: AnmpSync? = null,
    @CborLabel(6) val extras: Map<String, ByteArray> = emptyMap(),
)

/**
 * A space without expiry that holds one sealed entry, the newest wins (Kern-space, state space). Owner key and entry key
 * come from one secret with the HKDF infos [signInfo] and [aeadInfo]; the entry key's info is also the associated data.
 */
internal class SealedSpace private constructor(
    private val owner: SigningKey,
    private val aead: AnmpAead,
    private val aad: ByteArray,
    private val net: NetworkDirectory,
    private val pool: RelayPool,
    private val transport: Transport,
    /** Not used: these spaces hold no ANMP packets ([SpaceAccess] needs one, as for the personal store). */
    private val envelope: AnmpEnvelope,
) {
    val space: Key get() = owner.key

    private val access get() = SpaceAccess(space, owner, null, envelope, owner)

    fun accessOf(): SpaceAccess = access

    /** Queues [plaintext] as the new entry, with [contents] (ID, sealed bytes) it refers to. */
    suspend fun put(plaintext: ByteArray, ref: String = "", contents: List<Pair<ID, ByteArray>> = emptyList()) {
        transport.submit(space, aead.seal(plaintext, aad), until = 0, contents = contents, ref = ref, known = access)
    }

    /**
     * Every opened entry with its object ID; [Result.answered] is `false` if no holder answered (then try later).
     */
    suspend fun read(): Result {
        val found = mutableMapOf<String, ByteArray>()
        var answered = false
        for (relay in net.readOrder(space.toString()).take(4)) {
            val client = pool.client(relay) ?: continue
            try {
                var after = ""
                while (true) {
                    val page = client.list(access.cred, space, ListOptions(after, 0, 200))
                    for (o in page.objects) {
                        val id = o.id.toString()
                        if (id in found || o.revokes != null) continue
                        val bytes = o.inline() ?: client.get(access.cred, space, o.id).data
                        aead.open(bytes, aad)?.let { found[id] = it }
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
                if (e.isNotFound) answered = true
            } catch (_: Exception) {
            }
        }
        return Result(answered, found)
    }

    /** Deletes entries [ids] (older ones after a newer one is stored, or the whole space). */
    suspend fun delete(ids: Collection<String>) = ids.forEach { transport.delete(access.cred, space, ID.parse(it), entry = true) }

    class Result(val answered: Boolean, val entries: Map<String, ByteArray>)

    companion object {
        suspend fun of(secret: ByteArray, signInfo: String, aeadInfo: String, net: NetworkDirectory, pool: RelayPool, transport: Transport, envelope: AnmpEnvelope) =
            SealedSpace(
                SigningKey.fromSeed(AnmpKeys.hkdf(secret, signInfo)), AnmpAead.of(AnmpKeys.hkdf(secret, aeadInfo)), aeadInfo.encodeToByteArray(),
                net, pool, transport, envelope,
            )
    }
}
