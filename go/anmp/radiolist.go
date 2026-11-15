package anmp

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"math"
	"slices"
	"strconv"
	"strings"
)

// RadioPresets are the Meshtastic modem presets a region or community may name; others are skipped.
var RadioPresets = []string{
	"LONG_FAST", "LONG_SLOW", "LONG_MODERATE", "LONG_TURBO", "MEDIUM_FAST", "MEDIUM_SLOW",
	"SHORT_FAST", "SHORT_SLOW", "SHORT_TURBO",
}

// RadioLoraRegions are the Meshtastic LoRa region codes a region may name (RegionCode without UNSET); others are skipped.
var RadioLoraRegions = []string{
	"US", "EU_433", "EU_868", "CN", "JP", "ANZ", "KR", "TW", "RU", "IN", "NZ_865", "TH", "LORA_24", "UA_433",
	"UA_868", "MY_433", "MY_919", "SG_923", "PH_433", "PH_868", "PH_915", "ANZ_433", "KZ_433", "KZ_863", "NP_865",
	"BR_902",
}

// RadioDefaultLora is the LoRa region of a list region that names none: the lists before this field were all for Europe.
const RadioDefaultLora = "EU_868"

// RadioBorderMarginKm is the distance to a border before a radio changes region (ADR 0054 §1).
const RadioBorderMarginKm = 2.0

const kmPerDegree = 111.195

// RadioList is the content of the radio list (ADR 0054 §1b, spec "Radio list"): regions with their area, the preset
// of channel 0, whether ANMP goes out on channel 0, and the communities' public channels. ANDP checks head and
// signature (andp.NetworkList.AcceptRadio); ParseRadioList reads what it accepted.
type RadioList struct {
	Issued  int64 // Unix milliseconds
	Regions []RadioRegion
	Gateway *RadioGateway // where radios on Wi-Fi upload as gateways (ADR 0054 §5b); nil: no radio becomes a gateway
}

// RadioGateway is the anynear MQTT server for gateways (ADR 0054 §5b). The login is public, it is on every gateway.
// Host is a DNS name or IPv4 address (letters, digits, '.', '-'). Lengths are those the Meshtastic firmware stores:
// the address and User at most 63 bytes, Password and Root at most 31.
type RadioGateway struct {
	Host     string
	Port     int
	TLS      bool
	User     string
	Password string
	Root     string
}

// Gateway ports when the list names none.
const (
	RadioGatewayPort    = 1883
	RadioGatewayTLSPort = 8883
)

// Address is the value of the firmware's MQTT address: the host, with ":port" only if it is not the default for TLS.
func (g *RadioGateway) Address() string {
	if g.Port == gatewayDefaultPort(g.TLS) {
		return g.Host
	}
	return g.Host + ":" + strconv.Itoa(g.Port)
}

func gatewayDefaultPort(tls bool) int {
	if tls {
		return RadioGatewayTLSPort
	}
	return RadioGatewayPort
}

// RadioRegion is one region: Area as [lat, lon] points of a simple polygon, the radio settings there (Lora region
// code, Preset of channel 0, Frequency in MHz or 0 for the default slot of region and preset), its communities.
type RadioRegion struct {
	Name        string
	Area        [][2]float64
	Preset      string
	Channel0    bool // whether ANMP goes out on channel 0 here; otherwise only on the channel anynear
	Communities []RadioCommunity
	Lora        string
	Frequency   float64
}

// RadioCommunity is a community's public channel (ADR 0054 §1a): Channel as on the radio (1–11 bytes), PSK its
// public key (1, 16 or 32 bytes), the Preset it needs, Operator and Source for people.
type RadioCommunity struct {
	Name     string
	Channel  string
	PSK      []byte
	Preset   string
	Operator string
	Source   string
}

// ParseRadioList reads the text of a radio list, exactly like RadioList.parse of kotlin/core. Unknown fields are
// ignored; a region or community that is invalid or names a preset this code does not know is skipped, the rest
// counts. An error only if the text is no JSON object or has no issued.
func ParseRadioList(text []byte) (*RadioList, error) {
	d := json.NewDecoder(bytes.NewReader(text))
	d.UseNumber()
	var v any
	if err := d.Decode(&v); err != nil {
		return nil, err
	}
	if _, err := d.Token(); err != io.EOF {
		return nil, errors.New("anmp: radio list: trailing data")
	}
	obj, ok := v.(map[string]any)
	if !ok {
		return nil, errors.New("anmp: radio list is not an object")
	}
	issued, ok := jsonLong(obj["issued"])
	if !ok {
		return nil, errors.New("anmp: radio list without issued")
	}
	l := &RadioList{Issued: issued, Regions: []RadioRegion{}}
	regions, _ := obj["regions"].([]any)
	for _, e := range regions {
		if r, ok := readRegion(e); ok {
			l.Regions = append(l.Regions, r)
		}
	}
	l.Gateway = readGateway(obj["gateway"])
	return l, nil
}

