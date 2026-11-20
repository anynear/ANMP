package anmp

import (
	"bytes"
	"crypto/ed25519"
	"errors"
	"fmt"
	"math"
	"strings"
	"unicode/utf16"

	"github.com/fxamacker/cbor/v2"
)

// Kind of a message. The code is on the wire and never changes.
type Kind int

const (
	KindCircle  Kind = 1
	KindDirect  Kind = 2
	KindReact   Kind = 3
	KindRead    Kind = 4
	KindReceipt Kind = 5
	KindMember  Kind = 10
	KindMeta    Kind = 11
	KindProfile Kind = 12
	KindWant    Kind = 13
	KindHave    Kind = 14
	KindHistory Kind = 15
	KindLeft    Kind = 16
	KindRekey   Kind = 17
	KindRemoved Kind = 18
	KindSync    Kind = 19
	KindAsk     Kind = 20
	KindPatron  Kind = 21
	KindPickup  Kind = 22
	// ADR 0062 §11: channel subscribers.
	KindSubscribe   Kind = 23
	KindUnsubscribe Kind = 24
	KindChannelKey  Kind = 25
	// ADR 0068: the author withdraws an own message.
	KindDelete Kind = 26
	// ADR 0083: sign of life in a chosen circle and helpers' answers.
	KindAlive Kind = 27
	KindCare  Kind = 28
	// ADR 0088: receipt entry with the refs of a message without expiry; readers drop it.
	KindHold Kind = 29
	// ADR 0121: where the sender's person is, for one other person; also over radio, seldom.
	KindPosition Kind = 30
	// ADR 0083 §2: a person calls its helpers, or a helper answers; in a circle also over radio.
	KindSos Kind = 31
)

var kindConv = map[Kind]string{
	KindCircle: "circle", KindDirect: "direct", KindReact: "react", KindRead: "read", KindReceipt: "receipt",
	KindMember: "member", KindMeta: "meta", KindProfile: "profile", KindWant: "want", KindHave: "have",
	KindHistory: "history", KindLeft: "left", KindRekey: "rekey", KindRemoved: "removed", KindSync: "sync",
	KindAsk: "ask", KindPatron: "patron", KindPickup: "pickup",
	KindSubscribe: "subscribe", KindUnsubscribe: "unsubscribe", KindChannelKey: "channel-key",
	KindDelete: "delete", KindAlive: "alive", KindCare: "care", KindHold: "hold",
	KindPosition: "position", KindSos: "sos",
}

// Known reports whether k is a kind of ANMP v1; readers drop packets of unknown kinds.
func (k Kind) Known() bool { _, ok := kindConv[k]; return ok }

// String is the name of the kind in the radio rule (ADR 0015).
func (k Kind) String() string {
	if c, ok := kindConv[k]; ok {
		return c
	}
	return fmt.Sprintf("kind(%d)", int(k))
}

// Open reports kinds that are sent open, as header without ciphertext (ADR 0051).
func (k Kind) Open() bool { return k == KindAsk || k == KindPatron }

// Radio is the radio rule (ADR 0015, 0057): kinds that may go over radio. Radio carries what people write
// (messages, reactions, read and delivery receipts), signs of life and their answers (ADR 0083) and the patron
// exchange; everything else waits for the internet.
func (k Kind) Radio() bool {
	switch k {
	case KindCircle, KindDirect, KindReact, KindDelete, KindRead, KindReceipt, KindAsk, KindPatron, KindPickup, KindAlive, KindCare, KindSos, KindPosition:
		return true
	}
	return false
}

// Header is the header in clear. Over radio (public form, ADR 0051) From is the hourly sender tag, Route the hourly
// route tag of the recipient device or circle, To is absent (except in a pickup). Nil means absent.
type Header struct {
	Kind         Kind
	From         []byte   // 1: tag of the sender, 8
	To           []byte   // 2: tag of the recipient, 8
	Epoch        *int64   // 3: circle key epoch
	SenderKey    []byte   // 4: X25519 public key of the sender, 32
	SenderDevice []byte   // 5: device ID of the sender, 16
	Route        []byte   // 6: route tag, 8
	Since        *int64   // 7: ask, Unix seconds
	AskTags      [][]byte // 8: ask, shortened route tags, at most 8 of 6 bytes
	Waiting      *int64   // 9: patron, packets held for the asker
	Hour         *int64   // 10: pickup, hour up to which the windows reach
}

