// JavaScript facade of the core for the web client in TypeScript (ADR 0036): Promises instead of suspend
// functions, arrays instead of lists, a callback instead of a Flow. Browsers reach relays only by DNS name with
// Web PKI (ANDP §12). Storage is plugged in through [WebStorage] (localStorage) and [WebBlobStore] (IndexedDB).
@file:OptIn(DelicateCoroutinesApi::class)

package app.anynear.anmp.core

import app.anynear.andp.B64
import app.anynear.andp.Hash
import app.anynear.andp.Key
import app.anynear.anmp.AnmpAttachment
import app.anynear.anmp.core.firmware.WebFirmwareList
import app.anynear.anmp.core.firmware.radioSoftwareList
import app.anynear.anmp.core.radio.RadioTarget
import app.anynear.anmp.core.radio.WebRadioArea
import app.anynear.anmp.core.radio.WebRadioGateway
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.time.Duration.Companion.seconds

/** Synchronous string storage provided by the web page. Secrets are stored b64u-encoded under keys starting with `secret:`. */
@JsExport
interface WebStorage {
    fun getItem(key: String): String?

    fun setItem(key: String, value: String?)
}

/**
 * Asynchronous file storage provided by the web page (e.g. IndexedDB) for the core's [BlobStore]: stored alerts,
 * pending uploads, the own picture. Holds only ciphertext. Without it these live in memory and are gone after a reload.
 */
@JsExport
interface WebBlobStore {
    fun read(name: String): Promise<ByteArray?>

    fun write(name: String, bytes: ByteArray): Promise<Unit>

    fun delete(name: String): Promise<Unit>
}

/**
 * What the core asks the web page for (ADR 0014, 0088): the plaintext of a picture or attachment with plaintext hash
 * [hash] (16 bytes, b64u) that may be shared with [fromDevice] in [conversation], to answer a `want`; `null` if the
 * page does not have it or may not share it.
 */
@JsExport
interface WebHost {
    fun sharedContent(conversation: Conversation, hash: String, fromDevice: String): Promise<ByteArray?>
}

/** Host of the core that asks the page's [WebHost], once it is set ([AnmpCoreWeb.setHost]). */
private class WebHostRef : AnmpCoreHost {
    var web: WebHost? = null

    override suspend fun sharedContent(conversation: Conversation, hash: String, fromDevice: String): ByteArray? =
        web?.let { h -> runCatching { h.sharedContent(conversation, hash, fromDevice).await() }.getOrNull() }
}

/**
 * Result of loading a picture or attachment: [status] `found` with [bytes], `gone` (expired, deleted or nowhere: ask
 * with [AnmpCoreWeb.want]) or `later` (no connection, relays still repairing: try again).
 */
@JsExport
class WebBlobResult(val status: String, val bytes: ByteArray?) {
    companion object {
        const val FOUND = "found"
        const val GONE = "gone"
        const val LATER = "later"

        internal fun of(r: BlobResult): WebBlobResult = when (r) {
            is BlobResult.Found -> WebBlobResult(FOUND, r.plaintext)
            BlobResult.Gone -> WebBlobResult(GONE, null)
            BlobResult.Later -> WebBlobResult(LATER, null)
        }
    }
}

private class WebBlobs(private val web: WebBlobStore) : BlobStore {
    override suspend fun read(name: String): ByteArray? = web.read(name).await()

    override suspend fun write(name: String, bytes: ByteArray) = web.write(name, bytes).await()

    override suspend fun delete(name: String) = web.delete(name).await()
}

/** One extra of a pairing hand-over ([AnmpCore.awaitPairing]), e.g. `name` with the person's name as UTF-8. */
@JsExport
class WebExtra(val name: String, val value: ByteArray)

private class WebStores(private val web: WebStorage) : KeyValueStore, SecretStore {
    override fun getString(key: String): String? = web.getItem(key)

    override fun putString(key: String, value: String?) = web.setItem(key, value)

    override fun get(key: String): ByteArray? = web.getItem("secret:$key")?.let { runCatching { B64.decode(it) }.getOrNull() }

    override fun put(key: String, value: ByteArray?) = web.setItem("secret:$key", value?.let { B64.encode(it) })
}

/**
 * Result of [AnmpCoreWeb.addContactPeer]. [status]: `added`, `known`, `mismatch`, `own`, `foreign` or `invalid`;
 * [peer]: person ID ([MemberState.person]) for `added` and `known`, otherwise `null`.
 */
@JsExport
class WebAddContact(val status: String, val peer: String?) {
    internal companion object {
        fun of(result: AddContactResult): WebAddContact = when (result) {
            is AddContactResult.Added -> WebAddContact("added", result.contact.person)
            is AddContactResult.AlreadyKnown -> WebAddContact("known", result.contact.person)
            is AddContactResult.Requested -> WebAddContact("requested", null)
            is AddContactResult.KeyMismatch -> WebAddContact("mismatch", null)
            AddContactResult.Own -> WebAddContact("own", null)
            AddContactResult.ForeignNetwork -> WebAddContact("foreign", null)
            AddContactResult.Invalid -> WebAddContact("invalid", null)
        }
    }
}

