# ANMP for Go

Go module `github.com/anynear/ANMP/go`, the radio part of `spec/anmp.md` (version v1) for the bridge in the relay
(a relay that also listens to radio gateways). Standard library plus `github.com/fxamacker/cbor/v2`.
It does not need ANDP: the relay checks the radio list's head and signature, this module reads the content.

| Package | Content |
|---|---|
| `anmp` | frame and header (`Peek`, `EncodeHeader`), open packets `ask` and `patron` (`EncodeOpen`), sealed packets (`Encode`, `Decode`), exact size and radio budget (`Size`, `MaxTextBytes`, `FitsRadio`), radio rule (`Kind.Radio`), public form (`IsPublicForm`), signs of life and helpers' answers over radio (`Alive`, `Care`, `Valid`), channel PSK, radio secrets, sender and route tags per hour, chains, pickup windows and their tags, `pickup` with one-time keys (`PickupPacket`, `OpenPickup`), radio list content (`ParseRadioList`, with `Gateway`), spaces of the radio inbox, the patron role (`Patron` with `AppPatron` and `BridgePatron`: wait, offer, pickup, choice of packets, limits per ask, hour, patronage and gateway) |

Kotlin (`kotlin/format`, `kotlin/core`) is the reference and writes the vectors; `go test ./...` checks every radio
vector of `../vectors/anmp-v1.json`, all of `../vectors/anmp-v1-radio-list.json` and every flow of
`../vectors/anmp-v1-radio-flows.json`.

## Use

```go
// Uplink: keep only anynear packets in the public form.
if !anmp.IsPublicForm(payload) {
	return // dropped, nothing stored or logged
}

// Patron: answer an ask with a one-time key, open the pickup, compute the route tags it allows.
eph, _ := anmp.NewEphemeral()
patron, _ := anmp.EncodeOpen(&anmp.Header{Kind: anmp.KindPatron, From: senderTag, SenderKey: eph.PublicKey(), Waiting: anmp.Int(n)})
p, err := anmp.OpenPickup(pickup, eph)
for _, w := range p.Windows {
	for _, t := range anmp.WindowTags(w, p.Hour) {
		// t.Hour, t.Tag: match against header field 6 (route) of the radio inbox entries
	}
}

// Or the whole role, as the bridge does it: it never asks, it only becomes a patron.
role := anmp.NewPatron(anmp.BridgePatron, bridgeTag)
out, err := role.Ask(now, ask, &hops, nil, gatewayID) // offer due after 30 s plus a random part
out = role.HeardPatron(now)                          // someone else answered: our offer stays quiet
out = role.Advance(now)                              // offers whose wait is over (role.NextDue says when)
out = role.Pickup(now, pickup, inbox)                // patron now: up to 5 packets, newest first
out = role.Serve(now, inbox)                         // news for the patronages, within the limits
for _, s := range out.Sends {
	// s.Packet through gateway s.Gateway with hop limit s.HopLimit; out.Limits: what was held back
}
```
