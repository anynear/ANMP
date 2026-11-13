package app.anynear.anmp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * vectors/anmp-v1.json: computed here from fixed inputs and compared with the file. `UPDATE_VECTORS=1` on the JVM
 * rewrites the file instead. Bytes are lower-case hex, texts as they are.
 */
class VectorsTest {
    private val json = Json { prettyPrint = true }

    @Test
    fun vectorsMatchFile() = runTest {
        val computed = compute()
        val text = json.encodeToString(JsonObject.serializer(), computed) + "\n"
        if (writeVectorsIfRequested(text)) return@runTest
        if (EMBEDDED_VECTORS.isBlank()) fail("vectors/anmp-v1.json missing: run UPDATE_VECTORS=1 ./gradlew :format:jvmTest")
        val stored = json.parseToJsonElement(EMBEDDED_VECTORS) as JsonObject
        if (signaturesAreDeterministic) {
            assertEquals(stored, computed, "vectors/anmp-v1.json differs from the implementation")
        } else {
            // Signatures (and everything that contains them) differ here; storedPacketsOpen verifies them instead.
            assertEquals(withoutSigned(stored), withoutSigned(computed), "vectors/anmp-v1.json differs from the implementation")
        }
    }

    /** Drops the values that contain an Ed25519 signature. */
    private fun withoutSigned(v: JsonObject): JsonObject {
        val signed = mapOf(
            "packets" to mapOf("circleSigned" to setOf("signature", "opened", "packet"), "directInternet" to setOf("opened", "packet")),
            "envelope" to mapOf("circle" to setOf("sealed"), "inbox" to setOf("sealed")),
            "person" to mapOf("chain" to setOf("cert", "list", "proof")),
            "radio" to mapOf("circlePacket" to setOf("signature", "packet", "opened")),
            "alert" to mapOf("sender" to setOf("signed", "packet"), "owner" to setOf("signed", "packet"), "channelKey" to setOf("sig")),
            "stats" to mapOf("report" to setOf("signature", "report"), "switchOff" to setOf("report")),
            "mailbox" to mapOf("direct" to setOf("packet")),
        )
        return JsonObject(v.mapValues { (section, value) ->
            val drop = signed[section] ?: return@mapValues value
            JsonObject((value as JsonObject).mapValues { (name, entry) ->
                val keys = drop[name] ?: return@mapValues entry
                JsonObject((entry as JsonObject).filterKeys { it !in keys })
            })
        })
    }

    @Test
    fun storedPacketsOpen() = runTest {
        if (EMBEDDED_VECTORS.isBlank()) return@runTest
        val v = json.parseToJsonElement(EMBEDDED_VECTORS) as JsonObject
        val packets = v["packets"] as JsonObject
        val circle = packets["circleSigned"] as JsonObject
        val keys = AnmpCircleKeys.derive(Fixtures.circleSecret)
        val a = Fixtures.deviceA()
        val msg = Anmp.decode(circle.hex("packet"), Fixtures.CIRCLE_ID, keys.cipher, AnmpVerifier.withKey(a.signKey), true)
        assertEquals("Hallo", msg?.content?.text)
        val direct = packets["directInternet"] as JsonObject
        val b = Fixtures.deviceB()
        val dm = Anmp.decode(direct.hex("packet"), "", b.directCipher(a.deviceId, a.publicKey), AnmpVerifier.fromContent, true)
        assertTrue(dm?.signed == true)
        (v["mailbox"] as? JsonObject)?.let { mb ->
            val mailbox = AnmpMailbox.of(Fixtures.inboxOwner)
            val m = Anmp.decode((mb["direct"] as JsonObject).hex("packet"), "", mailbox.directCipher(a.deviceId, a.publicKey), AnmpVerifier.fromContent, true)
            assertEquals("Hallo", m?.content?.text)
        }
        (v["radio"] as? JsonObject)?.let { radio ->
            val cp = radio["circlePacket"] as JsonObject
            val rm = Anmp.decode(cp.hex("packet"), Fixtures.CIRCLE_ID, keys.cipher, AnmpVerifier.withKey(a.signKey), true)
            assertEquals("Hallo Funk", rm?.content?.text)
            val pickup = radio["pickup"] as JsonObject
            val opened = AnmpRadio.openPickup(pickup.hex("packet"), AnmpEphemeral.of(Fixtures.patronEph))
            assertEquals(2, opened?.windows?.size)
        }
        val chain = (v["person"] as? JsonObject)?.get("chain") as? JsonObject
        if (chain != null) {
            val p = AnmpPersons.verify(chain.hex("proof"))
            assertEquals(2, p?.deviceIds?.size)
        }
        (v["alert"] as? JsonObject)?.let { alert ->
            for (name in listOf("sender", "owner")) {
                val opened = AnmpAlerts.open((alert[name] as JsonObject).hex("packet"), Fixtures.readKey)
                assertEquals((alert[name] as JsonObject).hex("bodyBytes").toHex(), opened?.bodyBytes?.toHex(), name)
            }
            (alert["channelKey"] as? JsonObject)?.let { k ->
                val bytes = AnmpAlerts.channelKeySigningBytes(alert["space"].toString().trim('"'), Fixtures.readKey, alert["readGrant"].toString().trim('"'))
                assertEquals(k.hex("signingBytes").toHex(), bytes.toHex())
                assertTrue(AnmpCrypto.edVerify(AnmpSpaceKey.fromPrivateKey(Fixtures.channelOwner).publicKey, bytes, k.hex("sig")))
            }
            (alert["joinLink"] as? JsonObject)?.let { j ->
                val parsed = assertNotNull(AnmpAlerts.parseChannelLink(j["link"].toString().trim('"'), "anynear.net"))
                val jn = assertNotNull(parsed.join)
                assertEquals(ByteArray(16) { 0x11 }.toHex(), jn.device.toHex())
                assertEquals(ByteArray(32) { 0x44 }.toHex(), jn.inbox.toHex())
            }
            (alert["alertKey"] as? JsonObject)?.let { k ->
                assertEquals(k.hex("hash").toHex(), AnmpAlerts.alertKey(k["key"].toString().trim('"')).toHex())
                assertEquals(k.hex("unicodeHash").toHex(), AnmpAlerts.alertKey(k["unicodeKey"].toString().trim('"')).toHex())
            }
        }
        // Absent only in an older file while UPDATE_VECTORS rewrites it.
        val env = (v["envelope"] as? JsonObject)?.get("circle") as? JsonObject ?: return@runTest
        assertEquals(circle.hex("packet").toHex(), keys.envelope.open(env.hex("sealed"), keys.space)?.toHex())
    }