// Int gives a pointer to v, for the optional numbers of Header and Content.
func Int(v int64) *int64 { return &v }

// Str gives a pointer to v, for the optional texts of Content.
func Str(v string) *string { return &v }

// wireHeader is the CBOR map of a header; fields in ascending key order, defaults omitted.
type wireHeader struct {
	K *int64   `cbor:"0,keyasint,omitempty"`
	F []byte   `cbor:"1,keyasint,omitempty"`
	T []byte   `cbor:"2,keyasint,omitempty"`
	E *int64   `cbor:"3,keyasint,omitempty"`
	X []byte   `cbor:"4,keyasint,omitempty"`
	D []byte   `cbor:"5,keyasint,omitempty"`
	R []byte   `cbor:"6,keyasint,omitempty"`
	N *int64   `cbor:"7,keyasint,omitempty"`
	Q [][]byte `cbor:"8,keyasint,omitempty"`
	W *int64   `cbor:"9,keyasint,omitempty"`
	O *int64   `cbor:"10,keyasint,omitempty"`
}

// Content is the encrypted content, limited to the fields that go over radio (0–6, 10, 32, 35, 36). Readers skip all
// other fields; an internet packet opened here keeps them only in its original bytes.
type Content struct {
	ID      []byte   `cbor:"0,keyasint,omitempty"`  // message ID, 8
	Ts      int64    `cbor:"1,keyasint,omitempty"`  // sender time, seconds
	Text    *string  `cbor:"2,keyasint,omitempty"`  // text or attachment announcement
	Ref     []byte   `cbor:"3,keyasint,omitempty"`  // message ID a reaction refers to
	RefFrom []byte   `cbor:"4,keyasint,omitempty"`  // tag of its author
	Emoji   *string  `cbor:"5,keyasint,omitempty"`  // reaction, "" = withdrawn
	IDs     [][]byte `cbor:"6,keyasint,omitempty"`  // confirmed message IDs
	Node    *int64   `cbor:"10,keyasint,omitempty"` // Meshtastic node number (ADR 0058)
	Arrived *int64   `cbor:"32,keyasint,omitempty"` // receipt: bit 0 internet, bit 1 radio
	Alive   *Alive   `cbor:"35,keyasint,omitempty"` // sign of life (kind alive, ADR 0083 §5)
	Care    *Care    `cbor:"36,keyasint,omitempty"` // answer of a helper (kind care, ADR 0083 §4)
}

// Alive is a sign of life (content field 35, ADR 0083 §5): every packet carries the whole plan. Times in Unix
// seconds by the sender's clock, durations in seconds. Fields 0–7 are required; a content without one of them is
// invalid, as in kotlin/format.
type Alive struct {
	Plan    []byte  `cbor:"0,keyasint"`           // plan ID, the message ID of its start, 8
	State   int64   `cbor:"1,keyasint"`           // 1 start … 7 timeout; readers take unknown states as 2 (ok)
	Start   int64   `cbor:"2,keyasint"`           // start
	Until   int64   `cbor:"3,keyasint"`           // latest end
	Due     int64   `cbor:"4,keyasint"`           // next sign of life at the latest
	Hint    int64   `cbor:"5,keyasint"`           // after due: hint
	Alarm   int64   `cbor:"6,keyasint"`           // after due: alarm
	Quiet   int64   `cbor:"7,keyasint"`           // how long the phone may lie still
	Motion  bool    `cbor:"8,keyasint,omitempty"` // movement counts as a sign of life
	Label   *string `cbor:"9,keyasint,omitempty"` // name of the template, at most 40 characters; over radio left out if it does not fit
	Battery *int64  `cbor:"10,keyasint,omitempty"`
	// Position is where the sender's phone is (ADR 0121 §10): only in a packet with state missed and only if the
	// person allowed it. Field 11 is reserved.
	Position *Position `cbor:"12,keyasint,omitempty"`
}

// Position is a place on earth (ADR 0121 §10): Lat and Lon in degrees × 10 000, north and east positive (about
// 10 m), At the time the device located it in Unix seconds, Acc the accuracy in metres if known. Fields 0–2 are
// required.
type Position struct {
	Lat int64  `cbor:"0,keyasint"`
	Lon int64  `cbor:"1,keyasint"`
	At  int64  `cbor:"2,keyasint"`
	Acc *int64 `cbor:"3,keyasint,omitempty"`
}

