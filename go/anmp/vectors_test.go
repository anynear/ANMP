package anmp

import (
	"bytes"
	"crypto/ed25519"
	"encoding/hex"
	"encoding/json"
	"os"
	"strconv"
	"strings"
	"testing"
)

// Test vectors: vectors/anmp-v1.json is written by kotlin/format VectorsTest (UPDATE_VECTORS=1); Go computes the
// same values from the inputs and compares, as VectorsTest does.

const vectorsPath = "../../vectors/anmp-v1.json"

// vectorTs is the time of the vectors (content ts, radio hour).
const vectorTs = 1_760_000_000

type vectors map[string]any

func loadVectors(t *testing.T, path string) vectors {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var v vectors
	d := json.NewDecoder(bytes.NewReader(data))
	d.UseNumber()
	if err := d.Decode(&v); err != nil {
		t.Fatal(err)
	}
	return v
}

// at walks a dotted path of objects.
func (v vectors) at(t *testing.T, path string) any {
	t.Helper()
	var cur any = map[string]any(v)
	for _, k := range strings.Split(path, ".") {
		m, ok := cur.(map[string]any)
		if !ok {
			t.Fatalf("vectors: %s is no object at %s", path, k)
		}
		if cur, ok = m[k]; !ok {
			t.Fatalf("vectors: %s missing", path)
		}
	}
	return cur
}

func (v vectors) str(t *testing.T, path string) string {
	t.Helper()
	s, ok := v.at(t, path).(string)
	if !ok {
		t.Fatalf("vectors: %s is no string", path)
	}
	return s
}

func (v vectors) hex(t *testing.T, path string) []byte { t.Helper(); return unhex(t, v.str(t, path)) }

func (v vectors) int(t *testing.T, path string) int64 { t.Helper(); return num(t, v.at(t, path)) }

func num(t *testing.T, x any) int64 {
	t.Helper()
	n, ok := x.(json.Number)
	if !ok {
		t.Fatalf("vectors: %v is no number", x)
	}
	i, err := n.Int64()
	if err != nil {
		t.Fatal(err)
	}
	return i
}

func unhex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func eq(t *testing.T, name string, want, got []byte) {
	t.Helper()
	if !bytes.Equal(want, got) {
		t.Errorf("%s:\n want %x\n got  %x", name, want, got)
	}
}

func must[T any](t *testing.T) func(T, error) T {
	return func(v T, err error) T {
		t.Helper()
		if err != nil {
			t.Fatal(err)
		}
		return v
	}
}

func nonce(n byte) []byte {
	b := []byte{0xf0, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa, n}
	return b
}

type fixture struct {
	circleSecret, circleKey []byte
	signA                   ed25519.PrivateKey
	deviceA, tagA           []byte
	deviceB, tagB           []byte
	pairKey                 []byte
	messageID               []byte
}

func fixtures(t *testing.T, v vectors) fixture {
	signA := ed25519.NewKeyFromSeed(v.hex(t, "inputs.deviceASignPrivate"))
	signB := ed25519.NewKeyFromSeed(v.hex(t, "inputs.deviceBSignPrivate"))
	devA := DeviceIDOf(signA.Public().(ed25519.PublicKey))
	devB := DeviceIDOf(signB.Public().(ed25519.PublicKey))
	return fixture{
		circleSecret: v.hex(t, "inputs.circleSecret"),
		circleKey:    v.hex(t, "circle.aeadKey"),
		signA:        signA,
		deviceA:      devA, tagA: TagOf(devA),
		deviceB: devB, tagB: TagOf(devB),
		pairKey:   v.hex(t, "direct.key"),
		messageID: unhex(t, "a0a1a2a3a4a5a6a7"),
	}
}

func TestDevices(t *testing.T) {
	v := loadVectors(t, vectorsPath)
	for _, d := range []string{"deviceA", "deviceB"} {
		id := DeviceIDOf(v.hex(t, d+".signPublicKey"))
		eq(t, d+".deviceId", v.hex(t, d+".deviceId"), id)
		eq(t, d+".tag", v.hex(t, d+".tag"), TagOf(id))
		if got := B64.EncodeToString(id); got != v.str(t, d+".deviceIdText") {
			t.Errorf("%s.deviceIdText: %s", d, got)
		}
	}
}