/** The core for TypeScript. Create with [create], subscribe with [onEvent] and [onState], then call [start]. */
@JsExport
class AnmpCoreWeb private constructor(private val core: AnmpCore, private val hostRef: WebHostRef) {
    /** Lets the core ask the page for pictures and attachments it has, to answer a `want` (ADR 0014); `null` = none. */
    fun setHost(host: WebHost?) {
        hostRef.web = host
    }

    /**
     * Asks in [conversation] for the picture or attachment with plaintext hash [hash] (ADR 0014, 0088): whoever has it
     * puts it again and answers with `have` ([CoreEvent.Have]).
     */
    fun want(conversation: Conversation, hash: String): Promise<Boolean> = GlobalScope.promise { core.want(conversation, hash) }

    /** Every event goes to [listener]; subscribed at once, so nothing emitted after this call is lost. */
    fun onEvent(listener: (CoreEvent) -> Unit) {
        GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) { core.events.collect { listener(it) } }
    }

    /** [listener] runs whenever circles, members, contacts, own devices, relays, [updateNeeded] or the radio list change. */
    fun onState(listener: () -> Unit) {
        GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(core.circles, core.members, core.ownDevices, core.relays, core.identity, core.updateNeeded, core.radioList) { _ -> }.drop(1).collect { listener() }
        }
    }

    /** Resolves to the own device ID. */
    fun start(): Promise<String> = GlobalScope.promise { core.start().deviceId }

    /** Own device and person; `null` before [start]. */
    fun identity(): IdentityInfo? = core.identity.value

    fun relays(): RelayStatus = core.relays.value

    /** `true` when the relays serve fully only newer clients ([AnmpCore.updateNeeded]); the page asks for a reload. */
    fun updateNeeded(): Boolean = core.updateNeeded.value

    /**
     * What the network asks of this web app build [build] right now (ADR 0096, block `web` of the radio list). The
     * page shows a notice with the date for `soon` and reloads itself for `required`.
     */
    fun webUpdate(build: Double): WebUpdate = when (val d = core.radioList.value?.web?.demand(build.toLong(), kotlin.js.Date.now().toLong()) ?: UpdateDemand.None) {
        UpdateDemand.None -> WebUpdate("none", 0.0)
        is UpdateDemand.Soon -> WebUpdate("soon", d.from.toDouble())
        is UpdateDemand.Required -> WebUpdate("required", d.from.toDouble())
    }

    /** Asks the relays for a newer radio list now (e.g. when the tab becomes visible). */
    fun refreshRadioList(): Promise<Unit> = GlobalScope.promise { core.refreshRadioList() }

    /**
     * Where a radio on Wi-Fi uploads as a gateway (ADR 0054 §5b), from the signed radio list; loads the list first if
     * there is none yet. `null`: the list names no gateway, then a radio set up on Wi-Fi is none.
     */
    fun radioGateway(): Promise<WebRadioGateway?> = GlobalScope.promise {
        if (core.radioList.value == null) {
            try {
                core.refreshRadioList(force = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // No relay answered: without a list there is no gateway.
            }
        }
        core.radioList.value?.gateway?.let(::WebRadioGateway)
    }

    /**
     * Names of the radio areas of the signed radio list (ADR 0054 §1b) in its order, only those the core can apply; loads
     * the list first if there is none yet. Empty without a list.
     */
    fun radioAreas(): Promise<Array<String>> = GlobalScope.promise {
        if (core.radioList.value == null) {
            try {
                core.refreshRadioList(force = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // No relay answered: no areas, the default of ADR 0006 applies.
            }
        }
        core.radioList.value?.regions.orEmpty().filter { RadioTarget.of(it) != null }.map { it.name }.toTypedArray()
    }

    /**
     * The area for a radio at the approximate position (RadioList.regionFor, 2 km at borders), [current] the area it had;
     * `null` outside all areas or without a list. The position stays in the browser.
     */
    fun radioAreaAt(lat: Double, lon: Double, current: String?): String? =
        core.radioList.value?.regionFor(lat, lon, current)?.takeIf { RadioTarget.of(it) != null }?.name

    /** Settings of the area named [name] for [RadioWifiWeb.setUp]; `null` if the list has no such area. */
    fun radioArea(name: String): WebRadioArea? = core.radioList.value?.region(name)?.let(RadioTarget::of)?.let(::WebRadioArea)

    /** Push subscription of this browser (ANDP §10.1); `null` deregisters. */
    fun setWebPushTarget(endpoint: String?) = core.setWebPushTarget(endpoint)

    /** VAPID key of the relays for `pushManager.subscribe`; `null` if no relay offers Web Push. */
    fun webPushKey(): Promise<String?> = GlobalScope.promise { core.webPushKey() }

    /** A setting of the person (ADR 0050) as UTF-8 text, e.g. `usage_stats` = `false`; `null` if unknown. */
    fun setting(name: String): Promise<String?> = GlobalScope.promise { core.settings()[name]?.decodeToString() }

    /**
     * Merges name, picture and settings of the person with the profile on the relays (ADR 0050), like the app does at
     * start and in the foreground. What is newer there arrives as [CoreEvent.ProfileChanged]; a freshly paired browser
     * gets the own picture this way. Resolves to `true` if this browser wrote something back.
     */
    fun profileSync(): Promise<Boolean> = GlobalScope.promise { core.profileSync() }

    /** Changes a setting of the person; it goes to all own devices with the profile. */
    fun setSetting(name: String, value: String): Promise<Unit> = GlobalScope.promise { core.setSettings(mapOf(name to value.encodeToByteArray())) }

    /**
     * Sends today's usage report of this browser (ADR 0045, platform `web`, no radios). [curatedLink] and [curatedId]
     * name the curated channel of the config (e.g. NINA → `nina`), both empty without one. Resolves to `true` once a
     * relay took it.
     */
    fun sendStats(version: String, curatedLink: String, curatedId: String): Promise<Boolean> = GlobalScope.promise {
        core.sendStats("web", version, 0, if (curatedLink.isNotEmpty() && curatedId.isNotEmpty()) mapOf(curatedLink to curatedId) else emptyMap())
    }

    /** Tells once that the person switched the stats on or off in this browser (ADR 0045). */
    fun sendStatsSwitch(on: Boolean): Promise<Boolean> = GlobalScope.promise { core.sendStatsSwitch("web", on) }

    /** Checks the relays again now, e.g. when the page becomes visible. */
    fun wakeUp() = core.wakeUp()

    /** The browser is online again (`online` event): pending writes go at once instead of waiting (ADR 0090). */
    fun networkChanged() = core.networkChanged()

    fun setProfile(name: String): Promise<Unit> = GlobalScope.promise { core.setProfile(name) }

    fun circles(): Array<CircleState> = core.circles.value.toTypedArray()

    fun members(circleId: String): Array<MemberState> = core.membersOf(circleId).toTypedArray()

    /** Contacts outside any circle ([MemberState.CONTACTS]). */
    fun contacts(): Array<MemberState> = core.membersOf(MemberState.CONTACTS).toTypedArray()

    fun createCircle(name: String): Promise<CircleState> = GlobalScope.promise { core.createCircle(name) }

    /** Resolves to `joined`, `already`, `foreign` or `invalid`. */
    fun joinCircle(link: String): Promise<String> = GlobalScope.promise {
        when (core.joinCircle(link)) {
            is JoinResult.Joined -> "joined"
            is JoinResult.AlreadyMember -> "already"
            JoinResult.ForeignNetwork -> "foreign"
            JoinResult.Invalid -> "invalid"
        }
    }

    fun inviteLink(circleId: String): String? = core.inviteLink(circleId)

    // --- Signs of life (ADR 0083), like the app's LifeSigns: times in ms as Double --------------------

    /** All known signs of life with their status at [now] (ms), also ended ones; changes come as `CoreEvent.AliveChanged`. */
    fun aliveStatuses(now: Double): Array<AliveStatus> = core.alivePlans().mapNotNull { core.aliveStatus(it.id, now.toLong()) }.toTypedArray()

    /** When something changes next without news (ms), 0 if nothing waits. */
    fun aliveNextAt(now: Double): Double = core.aliveNextAt(now.toLong())?.toDouble() ?: 0.0

    /** Starts a sign of life in [circleId]; [firstDue] 0 = after [quiet]. `null` if the circle is unknown. */
    fun startAlive(circleId: String, label: String, until: Double, quiet: Double, firstDue: Double): Promise<AlivePlan?> =
        GlobalScope.promise { core.startAlive(circleId, label, until.toLong(), quiet.toLong(), firstDue = firstDue.toLong().takeIf { it > 0 }) }

    fun confirmAlive(planId: String): Promise<Boolean> = GlobalScope.promise { core.confirmAlive(planId) }

    fun endAlive(planId: String): Promise<Boolean> = GlobalScope.promise { core.endAlive(planId) }

    /** Answer as a helper: 1 looking, 2 away, 3 back ([app.anynear.anmp.AnmpCareState]). */
    fun careAlive(planId: String, state: Int): Promise<Boolean> = GlobalScope.promise { core.careAlive(planId, state) }

    /** Does what is due for own signs of life; resolves to the next time (ms), 0 if nothing waits. */
    fun aliveTick(): Promise<Double> = GlobalScope.promise { core.aliveTick()?.toDouble() ?: 0.0 }

    /** Persons that asked us to be their contact for calls and have no answer yet (ADR 0121 §25). */
    fun sosAsks(): Array<SosAsk> = core.sosAsks().toTypedArray()

    /** The persons we said yes to: only their calls may ring loudly. */
    fun sosAgreed(): Array<String> = core.sosAgreed().toTypedArray()

    /** Whom the own person asked to be a contact for its calls, and what they answered. */
    fun sosContacts(): Array<SosContact> = core.sosContacts().toTypedArray()

    /** The own answer to [person]: [agree] or not, also to take a yes back; every own device learns it. */
    fun answerSosAsk(person: String, agree: Boolean): Promise<Boolean> = GlobalScope.promise { core.answerSosAsk(person, agree) }

    /** Own and subscribed channels (ADR 0062). */
    fun channels(): Array<ChannelState> = core.channels.value.toTypedArray()

    /** Alerts of the last 30 days, newest first; changes come through [onAlerts]. */
    fun alerts(): Array<AlertItem> = core.alerts.value.toTypedArray()

    /** [listener] runs whenever channels or alerts change (also a status that expires). */
    fun onAlerts(listener: () -> Unit) {
        GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) { combine(core.channels, core.alerts) { _, _ -> }.drop(1).collect { listener() } }
    }

    fun createChannel(name: String): Promise<ChannelState> = GlobalScope.promise { core.createChannel(name) }

    /** Resolves to `joined`, `already`, `foreign` or `invalid`. */
    fun joinChannel(link: String): Promise<String> = GlobalScope.promise {
        when (core.joinChannel(link)) {
            is ChannelJoinResult.Joined -> "joined"
            is ChannelJoinResult.AlreadyJoined -> "already"
            ChannelJoinResult.ForeignNetwork -> "foreign"
            ChannelJoinResult.Invalid -> "invalid"
        }
    }

    fun channelLink(channelId: String): String? = core.channelLink(channelId)

    /** Reads a channel link (also inside text) before joining, e.g. to show its name; `null` if it is not one. */
    fun parseChannelLink(link: String): ChannelInvite? = core.parseChannelLink(link)

    /** What an invitation link (circle, contact, channel) stands for, e.g. to show it as a card; `null` if none or broken. */
    fun previewLink(link: String): Promise<LinkPreview?> = GlobalScope.promise { core.previewLink(link) }

    /** Login link (`/l#…`, ADR 0122) of a known issuer; `null` if none, broken or of an unknown issuer. */
    fun parseLoginLink(text: String): LoginLink? = core.parseLoginLink(text)

    /** Whether [text] holds a login link at all, also a broken one. */
    fun isLoginLink(text: String): Boolean = core.isLoginLink(text)

    /** Service, domain and the three numbers of a login ([AnmpCore.loginInfo]). */
    fun loginInfo(link: LoginLink): Promise<LoginInfo> = GlobalScope.promise { core.loginInfo(link) }

    /** Confirms a login after the person unlocked; [LoginResult.status] tells what happened. */
    fun confirmLogin(info: LoginInfo, n: Int, name: String): Promise<LoginResult> = GlobalScope.promise { core.confirmLogin(info, n, name) }

    fun leaveChannel(channelId: String): Promise<Unit> = GlobalScope.promise { core.leaveChannel(channelId) }

    /** Alert without attachments into an own channel; [until] Unix seconds or 0, [replaces] hex ID or `null`. */
    fun sendAlert(channelId: String, prio: Int, title: String?, text: String, url: String?, until: Double, replaces: String?, ended: Boolean): Promise<AlertItem?> =
        GlobalScope.promise { core.sendAlert(channelId, prio, title, text, url, emptyList(), until.toLong().takeIf { it > 0 }, replaces, ended) }

    /** Renames an own channel on every own device; `false` for channels of others. */
    fun renameChannel(channelId: String, name: String): Promise<Boolean> = GlobalScope.promise { core.renameChannel(channelId, name) }

    /** New read key for an own channel; old links stop working ([AnmpCore.resetChannelLink]). `null` for channels of others. */
    fun resetChannelLink(channelId: String): Promise<ChannelState?> = GlobalScope.promise { core.resetChannelLink(channelId) }

    /** Subscribers of an own channel that announced themselves, oldest first (ADR 0062 §11); empty for channels of others. */
    fun channelSubscribers(channelId: String): Array<ChannelSubscriber> = core.channel(channelId)?.takeIf { it.owner }?.subscribers.orEmpty().toTypedArray()

    /** Removes a subscriber: new read key for the others, the old link stops working ([AnmpCore.removeSubscriber]). */
    fun removeSubscriber(channelId: String, deviceId: String): Promise<Boolean> = GlobalScope.promise { core.removeSubscriber(channelId, deviceId) }

    /** Device address for an own channel (ADR 0062 §4); `null` for channels of others. */
    fun createIngress(channelId: String, label: String): Promise<Ingress?> = GlobalScope.promise { core.createIngress(channelId, label) }

    /** Revokes a device address; `false` if unknown. */
    fun revokeIngress(channelId: String, ingressId: String): Promise<Boolean> = GlobalScope.promise { core.revokeIngress(channelId, ingressId) }

    /** Attachment of an alert; resolves to the plaintext, `null` if it is gone or not available now. */
    fun fetchAlertAttachment(alert: AlertItem, attachment: AnmpAttachment): Promise<ByteArray?> =
        GlobalScope.promise { (core.fetchAlertAttachment(alert, attachment) as? BlobResult.Found)?.plaintext }

    fun ownContactLink(): Promise<String> = GlobalScope.promise { core.ownContactLink() }

    /** Contact link to publish, grant for a year (ADR 0084 §6). */
    fun publicContactLink(): Promise<String> = GlobalScope.promise { core.publicContactLink() }

    /** Withdraws the published contact link (ADR 0084 §6). */
    fun revokePublicContactLink(): Promise<Unit> = GlobalScope.promise { core.revokePublicContactLink() }

    /** Resolves to `added`, `known`, `mismatch`, `own`, `foreign` or `invalid`. */
    fun addContact(link: String): Promise<String> = GlobalScope.promise { WebAddContact.of(core.addContact(link)).status }

    /** Like [addContact], also with the person ID of the added or known contact, e.g. to write to it at once. */
    fun addContactPeer(link: String): Promise<WebAddContact> = GlobalScope.promise { WebAddContact.of(core.addContact(link)) }

    /** Persons asking to become a contact (ADR 0116), newest first. */
    fun contactRequests(): Promise<Array<ContactRequest>> = GlobalScope.promise { core.contactRequests().toTypedArray() }

    /** Our requests not yet answered (ADR 0116): „Anfrage gesendet“. */
    fun sentContactRequests(): Promise<Array<SentRequest>> = GlobalScope.promise { core.sentContactRequests().toTypedArray() }

    /** Agrees to the request of [person] (ADR 0116). */
    fun acceptContactRequest(person: String): Promise<Boolean> = GlobalScope.promise { core.acceptContactRequest(person) }

    /** Declines the request of [person] silently (ADR 0116 §4). */
    fun declineContactRequest(person: String): Promise<Unit> = GlobalScope.promise { core.declineContactRequest(person) }

    /** Introduces the contact [person] to the contact [toPerson] (ADR 0116); resolves to the message ID or `null`. */
    fun introduce(person: String, toPerson: String, text: String, image: PreparedAttachment?): Promise<String?> =
        GlobalScope.promise { core.introduce(person, toPerson, text, image)?.mid }

    /** We want the contact of [intro] (ADR 0116): resolves like [addContact], or `requested`. */
    fun acceptIntro(intro: Intro): Promise<String> = GlobalScope.promise { WebAddContact.of(core.acceptIntro(intro)).status }

    /**
     * Device ID of a contact link in this network, without adding it; `null` if [link] is not one. Lets the web app
     * recognise a known contact, such as the support identity, before or without adding it.
     */
    fun contactDevice(link: String): Promise<String?> = GlobalScope.promise { core.contactDevice(link) }

    /** `true` if direct messages to the person or device [id] can be sent (a grant for its mailbox is known). */
    fun canWriteTo(id: String): Boolean = core.canWriteTo(id)

    /**
     * Seals an attachment for [sendCircle] or [sendDirect] (ADR 0011). [type]: `jpg`, `pdf`, `m4a` or `file` (with [name]).
     * Rejects if it is larger than the relays' object limit.
     */
    fun prepareAttachment(plaintext: ByteArray, type: String, name: String?, width: Int, height: Int): Promise<PreparedAttachment> =
        GlobalScope.promise { core.prepareAttachment(plaintext, type, name, width, height) }

    /**
     * Resolves to the message ID, `null` if the circle is unknown. [mid]: as in [AnmpCore.sendCircle], `null` = new.
     * [replyTo], [replyFrom]: ID and author tag of the message this answers (ADR 0092), or both `null`.
     * [passedOn]: the message passes on one the sender got („Weiterleiten“, ADR 0118).
     */
    fun sendCircle(circleId: String, text: String, mid: String?, attachments: Array<PreparedAttachment>, replyTo: String?, replyFrom: String?, passedOn: Boolean): Promise<String?> =
        GlobalScope.promise { core.sendCircle(circleId, text, attachments.toList(), mid = mid, replyTo = replyTo, replyFrom = replyFrom, passedOn = passedOn)?.mid }

    /** Resolves to the message ID, `null` without a grant for the recipient's mailbox. [id]: person or device ID. [replyTo], [replyFrom] as in [sendCircle]. */
    fun sendDirect(id: String, text: String, mid: String?, attachments: Array<PreparedAttachment>, replyTo: String?, replyFrom: String?, passedOn: Boolean): Promise<String?> =
        GlobalScope.promise { core.sendDirect(id, text, attachments.toList(), mid = mid, replyTo = replyTo, replyFrom = replyFrom, passedOn = passedOn)?.mid }

    /**
     * A message that is a card about places (ADR 0121 §26, §28) to the person [id]: [kind] as in `AnmpSpot`, [level]
     * for asking to share each other's place and for its answer (0 for no; negative: none). [text] is what older apps
     * show. Resolves to the message ID, `null` if nothing went out.
     */
    fun sendDirectSpot(id: String, text: String, mid: String?, kind: Int, level: Int): Promise<String?> =
        GlobalScope.promise { core.sendDirect(id, text, mid = mid, spot = MessageSpot(kind, level = level.takeIf { it >= 0 }))?.mid }

    /** Reaction [emoji] (`""` withdraws it) on message [targetMid] of the installation with tag [targetTag] (ADR 0018). */
    fun react(conversation: Conversation, targetTag: String, targetMid: String, emoji: String): Promise<Boolean> =
        GlobalScope.promise { core.react(conversation, targetTag, targetMid, emoji) }

    /** Withdraws the own message [targetMid] (tag [targetTag] of the own installation) for everyone (ADR 0068). */
    fun deleteMessage(conversation: Conversation, targetTag: String, targetMid: String): Promise<Boolean> =
        GlobalScope.promise { core.deleteMessage(conversation, targetTag, targetMid) }

    /** The own message [mid] was deleted (for me or for everyone): what still waits does not go out (ADR 0090). */
    fun cancelSend(mid: String): Promise<Unit> = GlobalScope.promise { core.cancelSend(mid) }

    fun confirmReceipt(deviceId: String, mid: String) = core.confirmReceipt(deviceId, mid)

    /** Read receipt (ADR 0019); the own other devices learn it for [conversation] (ADR 0042). */
    fun confirmRead(deviceId: String, mid: String, conversation: Conversation?) = core.confirmRead(deviceId, mid, conversation)

    fun leaveCircle(circleId: String): Promise<Unit> = GlobalScope.promise { core.leaveCircle(circleId) }

    /** Only own devices in the circle: leaving also deletes its store for good (ADR 0049 §5). */
    fun aloneIn(circleId: String): Boolean = core.aloneIn(circleId)

    /** Renames the circle; every member may (ADR 0012). */
    fun renameCircle(circleId: String, name: String): Promise<Boolean> = GlobalScope.promise { core.renameCircle(circleId, name) }

    /** Sets (`null` removes) the circle picture. */
    fun setCircleImage(circleId: String, plaintext: ByteArray?): Promise<Boolean> = GlobalScope.promise { core.setCircleImage(circleId, plaintext) }

    /** New invitation link; the old ones stop working (key change). */
    fun resetInvites(circleId: String): Promise<Unit> = GlobalScope.promise { core.resetInvites(circleId) }

    /** Removes a contact person (person or device ID). */
    fun removeContact(id: String): Promise<Unit> = GlobalScope.promise { core.removeContact(id) }

    /** Own picture for circles and contacts (`null` removes it); goes to all own devices (ADR 0050). */
    fun setOwnImage(plaintext: ByteArray?): Promise<Unit> = GlobalScope.promise { core.setOwnImage(plaintext) }

    fun removeMember(circleId: String, deviceId: String): Promise<Unit> = GlobalScope.promise { core.removeMember(circleId, deviceId) }

    /** Resolves to the plaintext, `null` if it is gone or not available now. */
    fun fetchAttachment(space: String, attachment: AnmpAttachment): Promise<ByteArray?> =
        GlobalScope.promise { (core.fetchAttachment(space, attachment) as? BlobResult.Found)?.plaintext }

    /** Picture of a member, contact or circle; resolves to the plaintext, `null` if not available now. */
    fun fetchImage(ref: ImageRef): Promise<ByteArray?> = GlobalScope.promise { (core.fetchImage(ref) as? BlobResult.Found)?.plaintext }

    /** Like [fetchImage], but tells `gone` (then ask with [want]) from `later`. */
    fun fetchImageResult(ref: ImageRef): Promise<WebBlobResult> = GlobalScope.promise { WebBlobResult.of(core.fetchImage(ref)) }

    // --- Stores (ADR 0041, 0049) and password space (ADR 0048) --------------------------------------

    /** Puts [file] (the app's ID, same over changes) into the personal store; resolves to the ref of Stored/SendFailed. */
    fun storePut(file: String, plaintext: ByteArray, name: String, type: String, added: Long, width: Int, height: Int): Promise<String> =
        GlobalScope.promise { core.storePut(file, plaintext, name, type, added, width, height) }

    /** Files of the personal store, newest first; `null` if the relays cannot be reached now. */
    fun storeList(): Promise<Array<StoredFile>?> = GlobalScope.promise { core.storeList()?.toTypedArray() }

    /**
     * How full the own storage is over all stores together (shared quota), as of the last listings; `null` before
     * [storeList]. Putting a new file rejects with [StorageFullException] when it has no room.
     */
    fun storageUsage(): Promise<StoreUsage?> = GlobalScope.promise { core.storageUsage() }

    /** How full the personal store is, as of the last [storeList]; `null` before. */
    fun storeUsage(): Promise<StoreUsage?> = GlobalScope.promise { core.storeUsage() }

    /** Content of a file from [storeList]; `null` if it is gone or not available now. */
    fun storeFetch(f: StoredFile): Promise<ByteArray?> = GlobalScope.promise { (core.storeFetch(f) as? BlobResult.Found)?.plaintext }

    /** `false`: no relay reachable, try again later. */
    fun storeDelete(file: String): Promise<Boolean> = GlobalScope.promise { core.storeDelete(file) }

    /** Into the bin (ADR 0086); `false`: no relay reachable, try again later. */
    fun storeTrash(file: String): Promise<Boolean> = GlobalScope.promise { core.storeTrash(file) }

    /** Back out of the bin. */
    fun storeRestore(file: String): Promise<Boolean> = GlobalScope.promise { core.storeRestore(file) }

    /** Changes name and document details without uploading again (ADR 0067); `null`: put the file again. */
    fun storeUpdate(f: StoredFile, name: String, doc: DocInfo?): Promise<String?> = GlobalScope.promise { core.storeUpdate(f, name, doc) }

    /** Sets the title of [f] (ADR 0091), name and details stay; `null` title: none. `null`: put the file again. */
    fun storeSetTitle(f: StoredFile, title: FileTitle?): Promise<String?> = GlobalScope.promise { core.storeUpdate(f, f.name, f.doc, title) }

    /**
     * New content for a file already in the personal store (e.g. a rotated photo): same ID, name, type, date and
     * document details as [f], only [plaintext] and its size change. Resolves to the ref of Stored/SendFailed.
     */
    fun storeReplace(f: StoredFile, plaintext: ByteArray, width: Int, height: Int): Promise<String> =
        GlobalScope.promise { core.storePut(f.file, plaintext, f.name, f.type, f.added, width, height, f.doc, f.title) }

    /** Like [storePut] for the store of the circle; `null` if we are not in the circle. */
    fun circleStorePut(circleId: String, file: String, plaintext: ByteArray, name: String, type: String, added: Long, width: Int, height: Int): Promise<String?> =
        GlobalScope.promise { core.circleStorePut(circleId, file, plaintext, name, type, added, width, height) }

    /** Like [storeReplace] for the store of the circle; `null` if we are not in the circle. */
    fun circleStoreReplace(circleId: String, f: StoredFile, plaintext: ByteArray, width: Int, height: Int): Promise<String?> =
        GlobalScope.promise { core.circleStorePut(circleId, f.file, plaintext, f.name, f.type, f.added, width, height, f.doc, f.title) }

    fun circleStoreList(circleId: String): Promise<Array<StoredFile>?> = GlobalScope.promise { core.circleStoreList(circleId)?.toTypedArray() }

    /** Like [storeUsage] for the store of the circle, as of the last [circleStoreList]. */
    fun circleStoreUsage(circleId: String): Promise<StoreUsage?> = GlobalScope.promise { core.circleStoreUsage(circleId) }

    fun circleStoreFetch(circleId: String, f: StoredFile): Promise<ByteArray?> =
        GlobalScope.promise { (core.circleStoreFetch(circleId, f) as? BlobResult.Found)?.plaintext }

    fun circleStoreDelete(circleId: String, file: String): Promise<Boolean> = GlobalScope.promise { core.circleStoreDelete(circleId, file) }

    fun circleStoreTrash(circleId: String, file: String): Promise<Boolean> = GlobalScope.promise { core.circleStoreTrash(circleId, file) }

    fun circleStoreRestore(circleId: String, file: String): Promise<Boolean> = GlobalScope.promise { core.circleStoreRestore(circleId, file) }

    /** Like [storeUpdate] for the store of the circle. */
    fun circleStoreUpdate(circleId: String, f: StoredFile, name: String, doc: DocInfo?): Promise<String?> =
        GlobalScope.promise { core.circleStoreUpdate(circleId, f, name, doc) }

    /** Like [storeSetTitle] for the store of the circle. */
    fun circleStoreSetTitle(circleId: String, f: StoredFile, title: FileTitle?): Promise<String?> =
        GlobalScope.promise { core.circleStoreUpdate(circleId, f, f.name, f.doc, title) }

    /** Newest password vault on the relays (sealed with `pv` by the app), with `pv` if an own device put it there. */
    fun vaultGet(): Promise<VaultResult> = GlobalScope.promise { core.vaultGet() }

    /** Puts the vault (already sealed with `pv`) on the relays; [key] `null` keeps the key of the newest entry. */
    fun vaultPut(data: ByteArray, changed: Long, key: ByteArray?): Promise<Unit> = GlobalScope.promise { core.vaultPut(data, changed, key) }

    // --- Persons and devices (ADR 0042) --------------------------------------------------------------

    /** Devices of the own person, this one included. */
    fun ownDevices(): Array<DeviceInfo> = core.ownDevices.value.toTypedArray()

    /** The own mailbox was not read through once yet on this device (ADR 0107 §8): direct chats still load. */
    fun loadingMailbox(): Boolean = core.loading.value.mailbox

    /** Circles whose space was not read through once yet on this device (ADR 0107 §8). */
    fun loadingCircles(): Array<String> = core.loading.value.circles.toTypedArray()

    /** [listener] whenever [loadingMailbox] or [loadingCircles] change. */
    fun onLoading(listener: () -> Unit) {
        GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) { core.loading.drop(1).collect { listener() } }
    }

    /**
     * Hands the page's own direct history to the core once (ADR 0107 §6, [AnmpCore.keepOwnHistory]); `true` once done,
     * `false`: try again later.
     */
    fun keepOwnHistory(items: Array<WebHistoryItem>): Promise<Boolean> = GlobalScope.promise {
        core.keepOwnHistory(items.map { it.toCore() })
    }

    /**
     * Data for the own other devices (ADR 0042, [AnmpCore.sendOwnData]); they get it as [CoreEvent.OwnData]. [toDevice]
     * `null` = all own other devices. Resolves to `false` if there is none or this device was removed.
     */
    fun sendOwnData(topic: String, data: ByteArray, toDevice: String?): Promise<Boolean> =
        GlobalScope.promise { core.sendOwnData(topic, data, toDevice) }

    /** Name of this device in the own list (e.g. „Browser auf Mac“). */
    fun setOwnDeviceName(name: String): Promise<Unit> = GlobalScope.promise { core.setOwnDeviceName(name) }

    /** On a new installation: the pairing link to show as QR (`https://anynear.app/p#…`, [AnmpCore.pairingOffer]). */
    fun pairingOffer(name: String): Promise<String> = GlobalScope.promise { core.pairingOffer(name) }

    /** Comparison code to show next to the QR after [pairingOffer] (ADR 0093); the phone shows the same. */
    fun ownPairingCode(): Promise<String?> = GlobalScope.promise { core.ownPairingCode() }

    /**
     * Waits up to [timeoutSeconds] for the hand-over from an own device. On success the person's keys are stored and the
     * extras come back; the page then drops this core and creates a new one (reload). `null` on timeout.
     */
    fun awaitPairing(timeoutSeconds: Int): Promise<Array<WebExtra>?> = GlobalScope.promise {
        core.awaitPairing(timeoutSeconds.seconds)?.map { (k, v) -> WebExtra(k, v) }?.toTypedArray()
    }

    /**
     * The newest validly signed list of radio software from the relays (ADR 0056 §1a), for setting up a radio over USB
     * ([app.anynear.anmp.core.firmware.RadioSetupWeb]); `null` while the space is not set up or no relay answered.
     */
    fun radioSoftware(): Promise<WebFirmwareList?> = GlobalScope.promise { core.radioSoftwareList()?.let(::WebFirmwareList) }

    fun stop(): Promise<Unit> = GlobalScope.promise { core.stop() }

    companion object {
        /** Core in the anynear network with storage [storage]; [embeddedList]: JSON of GET /andp/v1/net or `""`. */
        fun createDefault(storage: WebStorage, embeddedList: String): AnmpCoreWeb = createDefaultWithCurated(storage, embeddedList, emptyArray())

        /** Like [createDefault]; [curatedChannels]: links of the curated channels from the page's configuration (ADR 0062 §7). */
        fun createDefaultWithCurated(storage: WebStorage, embeddedList: String, curatedChannels: Array<String>): AnmpCoreWeb = create(
            AnmpCoreConfig.NETWORK_KEY, AnmpCoreConfig.NETWORK_ID, embeddedList, storage, curatedChannels,
        )

        /** Like [createDefaultWithCurated]; files of the core go to [blobs], so they survive a reload. */
        fun createDefaultWithBlobs(storage: WebStorage, blobs: WebBlobStore, embeddedList: String, curatedChannels: Array<String>): AnmpCoreWeb = create(
            AnmpCoreConfig.NETWORK_KEY, AnmpCoreConfig.NETWORK_ID, embeddedList, storage, curatedChannels, blobs,
        )

        /** Core in the network with key [networkKey] (b64u); [networkId] `""` = derived from the key (first key); [blobs] `null` = in memory. */
        fun create(
            networkKey: String,
            networkId: String,
            embeddedList: String,
            storage: WebStorage,
            curatedChannels: Array<String> = emptyArray(),
            blobs: WebBlobStore? = null,
        ): AnmpCoreWeb {
            val key = Key.parse(networkKey)
            val id = if (networkId.isEmpty()) app.anynear.andp.networkId(key) else Hash.parse(networkId)
            val stores = WebStores(storage)
            val platform = AnmpPlatform(stores, stores, blobs?.let(::WebBlobs) ?: MemoryBlobStore())
            val hostRef = WebHostRef()
            return AnmpCoreWeb(AnmpCore(GlobalScope, AnmpCoreConfig(id, key, embeddedList, curatedChannels = curatedChannels.toList()), platform, host = hostRef), hostRef)
        }
    }
}