// Limits of a position: degrees × PositionScale on the wire, an accuracy of at most PositionMaxAcc metres.
const (
	PositionScale  = 10000
	PositionMaxAcc = 100000
)

type positionWire Position

// UnmarshalCBOR rejects a position without latitude, longitude or time, or with 32-bit fields out of range.
func (p *Position) UnmarshalCBOR(data []byte) error {
	if err := requireKeys(data, 3); err != nil {
		return err
	}
	var w positionWire
	if err := decMode.Unmarshal(data, &w); err != nil {
		return err
	}
	for _, n := range []int64{w.Lat, w.Lon} {
		if n < math.MinInt32 || n > math.MaxInt32 {
			return errors.New("position: number out of range")
		}
	}
	if w.Acc != nil && (*w.Acc < math.MinInt32 || *w.Acc > math.MaxInt32) {
		return errors.New("position: accuracy out of range")
	}
	*p = Position(w)
	return nil
}

// Plausible reports a position on earth, located at some time, with an accuracy of at most PositionMaxAcc metres.
// Readers ignore any other position and keep the packet.
func (p *Position) Plausible() bool {
	if p.Lat < -90*PositionScale || p.Lat > 90*PositionScale || p.Lon < -180*PositionScale || p.Lon > 180*PositionScale {
		return false
	}
	return p.At > 0 && (p.Acc == nil || (*p.Acc >= 0 && *p.Acc <= PositionMaxAcc))
}

// Care is a helper's answer to a sign of life (content field 36, ADR 0083 §4): 1 looking, 2 away, 3 back. Both
// fields are required.
type Care struct {
	Plan  []byte `cbor:"0,keyasint"`
	State int64  `cbor:"1,keyasint"`
}

// States of a sign of life and of a helper's answer (ADR 0083).
const (
	AliveStart   = 1
	AliveOK      = 2
	AliveMoving  = 3
	AliveMissed  = 4
	AliveEnd     = 5
	AliveBattery = 6
	AliveTimeout = 7

	CareLooking = 1
	CareAway    = 2
	CareBack    = 3

	// AliveMaxLabel is the longest label in UTF-16 code units, as counted by the apps.
	AliveMaxLabel = 40
)

type aliveWire Alive

// UnmarshalCBOR rejects a sign of life without one of the fields 0–7 or with 32-bit fields out of range.
func (a *Alive) UnmarshalCBOR(data []byte) error {
	if err := requireKeys(data, 8); err != nil {
		return err
	}
	var w aliveWire
	if err := decMode.Unmarshal(data, &w); err != nil {
		return err
	}
	for _, n := range []int64{w.State, w.Hint, w.Alarm, w.Quiet} {
		if n < math.MinInt32 || n > math.MaxInt32 {
			return errors.New("alive: number out of range")
		}
	}
	if w.Battery != nil && (*w.Battery < math.MinInt32 || *w.Battery > math.MaxInt32) {
		return errors.New("alive: battery out of range")
	}
	*a = Alive(w)
	return nil
}

type careWire Care

// UnmarshalCBOR rejects an answer without plan or state.
func (c *Care) UnmarshalCBOR(data []byte) error {
	if err := requireKeys(data, 2); err != nil {
		return err
	}
	var w careWire
	if err := decMode.Unmarshal(data, &w); err != nil {
		return err
	}
	if w.State < math.MinInt32 || w.State > math.MaxInt32 {
		return errors.New("care: state out of range")
	}
	*c = Care(w)
	return nil
}

// requireKeys checks that the CBOR map data holds the integer keys 0 … n-1.
func requireKeys(data []byte, n int) error {
	var m map[int64]cbor.RawMessage
	if err := decMode.Unmarshal(data, &m); err != nil {
		return err
	}
	for k := int64(0); k < int64(n); k++ {
		if _, ok := m[k]; !ok {
			return fmt.Errorf("field %d missing", k)
		}
	}
	return nil
}

// Valid reports a plausible sign of life, as the apps check it before they take it (ADR 0083 §5): plan ID of 8
// bytes, due within start … until, plan at most 400 days, 0 < hint <= alarm <= 1 day, quiet 60 s … 1 day, label at
// most AliveMaxLabel characters.
func (a *Alive) Valid() bool {
	switch {
	case len(a.Plan) != IDBytes:
		return false
	case a.Start <= 0 || a.Until < a.Start || a.Due < a.Start || a.Due > a.Until:
		return false
	case a.Until-a.Start > 400*24*3600:
		return false
	case a.Hint <= 0 || a.Alarm < a.Hint || a.Alarm > 86400:
		return false
	case a.Quiet < 60 || a.Quiet > 86400:
		return false
	}
	return a.Label == nil || len(utf16.Encode([]rune(*a.Label))) <= AliveMaxLabel
}