// readGateway reads the gateway block; nil if it is missing or anything in it is invalid.
func readGateway(e any) *RadioGateway {
	o, ok := e.(map[string]any)
	if !ok {
		return nil
	}
	host, ok := o["host"].(string)
	if !ok || host == "" || strings.IndexFunc(host, func(c rune) bool {
		return !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '.' || c == '-')
	}) >= 0 {
		return nil
	}
	tls := false
	if v, present := o["tls"]; present {
		if tls, ok = jsonBool(v); !ok {
			return nil
		}
	}
	port := gatewayDefaultPort(tls)
	if v, present := o["port"]; present {
		n, ok := jsonLong(v)
		if !ok || n < 1 || n > 65535 {
			return nil
		}
		port = int(n)
	}
	user, ok := o["user"].(string)
	if !ok || user == "" || len(user) > 63 {
		return nil
	}
	pass, ok := o["pass"].(string)
	if !ok || len(pass) > 31 {
		return nil
	}
	root, ok := o["root"].(string)
	if !ok || root == "" || len(root) > 31 {
		return nil
	}
	g := &RadioGateway{host, port, tls, user, pass, root}
	if len(g.Address()) > 63 {
		return nil
	}
	return g
}

// RegionAt gives the first region whose area contains the point, nil outside all regions.
func (l *RadioList) RegionAt(lat, lon float64) *RadioRegion {
	for i := range l.Regions {
		if l.Regions[i].Contains(lat, lon) {
			return &l.Regions[i]
		}
	}
	return nil
}

// RegionFor gives the region for a radio at the point with hysteresis at borders, exactly like RadioList.regionFor of
// kotlin/core: current (a region name) is kept while the point is inside it or less than marginKm outside, unless an
// earlier region contains the point at least marginKm deep. Otherwise the first region containing the point at least
// marginKm deep, else the first containing it at all; nil outside all regions.
func (l *RadioList) RegionFor(lat, lon float64, current string, marginKm float64) *RadioRegion {
	deep, held := -1, -1
	for i := range l.Regions {
		r := &l.Regions[i]
		if deep < 0 && r.Contains(lat, lon) && r.BorderDistanceKm(lat, lon) >= marginKm {
			deep = i
		}
		if held < 0 && current != "" && r.Name == current {
			held = i
		}
	}
	if held >= 0 {
		r := &l.Regions[held]
		if r.Contains(lat, lon) || r.BorderDistanceKm(lat, lon) < marginKm {
			if deep >= 0 && deep < held {
				return &l.Regions[deep]
			}
			return r
		}
	}
	if deep >= 0 {
		return &l.Regions[deep]
	}
	return l.RegionAt(lat, lon)
}

// BorderDistanceKm is the shortest distance in km from the point to the border of Area, inside or outside, in a plane
// approximation around the point (equirectangular).
func (r *RadioRegion) BorderDistanceKm(lat, lon float64) float64 {
	kx := kmPerDegree * math.Cos(lat*math.Pi/180)
	best := math.MaxFloat64
	j := len(r.Area) - 1
	for i := range r.Area {
		ax, ay := (r.Area[j][1]-lon)*kx, (r.Area[j][0]-lat)*kmPerDegree
		bx, by := (r.Area[i][1]-lon)*kx, (r.Area[i][0]-lat)*kmPerDegree
		dx, dy := bx-ax, by-ay
		t := 0.0
		if l := dx*dx + dy*dy; l != 0 {
			t = min(max(-(ax*dx+ay*dy)/l, 0), 1)
		}
		px, py := ax+t*dx, ay+t*dy
		best = min(best, math.Sqrt(px*px+py*py))
		j = i
	}
	return best
}

