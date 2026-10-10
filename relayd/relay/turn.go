package relay

import (
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"fmt"
	"time"
)

// TurnIssuer issues coturn `use-auth-secret` (REST API) credentials per session (spec 20.1): the username is
// `<expiry unix seconds>:<nodeId>` and the credential is base64(HMAC-SHA1(secret, username)). coturn checks the
// HMAC with its `static-auth-secret` and refuses the pair after the expiry, so a leaked credential dies with the
// session. Rotating the secret weekly on both sides invalidates everything older.
type TurnIssuer struct {
	Secret []byte
	URLs   []string
	TTL    time.Duration
}

// Issue returns credentials for nodeId valid for TTL from now, or nil when TURN is not configured.
func (t *TurnIssuer) Issue(nodeID string, now time.Time) *TurnCreds {
	if t == nil || len(t.Secret) == 0 || len(t.URLs) == 0 {
		return nil
	}
	ttl := t.TTL
	if ttl <= 0 {
		ttl = 24 * time.Hour
	}
	user := fmt.Sprintf("%d:%s", now.Add(ttl).Unix(), nodeID)
	mac := hmac.New(sha1.New, t.Secret)
	mac.Write([]byte(user))
	return &TurnCreds{URLs: append([]string(nil), t.URLs...), Username: user,
		Credential: base64.StdEncoding.EncodeToString(mac.Sum(nil)), TTLS: int64(ttl / time.Second)}
}
