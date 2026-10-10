package relay

import (
	"context"
	"log"
)

// Pusher sends the wake push (spec 5.5): a high-priority data message to one device token. The relay sees only
// the node id and the token; the payload names the group and the waking member so the app can reconnect and
// re-announce its profile.
type Pusher interface {
	Push(ctx context.Context, token string, data map[string]string) error
}

// NoopPusher logs and drops (the default without FCM credentials).
type NoopPusher struct{ Logger *log.Logger }

func (n NoopPusher) Push(_ context.Context, token string, data map[string]string) error {
	if n.Logger != nil {
		n.Logger.Printf("wake: no pusher configured; would push %v to token %.8s…", data, token)
	}
	return nil
}