func TestPackets(t *testing.T) {
	v := loadVectors(t, vectorsPath)
	f := fixtures(t, v)
	scope := v.str(t, "inputs.circleId")

	circleH := &Header{Kind: KindCircle, From: f.tagA, Epoch: Int(2)}
	content := &Content{ID: f.messageID, Ts: vectorTs, Text: Str("Hallo")}
	h := must[[]byte](t)(EncodeHeader(circleH))
	c := must[[]byte](t)(EncodeContent(content))
	eq(t, "cbor.headerCircle", v.hex(t, "cbor.headerCircle"), h)
	eq(t, "cbor.content", v.hex(t, "cbor.content"), c)
	eq(t, "circleSigned.aad", v.hex(t, "packets.circleSigned.aad"), AAD(scope, h))
	eq(t, "circleSigned.signingBytes", v.hex(t, "packets.circleSigned.signingBytes"), must[[]byte](t)(SigningBytes(scope, h, c)))
	circle := must[*Encoded](t)(encode(circleH, content, scope, f.circleKey, f.signA, nonce(1)))
	eq(t, "circleSigned.packet", v.hex(t, "packets.circleSigned.packet"), circle.Packet)
	eq(t, "circleSigned.opened", v.hex(t, "packets.circleSigned.opened"), circle.Opened)
	m := must[*Message](t)(Decode(circle.Packet, scope, f.circleKey, WithKey(f.signA.Public().(ed25519.PublicKey)), true))
	if *m.Content.Text != "Hallo" || !m.Signed {
		t.Errorf("circleSigned opened: %+v", m)
	}
	eq(t, "circleSigned.opened by Decode", circle.Opened, m.Opened)

	directH := &Header{Kind: KindDirect, From: f.tagA, To: f.tagB}
	eq(t, "cbor.headerDirect", v.hex(t, "cbor.headerDirect"), must[[]byte](t)(EncodeHeader(directH)))
	direct := must[*Encoded](t)(encode(directH, &Content{ID: f.messageID, Ts: vectorTs, Text: Str("Nur für dich")}, DirectScope, f.pairKey, nil, nonce(2)))
	eq(t, "directRadio.packet", v.hex(t, "packets.directRadio.packet"), direct.Packet)
	dm := must[*Message](t)(Decode(direct.Packet, DirectScope, f.pairKey, nil, false))
	if *dm.Content.Text != "Nur für dich" || dm.Signed {
		t.Errorf("directRadio opened: %+v", dm)
	}

	// Internet packet: header fields 4 and 5, content fields beyond radio are skipped but stay in the opened bytes.
	net := v.hex(t, "packets.directInternet.packet")
	nh := must[*Header](t)(Peek(net))
	eq(t, "directInternet.header", v.hex(t, "packets.directInternet.header"), must[[]byte](t)(EncodeHeader(nh)))
	nm := must[*Message](t)(Decode(net, DirectScope, f.pairKey, WithKey(f.signA.Public().(ed25519.PublicKey)), true))
	eq(t, "directInternet.opened", v.hex(t, "packets.directInternet.opened"), nm.Opened)

	// Tampering with any byte of the sealed part makes the packet invalid.
	bad := bytes.Clone(circle.Packet)
	bad[len(bad)-1] ^= 1
	if _, err := Decode(bad, scope, f.circleKey, WithKey(f.signA.Public().(ed25519.PublicKey)), true); err == nil {
		t.Error("tampered packet opened")
	}
	if _, err := Decode(circle.Packet, "circle-2", f.circleKey, WithKey(f.signA.Public().(ed25519.PublicKey)), true); err == nil {
		t.Error("packet opened in another circle")
	}
	if _, err := Decode(direct.Packet, DirectScope, f.pairKey, nil, true); err == nil {
		t.Error("unsigned packet accepted although a signature is required")
	}
}

func TestRadioBudget(t *testing.T) {
	v := loadVectors(t, vectorsPath)
	f := fixtures(t, v)
	text := func(s string) *Content { return &Content{ID: f.messageID, Ts: vectorTs, Text: Str(s)} }
	circleH := &Header{Kind: KindCircle, From: f.tagA, Epoch: Int(3)}
	directH := &Header{Kind: KindDirect, From: f.tagA, To: f.tagB}
	for _, c := range []struct {
		path   string
		h      *Header
		signed bool
	}{
		{"radioBudget.circleSigned", circleH, true},
		{"radioBudget.circleUnsigned", circleH, false},
		{"radioBudget.direct", directH, false},
	} {
		if got := MaxTextBytes(c.h, c.signed, text); int64(got) != v.int(t, c.path) {
			t.Errorf("%s: %d", c.path, got)
		}
	}
}

