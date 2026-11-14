package app.anynear.anmp

import kotlin.js.JsExport

/**
 * Protocol constants of ANMP version v1 (ADR 0061). Never renamed; an incompatible change gets `anmp/v2/…`.
 */
@JsExport
object AnmpInfo {
    /** HKDF salt of every derivation. */
    const val SALT = "anmp"

    /** Circle secret → AES-256-GCM circle key. */
    const val CHAT_AEAD = "anmp/v1/chat-aead"

    /** Circle secret → Ed25519 seed of the circle space (formerly circle mailbox). */
    const val MAILBOX_SIGN = "anmp/v1/mailbox-sign"

    /** Owner X25519 public key → envelope key of the personal mailbox. */
    const val INBOX = "anmp/v1/inbox"

    /** Circle secret → envelope key of the circle space; also the envelope AAD prefix, followed by `\n` space. */
    const val ENV = "anmp/v1/env"

    /** X25519 shared secret → device pair key; followed by `|` and both device IDs. */
    const val DIRECT = "anmp/v1/direct"

    /** Prefix of the device ID hash, followed by `\n`. */
    const val DEVICE = "anmp/v1/device"

    /** X25519 key of a personal mailbox from its owner seed (ADR 0084). */
    const val MAILBOX_X = "anmp/v1/mailbox-x"

    /** Prefix of the mailbox recipient ID hash, followed by `\n` (ADR 0084). */
    const val MAILBOX = "anmp/v1/mailbox"

    /** Associated data of an encrypted blob (attachment, image, history part). */
    const val ATT = "anmp/v1/att"

    /** Store secret → Ed25519 seed of the personal store space ("Meine Ablage", ADR 0041). */
    const val STORE_SIGN = "anmp/v1/store-sign"

    /** Store secret → AES-256-GCM key of the personal store entries; also their associated data. */
    const val STORE_AEAD = "anmp/v1/store-aead"

    /** Circle secret → Ed25519 seed of the circle store space ("Kreis-Ablage", ADR 0049). */
    const val CIRCLE_STORE_SIGN = "anmp/v1/circle-store-sign"

    /** Circle secret → AES-256-GCM key of the circle store entries; also their associated data. */
    const val CIRCLE_STORE_AEAD = "anmp/v1/circle-store-aead"

    /** X25519 private key → key for data that stays on the device. */
    const val LOCAL_STORAGE = "anmp/v1/local-storage"

    /** Prefix of values sealed with the local storage key. */
    const val LOCAL_PREFIX = "local-v1:"

    /** Associated data of a packet, followed by `\n` scope `\n` h. */
    const val AAD = "anmp/v1/aad"

    /** Signed bytes of a packet, followed by `\n` scope `\n` CBOR([h, c]). */
    const val SIG = "anmp/v1/sig"

    /** Fixed public input → pre-shared key of the one radio channel [RADIO_CHANNEL_NAME] (ADR 0051). Protects nothing. */
    const val RADIO_CHANNEL = "anmp/v1/radio-channel"

    /** Device ID or circle secret → radio secret, the root of route and sender tags (ADR 0051). */
    const val RADIO_SECRET = "anmp/v1/radio-secret"

    /** Prefix of the route tag hash, followed by `\n` and both chain values of the hour (ADR 0051 §5a). */
    const val ROUTE_TAG = "anmp/v1/route-tag"

    /** Radio secret → start of the forward chain of an epoch, followed by `\n` epoch (ADR 0051 §5a). */
    const val ROUTE_FWD = "anmp/v1/route-fwd"

    /** Radio secret → end of the backward chain of an epoch, followed by `\n` epoch (ADR 0051 §5a). */
    const val ROUTE_BACK = "anmp/v1/route-back"

    /** Prefix of one chain step, followed by `\n` and the chain value (ADR 0051 §5a). */
    const val ROUTE_CHAIN = "anmp/v1/route-chain"

    /** X25519 shared secret of asker and patron → key of a `pickup`; also its scope (ADR 0051 §5a). */
    const val PICKUP = "anmp/v1/pickup"

    /** Radio secret of a device → sender tag of an hour, followed by `\n` hour (ADR 0051). */
    const val SENDER_TAG = "anmp/v1/sender-tag"

