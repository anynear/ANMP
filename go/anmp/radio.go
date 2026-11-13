package anmp

import (
	"bytes"
	"crypto/ecdh"
	"crypto/rand"
	"fmt"
	"strconv"
)

// One radio channel for everyone and hourly tags (ADR 0051, spec "One radio channel").
const (
	// HourSeconds is the length of an hour; tags change with it.
	HourSeconds = 3600
	// EpochHours is the length of one chain epoch (about 170 days, ADR 0051 §5a).
	EpochHours = 4096
	// WindowHours is how far a patron may reach back: 30 days.
	WindowHours = 720
	// ChainBytes is the length of a chain value.
	ChainBytes = 16
	// PickupMaxWindows is the most pickup windows in one pickup packet.
	PickupMaxWindows = 3
)

// ChannelPSK is the pre-shared key of the radio channel: HKDF(UTF-8 "anynear", "anmp/v1/radio-channel"). Public.
func ChannelPSK() []byte { return HKDF([]byte(RadioChannelName), InfoRadioChannel) }

func floorDiv(a, b int64) int64 {
	q := a / b
	if (a%b != 0) && ((a < 0) != (b < 0)) {
		q--
	}
	return q
}

// HourOf gives the hour number of a Unix time in seconds.
func HourOf(unixSeconds int64) int64 { return floorDiv(unixSeconds, HourSeconds) }

// AcceptedHours are the hours whose tags a receiver accepts: the previous, the current and the next.
func AcceptedHours(unixSeconds int64) [3]int64 {
	h := HourOf(unixSeconds)
	return [3]int64{h - 1, h, h + 1}
}

// DeviceSecret is the radio secret of an installation: HKDF(device ID, "anmp/v1/radio-secret").
func DeviceSecret(deviceID []byte) ([]byte, error) {
	if len(deviceID) != DeviceBytes {
		return nil, fmt.Errorf("anmp: device ID must have %d bytes", DeviceBytes)
	}
	return HKDF(deviceID, InfoRadioSecret), nil
}

// CircleSecret is the radio secret of a circle: HKDF(circle secret, "anmp/v1/radio-secret").
func CircleSecret(circleSecret []byte) ([]byte, error) {
	if len(circleSecret) != 32 {
		return nil, fmt.Errorf("anmp: circle secret must have 32 bytes")
	}
	return HKDF(circleSecret, InfoRadioSecret), nil
}

// EpochOf gives the chain epoch of an hour.
func EpochOf(hour int64) int64 { return floorDiv(hour, EpochHours) }

// EpochStart is the first hour of an epoch.
func EpochStart(epoch int64) int64 { return epoch * EpochHours }

// EpochEnd is the last hour of an epoch.
func EpochEnd(epoch int64) int64 { return EpochStart(epoch) + EpochHours - 1 }

// ChainStep is one chain step: SHA-256("anmp/v1/route-chain\n" ‖ x)[0..16].
func ChainStep(x []byte) []byte { return sha([]byte(InfoRouteChain+"\n"), x)[:ChainBytes] }

// ForwardStart is the forward chain value at the start of epoch: HKDF(radio secret, "anmp/v1/route-fwd\n" epoch)[0..16].
func ForwardStart(radioSecret []byte, epoch int64) []byte {
	return HKDF(radioSecret, InfoRouteFwd+"\n"+strconv.FormatInt(epoch, 10))[:ChainBytes]
}

// BackwardEnd is the backward chain value at the end of epoch: HKDF(radio secret, "anmp/v1/route-back\n" epoch)[0..16].
func BackwardEnd(radioSecret []byte, epoch int64) []byte {
	return HKDF(radioSecret, InfoRouteBack+"\n"+strconv.FormatInt(epoch, 10))[:ChainBytes]
}

// ForwardAt is f(hour): from the epoch start, one step per hour.
func ForwardAt(radioSecret []byte, hour int64) []byte {
	e := EpochOf(hour)
	f := ForwardStart(radioSecret, e)
	for i := EpochStart(e); i < hour; i++ {
		f = ChainStep(f)
	}
	return f
}

