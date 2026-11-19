package app.anynear.anmp.core

import app.anynear.andp.Hash
import app.anynear.andp.Key
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The network the core works in (ADR 0035 §6). The app trusts only this network: a contact code or
 * invitation with another network ID is refused and never makes the app contact foreign relays.
 */
class AnmpCoreConfig(
    /** Network ID (ANDP §2): SHA-256 of the first network key; never changes. */
    val networkId: Hash,
    /** Current network key; a newer key is taken from a signed hand-over in the list (`next`). */
    val networkKey: Key,
    /** Network list built into the app (JSON of GET /andp/v1/net), `""` without. */
    val embeddedList: String = "",
    /** Starting point of the network list when nothing is known or no relay answers (ADR 0036 §2). */
    val seedUrl: String = SEED_URL,
    /** Host of the links people share (`https://<host>/k#…`, `/c#…`, `/p#…`, `/a#…`); new links use it. */
    val linkHost: String = LINK_HOST,
    /**
     * Hosts whose links the core recognizes, besides [linkHost] (ADR 0061 decision 4): links on the technical domain
     * stay valid, so a later change of the link host needs no reinstall.
     */
    val otherLinkHosts: List<String> = listOf(LINK_HOST_FALLBACK),
    val timings: CoreTimings = CoreTimings(),
    /** Space of the radio inbox (ADR 0051), `""` while it is not set up; then uplink and patrons are off. */
    val radioInboxSpace: String = RADIO_INBOX_SPACE,
    /** Grant for anyone (read, list, write) of [radioInboxSpace], wire form; `""` like the space. */
    val radioInboxGrant: String = RADIO_INBOX_GRANT,
    /**
     * Space of the software for radios (ADR 0056 §1a), `""` while it is not set up. Its key also signs the
     * list of released versions.
     */
    val radioSoftwareSpace: String = RADIO_SOFTWARE_SPACE,
    /** Grant for anyone (read, list) of [radioSoftwareSpace], wire form; `""` like the space. */
    val radioSoftwareGrant: String = RADIO_SOFTWARE_GRANT,
    /** Host of the device addresses of channels (`https://<host>/<token>`, ADR 0062 §4). */
    val ingressHost: String = INGRESS_HOST,
    /**
     * Channel links of the curated channels (ADR 0062 §7, e.g. NINA), empty while none is set up. Only a channel listed
     * here counts as curated when joined; the flag in a link alone does not make one.
     */
    val curatedChannels: List<String> = emptyList(),
    /**
     * Only for tests of the move to the person (ADR 0107 §6): write like before it, per device and without the mailbox
     * recipient in the own list, and do not move the mailbox. Never set in apps.
     */
    internal val legacyDirect: Boolean = false,
    /**
     * Issuers whose login links (`/l#…`, ADR 0122) the app accepts. Apps keep the default; only test builds add their
     * own (e.g. a local `id -person`). A link of any other issuer is refused.
     */
    val loginIssuers: List<String> = listOf(AnmpLogin.ISSUER),
) {
    companion object {
        const val SEED_URL = "https://anynear.net"
        const val LINK_HOST = "anynear.app"
        const val LINK_HOST_FALLBACK = "anynear.net"
        const val INGRESS_HOST = "alerts.anynear.app"

        /**
         * Client level of this core (ANDP §5.2). Raised only when the network needs newer clients; relays announce the
         * lowest level they still serve fully in `clients.min` of /andp/v1/info.
         */
        const val CLIENT_LEVEL = 1

        /** Network key of the anynear network for a first contact (ADR 0061, 0.4). Public, not a secret. */
        const val NETWORK_KEY = "NGt0UXWBluz3qOAx3IKiojRyRMM_f4nBzxoqutAkDY4"

        /** Network ID of the anynear network: SHA-256 of its first network key. It stays across key changes. */
        const val NETWORK_ID = "ro9NwRU2zyAA-5xRZy0U48TOfL5kGHDZABeekQ1utVk"

        /**
         * Radio inbox (ADR 0051): space and its grant for anyone (read, list, write, until the end of 2099). Public,
         * not secrets; the owner key stays outside every repository. Grant issued 04.11.2026 with the ANDP v1
         * signing text, valid until the end of 2099.
         */
        const val RADIO_INBOX_SPACE = "Pg1iLVMX9oL8p-aPTdbYLVlpwOqoaJ18W8ZophDaiK8"
        const val RADIO_INBOX_GRANT = "glgypAFYID4NYi1TF_aC_Kfmj03W2C1ZacDqqGidfFvGaKYQ2oivAgcFGvSGRucGGmrrtqtYQO6adGgddNSxKC-cWS1j9t8xQZKa5-InJYvInCLHvWSmDZhk8CILEYTi7CmZClaU7mGoFuUBc3HSBbghMQpQwQc"

        /** Gateways that heard an `ask` (ADR 0054 §5a, ADR 0061 decision 12). Grant for anyone, read, list, write. */
        const val RADIO_HEARD_SPACE = "_VjIfS9sTwox4K8mt5Rl0Tfqq4qUbbPjPyPjjWf4Ovs"
        const val RADIO_HEARD_GRANT = "glgypAFYIP1YyH0vbE8KMeCvJreUZdE36quKlG2z4z8j441n-Dr7AgcFGvSGRucGGmrrtqtYQDdu8AZUJIvtsUfywEm23h1iJcDbxAfuMjJp8v7cCJ5FBdG3est__JiDGlm19GcxoAek0dWNrM2hGDOWv0eyAQs"

        /** Downlinks for gateways, written by patrons (ADR 0054 §5a, ADR 0061 decision 12). Grant for anyone, read, list, write. */
        const val RADIO_OUTBOX_SPACE = "-0l_j3DMEJp7BqdVBqWOwW-gYtqOfG3ziieoSWprEEY"
        const val RADIO_OUTBOX_GRANT = "glgypAFYIPtJf49wzBCaewanVQaljsFvoGLajnxt84onqElqaxBGAgcFGvSGRucGGmrrtqtYQHAti9Abgzxl_y1u89REWxO2oaWSSsIkZnhaZ_zVOyu1udq6hCvH4U6vOK4TD-iUjah5LNOJLgPmtSR31MKaMgw"

        /**
         * Software for radios (ADR 0056 §1a): space and its grant for anyone (read, list). Public, not secrets; grant
         * issued 04.11.2026, valid until the end of 2099.
         */
        const val RADIO_SOFTWARE_SPACE = "lfw61Z5acbzJT3ymLKzVANPHLFWsYFW5xNLIwn8UVlQ"
        const val RADIO_SOFTWARE_GRANT = "glgypAFYIJX8OtWeWnG8yU98piys1QDTxyxVrGBVucTSyMJ_FFZUAgMFGvSGRucGGmrrtqtYQJva-sgNZByeRRNWdfpUH-oYz9efSpBX2WcI-s1bBI3i1lfHe_LvaAkxG509JfkoiIw-c7karmKWXKBlRkHoTAA"

        /** Configuration of the anynear network. */
        fun default(embeddedList: String = "", timings: CoreTimings = CoreTimings()): AnmpCoreConfig =
            AnmpCoreConfig(Hash.parse(NETWORK_ID), Key.parse(NETWORK_KEY), embeddedList, timings = timings)
    }
}

