package app.anynear.anmp.core

import app.anynear.andp.StrictJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64

/**
 * Radio list (ADR 0054 §1b, ANMP "Radio list"): regions with their area, the preset of channel 0, whether ANMP goes out
 * on channel 0, and the communities' public channels. ANDP checks head and signature (ANDP §5.9); this is the content.
 * Nothing of it is built into the app: a new community or another preset needs no app update. The same signed list
 * carries the update rules for app and web app (ADR 0096).
 */
data class RadioList(
    val issued: Long,
    val regions: List<RadioRegion>,
    /** What the network asks of app builds (Android, iOS), ADR 0096; `null` asks nothing. */
    val app: UpdateRule? = null,
    /** What the network asks of web app builds, ADR 0096; `null` asks nothing. */
    val web: UpdateRule? = null,
    /** Where radios on Wi-Fi upload as gateways (ADR 0054 §5b); `null`: no radio becomes a gateway. */
    val gateway: RadioGateway? = null,
) {
    /** The first region whose area contains the point, `null` outside all regions. The point never leaves the phone. */
    fun regionAt(lat: Double, lon: Double): RadioRegion? = regions.firstOrNull { it.contains(lat, lon) }

    /**
     * The region for a radio at the point, with hysteresis at borders (ADR 0054 §1, "Preset-Wechsel"): the radio keeps
     * [current] (a region name) while the point is inside it or less than [marginKm] outside, unless an earlier region
     * of the list contains the point at least [marginKm] deep. Without [current], or once it is left, the first region
     * containing the point at least [marginKm] deep counts, else the first containing it at all. `null` outside all
     * regions. The point never leaves the phone.
     */
    fun regionFor(lat: Double, lon: Double, current: String?, marginKm: Double = BORDER_MARGIN_KM): RadioRegion? {
        val deep = regions.indexOfFirst { it.contains(lat, lon) && it.borderDistanceKm(lat, lon) >= marginKm }
        val held = if (current.isNullOrEmpty()) -1 else regions.indexOfFirst { it.name == current }
        if (held >= 0) {
            val r = regions[held]
            if (r.contains(lat, lon) || r.borderDistanceKm(lat, lon) < marginKm) return if (deep in 0 until held) regions[deep] else r
        }
        return if (deep >= 0) regions[deep] else regionAt(lat, lon)
    }

    /** The region with this name, `null` if the list has none (e.g. a region chosen by hand that was removed). */
    fun region(name: String): RadioRegion? = regions.firstOrNull { it.name == name }

    companion object {
        /** Meshtastic modem presets a region may name; a region with another preset is skipped (ANMP "Radio list"). */
        /** Distance to a border before the radio changes region (ADR 0054 §1). */
        const val BORDER_MARGIN_KM = 2.0

        /** Meshtastic LoRa region codes (`Config.LoRaConfig.RegionCode` without UNSET); a region naming another is skipped. */
        val LORA_REGIONS = setOf(
            "US", "EU_433", "EU_868", "CN", "JP", "ANZ", "KR", "TW", "RU", "IN", "NZ_865", "TH", "LORA_24", "UA_433",
            "UA_868", "MY_433", "MY_919", "SG_923", "PH_433", "PH_868", "PH_915", "ANZ_433", "KZ_433", "KZ_863", "NP_865",
            "BR_902",
        )

        /** LoRa region of a list region that names none: the lists before this field were all for Europe. */
        const val DEFAULT_LORA = "EU_868"

        val PRESETS = setOf(
            "LONG_FAST", "LONG_SLOW", "LONG_MODERATE", "LONG_TURBO", "MEDIUM_FAST", "MEDIUM_SLOW",
            "SHORT_FAST", "SHORT_SLOW", "SHORT_TURBO",
        )

        /**
         * Reads the text of a radio list that ANDP accepted. Unknown fields are ignored; a region or community that is
         * invalid or names something this app does not know is skipped, the rest counts. A repeated key anywhere in the
         * text rejects the whole list, as in ANDP.
         */
        fun parse(text: String): RadioList {
            // The same strict reading as ANDP's check of the head (ANDP §1): a repeated key rejects the list.
            val obj = StrictJson.parse(text) as? JsonObject ?: throw IllegalArgumentException("radio list is not an object")
            val issued = (obj["issued"] as? JsonPrimitive)?.longOrNull ?: throw IllegalArgumentException("radio list without issued")
            val regions = (obj["regions"] as? JsonArray).orEmpty().mapNotNull { RadioRegion.read(it) }
            return RadioList(issued, regions, UpdateRule.read(obj["app"]), UpdateRule.read(obj["web"]), RadioGateway.read(obj["gateway"]))
        }
    }
}

