package app.anynear.anmp.core

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
 * carries the update rules for app and web app (Docs ADR 0096).
 */
data class RadioList(
    val issued: Long,
    val regions: List<RadioRegion>,
    /** What the network asks of app builds (Android, iOS), Docs ADR 0096; `null` asks nothing. */
    val app: UpdateRule? = null,
    /** What the network asks of web app builds, Docs ADR 0096; `null` asks nothing. */
    val web: UpdateRule? = null,
) {
    /** The first region whose area contains the point, `null` outside all regions. The point never leaves the phone. */
    fun regionAt(lat: Double, lon: Double): RadioRegion? = regions.firstOrNull { it.contains(lat, lon) }

    companion object {
        /** Meshtastic modem presets a region may name; a region with another preset is skipped (ANMP "Radio list"). */
        val PRESETS = setOf(
            "LONG_FAST", "LONG_SLOW", "LONG_MODERATE", "LONG_TURBO", "MEDIUM_FAST", "MEDIUM_SLOW",
            "SHORT_FAST", "SHORT_SLOW", "SHORT_TURBO",
        )

        /**
         * Reads the text of a radio list that ANDP accepted. Unknown fields are ignored; a region or community that is
         * invalid or names something this app does not know is skipped, the rest counts.
         */
        fun parse(text: String): RadioList {
            val obj = coreJson.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("radio list is not an object")
            val issued = (obj["issued"] as? JsonPrimitive)?.longOrNull ?: throw IllegalArgumentException("radio list without issued")
            val regions = (obj["regions"] as? JsonArray).orEmpty().mapNotNull { RadioRegion.read(it) }
            return RadioList(issued, regions, UpdateRule.read(obj["app"]), UpdateRule.read(obj["web"]))
        }
    }
}

/** One region: [area] as `[lat, lon]` points of a simple polygon, [preset] of channel 0, communities inside it. */
data class RadioRegion(
    val name: String,
    val area: List<Pair<Double, Double>>,
    val preset: String,
    /** Whether ANMP goes out on channel 0 here (ADR 0054 §1); otherwise only on the channel `anynear`. */
    val channel0: Boolean,
    val communities: List<RadioCommunity>,
) {
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
            val communities = (o["communities"] as? JsonArray).orEmpty().mapNotNull { RadioCommunity.read(it) }
            return RadioRegion(name, area, preset, channel0, communities)
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

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
