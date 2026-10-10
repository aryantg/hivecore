package relay

import (
	"crypto/hmac"
	"crypto/sha1"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"strings"
	"testing"
	"time"
)

const cipher = "AAECAwQFBgcICQ==" // opaque to the relay

func TestAuthSuccessListsMembersAndTime(t *testing.T) {
	h := newHarness(t, Options{})
	a, b := newNode(t, "a"), newNode(t, "b")
	admin := h.dial()
	if f := admin.roster(rosterJSON(t, "g1", 1, a, []*node{a, b}, nil, a)); f.Type != TRosterOK || f.Version != 1 {
		t.Fatalf("roster: %+v", *f)
	}
	ok := admin.authOK(a, "g1")
	if ok.ServerTs != h.clock.Now().UnixMilli() {
		t.Fatalf("auth_ok.serverTs = %d, want relay clock %d", ok.ServerTs, h.clock.Now().UnixMilli())
	}
	if len(ok.Members) != 2 || ok.Members[0].NodeID != a.id || !ok.Members[0].Online || ok.Members[1].NodeID != b.id || ok.Members[1].Online {
		t.Fatalf("members: %+v", ok.Members)
	}
	if ok.Version != 1 || ok.Turn != nil {
		t.Fatalf("auth_ok: %+v", *ok)
	}
}

func TestAuthFailures(t *testing.T) {
	h := newHarness(t, Options{})
	a, b, x := newNode(t, "a"), newNode(t, "b"), newNode(t, "x")
	admin := h.dial()
	admin.roster(rosterJSON(t, "g1", 1, a, []*node{a, b}, nil, a))

	c := h.dial()
	if f := c.auth(a, "nope", ""); f.Type != TAuthFail || f.Reason != ReasonUnknownGroup {
		t.Fatalf("unknown group: %+v", *f)
	}
	c = h.dial()
	if f := c.auth(x, "g1", ""); f.Type != TAuthFail || f.Reason != ReasonNotMember {
		t.Fatalf("non-member: %+v", *f)
	}
	c = h.dial()
	c.send(&Frame{Type: TAuth, NodeID: b.id, GroupID: "g1", Sig: x.sign(t, AuthBytes(c.nonce))})
	if f := c.expectOneOf(TAuthOK, TAuthFail); f.Type != TAuthFail || f.Reason != ReasonBadSignature {
		t.Fatalf("wrong key: %+v", *f)
	}
	c = h.dial()
	c.send(&Frame{Type: TAuth, NodeID: b.id, GroupID: "g1", Sig: b.sign(t, AuthBytes(make([]byte, NonceBytes)))})
	if f := c.expectOneOf(TAuthOK, TAuthFail); f.Type != TAuthFail || f.Reason != ReasonBadSignature {
		t.Fatalf("stale nonce: %+v", *f)
	}
	c = h.dial()
	c.send(&Frame{Type: TAuth, NodeID: b.id, GroupID: "g1", Sig: b.sign(t, c.nonce)}) // nonce without the domain
	if f := c.expectOneOf(TAuthOK, TAuthFail); f.Type != TAuthFail || f.Reason != ReasonBadSignature {
		t.Fatalf("undomained signature: %+v", *f)
	}
	// Anything but auth/roster/ping before auth is refused.
	c = h.dial()
	c.msg(b, a.id, cipher, 0)
	if f := c.expect(TDrop); f.Reason != ReasonNotAuth {
		t.Fatalf("pre-auth msg: %+v", *f)
	}
}