    /** Name of the one radio channel of all anynear radios (ADR 0051, 0061 decision 6). */
    const val RADIO_CHANNEL_NAME = "anynear"

    /** Radio channel of the app before ANMP (ADR 0061 decision 6); apps remove it. The value stays to recognize it. */
    const val LEGACY_RADIO_CHANNEL_NAME = "PYRSA"

    /** Prefix of the person ID hash, followed by `\n` root public key (ADR 0042). */
    const val PERSON = "anmp/v1/person"

    /** Signed bytes of a person certificate (root key → person key), followed by `\n` body. */
    const val PERSON_CERT = "anmp/v1/person-cert"

    /** Signed bytes of a device list (person key → devices), followed by `\n` body. */
    const val DEVICES = "anmp/v1/devices"

    /** One-time pairing secret → key of the hand-over to an added device; also its associated data, followed by `\n` space. */
    const val PAIR = "anmp/v1/pair"

    /** One-time pairing secret ‖ Ed25519 key of the new device → comparison code shown on both devices (ADR 0093). */
    const val PAIR_CODE = "anmp/v1/pair-code"

    /** Recovery code → Ed25519 seed of the Kern-space (root key, ADR 0042 §2). */
    const val CORE_SIGN = "anmp/v1/core-sign"

    /** Recovery code → AES-256-GCM key of the Kern-space entry; also its associated data. */
    const val CORE_AEAD = "anmp/v1/core-aead"

    /** State secret → Ed25519 seed of the space with the current state of the person (ADR 0042 §2). */
    const val STATE_SIGN = "anmp/v1/state-sign"

    /** State secret → AES-256-GCM key of its entry; also its associated data. */
    const val STATE_AEAD = "anmp/v1/state-aead"

    /** Store secret → Ed25519 seed of the password space (ADR 0042, passwords like the store). */
    const val VAULT_SIGN = "anmp/v1/vault-sign"

    /** Store secret → AES-256-GCM key of the password space entry; also its associated data. */
    const val VAULT_AEAD = "anmp/v1/vault-aead"

    /** Store secret → Ed25519 seed of the profile space: name, picture and settings of the person (ADR 0050). */
    const val PROFILE_SIGN = "anmp/v1/profile-sign"

    /** Store secret → AES-256-GCM key of the profile space entry; also its associated data. */
    const val PROFILE_AEAD = "anmp/v1/profile-aead"

    /** Signed bytes of an alert (ADR 0062), followed directly by the body bytes. */
    const val ALERT = "anmp/v1/alert"

    /** X25519 shared secret → key of an alert sealed to the read key; followed by both public keys (ADR 0062). */
    const val ALERT_SEAL = "anmp/v1/alert-seal"

    /** Prefix of the hashed incident key of an alert (ADR 0062 §4), followed by the key in UTF-8; SHA-256, first 8 bytes. */
    const val ALERT_KEY = "anmp/v1/alert-key"

    /** Device address token → Ed25519 seed of its ingress space (ADR 0062). */
    const val INGRESS_SIGN = "anmp/v1/ingress-sign"

    /** Device address token → AES-256-GCM key of the ingress record (ADR 0062). */
    const val INGRESS_AEAD = "anmp/v1/ingress-aead"

    /** Signed bytes of a new read key for a subscriber (ADR 0062 §11), followed by space, `0x00`, read key and grant. */
    const val CHANNEL_KEY = "anmp/v1/channel-key"

    /** Private device key (seed) → Ed25519 seed of the daily stats key, followed by `\n` day (ADR 0045). */
    const val STATS = "anmp/v1/stats"

    /** Private person key (seed) → person marker of a day, followed by `\n` day (ADR 0045). */
    const val STATS_PERSON = "anmp/v1/stats-person"

    /** Signed bytes of a stats report, followed by `\n` and the body bytes (ADR 0045). */
    const val STATS_SIG = "anmp/v1/stats-sig"

    /** Prefix of the circle radio channel names of the app before ADR 0051; apps remove such channels. The value stays to recognize them. */
    const val CHANNEL_PREFIX = "PYRSA-"
}
