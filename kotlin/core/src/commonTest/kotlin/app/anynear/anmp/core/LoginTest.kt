package app.anynear.anmp.core

import app.anynear.andp.B64
import app.anynear.andp.Key
import app.anynear.andp.Sig
import app.anynear.anmp.AnmpDevice
import app.anynear.anmp.AnmpPersons
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Login with anynear (ADR 0122, ANIP §10a). */
class LoginTest {
    private val req = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, -6, -5, -4, -3, -2, -1)

    /** Same bytes as `PersonConfirmBytes` in ANIP (Go); the signature from Go (Ed25519 seed 1…32) verifies. */
    @Test
    fun confirmBytesMatchAnip() = runTest {
        val msg = AnmpLogin.confirmBytes(AnmpLogin.ISSUER, "feedback", req, 42, "Kim Mustermann ✨", 1795000000)
        assertEquals(
            "YW5pcC92MS9wZXJzb24tY29uZmlybQpodHRwczovL2lkLmFueW5lYXIuYXBwCmZlZWRiYWNrCkFRSURCQVVHQndnSkN2cjdfUDMtX3cKNDIKS2ltIE11c3Rlcm1hbm4g4pyoCjE3OTUwMDAwMDA",
            B64.encode(msg),
        )
        val device = AnmpDevice.of(ByteArray(32) { (it + 1).toByte() }, ByteArray(32) { 7 })
        assertEquals("ebVWLo_mVPlAeLES6KmLp5AfhTrmlb7X4OORC60ElmQ", B64.encode(device.signKey))
        // Signature made by Go for these bytes verifies here; our own one verifies too. Not compared byte for byte:
        // CryptoKit on iOS signs Ed25519 with a random part, which is just as valid for the issuer.
        val key = Key(device.signKey)
        assertTrue(key.verify(msg, Sig(B64.decode("jF3l1T_XBjm1_5n5m1JPsSmzPDpmrI6zbCTAlqQZSEH-cVU3AZX5U1QedpZY7OLlMLM55zE_TBKpqIoxbxwLBg"))))
        assertTrue(key.verify(msg, Sig(device.sign(msg))))
    }

    @Test
    fun linkRoundTripOnBothHosts() {
        val link = AnmpLogin.link("anynear.app", AnmpLogin.ISSUER, req)
        assertTrue(link.startsWith("https://anynear.app/l#"))
        val parsed = assertNotNull(AnmpLogin.parse("Öffne das: $link danke", "anynear.app"))
        assertEquals(AnmpLogin.ISSUER, parsed.issuer)
        assertContentEquals(req, B64.decode(parsed.req))
        assertNotNull(AnmpLogin.parse(link.replace("anynear.app", "anynear.net"), "anynear.net"))
        assertNull(AnmpLogin.parse(link.replace("/l#", "/a#"), "anynear.app"))
        assertNull(AnmpLogin.parse("https://anynear.app/l#AAAA", "anynear.app"))
        assertTrue(AnmpLogin.isLink("https://anynear.app/l#AAAA", "anynear.app"))
        assertFalse(AnmpLogin.isLink("https://anynear.app/legal", "anynear.app"))
    }

    @Test
    fun names() {
        assertEquals("Kim M", AnmpLogin.cleanName("  Kim    M "))
        assertNull(AnmpLogin.cleanName("   "))
        assertNull(AnmpLogin.cleanName("Kim\u0007"))
        assertEquals("✨".repeat(40), AnmpLogin.cleanName("✨".repeat(40)))
        assertEquals("😀".repeat(40), AnmpLogin.cleanName("😀".repeat(40)))
        assertNull(AnmpLogin.cleanName("😀".repeat(41)))
        assertEquals("😀".repeat(40), AnmpLogin.defaultName("😀".repeat(50)))
        assertEquals("Kim", AnmpLogin.defaultName(" Kim "))
    }

    /** A small stand-in for `id -person`: checks the chain, the device and the signature like ID does. */
    private class FakeIssuer(val n: Int = 42) {
        var confirmed: String? = null
        var lastStatus = 0

        suspend fun handle(scope: io.ktor.client.engine.mock.MockRequestHandleScope, r: HttpRequestData) = with(scope) {
            fun json(code: Int, body: String) = respond(body, HttpStatusCode.fromValue(code), headersOf(HttpHeaders.ContentType, "application/json")).also { lastStatus = code }
            when (r.url.encodedPath) {
                "/l/info" -> if (r.url.parameters["r"] == B64.encode(REQ)) {
                    json(200, """{"client":"feedback","name":"Ideen und Wünsche","domain":"feedback.anynear.app","choices":[17,42,80],"exp":9999999999,"more":1}""")
                } else {
                    json(400, """{"error":"abgelaufen"}""")
                }
                "/l/confirm" -> {
                    val o = Json.parseToJsonElement((r.body as TextContent).text).jsonObject
                    val s = { k: String -> o[k]!!.jsonPrimitive.content }
                    val proof = AnmpPersons.verify(B64.decode(s("proof")))
                    val key = B64.decode(s("key"))
                    val chosen = o["n"]!!.jsonPrimitive.int
                    val msg = AnmpLogin.confirmBytes(ISSUER, "feedback", B64.decode(s("r")), chosen, s("name"), o["ts"]!!.jsonPrimitive.long)
                    when {
                        proof == null || proof.list.devices.none { it.signKey.contentEquals(key) } -> json(403, """{"error":"Gerät"}""")
                        !Key(key).verify(msg, Sig(B64.decode(s("sig")))) -> json(403, """{"error":"sig"}""")
                        chosen != n -> json(200, """{"ok":false,"error":"Falsche Zahl"}""")
                        else -> json(200, """{"ok":true}""").also { confirmed = s("name") }
                    }
                }
                else -> json(404, "{}")
            }
        }

        companion object {
            const val ISSUER = "https://id.test"
            val REQ = ByteArray(40) { it.toByte() }
        }
    }

    private fun config(net: FakeNet, issuers: List<String>) = AnmpCoreConfig(
        net.config().networkId, net.config().networkKey, seedUrl = "https://seed.test", timings = TEST_TIMINGS, radioInboxSpace = "",
        radioInboxGrant = "", radioSoftwareSpace = "", radioSoftwareGrant = "", loginIssuers = issuers,
    )

    @Test
    fun confirmSignsWithTheDeviceKeyAndTheChain() = world {
        val issuer = FakeIssuer()
        val http = HttpClient(MockEngine { issuer.handle(this, it) })
        val a = device("Anna", config = config(net, listOf(FakeIssuer.ISSUER)), http = http)
        val text = AnmpLogin.link("anynear.app", FakeIssuer.ISSUER, FakeIssuer.REQ)
        val link = assertNotNull(a.core.parseLoginLink(text))
        val info = a.core.loginInfo(link)
        assertEquals(LoginResult.OK, info.status)
        assertEquals("Ideen und Wünsche", info.name)
        assertEquals("feedback.anynear.app", info.domain)
        assertContentEquals(intArrayOf(17, 42, 80), info.choices)

        assertEquals(LoginResult.BAD_NAME, a.core.confirmLogin(info, 42, " ").status)
        assertEquals(LoginResult.OK, a.core.confirmLogin(info, 42, "  Anna  B ").status)
        assertEquals("Anna B", issuer.confirmed)
        assertEquals(LoginResult.WRONG_NUMBER, a.core.confirmLogin(info, 17, "Anna").status)
    }

    @Test
    fun unknownIssuerAndExpiredRequest() = world {
        val issuer = FakeIssuer()
        val http = HttpClient(MockEngine { issuer.handle(this, it) })
        val a = device("Anna", http = http)
        // Default config: only id.anynear.app.
        assertNull(a.core.parseLoginLink(AnmpLogin.link("anynear.app", FakeIssuer.ISSUER, FakeIssuer.REQ)))
        assertTrue(a.core.isLoginLink(AnmpLogin.link("anynear.app", FakeIssuer.ISSUER, FakeIssuer.REQ)))
        assertNotNull(a.core.parseLoginLink(AnmpLogin.link("anynear.net", AnmpLogin.ISSUER, req)))
        assertEquals(LoginResult.INVALID, a.core.loginInfo(LoginLink(FakeIssuer.ISSUER, B64.encode(FakeIssuer.REQ))).status)

        val b = device("Ben", config = config(net, listOf(FakeIssuer.ISSUER)), http = http)
        assertEquals(LoginResult.EXPIRED, b.core.loginInfo(LoginLink(FakeIssuer.ISSUER, B64.encode(req))).status)
    }
}
