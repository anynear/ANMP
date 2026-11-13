package app.anynear.anmp

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.cbor.CborArray
import kotlinx.serialization.cbor.CborLabel
import kotlin.js.JsExport

/**
 * One radio channel for everyone and hourly tags (ADR 0051). All anynear radio traffic runs on the channel
 * [AnmpInfo.RADIO_CHANNEL_NAME] with a public key; only the ANMP content is confidential. Over radio the header
 * carries no fixed tags: [senderTag] instead of the installation tag, [routeTag] of the recipient device or circle
 * instead of `to`. Spec: `spec/anmp.md`, "One radio channel".
 */
@JsExport
object AnmpRadio {
    /** Length of an hour in seconds; tags change with it. */
    const val HOUR_SECONDS = 3600L

    /** Pre-shared key of the radio channel: HKDF(UTF-8 of the channel name, `anmp/v1/radio-channel`). Public. */
    @JsExport.Ignore
    suspend fun channelPsk(): ByteArray = AnmpCrypto.hkdf(AnmpInfo.RADIO_CHANNEL_NAME.encodeToByteArray(), AnmpInfo.RADIO_CHANNEL)

    /** Hour number of a Unix time in seconds. */
    fun hourOf(unixSeconds: Long): Long = unixSeconds.floorDiv(HOUR_SECONDS)

    /** Hours whose tags a receiver accepts: the previous, the current and the next (clocks that are a bit off). */
    fun acceptedHours(unixSeconds: Long): LongArray = hourOf(unixSeconds).let { longArrayOf(it - 1, it, it + 1) }

    /** Radio secret of an installation: HKDF(device ID, `anmp/v1/radio-secret`). */
    @JsExport.Ignore
    suspend fun deviceSecret(deviceId: ByteArray): ByteArray {
        require(deviceId.size == Anmp.DEVICE_BYTES) { "device ID must have ${Anmp.DEVICE_BYTES} bytes" }
        return AnmpCrypto.hkdf(deviceId, AnmpInfo.RADIO_SECRET)
    }

    /** Radio secret of a circle: HKDF(circle secret, `anmp/v1/radio-secret`). */
    @JsExport.Ignore
    suspend fun circleSecret(circleSecret: ByteArray): ByteArray {
        require(circleSecret.size == AnmpCircleKeys.SECRET_BYTES) { "circle secret must have ${AnmpCircleKeys.SECRET_BYTES} bytes" }
        return AnmpCrypto.hkdf(circleSecret, AnmpInfo.RADIO_SECRET)
    }

    /** Hours of one chain epoch (about 170 days, ADR 0051 §5a). */
    const val EPOCH_HOURS = 4096L

    /** Hours a patron may reach back: 30 days. */
    const val WINDOW_HOURS = 720L

    /** Length of a chain value. */
    const val CHAIN_BYTES = 16

    /** Most pickup windows in one `pickup` packet (radio budget). */
    const val PICKUP_MAX_WINDOWS = 3

    fun epochOf(hour: Long): Long = hour.floorDiv(EPOCH_HOURS)

    fun epochStart(epoch: Long): Long = epoch * EPOCH_HOURS

    fun epochEnd(epoch: Long): Long = epochStart(epoch) + EPOCH_HOURS - 1

    /** One chain step: SHA-256(`anmp/v1/route-chain\n` || x)[0..16]. */
    @JsExport.Ignore
    suspend fun chainStep(x: ByteArray): ByteArray = AnmpCrypto.sha256((AnmpInfo.ROUTE_CHAIN + "\n").encodeToByteArray() + x).copyOf(CHAIN_BYTES)

    /** Forward chain value at the start of [epoch]: HKDF(radio secret, `anmp/v1/route-fwd\n` epoch)[0..16]. */
    @JsExport.Ignore
    suspend fun forwardStart(radioSecret: ByteArray, epoch: Long): ByteArray =
        AnmpCrypto.hkdf(radioSecret, AnmpInfo.ROUTE_FWD + "\n" + epoch).copyOf(CHAIN_BYTES)

    /** Backward chain value at the end of [epoch]: HKDF(radio secret, `anmp/v1/route-back\n` epoch)[0..16]. */
    @JsExport.Ignore
    suspend fun backwardEnd(radioSecret: ByteArray, epoch: Long): ByteArray =
        AnmpCrypto.hkdf(radioSecret, AnmpInfo.ROUTE_BACK + "\n" + epoch).copyOf(CHAIN_BYTES)

