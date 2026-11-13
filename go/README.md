# ANMP for Go

Go module `github.com/anynear/ANMP/go`, the radio part of `spec/anmp.md` (version v1) for the bridge in the relay
(a relay that also listens to radio gateways). Standard library plus `github.com/fxamacker/cbor/v2`.
It does not need ANDP: the relay checks the radio list's head and signature, this module reads the content.

| Package | Content |
|---|---|
| `anmp` | frame and header (`Peek`, `EncodeHeader`), open packets `ask` and `patron` (`EncodeOpen`), sealed packets (`Encode`, `Decode`), exact size and radio budget (`Size`, `MaxTextBytes`, `FitsRadio`), radio rule (`Kind.Radio`), public form (`IsPublicForm`), channel PSK, radio secrets, sender and route tags per hour, chains, pickup windows and their tags, `pickup` with one-time keys (`PickupPacket`, `OpenPickup`), radio list content (`ParseRadioList`), spaces of the radio inbox |

Kotlin (`kotlin/format`, `kotlin/core`) is the reference and writes the vectors; `go test ./...` checks every radio
vector of `../vectors/anmp-v1.json` and all of `../vectors/anmp-v1-radio-list.json`.

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
```
