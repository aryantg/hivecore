package relay

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"
)

// Options configure a Server. Zero values take the protocol constants; tests shorten them and inject a clock.
type Options struct {
	Pusher Pusher
	Turn   *TurnIssuer
	Now    func() time.Time
	Logger *log.Logger

	HoldFor     time.Duration
	PingEvery   time.Duration
	IdleTimeout time.Duration
	AuthTimeout time.Duration
	// SweepEvery is the janitor period for the hold queue and invites; 0 = every second, < 0 = none (tests call Sweep).
	SweepEvery time.Duration
}

// Server is the relay: an http.Handler that upgrades to WebSocket and runs the protocol of protocol.go. All state
// (rosters, live sockets, the hold queue, push tokens, open invites) is in memory; spec 5.3 and 20.1.
type Server struct {
	opt Options

	mu        sync.Mutex
	groups    map[string]*group
	conns     map[string]*conn // nodeId → the authenticated socket (members and guests)
	held      map[string][]*heldMsg
	heldBytes map[string]int
	tokens    map[string]string // nodeId → push token (registered at auth)
	lastWake  map[string]time.Time
	nextID    int64

	stopOnce sync.Once
	stop     chan struct{}
}

type group struct {
	roster  *Roster
	since   map[string]int64  // presence change time per member, ms
	invites map[string]invite // sha256 hex of the invite token → invite
}

type invite struct {
	inviter string
	expires time.Time
}

type heldMsg struct {
	from    string
	frame   []byte
	id      int64
	expires time.Time
	size    int
}

// NewServer builds a server; call Start to run the janitor and Stop to end it.
func NewServer(opt Options) *Server {
	if opt.Pusher == nil {
		opt.Pusher = NoopPusher{Logger: opt.Logger}
	}
	if opt.Now == nil {
		opt.Now = time.Now
	}
	if opt.Logger == nil {
		opt.Logger = log.Default()
	}
	if opt.HoldFor == 0 {
		opt.HoldFor = HoldFor
	}
	if opt.PingEvery == 0 {
		opt.PingEvery = PingEvery
	}
	if opt.IdleTimeout == 0 {
		opt.IdleTimeout = IdleTimeout
	}
	if opt.AuthTimeout == 0 {
		opt.AuthTimeout = AuthTimeout
	}
	if opt.SweepEvery == 0 {
		opt.SweepEvery = time.Second
	}
	return &Server{opt: opt, groups: map[string]*group{}, conns: map[string]*conn{}, held: map[string][]*heldMsg{},
		heldBytes: map[string]int{}, tokens: map[string]string{}, lastWake: map[string]time.Time{}, stop: make(chan struct{})}
}

func (s *Server) now() time.Time { return s.opt.Now() }
func (s *Server) nowMs() int64   { return s.now().UnixMilli() }

// Start runs the janitor until Stop.
func (s *Server) Start() {
	if s.opt.SweepEvery < 0 {
		return
	}
	go func() {
		t := time.NewTicker(s.opt.SweepEvery)
		defer t.Stop()
		for {
			select {
			case <-t.C:
				s.Sweep()
			case <-s.stop:
				return
			}
		}
	}()
}

// Stop ends the janitor and closes every socket.
func (s *Server) Stop() {
	s.stopOnce.Do(func() { close(s.stop) })
	s.mu.Lock()
	cs := make([]*conn, 0, len(s.conns))
	for _, c := range s.conns {
		cs = append(cs, c)
	}
	s.mu.Unlock()
	for _, c := range cs {
		c.close(websocket.StatusGoingAway, "shutdown")
	}
}

// ServeHTTP upgrades the request and serves one socket until it closes.
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	// Phones send no Origin header; browsers are not clients of this relay, so no origin allow-list applies.
	ws, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
	if err != nil {
		return
	}
	ws.SetReadLimit(MaxFrameBytes)
	c := &conn{s: s, ws: ws, out: make(chan []byte, OutboundQueue), closed: make(chan struct{})}
	s.serve(c)
}

// ---------------------------------------------------------------- connection

type conn struct {
	s  *Server
	ws *websocket.Conn

	out       chan []byte
	closed    chan struct{}
	closeOnce sync.Once

	closeCode   websocket.StatusCode
	closeReason string

	nonce   []byte
	nodeID  string
	groupID string
	guest   bool
	inviter string
	authed  bool
	msgs    *bucket
	bytes   *bucket
}

