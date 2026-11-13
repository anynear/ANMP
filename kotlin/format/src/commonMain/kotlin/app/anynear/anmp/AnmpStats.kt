package app.anynear.anmp

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel
import kotlin.js.JsExport

/**
 * Body of a daily stats report (ADR 0045), CBOR with integer keys. Counts are capped ([AnmpStats.MAX_RADIOS] …);
 * [AnmpStats.body] caps them, [AnmpStats.isValid] checks them.
 */
@JsExport
@Serializable
class AnmpStatsBody(
    /** UTC day `YYYY-MM-DD`. */
    @CborLabel(1) val day: String,
    /** Public Ed25519 key of the daily stats key of this device, 32 bytes. */
    @CborLabel(2) val key: ByteArray,
    /** Person marker of the day, [AnmpStats.MARKER_BYTES]. */
    @CborLabel(3) val person: ByteArray,
    /** `android`, `ios` or `web`. */
    @CborLabel(4) val platform: String,
    /** App version `X.Y.Z`; anything else is counted as other. */
    @CborLabel(5) val version: String,
    /** Radios paired on this device, capped at [AnmpStats.MAX_RADIOS]. */
    @CborLabel(6) val radios: Int,
    /** Own and shared channels on this device, capped at [AnmpStats.MAX_CHANNELS]. */
    @CborLabel(7) val channels: Int,
    /** IDs of curated channels subscribed on this device, e.g. `nina`. */
    @CborLabel(8) val curated: List<String>,
    /** Devices of the person, 1 … [AnmpStats.MAX_DEVICES]. */
    @CborLabel(9) val devices: Int,
    /** Circles of the person, capped at [AnmpStats.MAX_CIRCLES]. */
    @CborLabel(10) val circles: Int,
)

/**
 * Switch report (ADR 0045): sent once by the device on which the person switched the stats [on] or off, nothing else.
 * Same envelope and signature as a daily report; field 11 tells the two apart.
 */
@JsExport
@Serializable
class AnmpStatsSwitch(
    /** UTC day `YYYY-MM-DD`. */
    @CborLabel(1) val day: String,
    /** Public Ed25519 key of the daily stats key of this device, 32 bytes. */
    @CborLabel(2) val key: ByteArray,
    /** `android`, `ios` or `web`. */
    @CborLabel(4) val platform: String,
    /** `true`: switched on, `false`: switched off. */
    @CborLabel(11) val on: Boolean,
)

/**
 * Daily usage stats (ADR 0045): every device sends one signed report a day to one relay chosen by the person
 * marker. Keys and marker are derived from the day, so nothing links two days.
 */
@JsExport
object AnmpStats {
    const val MARKER_BYTES = 16
    const val MAX_RADIOS = 4
    const val MAX_CHANNELS = 4
    const val MAX_DEVICES = 6
    const val MAX_CIRCLES = 6
    const val MAX_CURATED = 8
    const val MAX_ID_CHARS = 32
    const val MAX_VERSION_CHARS = 32

    /** Largest report a relay accepts. */
    const val MAX_REPORT_BYTES = 512

    const val DAY_SECONDS = 86_400L

    val PLATFORMS = listOf("android", "ios", "web")

    /** UTC day `YYYY-MM-DD` of a Unix time in seconds. */
    fun dayOf(unixSeconds: Long): String {
        // Civil date from days since 1970-01-01 (H. Hinnant, days_from_civil inverse).
        val z = unixSeconds.floorDiv(DAY_SECONDS) + 719_468
        val era = z.floorDiv(146_097)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return y.toString().padStart(4, '0') + "-" + m.toString().padStart(2, '0') + "-" + d.toString().padStart(2, '0')
    }

    /** Ed25519 seed of the stats key of [day]: HKDF(private device key, `anmp/v1/stats\n` day). */
    @JsExport.Ignore
    suspend fun keySeed(deviceSeed: ByteArray, day: String): ByteArray = AnmpCrypto.hkdf(deviceSeed, AnmpInfo.STATS + "\n" + day)

    /** Person marker of [day]: first 16 bytes of HKDF(private person key, `anmp/v1/stats-person\n` day). */
    @JsExport.Ignore
    suspend fun marker(personSeed: ByteArray, day: String): ByteArray =
        AnmpCrypto.hkdf(personSeed, AnmpInfo.STATS_PERSON + "\n" + day).copyOf(MARKER_BYTES)

    /** Index of the relay for a person marker among [count] active relays, sorted by node key. */
    fun relayIndex(marker: ByteArray, count: Int): Int {
        require(marker.size >= 4 && count > 0) { "marker and relay count required" }
        val n = ((marker[0].toLong() and 0xff) shl 24) or ((marker[1].toLong() and 0xff) shl 16) or
            ((marker[2].toLong() and 0xff) shl 8) or (marker[3].toLong() and 0xff)
        return (n % count).toInt()
    }

    /** `X.Y.Z` of a version string (e.g. `0.4.2` from `0.4.2` or `0.1.0.ab12`), or `other`. */
    fun versionOf(raw: String?): String {
        val m = raw?.let { Regex("^(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})(?:\\D.*)?$").find(it.trim()) } ?: return "other"
        return m.groupValues.drop(1).joinToString(".")
    }