func TestRadio(t *testing.T) {
	v := loadVectors(t, vectorsPath)
	f := fixtures(t, v)
	scope := v.str(t, "inputs.circleId")

	if v.str(t, "radio.channelName") != RadioChannelName {
		t.Error("radio.channelName")
	}
	eq(t, "radio.channelPsk", v.hex(t, "radio.channelPsk"), ChannelPSK())
	hour := HourOf(vectorTs)
	if hour != v.int(t, "radio.hour") {
		t.Fatalf("radio.hour: %d", hour)
	}
	devSecret := must[[]byte](t)(DeviceSecret(f.deviceA))
	circleSecret := must[[]byte](t)(CircleSecret(f.circleSecret))
	eq(t, "radio.deviceRadioSecret", v.hex(t, "radio.deviceRadioSecret"), devSecret)
	devRoute := RouteTag(devSecret, hour)
	eq(t, "radio.deviceRouteTag", v.hex(t, "radio.deviceRouteTag"), devRoute)
	sender := SenderTag(devSecret, hour)
	eq(t, "radio.deviceSenderTag", v.hex(t, "radio.deviceSenderTag"), sender)
	eq(t, "radio.circleRadioSecret", v.hex(t, "radio.circleRadioSecret"), circleSecret)
	circleRoute := RouteTag(circleSecret, hour)
	eq(t, "radio.circleRouteTag", v.hex(t, "radio.circleRouteTag"), circleRoute)
	publicCircle := &Header{Kind: KindCircle, From: sender, Epoch: Int(3), Route: circleRoute}
	eq(t, "radio.publicCircleHeader", v.hex(t, "radio.publicCircleHeader"), must[[]byte](t)(EncodeHeader(publicCircle)))
	eq(t, "radio.ask", v.hex(t, "radio.ask"), must[[]byte](t)(EncodeOpen(&Header{Kind: KindAsk, From: sender, Since: Int(vectorTs), AskTags: [][]byte{AskTag(circleRoute)}})))
	eq(t, "radio.patron", v.hex(t, "radio.patron"), must[[]byte](t)(EncodeOpen(&Header{Kind: KindPatron, From: sender,
		SenderKey: v.hex(t, "radio.pickup.patronPublic"), Waiting: Int(2)})))

	epoch := EpochOf(hour)
	if epoch != v.int(t, "radio.chains.epoch") {
		t.Errorf("radio.chains.epoch: %d", epoch)
	}
	eq(t, "radio.chains.forwardStart", v.hex(t, "radio.chains.forwardStart"), ForwardStart(devSecret, epoch))
	eq(t, "radio.chains.backwardEnd", v.hex(t, "radio.chains.backwardEnd"), BackwardEnd(devSecret, epoch))
	ws := PickupWindows(devSecret, hour)
	w := ws[len(ws)-1]
	from, to := WindowRange(w.Epoch, hour)
	if from != v.int(t, "radio.chains.windowFrom") || to != v.int(t, "radio.chains.windowTo") {
		t.Errorf("radio.chains window %d..%d", from, to)
	}
	eq(t, "radio.chains.windowF", v.hex(t, "radio.chains.windowF"), w.F)
	eq(t, "radio.chains.windowB", v.hex(t, "radio.chains.windowB"), w.B)

	text := func(s string) *Content { return &Content{ID: f.messageID, Ts: vectorTs, Text: Str(s)} }
	devB := must[[]byte](t)(DeviceSecret(f.deviceB))
	eq(t, "radio.deviceBRadioSecret", v.hex(t, "radio.deviceBRadioSecret"), devB)
	bRoute := RouteTag(devB, hour)
	eq(t, "radio.deviceBRouteTag", v.hex(t, "radio.deviceBRouteTag"), bRoute)
	bSender := SenderTag(devB, hour)
	eq(t, "radio.deviceBSenderTag", v.hex(t, "radio.deviceBSenderTag"), bSender)
	publicDirect := &Header{Kind: KindDirect, From: sender, Route: devRoute}
	for _, c := range []struct {
		path   string
		h      *Header
		signed bool
	}{
		{"radio.budget.circleSigned", publicCircle, true},
		{"radio.budget.circleUnsigned", publicCircle, false},
		{"radio.budget.direct", publicDirect, false},
	} {
		if got := MaxTextBytes(c.h, c.signed, text); int64(got) != v.int(t, c.path) {
			t.Errorf("%s: %d", c.path, got)
		}
	}

	// Public-form circle message, signed.
	cc := text("Hallo Funk")
	eq(t, "radio.circlePacket.header", v.hex(t, "radio.circlePacket.header"), must[[]byte](t)(EncodeHeader(publicCircle)))
	eq(t, "radio.circlePacket.content", v.hex(t, "radio.circlePacket.content"), must[[]byte](t)(EncodeContent(cc)))
	cp := must[*Encoded](t)(encode(publicCircle, cc, scope, f.circleKey, f.signA, nonce(0x0a)))
	eq(t, "radio.circlePacket.packet", v.hex(t, "radio.circlePacket.packet"), cp.Packet)
	eq(t, "radio.circlePacket.opened", v.hex(t, "radio.circlePacket.opened"), cp.Opened)
	sigData := must[[]byte](t)(SigningBytes(scope, v.hex(t, "radio.circlePacket.header"), v.hex(t, "radio.circlePacket.content")))
	eq(t, "radio.circlePacket.signingBytes", v.hex(t, "radio.circlePacket.signingBytes"), sigData)
	eq(t, "radio.circlePacket.signature", v.hex(t, "radio.circlePacket.signature"), ed25519.Sign(f.signA, sigData))
	if n := must[int](t)(Size(publicCircle, cc, true)); int64(n) != v.int(t, "radio.circlePacket.size") || n != len(cp.Packet) {
		t.Errorf("radio.circlePacket.size: %d", n)
	}
	if !IsPublicForm(cp.Packet) || v.at(t, "radio.circlePacket.publicForm") != true {
		t.Error("radio.circlePacket.publicForm")
	}

	// Public-form direct message to deviceB with node.
	dc := &Content{ID: f.messageID, Ts: vectorTs, Text: Str("Nur für dich"), Node: Int(0x12345678)}
	dh := &Header{Kind: KindDirect, From: sender, Route: bRoute}
	eq(t, "radio.directPacket.header", v.hex(t, "radio.directPacket.header"), must[[]byte](t)(EncodeHeader(dh)))
	eq(t, "radio.directPacket.content", v.hex(t, "radio.directPacket.content"), must[[]byte](t)(EncodeContent(dc)))
	dp := must[*Encoded](t)(encode(dh, dc, DirectScope, f.pairKey, nil, nonce(0x0b)))
	eq(t, "radio.directPacket.packet", v.hex(t, "radio.directPacket.packet"), dp.Packet)
	if n := must[int](t)(Size(dh, dc, false)); int64(n) != v.int(t, "radio.directPacket.size") || n != len(dp.Packet) {
		t.Errorf("radio.directPacket.size: %d", n)
	}
	dm := must[*Message](t)(Decode(dp.Packet, DirectScope, f.pairKey, nil, false))
	if *dm.Content.Node != 0x12345678 {
		t.Error("radio.directPacket node")
	}

	for _, e := range v.at(t, "radio.publicForm").([]any) {
		o := e.(map[string]any)
		p := unhex(t, o["packet"].(string))
		if got := IsPublicForm(p); got != o["publicForm"].(bool) {
			t.Errorf("radio.publicForm %s: %v (%v)", o["name"], got, CheckPublic(p))
		}
		if int64(len(p)) != num(t, o["size"]) {
			t.Errorf("radio.publicForm %s size", o["name"])
		}
	}
}

