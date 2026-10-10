# Deploying relayd

One small VPS (or a Fly.io machine) runs the relay and coturn; Caddy terminates TLS (spec 20.1). The relay keeps no
files: rosters, the hold queue, push tokens and open invites live in memory, and the admin phone re-uploads the roster
on reconnect.

## Build and run

```sh
docker build -t relayd .
docker run -d --name relayd --restart unless-stopped -p 127.0.0.1:8080:8080 \
  -e RELAY_TURN_SECRET="$(cat /etc/coturn/secret)" \
  -e RELAY_TURN_URLS="turn:relay.example.net:3478?transport=udp,turns:relay.example.net:5349?transport=tcp" \
  -e RELAY_FCM_CREDENTIALS=/run/secrets/fcm.json -v /etc/relayd/fcm.json:/run/secrets/fcm.json:ro \
  relayd
```

| Variable | Meaning | Default |
| --- | --- | --- |
| `RELAY_LISTEN` | Listen address | `:8080` |
| `RELAY_FCM_CREDENTIALS` | Path to a Firebase service-account JSON; unset = wake is logged only | unset |
| `RELAY_TURN_SECRET` | coturn `static-auth-secret`; unset = no TURN credentials in `auth_ok` | unset |
| `RELAY_TURN_URLS` | Comma-separated TURN URLs handed to phones | — |
| `RELAY_TURN_TTL_S` | TURN credential lifetime | `86400` |

`GET /healthz` returns `{"groups":n,"sockets":n,"held":n}`.

## Caddy (TLS)

```
relay.example.net {
    reverse_proxy /ws 127.0.0.1:8080
    reverse_proxy /healthz 127.0.0.1:8080
}
```

Caddy obtains and renews the certificate itself and proxies WebSockets without extra directives. Phones connect to
`wss://relay.example.net/ws`. Two relay instances behind one hostname give redundancy: no state needs sharing, a
phone that lands on the other instance simply re-authenticates and the admin phone re-uploads the roster.

## coturn

```
listening-port=3478
tls-listening-port=5349
realm=relay.example.net
use-auth-secret
static-auth-secret=<same value as RELAY_TURN_SECRET>
cert=/etc/letsencrypt/live/relay.example.net/fullchain.pem
pkey=/etc/letsencrypt/live/relay.example.net/privkey.pem
no-cli
```

The relay issues `username = <expiry>:<nodeId>` with `credential = base64(HMAC-SHA1(secret, username))` per session
(coturn's REST API scheme). Rotate the secret weekly by changing it in both places and restarting both services; the
phones receive fresh credentials at their next `auth_ok`.

## Wake pushes

Create a Firebase project, add the Android app, download a service-account key (Project settings → Service
accounts) and mount it as `RELAY_FCM_CREDENTIALS`. Phones register their device token in the `auth` frame
(`pushToken`); the relay sends a high-priority data message `{type: wake, groupId, from}` when a member asks to wake an
offline node. UnifiedPush can replace this by implementing the `relay.Pusher` interface.
