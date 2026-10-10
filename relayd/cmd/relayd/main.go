// Command relayd runs the HiveCore relay (spec 5.3, 20.1). Configuration is by environment:
//
//	RELAY_LISTEN           address to listen on (default :8080); put Caddy or another TLS terminator in front
//	RELAY_FCM_CREDENTIALS  path to a Firebase service-account JSON for wake pushes (unset = log-only)
//	RELAY_TURN_SECRET      coturn static-auth-secret; unset = no TURN credentials in auth_ok
//	RELAY_TURN_URLS        comma-separated TURN URLs, e.g. turn:turn.example.net:3478?transport=udp
//	RELAY_TURN_TTL_S       credential lifetime in seconds (default 86400)
//
// Endpoints: `/ws` (the relay protocol), `/healthz` (JSON counters).
package main

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/hivecore/relayd/relay"
)

func main() {
	logger := log.New(os.Stdout, "relayd ", log.LstdFlags|log.LUTC)
	listen := envOr("RELAY_LISTEN", ":8080")

	var pusher relay.Pusher = relay.NoopPusher{Logger: logger}
	if path := os.Getenv("RELAY_FCM_CREDENTIALS"); path != "" {
		p, err := relay.NewFCMPusherFromFile(path)
		if err != nil {
			logger.Fatalf("RELAY_FCM_CREDENTIALS: %v", err)
		}
		pusher = p
		logger.Printf("wake pushes through FCM project %s", p.ProjectID)
	}

	var turn *relay.TurnIssuer
	if secret := os.Getenv("RELAY_TURN_SECRET"); secret != "" {
		ttl := 24 * time.Hour
		if s := os.Getenv("RELAY_TURN_TTL_S"); s != "" {
			n, err := strconv.Atoi(s)
			if err != nil || n <= 0 {
				logger.Fatalf("RELAY_TURN_TTL_S: not a positive integer")
			}
			ttl = time.Duration(n) * time.Second
		}
		var urls []string
		for _, u := range strings.Split(os.Getenv("RELAY_TURN_URLS"), ",") {
			if u = strings.TrimSpace(u); u != "" {
				urls = append(urls, u)
			}
		}
		if len(urls) == 0 {
			logger.Fatalf("RELAY_TURN_SECRET is set but RELAY_TURN_URLS is empty")
		}
		turn = &relay.TurnIssuer{Secret: []byte(secret), URLs: urls, TTL: ttl}
		logger.Printf("issuing TURN credentials for %v", urls)
	}

	srv := relay.NewServer(relay.Options{Pusher: pusher, Turn: turn, Logger: logger})
	srv.Start()

	mux := http.NewServeMux()
	mux.Handle("/ws", srv)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(srv.Stats())
	})
	hs := &http.Server{Addr: listen, Handler: mux, ReadHeaderTimeout: 10 * time.Second}

	go func() {
		logger.Printf("listening on %s", listen)
		if err := hs.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatalf("listen: %v", err)
		}
	}()

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig
	logger.Printf("shutting down")
	srv.Stop()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = hs.Shutdown(ctx)
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}