func TestRosterUploadRules(t *testing.T) {
	h := newHarness(t, Options{})
	a, b, c := newNode(t, "a"), newNode(t, "b"), newNode(t, "c")
	admin := h.dial()
	// Signed by a non-admin: refused.
	if f := admin.roster(rosterJSON(t, "g1", 1, a, []*node{a, b}, nil, b)); f.Type != TRosterFail || f.Reason != ReasonBadSignature {
		t.Fatalf("non-admin signature: %+v", *f)
	}
	// Tampered after signing: refused.
	good := string(rosterJSON(t, "g1", 1, a, []*node{a, b}, nil, a))
	if f := admin.roster([]byte(strings.Replace(good, `"name":"b"`, `"name":"B"`, 1))); f.Type != TRosterFail {
		t.Fatalf("tampered roster accepted")
	}
	if f := admin.roster([]byte(good)); f.Type != TRosterOK {
		t.Fatalf("good roster: %+v", *f)
	}
	// Same version again is fine (re-upload on reconnect); an older one is stale.
	if f := admin.roster([]byte(good)); f.Type != TRosterOK {
		t.Fatalf("re-upload: %+v", *f)
	}
	admin.roster(rosterJSON(t, "g1", 3, a, []*node{a, b, c}, nil, a))
	if f := admin.roster(rosterJSON(t, "g1", 2, a, []*node{a, b}, nil, a)); f.Type != TRosterFail || f.Reason != ReasonStaleVersion {
		t.Fatalf("stale: %+v", *f)
	}
	// Handover: version 4 names b as admin and is signed by a (the held admin); version 5 is then signed by b.
	if f := admin.roster(rosterJSON(t, "g1", 4, b, []*node{a, b, c}, nil, a)); f.Type != TRosterOK {
		t.Fatalf("handover: %+v", *f)
	}
	if f := admin.roster(rosterJSON(t, "g1", 5, b, []*node{b, c}, nil, a)); f.Type != TRosterFail {
		t.Fatalf("old admin after handover accepted")
	}
	if f := admin.roster(rosterJSON(t, "g1", 5, b, []*node{b, c}, nil, b)); f.Type != TRosterOK {
		t.Fatalf("new admin: %+v", *f)
	}
	// Removed from the roster: a can no longer authenticate.
	if f := h.dial().auth(a, "g1", ""); f.Type != TAuthFail || f.Reason != ReasonNotMember {
		t.Fatalf("removed member: %+v", *f)
	}
}

func TestForwardingIsRosterScoped(t *testing.T) {
	h := newHarness(t, Options{})
	a, b := newNode(t, "a"), newNode(t, "b")
	c := newNode(t, "c")
	g1 := h.group("g1", a, b)
	g2 := h.group("g2", c)
	ca, cb, cc := g1[0], g1[1], g2[0]

	ca.msg(a, b.id, cipher, 7)
	got := cb.expect(TMsg)
	if got.From != a.id || got.To != b.id || got.Cipher != cipher || got.ID != 7 {
		t.Fatalf("forwarded: %+v", *got)
	}
	if s := ca.expect(TSent); s.ID != 7 || s.Held {
		t.Fatalf("sent receipt: %+v", *s)
	}
	// Across rosters: dropped, and the other group never sees it.
	ca.msg(a, c.id, cipher, 8)
	if d := ca.expect(TDrop); d.Reason != ReasonNotMember || d.To != c.id || d.ID != 8 {
		t.Fatalf("cross-roster: %+v", *d)
	}
	cc.none(150 * time.Millisecond)
	// from must be the socket's own id.
	ca.msg(b, b.id, cipher, 9)
	if d := ca.expect(TDrop); d.Reason != ReasonBadFrom {
		t.Fatalf("bad from: %+v", *d)
	}
	// Oversized ciphertext.
	ca.send(&Frame{Type: TMsg, To: b.id, From: a.id, Cipher: strings.Repeat("A", base64.StdEncoding.EncodedLen(MaxCipherBytes+3))})
	if d := ca.expect(TDrop); d.Reason != ReasonTooLarge {
		t.Fatalf("too large: %+v", *d)
	}
	cb.none(100 * time.Millisecond)
}

func TestHoldAndDeliverToOfflineMember(t *testing.T) {
	h := newHarness(t, Options{})
	a, b := newNode(t, "a"), newNode(t, "b")
	cs := h.group("g1", a, b)
	ca := cs[0]
	cs[1].leave(ca)

	ca.msg(a, b.id, "one", 1)
	ca.msg(a, b.id, "two", 2)
	if s := ca.expect(TSent); s.ID != 1 || !s.Held {
		t.Fatalf("held receipt: %+v", *s)
	}
	ca.expect(TSent)
	if n := h.srv.HeldCount(b.id); n != 2 {
		t.Fatalf("held = %d", n)
	}
	h.clock.Advance(2 * time.Minute)
	h.srv.Sweep()

	cb := h.dial()
	cb.authOK(b, "g1")
	first, second := cb.expect(TMsg), cb.expect(TMsg)
	if first.Cipher != "one" || second.Cipher != "two" || first.From != a.id {
		t.Fatalf("held delivery order: %q %q", first.Cipher, second.Cipher)
	}
	if n := h.srv.HeldCount(b.id); n != 0 {
		t.Fatalf("held after delivery = %d", n)
	}
	if p := ca.expect(TPresence); p.NodeID != b.id || !*p.Online {
		t.Fatalf("presence: %+v", *p)
	}
}