func TestRadioRuleAndHours(t *testing.T) {
	v := loadVectors(t, vectorsPath)
	kinds := v.at(t, "radio.rule.kinds").([]any)
	if len(kinds) != len(kindConv) {
		t.Errorf("radio.rule.kinds: %d kinds, Go knows %d", len(kinds), len(kindConv))
	}
	for _, e := range kinds {
		o := e.(map[string]any)
		k := Kind(num(t, o["code"]))
		if !k.Known() || k.String() != o["conv"] || k.Radio() != o["radio"] || k.Open() != o["open"] {
			t.Errorf("radio.rule %v: known %v, radio %v, open %v", o, k.Known(), k.Radio(), k.Open())
		}
	}
	f := fixtures(t, v)
	devSecret := must[[]byte](t)(DeviceSecret(f.deviceA))
	hours := v.at(t, "radio.hours.accepted").([]any)
	routes := v.at(t, "radio.hours.deviceRouteTags").([]any)
	senders := v.at(t, "radio.hours.deviceSenderTags").([]any)
	for i, h := range AcceptedHours(vectorTs) {
		if h != num(t, hours[i]) {
			t.Errorf("radio.hours.accepted[%d]: %d", i, h)
		}
		eq(t, "radio.hours.deviceRouteTags", unhex(t, routes[i].(string)), RouteTag(devSecret, h))
		eq(t, "radio.hours.deviceSenderTags", unhex(t, senders[i].(string)), SenderTag(devSecret, h))
	}
}