func (c *conn) send(f *Frame) {
	b, err := json.Marshal(f)
	if err != nil {
		return
	}
	select {
	case <-c.closed:
	case c.out <- b:
	default:
		c.close(websocket.StatusPolicyViolation, "client too slow")
	}
}

func (c *conn) sendRaw(b []byte) {
	select {
	case <-c.closed:
	case c.out <- b:
	default:
		c.close(websocket.StatusPolicyViolation, "client too slow")
	}
}

// close marks the socket for closing; the write loop drains what is queued (an auth_fail, a drop) and then sends
// the close frame, so the client always sees the reason before the socket goes.
func (c *conn) close(code websocket.StatusCode, reason string) {
	c.closeOnce.Do(func() {
		c.closeCode, c.closeReason = code, reason
		close(c.closed)
	})
}

func (s *Server) serve(c *conn) {
	defer s.detach(c)
	defer c.close(websocket.StatusNormalClosure, "bye")

	nonce := make([]byte, NonceBytes)
	if _, err := rand.Read(nonce); err != nil {
		return
	}
	c.nonce = nonce
	go s.writeLoop(c)
	go s.pingLoop(c)
	c.send(&Frame{Type: TChallenge, Nonce: base64.StdEncoding.EncodeToString(nonce), ServerTs: s.nowMs()})

	for {
		timeout := s.opt.IdleTimeout
		if !c.authed {
			timeout = s.opt.AuthTimeout
		}
		ctx, cancel := context.WithTimeout(context.Background(), timeout)
		kind, data, err := c.ws.Read(ctx)
		cancel()
		if err != nil {
			return
		}
		if kind != websocket.MessageText {
			continue
		}
		var f Frame
		if err := json.Unmarshal(data, &f); err != nil {
			c.send(&Frame{Type: TDrop, Reason: ReasonBadFrame})
			continue
		}
		if !s.handle(c, &f, len(data)) {
			return
		}
	}
}

func (s *Server) writeLoop(c *conn) {
	write := func(b []byte) error {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		return c.ws.Write(ctx, websocket.MessageText, b)
	}
	for {
		select {
		case <-c.closed:
			for drained := false; !drained; {
				select {
				case b := <-c.out:
					if write(b) != nil {
						drained = true
					}
				default:
					drained = true
				}
			}
			_ = c.ws.Close(c.closeCode, c.closeReason)
			return
		case b := <-c.out:
			if write(b) != nil {
				c.close(websocket.StatusAbnormalClosure, "write failed")
				_ = c.ws.CloseNow()
				return
			}
		}
	}
}

func (s *Server) pingLoop(c *conn) {
	t := time.NewTicker(s.opt.PingEvery)
	defer t.Stop()
	for {
		select {
		case <-c.closed:
			return
		case <-t.C:
			c.send(&Frame{Type: TPing, ServerTs: s.nowMs()})
		}
	}
}

// detach forgets a closed socket: presence offline for a member, nothing for a guest or a replaced socket.
func (s *Server) detach(c *conn) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.detachLocked(c)
}

func (s *Server) detachLocked(c *conn) {
	if !c.authed || s.conns[c.nodeID] != c {
		return
	}
	delete(s.conns, c.nodeID)
	if c.guest {
		return
	}
	if g := s.groups[c.groupID]; g != nil {
		g.since[c.nodeID] = s.nowMs()
		s.broadcastPresenceLocked(g, c.groupID, c.nodeID, false)
	}
}

// handle routes one frame; false ends the socket.
func (s *Server) handle(c *conn, f *Frame, wire int) bool {
	switch f.Type {
	case TPing:
		c.send(&Frame{Type: TPong, Ts: f.Ts, ServerTs: s.nowMs()})
		return true
	case TPong:
		return true
	case TAuth:
		return s.auth(c, f)
	case TRoster:
		s.applyRoster(c, f)
		return true
	}
	if !c.authed {
		c.send(&Frame{Type: TDrop, Reason: ReasonNotAuth})
		return true
	}
	switch f.Type {
	case TMsg:
		s.forward(c, f, wire)
	case TWake:
		s.wake(c, f)
	case TInvite:
		s.openInvite(c, f)
	default:
		c.send(&Frame{Type: TDrop, Reason: ReasonBadFrame})
	}
	return true
}