// BackwardAt is b(hour): from the epoch end, one step per hour back.
func BackwardAt(radioSecret []byte, hour int64) []byte {
	e := EpochOf(hour)
	b := BackwardEnd(radioSecret, e)
	for i := EpochEnd(e); i > hour; i-- {
		b = ChainStep(b)
	}
	return b
}

// TagOfChains is the route tag from both chain values of an hour: SHA-256("anmp/v1/route-tag\n" ‖ f ‖ b)[0..8].
func TagOfChains(f, b []byte) []byte { return sha([]byte(InfoRouteTag+"\n"), f, b)[:RouteTagBytes] }

// RouteTag is the route tag of a device or circle in hour (ADR 0051 §5a).
func RouteTag(radioSecret []byte, hour int64) []byte {
	return TagOfChains(ForwardAt(radioSecret, hour), BackwardAt(radioSecret, hour))
}

// SenderTag is the sender tag of a device in hour: HKDF(radio secret of the device, "anmp/v1/sender-tag\n" hour)[0..8].
func SenderTag(deviceRadioSecret []byte, hour int64) []byte {
	return HKDF(deviceRadioSecret, InfoSenderTag+"\n"+strconv.FormatInt(hour, 10))[:TagBytes]
}

// AskTag is a route tag shortened for an ask.
func AskTag(routeTag []byte) []byte { return append([]byte(nil), routeTag[:AskTagBytes]...) }

// MatchesAsk reports whether routeTag matches the shortened askTag.
func MatchesAsk(routeTag, askTag []byte) bool {
	return len(askTag) == AskTagBytes && len(routeTag) >= AskTagBytes && bytes.Equal(routeTag[:AskTagBytes], askTag)
}

// WindowRange gives the hours a window of epoch covers for a pickup up to hour: the last 30 days, cut at the epoch
// borders. Empty if from > to.
func WindowRange(epoch, hour int64) (from, to int64) {
	return max(hour-WindowHours+1, EpochStart(epoch)), min(hour, EpochEnd(epoch))
}

// PickupWindow holds the pickup keys of one target and epoch: f(from) and b(to) of WindowRange. CBOR [epoch, f, b].
type PickupWindow struct {
	_     struct{} `cbor:",toarray"`
	Epoch int64
	F     []byte
	B     []byte
}

// PickupWindows gives the windows of one target for the last 30 days up to hour: one, or two across an epoch border.
func PickupWindows(radioSecret []byte, hour int64) []PickupWindow {
	epochs := []int64{EpochOf(hour - WindowHours + 1)}
	if e := EpochOf(hour); e != epochs[0] {
		epochs = append(epochs, e)
	}
	out := make([]PickupWindow, 0, len(epochs))
	for _, e := range epochs {
		from, to := WindowRange(e, hour)
		out = append(out, PickupWindow{Epoch: e, F: ForwardAt(radioSecret, from), B: BackwardAt(radioSecret, to)})
	}
	return out
}

// HourTag is a route tag and its hour.
type HourTag struct {
	Hour int64
	Tag  []byte
}

// WindowTags gives every (hour, route tag) a window yields for a pickup up to hour, and no others.
func WindowTags(w PickupWindow, hour int64) []HourTag {
	from, to := WindowRange(w.Epoch, hour)
	if from > to || len(w.F) != ChainBytes || len(w.B) != ChainBytes {
		return nil
	}
	n := int(to - from + 1)
	backs := make([][]byte, n)
	b := w.B
	for i := range n {
		if i > 0 {
			b = ChainStep(b)
		}
		backs[i] = b
	}
	out := make([]HourTag, n)
	f := w.F
	for i := range n {
		if i > 0 {
			f = ChainStep(f)
		}
		out[i] = HourTag{from + int64(i), TagOfChains(f, backs[n-1-i])}
	}
	return out
}

// Ephemeral is a one-time X25519 key of an asker or a patron (ADR 0051 §5a).
type Ephemeral struct{ key *ecdh.PrivateKey }

// NewEphemeral makes a random one-time key.
func NewEphemeral() (*Ephemeral, error) {
	raw := make([]byte, KeyBytes)
	if _, err := rand.Read(raw); err != nil {
		return nil, err
	}
	return EphemeralOf(raw)
}