    private fun JsonObject.hex(key: String): ByteArray = (this[key].toString().trim('"')).hex()

    private fun JsonObjectBuilder.hex(key: String, value: ByteArray) = put(key, value.toHex())

    private suspend fun compute(): JsonObject {
        val circle = AnmpCircleKeys.derive(Fixtures.circleSecret)
        val a = Fixtures.deviceA()
        val b = Fixtures.deviceB()
        val inbox = AnmpSpaceKey.fromPrivateKey(Fixtures.inboxOwner)
        val scope = Fixtures.CIRCLE_ID
        val ts = 1_760_000_000L

        // Circle message, signed, fixed nonce.
        val circleHeader = AnmpHeader(AnmpKind.CIRCLE, a.tag, epoch = 2)
        val circleContent = AnmpContent(id = Fixtures.messageId, ts = ts, text = "Hallo")
        val h = Anmp.encodeHeader(circleHeader)
        val c = Anmp.encodeContent(circleContent)
        val signing = Anmp.signingBytes(scope, h, c)
        val circlePacket = Anmp.encodeFull(circleHeader, circleContent, scope, AnmpAead.withFixedNonce(circle.aeadKey, Fixtures.nonce(1)), a)

        // Direct message over radio, unsigned.
        val pairKey = a.directKey(b.deviceId, b.publicKey)
        val radioHeader = AnmpHeader(AnmpKind.DIRECT, a.tag, b.tag)
        val radioContent = AnmpContent(id = Fixtures.messageId, ts = ts, text = "Nur für dich")
        val radioPacket = Anmp.encode(radioHeader, radioContent, Anmp.DIRECT_SCOPE, AnmpAead.withFixedNonce(pairKey, Fixtures.nonce(2)))

        // Direct message over the internet: sender keys in the header, signed, full sender in the content.
        val netHeader = AnmpHeader(AnmpKind.DIRECT, a.tag, b.tag, senderKey = a.publicKey, senderDevice = a.deviceId)
        val netContent = AnmpContent(id = Fixtures.messageId, ts = ts, text = "Hallo", name = "Anna", key = a.publicKey, signKey = a.signKey)
        val netPacket = Anmp.encodeFull(netHeader, netContent, Anmp.DIRECT_SCOPE, AnmpAead.withFixedNonce(pairKey, Fixtures.nonce(3)), a)

        // Direct message to the mailbox recipient (ADR 0084), as for a new contact from the contact link.
        val mailbox = AnmpMailbox.of(Fixtures.inboxOwner)
        val mailboxKey = a.directKey(mailbox.id, mailbox.publicKey)
        val mailboxHeader = AnmpHeader(AnmpKind.DIRECT, a.tag, mailbox.tag, senderKey = a.publicKey, senderDevice = a.deviceId)
        val mailboxPacket = Anmp.encodeFull(mailboxHeader, netContent, Anmp.DIRECT_SCOPE, AnmpAead.withFixedNonce(mailboxKey, Fixtures.nonce(10)), a)

        val blobPlain = "anynear blob".encodeToByteArray()
        val blob = AnmpBlob.sealWith(AnmpAead.withFixedNonce(Fixtures.blobKey, Fixtures.nonce(4)), blobPlain)

        val spaceSeed = AnmpKeys.hkdf(Fixtures.circleSecret, AnmpInfo.MAILBOX_SIGN)
        val circleEnvKey = AnmpKeys.hkdf(Fixtures.circleSecret, AnmpInfo.ENV)
        val inboxEnvKey = AnmpKeys.hkdf(a.publicKey, AnmpInfo.INBOX)
        val circleEnv = AnmpEnvelope.withFixedNonce(circleEnvKey, Fixtures.nonce(5)).seal(circlePacket.packet, circle.space)
        val inboxEnv = AnmpEnvelope.withFixedNonce(inboxEnvKey, Fixtures.nonce(6)).seal(netPacket.packet, inbox.space)
        val signature = a.sign(signing)
        val root = AnmpSpaceKey.fromPrivateKey(Fixtures.rootSeed)
        val person = AnmpSpaceKey.fromPrivateKey(Fixtures.personSeed)
        val personCert = AnmpPersonCert(root.publicKey, person.publicKey, 0, ts * 1000)
        val deviceList = AnmpDeviceList(
            root.publicKey, 0, 1, ts * 1000, inbox.publicKey,
            listOf(AnmpDeviceEntry(a.signKey, a.publicKey, "Handy", ts * 1000), AnmpDeviceEntry(b.signKey, b.publicKey, "Tablet", ts * 1000)),
        )
        val personId = AnmpPersons.personIdOf(root.publicKey)
        val certSigned = AnmpPersons.certify(root, personCert)
        val listSigned = AnmpPersons.signList(person, deviceList)
        val deviceA = device(a)
        val deviceB = device(b)
        val radio = Radio.of(a, ts)
        val patronKey = AnmpEphemeral.of(Fixtures.patronEph).publicKey
        val radioMore = radioVectors(radio, a, b, circle, pairKey, ts)
        val alert = alertVectors(ts, blob)
        val stats = statsVectors(ts)

        return buildJsonObject {
            put("protocol", "ANMP version v1")
            put("comment", "Invented inputs. Bytes are hex. HKDF-SHA256 with salt \"anmp\", 32 bytes. Generated by VectorsTest (UPDATE_VECTORS=1).")
            putJsonObject("inputs") {
                hex("circleSecret", Fixtures.circleSecret)
                put("circleId", Fixtures.CIRCLE_ID)
                hex("deviceASignPrivate", Fixtures.signA)
                hex("deviceAX25519Private", Fixtures.xA)
                hex("deviceBSignPrivate", Fixtures.signB)
                hex("deviceBX25519Private", Fixtures.xB)
                hex("inboxOwnerPrivate", Fixtures.inboxOwner)
                hex("rootPrivate", Fixtures.rootSeed)
                hex("personPrivate", Fixtures.personSeed)
                put("nonces", "f0f1f2f3f4f5f6f7f8f9fa + one byte: 01 circleSigned, 02 directRadio, 03 directInternet, 04 blob, 05 circle envelope, 06 inbox envelope, 07 alert.sender, 08 alert.owner, 09 alert.ingress")
                hex("blobKey", Fixtures.blobKey)
            }
            putJsonObject("circle") {
                hex("aeadKey", circle.aeadKey)
                hex("spaceSeed", spaceSeed)
                hex("spacePublicKey", circle.spaceKey.publicKey)
                put("space", circle.space)
            }
            put("deviceA", deviceA)
            put("deviceB", deviceB)
            putJsonObject("direct") {
                put("info", AnmpInfo.DIRECT + "|" + listOf(a.deviceIdText, b.deviceIdText).sorted().joinToString("|"))
                hex("key", pairKey)
            }
            putJsonObject("inbox") {
                hex("ownerPublicKey", inbox.publicKey)
                put("space", inbox.space)
            }
            putJsonObject("mailbox") {
                put("comment", "ADR 0084: mailbox recipient from the owner seed of inbox; packet = packets.directInternet content from deviceA, nonce 0a")
                put("xInfo", AnmpInfo.MAILBOX_X)
                hex("x25519PublicKey", mailbox.publicKey)
                hex("id", mailbox.id)
                hex("tag", mailbox.tag)
                hex("pairKey", mailboxKey)
                putJsonObject("direct") {
                    hex("header", Anmp.encodeHeader(mailboxHeader))
                    hex("packet", mailboxPacket.packet)
                }
            }
            putJsonObject("cbor") {
                hex("headerCircle", h)
                hex("headerDirect", Anmp.encodeHeader(radioHeader))
                hex("content", c)
            }
            putJsonObject("packets") {
                putJsonObject("circleSigned") {
                    put("scope", scope)
                    hex("aad", Anmp.aad(scope, h))
                    hex("signingBytes", signing)
                    hex("signature", signature)
                    hex("packet", circlePacket.packet)
                    hex("opened", circlePacket.opened)
                }
                putJsonObject("directRadio") {
                    put("scope", Anmp.DIRECT_SCOPE)
                    hex("packet", radioPacket)
                }
                putJsonObject("directInternet") {
                    put("scope", Anmp.DIRECT_SCOPE)
                    hex("header", Anmp.encodeHeader(netHeader))
                    hex("content", Anmp.encodeContent(netContent))
                    hex("packet", netPacket.packet)
                    hex("opened", netPacket.opened)
                }
            }
            putJsonObject("envelope") {
                putJsonObject("circle") {
                    hex("key", circleEnvKey)
                    put("space", circle.space)
                    hex("aad", AnmpEnvelope.aad(circle.space))
                    put("packet", "packets.circleSigned.packet")
                    hex("sealed", circleEnv)
                }
                putJsonObject("inbox") {
                    put("keyFrom", "deviceA.x25519PublicKey")
                    hex("key", inboxEnvKey)
                    put("space", inbox.space)
                    hex("aad", AnmpEnvelope.aad(inbox.space))
                    put("packet", "packets.directInternet.packet")
                    hex("sealed", inboxEnv)
                }
            }
            putJsonObject("person") {
                hex("rootPublicKey", root.publicKey)
                hex("personPublicKey", person.publicKey)
                hex("personId", personId)
                putJsonObject("chain") {
                    put("comment", "deviceA (Handy) and deviceB (Tablet), mailbox = inbox.ownerPublicKey, n 0, seq 1")
                    hex("certBody", Anmp.cbor.encodeToByteArray(AnmpPersonCert.serializer(), personCert))
                    hex("listBody", Anmp.cbor.encodeToByteArray(AnmpDeviceList.serializer(), deviceList))
                    hex("cert", certSigned)
                    hex("list", listSigned)
                    hex("proof", AnmpPersons.proof(certSigned, listSigned))
                }
            }
            putJsonObject("blob") {
                put("aad", AnmpInfo.ATT)
                hex("plaintext", blobPlain)
                hex("sealed", blob.bytes)
                hex("id", blob.id)
                put("idText", blob.idText)
            }
            putJsonObject("radioBudget") {
                put("circleSigned", Anmp.maxTextBytes(circleHeader.copyEpoch(3), true) { AnmpContent(id = Fixtures.messageId, ts = ts, text = it) })
                put("circleUnsigned", Anmp.maxTextBytes(circleHeader.copyEpoch(3), false) { AnmpContent(id = Fixtures.messageId, ts = ts, text = it) })
                put("direct", Anmp.maxTextBytes(radioHeader, false) { AnmpContent(id = Fixtures.messageId, ts = ts, text = it) })
            }
            putJsonObject("radio") {
                put("comment", "ADR 0051: one channel anynear, hourly tags. ts = radioBudget time, device = deviceA, circle = circle.secret, patron key = pickup.patronPublic")
                put("channelName", AnmpInfo.RADIO_CHANNEL_NAME)
                hex("channelPsk", radio.channelPsk)
                put("hour", radio.hour)
                hex("deviceRadioSecret", radio.deviceSecret)
                hex("deviceRouteTag", radio.deviceRoute)
                hex("deviceSenderTag", radio.sender)
                hex("circleRadioSecret", radio.circleSecret)
                hex("circleRouteTag", radio.circleRoute)
                hex("publicCircleHeader", Anmp.encodeHeader(radio.circleHeader))
                hex("ask", Anmp.encodeOpen(AnmpHeader(AnmpKind.ASK, radio.sender, since = ts, askTags = arrayOf(AnmpRadio.askTag(radio.circleRoute)))))
                hex("patron", Anmp.encodeOpen(AnmpHeader(AnmpKind.PATRON, radio.sender, senderKey = patronKey, waiting = 2)))
                putJsonObject("chains") {
                    put("comment", "ADR 0051 §5a: device radio secret, epoch of hour, epoch 4096 hours, window 720 hours")
                    put("epoch", AnmpRadio.epochOf(radio.hour))
                    hex("forwardStart", radio.forwardStart)
                    hex("backwardEnd", radio.backwardEnd)
                    put("windowFrom", AnmpRadio.windowRange(radio.window.epoch, radio.hour).first)
                    put("windowTo", AnmpRadio.windowRange(radio.window.epoch, radio.hour).last)
                    hex("windowF", radio.window.f)
                    hex("windowB", radio.window.b)
                }
                putJsonObject("budget") {
                    put("circleSigned", Anmp.maxTextBytes(radio.circleHeader, true) { AnmpContent(id = Fixtures.messageId, ts = ts, text = it) })
                    put("circleUnsigned", Anmp.maxTextBytes(radio.circleHeader, false) { AnmpContent(id = Fixtures.messageId, ts = ts, text = it) })
                    put("direct", Anmp.maxTextBytes(radio.directHeader, false) { AnmpContent(id = Fixtures.messageId, ts = ts, text = it) })
                }
                for ((k, value) in radioMore) put(k, value)
            }
            put("alert", alert)
            put("stats", stats)
        }
    }