func checkTags(t *testing.T, name string, o map[string]any, w PickupWindow, hour int64) {
	t.Helper()
	tags := WindowTags(w, hour)
	if len(tags) == 0 {
		t.Fatalf("%s: no tags", name)
	}
	all := make([]byte, 0, len(tags)*RouteTagBytes)
	for _, ht := range tags {
		all = append(all, ht.Tag...)
	}
	if tags[0].Hour != num(t, o["from"]) || tags[len(tags)-1].Hour != num(t, o["to"]) || int64(len(tags)) != num(t, o["count"]) {
		t.Errorf("%s: hours %d..%d, %d tags", name, tags[0].Hour, tags[len(tags)-1].Hour, len(tags))
	}
	eq(t, name+".firstTag", unhex(t, o["firstTag"].(string)), tags[0].Tag)
	eq(t, name+".lastTag", unhex(t, o["lastTag"].(string)), tags[len(tags)-1].Tag)
	eq(t, name+".sha256", unhex(t, o["sha256"].(string)), sha(all))
}

func TestPickup(t *testing.T) {
	v := loadVectors(t, vectorsPath)
	f := fixtures(t, v)
	hour := v.int(t, "radio.pickup.hour")
	devSecret := must[[]byte](t)(DeviceSecret(f.deviceA))
	circleSecret := must[[]byte](t)(CircleSecret(f.circleSecret))
	sender := SenderTag(devSecret, hour)
	bSender := SenderTag(must[[]byte](t)(DeviceSecret(f.deviceB)), hour)

	ask := must[[]byte](t)(EncodeOpen(&Header{Kind: KindAsk, From: sender, Since: Int(vectorTs),
		AskTags: [][]byte{AskTag(RouteTag(devSecret, hour)), AskTag(RouteTag(circleSecret, hour))}}))
	eq(t, "radio.pickup.ask", v.hex(t, "radio.pickup.ask"), ask)
	ah := must[*Header](t)(Peek(ask))
	if !MatchesAsk(RouteTag(circleSecret, hour), ah.AskTags[1]) || MatchesAsk(RouteTag(circleSecret, hour+1), ah.AskTags[1]) {
		t.Error("MatchesAsk")
	}

	asker := must[*Ephemeral](t)(EphemeralOf(v.hex(t, "radio.pickup.askerPrivate")))
	patron := must[*Ephemeral](t)(EphemeralOf(v.hex(t, "radio.pickup.patronPrivate")))
	eq(t, "radio.pickup.askerPublic", v.hex(t, "radio.pickup.askerPublic"), asker.PublicKey())
	eq(t, "radio.pickup.patronPublic", v.hex(t, "radio.pickup.patronPublic"), patron.PublicKey())
	shared := must[[]byte](t)(asker.SharedKey(patron.PublicKey()))
	eq(t, "radio.pickup.sharedKey", v.hex(t, "radio.pickup.sharedKey"), shared)
	eq(t, "radio.pickup.sharedKey (patron side)", shared, must[[]byte](t)(patron.SharedKey(asker.PublicKey())))
	eq(t, "radio.pickup.patron", v.hex(t, "radio.pickup.patron"),
		must[[]byte](t)(EncodeOpen(&Header{Kind: KindPatron, From: bSender, SenderKey: patron.PublicKey(), Waiting: Int(2)})))

	secrets := map[string][]byte{"deviceA": devSecret, "circle": circleSecret}
	var windows []PickupWindow
	for _, e := range v.at(t, "radio.pickup.windows").([]any) {
		o := e.(map[string]any)
		w := PickupWindows(secrets[o["target"].(string)], hour)
		if len(w) != 1 || w[0].Epoch != num(t, o["epoch"]) {
			t.Fatalf("radio.pickup.windows %s: %+v", o["target"], w)
		}
		eq(t, "radio.pickup.windows.f", unhex(t, o["f"].(string)), w[0].F)
		eq(t, "radio.pickup.windows.b", unhex(t, o["b"].(string)), w[0].B)
		windows = append(windows, w[0])
	}
	packet := must[[]byte](t)(pickupPacket(sender, asker, patron.PublicKey(), hour, windows, nonce(0x0c)))
	eq(t, "radio.pickup.packet", v.hex(t, "radio.pickup.packet"), packet)

	opened := must[*Pickup](t)(OpenPickup(v.hex(t, "radio.pickup.packet"), patron))
	if opened.Hour != hour || len(opened.Windows) != len(windows) {
		t.Fatalf("OpenPickup: %+v", opened)
	}
	for i, e := range v.at(t, "radio.pickup.tags").([]any) {
		o := e.(map[string]any)
		checkTags(t, "radio.pickup.tags."+o["target"].(string), o, opened.Windows[i], hour)
	}
	if _, err := OpenPickup(packet, asker); err == nil {
		t.Error("pickup opened by a key it is not for")
	}
	other := must[*Ephemeral](t)(EphemeralOf(bytes.Repeat([]byte{7}, 32)))
	if _, err := OpenPickup(must[[]byte](t)(PickupPacket(sender, other, patron.PublicKey(), hour, windows)), asker); err == nil {
		t.Error("pickup for the patron opened by the asker")
	}
	if p := must[[]byte](t)(PickupPacket(sender, other, patron.PublicKey(), hour, windows)); bytes.Equal(p, packet) {
		t.Error("random nonce gave the vector bytes")
	}

	// Two windows 10 hours after an epoch border.
	border := v.int(t, "radio.border.hour")
	bw := PickupWindows(devSecret, border)
	list := v.at(t, "radio.border.windows").([]any)
	if len(bw) != 2 || len(list) != 2 {
		t.Fatalf("radio.border: %d windows", len(bw))
	}
	for i, e := range list {
		o := e.(map[string]any)
		if bw[i].Epoch != num(t, o["epoch"]) {
			t.Errorf("radio.border.windows[%d].epoch: %d", i, bw[i].Epoch)
		}
		eq(t, "radio.border.f", unhex(t, o["f"].(string)), bw[i].F)
		eq(t, "radio.border.b", unhex(t, o["b"].(string)), bw[i].B)
		checkTags(t, "radio.border", o, bw[i], border)
	}
}

