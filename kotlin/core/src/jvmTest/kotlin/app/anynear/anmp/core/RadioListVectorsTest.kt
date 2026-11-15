package app.anynear.anmp.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * vectors/anmp-v1-radio-list.json: the content of the radio list (ANMP "Radio list") as [RadioList.parse] reads it, so
 * that other implementations (Go) skip and keep exactly the same regions and communities. `UPDATE_VECTORS=1` rewrites
 * the file instead. JVM only, it reads the file directly.
 */
class RadioListVectorsTest {
    private val json = Json { prettyPrint = true }

    // Rough square around Hessen, a second one around Bayern.
    private val list = """
        {"v":1,"id":"x","network":"y","issued":1762300000000,"future":{"a":1},
         "regions":[
          {"name":"Hessen","area":[[49.4,7.7],[51.7,7.7],[51.7,10.3],[49.4,10.3]],"preset":"SHORT_SLOW",
           "communities":[
            {"name":"Mesh Sample","channel":"Mesh Sample","psk":"AQ","preset":"SHORT_SLOW","operator":"example.org","source":"https://example.org"},
            {"name":"zu lang","channel":"Mesh Sample 2","psk":"AQ","preset":"SHORT_SLOW"},
            {"name":"falscher Schlüssel","channel":"X","psk":"AQID","preset":"SHORT_SLOW"},
            {"name":"elf Bytes","channel":"Grüße aus","psk":"AAECAwQFBgcICQoLDA0ODw","preset":"SHORT_SLOW","operator":7},
            {"name":"zwölf Bytes","channel":"Grüße aus H","psk":"AQ","preset":"SHORT_SLOW"},
            {"name":"leer","channel":"","psk":"AQ","preset":"SHORT_SLOW"},
            {"name":"Polsterung","channel":"P","psk":"AQ==","preset":"SHORT_SLOW"},
            {"name":"Restbits","channel":"R","psk":"AR","preset":"SHORT_SLOW"},
            {"name":"Zeilenumbruch","channel":"Z","psk":"AAECAwQFBgcICQoL\nDA0ODw","preset":"SHORT_SLOW"},
            {"name":"Plus","channel":"S","psk":"+/8","preset":"SHORT_SLOW"},
            {"name":"Preset fremd","channel":"F","psk":"AQ","preset":"VERY_NEW_PRESET"},
            {"channel":"ohne Name","psk":"AQ","preset":"SHORT_SLOW"},
            "kein Objekt"]},
          {"name":"Neu","area":[[0,0],[1,0],[1,1]],"preset":"VERY_NEW_PRESET"},
          {"name":"Zwei Punkte","area":[[0,0],[1,1]],"preset":"LONG_FAST"},
          {"name":"Breite 91","area":[[0,0],[1,1],[91,0]],"preset":"LONG_FAST"},
          {"name":"Drei Zahlen","area":[[0,0,0],[1,1],[1,0]],"preset":"LONG_FAST"},
          {"name":"Text als Zahl","area":[["10",20],[11,"20"],[11,21]],"preset":"MEDIUM_FAST","channel0":"false","communities":"keine Liste"},
          {"name":"Zahl als Schalter","area":[[-10,-20],[-11,-20],[-11,-21]],"preset":"LONG_TURBO","channel0":0},
          {"name":7,"area":[[0,0],[1,0],[1,1]],"preset":"LONG_FAST"},
          [],
          {"name":"Bayern","area":[[47.2,8.9],[50.6,8.9],[50.6,13.9],[47.2,13.9]],"preset":"LONG_FAST","channel0":false}
         ]}
    """.trimIndent()