    /** ADR 0045: daily stats key and person marker, a signed report by deviceA, relay choice and day borders. */
    private suspend fun statsVectors(ts: Long): JsonObject {
        val day = AnmpStats.dayOf(ts)
        val seed = AnmpStats.keySeed(Fixtures.signA, day)
        val key = AnmpSpaceKey.fromPrivateKey(seed)
        val marker = AnmpStats.marker(Fixtures.personSeed, day)
        val body = AnmpStats.body(Fixtures.signA, Fixtures.personSeed, ts, "android", "0.4.2", 1, 2, listOf("nina"), 2, 3)
        val bodyBytes = Anmp.cbor.encodeToByteArray(AnmpStatsBody.serializer(), body)
        val signing = AnmpStats.signingBytes(bodyBytes)
        val signature = key.sign(signing)
        val report = AnmpStats.sign(body, Fixtures.signA)
        check(AnmpStats.verify(report) != null) { "stats report does not verify" }
        val switchOff = AnmpStats.signSwitch(Fixtures.signA, ts, "android", on = false)
        check(AnmpStats.verifySwitch(switchOff)?.on == false && AnmpStats.verify(switchOff) == null && AnmpStats.verifySwitch(report) == null)
        return buildJsonObject {
            put("comment", "ADR 0045. ts = radioBudget time, device key = inputs.deviceASignPrivate, person key = inputs.personPrivate")
            put("day", day)
            hex("keySeed", seed)
            hex("publicKey", key.publicKey)
            hex("marker", marker)
            put("relayIndexOf5", AnmpStats.relayIndex(marker, 5))
            putJsonObject("report") {
                hex("bodyBytes", bodyBytes)
                hex("signingBytes", signing)
                hex("signature", signature)
                hex("report", report)
            }
            putJsonObject("switchOff") {
                put("comment", "switched off on deviceA, android")
                hex("report", switchOff)
            }
            putJsonObject("days") {
                put("0", AnmpStats.dayOf(0))
                put("951782399", AnmpStats.dayOf(951_782_399))
                put("951782400", AnmpStats.dayOf(951_782_400))
                put("-1", AnmpStats.dayOf(-1))
            }
            putJsonObject("versions") {
                put("0.4.2", AnmpStats.versionOf("0.4.2"))
                put("0.1.0.ab12cd", AnmpStats.versionOf("0.1.0.ab12cd"))
                put("0.2.0-beta.1", AnmpStats.versionOf("0.2.0-beta.1"))
                put("dev", AnmpStats.versionOf("dev"))
            }
        }
    }