// ---------------------------------------------------------------- auth

func (s *Server) auth(c *conn, f *Frame) bool {
	fail := func(reason string) bool {
		c.send(&Frame{Type: TAuthFail, Reason: reason})
		c.close(websocket.StatusPolicyViolation, reason)
		return false
	}
	if c.authed {
		return fail(ReasonBadFrame)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	g := s.groups[f.GroupID]
	if g == nil {
		return fail(ReasonUnknownGroup)
	}
	now := s.now()
	if f.Invite != "" {
		// Guest (spec 6.2 remote join): a joiner presenting an open invite token, reachable by the inviter only.
		if f.Pubkey == "" {
			return fail(ReasonBadInvite)
		}
		id, key, err := NodeIDOf(f.Pubkey)
		if err != nil || id != f.NodeID {
			return fail(ReasonBadInvite)
		}
		sum := sha256.Sum256([]byte(f.Invite))
		inv, ok := g.invites[hex.EncodeToString(sum[:])]
		if !ok || now.After(inv.expires) || (f.Inviter != "" && f.Inviter != inv.inviter) {
			return fail(ReasonBadInvite)
		}
		if !VerifySig(key, AuthBytes(c.nonce), f.Sig) {
			return fail(ReasonBadSignature)
		}
		if g.roster.IsMember(id) {
			return fail(ReasonBadInvite) // members authenticate as members
		}
		c.guest, c.inviter = true, inv.inviter
		c.msgs = newBucket(GuestRateMsgsPerSec, GuestRateMsgsPerSec, now)
		c.bytes = newBucket(GuestRateBytesPerSec, GuestRateBytesPerSec, now)
	} else {
		key := g.roster.Members[f.NodeID]
		if g.roster.Revoked[f.NodeID] {
			return fail(ReasonRevoked)
		}
		if key == nil {
			return fail(ReasonNotMember)
		}
		if !VerifySig(key, AuthBytes(c.nonce), f.Sig) {
			return fail(ReasonBadSignature)
		}
		c.msgs = newBucket(RateMsgsPerSec, RateMsgsPerSec, now)
		c.bytes = newBucket(RateBytesPerSec, RateBytesPerSec, now)
	}
	c.nodeID, c.groupID, c.authed = f.NodeID, f.GroupID, true
	old := s.conns[f.NodeID]
	if old != nil {
		// One socket per node: the newer one wins, and no presence flap is announced.
		delete(s.conns, f.NodeID)
		old.close(websocket.StatusPolicyViolation, ReasonReplaced)
	} else if !c.guest {
		g.since[c.nodeID] = s.nowMs()
	}
	s.conns[f.NodeID] = c
	if f.PushToken != "" && !c.guest {
		s.tokens[f.NodeID] = f.PushToken
	}

	ok := &Frame{Type: TAuthOK, ServerTs: s.nowMs(), Version: g.roster.Version, Turn: s.opt.Turn.Issue(c.nodeID, now)}
	if c.guest {
		ok.Members = []Member{{NodeID: c.inviter, Online: s.conns[c.inviter] != nil}}
	} else {
		for _, id := range g.roster.Order {
			if !g.roster.Revoked[id] {
				ok.Members = append(ok.Members, Member{NodeID: id, Online: s.conns[id] != nil})
			}
		}
	}
	c.send(ok)
	if !c.guest {
		if old == nil {
			s.broadcastPresenceLocked(g, c.groupID, c.nodeID, true)
		}
		for _, h := range s.held[c.nodeID] {
			c.sendRaw(h.frame)
		}
		delete(s.held, c.nodeID)
		delete(s.heldBytes, c.nodeID)
	}
	return true
}

// broadcastPresenceLocked tells every other connected member of the group about nodeId (spec 5.3).
func (s *Server) broadcastPresenceLocked(g *group, groupID, nodeID string, online bool) {
	f := &Frame{Type: TPresence, NodeID: nodeID, Online: boolp(online), Since: g.since[nodeID]}
	b, _ := json.Marshal(f)
	for id, c := range s.conns {
		if id == nodeID || c.groupID != groupID || c.guest {
			continue
		}
		c.sendRaw(b)
	}
}

// ---------------------------------------------------------------- roster

func (s *Server) applyRoster(c *conn, f *Frame) {
	fail := func(reason string) { c.send(&Frame{Type: TRosterFail, Reason: reason}) }
	if len(f.RosterJS) == 0 {
		fail(ReasonBadRoster)
		return
	}
	r, adminSig, err := ParseRoster(f.RosterJS, f.AdminSig)
	if err != nil {
		s.opt.Logger.Printf("roster rejected: %v", err)
		fail(ReasonBadRoster)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	g := s.groups[r.GroupID]
	var held *Roster
	if g != nil {
		held = g.roster
	}
	if err := VerifyRoster(r, adminSig, held); err != nil {
		s.opt.Logger.Printf("roster %s v%d rejected: %v", r.GroupID, r.Version, err)
		fail(ReasonBadSignature)
		return
	}
	if held != nil && r.Version < held.Version {
		fail(ReasonStaleVersion)
		return
	}
	if held != nil && r.Version == held.Version {
		c.send(&Frame{Type: TRosterOK, GroupID: r.GroupID, Version: r.Version})
		return
	}
	if g == nil {
		g = &group{since: map[string]int64{}, invites: map[string]invite{}}
		s.groups[r.GroupID] = g
	}
	g.roster = r
	c.send(&Frame{Type: TRosterOK, GroupID: r.GroupID, Version: r.Version})
	// Members no longer on the roster (removed or revoked) lose their socket and their held messages (spec 6.4).
	for id, mc := range s.conns {
		if mc.groupID != r.GroupID || mc.guest || r.IsMember(id) {
			continue
		}
		reason := ReasonRemovedByRoster
		if r.Revoked[id] {
			reason = ReasonRevoked
		}
		mc.close(websocket.StatusPolicyViolation, reason)
		s.detachLocked(mc)
		delete(s.held, id)
		delete(s.heldBytes, id)
	}
	for id := range s.held {
		if !r.IsMember(id) {
			delete(s.held, id)
			delete(s.heldBytes, id)
		}
	}
}

// ---------------------------------------------------------------- forwarding

func (s *Server) forward(c *conn, f *Frame, wire int) {
	drop := func(reason string) { c.send(&Frame{Type: TDrop, To: f.To, Reason: reason, ID: f.ID}) }
	if f.From != c.nodeID {
		drop(ReasonBadFrom)
		return
	}
	if f.To == "" || base64.StdEncoding.DecodedLen(len(f.Cipher)) > MaxCipherBytes {
		drop(ReasonTooLarge)
		return
	}
	now := s.now()
	if !c.msgs.take(1, now) || !c.bytes.take(float64(wire), now) {
		drop(ReasonRateLimited)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	g := s.groups[c.groupID]
	if g == nil {
		drop(ReasonUnknownGroup)
		return
	}
	target := s.conns[f.To]
	targetGuest := target != nil && target.guest && target.groupID == c.groupID && target.inviter == c.nodeID
	switch {
	case c.guest && f.To != c.inviter:
		drop(ReasonGuestOnly)
		return
	case !c.guest && !targetGuest && !g.roster.IsMember(f.To):
		drop(ReasonNotMember)
		return
	}
	out, _ := json.Marshal(&Frame{Type: TMsg, To: f.To, From: f.From, Cipher: f.Cipher, ID: f.ID})
	if target != nil && target.groupID == c.groupID {
		target.sendRaw(out)
		if f.ID != 0 {
			c.send(&Frame{Type: TSent, ID: f.ID})
		}
		return
	}
	if c.guest || targetGuest {
		drop(ReasonNotMember) // no store-and-forward for pairing traffic
		return
	}
	ttl := s.opt.HoldFor
	if f.TTLS > 0 && time.Duration(f.TTLS)*time.Second < ttl {
		ttl = time.Duration(f.TTLS) * time.Second
	}
	if len(s.held[f.To]) >= MaxHeldPerNode || s.heldBytes[f.To]+len(out) > MaxHeldBytesPerNode {
		drop(ReasonQueueFull)
		return
	}
	s.held[f.To] = append(s.held[f.To], &heldMsg{from: c.nodeID, frame: out, id: f.ID, expires: now.Add(ttl), size: len(out)})
	s.heldBytes[f.To] += len(out)
	if f.ID != 0 {
		c.send(&Frame{Type: TSent, ID: f.ID, Held: true})
	}
}

// Sweep drops expired held messages (telling the sender) and expired invites, closing their guests.
func (s *Server) Sweep() {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now()
	for to, q := range s.held {
		kept := q[:0]
		for _, h := range q {
			if now.Before(h.expires) {
				kept = append(kept, h)
				continue
			}
			s.heldBytes[to] -= h.size
			if sender := s.conns[h.from]; sender != nil {
				sender.send(&Frame{Type: TDrop, To: to, Reason: ReasonExpired, ID: h.id})
			}
		}
		if len(kept) == 0 {
			delete(s.held, to)
			delete(s.heldBytes, to)
		} else {
			s.held[to] = kept
		}
	}
	for gid, g := range s.groups {
		for hash, inv := range g.invites {
			if now.After(inv.expires) {
				delete(g.invites, hash)
			}
		}
		for id, c := range s.conns {
			if c.guest && c.groupID == gid {
				alive := false
				for _, inv := range g.invites {
					if inv.inviter == c.inviter {
						alive = true
						break
					}
				}
				if !alive {
					c.close(websocket.StatusPolicyViolation, ReasonExpired)
					delete(s.conns, id)
				}
			}
		}
	}
}

// ---------------------------------------------------------------- wake and invites

func (s *Server) wake(c *conn, f *Frame) {
	drop := func(reason string) { c.send(&Frame{Type: TDrop, To: f.NodeID, Reason: reason}) }
	if c.guest {
		drop(ReasonGuestOnly)
		return
	}
	s.mu.Lock()
	g := s.groups[c.groupID]
	if g == nil || !g.roster.IsMember(f.NodeID) {
		s.mu.Unlock()
		drop(ReasonNotMember)
		return
	}
	if s.conns[f.NodeID] != nil {
		s.mu.Unlock()
		return // already online; presence says so
	}
	token := s.tokens[f.NodeID]
	now := s.now()
	if last, ok := s.lastWake[f.NodeID]; ok && now.Sub(last) < WakeEvery {
		s.mu.Unlock()
		return
	}
	s.lastWake[f.NodeID] = now
	s.mu.Unlock()
	if token == "" {
		drop(ReasonNoPushToken)
		return
	}
	data := map[string]string{"type": "wake", "groupId": c.groupID, "from": c.nodeID}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := s.opt.Pusher.Push(ctx, token, data); err != nil {
			s.opt.Logger.Printf("wake %.8s: push failed: %v", f.NodeID, err)
		}
	}()
}

func (s *Server) openInvite(c *conn, f *Frame) {
	if c.guest || len(f.TokenHash) != 64 {
		c.send(&Frame{Type: TInviteFail, Reason: ReasonBadInvite})
		return
	}
	ttl := InviteTTL
	if f.TTLS > 0 && time.Duration(f.TTLS)*time.Second < ttl {
		ttl = time.Duration(f.TTLS) * time.Second
	}
	s.mu.Lock()
	if g := s.groups[c.groupID]; g != nil {
		g.invites[f.TokenHash] = invite{inviter: c.nodeID, expires: s.now().Add(ttl)}
	}
	s.mu.Unlock()
	c.send(&Frame{Type: TInviteOK, TokenHash: f.TokenHash, TTLS: int(ttl / time.Second)})
}

// ---------------------------------------------------------------- introspection (tests, /healthz)

// Online reports whether nodeId has an authenticated socket.
func (s *Server) Online(nodeID string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.conns[nodeID] != nil
}

// HeldCount is the number of messages waiting for nodeId.
func (s *Server) HeldCount(nodeID string) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.held[nodeID])
}

// Stats is a small summary for the health endpoint.
func (s *Server) Stats() map[string]int {
	s.mu.Lock()
	defer s.mu.Unlock()
	held := 0
	for _, q := range s.held {
		held += len(q)
	}
	return map[string]int{"groups": len(s.groups), "sockets": len(s.conns), "held": held}
}
