// Package relay implements relayd, the zero-trust WebSocket relay of the HiveCore remote mesh
// (implementation plan v2 §3.2, §13.6). It authenticates phones against an admin-signed roster, forwards
// opaque ciphertext between members of one roster, holds messages for offline members for up to five minutes,
// publishes presence, sends FCM wake messages and issues per-session TURN credentials. It never sees plaintext.
package relay

import "time"

// Constants from plan §13.6 / §17.3.
const (
	// MaxCipherBytes caps one decoded `cipher`: the 64 KB envelope cap plus the client's transport framing byte(s).
	MaxCipherBytes = 64*1024 + 64
	// MaxFrameBytes caps one WebSocket text frame (base64 of MaxCipherBytes plus the JSON around it).
	MaxFrameBytes = 128 * 1024
	// HoldFor is the store-and-forward window for an offline member (plan: 5 min).
	HoldFor = 5 * time.Minute
	// PingEvery is the keep-alive cadence in both directions.
	PingEvery = 25 * time.Second
	// IdleTimeout closes a socket that sent nothing (not even a pong) for this long.
	IdleTimeout = 70 * time.Second
	// AuthTimeout closes a socket that did not authenticate in time.
	AuthTimeout = 20 * time.Second
	// RateMsgsPerSec and RateBytesPerSec are the per-node limits on the relay path.
	RateMsgsPerSec  = 50
	RateBytesPerSec = 2 * 1024 * 1024
	// MaxHeldPerNode / MaxHeldBytesPerNode bound one offline member's hold queue.
	MaxHeldPerNode      = 2000
	MaxHeldBytesPerNode = 32 * 1024 * 1024
	// WakeEvery rate-limits WAKE pushes per target.
	WakeEvery = 10 * time.Second
	// NonceBytes is the challenge size.
	NonceBytes = 32
	// OutboundQueue is the per-socket write buffer in frames; a client that falls this far behind is closed.
	OutboundQueue = 1024

	// AuthDomain and RosterDomain separate what the node key signs for the relay from envelope bodies, so a
	// malicious relay cannot pick a "nonce" that doubles as a signed message body.
	AuthDomain   = "relayd-auth-v1"
	RosterDomain = "relayd-roster-v1"
)

// Frame types.
const (
	TChallenge  = "challenge"
	TAuth       = "auth"
	TAuthOK     = "auth_ok"
	TAuthFail   = "auth_fail"
	TRoster     = "roster"
	TRosterOK   = "roster_ok"
	TRosterFail = "roster_fail"
	TMsg        = "msg"
	TSent       = "sent"
	TPresence   = "presence"
	TWake       = "wake"
	TDrop       = "drop"
	TPing       = "ping"
	TPong       = "pong"
)

// Drop and failure reasons.
const (
	ReasonExpired         = "expired"
	ReasonNotMember       = "not_member"
	ReasonRevoked         = "revoked"
	ReasonRateLimited     = "rate_limited"
	ReasonTooLarge        = "too_large"
	ReasonQueueFull       = "queue_full"
	ReasonBadFrom         = "bad_from"
	ReasonUnknownGroup    = "unknown_group"
	ReasonBadSignature    = "bad_signature"
	ReasonNotAuth         = "not_authenticated"
	ReasonBadFrame        = "bad_frame"
	ReasonStaleVersion    = "stale_version"
	ReasonBadRoster       = "bad_roster"
	ReasonNotAllowed      = "admin_not_allowed"
	ReasonReplaced        = "replaced"
	ReasonRemovedByRoster = "removed_by_roster"
)

// Member is one roster member as reported in auth_ok.
type Member struct {
	NodeID string `json:"nodeId"`
	Online bool   `json:"online"`
}

// TurnCreds are coturn `use-auth-secret` credentials issued for one session.
type TurnCreds struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username"`
	Credential string   `json:"credential"`
	TTLS       int64    `json:"ttlS"`
}

// Frame is every relayd frame, flattened; `type` selects which fields are meaningful (plan §13.6):
//
//	challenge  relay→client  {nonce, serverTs}
//	auth       client→relay  {nodeId, groupId, rosterVersion, sig, pushToken?}
//	auth_ok    relay→client  {serverTs, members:[{nodeId, online}], turn?, version}
//	auth_fail  relay→client  {reason}
//	roster     client→relay  {roster, adminSig, sig?}          sig only for a group the relay does not know yet
//	roster_ok / roster_fail  relay→client {groupId, version} / {reason}
//	msg        both          {to, from, cipher, ttlS?, id?}
//	sent       relay→client  {id, held}                         for a msg that carried an id
//	presence   relay→client  {nodeId, online, since}
//	wake       client→relay  {nodeId}
//	drop       relay→client  {to, reason, id?}
//	ping/pong  both          {ts, serverTs?}
type Frame struct {
	Type string `json:"type"`

	Nonce    string `json:"nonce,omitempty"`
	ServerTs int64  `json:"serverTs,omitempty"`
	Ts       int64  `json:"ts,omitempty"`

	NodeID        string `json:"nodeId,omitempty"`
	GroupID       string `json:"groupId,omitempty"`
	RosterVersion int64  `json:"rosterVersion,omitempty"`
	Sig           string `json:"sig,omitempty"`
	PushToken     string `json:"pushToken,omitempty"`

	Members []Member   `json:"members,omitempty"`
	Turn    *TurnCreds `json:"turn,omitempty"`
	Version int64      `json:"version,omitempty"`

	Reason string `json:"reason,omitempty"`

	Roster   []byte `json:"-"`
	RosterJS rawJSON `json:"roster,omitempty"`
	AdminSig string  `json:"adminSig,omitempty"`

	To     string `json:"to,omitempty"`
	From   string `json:"from,omitempty"`
	Cipher string `json:"cipher,omitempty"`
	TTLS   int    `json:"ttlS,omitempty"`
	ID     int64  `json:"id,omitempty"`
	Held   bool   `json:"held,omitempty"`

	Online *bool `json:"online,omitempty"`
	Since  int64 `json:"since,omitempty"`
}

// rawJSON is json.RawMessage with omitempty working on nil.
type rawJSON []byte

func (r rawJSON) MarshalJSON() ([]byte, error) {
	if len(r) == 0 {
		return []byte("null"), nil
	}
	return r, nil
}

func (r *rawJSON) UnmarshalJSON(b []byte) error {
	*r = append((*r)[:0], b...)
	return nil
}

func boolp(b bool) *bool { return &b }
