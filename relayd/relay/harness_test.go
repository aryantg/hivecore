package relay

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
)

// In-process protocol tests: a real Server behind httptest, real WebSockets over loopback, keys generated per test.

type fakeClock struct {
	mu sync.Mutex
	t  time.Time
}

func newClock() *fakeClock { return &fakeClock{t: time.Date(2026, 10, 10, 12, 0, 0, 0, time.UTC)} }

func (c *fakeClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

func (c *fakeClock) Advance(d time.Duration) {
	c.mu.Lock()
	c.t = c.t.Add(d)
	c.mu.Unlock()
}

type node struct {
	key    *ecdsa.PrivateKey
	pubB64 string
	id     string
	name   string
}

func newNode(t *testing.T, name string) *node {
	t.Helper()
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(&k.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(der)
	return &node{key: k, pubB64: base64.StdEncoding.EncodeToString(der), id: fmt.Sprintf("%x", sum[:]), name: name}
}

func (n *node) sign(t *testing.T, msg []byte) string {
	t.Helper()
	h := sha256.Sum256(msg)
	sig, err := ecdsa.SignASN1(rand.Reader, n.key, h[:])
	if err != nil {
		t.Fatal(err)
	}
	return base64.StdEncoding.EncodeToString(sig)
}

// rosterJSON builds a RosterBody signed by signer (normally the admin) with adminSig embedded.
func rosterJSON(t *testing.T, groupID string, version int64, admin *node, members []*node, revoked []string, signer *node) []byte {
	t.Helper()
	ms := make([]map[string]any, 0, len(members))
	for i, m := range members {
		ms = append(ms, map[string]any{"nodeId": m.id, "pubkey": m.pubB64, "name": m.name, "addedTs": 1_700_000_000_000 + int64(i)})
	}
	if revoked == nil {
		revoked = []string{}
	}
	body := map[string]any{"groupId": groupID, "version": version, "admin": admin.id, "members": ms, "revoked": revoked}
	raw, _ := json.Marshal(body)
	canon, err := CanonicalWithout(raw, "adminSig")
	if err != nil {
		t.Fatal(err)
	}
	body["adminSig"] = signer.sign(t, canon)
	out, _ := json.Marshal(body)
	return out
}

type harness struct {
	t     *testing.T
	srv   *Server
	hs    *httptest.Server
	clock *fakeClock
	url   string
}

func newHarness(t *testing.T, opt Options) *harness {
	t.Helper()
	clock := newClock()
	if opt.Now == nil {
		opt.Now = clock.Now
	}
	if opt.SweepEvery == 0 {
		opt.SweepEvery = -1
	}
	if opt.PingEvery == 0 {
		opt.PingEvery = time.Hour
	}
	if opt.Logger == nil {
		opt.Logger = log.New(io.Discard, "", 0)
	}
	srv := NewServer(opt)
	srv.Start()
	hs := httptest.NewServer(srv)
	t.Cleanup(func() { srv.Stop(); hs.Close() })
	return &harness{t: t, srv: srv, hs: hs, clock: clock, url: "ws" + strings.TrimPrefix(hs.URL, "http")}
}

// client reads frames on a goroutine into a channel, because coder/websocket closes the socket when a Read
// context expires; a timed wait must therefore never be a timed Read.
type client struct {
	t      *testing.T
	ws     *websocket.Conn
	frames chan []byte
	nonce  []byte
}

var errClosed = errors.New("socket closed")

func (h *harness) dial() *client {
	h.t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ws, _, err := websocket.Dial(ctx, h.url, nil)
	if err != nil {
		h.t.Fatal(err)
	}
	ws.SetReadLimit(MaxFrameBytes)
	c := &client{t: h.t, ws: ws, frames: make(chan []byte, 4096)}
	go func() {
		defer close(c.frames)
		for {
			_, data, err := ws.Read(context.Background())
			if err != nil {
				return
			}
			c.frames <- data
		}
	}()
	h.t.Cleanup(func() { _ = ws.CloseNow() })
	ch := c.expect(TChallenge)
	c.nonce, err = base64.StdEncoding.DecodeString(ch.Nonce)
	if err != nil || len(c.nonce) != NonceBytes {
		h.t.Fatalf("bad challenge nonce %q", ch.Nonce)
	}
	if ch.ServerTs == 0 {
		h.t.Fatal("challenge without serverTs")
	}
	return c
}

func (c *client) send(f *Frame) {
	c.t.Helper()
	b, _ := json.Marshal(f)
	c.sendRaw(string(b))
}

// sendRaw writes a text frame as given (for a roster frame carrying raw JSON).
func (c *client) sendRaw(s string) {
	c.t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := c.ws.Write(ctx, websocket.MessageText, []byte(s)); err != nil {
		c.t.Fatalf("write: %v", err)
	}
}

// next returns the next frame, errClosed once the socket is gone, or a timeout error.
func (c *client) next(timeout time.Duration) (*Frame, error) {
	select {
	case data, ok := <-c.frames:
		if !ok {
			return nil, errClosed
		}
		var f Frame
		if err := json.Unmarshal(data, &f); err != nil {
			return nil, err
		}
		return &f, nil
	case <-time.After(timeout):
		return nil, errors.New("timeout")
	}
}

// expect reads until a frame of the given type arrives, skipping server pings.
func (c *client) expect(typ string) *Frame { c.t.Helper(); return c.expectOneOf(typ) }

func (c *client) expectOneOf(types ...string) *Frame {
	c.t.Helper()
	for {
		f, err := c.next(5 * time.Second)
		if err != nil {
			c.t.Fatalf("waiting for %v: %v", types, err)
		}
		if f.Type == TPing {
			continue
		}
		for _, t := range types {
			if f.Type == t {
				return f
			}
		}
		c.t.Fatalf("expected one of %v, got %s (%s)", types, f.Type, describe(f))
	}
}

// none asserts that nothing but pings arrives for a short while and the socket stays open.
func (c *client) none(d time.Duration) {
	c.t.Helper()
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		f, err := c.next(time.Until(deadline))
		if err == errClosed {
			c.t.Fatal("socket closed")
		}
		if err != nil {
			return
		}
		if f.Type != TPing {
			c.t.Fatalf("unexpected frame %s (%s)", f.Type, describe(f))
		}
	}
}

// closed asserts the server closed the socket.
func (c *client) closed() {
	c.t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if _, err := c.next(time.Until(deadline)); err == errClosed {
			return
		} else if err != nil {
			break
		}
	}
	c.t.Fatal("socket still open")
}