    /**
     * ADR 0051, 0057, ANMP "One radio channel": what a bridge needs beyond the tags above. Public-form packets (signed
     * circle message, direct message to deviceB), radio rule, accepted hours, public form check, the patron exchange with
     * fixed one-time keys and nonce, windows across an epoch border, and packets every reader must drop.
     */
    private suspend fun radioVectors(radio: Radio, a: AnmpDevice, b: AnmpDevice, circle: AnmpCircleKeys, pairKey: ByteArray, ts: Long): JsonObject {
        val scope = Fixtures.CIRCLE_ID
        val bSecret = AnmpRadio.deviceSecret(b.deviceId)
        val bRoute = AnmpRadio.routeTag(bSecret, radio.hour)
        val bSender = AnmpRadio.senderTag(bSecret, radio.hour)

        val circleContent = AnmpContent(id = Fixtures.messageId, ts = ts, text = "Hallo Funk")
        val circleH = Anmp.encodeHeader(radio.circleHeader)
        val circleC = Anmp.encodeContent(circleContent)
        val circleSigning = Anmp.signingBytes(scope, circleH, circleC)
        val circleSignature = a.sign(circleSigning)
        val circlePacket = Anmp.encodeFull(radio.circleHeader, circleContent, scope, AnmpAead.withFixedNonce(circle.aeadKey, Fixtures.nonce(0x0A)), a)

        val directHeader = AnmpHeader(AnmpKind.DIRECT, radio.sender, route = bRoute)
        val directContent = AnmpContent(id = Fixtures.messageId, ts = ts, text = "Nur für dich", node = 0x12345678)
        val directPacket = Anmp.encode(directHeader, directContent, Anmp.DIRECT_SCOPE, AnmpAead.withFixedNonce(pairKey, Fixtures.nonce(0x0B)))

        val unsignedCircle = Anmp.encode(radio.circleHeader, circleContent, scope, AnmpAead.withFixedNonce(circle.aeadKey, Fixtures.nonce(0x0D)))
        val oversize = Anmp.encode(radio.circleHeader, AnmpContent(id = Fixtures.messageId, ts = ts, text = "x".repeat(200)), scope, AnmpAead.withFixedNonce(circle.aeadKey, Fixtures.nonce(0x0E)))

        val asker = AnmpEphemeral.of(Fixtures.askerEph)
        val patron = AnmpEphemeral.of(Fixtures.patronEph)
        val patronPacket = Anmp.encodeOpen(AnmpHeader(AnmpKind.PATRON, bSender, senderKey = patron.publicKey, waiting = 2))
        val ask = Anmp.encodeOpen(AnmpHeader(AnmpKind.ASK, radio.sender, since = ts, askTags = arrayOf(AnmpRadio.askTag(radio.deviceRoute), AnmpRadio.askTag(radio.circleRoute))))
        val windows = listOf("deviceA" to radio.deviceSecret, "circle" to radio.circleSecret).map { (target, secret) -> target to AnmpRadio.pickupWindows(secret, radio.hour).single() }
        val sharedKey = asker.sharedKey(patron.publicKey)
        val pickupPacket = AnmpRadio.pickupPacket(radio.sender, asker, patron.publicKey, radio.hour, windows.map { it.second }, AnmpAead.withFixedNonce(sharedKey, Fixtures.nonce(0x0C)))

        val borderHour = AnmpRadio.epochStart(AnmpRadio.epochOf(radio.hour) + 1) + 10
        val borderWindows = AnmpRadio.pickupWindows(radio.deviceSecret, borderHour)

        suspend fun JsonObjectBuilder.windowTags(w: AnmpPickupWindow, hour: Long) {
            val tags = AnmpRadio.windowTags(w, hour)
            put("from", tags.first().first)
            put("to", tags.last().first)
            put("count", tags.size)
            hex("firstTag", tags.first().second)
            hex("lastTag", tags.last().second)
            hex("sha256", AnmpCrypto.sha256(tags.fold(ByteArray(0)) { acc, t -> acc + t.second }))
        }

        val invalid = invalidPackets(radio)
        val hours = AnmpRadio.acceptedHours(ts)
        val routeTags = hours.map { AnmpRadio.routeTag(radio.deviceSecret, it).toHex() }
        val senderTags = hours.map { AnmpRadio.senderTag(radio.deviceSecret, it).toHex() }
        val tagJson = windows.map { (target, w) -> buildJsonObject { put("target", target); windowTags(w, radio.hour) } }
        val borderJson = borderWindows.map { w -> buildJsonObject { put("epoch", w.epoch); hex("f", w.f); hex("b", w.b); windowTags(w, borderHour) } }

        return buildJsonObject {
            hex("deviceBRadioSecret", bSecret)
            hex("deviceBRouteTag", bRoute)
            hex("deviceBSenderTag", bSender)
            putJsonObject("rule") {
                put("comment", "AnmpKind: radio = may go over radio (radio rule, ADR 0015, 0057), open = header only (ADR 0051)")
                put("kinds", buildJsonArray {
                    for (k in AnmpKind.entries) add(buildJsonObject { put("code", k.code); put("conv", k.conv); put("radio", k.radio); put("open", k.open) })
                })
            }
            putJsonObject("hours") {
                put("comment", "accepted hours of ts (previous, current, next) with route and sender tags of deviceA")
                put("accepted", buildJsonArray { hours.forEach { add(JsonPrimitive(it)) } })
                put("deviceRouteTags", buildJsonArray { routeTags.forEach { add(JsonPrimitive(it)) } })
                put("deviceSenderTags", buildJsonArray { senderTags.forEach { add(JsonPrimitive(it)) } })
            }
            putJsonObject("circlePacket") {
                put("comment", "public form: circle message of deviceA, signed, circle key, epoch 3, route = circleRouteTag, nonce 0a")
                put("scope", scope)
                hex("header", circleH)
                hex("content", circleC)
                hex("signingBytes", circleSigning)
                hex("signature", circleSignature)
                hex("packet", circlePacket.packet)
                hex("opened", circlePacket.opened)
                put("size", circlePacket.packet.size)
                put("publicForm", AnmpRadio.isPublicForm(circlePacket.packet))
            }
            putJsonObject("directPacket") {
                put("comment", "public form: direct message deviceA → deviceB, unsigned, key direct.key, route = deviceBRouteTag, node 0x12345678, nonce 0b")
                put("scope", Anmp.DIRECT_SCOPE)
                hex("header", Anmp.encodeHeader(directHeader))
                hex("content", Anmp.encodeContent(directContent))
                hex("packet", directPacket)
                put("size", directPacket.size)
                put("publicForm", AnmpRadio.isPublicForm(directPacket))
            }
            put("publicForm", buildJsonArray {
                for ((name, p) in listOf(
                    "directPacket" to directPacket, "circleUnsigned" to unsignedCircle, "oversize" to oversize,
                    "directWithTo" to Anmp.encode(AnmpHeader(AnmpKind.DIRECT, a.tag, b.tag), directContent, Anmp.DIRECT_SCOPE, AnmpAead.withFixedNonce(pairKey, Fixtures.nonce(0x0F))),
                    "ask" to ask, "patron" to patronPacket, "pickup" to pickupPacket,
                )) add(buildJsonObject { put("name", name); hex("packet", p); put("size", p.size); put("publicForm", AnmpRadio.isPublicForm(p)) })
            })
            putJsonObject("pickup") {
                put("comment", "ADR 0051 §5a: deviceA asks, deviceB is patron. ask with both short route tags; patron with one-time key, waiting 2; " +
                    "pickup up to hour with the windows of deviceA and the circle, nonce 0c. tags: all route tags a window gives, sha256 over them in hour order")
                hex("ask", ask)
                hex("askerPrivate", Fixtures.askerEph)
                hex("askerPublic", asker.publicKey)
                hex("patronPrivate", Fixtures.patronEph)
                hex("patronPublic", patron.publicKey)
                hex("sharedKey", sharedKey)
                hex("patron", patronPacket)
                put("hour", radio.hour)
                put("windows", buildJsonArray {
                    for ((target, w) in windows) add(buildJsonObject { put("target", target); put("epoch", w.epoch); hex("f", w.f); hex("b", w.b) })
                })
                hex("packet", pickupPacket)
                put("tags", buildJsonArray { tagJson.forEach { add(it) } })
            }
            putJsonObject("border") {
                put("comment", "pickup windows of deviceA 10 hours after an epoch border: two windows")
                put("hour", borderHour)
                put("windows", buildJsonArray { borderJson.forEach { add(it) } })
            }
            put("invalid", buildJsonArray {
                for ((name, p) in invalid) add(buildJsonObject { put("name", name); hex("packet", p) })
            })
        }
    }