func TestInvalid(t *testing.T) {
	v := loadVectors(t, vectorsPath)
	list := v.at(t, "radio.invalid").([]any)
	if len(list) == 0 {
		t.Fatal("radio.invalid is empty")
	}
	for _, e := range list {
		o := e.(map[string]any)
		if h, err := Peek(unhex(t, o["packet"].(string))); err == nil {
			t.Errorf("radio.invalid %s accepted: %+v", o["name"], h)
		}
	}
	for _, p := range []string{"radio.ask", "radio.patron", "radio.pickup.patron", "radio.pickup.packet"} {
		if _, err := Peek(v.hex(t, p)); err != nil {
			t.Errorf("%s: %v", p, err)
		}
	}
}

func TestStats(t *testing.T) {
	v := loadVectors(t, vectorsPath)
	day := StatsDay(vectorTs)
	if day != v.str(t, "stats.day") {
		t.Fatalf("day %s", day)
	}
	seed := StatsKeySeed(v.hex(t, "inputs.deviceASignPrivate"), day)
	eq(t, "keySeed", v.hex(t, "stats.keySeed"), seed)
	eq(t, "publicKey", v.hex(t, "stats.publicKey"), ed25519.NewKeyFromSeed(seed).Public().(ed25519.PublicKey))
	marker := StatsMarker(v.hex(t, "inputs.personPrivate"), day)
	eq(t, "marker", v.hex(t, "stats.marker"), marker)
	if got := StatsRelayIndex(marker, 5); int64(got) != v.int(t, "stats.relayIndexOf5") {
		t.Fatalf("relay index %d", got)
	}
	eq(t, "signingBytes", v.hex(t, "stats.report.signingBytes"), StatsSigningBytes(v.hex(t, "stats.report.bodyBytes")))
	report := v.hex(t, "stats.report.report")
	b, err := OpenStats(report)
	if err != nil {
		t.Fatal(err)
	}
	if b.Platform != "android" || b.Version != "0.4.2" || b.Radios != 1 || b.Channels != 2 || b.Devices != 2 || b.Circles != 3 ||
		len(b.Curated) != 1 || b.Curated[0] != "nina" || !bytes.Equal(b.Person, marker) || !SameStatsKey(v.hex(t, "inputs.deviceASignPrivate"), b) {
		t.Fatalf("body %+v", b)
	}
	for i := range report {
		bad := bytes.Clone(report)
		bad[i] ^= 1
		if _, err := OpenStats(bad); err == nil {
			t.Fatalf("byte %d flipped is accepted", i)
		}
	}
	sw, err := OpenStatsSwitch(v.hex(t, "stats.switchOff.report"))
	if err != nil || *sw.On || sw.Platform != "android" || sw.Day != day || !bytes.Equal(sw.Key, b.Key) {
		t.Fatalf("switch %+v %v", sw, err)
	}
	if _, err := OpenStatsSwitch(report); err == nil {
		t.Fatal("daily report taken as switch")
	}
	for ts, want := range map[int64]string{0: "1970-01-01", 951782399: "2000-02-28", 951782400: "2000-02-29", -1: "1969-12-31"} {
		if got := StatsDay(ts); got != want || v.str(t, "stats.days."+strconv.FormatInt(ts, 10)) != want {
			t.Fatalf("day of %d: %s", ts, got)
		}
	}
	versions := v.at(t, "stats.versions").(map[string]any)
	for raw, want := range map[string]string{"0.4.2": "0.4.2", "0.1.0.ab12cd": "0.1.0", "0.2.0-beta.1": "0.2.0", "dev": "other"} {
		if got := StatsVersion(raw); got != want || versions[raw] != want {
			t.Fatalf("version %s: %s", raw, got)
		}
	}
}