func describe(f *Frame) string {
	b, _ := json.Marshal(f)
	return string(b)
}

func (c *client) roster(body []byte) *Frame {
	c.t.Helper()
	c.sendRaw(fmt.Sprintf(`{"type":"roster","roster":%s}`, body))
	return c.expectOneOf(TRosterOK, TRosterFail)
}

func (c *client) auth(n *node, groupID string, pushToken string) *Frame {
	c.t.Helper()
	c.send(&Frame{Type: TAuth, NodeID: n.id, GroupID: groupID, RosterVersion: 1, Sig: n.sign(c.t, AuthBytes(c.nonce)), PushToken: pushToken})
	return c.expectOneOf(TAuthOK, TAuthFail)
}

func (c *client) authOK(n *node, groupID string) *Frame {
	c.t.Helper()
	f := c.auth(n, groupID, "")
	if f.Type != TAuthOK {
		c.t.Fatalf("auth failed: %s", f.Reason)
	}
	return f
}

func (c *client) msg(from *node, to string, cipher string, id int64) {
	c.t.Helper()
	c.send(&Frame{Type: TMsg, To: to, From: from.id, Cipher: cipher, ID: id})
}

// leave closes the socket from the client side and has every other client see the offline presence.
func (c *client) leave(others ...*client) {
	c.t.Helper()
	_ = c.ws.Close(websocket.StatusNormalClosure, "bye")
	for _, o := range others {
		if p := o.expect(TPresence); p.Online == nil || *p.Online {
			c.t.Fatalf("expected offline presence, got %s", describe(p))
		}
	}
}

// group sets up a roster of the given nodes (first is admin) and returns a connected, authenticated client per node.
func (h *harness) group(groupID string, nodes ...*node) []*client {
	h.t.Helper()
	admin := h.dial()
	if f := admin.roster(rosterJSON(h.t, groupID, 1, nodes[0], nodes, nil, nodes[0])); f.Type != TRosterOK {
		h.t.Fatalf("roster upload: %s", f.Reason)
	}
	out := []*client{admin}
	admin.authOK(nodes[0], groupID)
	for _, n := range nodes[1:] {
		c := h.dial()
		c.authOK(n, groupID)
		out = append(out, c)
		// every earlier client sees this one come online
		for _, prev := range out[:len(out)-1] {
			p := prev.expect(TPresence)
			if p.NodeID != n.id || p.Online == nil || !*p.Online {
				h.t.Fatalf("expected presence online for %s, got %s", n.id, describe(p))
			}
		}
	}
	return out
}

type recordingPusher struct {
	mu    sync.Mutex
	calls []pushCall
	done  chan struct{}
}

type pushCall struct {
	token string
	data  map[string]string
}

func (r *recordingPusher) Push(_ context.Context, token string, data map[string]string) error {
	r.mu.Lock()
	r.calls = append(r.calls, pushCall{token, data})
	r.mu.Unlock()
	select {
	case r.done <- struct{}{}:
	default:
	}
	return nil
}