// Valid reports an answer with a plan ID of 8 bytes and a known state.
func (c *Care) Valid() bool {
	return len(c.Plan) == IDBytes && c.State >= CareLooking && c.State <= CareBack
}

// wirePacket is [h, b]; also the signed array [h, c].
type wirePacket struct {
	_ struct{} `cbor:",toarray"`
	H []byte
	B []byte
}

var (
	encMode cbor.EncMode
	decMode cbor.DecMode
)

func init() {
	var err error
	// Struct fields are declared in ascending key order; integers and lengths are always shortest and definite.
	if encMode, err = (cbor.EncOptions{}).EncMode(); err != nil {
		panic(err)
	}
	// Readers skip unknown keys in header and content.
	if decMode, err = (cbor.DecOptions{}).DecMode(); err != nil {
		panic(err)
	}
}

func invalid(format string, a ...any) error {
	return fmt.Errorf("%w: "+format, append([]any{ErrInvalid}, a...)...)
}

// EncodeHeader gives the CBOR of a header (h).
func EncodeHeader(h *Header) ([]byte, error) {
	k := int64(h.Kind)
	return encMode.Marshal(&wireHeader{&k, h.From, h.To, h.Epoch, h.SenderKey, h.SenderDevice, h.Route, h.Since, h.AskTags, h.Waiting, h.Hour})
}

// DecodeHeader parses and checks a header (h): known kind and the lengths of fields 1, 2, 4, 5, 6, 8.
func DecodeHeader(h []byte) (*Header, error) {
	var w wireHeader
	if err := decMode.Unmarshal(h, &w); err != nil {
		return nil, invalid("header: %v", err)
	}
	if w.K == nil || !Kind(*w.K).Known() {
		return nil, invalid("unknown kind")
	}
	switch {
	case len(w.F) != TagBytes:
		return nil, invalid("from must have %d bytes", TagBytes)
	case w.T != nil && len(w.T) != TagBytes:
		return nil, invalid("to must have %d bytes", TagBytes)
	case w.X != nil && len(w.X) != KeyBytes:
		return nil, invalid("senderKey must have %d bytes", KeyBytes)
	case w.D != nil && len(w.D) != DeviceBytes:
		return nil, invalid("senderDevice must have %d bytes", DeviceBytes)
	case w.R != nil && len(w.R) != RouteTagBytes:
		return nil, invalid("route must have %d bytes", RouteTagBytes)
	case w.Q != nil && (len(w.Q) > AskMaxTags || !allLen(w.Q, AskTagBytes)):
		return nil, invalid("at most %d ask tags of %d bytes", AskMaxTags, AskTagBytes)
	case w.E != nil && (*w.E < math.MinInt32 || *w.E > math.MaxInt32):
		return nil, invalid("epoch out of range")
	case w.N != nil && *w.N < 0, w.O != nil && *w.O < 0:
		return nil, invalid("negative time")
	case w.W != nil && (*w.W < 0 || *w.W > math.MaxInt32):
		return nil, invalid("waiting out of range")
	}
	return &Header{Kind(*w.K), w.F, w.T, w.E, w.X, w.D, w.R, w.N, w.Q, w.W, w.O}, nil
}

func allLen(list [][]byte, n int) bool {
	for _, b := range list {
		if len(b) != n {
			return false
		}
	}
	return true
}

// EncodeContent gives the CBOR of a content (c).
func EncodeContent(c *Content) ([]byte, error) { return encMode.Marshal(c) }

// DecodeContent parses a content (c); fields other than the radio fields are skipped.
func DecodeContent(c []byte) (*Content, error) {
	var out Content
	if err := decMode.Unmarshal(c, &out); err != nil {
		return nil, invalid("content: %v", err)
	}
	return &out, nil
}

// IsANMP reports whether packet starts like an ANMP packet: longer than the magic and starting with it.
func IsANMP(packet []byte) bool { return len(packet) > len(Magic) && bytes.HasPrefix(packet, Magic) }

// FitsRadio reports whether a packet fits into one Meshtastic packet.
func FitsRadio(packet []byte) bool { return len(packet) <= RadioMaxBytes }