// Contains is the even-odd test over the polygon; points on the border may count either way.
func (r *RadioRegion) Contains(lat, lon float64) bool {
	inside := false
	j := len(r.Area) - 1
	for i := range r.Area {
		yi, xi := r.Area[i][0], r.Area[i][1]
		yj, xj := r.Area[j][0], r.Area[j][1]
		if (yi > lat) != (yj > lat) && lon < (xj-xi)*(lat-yi)/(yj-yi)+xi {
			inside = !inside
		}
		j = i
	}
	return inside
}

func readRegion(e any) (RadioRegion, bool) {
	o, ok := e.(map[string]any)
	if !ok {
		return RadioRegion{}, false
	}
	name, ok := o["name"].(string)
	if !ok {
		return RadioRegion{}, false
	}
	preset, ok := o["preset"].(string)
	if !ok || !slices.Contains(RadioPresets, preset) {
		return RadioRegion{}, false
	}
	points, ok := o["area"].([]any)
	if !ok {
		return RadioRegion{}, false
	}
	area := make([][2]float64, 0, len(points))
	for _, p := range points {
		xy, ok := p.([]any)
		if !ok || len(xy) != 2 {
			return RadioRegion{}, false
		}
		lat, ok1 := jsonDouble(xy[0])
		lon, ok2 := jsonDouble(xy[1])
		if !ok1 || !ok2 || !(lat >= -90 && lat <= 90) || !(lon >= -180 && lon <= 180) {
			return RadioRegion{}, false
		}
		area = append(area, [2]float64{lat, lon})
	}
	if len(area) < 3 {
		return RadioRegion{}, false
	}
	channel0 := true
	if b, ok := jsonBool(o["channel0"]); ok {
		channel0 = b
	}
	lora := RadioDefaultLora
	if v, present := o["lora"]; present {
		if lora, ok = v.(string); !ok || !slices.Contains(RadioLoraRegions, lora) {
			return RadioRegion{}, false
		}
	}
	frequency := 0.0
	if v, present := o["frequency"]; present {
		if frequency, ok = jsonDouble(v); !ok || !(frequency == 0 || frequency >= 100 && frequency <= 3000) {
			return RadioRegion{}, false
		}
	}
	r := RadioRegion{Name: name, Area: area, Preset: preset, Channel0: channel0, Communities: []RadioCommunity{}, Lora: lora, Frequency: frequency}
	communities, _ := o["communities"].([]any)
	for _, c := range communities {
		if c, ok := readCommunity(c); ok {
			r.Communities = append(r.Communities, c)
		}
	}
	return r, true
}

func readCommunity(e any) (RadioCommunity, bool) {
	o, ok := e.(map[string]any)
	if !ok {
		return RadioCommunity{}, false
	}
	name, ok := o["name"].(string)
	if !ok {
		return RadioCommunity{}, false
	}
	channel, ok := o["channel"].(string)
	if !ok || len(channel) == 0 || len(channel) > 11 {
		return RadioCommunity{}, false
	}
	text, ok := o["psk"].(string)
	if !ok || strings.ContainsAny(text, "\r\n") {
		return RadioCommunity{}, false
	}
	psk, err := B64.Strict().DecodeString(text)
	if err != nil || (len(psk) != 1 && len(psk) != 16 && len(psk) != 32) {
		return RadioCommunity{}, false
	}
	preset, ok := o["preset"].(string)
	if !ok || !slices.Contains(RadioPresets, preset) {
		return RadioCommunity{}, false
	}
	operator, _ := o["operator"].(string)
	source, _ := o["source"].(string)
	return RadioCommunity{name, channel, psk, preset, operator, source}, true
}

// The JSON primitives of the Kotlin reader take numbers and their text alike (JsonPrimitive.content).

func jsonLong(v any) (int64, bool) {
	var s string
	switch t := v.(type) {
	case json.Number:
		s = t.String()
	case string:
		s = t
	default:
		return 0, false
	}
	n, err := strconv.ParseInt(s, 10, 64)
	return n, err == nil
}

func jsonDouble(v any) (float64, bool) {
	var s string
	switch t := v.(type) {
	case json.Number:
		s = t.String()
	case string:
		s = t
	default:
		return 0, false
	}
	f, err := strconv.ParseFloat(s, 64)
	return f, err == nil
}

func jsonBool(v any) (bool, bool) {
	switch t := v.(type) {
	case bool:
		return t, true
	case string:
		switch t {
		case "true":
			return true, true
		case "false":
			return false, true
		}
	}
	return false, false
}
