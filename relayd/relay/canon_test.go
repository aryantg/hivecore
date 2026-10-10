package relay

import "testing"

// The same vector is asserted against `core.Canon` in the Kotlin tests (RelayFramesTest), so the two canonical
// writers are checked against each other without a shared runtime.
const canonVectorIn = `{"version":5.0,"groupId":"g\"1","members":[{"nodeId":"n","addedTs":1700000000000,"name":"tab\tname","pubkey":"k"}],"admin":"n","revoked":[],"adminSig":"x","ratio":1.50,"neg":-0,"exp":1e3,"small":0.000100,"ctl":"a\u0001b","uni":"é"}`
const canonVectorOut = `{"admin":"n","ctl":"a\u0001b","exp":1000,"groupId":"g\"1","members":[{"addedTs":1700000000000,"name":"tab\tname","nodeId":"n","pubkey":"k"}],"neg":0,"ratio":1.5,"revoked":[],"small":0.0001,"uni":"é","version":5}`

func TestCanonicalMatchesKotlinVector(t *testing.T) {
	got, err := CanonicalWithout([]byte(canonVectorIn), "adminSig")
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != canonVectorOut {
		t.Fatalf("canonical mismatch:\n got %s\nwant %s", got, canonVectorOut)
	}
	full, err := Canonical([]byte(`[1.0, -2.50, 3e-2, true, null, "x"]`))
	if err != nil || string(full) != `[1,-2.5,0.03,true,null,"x"]` {
		t.Fatalf("array: %s %v", full, err)
	}
}

func TestCanonicalNumber(t *testing.T) {
	cases := map[string]string{"0": "0", "-0": "0", "0.0": "0", "5": "5", "5.00": "5", "1.50": "1.5", "1e3": "1000",
		"1.5e2": "150", "25e-1": "2.5", "0.000100": "0.0001", "-7.250": "-7.25", "123456789012345678901234567890": "123456789012345678901234567890",
		"1E-7": "0.0000001"}
	for in, want := range cases {
		if got := canonicalNumber(in); got != want {
			t.Errorf("%s → %s, want %s", in, got, want)
		}
	}
}

func TestUTF16Order(t *testing.T) {
	// U+1F600 (emoji, surrogate 0xD83D) sorts before U+FF5E (fullwidth tilde) in UTF-16, after it in UTF-8 bytes.
	if !utf16Less("\U0001F600", "～") || utf16Less("～", "\U0001F600") {
		t.Fatal("expected UTF-16 order")
	}
	if utf16Less("b", "a") || !utf16Less("a", "ab") {
		t.Fatal("basic order")
	}
}