    /** Forward chain value f(hour): from the epoch start, one step per hour. */
    @JsExport.Ignore
    suspend fun forwardAt(radioSecret: ByteArray, hour: Long): ByteArray {
        val e = epochOf(hour)
        var f = forwardStart(radioSecret, e)
        repeat((hour - epochStart(e)).toInt()) { f = chainStep(f) }
        return f
    }

    /** Backward chain value b(hour): from the epoch end, one step per hour back. */
    @JsExport.Ignore
    suspend fun backwardAt(radioSecret: ByteArray, hour: Long): ByteArray {
        val e = epochOf(hour)
        var b = backwardEnd(radioSecret, e)
        repeat((epochEnd(e) - hour).toInt()) { b = chainStep(b) }
        return b
    }

    /** Route tag from both chain values of an hour: SHA-256(`anmp/v1/route-tag\n` || f || b)[0..8]. */
    @JsExport.Ignore
    suspend fun tagOf(f: ByteArray, b: ByteArray): ByteArray =
        AnmpCrypto.sha256((AnmpInfo.ROUTE_TAG + "\n").encodeToByteArray() + f + b).copyOf(Anmp.ROUTE_TAG_BYTES)

    /**
     * Route tag of a device or circle in [hour] (ADR 0051 §5a): from the forward and the backward chain of its epoch.
     * Whoever holds f(from) and b(to) computes the tags from `from` to `to` and no others.
     */
    @JsExport.Ignore
    suspend fun routeTag(radioSecret: ByteArray, hour: Long): ByteArray = tagOf(forwardAt(radioSecret, hour), backwardAt(radioSecret, hour))

    /** Hours a window of [epoch] covers for a pickup up to [hour]: the last 30 days, cut at the epoch borders. */
    fun windowRange(epoch: Long, hour: Long): LongRange = maxOf(hour - WINDOW_HOURS + 1, epochStart(epoch))..minOf(hour, epochEnd(epoch))

    /** Pickup windows of one target for the last 30 days up to [hour]: one, or two across an epoch border. */
    @JsExport.Ignore
    suspend fun pickupWindows(radioSecret: ByteArray, hour: Long): List<AnmpPickupWindow> =
        listOf(epochOf(hour - WINDOW_HOURS + 1), epochOf(hour)).distinct().map { e ->
            val r = windowRange(e, hour)
            AnmpPickupWindow(e, forwardAt(radioSecret, r.first), backwardAt(radioSecret, r.last))
        }

    /** All (hour, route tag) a window gives for a pickup up to [hour]. */
    @JsExport.Ignore
    suspend fun windowTags(window: AnmpPickupWindow, hour: Long): List<Pair<Long, ByteArray>> {
        val r = windowRange(window.epoch, hour)
        if (r.isEmpty() || window.f.size != CHAIN_BYTES || window.b.size != CHAIN_BYTES) return emptyList()
        val n = (r.last - r.first + 1).toInt()
        val backs = ArrayList<ByteArray>(n)
        var b = window.b
        repeat(n) { i -> if (i > 0) b = chainStep(b); backs += b }
        var f = window.f
        return (0 until n).map { i ->
            if (i > 0) f = chainStep(f)
            (r.first + i) to tagOf(f, backs[n - 1 - i])
        }
    }

    /**
     * `pickup` for one patron (ADR 0051 §5a): [windows] (at most [PICKUP_MAX_WINDOWS]) sealed to the patron's one-time key
     * [patronKey] with the asker's one-time key [mine]. Header: sender tag, `to` = first 8 bytes of the patron key,
     * `senderKey` = public key of [mine], `hour`.
     */
    @JsExport.Ignore
    suspend fun pickupPacket(senderTag: ByteArray, mine: AnmpEphemeral, patronKey: ByteArray, hour: Long, windows: List<AnmpPickupWindow>): ByteArray {
        require(patronKey.size == Anmp.KEY_BYTES) { "patron key must have ${Anmp.KEY_BYTES} bytes" }
        return pickupPacket(senderTag, mine, patronKey, hour, windows, AnmpAead.of(mine.sharedKey(patronKey)))
    }

    /** Like [pickupPacket] with a given [cipher] (test vectors with a fixed nonce). */
    internal suspend fun pickupPacket(senderTag: ByteArray, mine: AnmpEphemeral, patronKey: ByteArray, hour: Long, windows: List<AnmpPickupWindow>, cipher: AnmpCipher): ByteArray {
        require(windows.size in 1..PICKUP_MAX_WINDOWS) { "1 to $PICKUP_MAX_WINDOWS windows" }
        require(patronKey.size == Anmp.KEY_BYTES) { "patron key must have ${Anmp.KEY_BYTES} bytes" }
        val header = AnmpHeader(AnmpKind.PICKUP, senderTag, patronKey.copyOf(Anmp.TAG_BYTES), senderKey = mine.publicKey, hour = hour)
        val payload = Anmp.cbor.encodeToByteArray(ListSerializer(AnmpPickupWindow.serializer()), windows)
        return Anmp.encodeSealed(header, payload, AnmpInfo.PICKUP, cipher)
    }