    /** Body with counts capped; [key] and [person] from [keySeed] and [marker]. */
    @JsExport.Ignore
    suspend fun body(
        deviceSeed: ByteArray,
        personSeed: ByteArray,
        unixSeconds: Long,
        platform: String,
        version: String,
        radios: Int,
        channels: Int,
        curated: List<String>,
        devices: Int,
        circles: Int,
    ): AnmpStatsBody {
        val day = dayOf(unixSeconds)
        val key = AnmpSpaceKey.fromPrivateKey(keySeed(deviceSeed, day))
        return AnmpStatsBody(
            day = day,
            key = key.publicKey,
            person = marker(personSeed, day),
            platform = platform,
            version = versionOf(version),
            radios = radios.coerceIn(0, MAX_RADIOS),
            channels = channels.coerceIn(0, MAX_CHANNELS),
            curated = curated.distinct().sorted().take(MAX_CURATED),
            devices = devices.coerceIn(1, MAX_DEVICES),
            circles = circles.coerceIn(0, MAX_CIRCLES),
        )
    }

    /** `true` if the body keeps the limits of ADR 0045. */
    fun isValid(b: AnmpStatsBody): Boolean {
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(b.day)) return false
        if (b.key.size != Anmp.KEY_BYTES || b.person.size != MARKER_BYTES) return false
        if (b.platform !in PLATFORMS || b.version.isEmpty() || b.version.length > MAX_VERSION_CHARS) return false
        if (b.radios !in 0..MAX_RADIOS || b.channels !in 0..MAX_CHANNELS) return false
        if (b.devices !in 1..MAX_DEVICES || b.circles !in 0..MAX_CIRCLES) return false
        if (b.curated.size > MAX_CURATED || b.curated.any { it.isEmpty() || it.length > MAX_ID_CHARS }) return false
        return true
    }

    /** `anmp/v1/stats-sig\n` ‖ body bytes. */
    fun signingBytes(bodyBytes: ByteArray): ByteArray = (AnmpInfo.STATS_SIG + "\n").encodeToByteArray() + bodyBytes

    /**
     * Report = CBOR `{0: body bytes, 1: sig}`, sig = Ed25519 with the stats key of the body's day over
     * [signingBytes]. The public key is field 2 of the body.
     */
    @JsExport.Ignore
    suspend fun sign(body: AnmpStatsBody, deviceSeed: ByteArray): ByteArray {
        require(isValid(body)) { "invalid stats body" }
        val key = AnmpSpaceKey.fromPrivateKey(keySeed(deviceSeed, body.day))
        require(key.publicKey.contentEquals(body.key)) { "stats key does not match the body" }
        val b = Anmp.cbor.encodeToByteArray(AnmpStatsBody.serializer(), body)
        val report = Anmp.cbor.encodeToByteArray(Signed.serializer(), Signed(b, key.sign(signingBytes(b))))
        check(report.size <= MAX_REPORT_BYTES) { "stats report too large" }
        return report
    }

    /** Switch report of [unixSeconds]'s day, signed with that day's stats key. */
    @JsExport.Ignore
    suspend fun signSwitch(deviceSeed: ByteArray, unixSeconds: Long, platform: String, on: Boolean): ByteArray {
        require(platform in PLATFORMS) { "unknown platform" }
        val day = dayOf(unixSeconds)
        val key = AnmpSpaceKey.fromPrivateKey(keySeed(deviceSeed, day))
        val b = Anmp.cbor.encodeToByteArray(AnmpStatsSwitch.serializer(), AnmpStatsSwitch(day, key.publicKey, platform, on))
        return Anmp.cbor.encodeToByteArray(Signed.serializer(), Signed(b, key.sign(signingBytes(b))))
    }

    /** Checks a switch report; `null` if invalid or a daily report. */
    @JsExport.Ignore
    suspend fun verifySwitch(report: ByteArray): AnmpStatsSwitch? {
        if (report.size > MAX_REPORT_BYTES) return null
        val s = runCatching { Anmp.cbor.decodeFromByteArray(Signed.serializer(), report) }.getOrNull() ?: return null
        if (s.sig.size != Anmp.SIGNATURE_BYTES) return null
        val body = runCatching { Anmp.cbor.decodeFromByteArray(AnmpStatsSwitch.serializer(), s.body) }.getOrNull() ?: return null
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(body.day) || body.key.size != Anmp.KEY_BYTES || body.platform !in PLATFORMS) return null
        if (!AnmpCrypto.edVerify(body.key, signingBytes(s.body), s.sig)) return null
        return body
    }

    /** Checks a report: size, signature and body. `null` if invalid. */
    @JsExport.Ignore
    suspend fun verify(report: ByteArray): AnmpStatsBody? {
        if (report.size > MAX_REPORT_BYTES) return null
        val s = runCatching { Anmp.cbor.decodeFromByteArray(Signed.serializer(), report) }.getOrNull() ?: return null
        if (s.sig.size != Anmp.SIGNATURE_BYTES) return null
        val body = runCatching { Anmp.cbor.decodeFromByteArray(AnmpStatsBody.serializer(), s.body) }.getOrNull() ?: return null
        if (!isValid(body)) return null
        if (!AnmpCrypto.edVerify(body.key, signingBytes(s.body), s.sig)) return null
        return body
    }

    @Serializable
    internal class Signed(
        @CborLabel(0) val body: ByteArray,
        @CborLabel(1) val sig: ByteArray,
    )
}