func TestDropAfterHoldWindow(t *testing.T) {
	h := newHarness(t, Options{})
	a, b := newNode(t, "a"), newNode(t, "b")
	cs := h.group("g1", a, b)
	ca := cs[0]
	cs[1].leave(ca)

	ca.msg(a, b.id, "slow", 11)
	ca.send(&Frame{Type: TMsg, To: b.id, From: a.id, Cipher: "fast", ID: 12, TTLS: 30})
	ca.expect(TSent)
	ca.expect(TSent)

	h.clock.Advance(31 * time.Second)
	h.srv.Sweep()
	if d := ca.expect(TDrop); d.ID != 12 || d.Reason != ReasonExpired || d.To != b.id {
		t.Fatalf("ttlS drop: %+v", *d)
	}
	h.clock.Advance(4 * time.Minute)
	h.srv.Sweep()
	ca.none(100 * time.Millisecond) // 4:31 < 5:00
	h.clock.Advance(30 * time.Second)
	h.srv.Sweep()
	if d := ca.expect(TDrop); d.ID != 11 || d.Reason != ReasonExpired {
		t.Fatalf("hold drop: %+v", *d)
	}
	cb := h.dial()
	cb.authOK(b, "g1")
	cb.none(100 * time.Millisecond)
}

func TestPresence(t *testing.T) {
	h := newHarness(t, Options{})
	a, b := newNode(t, "a"), newNode(t, "b")
	cs := h.group("g1", a, b) // the harness already asserted a saw b come online
	ca, cb := cs[0], cs[1]

	since := h.clock.Now().UnixMilli()
	h.clock.Advance(time.Minute)
	_ = cb.ws.Close(1000, "bye")
	p := ca.expect(TPresence)
	if p.NodeID != b.id || p.Online == nil || *p.Online || p.Since != since+60_000 {
		t.Fatalf("offline presence: %+v (since %d)", *p, since)
	}
	if h.srv.Online(b.id) {
		t.Fatal("b still online")
	}
}

func TestRateLimitPerNode(t *testing.T) {
	h := newHarness(t, Options{})
	a, b := newNode(t, "a"), newNode(t, "b")
	cs := h.group("g1", a, b)
	ca, cb := cs[0], cs[1]

	// The clock is frozen, so the bucket never refills: exactly the burst gets through.
	for i := 1; i <= RateMsgsPerSec+10; i++ {
		ca.msg(a, b.id, cipher, int64(i))
	}
	sent, dropped := 0, 0
	for i := 0; i < RateMsgsPerSec+10; i++ {
		switch f := ca.expectOneOf(TSent, TDrop); f.Type {
		case TSent:
			sent++
		case TDrop:
			if f.Reason != ReasonRateLimited {
				t.Fatalf("drop reason %s", f.Reason)
			}
			dropped++
		}
	}
	if sent != RateMsgsPerSec || dropped != 10 {
		t.Fatalf("sent %d dropped %d", sent, dropped)
	}
	for i := 0; i < RateMsgsPerSec; i++ {
		cb.expect(TMsg)
	}
	cb.none(50 * time.Millisecond)
	// A second later the bucket has refilled.
	h.clock.Advance(time.Second)
	ca.msg(a, b.id, cipher, 99)
	if f := ca.expect(TSent); f.ID != 99 {
		t.Fatalf("after refill: %+v", *f)
	}
}

