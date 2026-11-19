package app.anynear.anmp.core

import app.anynear.andp.B64
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel
import kotlin.js.JsExport

/**
 * Decoded login link (`https://anynear.app/l#…`, ADR 0122, ANIP §10a): a service asks this person to sign in through
 * the personal issuer. [req] is the sealed request (b64u), opaque to the app; only the issuer can open it.
 */
@JsExport
class LoginLink(val issuer: String, val req: String)

/**
 * Answer of `GET {issuer}/l/info` (ANIP §10a.3): which service asks and the three numbers the person chooses from.
 * [status] is [LoginResult.OK] when the fields are filled, otherwise one of the other [LoginResult] codes.
 */
@JsExport
class LoginInfo(
    val status: String,
    val issuer: String = "",
    val req: String = "",
    /** Client ID of the service, part of the signed confirmation. */
    val client: String = "",
    /** Name of the service as in the issuer's signed client list, e.g. „Ideen und Wünsche“. */
    val name: String = "",
    /** Domain of the service, always shown next to the name (phishing, ADR 0122 risks). */
    val domain: String = "",
    val choices: IntArray = IntArray(0),
    /** End of the request, Unix seconds. */
    val exp: Long = 0,
)

/** Outcome of [AnmpCore.confirmLogin] or [AnmpCore.loginInfo]; [status] is one of the constants. */
@JsExport
class LoginResult(val status: String) {
    companion object {
        /** Signed in: the page on the computer or in the browser continues by itself. */
        const val OK = "ok"
        /** The number did not match the page; the request is used up. */
        const val WRONG_NUMBER = "wrong-number"
        /** The request ran out (5 minutes) or is not readable: start again on the page. */
        const val EXPIRED = "expired"
        /** The issuer did not accept this device or the signature (often a wrong clock on the phone). */
        const val REJECTED = "rejected"
        /** Too many tries from this address; wait a minute. */
        const val TOO_MANY = "too-many"
        /** Issuer not reachable. */
        const val OFFLINE = "offline"
        /** This installation has no key chain to the root key (runs as its own person) and cannot sign in. */
        const val NO_CHAIN = "no-chain"
        /** Name empty, too long or with control characters. */
        const val BAD_NAME = "bad-name"
        /** Not a login link of a known issuer, or a broken one. */
        const val INVALID = "invalid"
    }
}

/**
 * Login with anynear (ADR 0122, ANIP §10a): link format, the bytes the device signs and the name rules. The confirmation
 * is signed with the device key, never with the person key: the person key signs nothing but device lists (ANMP
 * "Persons and devices"). The issuer checks that the device is in the list of the attached proof.
 */
@JsExport
object AnmpLogin {
    /** The personal issuer of anynear; the only one apps accept (test builds may add their own, [AnmpCoreConfig.loginIssuers]). */
    const val ISSUER = "https://id.anynear.app"

    /** Signing domain of the confirmation (ANIP §10). */
    const val LABEL = "anip/v1/person-confirm"

    /** Length of the name shown at the service, in Unicode code points (ANIP §10a.3). */
    const val MAX_NAME = 40

    /** Longest sealed request a link may carry; real ones are around 200 bytes. */
    internal const val MAX_REQ = 1024

    @Serializable
    internal class LoginWire(
        @CborLabel(0) val issuer: String,
        @CborLabel(1) val req: ByteArray,
    )

    /** Builds a login link (the issuer does this; here for tests and tools). */
    fun link(host: String, issuer: String, req: ByteArray): String =
        "https://$host/l#" + B64.encode(coreCbor.encodeToByteArray(LoginWire.serializer(), LoginWire(issuer, req)))

    /** Reads a login link, also inside text. Does not check the issuer: [AnmpCore.parseLoginLink] does. */
    fun parse(text: String, host: String): LoginLink? {
        val m = Regex(Regex.escape(host) + "/l/?#([A-Za-z0-9_-]+)").find(text) ?: return null
        val bytes = runCatching { B64.decode(m.groupValues[1]) }.getOrNull() ?: return null
        val w = runCatching { coreCbor.decodeFromByteArray(LoginWire.serializer(), bytes) }.getOrNull() ?: return null
        if (w.req.isEmpty() || w.req.size > MAX_REQ || !w.issuer.startsWith("https://") && !w.issuer.startsWith("http://")) return null
        return LoginLink(w.issuer.trimEnd('/'), B64.encode(w.req))
    }

    /** Whether [text] holds a login link on [host] (broken or not). */
    fun isLink(text: String, host: String): Boolean = Regex(Regex.escape(host) + "/l/?#").containsMatchIn(text)

    /**
     * What the device signs: `anip/v1/person-confirm\n` issuer `\n` client `\n` b64u(req) `\n` n `\n` name `\n` ts.
     * Same bytes as `PersonConfirmBytes` in ANIP (Go).
     */
    fun confirmBytes(issuer: String, client: String, req: ByteArray, n: Int, name: String, ts: Long): ByteArray =
        listOf(LABEL, issuer, client, B64.encode(req), n.toString(), name, ts.toString()).joinToString("\n").encodeToByteArray()

    /**
     * The name as the issuer stores it: no control characters, runs of white space become one space, trimmed, 1 to
     * [MAX_NAME] code points. `null` if it does not fit. Same rule as `CleanName` in ID.
     */
    fun cleanName(name: String): String? {
        if (name.any { it.isISOControl() }) return null
        val s = collapse(name)
        val n = codePoints(s)
        return s.takeIf { n in 1..MAX_NAME }
    }

    /** The profile name shortened to fit as the default of „Dein Name dort“. */
    fun defaultName(profileName: String): String {
        val s = collapse(profileName).filterNot { it.isISOControl() }
        if (codePoints(s) <= MAX_NAME) return s
        val out = StringBuilder()
        var n = 0
        var i = 0
        while (i < s.length && n < MAX_NAME) {
            val step = if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
            out.append(s, i, i + step)
            i += step
            n++
        }
        return out.toString().trimEnd()
    }

    /** Runs of white space (also no-break spaces, like Go's `strings.Fields`) become one space; trimmed. */
    private fun collapse(s: String): String =
        CharArray(s.length) { if (s[it].isWhitespace()) ' ' else s[it] }.concatToString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    private fun codePoints(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            i += if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
            n++
        }
        return n
    }
}

@Serializable
internal class LoginInfoWire(
    val client: String = "",
    val name: String = "",
    val domain: String = "",
    val choices: List<Int> = emptyList(),
    val exp: Long = 0,
)

@Serializable
internal class LoginConfirmWire(
    val r: String,
    val n: Int,
    val name: String,
    val ts: Long,
    val proof: String,
    val key: String,
    val sig: String,
)

@Serializable
internal class LoginAnswerWire(val ok: Boolean = false, val error: String? = null)