// EphemeralOf takes a fixed private key (test vectors).
func EphemeralOf(privateRaw []byte) (*Ephemeral, error) {
	k, err := ecdh.X25519().NewPrivateKey(privateRaw)
	if err != nil {
		return nil, err
	}
	return &Ephemeral{k}, nil
}

// PublicKey is the raw X25519 public key.
func (e *Ephemeral) PublicKey() []byte { return e.key.PublicKey().Bytes() }

// SharedKey is HKDF(X25519(own, peerPublic), "anmp/v1/pickup").
func (e *Ephemeral) SharedKey(peerPublic []byte) ([]byte, error) {
	if len(peerPublic) != KeyBytes {
		return nil, fmt.Errorf("anmp: peer key must have %d bytes", KeyBytes)
	}
	peer, err := ecdh.X25519().NewPublicKey(peerPublic)
	if err != nil {
		return nil, err
	}
	shared, err := e.key.ECDH(peer)
	if err != nil {
		return nil, err
	}
	return HKDF(shared, InfoPickup), nil
}

// PickupPacket builds a pickup for one patron (ADR 0051 §5a): windows (1 to PickupMaxWindows) sealed to the
// patron's one-time key with the asker's one-time key mine. Header: sender tag, to = first 8 bytes of the patron
// key, senderKey = public key of mine, hour.
func PickupPacket(senderTag []byte, mine *Ephemeral, patronKey []byte, hour int64, windows []PickupWindow) ([]byte, error) {
	return pickupPacket(senderTag, mine, patronKey, hour, windows, nil)
}

func pickupPacket(senderTag []byte, mine *Ephemeral, patronKey []byte, hour int64, windows []PickupWindow, nonce []byte) ([]byte, error) {
	if len(windows) < 1 || len(windows) > PickupMaxWindows {
		return nil, fmt.Errorf("anmp: 1 to %d windows", PickupMaxWindows)
	}
	if len(patronKey) != KeyBytes {
		return nil, fmt.Errorf("anmp: patron key must have %d bytes", KeyBytes)
	}
	if len(senderTag) != TagBytes {
		return nil, fmt.Errorf("anmp: sender tag must have %d bytes", TagBytes)
	}
	key, err := mine.SharedKey(patronKey)
	if err != nil {
		return nil, err
	}
	hb, err := EncodeHeader(&Header{Kind: KindPickup, From: senderTag, To: patronKey[:TagBytes], SenderKey: mine.PublicKey(), Hour: &hour})
	if err != nil {
		return nil, err
	}
	payload, err := encMode.Marshal(windows)
	if err != nil {
		return nil, err
	}
	b, err := seal(key, payload, AAD(InfoPickup, hb), nonce)
	if err != nil {
		return nil, err
	}
	pb, err := encMode.Marshal(&wirePacket{H: hb, B: b})
	if err != nil {
		return nil, err
	}
	return append(append([]byte(nil), Magic...), pb...), nil
}

// Pickup is an opened pickup: windows up to Hour.
type Pickup struct {
	Hour    int64
	Windows []PickupWindow
}

// OpenPickup opens a pickup meant for the patron key mine; an error if it is for another patron or invalid.
func OpenPickup(packet []byte, mine *Ephemeral) (*Pickup, error) {
	p, h, err := parse(packet)
	if err != nil {
		return nil, err
	}
	if h.Kind != KindPickup || h.Hour == nil {
		return nil, invalid("no pickup")
	}
	if !bytes.Equal(h.To, mine.PublicKey()[:TagBytes]) {
		return nil, invalid("pickup for another patron")
	}
	if h.SenderKey == nil {
		return nil, invalid("pickup without asker key")
	}
	key, err := mine.SharedKey(h.SenderKey)
	if err != nil {
		return nil, invalid("asker key: %v", err)
	}
	plain, err := open(key, p.B, AAD(InfoPickup, p.H))
	if err != nil {
		return nil, err
	}
	var windows []PickupWindow
	if err := decMode.Unmarshal(plain, &windows); err != nil {
		return nil, invalid("windows: %v", err)
	}
	if len(windows) < 1 || len(windows) > PickupMaxWindows {
		return nil, invalid("1 to %d windows", PickupMaxWindows)
	}
	return &Pickup{*h.Hour, windows}, nil
}
