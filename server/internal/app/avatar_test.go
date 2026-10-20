package app

import (
	"bytes"
	"encoding/base64"
	"image"
	"image/jpeg"
	"strings"
	"testing"
)

func TestAvatarValidation(t *testing.T) {
	encode := func(width, height int) string {
		var b bytes.Buffer
		if err := jpeg.Encode(&b, image.NewRGBA(image.Rect(0, 0, width, height)), nil); err != nil {
			t.Fatal(err)
		}
		return base64.StdEncoding.EncodeToString(b.Bytes())
	}
	valid := encode(256, 256)
	for _, test := range []struct {
		name, data string
		ok         bool
	}{
		{"remove", "", true}, {"jpeg", valid, true},
		{"invalid base64", "%%%", false}, {"not an image", base64.StdEncoding.EncodeToString([]byte("test")), false},
		{"oversize", strings.Repeat("A", 65540), false}, {"too wide", encode(513, 16), false},
		{"too tall", encode(16, 513), false}, {"truncated", valid[:len(valid)/2], false},
	} {
		t.Run(test.name, func(t *testing.T) {
			if (validateAvatar(test.data) == nil) != test.ok {
				t.Fatalf("unexpected validation result")
			}
		})
	}
}
