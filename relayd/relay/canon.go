package relay

import (
	"bytes"
	"encoding/json"
	"errors"
	"sort"
	"strconv"
	"strings"
)

// Canonical JSON (spec 6.6), byte-for-byte what the Kotlin side (`core.Canon`) writes: object keys sorted, no
// whitespace, strings escaped the way kotlinx.serialization escapes them (`"`, `\`, \b \t \n \f \r, other control
// characters as \u00xx, nothing else), numbers as the shortest exact decimal (`5`, `5.0`, `5e0` all print `5`,
// `-0` prints `0`). A roster's adminSig is verified over this form of the body without `adminSig`.

// Canonical re-serialises one JSON document canonically. Numbers keep their exact decimal value.
func Canonical(doc []byte) ([]byte, error) {
	dec := json.NewDecoder(bytes.NewReader(doc))
	dec.UseNumber()
	var v any
	if err := dec.Decode(&v); err != nil {
		return nil, err
	}
	if dec.More() {
		return nil, errors.New("trailing data after JSON value")
	}
	var out bytes.Buffer
	if err := writeCanonical(&out, v); err != nil {
		return nil, err
	}
	return out.Bytes(), nil
}

// CanonicalWithout canonicalises an object after deleting the named top-level keys.
func CanonicalWithout(doc []byte, keys ...string) ([]byte, error) {
	dec := json.NewDecoder(bytes.NewReader(doc))
	dec.UseNumber()
	var m map[string]any
	if err := dec.Decode(&m); err != nil {
		return nil, err
	}
	for _, k := range keys {
		delete(m, k)
	}
	var out bytes.Buffer
	if err := writeCanonical(&out, m); err != nil {
		return nil, err
	}
	return out.Bytes(), nil
}

func writeCanonical(out *bytes.Buffer, v any) error {
	switch x := v.(type) {
	case nil:
		out.WriteString("null")
	case bool:
		if x {
			out.WriteString("true")
		} else {
			out.WriteString("false")
		}
	case string:
		writeCanonicalString(out, x)
	case json.Number:
		out.WriteString(canonicalNumber(string(x)))
	case float64: // only when a caller hands a decoded value without UseNumber
		out.WriteString(canonicalNumber(strconv.FormatFloat(x, 'f', -1, 64)))
	case []any:
		out.WriteByte('[')
		for i, e := range x {
			if i > 0 {
				out.WriteByte(',')
			}
			if err := writeCanonical(out, e); err != nil {
				return err
			}
		}
		out.WriteByte(']')
	case map[string]any:
		keys := make([]string, 0, len(x))
		for k := range x {
			keys = append(keys, k)
		}
		sort.Slice(keys, func(i, j int) bool { return utf16Less(keys[i], keys[j]) })
		out.WriteByte('{')
		for i, k := range keys {
			if i > 0 {
				out.WriteByte(',')
			}
			writeCanonicalString(out, k)
			out.WriteByte(':')
			if err := writeCanonical(out, x[k]); err != nil {
				return err
			}
		}
		out.WriteByte('}')
	default:
		return errors.New("unsupported JSON value")
	}
	return nil
}

// utf16Less orders strings by UTF-16 code units, as Kotlin's String.compareTo does. It only differs from byte order
// for characters outside the Basic Multilingual Plane against U+E000..U+FFFF.
func utf16Less(a, b string) bool {
	ra, rb := []rune(a), []rune(b)
	for i := 0; i < len(ra) && i < len(rb); i++ {
		if ra[i] == rb[i] {
			continue
		}
		ua, ub := utf16Units(ra[i]), utf16Units(rb[i])
		for j := 0; j < len(ua) && j < len(ub); j++ {
			if ua[j] != ub[j] {
				return ua[j] < ub[j]
			}
		}
		return len(ua) < len(ub)
	}
	return len(ra) < len(rb)
}

func utf16Units(r rune) []uint16 {
	if r < 0x10000 {
		return []uint16{uint16(r)}
	}
	r -= 0x10000
	return []uint16{uint16(0xD800 + (r >> 10)), uint16(0xDC00 + (r & 0x3FF))}
}

const hexDigits = "0123456789abcdef"

func writeCanonicalString(out *bytes.Buffer, s string) {
	out.WriteByte('"')
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch c {
		case '"':
			out.WriteString(`\"`)
		case '\\':
			out.WriteString(`\\`)
		case '\b':
			out.WriteString(`\b`)
		case '\t':
			out.WriteString(`\t`)
		case '\n':
			out.WriteString(`\n`)
		case '\f':
			out.WriteString(`\f`)
		case '\r':
			out.WriteString(`\r`)
		default:
			if c < 0x20 {
				out.WriteString(`\u00`)
				out.WriteByte(hexDigits[c>>4])
				out.WriteByte(hexDigits[c&0xf])
			} else {
				out.WriteByte(c)
			}
		}
	}
	out.WriteByte('"')
}

// canonicalNumber rewrites a JSON number literal as its shortest exact plain decimal.
func canonicalNumber(s string) string {
	neg := strings.HasPrefix(s, "-")
	s = strings.TrimPrefix(strings.TrimPrefix(s, "-"), "+")
	mant, exp := s, 0
	if i := strings.IndexAny(s, "eE"); i >= 0 {
		mant = s[:i]
		exp, _ = strconv.Atoi(s[i+1:])
	}
	intPart, frac := mant, ""
	if i := strings.IndexByte(mant, '.'); i >= 0 {
		intPart, frac = mant[:i], mant[i+1:]
	}
	digits := strings.TrimLeft(intPart+frac, "0")
	scale := len(frac) - exp // value = digits × 10^-scale
	if digits == "" {
		return "0"
	}
	for scale > 0 && strings.HasSuffix(digits, "0") {
		digits = digits[:len(digits)-1]
		scale--
	}
	if scale < 0 {
		digits += strings.Repeat("0", -scale)
		scale = 0
	}
	var out string
	switch {
	case scale == 0:
		out = digits
	case len(digits) > scale:
		out = digits[:len(digits)-scale] + "." + digits[len(digits)-scale:]
	default:
		out = "0." + strings.Repeat("0", scale-len(digits)) + digits
	}
	if neg {
		out = "-" + out
	}
	return out
}