    /** Opens a `pickup` meant for the patron key [mine]; `null` if it is for another patron or invalid. */
    @JsExport.Ignore
    suspend fun openPickup(packet: ByteArray, mine: AnmpEphemeral): AnmpPickup? {
        val h = Anmp.peek(packet) ?: return null
        if (h.kind != AnmpKind.PICKUP || h.hour == null) return null
        if (h.to?.contentEquals(mine.publicKey.copyOf(Anmp.TAG_BYTES)) != true) return null
        val asker = h.senderKey ?: return null
        val (_, plain) = Anmp.openSealed(packet, AnmpInfo.PICKUP, AnmpAead.of(mine.sharedKey(asker))) ?: return null
        val windows = runCatching { Anmp.cbor.decodeFromByteArray(ListSerializer(AnmpPickupWindow.serializer()), plain) }.getOrNull() ?: return null
        if (windows.isEmpty() || windows.size > PICKUP_MAX_WINDOWS) return null
        return AnmpPickup(h.hour, windows)
    }

    /** Sender tag of a device in [hour]: first 8 bytes of HKDF(radio secret of the device, `anmp/v1/sender-tag\n` hour). */
    @JsExport.Ignore
    suspend fun senderTag(deviceRadioSecret: ByteArray, hour: Long): ByteArray =
        AnmpCrypto.hkdf(deviceRadioSecret, AnmpInfo.SENDER_TAG + "\n" + hour).copyOf(Anmp.TAG_BYTES)

    /**
     * `true` for a packet in the public form that goes into the radio inbox (ADR 0051): a valid ANMP packet of at most
     * [Anmp.RADIO_MAX_BYTES] bytes with a route tag, not open, without `to` and without `senderKey`.
     */
    fun isPublicForm(packet: ByteArray): Boolean {
        if (!Anmp.fitsRadio(packet)) return false
        val h = Anmp.peek(packet) ?: return false
        return !h.kind.open && h.route != null && h.to == null && h.senderKey == null
    }

    /** Route tag shortened for an [AnmpKind.ASK]. */
    fun askTag(routeTag: ByteArray): ByteArray = routeTag.copyOf(Anmp.ASK_TAG_BYTES)

    /** `true` if [routeTag] matches the shortened [askTag]. */
    fun matchesAsk(routeTag: ByteArray, askTag: ByteArray): Boolean =
        askTag.size == Anmp.ASK_TAG_BYTES && routeTag.size >= Anmp.ASK_TAG_BYTES && (0 until Anmp.ASK_TAG_BYTES).all { routeTag[it] == askTag[it] }
}

/** Pickup keys of one target and epoch (ADR 0051 §5a): f(from) and b(to) of [AnmpRadio.windowRange]. CBOR `[epoch, f, b]`. */
@CborArray
@Serializable
class AnmpPickupWindow(
    @CborLabel(0) val epoch: Long,
    @CborLabel(1) val f: ByteArray,
    @CborLabel(2) val b: ByteArray,
)

/** Opened `pickup`: windows up to [hour]. */
class AnmpPickup(val hour: Long, val windows: List<AnmpPickupWindow>)

/** One-time X25519 key of an asker or a patron (ADR 0051 §5a). */
class AnmpEphemeral private constructor(private val privateRaw: ByteArray, val publicKey: ByteArray) {
    /** HKDF(X25519(own, [peerPublic]), `anmp/v1/pickup`). */
    suspend fun sharedKey(peerPublic: ByteArray): ByteArray {
        require(peerPublic.size == Anmp.KEY_BYTES) { "peer key must have ${Anmp.KEY_BYTES} bytes" }
        return AnmpCrypto.hkdf(AnmpCrypto.xShared(AnmpCrypto.xPrivate(privateRaw), peerPublic), AnmpInfo.PICKUP)
    }

    companion object {
        suspend fun generate(): AnmpEphemeral = of(AnmpCrypto.random(Anmp.KEY_BYTES))

        /** From a fixed private key (test vectors). */
        suspend fun of(privateRaw: ByteArray): AnmpEphemeral = AnmpEphemeral(privateRaw.copyOf(), AnmpCrypto.xPublicRaw(AnmpCrypto.xPrivate(privateRaw)))
    }
}
