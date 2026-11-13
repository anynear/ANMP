package anmp

import (
	"bytes"
	"crypto/ed25519"
	"encoding/binary"
	"regexp"
	"slices"
	"time"
)

// Daily usage stats (ADR 0045): one signed report per device and day, keys and person marker derived from the day.
const (
	StatsMarkerBytes  = 16
	StatsMaxRadios    = 4
	StatsMaxChannels  = 4
	StatsMaxDevices   = 6
	StatsMaxCircles   = 6
	StatsMaxCurated   = 8
	StatsMaxIDChars   = 32
	StatsMaxVersion   = 32
	StatsMaxReport    = 512
	StatsVersionOther = "other"
)

// StatsPlatforms are the platforms a report may name.
var StatsPlatforms = []string{"android", "ios", "web"}

var (
	statsDay     = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}$`)
	statsVersion = regexp.MustCompile(`^(\d{1,4})\.(\d{1,4})\.(\d{1,4})(?:\D.*)?$`)
)

// StatsBody is the body of a report, CBOR with integer keys 1–10.
type StatsBody struct {
	Day      string   `cbor:"1,keyasint"`
	Key      []byte   `cbor:"2,keyasint"`
	Person   []byte   `cbor:"3,keyasint"`
	Platform string   `cbor:"4,keyasint"`
	Version  string   `cbor:"5,keyasint"`
	Radios   int      `cbor:"6,keyasint"`
	Channels int      `cbor:"7,keyasint"`
	Curated  []string `cbor:"8,keyasint"`
	Devices  int      `cbor:"9,keyasint"`
	Circles  int      `cbor:"10,keyasint"`
}

// StatsSwitch is a switch report (ADR 0045): sent once when the person switches the stats on or off. Field 11 sets it
// apart from a daily report.
type StatsSwitch struct {
	Day      string `cbor:"1,keyasint"`
	Key      []byte `cbor:"2,keyasint"`
	Platform string `cbor:"4,keyasint"`
	On       *bool  `cbor:"11,keyasint"`
}

type statsSigned struct {
	Body []byte `cbor:"0,keyasint"`
	Sig  []byte `cbor:"1,keyasint"`
}

// StatsDay is the UTC day YYYY-MM-DD of a Unix time in seconds.
func StatsDay(unixSeconds int64) string { return time.Unix(unixSeconds, 0).UTC().Format("2006-01-02") }

// StatsKeySeed is the Ed25519 seed of the stats key of a day: HKDF(device seed, "anmp/v1/stats\n" day).
func StatsKeySeed(deviceSeed []byte, day string) []byte { return HKDF(deviceSeed, InfoStats+"\n"+day) }

// StatsMarker is the person marker of a day: first 16 bytes of HKDF(person seed, "anmp/v1/stats-person\n" day).
func StatsMarker(personSeed []byte, day string) []byte {
	return HKDF(personSeed, InfoStatsPerson+"\n"+day)[:StatsMarkerBytes]
}

// StatsRelayIndex picks the relay for a marker among count active relays sorted by node key.
func StatsRelayIndex(marker []byte, count int) int {
	return int(uint64(binary.BigEndian.Uint32(marker[:4])) % uint64(count))
}

// StatsVersion is X.Y.Z of a version string, or "other".
func StatsVersion(raw string) string {
	m := statsVersion.FindStringSubmatch(raw)
	if m == nil {
		return StatsVersionOther
	}
	return m[1] + "." + m[2] + "." + m[3]
}

// StatsSigningBytes is "anmp/v1/stats-sig\n" ‖ body bytes.
func StatsSigningBytes(body []byte) []byte {
	return append([]byte(InfoStatsSig+"\n"), body...)
}

// Valid reports whether the body keeps the limits of ADR 0045.
func (b *StatsBody) Valid() bool {
	if !statsDay.MatchString(b.Day) || len(b.Key) != ed25519.PublicKeySize || len(b.Person) != StatsMarkerBytes {
		return false
	}
	if !slices.Contains(StatsPlatforms, b.Platform) || b.Version == "" || len(b.Version) > StatsMaxVersion {
		return false
	}
	if b.Radios < 0 || b.Radios > StatsMaxRadios || b.Channels < 0 || b.Channels > StatsMaxChannels {
		return false
	}
	if b.Devices < 1 || b.Devices > StatsMaxDevices || b.Circles < 0 || b.Circles > StatsMaxCircles {
		return false
	}
	if len(b.Curated) > StatsMaxCurated {
		return false
	}
	for _, c := range b.Curated {
		if c == "" || len(c) > StatsMaxIDChars {
			return false
		}
	}
	return true
}

// OpenStats checks a report {0: body, 1: sig}: size, body limits and the signature by the key in the body.
func OpenStats(report []byte) (*StatsBody, error) {
	if len(report) > StatsMaxReport {
		return nil, invalid("stats report too large")
	}
	var s statsSigned
	if err := decMode.Unmarshal(report, &s); err != nil {
		return nil, invalid("stats report: %v", err)
	}
	if len(s.Sig) != ed25519.SignatureSize {
		return nil, invalid("stats signature size")
	}
	var b StatsBody
	if err := decMode.Unmarshal(s.Body, &b); err != nil {
		return nil, invalid("stats body: %v", err)
	}
	if !b.Valid() {
		return nil, invalid("stats body limits")
	}
	if !ed25519.Verify(b.Key, StatsSigningBytes(s.Body), s.Sig) {
		return nil, invalid("stats signature")
	}
	return &b, nil
}

// OpenStatsSwitch checks a switch report: size, fields and the signature by the key in the body.
func OpenStatsSwitch(report []byte) (*StatsSwitch, error) {
	if len(report) > StatsMaxReport {
		return nil, invalid("stats report too large")
	}
	var s statsSigned
	if err := decMode.Unmarshal(report, &s); err != nil || len(s.Sig) != ed25519.SignatureSize {
		return nil, invalid("stats switch report")
	}
	var b StatsSwitch
	if err := decMode.Unmarshal(s.Body, &b); err != nil {
		return nil, invalid("stats switch body: %v", err)
	}
	if b.On == nil || !statsDay.MatchString(b.Day) || len(b.Key) != ed25519.PublicKeySize || !slices.Contains(StatsPlatforms, b.Platform) {
		return nil, invalid("stats switch fields")
	}
	if !ed25519.Verify(b.Key, StatsSigningBytes(s.Body), s.Sig) {
		return nil, invalid("stats signature")
	}
	return &b, nil
}

// SameStatsKey reports whether seed derives the key in the body (for tests and tools).
func SameStatsKey(deviceSeed []byte, b *StatsBody) bool {
	k := ed25519.NewKeyFromSeed(StatsKeySeed(deviceSeed, b.Day))
	return bytes.Equal(k.Public().(ed25519.PublicKey), b.Key)
}