/** Result of [AnmpCoreWeb.webUpdate]: [kind] `none`, `soon` or `required`; [from] Unix milliseconds or 0. */
@JsExport
data class WebUpdate(val kind: String, val from: Double)

/** One direct message of the page's own history for [AnmpCoreWeb.keepOwnHistory] (ADR 0107 §6); IDs and keys b64u. */
@JsExport
class WebHistoryItem(
    /** The other person (person ID, or a device ID of it). */
    val peer: String,
    val mid: String,
    val ts: Double,
    val text: String,
    /** Sender's device of a received message; `null` for an own one. */
    val author: String?,
    val replyTo: String?,
    val replyFrom: String?,
    val attachments: Array<WebHistoryAttachment>,
) {
    internal fun toCore() = OwnHistoryItem(peer, mid, ts.toLong(), text, author, attachments.mapNotNull { it.toCore() }, replyTo, replyFrom)
}

/** Attachment of a [WebHistoryItem], as the page keeps it (ANMP field 11). */
@JsExport
class WebHistoryAttachment(
    val id: String,
    val key: String,
    val type: String,
    val name: String,
    val size: Double,
    val width: Int,
    val height: Int,
    val duration: Double,
    val hash: String?,
) {
    internal fun toCore(): AnmpAttachment? = runCatching {
        AnmpAttachment(
            B64.decode(id), B64.decode(key), type, name.ifEmpty { null }, size.toLong(), width, height,
            hash?.let(B64::decode), duration.toLong(),
        )
    }.getOrNull()
}