    /** Packets every reader drops (peek gives nothing); checked here, so the file never holds one Kotlin accepts. */
    private fun invalidPackets(radio: Radio): List<Pair<String, ByteArray>> {
        fun packet(h: Anmp.Header, b: ByteArray = ByteArray(0)) = Anmp.MAGIC + Anmp.cbor.encodeToByteArray(Anmp.Packet.serializer(), Anmp.Packet(Anmp.cbor.encodeToByteArray(Anmp.Header.serializer(), h), b))
        val f = radio.sender
        val good = packet(Anmp.Header(21, f, w = 2))
        val list = listOf(
            "onlyMagic" to Anmp.MAGIC,
            "oldMagic" to byteArrayOf(0x50, 0x4D, 0x01) + good.copyOfRange(3, good.size),
            "otherVersion" to byteArrayOf(0x41, 0x4E, 0x02) + good.copyOfRange(3, good.size),
            "notArray" to Anmp.MAGIC + byteArrayOf(0xA0.toByte()),
            "unknownKind" to packet(Anmp.Header(99, f)),
            "fromLength" to packet(Anmp.Header(21, f.copyOf(7))),
            "toLength" to packet(Anmp.Header(22, f, t = ByteArray(9))),
            "senderKeyLength" to packet(Anmp.Header(21, f, x = ByteArray(31))),
            "senderDeviceLength" to packet(Anmp.Header(2, f, d = ByteArray(8))),
            "routeLength" to packet(Anmp.Header(1, f, e = 3, r = ByteArray(7))),
            "askTagLength" to packet(Anmp.Header(20, f, n = 1, q = listOf(ByteArray(5)))),
            "askTooManyTags" to packet(Anmp.Header(20, f, n = 1, q = List(9) { ByteArray(6) })),
            "negativeSince" to packet(Anmp.Header(20, f, n = -1)),
            "negativeHour" to packet(Anmp.Header(22, f, o = -1)),
        )
        for ((name, p) in list) check(Anmp.peek(p) == null) { "invalid vector $name is accepted" }
        check(Anmp.peek(good) != null)
        return list
    }