func parse(packet []byte) (*wirePacket, *Header, error) {
	if !IsANMP(packet) {
		return nil, nil, invalid("no ANMP magic")
	}
	var p wirePacket
	if err := decMode.Unmarshal(packet[len(Magic):], &p); err != nil {
		return nil, nil, invalid("packet: %v", err)
	}
	h, err := DecodeHeader(p.H)
	if err != nil {
		return nil, nil, err
	}
	return &p, h, nil
}

// Peek reads only the header, to choose the key; an error if it is no valid ANMP packet.
func Peek(packet []byte) (*Header, error) {
	_, h, err := parse(packet)
	return h, err
}

// AAD is "anmp/v1/aad\n" ‖ scope ‖ "\n" ‖ h.
func AAD(scope string, h []byte) []byte {
	return append([]byte(InfoAAD+"\n"+scope+"\n"), h...)
}

// SigningBytes is "anmp/v1/sig\n" ‖ scope ‖ "\n" ‖ CBOR([h, c]).
func SigningBytes(scope string, h, c []byte) ([]byte, error) {
	hc, err := encMode.Marshal(&wirePacket{H: h, B: c})
	if err != nil {
		return nil, err
	}
	return append([]byte(InfoSig+"\n"+scope+"\n"), hc...), nil
}

// Encoded is a built packet and its opened form ([h, c] or [h, c, s]) for the history.
type Encoded struct {
	Packet []byte
	Opened []byte
}

// Encode builds a sealed packet with the circle key or device pair key. signer signs with the sender's device key;
// nil = unsigned (radio only).
func Encode(h *Header, c *Content, scope string, key []byte, signer ed25519.PrivateKey) (*Encoded, error) {
	return encode(h, c, scope, key, signer, nil)
}

func encode(h *Header, c *Content, scope string, key []byte, signer ed25519.PrivateKey, nonce []byte) (*Encoded, error) {
	switch {
	case len(h.From) != TagBytes:
		return nil, fmt.Errorf("anmp: from must have %d bytes", TagBytes)
	case h.To != nil && len(h.To) != TagBytes:
		return nil, fmt.Errorf("anmp: to must have %d bytes", TagBytes)
	case h.Route != nil && len(h.Route) != RouteTagBytes:
		return nil, fmt.Errorf("anmp: route must have %d bytes", RouteTagBytes)
	case h.Kind.Open() || h.Kind == KindPickup:
		return nil, fmt.Errorf("anmp: %v has its own encoding", h.Kind)
	}
	hb, err := EncodeHeader(h)
	if err != nil {
		return nil, err
	}
	cb, err := EncodeContent(c)
	if err != nil {
		return nil, err
	}
	inner := []any{cb}
	opened := []any{hb, cb}
	if signer != nil {
		data, err := SigningBytes(scope, hb, cb)
		if err != nil {
			return nil, err
		}
		s := ed25519.Sign(signer, data)
		inner, opened = append(inner, s), append(opened, s)
	}
	ib, err := encMode.Marshal(inner)
	if err != nil {
		return nil, err
	}
	b, err := seal(key, ib, AAD(scope, hb), nonce)
	if err != nil {
		return nil, err
	}
	pb, err := encMode.Marshal(&wirePacket{H: hb, B: b})
	if err != nil {
		return nil, err
	}
	ob, err := encMode.Marshal(opened)
	if err != nil {
		return nil, err
	}
	return &Encoded{append(append([]byte(nil), Magic...), pb...), ob}, nil
}

// EncodeOpen builds an open packet (ask, patron, ADR 0051): header only, b is empty. Patrons read it without a key.
func EncodeOpen(h *Header) ([]byte, error) {
	switch {
	case !h.Kind.Open():
		return nil, fmt.Errorf("anmp: %v is not sent open", h.Kind)
	case len(h.From) != TagBytes:
		return nil, fmt.Errorf("anmp: from must have %d bytes", TagBytes)
	case len(h.AskTags) > AskMaxTags || !allLen(h.AskTags, AskTagBytes):
		return nil, fmt.Errorf("anmp: at most %d ask tags of %d bytes", AskMaxTags, AskTagBytes)
	}
	hb, err := EncodeHeader(h)
	if err != nil {
		return nil, err
	}
	pb, err := encMode.Marshal(&wirePacket{H: hb, B: []byte{}})
	if err != nil {
		return nil, err
	}
	return append(append([]byte(nil), Magic...), pb...), nil
}

