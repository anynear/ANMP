package anmp

import (
	"bytes"
	"cmp"
	"math/rand/v2"
	"slices"
	"time"
)

// Patron role (ADR 0051 §5, §5a, §6; ADR 0054 §4), as kotlin/core RadioRelay does it for an app. The bridge never
// asks; it only becomes a patron: it waits, offers itself with a one-time key, opens the pickup, works out the route
// tags of the windows, picks the packets of the radio inbox and keeps the limits. Both are checked against
// vectors/anmp-v1-radio-flows.json.
//
// A Patron holds only what the role needs: offers waiting for their time, offers waiting for a pickup, patronages
// with their tags and what went out in the last hour. A patron whose gateway budget is used up does not offer itself.
// A packet goes to the same asker (its hourly sender tag) at most once an hour, also across patronages: each ask brings
// a new one-time key, and since alone does not stop resending. Times are Unix milliseconds; the caller passes the time with
// every call and calls Advance when an offer is due (NextDue). Not safe for concurrent use.

// PatronSettings are the parameters of a patron.
type PatronSettings struct {
	// WaitMin and WaitMax: wait before an offer by signal (SNR 5 dB and more: WaitMin, −15 dB and less: WaitMax),
	// plus a random part below Jitter.
	WaitMin, WaitMax, Jitter time.Duration
	// PerAsk: packets sent when a pickup comes.
	PerAsk int
	// PerHour: packets per hour and patronage.
	PerHour int
	// PerGatewayHour: packets per hour and gateway over all patronages (the app has one gateway, its radio).
	PerGatewayHour int
	// Patronage: how long a patronage serves after its pickup.
	Patronage time.Duration
	// OfferFor: how long an offer waits for its pickup.
	OfferFor time.Duration
	// SinceOverlap: packets are sent if put later than the ask's since minus this.
	SinceOverlap time.Duration
	// DefaultHops: hop limit of the packets when the ask's hops are unknown.
	DefaultHops int
	// InboxLifetime: older radio inbox entries are left out.
	InboxLifetime time.Duration
}

// AppPatron are the settings of an app (kotlin/core RadioTimings and RadioRelay).
var AppPatron = PatronSettings{
	WaitMin: 2 * time.Second, WaitMax: 20 * time.Second, Jitter: 2 * time.Second,
	PerAsk: 5, PerHour: 20, PerGatewayHour: 20,
	Patronage: time.Hour, OfferFor: 2 * time.Minute, SinceOverlap: 10 * time.Minute, DefaultHops: 3,
	InboxLifetime: RadioInboxLifetimeSeconds * time.Second,
}

// BridgePatron are the settings of the bridge (ADR 0054 §4): 30 s plus the random part, longer than any app waits,
// whatever the signal; 60 packets per hour and gateway, to be adjusted after the first measurements.
var BridgePatron = func() PatronSettings {
	s := AppPatron
	s.WaitMin, s.WaitMax = 30*time.Second, 30*time.Second
	s.PerGatewayHour = 60
	return s
}()

// Limits a patron reports when it held a packet back.
const (
	LimitPatronage = "patronage"
	LimitGateway   = "gateway"
)

// InboxPacket is an entry of the radio inbox: a packet in the public form and the time it was put (Unix seconds).
type InboxPacket struct {
	Packet []byte
	Ts     int64
}

// PatronSend is a packet the patron sends: through Gateway ("" for the app's radio), with HopLimit (0: the radio's
// own).
type PatronSend struct {
	Packet   []byte
	HopLimit int
	Gateway  string
}

// PatronOut is what a call gave: packets to send, in order, and the limits that held packets back.
type PatronOut struct {
	Sends  []PatronSend
	Limits []string
}

type pendingOffer struct {
	askedAt, due int64
	seq          int
	key          *Ephemeral
	hops         *int
	since        int64
	gateway      string
}

type patronOffer struct {
	key     *Ephemeral
	hops    int
	since   int64
	at      int64
	gateway string
}

type patronage struct {
	asker   string // one-time key of the pickup
	from    string // hourly sender tag of the asker
	tags    map[string]bool
	hops    int
	until   int64
	since   int64
	gateway string
	sent    map[string]bool
	sentAt  []int64
}

// Patron is the patron role of one bridge or app.
type Patron struct {
	settings PatronSettings
	from     []byte

	// JitterFunc gives the random part of a wait, below max; NewKey the one-time key of an offer. Both are drawn
	// when the ask is heard. Fixed only in tests.
	JitterFunc func(max time.Duration) time.Duration
	NewKey     func() (*Ephemeral, error)

	pending         []*pendingOffer
	seq             int
	offers          []*patronOffer
	patronages      []*patronage
	sentAt          map[string][]int64
	delivered       map[string]int64 // asker sender tag and packet → sent at, the last hour
	deliveredOrder  []deliveredEntry
	lastPatronHeard int64
}

type deliveredEntry struct {
	key string
	at  int64
}

