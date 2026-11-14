package app.anynear.anmp.core

import app.anynear.andp.B64
import app.anynear.anmp.Anmp
import app.anynear.anmp.AnmpAlive
import app.anynear.anmp.AnmpAliveState
import app.anynear.anmp.AnmpArrived
import app.anynear.anmp.AnmpCare
import app.anynear.anmp.AnmpContent
import app.anynear.anmp.AnmpKind
import app.anynear.anmp.AnmpRadio
import app.anynear.anmp.AnmpVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the radio way reads from the core. */
internal interface RadioCore {
    val keys: RadioKeys
    val circles: List<CircleState>
    val members: List<MemberState>
    val hasInbox: Boolean

    /** Meshtastic node this device uses now; 0 if none. */
    val ownNode: Long
    val store: KeyValueStore

    fun nowMillis(): Long

    fun personOf(deviceId: String): String

    /** [deviceId] uses the radio [node]; told inside an opened, authenticated packet (ADR 0058). */
    suspend fun learnNode(deviceId: String, node: Long)

    suspend fun emit(event: CoreEvent)

    /** Checked withdrawal (ADR 0068): only if [fromDevice] belongs to the author's person. */
    fun deleteEvent(conversation: Conversation, fromDevice: String, content: AnmpContent): CoreEvent.Deleted?

    /**
     * Sign of life [alive] heard over radio in [circleId] (ADR 0083), taken like one from the internet. `true` if it is
     * valid and of another person, so the radio way confirms it.
     */
    suspend fun onAlive(circleId: String, fromDevice: String, mid: ByteArray?, ts: Long, alive: AnmpAlive): Boolean

    /** A helper's answer [care] heard over radio in [circleId] (ADR 0083), taken like one from the internet. */
    suspend fun onCare(circleId: String, fromDevice: String, ts: Long, care: AnmpCare)

    /** Delivery receipt over radio from [fromDevice] for [mids] (b64u), e.g. for own signs of life. */
    suspend fun onReceipt(fromDevice: String, mids: List<String>)

    suspend fun uplink(packet: ByteArray): Boolean

    suspend fun poll(wanted: (ByteArray) -> Boolean): List<RadioInboxPacket>

    fun log(line: String)
}

/**
 * The radio way of the core (ADR 0051, 0057): packets in the public form over [port], tag tables from the own circles
 * and known devices, sorting by kind, sender and circle, signature check, receipts and patrons. Reports what arrives as
 * [CoreEvent] ([CoreEvent.RadioMessage], [CoreEvent.Reaction], [CoreEvent.Receipt] with `viaRadio`).
 */
