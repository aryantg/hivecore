package relay

import (
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
)

// Roster handling (spec 6.4): the admin-signed membership list the relay authenticates against. The relay keeps
// one roster per group in memory (the admin re-uploads on reconnect, spec 20.1); it never stores keys elsewhere.

// rosterDoc is the roster body as uploaded (`RosterBody` on the Kotlin side); `adminSig` may sit inside it or in
// the roster frame's own `adminSig` field.
type rosterDoc struct {
	GroupID  string         `json:"groupId"`
	Version  int64          `json:"version"`
	Admin    string         `json:"admin"`
	Members  []rosterMember `json:"members"`
	Revoked  []string       `json:"revoked"`
	AdminSig string         `json:"adminSig"`
}

type rosterMember struct {
	NodeID  string `json:"nodeId"`
	Pubkey  string `json:"pubkey"`
	Name    string `json:"name"`
	AddedTs int64  `json:"addedTs"`
}

// Roster is a parsed, verified roster.
type Roster struct {
	GroupID string
	Version int64
	Admin   string
	Order   []string                    // member nodeIds in roster order (for auth_ok)
	Members map[string]*ecdsa.PublicKey // nodeId → key
	Names   map[string]string
	Revoked map[string]bool
	Raw     []byte // the body as uploaded
}

// IsMember reports whether nodeId is a current, unrevoked member.
func (r *Roster) IsMember(nodeID string) bool {
	_, ok := r.Members[nodeID]
	return ok && !r.Revoked[nodeID]
}

// NodeIDOf is `sha256(DER SubjectPublicKeyInfo)` in lowercase hex, as `Wire.nodeIdOf` computes it.
func NodeIDOf(pubkeyB64 string) (string, *ecdsa.PublicKey, error) {
	der, err := base64.StdEncoding.DecodeString(pubkeyB64)
	if err != nil {
		return "", nil, fmt.Errorf("pubkey is not base64: %w", err)
	}
	pk, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return "", nil, fmt.Errorf("pubkey is not SubjectPublicKeyInfo: %w", err)
	}
	ec, ok := pk.(*ecdsa.PublicKey)
	if !ok {
		return "", nil, errors.New("pubkey is not an EC key")
	}
	sum := sha256.Sum256(der)
	return hex.EncodeToString(sum[:]), ec, nil
}

// VerifySig checks a base64 DER ECDSA signature (SHA256withECDSA) over msg.
func VerifySig(pub *ecdsa.PublicKey, msg []byte, sigB64 string) bool {
	sig, err := base64.StdEncoding.DecodeString(sigB64)
	if err != nil || pub == nil {
		return false
	}
	h := sha256.Sum256(msg)
	return ecdsa.VerifyASN1(pub, h[:], sig)
}

// ParseRoster validates the shape of an uploaded roster body and resolves every member key. It does not verify
// adminSig; see VerifyRoster.
func ParseRoster(body []byte, adminSig string) (*Roster, string, error) {
	var d rosterDoc
	if err := json.Unmarshal(body, &d); err != nil {
		return nil, "", fmt.Errorf("roster body: %w", err)
	}
	if adminSig == "" {
		adminSig = d.AdminSig
	}
	if d.GroupID == "" || d.Admin == "" || d.Version <= 0 || len(d.Members) == 0 || adminSig == "" {
		return nil, "", errors.New("roster needs groupId, version > 0, admin, members and adminSig")
	}
	r := &Roster{GroupID: d.GroupID, Version: d.Version, Admin: d.Admin, Members: map[string]*ecdsa.PublicKey{},
		Names: map[string]string{}, Revoked: map[string]bool{}, Raw: append([]byte(nil), body...)}
	for _, m := range d.Members {
		id, key, err := NodeIDOf(m.Pubkey)
		if err != nil {
			return nil, "", fmt.Errorf("member %s: %w", m.NodeID, err)
		}
		if id != m.NodeID {
			return nil, "", fmt.Errorf("member %s: pubkey hashes to %s", m.NodeID, id)
		}
		if _, dup := r.Members[id]; dup {
			return nil, "", fmt.Errorf("member %s listed twice", id)
		}
		r.Members[id] = key
		r.Names[id] = m.Name
		r.Order = append(r.Order, id)
	}
	for _, id := range d.Revoked {
		r.Revoked[id] = true
	}
	if _, ok := r.Members[d.Admin]; !ok {
		return nil, "", errors.New("admin is not a member")
	}
	return r, adminSig, nil
}

// VerifyRoster checks adminSig over the canonical body without `adminSig` against the signing admin's key. For a
// group the relay already holds, the signer is the held roster's admin (so a handover roster naming a successor is
// signed by the outgoing admin, spec 6.4); for a new group it is the roster's own admin.
func VerifyRoster(r *Roster, adminSig string, held *Roster) error {
	canon, err := CanonicalWithout(r.Raw, "adminSig")
	if err != nil {
		return err
	}
	signer := r.Admin
	if held != nil {
		signer = held.Admin
	}
	key := r.Members[signer]
	if key == nil && held != nil {
		key = held.Members[signer]
	}
	if key == nil {
		return fmt.Errorf("no key for admin %s", signer)
	}
	if !VerifySig(key, canon, adminSig) {
		return errors.New("adminSig does not verify")
	}
	return nil
}