// MaxDelivered caps the packets a patron remembers per asker for an hour, over all askers (bounded besides by the
// gateway budgets).
const MaxDelivered = 2000

// NewPatron makes a patron with settings; from is the sender tag of its patron packets (8 bytes).
func NewPatron(settings PatronSettings, from []byte) *Patron {
	return &Patron{
		settings: settings,
		from:     append([]byte(nil), from...),
		JitterFunc: func(max time.Duration) time.Duration {
			if max <= 0 {
				return 0
			}
			return time.Duration(rand.Int64N(int64(max/time.Millisecond))) * time.Millisecond
		},
		NewKey:    NewEphemeral,
		sentAt:    map[string][]int64{},
		delivered: map[string]int64{},
	}
}

// Wait is the wait before an offer for an ask heard with snr (nil: unknown, counts as −15 dB), without the random
// part. Computed in float32 like the app.
func (s PatronSettings) Wait(snr *float32) time.Duration {
	lo, hi := s.WaitMin.Milliseconds(), s.WaitMax.Milliseconds()
	v := float32(-15)
	if snr != nil {
		v = *snr
	}
	share := min(max((float32(5)-v)/float32(20), 0), 1)
	return time.Duration(lo+int64(float32(hi-lo)*share)) * time.Millisecond
}

// Ask takes an ask heard at now through gateway with the measured hops (nil: unknown) and snr (nil: unknown). The
// offer goes out with Advance once the wait is over, unless another patron is heard first.
func (p *Patron) Ask(now int64, packet []byte, hops *int, snr *float32, gateway string) (PatronOut, error) {
	out := p.Advance(now)
	h, err := Peek(packet)
	if err != nil {
		return out, err
	}
	if h.Kind != KindAsk {
		return out, invalid("no ask")
	}
	jitter := p.JitterFunc(p.settings.Jitter)
	key, err := p.NewKey()
	if err != nil {
		return out, err
	}
	var since int64
	if h.Since != nil {
		since = *h.Since
	}
	p.seq++
	p.pending = append(p.pending, &pendingOffer{
		askedAt: now,
		due:     now + (p.settings.Wait(snr) + jitter).Milliseconds(),
		seq:     p.seq,
		key:     key,
		hops:    hops,
		since:   since - int64(p.settings.SinceOverlap/time.Second),
		gateway: gateway,
	})
	return out, nil
}

// HeardPatron takes another patron's answer heard at now: offers for asks heard until now stay quiet.
func (p *Patron) HeardPatron(now int64) PatronOut {
	out := p.Advance(now)
	p.lastPatronHeard = now
	return out
}

// NextDue is the time the next offer is due, false if none waits.
func (p *Patron) NextDue() (int64, bool) {
	if len(p.pending) == 0 {
		return 0, false
	}
	return slices.MinFunc(p.pending, comparePending).due, true
}

func comparePending(a, b *pendingOffer) int {
	return cmp.Or(cmp.Compare(a.due, b.due), cmp.Compare(a.seq, b.seq))
}

// Advance sends the offers whose wait ended at or before now, in that order. Every other call does this first.
func (p *Patron) Advance(now int64) PatronOut {
	var out PatronOut
	slices.SortFunc(p.pending, comparePending)
	n := 0
	for _, o := range p.pending {
		if o.due > now {
			break
		}
		n++
		// Listen first, then send: if another patron answered while we waited, we stay quiet.
		if p.lastPatronHeard >= o.askedAt {
			continue
		}
		// With the gateway budget used up there is nothing to send: no offer either.
		if gw := pruneHour(p.sentAt[o.gateway], o.due); len(gw) >= p.settings.PerGatewayHour {
			p.sentAt[o.gateway] = gw
			out.Limits = append(out.Limits, LimitGateway)
			continue
		}
		packet, err := EncodeOpen(&Header{Kind: KindPatron, From: p.from, SenderKey: o.key.PublicKey()})
		if err != nil {
			continue
		}
		limit := 0
		if o.hops != nil {
			limit = max(*o.hops, 1)
		}
		out.Sends = append(out.Sends, PatronSend{packet, limit, o.gateway})
		hops := p.settings.DefaultHops
		if o.hops != nil {
			hops = *o.hops
		}
		p.offers = append(p.offers, &patronOffer{o.key, max(hops, 1), o.since, o.due, o.gateway})
	}
	p.pending = p.pending[n:]
	return out
}

