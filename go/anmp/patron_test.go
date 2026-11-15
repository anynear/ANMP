package anmp

import (
	"encoding/hex"
	"encoding/json"
	"os"
	"reflect"
	"testing"
	"time"
)

// Radio flow vectors: vectors/anmp-v1-radio-flows.json is written by kotlin/core RadioFlowVectorsTest
// (UPDATE_VECTORS=1) through RadioRelay; the Go patron plays every flow and must give the same outputs.

const flowVectorsPath = "../../vectors/anmp-v1-radio-flows.json"

type flowFile struct {
	Start    int64                   `json:"start"`
	Settings map[string]flowSettings `json:"settings"`
	Flows    []flow                  `json:"flows"`
}

type flowSettings struct {
	WaitMinMs      int64 `json:"waitMinMs"`
	WaitMaxMs      int64 `json:"waitMaxMs"`
	JitterMaxMs    int64 `json:"jitterMaxMs"`
	PerAsk         int   `json:"perAsk"`
	PerHour        int   `json:"perHour"`
	PerGatewayHour int   `json:"perGatewayHour"`
	PatronageMs    int64 `json:"patronageMs"`
	OfferMs        int64 `json:"offerMs"`
	SinceOverlapS  int64 `json:"sinceOverlapS"`
	DefaultHops    int   `json:"defaultHops"`
	InboxLifetimeS int64 `json:"inboxLifetimeS"`
}

type flow struct {
	Name       string     `json:"name"`
	Setting    string     `json:"setting"`
	PatronFrom string     `json:"patronFrom"`
	Steps      []flowStep `json:"steps"`
}

type flowStep struct {
	T     int64  `json:"t"`
	Note  string `json:"note"`
	Inbox []struct {
		Packet string `json:"packet"`
		Ts     int64  `json:"ts"`
	} `json:"inbox"`
	Ask *struct {
		Packet    string   `json:"packet"`
		Hops      *int     `json:"hops"`
		Snr       *float32 `json:"snr"`
		Gateway   *string  `json:"gateway"`
		JitterMs  int64    `json:"jitterMs"`
		PatronKey string   `json:"patronKey"`
	} `json:"ask"`
	Patron string     `json:"patron"`
	Pickup string     `json:"pickup"`
	Serve  bool       `json:"serve"`
	Send   []flowSend `json:"send"`
	Limits []string   `json:"limits"`
}

type flowSend struct {
	Packet  string  `json:"packet"`
	Hops    *int    `json:"hops"`
	Gateway *string `json:"gateway"`
}

func settingsOf(s PatronSettings) flowSettings {
	return flowSettings{
		WaitMinMs: s.WaitMin.Milliseconds(), WaitMaxMs: s.WaitMax.Milliseconds(), JitterMaxMs: s.Jitter.Milliseconds(),
		PerAsk: s.PerAsk, PerHour: s.PerHour, PerGatewayHour: s.PerGatewayHour,
		PatronageMs: s.Patronage.Milliseconds(), OfferMs: s.OfferFor.Milliseconds(),
		SinceOverlapS: int64(s.SinceOverlap / time.Second), DefaultHops: s.DefaultHops,
		InboxLifetimeS: int64(s.InboxLifetime / time.Second),
	}
}

func loadFlows(t *testing.T) flowFile {
	t.Helper()
	data, err := os.ReadFile(flowVectorsPath)
	if err != nil {
		t.Fatal(err)
	}
	var f flowFile
	if err := json.Unmarshal(data, &f); err != nil {
		t.Fatal(err)
	}
	return f
}

func TestRadioFlowSettings(t *testing.T) {
	f := loadFlows(t)
	for name, s := range map[string]PatronSettings{"app": AppPatron, "bridge": BridgePatron} {
		if got, want := settingsOf(s), f.Settings[name]; got != want {
			t.Errorf("settings %s: Go %+v, vectors %+v", name, got, want)
		}
	}
	if len(f.Settings) != 2 {
		t.Errorf("settings: %d in the file, Go knows app and bridge", len(f.Settings))
	}
}

func TestRadioFlows(t *testing.T) {
	f := loadFlows(t)
	if len(f.Flows) == 0 {
		t.Fatal("no flows")
	}
	settings := map[string]PatronSettings{"app": AppPatron, "bridge": BridgePatron}
	for _, fl := range f.Flows {
		t.Run(fl.Name, func(t *testing.T) {
			s, ok := settings[fl.Setting]
			if !ok {
				t.Fatalf("unknown setting %q", fl.Setting)
			}
			playFlow(t, f.Start, s, fl)
		})
	}
}

