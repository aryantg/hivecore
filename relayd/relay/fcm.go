package relay

import (
	"bytes"
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"
)

// FCMPusher sends FCM HTTP v1 data messages with a service-account access token (RS256 JWT exchanged at the
// token endpoint), standard library only. Both URLs are fields so tests point them at an httptest server.
type FCMPusher struct {
	ProjectID   string
	ClientEmail string
	PrivateKey  *rsa.PrivateKey
	TokenURL    string // default https://oauth2.googleapis.com/token
	SendURL     string // default https://fcm.googleapis.com/v1/projects/<project>/messages:send
	HTTP        *http.Client
	Now         func() time.Time

	mu          sync.Mutex
	accessToken string
	expires     time.Time
}

type serviceAccount struct {
	ProjectID   string `json:"project_id"`
	ClientEmail string `json:"client_email"`
	PrivateKey  string `json:"private_key"`
	TokenURI    string `json:"token_uri"`
}

// NewFCMPusherFromFile reads a Firebase service-account JSON file.
func NewFCMPusherFromFile(path string) (*FCMPusher, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	return NewFCMPusher(raw)
}

// NewFCMPusher builds a pusher from service-account JSON bytes.
func NewFCMPusher(raw []byte) (*FCMPusher, error) {
	var sa serviceAccount
	if err := json.Unmarshal(raw, &sa); err != nil {
		return nil, fmt.Errorf("service account json: %w", err)
	}
	if sa.ProjectID == "" || sa.ClientEmail == "" || sa.PrivateKey == "" {
		return nil, errors.New("service account json needs project_id, client_email and private_key")
	}
	block, _ := pem.Decode([]byte(sa.PrivateKey))
	if block == nil {
		return nil, errors.New("private_key is not PEM")
	}
	var key *rsa.PrivateKey
	if k, err := x509.ParsePKCS8PrivateKey(block.Bytes); err == nil {
		rk, ok := k.(*rsa.PrivateKey)
		if !ok {
			return nil, errors.New("private_key is not RSA")
		}
		key = rk
	} else if rk, err2 := x509.ParsePKCS1PrivateKey(block.Bytes); err2 == nil {
		key = rk
	} else {
		return nil, fmt.Errorf("private_key: %w", err)
	}
	p := &FCMPusher{ProjectID: sa.ProjectID, ClientEmail: sa.ClientEmail, PrivateKey: key, TokenURL: sa.TokenURI}
	if p.TokenURL == "" {
		p.TokenURL = "https://oauth2.googleapis.com/token"
	}
	p.SendURL = "https://fcm.googleapis.com/v1/projects/" + sa.ProjectID + "/messages:send"
	return p, nil
}

func (p *FCMPusher) now() time.Time {
	if p.Now != nil {
		return p.Now()
	}
	return time.Now()
}

func (p *FCMPusher) client() *http.Client {
	if p.HTTP != nil {
		return p.HTTP
	}
	return &http.Client{Timeout: 15 * time.Second}
}

func b64url(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

// token returns a cached access token or exchanges a fresh JWT for one.
func (p *FCMPusher) token(ctx context.Context) (string, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	now := p.now()
	if p.accessToken != "" && now.Before(p.expires.Add(-time.Minute)) {
		return p.accessToken, nil
	}
	header := b64url([]byte(`{"alg":"RS256","typ":"JWT"}`))
	claims, _ := json.Marshal(map[string]any{
		"iss":   p.ClientEmail,
		"scope": "https://www.googleapis.com/auth/firebase.messaging",
		"aud":   p.TokenURL,
		"iat":   now.Unix(),
		"exp":   now.Add(time.Hour).Unix(),
	})
	signing := header + "." + b64url(claims)
	h := sha256.Sum256([]byte(signing))
	sig, err := rsa.SignPKCS1v15(rand.Reader, p.PrivateKey, crypto.SHA256, h[:])
	if err != nil {
		return "", err
	}
	form := url.Values{"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"}, "assertion": {signing + "." + b64url(sig)}}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, p.TokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	res, err := p.client().Do(req)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	if res.StatusCode/100 != 2 {
		return "", fmt.Errorf("token endpoint: %s: %.200s", res.Status, body)
	}
	var tr struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int64  `json:"expires_in"`
	}
	if err := json.Unmarshal(body, &tr); err != nil || tr.AccessToken == "" {
		return "", errors.New("token endpoint: no access_token")
	}
	p.accessToken = tr.AccessToken
	p.expires = now.Add(time.Duration(tr.ExpiresIn) * time.Second)
	return p.accessToken, nil
}

// Push sends one high-priority data message.
func (p *FCMPusher) Push(ctx context.Context, token string, data map[string]string) error {
	at, err := p.token(ctx)
	if err != nil {
		return err
	}
	msg, _ := json.Marshal(map[string]any{"message": map[string]any{
		"token":   token,
		"data":    data,
		"android": map[string]any{"priority": "high", "ttl": "60s"},
	}})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, p.SendURL, bytes.NewReader(msg))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+at)
	req.Header.Set("Content-Type", "application/json")
	res, err := p.client().Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if res.StatusCode/100 != 2 {
		body, _ := io.ReadAll(io.LimitReader(res.Body, 4096))
		return fmt.Errorf("fcm send: %s: %.200s", res.Status, body)
	}
	return nil
}