func TestRevocation(t *testing.T) {
	h := newHarness(t, Options{})
	a, b, c := newNode(t, "a"), newNode(t, "b"), newNode(t, "c")
	cs := h.group("g1", a, b, c)
	ca, cb, cc := cs[0], cs[1], cs[2]

	ca.msg(a, b.id, cipher, 1)
	cb.expect(TMsg)
	ca.expect(TSent)

	if f := ca.roster(rosterJSON(t, "g1", 2, a, []*node{a, b, c}, []string{b.id}, a)); f.Type != TRosterOK {
		t.Fatalf("revoking roster: %+v", *f)
	}
	cb.closed()
	for _, c := range []*client{ca, cc} {
		if p := c.expect(TPresence); p.NodeID != b.id || *p.Online {
			t.Fatalf("presence after revocation: %+v", *p)
		}
	}
	if f := h.dial().auth(b, "g1", ""); f.Type != TAuthFail || f.Reason != ReasonRevoked {
		t.Fatalf("revoked auth: %+v", *f)
	}
	ca.msg(a, b.id, cipher, 2)
	if d := ca.expect(TDrop); d.Reason != ReasonNotMember {
		t.Fatalf("msg to revoked: %+v", *d)
	}
	ok := h.dial().authOK(c, "g1")
	if len(ok.Members) != 2 {
		t.Fatalf("members after revocation: %+v", ok.Members)
	}
}

func TestWakeThroughPusher(t *testing.T) {
	p := &recordingPusher{done: make(chan struct{}, 4)}
	h := newHarness(t, Options{Pusher: p})
	a, b := newNode(t, "a"), newNode(t, "b")
	cs := h.group("g1", a, b)
	ca, cb := cs[0], cs[1]

	// b registers a push token on a fresh auth, then goes away.
	_ = cb.ws.Close(1000, "bye")
	ca.expect(TPresence)
	cb = h.dial()
	cb.auth(b, "g1", "tok-b")
	ca.expect(TPresence)
	_ = cb.ws.Close(1000, "bye")
	ca.expect(TPresence)

	ca.send(&Frame{Type: TWake, NodeID: b.id})
	select {
	case <-p.done:
	case <-time.After(3 * time.Second):
		t.Fatal("no push")
	}
	p.mu.Lock()
	call := p.calls[0]
	p.mu.Unlock()
	if call.token != "tok-b" || call.data["type"] != "wake" || call.data["groupId"] != "g1" || call.data["from"] != a.id {
		t.Fatalf("push: %+v", call)
	}
	// Rate-limited: a second wake within WakeEvery is swallowed.
	ca.send(&Frame{Type: TWake, NodeID: b.id})
	ca.none(100 * time.Millisecond)
	p.mu.Lock()
	n := len(p.calls)
	p.mu.Unlock()
	if n != 1 {
		t.Fatalf("pushes = %d", n)
	}
	// Unknown target and a member without a token.
	ca.send(&Frame{Type: TWake, NodeID: "nobody"})
	if d := ca.expect(TDrop); d.Reason != ReasonNotMember {
		t.Fatalf("wake nobody: %+v", *d)
	}
	c := newNode(t, "c")
	ca.roster(rosterJSON(t, "g1", 2, a, []*node{a, b, c}, nil, a))
	ca.send(&Frame{Type: TWake, NodeID: c.id})
	if d := ca.expect(TDrop); d.Reason != ReasonNoPushToken {
		t.Fatalf("wake without token: %+v", *d)
	}
}

func TestTurnCredentialsInAuthOK(t *testing.T) {
	issuer := &TurnIssuer{Secret: []byte("s3cret"), URLs: []string{"turn:turn.example.invalid:3478?transport=udp"}, TTL: time.Hour}
	h := newHarness(t, Options{Turn: issuer})
	a := newNode(t, "a")
	h.group("g1", a)
	c := h.dial()
	f := c.authOK(a, "g1")
	if f.Turn == nil || len(f.Turn.URLs) != 1 || f.Turn.TTLS != 3600 {
		t.Fatalf("turn: %+v", f.Turn)
	}
	wantUser := fmt.Sprintf("%d:%s", h.clock.Now().Add(time.Hour).Unix(), a.id)
	if f.Turn.Username != wantUser {
		t.Fatalf("username %q want %q", f.Turn.Username, wantUser)
	}
	mac := hmac.New(sha1.New, []byte("s3cret"))
	mac.Write([]byte(wantUser))
	if f.Turn.Credential != base64.StdEncoding.EncodeToString(mac.Sum(nil)) {
		t.Fatal("credential is not HMAC-SHA1(secret, username)")
	}
}

