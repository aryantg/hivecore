package relay

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestFCMPusherExchangesTokenAndSends(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	der, _ := x509.MarshalPKCS8PrivateKey(key)
	pemKey := string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}))

	var tokenCalls, sendCalls int
	var sentBody map[string]any
	var authHeader string
	mux := http.NewServeMux()
	mux.HandleFunc("/token", func(w http.ResponseWriter, r *http.Request) {
		tokenCalls++
		body, _ := io.ReadAll(r.Body)
		if !strings.Contains(string(body), "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer") || !strings.Contains(string(body), "assertion=") {
			t.Errorf("token form: %s", body)
		}
		_, _ = w.Write([]byte(`{"access_token":"at-1","expires_in":3600}`))
	})
	mux.HandleFunc("/send", func(w http.ResponseWriter, r *http.Request) {
		sendCalls++
		authHeader = r.Header.Get("Authorization")
		_ = json.NewDecoder(r.Body).Decode(&sentBody)
		_, _ = w.Write([]byte(`{"name":"projects/p/messages/1"}`))
	})
	ts := httptest.NewServer(mux)
	defer ts.Close()

	sa, _ := json.Marshal(map[string]string{"project_id": "p", "client_email": "svc@example.invalid", "private_key": pemKey, "token_uri": ts.URL + "/token"})
	p, err := NewFCMPusher(sa)
	if err != nil {
		t.Fatal(err)
	}
	p.SendURL = ts.URL + "/send"
	p.HTTP = ts.Client()

	for i := 0; i < 2; i++ {
		if err := p.Push(context.Background(), "device-token", map[string]string{"type": "wake", "groupId": "g1"}); err != nil {
			t.Fatal(err)
		}
	}
	if tokenCalls != 1 || sendCalls != 2 {
		t.Fatalf("token calls %d send calls %d", tokenCalls, sendCalls)
	}
	if authHeader != "Bearer at-1" {
		t.Fatalf("authorization %q", authHeader)
	}
	msg := sentBody["message"].(map[string]any)
	if msg["token"] != "device-token" || msg["data"].(map[string]any)["type"] != "wake" || msg["android"].(map[string]any)["priority"] != "high" {
		t.Fatalf("message: %+v", msg)
	}
}