// Verifier checks a packet signature over data with the sender's key (e.g. from the member directory by tag).
type Verifier func(h *Header, c *Content, data, sig []byte) bool

// WithKey accepts only signatures by publicKey.
func WithKey(publicKey ed25519.PublicKey) Verifier {
	return func(_ *Header, _ *Content, data, sig []byte) bool {
		return len(publicKey) == ed25519.PublicKeySize && ed25519.Verify(publicKey, data, sig)
	}
}

// Message is an opened packet. Opened is header, content and signature without encryption.
type Message struct {
	Header  *Header
	Content *Content
	Signed  bool
	Opened  []byte
}

// Decode opens a sealed packet with key. verify checks a present signature (nil rejects every signature); with
// requireSignature only signed packets are accepted.
func Decode(packet []byte, scope string, key []byte, verify Verifier, requireSignature bool) (*Message, error) {
	p, h, err := parse(packet)
	if err != nil {
		return nil, err
	}
	if h.Kind.Open() || h.Kind == KindPickup {
		return nil, invalid("%v has its own encoding", h.Kind)
	}
	plain, err := open(key, p.B, AAD(scope, p.H))
	if err != nil {
		return nil, err
	}
	var inner [][]byte
	if err := decMode.Unmarshal(plain, &inner); err != nil || len(inner) < 1 || len(inner) > 2 {
		return nil, invalid("inner")
	}
	c, err := DecodeContent(inner[0])
	if err != nil {
		return nil, err
	}
	opened := []any{p.H, inner[0]}
	signed := len(inner) == 2
	switch {
	case !signed && requireSignature:
		return nil, invalid("signature missing")
	case signed:
		s := inner[1]
		data, err := SigningBytes(scope, p.H, inner[0])
		if err != nil {
			return nil, err
		}
		if len(s) != SignatureBytes || verify == nil || !verify(h, c, data, s) {
			return nil, invalid("bad signature")
		}
		opened = append(opened, s)
	}
	ob, err := encMode.Marshal(opened)
	if err != nil {
		return nil, err
	}
	return &Message{h, c, signed, ob}, nil
}

func headSize(n int) int {
	switch {
	case n < 24:
		return 1
	case n < 256:
		return 2
	case n < 65536:
		return 3
	}
	return 5
}

func bstrSize(n int) int { return headSize(n) + n }

// Size is the exact packet size without keys: ciphertext and signature have fixed lengths (spec "Radio budget").
func Size(h *Header, c *Content, signed bool) (int, error) {
	hb, err := EncodeHeader(h)
	if err != nil {
		return 0, err
	}
	cb, err := EncodeContent(c)
	if err != nil {
		return 0, err
	}
	inner := 1 + bstrSize(len(cb))
	if signed {
		inner += bstrSize(SignatureBytes)
	}
	return len(Magic) + 1 + bstrSize(len(hb)) + bstrSize(inner+SealOverhead), nil
}

// MaxTextBytes is how many bytes of text (UTF-8) fit into a radio packet; content builds the content for a text.
// -1 if nothing fits.
func MaxTextBytes(h *Header, signed bool, content func(text string) *Content) int {
	fits := func(n int) bool {
		s, err := Size(h, content(strings.Repeat("x", n)), signed)
		return err == nil && s <= RadioMaxBytes
	}
	if !fits(0) {
		return -1
	}
	low, high := 0, RadioMaxBytes
	for low < high {
		mid := (low + high + 1) / 2
		if fits(mid) {
			low = mid
		} else {
			high = mid - 1
		}
	}
	return low
}

// errNotPublic is returned by CheckPublic.
var errNotPublic = errors.New("anmp: not in the public form")

// IsPublicForm reports a packet in the public form that goes into the radio inbox (ADR 0051): a valid ANMP packet
// of at most RadioMaxBytes bytes with a route tag, not open, without to and without senderKey.
func IsPublicForm(packet []byte) bool { return CheckPublic(packet) == nil }

// CheckPublic is IsPublicForm with the reason.
func CheckPublic(packet []byte) error {
	if !FitsRadio(packet) {
		return fmt.Errorf("%w: more than %d bytes", errNotPublic, RadioMaxBytes)
	}
	h, err := Peek(packet)
	if err != nil {
		return err
	}
	if h.Kind.Open() || h.Route == nil || h.To != nil || h.SenderKey != nil {
		return fmt.Errorf("%w: %v", errNotPublic, h.Kind)
	}
	return nil
}