// Sign of life and a helper's answer over radio (ADR 0083): the same bytes as kotlin/format, signature, label budget
// and the contents every reader rejects.
func TestRadioAliveAndCare(t *testing.T) {
	v := loadVectors(t, vectorsPath)
	f := fixtures(t, v)
	scope := v.str(t, "inputs.circleId")
	hour := HourOf(vectorTs)
	devSecret := must[[]byte](t)(DeviceSecret(f.deviceA))
	circleSecret := must[[]byte](t)(CircleSecret(f.circleSecret))
	circleRoute := RouteTag(circleSecret, hour)
	signB := ed25519.NewKeyFromSeed(v.hex(t, "inputs.deviceBSignPrivate"))
	bSender := SenderTag(must[[]byte](t)(DeviceSecret(f.deviceB)), hour)

	aliveOf := func(label *string) *Alive {
		return &Alive{Plan: f.messageID, State: AliveStart, Start: vectorTs, Until: vectorTs + 8*3600, Due: vectorTs + 900,
			Hint: 300, Alarm: 600, Quiet: 900, Motion: true, Label: label, Battery: Int(80)}
	}
	ah := &Header{Kind: KindAlive, From: SenderTag(devSecret, hour), Epoch: Int(3), Route: circleRoute}
	ac := &Content{ID: f.messageID, Ts: vectorTs, Alive: aliveOf(Str("Wandern"))}
	eq(t, "radio.alivePacket.header", v.hex(t, "radio.alivePacket.header"), must[[]byte](t)(EncodeHeader(ah)))
	eq(t, "radio.alivePacket.content", v.hex(t, "radio.alivePacket.content"), must[[]byte](t)(EncodeContent(ac)))
	ap := must[*Encoded](t)(encode(ah, ac, scope, f.circleKey, f.signA, nonce(0x10)))
	eq(t, "radio.alivePacket.packet", v.hex(t, "radio.alivePacket.packet"), ap.Packet)
	eq(t, "radio.alivePacket.opened", v.hex(t, "radio.alivePacket.opened"), ap.Opened)
	if !IsPublicForm(ap.Packet) || int64(len(ap.Packet)) != v.int(t, "radio.alivePacket.size") {
		t.Error("radio.alivePacket size or public form")
	}
	am := must[*Message](t)(Decode(ap.Packet, scope, f.circleKey, WithKey(f.signA.Public().(ed25519.PublicKey)), true))
	if am.Content.Alive == nil || !am.Content.Alive.Valid() || *am.Content.Alive.Label != "Wandern" || am.Content.Alive.Quiet != 900 {
		t.Errorf("radio.alivePacket alive: %+v", am.Content.Alive)
	}
	if _, err := Decode(ap.Packet, scope, f.circleKey, WithKey(signB.Public().(ed25519.PublicKey)), true); err == nil {
		t.Error("radio.alivePacket opens with another member's key")
	}
	label := MaxTextBytes(ah, true, func(s string) *Content { return &Content{ID: f.messageID, Ts: vectorTs, Alive: aliveOf(&s)} })
	if int64(label) != v.int(t, "radio.alivePacket.labelBytes") {
		t.Errorf("radio.alivePacket.labelBytes: %d", label)
	}

	// The same plan after nobody confirmed, with the place of the phone (ADR 0121 §10).
	missed := aliveOf(Str("Wandern"))
	missed.State = AliveMissed
	missed.Position = &Position{Lat: 501106, Lon: 86821, At: vectorTs + 1080, Acc: Int(12)}
	mc := &Content{ID: unhex(t, "c0c1c2c3c4c5c6c7"), Ts: vectorTs + 1140, Alive: missed}
	eq(t, "radio.aliveMissedPacket.content", v.hex(t, "radio.aliveMissedPacket.content"), must[[]byte](t)(EncodeContent(mc)))
	mp := must[*Encoded](t)(encode(ah, mc, scope, f.circleKey, f.signA, nonce(0x12)))
	eq(t, "radio.aliveMissedPacket.packet", v.hex(t, "radio.aliveMissedPacket.packet"), mp.Packet)
	eq(t, "radio.aliveMissedPacket.opened", v.hex(t, "radio.aliveMissedPacket.opened"), mp.Opened)
	if !IsPublicForm(mp.Packet) || int64(len(mp.Packet)) != v.int(t, "radio.aliveMissedPacket.size") {
		t.Error("radio.aliveMissedPacket size or public form")
	}
	mm := must[*Message](t)(Decode(mp.Packet, scope, f.circleKey, WithKey(f.signA.Public().(ed25519.PublicKey)), true))
	if a := mm.Content.Alive; a == nil || !a.Valid() || a.State != AliveMissed || a.Position == nil || !a.Position.Plausible() ||
		a.Position.Lat != 501106 || a.Position.Lon != 86821 || a.Position.At != vectorTs+1080 || *a.Position.Acc != 12 {
		t.Errorf("radio.aliveMissedPacket alive: %+v", mm.Content.Alive)
	}
	for _, p := range []*Position{{Lat: 900001, Lon: 0, At: 1}, {Lat: 0, Lon: -1800001, At: 1}, {Lat: 0, Lon: 0, At: 0}, {Lat: 0, Lon: 0, At: 1, Acc: Int(100001)}} {
		if p.Plausible() {
			t.Errorf("position out of range is plausible: %+v", p)
		}
	}
	if !(&Position{Lat: -900000, Lon: -1800000, At: 1}).Plausible() {
		t.Error("south pole at the date line is not plausible")
	}

	// The call a sign of life turns into (ADR 0083 §5.4) has an ID everyone computes alike.
	eq(t, "radio.aliveSos.id", v.hex(t, "radio.aliveSos.id"), AliveSosID(v.hex(t, "radio.aliveSos.plan"), v.int(t, "radio.aliveSos.due")))
	if bytes.Equal(AliveSosID(f.messageID, vectorTs+900), AliveSosID(f.messageID, vectorTs+901)) {
		t.Error("the call of another due has the same ID")
	}

	ch := &Header{Kind: KindCare, From: bSender, Epoch: Int(3), Route: circleRoute}
	cc := &Content{ID: unhex(t, "b0b1b2b3b4b5b6b7"), Ts: vectorTs + 60, Care: &Care{Plan: f.messageID, State: CareLooking}}
	eq(t, "radio.carePacket.content", v.hex(t, "radio.carePacket.content"), must[[]byte](t)(EncodeContent(cc)))
	cp := must[*Encoded](t)(encode(ch, cc, scope, f.circleKey, signB, nonce(0x11)))
	eq(t, "radio.carePacket.packet", v.hex(t, "radio.carePacket.packet"), cp.Packet)
	eq(t, "radio.carePacket.opened", v.hex(t, "radio.carePacket.opened"), cp.Opened)
	cm := must[*Message](t)(Decode(cp.Packet, scope, f.circleKey, WithKey(signB.Public().(ed25519.PublicKey)), true))
	if cm.Content.Care == nil || !cm.Content.Care.Valid() || cm.Content.Care.State != CareLooking {
		t.Errorf("radio.carePacket care: %+v", cm.Content.Care)
	}

	for _, e := range v.at(t, "radio.invalidContent").([]any) {
		o := e.(map[string]any)
		if c, err := DecodeContent(unhex(t, o["content"].(string))); err == nil {
			t.Errorf("radio.invalidContent %s accepted: %+v", o["name"], c)
		}
	}

	bad := *aliveOf(nil)
	bad.Alarm = 200 // before the hint
	if bad.Valid() {
		t.Error("alarm before hint is valid")
	}
	long := strings.Repeat("ä", AliveMaxLabel+1)
	if (&Alive{Plan: f.messageID, State: 1, Start: 1, Until: 2, Due: 2, Hint: 1, Alarm: 1, Quiet: 60, Label: &long}).Valid() {
		t.Error("label over 40 characters is valid")
	}
	if (&Care{Plan: f.messageID, State: 4}).Valid() {
		t.Error("care state 4 is valid")
	}
}