internal class RadioWay(
    private val scope: CoroutineScope,
    private val core: RadioCore,
    private val port: RadioPort,
    private val host: RadioHost,
    timings: RadioTimings,
) {
    private val relay = RadioRelay(scope, object : RadioRelay.Env {
        override fun nowMillis(): Long = core.nowMillis()

        override fun radioReady(): Boolean = port.ready.value

        override fun online(): Boolean = host.online()

        override fun inboxReady(): Boolean = core.hasInbox

        override fun mayServeOthers(): Boolean = host.mayServeOthers()

        override suspend fun isOwnRoute(route: ByteArray, atSeconds: Long): Boolean = table(atSeconds).isOwn(route)

        override suspend fun uplink(packet: ByteArray): Boolean = core.uplink(packet)

        override suspend fun pollInbox(wanted: (ByteArray) -> Boolean) = core.poll(wanted)

        override suspend fun deliverOwn(packet: ByteArray, atSeconds: Long) = receive(RadioFrame(packet, atMillis = core.nowMillis()), inboxSeconds = atSeconds)

        override suspend fun askPacket(since: Long): ByteArray = core.keys.askPacket(since)

        override suspend fun patronPacket(key: ByteArray): ByteArray = core.keys.patronPacket(key)

        override suspend fun pickupPackets(patronKey: ByteArray): List<ByteArray> =
            core.keys.pickupPackets(core.circles.map { it.secret }, patronKey)

        override suspend fun broadcast(packet: ByteArray, hopLimit: Int?): Boolean = send(packet, hopLimit) != null

        override fun log(text: String) = core.log(text)
    }, timings)

    /** A patron answered the last request: messages reach this device over radio (ADR 0051). */
    val servedByPatron: StateFlow<Boolean> get() = relay.servedByPatron

    private val queue = RadioQueue(
        scope, core.store, port, host, timings,
        packet = { textPacket(it.circleId, it.deviceId, it.mid, it.ts, it.text, it.replyTo, it.replyFrom) },
        report = { mid, packetId, state -> core.emit(CoreEvent.RadioSend(mid, packetId, state)) },
        nodesOf = ::nodesOf,
        log = core::log,
        now = core::nowMillis,
    )

    /**
     * Radio node numbers of a queue entry's target (ADR 0058 field 10): the target device, or the other members of the
     * circle. Compared as the radio network names them (32 bits).
     */
    private fun nodesOf(entry: RadioQueued): Set<Int> {
        val members = core.members
        val own = core.keys.deviceId
        val ofTarget = if (entry.circleId != null) {
            members.filter { it.circleId == entry.circleId && it.deviceId != own }
        } else {
            members.filter { it.deviceId == entry.deviceId }
        }
        return ofTarget.map { it.node.toInt() }.filter { it != 0 && it != core.ownNode.toInt() }.toSet()
    }

    /** Takes message [mid] out of the radio queue. */
    suspend fun cancelText(mid: String): Boolean = queue.cancel(mid)

    /** A radio check to [node] was answered (ADR 0053, 0090): waiting messages for it may go again. */
    suspend fun radioHeard(node: Int) = queue.onHeard(node, null, HeardBy.CHECK)

    /** Queues an own message for the radio, kept over restarts; states come as [CoreEvent.RadioSend]. */
    suspend fun queueText(entry: RadioQueued) = queue.add(entry)

    fun start() {
        queue.start()
        scope.launch { receipts.start() }
        scope.launch {
            port.incoming.collect { frame ->
                try {
                    receive(frame)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    core.log("radio: packet failed ${e.message}")
                }
            }
        }
        relay.start()
    }

    /** Ask at once if the situation calls for it (app opened). */
    fun onForeground() = relay.onForeground()

    private suspend fun send(packet: ByteArray, hopLimit: Int? = null): RadioSent? {
        if (!port.ready.value) return null
        return try {
            port.broadcast(packet, hopLimit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // --- Sending ------------------------------------------------------------------------------------

    private fun circle(circleId: String): CircleState? = core.circles.firstOrNull { it.id == circleId }

    private fun pinnedKey(deviceId: String): String? = core.members.firstOrNull { it.deviceId == deviceId }?.publicKey

    private fun check(kind: AnmpKind) = check(kind.radio) { "${kind.conv} may not go over radio" }

    /**
     * Radio packet of an own message [mid] (b64u) with [text] at [tsMillis] in the circle [circleId], or to the device
     * [deviceId] if [circleId] is `null`. `null` if the circle or device is unknown or the packet is too large.
     */
    suspend fun textPacket(
        circleId: String?, deviceId: String?, mid: String, tsMillis: Long, text: String, replyTo: String? = null, replyFrom: String? = null,
    ): ByteArray? {
        val id = midBytes(mid) ?: return null
        val ref = replyTo?.let(::midBytes)
        val refFrom = replyFrom?.let { runCatching { B64.decode(it) }.getOrNull()?.takeIf { b -> b.size == Anmp.TAG_BYTES } }
        suspend fun packet(node: Long?, reply: Boolean): ByteArray? {
            val content = AnmpContent(
                id = id, ts = tsMillis / 1000, text = text, node = node,
                ref = ref.takeIf { reply && refFrom != null }, refFrom = refFrom.takeIf { reply && ref != null },
            )
            return if (circleId != null) {
                check(AnmpKind.CIRCLE)
                core.keys.circlePacket(AnmpKind.CIRCLE, circle(circleId) ?: return null, content)
            } else {
                check(AnmpKind.DIRECT)
                val device = deviceId ?: return null
                core.keys.directPacket(AnmpKind.DIRECT, device, pinnedKey(device) ?: return null, content)
            }
        }
        // The own radio goes along when the text leaves room, so others learn it without internet (ADR 0058); text wins.
        // The answer reference (ADR 0092) likewise, before the radio: the reader sees what it answers.
        val node = core.ownNode.takeIf { it != 0L }
        val reply = ref != null && refFrom != null
        val tries = listOf(node to reply, null to reply, node to false, null to false).distinct()
            .filter { (n, r) -> (n == null || node != null) && (!r || reply) }
        for ((n, r) in tries) packet(n, r)?.takeIf(Anmp::fitsRadio)?.let { return it }
        return null
    }

    /** Sends [packet] (from [textPacket]) to everyone on the radio channel, so others can relay it. */
    suspend fun sendPacket(packet: ByteArray): RadioSent? = send(packet)

    /**
     * Own reaction [emoji] on message [targetMid] of the installation with tag [targetTag] (b64u) in [circleId], or to
     * the device [deviceId]. Without queue: if the radio is not there, it goes only over the internet.
     */
    suspend fun react(circleId: String?, deviceId: String?, targetTag: String, targetMid: String, emoji: String, tsMillis: Long): RadioSent? {
        check(AnmpKind.REACT)
        val ref = midBytes(targetMid) ?: return null
        val refFrom = runCatching { B64.decode(targetTag) }.getOrNull() ?: return null
        val content = AnmpContent(id = Anmp.newMessageId(), ts = tsMillis / 1000, ref = ref, refFrom = refFrom, emoji = emoji)
        val packet = if (circleId != null) {
            core.keys.circlePacket(AnmpKind.REACT, circle(circleId) ?: return null, content)
        } else {
            val device = deviceId ?: return null
            core.keys.directPacket(AnmpKind.REACT, device, pinnedKey(device) ?: return null, content)
        }
        return send(packet)
    }

    /** Own withdrawal of message [targetMid] (ADR 0068), like [react]: without queue, otherwise only over the internet. */
    suspend fun delete(circleId: String?, deviceId: String?, targetTag: String, targetMid: String, tsMillis: Long): RadioSent? {
        check(AnmpKind.DELETE)
        val ref = midBytes(targetMid) ?: return null
        val refFrom = runCatching { B64.decode(targetTag) }.getOrNull() ?: return null
        val content = AnmpContent(id = Anmp.newMessageId(), ts = tsMillis / 1000, ref = ref, refFrom = refFrom)
        val packet = if (circleId != null) {
            core.keys.circlePacket(AnmpKind.DELETE, circle(circleId) ?: return null, content)
        } else {
            val device = deviceId ?: return null
            core.keys.directPacket(AnmpKind.DELETE, device, pinnedKey(device) ?: return null, content)
        }
        return send(packet)
    }

    /**
     * Own sign of life [alive] in [circleId] over radio (ADR 0083 §7), with the message ID [mid] and time [tsSeconds]
     * of the internet packet, so readers merge both. Signed like every circle packet, without queue: if the radio is not
     * there or it does not fit, it goes only over the internet. When the whole plan does not fit, the label stays
     * behind (readers keep the one they know). Confirmations (ok, moving) go at most once per [ALIVE_CONFIRM_GAP_MILLIS]
     * and plan (radio budget); start, missed, battery, end and timeout always.
     */
    suspend fun alive(circleId: String, mid: ByteArray, tsSeconds: Long, alive: AnmpAlive): RadioSent? {
        check(AnmpKind.ALIVE)
        val circle = circle(circleId) ?: return null
        val confirming = alive.state == AnmpAliveState.OK || alive.state == AnmpAliveState.MOVING
        val plan = hex(alive.plan)
        val now = core.nowMillis()
        if (confirming && aliveLock.withLock { aliveSent[plan]?.let { now - it < ALIVE_CONFIRM_GAP_MILLIS } == true }) return null
        val bare = AnmpAlive(
            alive.plan, alive.state, alive.start, alive.until, alive.due, alive.hint, alive.alarm, alive.quiet, alive.motion, null, alive.battery,
        )
        val packet = listOfNotNull(alive, bare.takeIf { alive.label != null }).firstNotNullOfOrNull { a ->
            core.keys.circlePacket(AnmpKind.ALIVE, circle, AnmpContent(id = mid, ts = tsSeconds, alive = a)).takeIf(Anmp::fitsRadio)
        } ?: return null.also { core.log("radio: sign of life does not fit") }
        val sent = send(packet) ?: return null
        aliveLock.withLock {
            aliveSent[plan] = now
            while (aliveSent.size > MAX_PLANS) aliveSent.remove(aliveSent.keys.first())
        }
        return sent
    }

    /** Own answer [care] to a sign of life in [circleId] over radio (ADR 0083), like [alive] with the internet packet's ID. */
    suspend fun care(circleId: String, mid: ByteArray, tsSeconds: Long, care: AnmpCare): RadioSent? {
        check(AnmpKind.CARE)
        val circle = circle(circleId) ?: return null
        val packet = core.keys.circlePacket(AnmpKind.CARE, circle, AnmpContent(id = mid, ts = tsSeconds, care = care)).takeIf(Anmp::fitsRadio) ?: return null
        return send(packet)
    }

    private val aliveLock = Mutex()
    private val aliveSent = LinkedHashMap<String, Long>()

    /**
     * Read receipt for the messages [mids] (b64u) of the device [deviceId]: as many as fit into one radio packet, the
     * newest (last). `null` if nothing went out.
     */
    suspend fun read(deviceId: String, mids: List<String>): RadioSent? {
        check(AnmpKind.READ)
        val key = pinnedKey(deviceId) ?: return null
        val header = RadioKeys.budgetHeader(AnmpKind.READ, null)
        var batch = mids.mapNotNull(::midBytes)
        fun content() = AnmpContent(id = Anmp.newMessageId(), ts = core.nowMillis() / 1000, ids = batch)
        while (batch.isNotEmpty() && !Anmp.fitsRadioSize(Anmp.size(header, content(), signed = false))) batch = batch.drop(1)
        if (batch.isEmpty()) return null
        return send(core.keys.directPacket(AnmpKind.READ, deviceId, key, content()))
    }

    /**
     * Delivery receipts over radio for messages that came over radio, direct and in circles, so a sender without
     * internet sees them arrive; patrons carry them on (ADR 0051). They wait [RadioTimings.receiptDelay] and go out
     * together, one packet per sender device with as many IDs as fit, and wait while the radio is away.
     */
    private val receipts = RadioReceipts(scope, core.store, port, timings.receiptDelay, timings.receiptKeep, core::nowMillis, core::log) { device, ids, hopLimit ->
        val sender = core.members.lastOrNull { it.deviceId == device } ?: return@RadioReceipts -1
        check(AnmpKind.RECEIPT)
        val header = RadioKeys.budgetHeader(AnmpKind.RECEIPT, null)
        fun content(batch: List<ByteArray>) = AnmpContent(id = Anmp.newMessageId(), ts = core.nowMillis() / 1000, ids = batch, arrived = AnmpArrived.RADIO)
        var batch = ids
        while (batch.size > 1 && !Anmp.fitsRadioSize(Anmp.size(header, content(batch), signed = false))) batch = batch.dropLast(1)
        val sent = send(core.keys.directPacket(AnmpKind.RECEIPT, sender.deviceId, sender.publicKey, content(batch)), hopLimit)
        if (sent == null) 0 else batch.size
    }

    // --- Receiving ----------------------------------------------------------------------------------

    private val tablesLock = Mutex()
    private val tables = LinkedHashMap<String, RadioTable>()
    private val receiptsLock = Mutex()
    private val receipted = LinkedHashMap<String, Unit>()

    /** Tags of the accepted hours for the own circles and the known devices (ADR 0051). */
    private suspend fun table(atSeconds: Long): RadioTable {
        val circles = core.circles
        val senders = core.members.map { it.deviceId }.toSet()
        val key = AnmpRadio.hourOf(atSeconds).toString() + "|" + circles.joinToString(",") { it.secret + it.previousSecret + it.graceUntil } + "|" + senders.hashCode()
        tablesLock.withLock { tables[key] }?.let { return it }
        // Entries of the radio inbox carry the tags of the hour they were written (up to 30 days).
        val table = core.keys.table(circles, senders, atSeconds)
        tablesLock.withLock {
            tables[key] = table
            while (tables.size > MAX_TABLES) tables.remove(tables.keys.first())
        }
        return table
    }

    private fun verifyFrom(member: MemberState) = AnmpVerifier.of { header, content, data, signature ->
        val signKey = member.signKey ?: return@of false
        AnmpVerifier.withKey(B64.decode(signKey)).verify(header, content, data, signature)
    }

    /**
     * Opens and sorts a packet heard over radio or taken from the radio inbox ([inboxSeconds]: time of the entry). What
     * is heard over radio is uplinked first, whoever it is for. Sorting by the hourly tags: route tag → this device or an
     * own circle, sender tag → known device.
     */
    suspend fun receive(frame: RadioFrame, inboxSeconds: Long? = null) {
        val fromInbox = inboxSeconds != null
        val payload = frame.payload
        val header = Anmp.peek(payload) ?: return
        if (!header.kind.radio) return
        // An anynear app at that radio is in reach: own messages waiting for it may go again (ADR 0090).
        if (!fromInbox) queue.onHeard(frame.fromNode, frame.hops, HeardBy.APP)
        when (header.kind) {
            AnmpKind.ASK -> return if (!fromInbox) relay.onAsk(header, frame.hops, frame.snr) else Unit
            AnmpKind.PATRON -> return if (!fromInbox) relay.onPatron(header) else Unit
            AnmpKind.PICKUP -> return if (!fromInbox) relay.onPickup(payload) else Unit
            else -> Unit
        }
        val route = header.route ?: return
        val nowSeconds = core.nowMillis() / 1000
        // Own packets, also those written in another hour (read back from the radio inbox, uplinked by others).
        if (core.keys.isOwnSender(header.from, inboxSeconds ?: nowSeconds)) return
        if (!fromInbox) relay.onHeard(payload)
        // Heard over radio: the tags of the hour around now, else around when the radio received it (a packet can
        // wait in the radio while the phone is away, ADR 0090).
        val table = table(inboxSeconds ?: nowSeconds).takeIf { it.target(route) != null }
            ?: receivedSeconds(frame, nowSeconds)?.let { table(it) }?.takeIf { it.target(route) != null }
            ?: return core.log("radio: packet not for us (route tag unknown) ${describe(header.kind, frame, fromInbox)}")
        val target = table.target(route) ?: return
        // In a circle only its members count: someone removed from it (and known from elsewhere) is no sender there.
        val inCircle = (target as? RadioTarget.Circle)?.circleId
        val sender = table.sender(header.from)?.let { d -> core.members.lastOrNull { it.deviceId == d && (inCircle == null || it.circleId == inCircle) } }
            ?: return core.log(
                "radio: sender unknown (sender tag) ${describe(header.kind, frame, fromInbox)}, for " +
                    (if (target is RadioTarget.Circle) "circle ${target.circleId.take(6)}" else "this device") +
                    ", tag ${hex(header.from)}, hour ${AnmpRadio.hourOf(inboxSeconds ?: nowSeconds)} (now ${AnmpRadio.hourOf(nowSeconds)}), " +
                    "${core.members.map { it.deviceId }.distinct().size} known devices",
            )
        if (sender.deviceId == core.keys.deviceId) return
        if (fromInbox) relay.onDelivered()
        val circle = (target as? RadioTarget.Circle)?.let { t -> circle(t.circleId) ?: return }
        val message = if (circle == null) {
            core.keys.openDirect(payload, sender.deviceId, sender.publicKey)
        } else {
            core.keys.openCircle(payload, circle, verifyFrom(sender), (inboxSeconds?.times(1000)) ?: core.nowMillis())
        } ?: return core.log("radio: packet could not be opened ${describe(header.kind, frame, fromInbox)} from ${sender.deviceId.take(6)}")
        val content = message.content
        // Authenticated by the device pair key or the signature, unlike the radio's own sender field, which a patron
        // replaces with its own when it carries a packet on (ADR 0051, 0058).
        if (message.header.kind == AnmpKind.CIRCLE || message.header.kind == AnmpKind.DIRECT) {
            content.node?.takeIf { it != 0L }?.let { core.learnNode(sender.deviceId, it) }
        }
        val ts = content.ts.takeIf { it > 0 }?.let { it * 1000 } ?: frame.atMillis
        val conversation = if (circle == null) Conversation.Direct(core.personOf(sender.deviceId)) else Conversation.Circle(circle.id)
        // Packets heard twice: texts are recognized by the app, reactions and receipts act only once there.
        val packetKey = if (fromInbox) payload.contentHashCode() else frame.packetId
        when (message.header.kind) {
            AnmpKind.CIRCLE, AnmpKind.DIRECT -> {
                if ((message.header.kind == AnmpKind.DIRECT) != (circle == null)) return
                val id = content.id ?: return
                val mid = B64.encode(id)
                core.emit(
                    CoreEvent.RadioMessage(
                        conversation = conversation,
                        mid = mid,
                        fromDevice = sender.deviceId,
                        fromName = sender.name,
                        fromNode = frame.fromNode,
                        packetKey = packetKey,
                        text = content.text.orEmpty(),
                        timestampMillis = ts,
                        relayed = fromInbox,
                        replyTo = replyOf(content)?.first,
                        replyFrom = replyOf(content)?.second,
                    ),
                )
                // Even if the internet was faster: the sender should see that radio arrived, too (ADR 0003, 0058). Also in
                // a circle, so that a sender with only radio sees who got it.
                if (firstReceipt(sender.deviceId, mid)) receipts.add(sender.deviceId, id, frame.hops.takeIf { !fromInbox })
            }
            AnmpKind.REACT -> {
                val ref = content.ref ?: return
                val refFrom = content.refFrom ?: return
                val emoji = content.emoji ?: return
                core.emit(CoreEvent.Reaction(conversation, sender.deviceId, B64.encode(refFrom), B64.encode(ref), emoji, ts))
            }
            AnmpKind.DELETE -> core.deleteEvent(conversation, sender.deviceId, content)?.let { core.emit(it) }
            AnmpKind.ALIVE -> {
                val alive = content.alive ?: return
                if (circle == null || !core.onAlive(circle.id, sender.deviceId, content.id, content.ts, alive)) return
                // Confirmed over radio like a circle message, so an owner without internet sees that it arrived (ADR 0083 §5.1).
                val id = content.id?.takeIf { it.size == Anmp.ID_BYTES } ?: return
                if (firstReceipt(sender.deviceId, B64.encode(id))) receipts.add(sender.deviceId, id, frame.hops.takeIf { !fromInbox })
            }
            AnmpKind.CARE -> {
                val care = content.care ?: return
                if (circle != null) core.onCare(circle.id, sender.deviceId, content.ts, care)
            }
            AnmpKind.READ, AnmpKind.RECEIPT -> {
                val mids = content.ids.orEmpty().map(B64::encode)
                if (mids.isEmpty()) return
                if (message.header.kind == AnmpKind.RECEIPT) core.onReceipt(sender.deviceId, mids)
                core.emit(CoreEvent.Receipt(sender.deviceId, mids, read = message.header.kind == AnmpKind.READ, viaRadio = true, arrived = content.arrived ?: 0))
            }
            else -> Unit
        }
    }

    private fun receivedSeconds(frame: RadioFrame, nowSeconds: Long): Long? = lateSeconds(frame.atMillis, nowSeconds)

    /** Kind and radio details of a packet for the log: node, hops, packet ID, or that it came from the radio inbox. */
    private fun describe(kind: AnmpKind, frame: RadioFrame, fromInbox: Boolean): String =
        if (fromInbox) "(${kind.conv}, radio inbox)" else "(${kind.conv}, from ${nodeName(frame.fromNode)}, hops ${frame.hops ?: "?"}, packet ${frame.packetId})"

    private suspend fun firstReceipt(deviceId: String, mid: String): Boolean = receiptsLock.withLock {
        val key = "$deviceId/$mid"
        if (key in receipted) return@withLock false
        receipted[key] = Unit
        while (receipted.size > MAX_RECEIPTS) receipted.remove(receipted.keys.first())
        true
    }

    companion object {
        /** Hour tables kept in memory (entries of the radio inbox go back 30 days). */
        private const val MAX_TABLES = 48
        private const val MAX_RECEIPTS = 2_000

        /** Signs of life over radio: confirmations of one plan at most this often (ADR 0083 §7, 15 minutes). */
        internal const val ALIVE_CONFIRM_GAP_MILLIS = 15L * 60 * 1000

        private const val MAX_PLANS = 64

        /** Radio node as Meshtastic names it (`!939d1998`). */
        internal fun nodeName(node: Int): String = "!" + node.toUInt().toString(16).padStart(8, '0')

        internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

        /**
         * When the radio received a packet (Unix s, from [atMillis]), if it says so, it is plausible and lies in another
         * hour than [nowSeconds]; `null` otherwise.
         */
        internal fun lateSeconds(atMillis: Long, nowSeconds: Long): Long? {
            val at = atMillis.takeIf { it > 0 }?.div(1000) ?: return null
            if (at > nowSeconds + LATE_FUTURE_SECONDS || nowSeconds - at > LATE_MAX_SECONDS) return null
            return at.takeIf { AnmpRadio.hourOf(it) != AnmpRadio.hourOf(nowSeconds) }
        }

        /** A receive time of the radio this far ahead of the phone's clock is not believed. */
        private const val LATE_FUTURE_SECONDS = 5L * 60

        /** Packets that waited in the radio are taken up to this age. */
        private const val LATE_MAX_SECONDS = 7L * 24 * 3600

        private fun midBytes(mid: String): ByteArray? =
            mid.takeIf { it.length == 11 }?.let { runCatching { B64.decode(it) }.getOrNull() }?.takeIf { it.size == Anmp.ID_BYTES }
    }
}
