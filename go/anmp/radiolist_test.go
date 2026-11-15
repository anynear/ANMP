package anmp

import (
	"encoding/hex"
	"encoding/json"
	"os"
	"reflect"
	"testing"
)

// vectors/anmp-v1-radio-list.json is written by kotlin/core RadioListVectorsTest (UPDATE_VECTORS=1).

const radioListVectorsPath = "../../vectors/anmp-v1-radio-list.json"

type radioListVectors struct {
	Cases []struct {
		Name   string          `json:"name"`
		Text   string          `json:"text"`
		Result json.RawMessage `json:"result"`
	} `json:"cases"`
	Points []struct {
		Name   string  `json:"name"`
		Lat    float64 `json:"lat"`
		Lon    float64 `json:"lon"`
		Region *string `json:"region"`
	} `json:"points"`
	Choices []struct {
		Name    string  `json:"name"`
		Lat     float64 `json:"lat"`
		Lon     float64 `json:"lon"`
		Current *string `json:"current"`
		Region  *string `json:"region"`
	} `json:"choices"`
}

type vRadioList struct {
	Issued  int64     `json:"issued"`
	Regions []vRegion `json:"regions"`
	Gateway *vGateway `json:"gateway"`
}

type vGateway struct {
	Host    string `json:"host"`
	Port    int    `json:"port"`
	TLS     bool   `json:"tls"`
	User    string `json:"user"`
	Pass    string `json:"pass"`
	Root    string `json:"root"`
	Address string `json:"address"`
}

type vRegion struct {
	Name        string       `json:"name"`
	Area        [][2]float64 `json:"area"`
	Preset      string       `json:"preset"`
	Channel0    bool         `json:"channel0"`
	Communities []vCommunity `json:"communities"`
	Lora        string       `json:"lora"`
	Frequency   float64      `json:"frequency"`
}

type vCommunity struct {
	Name     string `json:"name"`
	Channel  string `json:"channel"`
	PSK      string `json:"psk"`
	Preset   string `json:"preset"`
	Operator string `json:"operator"`
	Source   string `json:"source"`
}

func TestRadioListVectors(t *testing.T) {
	data, err := os.ReadFile(radioListVectorsPath)
	if err != nil {
		t.Fatal(err)
	}
	var v radioListVectors
	if err := json.Unmarshal(data, &v); err != nil {
		t.Fatal(err)
	}
	var list *RadioList
	for _, c := range v.Cases {
		got, err := ParseRadioList([]byte(c.Text))
		if string(c.Result) == `"error"` {
			if err == nil {
				t.Errorf("%s: accepted, Kotlin refuses it", c.Name)
			}
			continue
		}
		if err != nil {
			t.Errorf("%s: %v", c.Name, err)
			continue
		}
		var want vRadioList
		if err := json.Unmarshal(c.Result, &want); err != nil {
			t.Fatal(err)
		}
		have := vRadioList{Issued: got.Issued}
		if g := got.Gateway; g != nil {
			have.Gateway = &vGateway{g.Host, g.Port, g.TLS, g.User, g.Password, g.Root, g.Address()}
		}
		for _, r := range got.Regions {
			e := vRegion{Name: r.Name, Area: r.Area, Preset: r.Preset, Channel0: r.Channel0, Lora: r.Lora, Frequency: r.Frequency}
			for _, cm := range r.Communities {
				e.Communities = append(e.Communities, vCommunity{cm.Name, cm.Channel, hex.EncodeToString(cm.PSK), cm.Preset, cm.Operator, cm.Source})
			}
			have.Regions = append(have.Regions, e)
		}
		normalize(&want)
		normalize(&have)
		if !reflect.DeepEqual(want, have) {
			t.Errorf("%s:\n want %+v\n got  %+v", c.Name, want, have)
		}
		if c.Name == "list" {
			list = got
		}
	}
	if list == nil {
		t.Fatal("case list missing")
	}
	for _, p := range v.Points {
		r := list.RegionAt(p.Lat, p.Lon)
		switch {
		case p.Region == nil && r != nil:
			t.Errorf("%s: in %s, Kotlin finds no region", p.Name, r.Name)
		case p.Region != nil && (r == nil || r.Name != *p.Region):
			t.Errorf("%s: %v, Kotlin finds %s", p.Name, r, *p.Region)
		}
	}
	if len(v.Choices) == 0 {
		t.Fatal("choices missing")
	}
	for _, c := range v.Choices {
		current := ""
		if c.Current != nil {
			current = *c.Current
		}
		r := list.RegionFor(c.Lat, c.Lon, current, RadioBorderMarginKm)
		switch {
		case c.Region == nil && r != nil:
			t.Errorf("%s: %s, Kotlin finds no region", c.Name, r.Name)
		case c.Region != nil && (r == nil || r.Name != *c.Region):
			t.Errorf("%s: %v, Kotlin finds %s", c.Name, r, *c.Region)
		}
	}
}

// normalize makes empty and missing lists equal for the comparison.
func normalize(l *vRadioList) {
	if len(l.Regions) == 0 {
		l.Regions = nil
	}
	for i := range l.Regions {
		if len(l.Regions[i].Communities) == 0 {
			l.Regions[i].Communities = nil
		}
	}
}
