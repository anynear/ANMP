// Package anmp implements the radio part of ANMP (anynear Message Protocol) version v1 as specified in
// spec/anmp.md, as far as a bridge in a relay needs it: frame and header, open packets (ask, patron), sealed
// packets and their size, the public form with hourly sender and route tags, pickup windows and chains, the radio
// rule and budget, the PSK of the radio channel and the content of the radio list. It has no state.
//
// Reference implementation: kotlin/format (package app.anynear.anmp). Both are checked against
// vectors/anmp-v1.json and vectors/anmp-v1-radio-list.json.
package anmp

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
)

// Magic starts every ANMP packet: "AN", version v1. The third byte is the only version field.
var Magic = []byte{0x41, 0x4E, 0x01}

const (
	// TagBytes is the length of an ANMP tag (first 8 bytes of the device ID) and of a sender tag.
	TagBytes = 8
	// IDBytes is the length of a message ID.
	IDBytes = 8
	// DeviceBytes is the length of a device ID.
	DeviceBytes = 16
	// KeyBytes is the length of an X25519 or Ed25519 public key.
	KeyBytes = 32
	// SignatureBytes is the length of an Ed25519 signature.
	SignatureBytes = 64
	// NonceBytes and SealTagBytes of AES-256-GCM; a sealed form is nonce ‖ ciphertext ‖ tag.
	NonceBytes   = 12
	SealTagBytes = 16
	// SealOverhead is what sealing adds to the plaintext.
	SealOverhead = NonceBytes + SealTagBytes
	// RadioMaxBytes is the payload limit of a Meshtastic packet.
	RadioMaxBytes = 233
	// DirectScope is the scope of direct packets.
	DirectScope = ""
	// RouteTagBytes is the length of a route tag in the public form (ADR 0051).
	RouteTagBytes = 8
	// AskTagBytes is the length of a shortened route tag in an ask.
	AskTagBytes = 6
	// AskMaxTags is the most route tags in one ask: own device and up to 7 circles.
	AskMaxTags = 8
)

// Info strings of HKDF and the other derivations ("anmp/v1/…").
const (
	Salt             = "anmp"
	InfoAAD          = "anmp/v1/aad"
	InfoSig          = "anmp/v1/sig"
	InfoDevice       = "anmp/v1/device"
	InfoRadioChannel = "anmp/v1/radio-channel"
	InfoRadioSecret  = "anmp/v1/radio-secret"
	InfoRouteTag     = "anmp/v1/route-tag"
	InfoRouteFwd     = "anmp/v1/route-fwd"
	InfoRouteBack    = "anmp/v1/route-back"
	InfoRouteChain   = "anmp/v1/route-chain"
	InfoSenderTag    = "anmp/v1/sender-tag"
	InfoPickup       = "anmp/v1/pickup"
	InfoStats        = "anmp/v1/stats"
	InfoStatsPerson  = "anmp/v1/stats-person"
	InfoStatsSig     = "anmp/v1/stats-sig"
	// RadioChannelName is the one radio channel of anynear (ADR 0051).
	RadioChannelName = "anynear"
)

// ErrInvalid is wrapped by every error about bytes that are no valid ANMP; readers drop such packets silently.
var ErrInvalid = errors.New("anmp: invalid packet")

// B64 is base64url without padding, as used for IDs and spaces.
var B64 = base64.RawURLEncoding

// HKDF is HKDF-SHA256 with salt "anmp" and 32 bytes of output.
func HKDF(secret []byte, info string) []byte {
	k, err := hkdf.Key(sha256.New, secret, []byte(Salt), info, 32)
	if err != nil {
		panic(err) // only for output lengths HKDF cannot give
	}
	return k
}

func sha(parts ...[]byte) []byte {
	h := sha256.New()
	for _, p := range parts {
		h.Write(p)
	}
	return h.Sum(nil)
}

// seal is AES-256-GCM, returning nonce ‖ ciphertext ‖ tag. A nil nonce takes a random one; fixed nonces only for
// test vectors.
func seal(key, plaintext, aad, nonce []byte) ([]byte, error) {
	g, err := gcm(key)
	if err != nil {
		return nil, err
	}
	if nonce == nil {
		nonce = make([]byte, NonceBytes)
		if _, err := rand.Read(nonce); err != nil {
			return nil, err
		}
	} else if len(nonce) != NonceBytes {
		return nil, errors.New("anmp: nonce must have 12 bytes")
	}
	return g.Seal(append([]byte(nil), nonce...), nonce, plaintext, aad), nil
}

func open(key, sealed, aad []byte) ([]byte, error) {
	if len(sealed) < SealOverhead {
		return nil, ErrInvalid
	}
	g, err := gcm(key)
	if err != nil {
		return nil, err
	}
	plain, err := g.Open(nil, sealed[:NonceBytes], sealed[NonceBytes:], aad)
	if err != nil {
		return nil, ErrInvalid
	}
	return plain, nil
}

func gcm(key []byte) (cipher.AEAD, error) {
	if len(key) != 32 {
		return nil, errors.New("anmp: AES-256 key must have 32 bytes")
	}
	b, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(b)
}

// DeviceIDOf gives the device ID of an Ed25519 public key: SHA-256("anmp/v1/device\n" ‖ key)[0..16].
func DeviceIDOf(signKey []byte) []byte {
	return sha([]byte(InfoDevice+"\n"), signKey)[:DeviceBytes]
}

// TagOf gives the ANMP tag of a device ID: its first 8 bytes.
func TagOf(deviceID []byte) []byte { return append([]byte(nil), deviceID[:TagBytes]...) }