    /** ADR 0062: alert by a sender (as the device address sends it) and by the owner, channel link, ingress record. */
    private suspend fun alertVectors(ts: Long, blob: AnmpSealedBlob): JsonObject {
        val owner = AnmpSpaceKey.fromPrivateKey(Fixtures.channelOwner)
        val sender = AnmpSpaceKey.fromPrivateKey(Fixtures.senderSeed)
        val readPublic = AnmpAlerts.readPublicKey(Fixtures.readKey)
        val attachment = AnmpAttachment(blob.id, Fixtures.blobKey, "jpg", "garten.jpg", 10, 640, 480, ByteArray(16) { 0x33 })
        val senderBody = AnmpAlertBody(
            Fixtures.alertId, ts, AnmpAlerts.ALARM, "Bewegung im Garten", "Kamera Süd", "https://cam.example/dashboard",
            until = ts + 3600, source = "Home Assistant", attachments = listOf(attachment), key = AnmpAlerts.alertKey("garten-bewegung"),
        )
        val ownerBody = AnmpAlertBody(ByteArray(8) { (0xD0 + it).toByte() }, ts + 60, AnmpAlerts.INFO, text = "Entwarnung", replaces = Fixtures.alertId, ended = true)
        val token = Fixtures.token
        val tokenText = AnmpKeys.encode(token)
        val ingress = AnmpAlerts.ingressSpace(token)
        val record = AnmpIngressRecord(1, owner.space, readPublic, Fixtures.senderSeed, Fixtures.SENDER_GRANT, "Home Assistant")
        val invite = AnmpChannelInvite(Fixtures.networkId, owner.space, "Haus", Fixtures.readKey, Fixtures.READ_GRANT)

        suspend fun alertOf(body: AnmpAlertBody, signer: AnmpSpaceKey, eph: ByteArray, nonce: Int): JsonObject {
            val bodyBytes = AnmpAlerts.encodeBody(body)
            val signed = AnmpAlerts.sign(body, signer)
            val ephPublic = AnmpCrypto.xPublicRaw(AnmpCrypto.xPrivate(eph))
            val sealKey = AnmpCrypto.hkdf(AnmpCrypto.xShared(AnmpCrypto.xPrivate(eph), readPublic), AnmpInfo.ALERT_SEAL.encodeToByteArray() + ephPublic + readPublic)
            return buildJsonObject {
                hex("signer", signer.publicKey)
                hex("ephPrivateKey", eph)
                hex("ephPublicKey", ephPublic)
                hex("nonce", Fixtures.nonce(nonce))
                hex("sealKey", sealKey)
                hex("bodyBytes", bodyBytes)
                hex("signingBytes", AnmpAlerts.signingBytes(bodyBytes))
                hex("signed", signed)
                hex("packet", AnmpAlerts.seal(signed, readPublic, eph, Fixtures.nonce(nonce)))
            }
        }

        val senderJson = alertOf(senderBody, sender, Fixtures.ephKey, 7)
        val ownerJson = alertOf(ownerBody, owner, Fixtures.ephKey2, 8)
        val link = AnmpAlerts.channelLink("anynear.net", invite)
        val join = AnmpChannelJoin(ByteArray(16) { 0x11 }, ByteArray(32) { 0x22 }, ByteArray(32) { 0x44 }, ByteArray(40) { 0x55 })
        val joinLink = AnmpAlerts.channelLink("anynear.net", AnmpChannelInvite(Fixtures.networkId, owner.space, "Haus", Fixtures.readKey, Fixtures.READ_GRANT, join = join))
        val keyBytes = AnmpAlerts.channelKeySigningBytes(owner.space, Fixtures.readKey, Fixtures.READ_GRANT)
        val keySig = owner.sign(keyBytes)
        val seed = AnmpAlerts.ingressSeed(token)
        val aeadKey = AnmpCrypto.hkdf(token, AnmpInfo.INGRESS_AEAD)
        val sealed = AnmpAlerts.sealIngress(token, record, Fixtures.nonce(9))
        val noLabel = AnmpIngressRecord(1, owner.space, readPublic, Fixtures.senderSeed, Fixtures.SENDER_GRANT)
        val gardenKey = AnmpAlerts.alertKey("garten-bewegung")
        val kitchenKey = AnmpAlerts.alertKey("Küche/Rauch")
        val emptyText = AnmpAlerts.encodeBody(
            AnmpAlertBody(Fixtures.alertId, ts, AnmpAlerts.INFO, text = "", attachments = listOf(AnmpAttachment(blob.id, Fixtures.blobKey, "file", size = 0))),
        )
        return buildJsonObject {
            put("comment", "ADR 0062. sender: alert as the device address sends it (fields id, ts, prio, title, text, url, until, source, " +
                "attachments = [blob.id, blob.key, jpg, garten.jpg, 10, 640, 480, hash 33…], key = alertKey garten-bewegung); owner: all clear by the owner " +
                "(id d0…d7, ts + 60, prio 1, text Entwarnung, replaces = sender id, ended). Grants from ANDP IssueGrant, issued ts - 60.")
            put("ts", ts)
            hex("ownerSeed", Fixtures.channelOwner)
            hex("ownerPublicKey", owner.publicKey)
            put("space", owner.space)
            hex("readPrivateKey", Fixtures.readKey)
            hex("readPublicKey", readPublic)
            hex("senderSeed", Fixtures.senderSeed)
            hex("senderPublicKey", sender.publicKey)
            put("readGrant", Fixtures.READ_GRANT)
            put("senderGrant", Fixtures.SENDER_GRANT)
            put("sender", senderJson)
            put("owner", ownerJson)
            putJsonObject("link") {
                put("comment", "name Haus, not curated")
                hex("networkId", Fixtures.networkId)
                put("host", "anynear.net")
                put("link", link)
            }
            putJsonObject("joinLink") {
                put("comment", "ADR 0062 §11: link of an own channel with field 7 join = {0 device 11×16, 1 key 22×32, 2 inbox 44×32, 3 grant 55×40}")
                put("link", joinLink)
            }
            putJsonObject("channelKey") {
                put("comment", "ADR 0062 §11: new read key for a subscriber; readKey = readPrivateKey, grant = readGrant, sig by the owner key")
                hex("signingBytes", keyBytes)
                hex("sig", keySig)
            }
            putJsonObject("ingress") {
                put("comment", "HKDF over the 32 raw token bytes; record label Home Assistant")
                hex("token", token)
                put("tokenText", tokenText)
                put("url", AnmpAlerts.ingressUrl("alerts.anynear.app", tokenText))
                hex("seed", seed)
                hex("ownerPublicKey", ingress.publicKey)
                put("space", ingress.space)
                hex("aeadKey", aeadKey)
                hex("nonce", Fixtures.nonce(9))
                hex("record", Anmp.cbor.encodeToByteArray(AnmpIngressRecord.serializer(), record))
                hex("sealed", sealed)
                hex("recordWithoutLabel", Anmp.cbor.encodeToByteArray(AnmpIngressRecord.serializer(), noLabel))
            }
            putJsonObject("alertKey") {
                put("comment", "field 12: SHA-256(\"anmp/v1/alert-key\" ‖ key in UTF-8), first 8 bytes")
                put("key", "garten-bewegung")
                hex("hash", gardenKey)
                put("unicodeKey", "Küche/Rauch")
                hex("unicodeHash", kitchenKey)
            }
            putJsonObject("encoding") {
                put("comment", "text (4) always written, also empty; label (5) of a record omitted when empty; attachment type and size always written")
                hex("emptyTextBody", emptyText)
            }
        }
    }