func playFlow(t *testing.T, start int64, s PatronSettings, fl flow) {
	p := NewPatron(s, unhex(t, fl.PatronFrom))
	var jitters []time.Duration
	var keys []*Ephemeral
	p.JitterFunc = func(max time.Duration) time.Duration {
		j := jitters[0]
		jitters = jitters[1:]
		if j < 0 || j >= max {
			t.Fatalf("jitter %v not below %v", j, max)
		}
		return j
	}
	p.NewKey = func() (*Ephemeral, error) {
		k := keys[0]
		keys = keys[1:]
		return k, nil
	}
	var inbox []InboxPacket
	last := int64(0)
	for i, st := range fl.Steps {
		if st.T < last {
			t.Fatalf("step %d goes back in time", i)
		}
		last = st.T
		now := start + st.T
		out := p.Advance(now)
		add := func(o PatronOut) {
			out.Sends = append(out.Sends, o.Sends...)
			out.Limits = append(out.Limits, o.Limits...)
		}
		for _, e := range st.Inbox {
			inbox = append(inbox, InboxPacket{unhex(t, e.Packet), e.Ts})
		}
		if a := st.Ask; a != nil {
			key, err := EphemeralOf(unhex(t, a.PatronKey))
			if err != nil {
				t.Fatal(err)
			}
			jitters = append(jitters, time.Duration(a.JitterMs)*time.Millisecond)
			keys = append(keys, key)
			gw := ""
			if a.Gateway != nil {
				gw = *a.Gateway
			}
			o, err := p.Ask(now, unhex(t, a.Packet), a.Hops, a.Snr, gw)
			if err != nil {
				t.Fatalf("step %d: ask: %v", i, err)
			}
			add(o)
		}
		if st.Patron != "" {
			add(p.HeardPatron(now))
		}
		if st.Pickup != "" {
			add(p.Pickup(now, unhex(t, st.Pickup), inbox))
		}
		if st.Serve {
			add(p.Serve(now, inbox))
		}
		got := make([]flowSend, 0, len(out.Sends))
		for _, sd := range out.Sends {
			fs := flowSend{Packet: hex.EncodeToString(sd.Packet)}
			if sd.HopLimit != 0 {
				fs.Hops = &sd.HopLimit
			}
			if sd.Gateway != "" {
				fs.Gateway = &sd.Gateway
			}
			got = append(got, fs)
		}
		want := st.Send
		if want == nil {
			want = []flowSend{}
		}
		if !reflect.DeepEqual(got, want) {
			t.Errorf("step %d (t %d, %s): send\n got  %s\n want %s", i, st.T, st.Note, sendsText(got), sendsText(want))
		}
		limits := out.Limits
		if limits == nil {
			limits = []string{}
		}
		wantLimits := st.Limits
		if wantLimits == nil {
			wantLimits = []string{}
		}
		if !reflect.DeepEqual(limits, wantLimits) {
			t.Errorf("step %d (t %d, %s): limits %v, want %v", i, st.T, st.Note, limits, wantLimits)
		}
	}
}

func sendsText(list []flowSend) string {
	b, _ := json.Marshal(list)
	return string(b)
}

// The wait follows the signal in the app and is fixed at the bridge.
func TestPatronWait(t *testing.T) {
	f := func(v float32) *float32 { return &v }
	for _, c := range []struct {
		s    PatronSettings
		snr  *float32
		want time.Duration
	}{
		{AppPatron, f(5), 2 * time.Second},
		{AppPatron, f(10), 2 * time.Second},
		{AppPatron, f(-5), 11 * time.Second},
		{AppPatron, nil, 20 * time.Second},
		{BridgePatron, f(10), 30 * time.Second},
		{BridgePatron, nil, 30 * time.Second},
	} {
		if got := c.s.Wait(c.snr); got != c.want {
			t.Errorf("wait %v: %v, want %v", c.snr, got, c.want)
		}
	}
	if BridgePatron.WaitMin <= AppPatron.WaitMax+AppPatron.Jitter {
		t.Error("the bridge must wait longer than any app")
	}
}

// NextDue tells the caller when to call Advance; the random part stays below Jitter.
func TestPatronNextDue(t *testing.T) {
	p := NewPatron(BridgePatron, make([]byte, TagBytes))
	if _, ok := p.NextDue(); ok {
		t.Fatal("nothing due yet")
	}
	ask, err := EncodeOpen(&Header{Kind: KindAsk, From: make([]byte, TagBytes), Since: Int(0)})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := p.Ask(1000, ask, nil, nil, "gw"); err != nil {
		t.Fatal(err)
	}
	due, ok := p.NextDue()
	if !ok || due < 31_000 || due >= 33_000 {
		t.Fatalf("due %d", due)
	}
	if out := p.Advance(due - 1); len(out.Sends) != 0 {
		t.Fatal("too early")
	}
	if out := p.Advance(due); len(out.Sends) != 1 || out.Sends[0].Gateway != "gw" || out.Sends[0].HopLimit != 0 {
		t.Fatalf("offer %+v", out)
	}
	if _, ok := p.NextDue(); ok {
		t.Fatal("offer sent, nothing due")
	}
	if _, err := p.Ask(1000, make([]byte, 10), nil, nil, ""); err == nil {
		t.Fatal("no ANMP packet must be refused")
	}
}