func TestPingPongCarriesRelayTime(t *testing.T) {
	h := newHarness(t, Options{})
	a := newNode(t, "a")
	ca := h.group("g1", a)[0]
	h.clock.Advance(1234 * time.Millisecond)
	ca.send(&Frame{Type: TPing, Ts: 42})
	p := ca.expect(TPong)
	if p.Ts != 42 || p.ServerTs != h.clock.Now().UnixMilli() {
		t.Fatalf("pong: %+v", *p)
	}
}

func TestReplacedSocketLosesToTheNewerOne(t *testing.T) {
	h := newHarness(t, Options{})
	a, b := newNode(t, "a"), newNode(t, "b")
	cs := h.group("g1", a, b)
	ca, cb1 := cs[0], cs[1]
	cb2 := h.dial()
	cb2.authOK(b, "g1")
	cb1.closed()
	ca.none(100 * time.Millisecond) // no presence flap
	ca.msg(a, b.id, cipher, 1)
	if m := cb2.expect(TMsg); m.Cipher != cipher {
		t.Fatalf("msg to the new socket: %+v", *m)
	}
}

func TestInviteGuestReachesOnlyTheInviter(t *testing.T) {
	h := newHarness(t, Options{})
	a, b, j := newNode(t, "a"), newNode(t, "b"), newNode(t, "joiner")
	cs := h.group("g1", a, b)
	ca, cb := cs[0], cs[1]

	token := "tok-123"
	sum := sha256.Sum256([]byte(token))
	hash := hex.EncodeToString(sum[:])

	// Nobody can join before the invite is open.
	cj := h.dial()
	cj.send(&Frame{Type: TAuth, NodeID: j.id, GroupID: "g1", Pubkey: j.pubB64, Invite: token, Inviter: a.id, Sig: j.sign(t, AuthBytes(cj.nonce))})
	if f := cj.expectOneOf(TAuthOK, TAuthFail); f.Type != TAuthFail || f.Reason != ReasonBadInvite {
		t.Fatalf("closed invite: %+v", *f)
	}

	ca.send(&Frame{Type: TInvite, TokenHash: hash})
	if f := ca.expect(TInviteOK); f.TokenHash != hash || f.TTLS != 600 {
		t.Fatalf("invite_ok: %+v", *f)
	}
	cj = h.dial()
	cj.send(&Frame{Type: TAuth, NodeID: j.id, GroupID: "g1", Pubkey: j.pubB64, Invite: token, Inviter: a.id, Sig: j.sign(t, AuthBytes(cj.nonce))})
	ok := cj.expectOneOf(TAuthOK, TAuthFail)
	if ok.Type != TAuthOK || len(ok.Members) != 1 || ok.Members[0].NodeID != a.id || !ok.Members[0].Online {
		t.Fatalf("guest auth: %+v", *ok)
	}
	cb.none(100 * time.Millisecond) // no presence for a guest

	cj.msg(j, a.id, "hello", 1)
	if m := ca.expect(TMsg); m.From != j.id || m.Cipher != "hello" {
		t.Fatalf("guest → inviter: %+v", *m)
	}
	cj.expect(TSent)
	ca.msg(a, j.id, "welcome", 2)
	if m := cj.expect(TMsg); m.From != a.id || m.Cipher != "welcome" {
		t.Fatalf("inviter → guest: %+v", *m)
	}
	ca.expect(TSent)
	cj.msg(j, b.id, "psst", 3)
	if d := cj.expect(TDrop); d.Reason != ReasonGuestOnly {
		t.Fatalf("guest → member: %+v", *d)
	}
	cb.msg(b, j.id, "hi", 4)
	if d := cb.expect(TDrop); d.Reason != ReasonNotMember {
		t.Fatalf("member → someone else's guest: %+v", *d)
	}
	cj.send(&Frame{Type: TWake, NodeID: b.id})
	if d := cj.expect(TDrop); d.Reason != ReasonGuestOnly {
		t.Fatalf("guest wake: %+v", *d)
	}
	// The invite expires after 10 minutes and takes the guest socket with it.
	h.clock.Advance(InviteTTL + time.Second)
	h.srv.Sweep()
	cj.closed()
}