    private val cases = listOf(
        "list" to list,
        "notObject" to "[]",
        "withoutIssued" to """{"v":1}""",
        "issuedText" to """{"issued":"1762300000000","regions":[]}""",
        "issuedFraction" to """{"issued":1.5,"regions":[]}""",
        "regionsNotArray" to """{"issued":1,"regions":{}}""",
        "notJson" to "{",
        "gateway" to """{"issued":1,"regions":[],"gateway":{"host":"mqtt.example.net","port":1883,"tls":false,"user":"gw","pass":"open","root":"msh/EU_868","future":1}}""",
        "gatewayDefaults" to """{"issued":1,"regions":[],"gateway":{"host":"mqtt.example.net","user":"gw","pass":"","root":"msh/EU_868"}}""",
        "gatewayOwnPort" to """{"issued":1,"regions":[],"gateway":{"host":"192.0.2.7","port":1884,"user":"gw","pass":"open","root":"msh/EU_868"}}""",
        "gatewayTls" to """{"issued":1,"regions":[],"gateway":{"host":"mqtt.example.net","tls":true,"user":"gw","pass":"open","root":"msh/EU_868"}}""",
        "gatewayTextValues" to """{"issued":1,"regions":[],"gateway":{"host":"mqtt.example.net","port":"1883","tls":"false","user":"gw","pass":"open","root":"msh/EU_868"}}""",
        "gatewayHostWithPort" to """{"issued":1,"regions":[],"gateway":{"host":"mqtt.example.net:1883","user":"gw","pass":"open","root":"msh/EU_868"}}""",
        "gatewayPortZero" to """{"issued":1,"regions":[],"gateway":{"host":"mqtt.example.net","port":0,"user":"gw","pass":"open","root":"msh/EU_868"}}""",
        "gatewayTlsNull" to """{"issued":1,"regions":[],"gateway":{"host":"mqtt.example.net","tls":null,"user":"gw","pass":"open","root":"msh/EU_868"}}""",
        "gatewayWithoutRoot" to """{"issued":1,"regions":[],"gateway":{"host":"mqtt.example.net","user":"gw","pass":"open"}}""",
        "gatewayWithoutPass" to """{"issued":1,"regions":[],"gateway":{"host":"mqtt.example.net","user":"gw","root":"msh/EU_868"}}""",
        "gatewayEmptyUser" to """{"issued":1,"regions":[],"gateway":{"host":"mqtt.example.net","user":"","pass":"open","root":"msh/EU_868"}}""",
        "gatewayPass31" to """{"issued":1,"regions":[],"gateway":{"host":"mqtt.example.net","user":"gw","pass":"ppppppppppppppppppppppppppppppp","root":"msh/EU_868"}}""",
        "gatewayPass32" to """{"issued":1,"regions":[],"gateway":{"host":"mqtt.example.net","user":"gw","pass":"pppppppppppppppppppppppppppppppp","root":"msh/EU_868"}}""",
        "gatewayUserBytes" to """{"issued":1,"regions":[],"gateway":{"host":"mqtt.example.net","user":"üüüüüüüüüüüüüüüüüüüüüüüüüüüüüüüü","pass":"open","root":"msh/EU_868"}}""",
        "gatewayAddress64" to """{"issued":1,"regions":[],"gateway":{"host":"hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh","port":1884,"user":"gw","pass":"open","root":"msh/EU_868"}}""",
        "lora" to """{"issued":1,"regions":[
            {"name":"Europa","area":[[35,-10],[71,-10],[71,40],[35,40]],"preset":"LONG_FAST","lora":"EU_868"},
            {"name":"Hessen","area":[[49.4,7.7],[51.7,7.7],[51.7,10.3],[49.4,10.3]],"preset":"SHORT_SLOW","lora":"EU_868","frequency":869.525},
            {"name":"Text als Frequenz","area":[[0,0],[1,0],[1,1]],"preset":"LONG_FAST","lora":"US","frequency":"906.875"},
            {"name":"Null","area":[[0,0],[1,0],[1,1]],"preset":"LONG_FAST","lora":"LORA_24","frequency":0},
            {"name":"Region fremd","area":[[0,0],[1,0],[1,1]],"preset":"LONG_FAST","lora":"MARS_433"},
            {"name":"UNSET","area":[[0,0],[1,0],[1,1]],"preset":"LONG_FAST","lora":"UNSET"},
            {"name":"Region als Zahl","area":[[0,0],[1,0],[1,1]],"preset":"LONG_FAST","lora":3},
            {"name":"Region null","area":[[0,0],[1,0],[1,1]],"preset":"LONG_FAST","lora":null},
            {"name":"Frequenz zu klein","area":[[0,0],[1,0],[1,1]],"preset":"LONG_FAST","frequency":50},
            {"name":"Frequenz negativ","area":[[0,0],[1,0],[1,1]],"preset":"LONG_FAST","frequency":-869.525},
            {"name":"Frequenz Text","area":[[0,0],[1,0],[1,1]],"preset":"LONG_FAST","frequency":"schnell"},
            {"name":"Frequenz null","area":[[0,0],[1,0],[1,1]],"preset":"LONG_FAST","frequency":null}]}""",
        "gatewayNotObject" to """{"issued":1,"regions":[],"gateway":"mqtt.example.net"}""",
    )

    private val points = listOf(
        Triple("Frankfurt", 50.11, 8.68),
        Triple("München", 48.14, 11.58),
        Triple("both squares: the first region counts", 50.0, 9.5),
        Triple("Berlin", 52.52, 13.40),
        Triple("Text als Zahl", 10.9, 20.5),
        Triple("Corner Hessen", 49.4, 7.7),
    )

    /** regionFor in the list of case list: Hessen (49.4–51.7, 7.7–10.3) before Bayern (47.2–50.6, 8.9–13.9). */
    private data class Choice(val name: String, val lat: Double, val lon: Double, val current: String?)