// Pickup takes a pickup heard at now. If it is for one of the offers, the patron serves its windows for
// PatronSettings.Patronage and sends at most PerAsk of the waiting packets in inbox (the radio inbox), newest first.
func (p *Patron) Pickup(now int64, packet []byte, inbox []InboxPacket) PatronOut {
	out := p.Advance(now)
	p.offers = slices.DeleteFunc(p.offers, func(o *patronOffer) bool { return now-o.at > p.settings.OfferFor.Milliseconds() })
	for _, o := range p.offers {
		pickup, err := OpenPickup(packet, o.key)
		if err != nil {
			continue
		}
		tags := map[string]bool{}
		for _, w := range pickup.Windows {
			for _, t := range WindowTags(w, pickup.Hour) {
				tags[string(t.Tag)] = true
			}
		}
		h, err := Peek(packet)
		if err != nil || h.SenderKey == nil {
			return out
		}
		asker := string(h.SenderKey)
		pa := &patronage{asker: asker, from: string(h.From), tags: tags, hops: o.hops, until: now + p.settings.Patronage.Milliseconds(), since: o.since,
			gateway: o.gateway, sent: map[string]bool{}}
		if i := slices.IndexFunc(p.patronages, func(x *patronage) bool { return x.asker == asker }); i >= 0 {
			old := p.patronages[i]
			for t := range old.tags {
				pa.tags[t] = true
			}
			pa.sent, pa.sentAt = old.sent, old.sentAt
			p.patronages[i] = pa
		} else {
			p.patronages = append(p.patronages, pa)
		}
		waiting := p.waiting(pa, inbox, now)
		p.send(&out, pa, waiting[:min(len(waiting), p.settings.PerAsk)], now)
		return out
	}
	return out
}

// Serve reads inbox (the radio inbox) at now and sends what the patronages wait for. Call it whenever the radio
// inbox may have news, the app does every 30 seconds.
func (p *Patron) Serve(now int64, inbox []InboxPacket) PatronOut {
	out := p.Advance(now)
	p.patronages = slices.DeleteFunc(p.patronages, func(x *patronage) bool { return x.until < now })
	p.offers = slices.DeleteFunc(p.offers, func(o *patronOffer) bool { return now-o.at > p.settings.OfferFor.Milliseconds() })
	for _, pa := range p.patronages {
		p.send(&out, pa, p.waiting(pa, inbox, now), now)
	}
	for g, q := range p.sentAt {
		if len(pruneHour(q, now)) == 0 {
			delete(p.sentAt, g)
		}
	}
	return out
}

// waiting gives the entries of inbox for pa: route tag among its tags, put after its since, newest first; the same
// bytes only once, at the place of the first and with the time of the last (as the app's cache keeps them).
func (p *Patron) waiting(pa *patronage, inbox []InboxPacket, now int64) []InboxPacket {
	oldest := now/1000 - int64(p.settings.InboxLifetime/time.Second)
	var list []InboxPacket
	at := map[string]int{}
	for _, e := range inbox {
		if i, ok := at[string(e.Packet)]; ok {
			list[i].Ts = e.Ts
			continue
		}
		at[string(e.Packet)] = len(list)
		list = append(list, e)
	}
	var out []InboxPacket
	for _, e := range list {
		if e.Ts < oldest || e.Ts <= pa.since {
			continue
		}
		h, err := Peek(e.Packet)
		if err != nil || h.Route == nil || !pa.tags[string(h.Route)] {
			continue
		}
		out = append(out, e)
	}
	slices.SortStableFunc(out, func(a, b InboxPacket) int { return cmp.Compare(b.Ts, a.Ts) })
	return out
}

func (p *Patron) send(out *PatronOut, pa *patronage, packets []InboxPacket, now int64) {
	for _, e := range packets {
		key := string(e.Packet)
		toAsker := pa.from + "/" + key
		pa.sentAt = pruneHour(pa.sentAt, now)
		gw := pruneHour(p.sentAt[pa.gateway], now)
		p.sentAt[pa.gateway] = gw
		p.pruneDelivered(now)
		_, delivered := p.delivered[toAsker]
		switch {
		case pa.sent[key], delivered:
			continue
		case len(pa.sentAt) >= p.settings.PerHour:
			out.Limits = append(out.Limits, LimitPatronage)
			continue
		case len(gw) >= p.settings.PerGatewayHour:
			out.Limits = append(out.Limits, LimitGateway)
			continue
		}
		pa.sent[key] = true
		pa.sentAt = append(pa.sentAt, now)
		p.sentAt[pa.gateway] = append(gw, now)
		p.delivered[toAsker] = now
		p.deliveredOrder = append(p.deliveredOrder, deliveredEntry{toAsker, now})
		for len(p.deliveredOrder) > MaxDelivered {
			delete(p.delivered, p.deliveredOrder[0].key)
			p.deliveredOrder = p.deliveredOrder[1:]
		}
		out.Sends = append(out.Sends, PatronSend{bytes.Clone(e.Packet), pa.hops, pa.gateway})
	}
}

// pruneDelivered forgets what went to an asker more than an hour before now.
func (p *Patron) pruneDelivered(now int64) {
	i := 0
	for i < len(p.deliveredOrder) && now-p.deliveredOrder[i].at > time.Hour.Milliseconds() {
		delete(p.delivered, p.deliveredOrder[i].key)
		i++
	}
	p.deliveredOrder = p.deliveredOrder[i:]
}

// pruneHour drops the times more than an hour before now.
func pruneHour(q []int64, now int64) []int64 {
	i := 0
	for i < len(q) && now-q[i] > time.Hour.Milliseconds() {
		i++
	}
	return q[i:]
}