    private class Radio(
        val channelPsk: ByteArray,
        val hour: Long,
        val deviceSecret: ByteArray,
        val deviceRoute: ByteArray,
        val sender: ByteArray,
        val circleSecret: ByteArray,
        val circleRoute: ByteArray,
        val forwardStart: ByteArray,
        val backwardEnd: ByteArray,
        val window: AnmpPickupWindow,
    ) {
        val circleHeader get() = AnmpHeader(AnmpKind.CIRCLE, sender, epoch = 3, route = circleRoute)
        val directHeader get() = AnmpHeader(AnmpKind.DIRECT, sender, route = deviceRoute)

        companion object {
            suspend fun of(a: AnmpDevice, ts: Long): Radio {
                val hour = AnmpRadio.hourOf(ts)
                val deviceSecret = AnmpRadio.deviceSecret(a.deviceId)
                val circleSecret = AnmpRadio.circleSecret(Fixtures.circleSecret)
                val epoch = AnmpRadio.epochOf(hour)
                return Radio(
                    AnmpRadio.channelPsk(), hour, deviceSecret, AnmpRadio.routeTag(deviceSecret, hour), AnmpRadio.senderTag(deviceSecret, hour),
                    circleSecret, AnmpRadio.routeTag(circleSecret, hour),
                    AnmpRadio.forwardStart(deviceSecret, epoch), AnmpRadio.backwardEnd(deviceSecret, epoch),
                    AnmpRadio.pickupWindows(deviceSecret, hour).last(),
                )
            }
        }
    }

    private fun AnmpHeader.copyEpoch(e: Int) = AnmpHeader(kind, from, to, e, senderKey, senderDevice)

    private suspend fun device(d: AnmpDevice): JsonObject {
        val local = d.localStorageKey()
        return buildJsonObject { deviceFields(d, local) }
    }

    private fun JsonObjectBuilder.deviceFields(d: AnmpDevice, local: ByteArray) {
        hex("signPublicKey", d.signKey)
        hex("x25519PublicKey", d.publicKey)
        hex("deviceId", d.deviceId)
        put("deviceIdText", d.deviceIdText)
        hex("tag", d.tag)
        hex("localStorageKey", local)
    }
}