/**
 * One region: [area] as `[lat, lon]` points of a simple polygon, the radio settings there ([lora] region code,
 * [preset] of channel 0, [frequency] in MHz or 0 for the default slot of region and preset), communities inside it.
 */
data class RadioRegion(
    val name: String,
    val area: List<Pair<Double, Double>>,
    val preset: String,
    /** Whether ANMP goes out on channel 0 here (ADR 0054 §1); otherwise only on the channel `anynear`. */
    val channel0: Boolean,
    val communities: List<RadioCommunity>,
    val lora: String = RadioList.DEFAULT_LORA,
    val frequency: Double = 0.0,
) {
    /**
     * Shortest distance in km from the point to the border of [area], inside or outside. Plane approximation around
     * the point (equirectangular), good enough for a few km of hysteresis.
     */
    fun borderDistanceKm(lat: Double, lon: Double): Double {
        val kx = KM_PER_DEGREE * kotlin.math.cos(lat * kotlin.math.PI / 180)
        var best = Double.MAX_VALUE
        var j = area.size - 1
        for (i in area.indices) {
            val ax = (area[j].second - lon) * kx
            val ay = (area[j].first - lat) * KM_PER_DEGREE
            val bx = (area[i].second - lon) * kx
            val by = (area[i].first - lat) * KM_PER_DEGREE
            val dx = bx - ax
            val dy = by - ay
            val len = dx * dx + dy * dy
            val t = if (len == 0.0) 0.0 else (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            best = minOf(best, kotlin.math.sqrt(px * px + py * py))
            j = i
        }
        return best
    }

    /** Even-odd test over the polygon; points on the border may count either way. */
    fun contains(lat: Double, lon: Double): Boolean {
        var inside = false
        var j = area.size - 1
        for (i in area.indices) {
            val (yi, xi) = area[i]
            val (yj, xj) = area[j]
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    internal companion object {
        private const val KM_PER_DEGREE = 111.195

        /** Frequencies Meshtastic regions use (LORA_24 up to 2.5 GHz); anything else skips the region. */
        private val FREQUENCY_MHZ = 100.0..3000.0

        fun read(e: JsonElement): RadioRegion? {
            val o = e as? JsonObject ?: return null
            val name = o.string("name") ?: return null
            val preset = o.string("preset")?.takeIf { it in RadioList.PRESETS } ?: return null
            val area = (o["area"] as? JsonArray)?.map { p ->
                val xy = p as? JsonArray ?: return null
                if (xy.size != 2) return null
                val lat = (xy[0] as? JsonPrimitive)?.doubleOrNull ?: return null
                val lon = (xy[1] as? JsonPrimitive)?.doubleOrNull ?: return null
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
                lat to lon
            } ?: return null
            if (area.size < 3) return null
            val channel0 = (o["channel0"] as? JsonPrimitive)?.booleanOrNull ?: true
            val lora = if (o.containsKey("lora")) o.string("lora")?.takeIf { it in RadioList.LORA_REGIONS } ?: return null
                else RadioList.DEFAULT_LORA
            val frequency = if (o.containsKey("frequency")) (o["frequency"] as? JsonPrimitive)?.doubleOrNull
                ?.takeIf { it == 0.0 || it in FREQUENCY_MHZ } ?: return null else 0.0
            val communities = (o["communities"] as? JsonArray).orEmpty().mapNotNull { RadioCommunity.read(it) }
            return RadioRegion(name, area, preset, channel0, communities, lora, frequency)
        }
    }
}

/**
 * A community's public channel (ADR 0054 §1a): [channel] as on the radio (at most 11 bytes), [psk] its public key
 * (1, 16 or 32 bytes), [preset] it needs, [operator] and [source] (where the community publishes the channel).
 */
data class RadioCommunity(
    val name: String,
    val channel: String,
    val psk: ByteArray,
    val preset: String,
    val operator: String,
    val source: String,
) {
    override fun equals(other: Any?): Boolean = other is RadioCommunity && name == other.name && channel == other.channel &&
        psk.contentEquals(other.psk) && preset == other.preset && operator == other.operator && source == other.source

    override fun hashCode(): Int = (name.hashCode() * 31 + channel.hashCode()) * 31 + psk.contentHashCode()

    internal companion object {
        private val b64u = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

        fun read(e: JsonElement): RadioCommunity? {
            val o = e as? JsonObject ?: return null
            val name = o.string("name") ?: return null
            val channel = o.string("channel")?.takeIf { it.isNotEmpty() && it.encodeToByteArray().size <= 11 } ?: return null
            val psk = o.string("psk")?.let { runCatching { b64u.decode(it) }.getOrNull() }?.takeIf { it.size in setOf(1, 16, 32) } ?: return null
            val preset = o.string("preset")?.takeIf { it in RadioList.PRESETS } ?: return null
            return RadioCommunity(name, channel, psk, preset, o.string("operator").orEmpty(), o.string("source").orEmpty())
        }
    }
}

/**
 * The anynear MQTT server for gateways (ADR 0054 §5b): every radio on Wi-Fi uploads there. The login is public, it is on
 * every gateway. [host] is a DNS name or IPv4 address (letters, digits, `.`, `-`). Lengths are those the Meshtastic firmware stores: [address] and [user] at most 63 bytes, [password]
 * and [root] at most 31.
 */
data class RadioGateway(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val user: String,
    val password: String,
    val root: String,
) {
    /** The value of the firmware's MQTT `address`: the host, with `:port` only if it is not the default for [tls]. */
    val address: String get() = if (port == defaultPort(tls)) host else "$host:$port"

    internal companion object {
        const val PORT = 1883
        const val TLS_PORT = 8883
        private const val MAX_ADDRESS = 63
        private const val MAX_USER = 63
        private const val MAX_PASSWORD = 31
        private const val MAX_ROOT = 31

        fun defaultPort(tls: Boolean) = if (tls) TLS_PORT else PORT

        /** Reads the block; `null` if it is missing or anything in it is invalid (then no radio becomes a gateway). */
        fun read(e: JsonElement?): RadioGateway? {
            val o = e as? JsonObject ?: return null
            val host = o.string("host")?.takeIf { it.isNotEmpty() && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' } } ?: return null
            val tls = if (o.containsKey("tls")) (o["tls"] as? JsonPrimitive)?.booleanOrNull ?: return null else false
            val port = if (o.containsKey("port")) (o["port"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..65535 }?.toInt() ?: return null
                else defaultPort(tls)
            val user = o.string("user")?.takeIf { it.isNotEmpty() && it.encodeToByteArray().size <= MAX_USER } ?: return null
            val password = o.string("pass")?.takeIf { it.encodeToByteArray().size <= MAX_PASSWORD } ?: return null
            val root = o.string("root")?.takeIf { it.isNotEmpty() && it.encodeToByteArray().size <= MAX_ROOT } ?: return null
            return RadioGateway(host, port, tls, user, password, root).takeIf { it.address.encodeToByteArray().size <= MAX_ADDRESS }
        }
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
