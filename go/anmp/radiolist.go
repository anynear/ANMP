package anmp

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"slices"
	"strconv"
	"strings"
)

// RadioPresets are the Meshtastic modem presets a region or community may name; others are skipped.
var RadioPresets = []string{
	"LONG_FAST", "LONG_SLOW", "LONG_MODERATE", "LONG_TURBO", "MEDIUM_FAST", "MEDIUM_SLOW",
	"SHORT_FAST", "SHORT_SLOW", "SHORT_TURBO",
}

// RadioList is the content of the radio list (ADR 0054 §1b, spec "Radio list"): regions with their area, the preset
// of channel 0, whether ANMP goes out on channel 0, and the communities' public channels. ANDP checks head and
// signature (andp.NetworkList.AcceptRadio); ParseRadioList reads what it accepted.
type RadioList struct {
	Issued  int64 // Unix milliseconds
	Regions []RadioRegion
}

// RadioRegion is one region: Area as [lat, lon] points of a simple polygon, Preset of channel 0, its communities.
type RadioRegion struct {
	Name        string
	Area        [][2]float64
	Preset      string
	Channel0    bool // whether ANMP goes out on channel 0 here; otherwise only on the channel anynear
	Communities []RadioCommunity
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
	return l, nil
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
	r := RadioRegion{Name: name, Area: area, Preset: preset, Channel0: channel0, Communities: []RadioCommunity{}}
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