/** Times of the core. Defaults follow today's app; tests shorten them. */
data class CoreTimings(
    /** Long poll of a list request (ANDP §5.6, at most 25 s). */
    val longPollSeconds: Int = 25,
    /** Pause after a list answer that came back at once without anything new (relays without long poll). */
    val minPollInterval: Duration = 1.seconds,
    /**
     * In the background ([AnmpCore.setBackground]) a list that brought nothing new is asked again at the earliest
     * this long after it was sent: a long poll still delivers at once, only the round after it waits.
     */
    val backgroundPollInterval: Duration = 60.seconds,
    /** Stream (ADR 0072): wait before connecting again after it ended, at random between these two. */
    val streamRetryMin: Duration = 1.seconds,
    val streamRetryMax: Duration = 30.seconds,
    /** Without a relay that offers the stream, look again after this long (or when the list or a wake-up comes). */
    val streamRecheck: Duration = 15.minutes,
    val backoffMin: Duration = 2.seconds,
    val backoffMax: Duration = 60.seconds,
    /** How long a write is retried across restarts (ADR 0016: open deliveries). */
    val pendingMaxAge: Duration = 7.days,
    /**
     * Delivery plan of writes (ADR 0090): after a failed try 10 s, 30 s, 1, 2, 5 min, then every 15 min; network back,
     * foreground or a relay answering again ends the wait at once.
     */
    val writeRetry: RetryPlan = RetryPlan(listOf(10.seconds, 30.seconds, 1.minutes, 2.minutes, 5.minutes), 15.minutes),
    /** Receipts that come in shortly after each other go out together. */
    val receiptBatch: Duration = 2.seconds,
    /** Duty ranking (ADR 0014): rank 0 waits [dutyFirst], every further rank [dutyStep] more, at most [dutyMax]. */
    val dutyFirst: Duration = 5.seconds,
    val dutyStep: Duration = 60.seconds,
    val dutyMax: Duration = 10.minutes,
    /** New member: wait, then announce again (collects several joins). */
    val reannounceDelay: Duration = 5.seconds,
    /** Someone left: wait until everyone saw it, then the ranked member changes the key. */
    val leaveRotateDelay: Duration = 10.seconds,
    /** A file stays this long in the bin of a store without a subscription (ADR 0076, 0086), then it is deleted. */
    val trashKeep: Duration = 7.days,
    /** After a key change the previous circle space is still read this long (ADR 0012). */
    val keyChangeGrace: Duration = 3.days,
    /** §6b: a chosen relay must be reachable this long before the app puts a missing object there itself. */
    val reseedAfter: Duration = 30.minutes,
    val reseedCheck: Duration = 60.seconds,
    /** Network list: ask all relays this often; use the starting point when no relay answered for [seedAfter]. */
    val listRefresh: Duration = 6.hours,
    /** Network list on [AnmpCore.wakeUp] (app in the foreground, alarm): at most this often. */
    val listRefreshSoon: Duration = 10.minutes,
    val seedAfter: Duration = 24.hours,
    /** watch: register again this often (relays keep a watch for 45 days, ANDP §10). */
    val watchRefresh: Duration = 24.hours,
    /** Lifetime of grants for contacts and circles; renewed when less than [grantRenewBefore] is left. */
    val grantLifetime: Duration = 180.days,
    val grantRenewBefore: Duration = 90.days,
    /** Lifetime of the write grant in a contact link (anyone with the link may write until then). */
    val linkGrantLifetime: Duration = 30.days,
    /** Lifetime of the write grant in a published contact link (ADR 0084 §6), e.g. the one built into the apps for support. */
    val publicLinkGrantLifetime: Duration = 365.days,
    /** Expiry of entries that are not kept for ever (capped by the relay's max_until): receipts, read states, sync. */
    val entryLifetime: Duration = 30.days,
    /**
     * Expiry of messages and what belongs to them (ADR 0088, [AnmpCore.PERMANENT_KINDS]); [Duration.ZERO] = no expiry
     * (`until = 0`).
     */
    val messageLifetime: Duration = Duration.ZERO,
    /** Expiry of the contents (attachments, pictures, history parts) of entries without expiry (ADR 0088). */
    val contentLifetime: Duration = 30.days,
    /** Expiry of want/have and of history indexes. */
    val wantLifetime: Duration = 7.days,
    /** History download: pause between attempts. */
    val historyRetryMin: Duration = 30.seconds,
    val historyRetryMax: Duration = 10.minutes,
    /** A direct packet heard over radio waits this long for its internet copy before an own copy is written (ADR 0107 §4). */
    val radioCopyWait: Duration = 10.minutes,
    /** How often removed circles are checked for being purged after their grace period. */
    val purgeCheck: Duration = 1.hours,
)