    private val choices = listOf(
        Choice("Frankfurt, nothing before", 50.11, 8.68, null),
        Choice("1 km south of Hessen, was Hessen: kept", 49.39, 9.5, "Hessen"),
        Choice("1 km south of Hessen, nothing before: Bayern", 49.39, 9.5, null),
        Choice("6 km south of Hessen, was Hessen: Bayern", 49.345, 9.5, "Hessen"),
        Choice("1 km inside Hessen and Bayern, was Bayern: kept", 49.41, 9.5, "Bayern"),
        Choice("11 km inside Hessen and Bayern, was Bayern: Hessen comes first", 49.5, 9.5, "Bayern"),
        Choice("1 km inside Hessen only, nothing before: Hessen anyway", 51.69, 8.0, null),
        Choice("0.7 km west of Hessen, nothing before: none", 50.0, 7.69, null),
        Choice("0.7 km west of Hessen, was Hessen: kept", 50.0, 7.69, "Hessen"),
        Choice("Berlin, was Hessen: none", 52.52, 13.40, "Hessen"),
        Choice("Frankfurt, region no longer in the list", 50.11, 8.68, "Weg"),
        Choice("Frankfurt, empty name", 50.11, 8.68, ""),
    )

    @Test
    fun vectorsMatchFile() {
        val computed = compute()
        val text = json.encodeToString(JsonObject.serializer(), computed) + "\n"
        val file = File(System.getProperty("anmp.radioListVectors") ?: error("anmp.radioListVectors not set"))
        if (System.getProperty("anmp.updateVectors") == "1") {
            file.writeText(text)
            return
        }
        if (!file.exists()) fail("vectors/anmp-v1-radio-list.json missing: run UPDATE_VECTORS=1 ./gradlew :core:jvmTest")
        assertEquals(json.parseToJsonElement(file.readText()), computed, "vectors/anmp-v1-radio-list.json differs from RadioList")
    }

    private fun compute(): JsonObject = buildJsonObject {
        put("protocol", "ANMP version v1, radio list (ADR 0054 §1b)")
        put("comment", "Inputs are list texts that ANDP accepted (head and signature are ANDP's, not checked here). " +
            "result is what RadioList.parse keeps, or error; lora is EU_868 and frequency 0 (default slot) when the region names none. psk is hex; gateway is null unless the block is valid, address is the firmware value. points: regionAt in the list of case list. choices: regionFor (2 km at borders) in that list with the region the radio had before. Generated by RadioListVectorsTest (UPDATE_VECTORS=1).")
        put("cases", buildJsonArray {
            for ((name, text) in cases) add(buildJsonObject {
                put("name", name)
                put("text", text)
                put("result", runCatching { RadioList.parse(text) }.map(::encode).getOrElse { JsonPrimitive("error") })
            })
        })
        val l = RadioList.parse(list)
        put("points", buildJsonArray {
            for ((name, lat, lon) in points) add(buildJsonObject {
                put("name", name)
                put("lat", lat)
                put("lon", lon)
                put("region", l.regionAt(lat, lon)?.name?.let(::JsonPrimitive) ?: JsonNull)
            })
        })
        put("choices", buildJsonArray {
            for (c in choices) add(buildJsonObject {
                put("name", c.name)
                put("lat", c.lat)
                put("lon", c.lon)
                put("current", c.current?.let(::JsonPrimitive) ?: JsonNull)
                put("region", l.regionFor(c.lat, c.lon, c.current)?.name?.let(::JsonPrimitive) ?: JsonNull)
            })
        })
    }

    private fun encode(l: RadioList): JsonElement = buildJsonObject {
        put("issued", l.issued)
        put("regions", buildJsonArray {
            for (r in l.regions) add(buildJsonObject {
                put("name", r.name)
                put("area", buildJsonArray { for ((lat, lon) in r.area) add(buildJsonArray { add(JsonPrimitive(lat)); add(JsonPrimitive(lon)) }) })
                put("preset", r.preset)
                put("channel0", r.channel0)
                put("lora", r.lora)
                put("frequency", r.frequency)
                put("communities", buildJsonArray {
                    for (c in r.communities) add(buildJsonObject {
                        put("name", c.name)
                        put("channel", c.channel)
                        put("psk", c.psk.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
                        put("preset", c.preset)
                        put("operator", c.operator)
                        put("source", c.source)
                    })
                })
            })
        })
        put("gateway", l.gateway?.let { gw ->
            buildJsonObject {
                put("host", gw.host)
                put("port", gw.port)
                put("tls", gw.tls)
                put("user", gw.user)
                put("pass", gw.password)
                put("root", gw.root)
                put("address", gw.address)
            }
        } ?: JsonNull)
    }
}
