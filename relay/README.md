# Lucky-Tool-Android relay

Self-hosted WebSocket relay for the `RELAY` tunnel provider. The phone opens an outbound WebSocket to
`wss://<this host>/device` (bearer `RELAY_DEVICE_TOKEN`); every HTTP request this server receives on any other path
is forwarded to the phone as a JSON frame and answered from the phone's reply. MCP clients therefore use
`https://<this host>/mcp` exactly as they would use the device's own URL.

## Run

```bash
RELAY_DEVICE_TOKEN='<at least 16 random characters>' node src/server.js
```

| Variable | Default | Meaning |
|---|---|---|
| `RELAY_DEVICE_TOKEN` | — (required, ≥ 16 chars) | Bearer the phone presents on `/device` |
| `PORT` | `8080` | Listen port (1–65535) |
| `REQUEST_TIMEOUT_MS` | `120000` | Wait for the phone's answer before `504` |
| `BODY_TIMEOUT_MS` | `30000` | Time to receive a request body before `408`; a stalled upload stops holding a `MAX_PENDING` slot |
| `MAX_BODY_BYTES` | `16777216` | Request body limit (`413` above) |
| `MAX_RESPONSE_BYTES` | `8388608` | Response body limit (`502` above; the phone enforces the same 8 MiB) |
| `MAX_PENDING` | `32` | Requests in flight before new ones get `503` |
| `HEARTBEAT_INTERVAL_MS` | `25000` | Ping interval towards the phone |
| `HEARTBEAT_TIMEOUT_MS` | `60000` | Silence after which the phone socket is dropped |
| `TRUST_PROXY` | `true` | Keep incoming `X-Forwarded-Host`/`X-Forwarded-Proto` (set by your TLS proxy). Set `false` when the relay is reached directly, so clients cannot spoof them |

`REQUEST_TIMEOUT_MS`, `BODY_TIMEOUT_MS` and `HEARTBEAT_INTERVAL_MS` are timer delays: values above `2147483647`,
the longest delay Node can schedule, are refused at startup.

## Container

```bash
docker build -t lucky-tool-android-relay .
docker run --rm -p 8080:8080 -e RELAY_DEVICE_TOKEN='…' -e TRUST_PROXY=false lucky-tool-android-relay
```

Put it behind a TLS-terminating proxy (Coolify "Dockerfile" build pack with base directory `relay/`, port `8080`).
Point the platform health check at `/relay/health` — it is answered by the relay itself (`200` even when no phone is
connected; the body reports `deviceConnected`). `/health` is forwarded to the phone and returns `503` when none is
connected.

## HTTP semantics

- `400` malformed request target · `408` request body not received within `BODY_TIMEOUT_MS` · `503` no phone
  connected or `MAX_PENDING` reached · `504` phone did not answer in time · `413` body too large · `502` phone
  dropped mid-request or answered with more than `MAX_RESPONSE_BYTES`
- One phone at a time: a new authenticated `/device` connection replaces the previous one (closed with code `4000`).
- Responses above 8 MiB (e.g. large `/s/` capability downloads) are not served through the relay.

## Limits

- `MAX_PENDING` is shared by every client, and the relay does not authenticate clients (the phone checks their
  bearer). A client that keeps opening uploads that never finish holds one slot per upload for up to
  `BODY_TIMEOUT_MS`, so enough of them keep every slot busy and other requests get `503`. Behind Traefik, a
  `buffering` middleware reads each body completely before forwarding it, so unfinished uploads never reach the
  relay; bodies above `maxRequestBodyBytes` get `413` from Traefik itself:

  ```text
  traefik.http.middlewares.relay-buffering.buffering.maxRequestBodyBytes=16777216
  traefik.http.routers.<relay router>.middlewares=relay-buffering
  ```

  A router takes a comma-separated middleware list: append `relay-buffering` to the one it already has.
- Values of `BODY_TIMEOUT_MS` above Node's own `requestTimeout` (300 s) have no effect, since Node answers `408`
  first; behind Traefik, values above its entry point `readTimeout` (60 s by default) need that timeout raised too.

## Test

```bash
npm ci
npm run lint
npm test
```
