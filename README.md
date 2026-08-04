# realtime-gateway

A stateless **bus → SSE bridge**. It subscribes to the platform event bus,
maps each domain event to one or more per-tenant channels, and fans it out to
browsers over **Server-Sent Events**. It holds no business state.

Realtime is **best-effort**; correctness always comes from REST. Every screen
(1) `GET`s a snapshot from the existing service endpoint, (2) opens the stream
and applies deltas, and (3) re-snapshots on reconnect. A dropped event self-heals
on the next snapshot — which is why **polling stays a permanent fallback** and no
working surface can regress.

## Why its own origin (not api-gateway)

The edge `api-gateway` proxies with a buffering JDK `HttpClient`, which breaks
SSE. The gateway runs on its own port (`:8095`) and the browser's `EventSource`
connects **directly**; the front-ends point `VITE_REALTIME_URL` at it. CORS is
the shared commons list (`RESTOPANDA_WEB_CORS_ALLOWED_ORIGINS`).

## The two-step handshake

An `EventSource` cannot send an `Authorization` header, so auth is a ticket
handshake that keeps JWTs out of URLs and logs:

```
POST /v1/stream/ticket        # staff JWT  OR  X-Guest-Token
  { "channels": ["ten_x:kds.station.stn_1", …] }
  → { "ticket": "rtk_…", "expires_in": 30, "channels": [ …granted… ] }

GET  /v1/stream?ticket=rtk_…  Accept: text/event-stream
  → text/event-stream (one-time ticket redeemed; stream opens)
```

**Tenant is always derived from the verified token, never the client.** A ticket
is one-time and short-lived, bound to the exact channels the caller was allowed
to see at mint time. Authorization is all-or-nothing: if any requested channel is
out of scope the whole mint is rejected.

### Who may subscribe

| Family                    | Staff rule                                   | Guest rule                     |
|---------------------------|----------------------------------------------|--------------------------------|
| `{t}:kds.station.{id}`    | `kds:read`                                   | ✗                              |
| `{t}:kds.runner.{loc}`    | `kds:read` + `loc` ∈ token `location_ids`    | ✗                              |
| `{t}:floor.{loc}`         | `floor:read` + `loc` ∈ token `location_ids`  | ✗                              |
| `{t}:session.{id}`        | `floor:read`                                 | only its **own** `session.{id}`|
| `{t}:user.{userId}`       | **self-only** (`userId` == token `sub`; no entitlement) | ✗                   |
| `{t}:thread.{id}`         | `messaging:read`                             | ✗                              |
| `{t}:register.{loc}`      | `payment:read` **or** `payment:drawer`, + `loc` ∈ token `location_ids` | ✗     |
| `{t}:approvals.{loc}`     | `order:manage` + `loc` ∈ token `location_ids`| ✗                              |
| `{t}:marketplace.{loc}`   | `marketplace:read` + `loc` ∈ token `location_ids` | ✗                         |

Cross-tenant, cross-location, missing-entitlement, and guest-out-of-session
subscriptions are all rejected (the RLS-equivalent for realtime). Guest tokens
are the **self-contained** device-session tokens Service & Floor mints; the
gateway only *verifies* them (shared `GUEST_SIGNING_KEY`), exactly like
messaging-service.

## Event → channel map

The gateway reads the domain events services **already** emit — most need no
change. Everything is a refetch **hint** except chat, which carries the body.

| Event                                   | Channel(s)                                             | Payload |
|-----------------------------------------|--------------------------------------------------------|---------|
| `ticket.item_started/ready/bumped`      | `{t}:kds.station.{station_id}`, `{t}:kds.runner.{loc}` | hint    |
| `ticket.delay_noted`                    | `{t}:kds.station.{station_id}`, `{t}:kds.runner.{loc}` | hint    |
| `order.course_fired`                    | `{t}:kds.runner.{loc}`                                 | hint    |
| `order.voided` / `order.item_voided` / `order.item_refired` / `order.item_recalled` / `order.force_resolved` | `{t}:floor.{loc}` | hint |
| `table.status_changed`                  | `{t}:floor.{location_id}`                              | hint    |
| `order.server_reassigned`               | `{t}:user.{new_server_id}`, `{t}:user.{old_server_id}` | hint (carries `table_label`) |
| `session.opened/check_requested/closed` | `{t}:floor.{loc}`, `{t}:session.{session_id}`          | hint    |
| `payment.captured` / `payment.failed`   | `{t}:floor.{loc}`                                      | hint (ids only) |
| `order.approval_requested/resolved`     | `{t}:approvals.{loc}`                                  | hint (carries the request fields) |
| `register.updated`                      | `{t}:register.{location_id}`                           | hint (carries drawer state) |
| `marketplace.order_received/injected/accepted/rejected/cancelled` | `{t}:marketplace.{loc}`      | hint (ids only, no `status`) |
| `order.delivery_eta_updated`            | `{t}:marketplace.{loc}`                                | hint (`courier_eta` only) |
| `message.sent` / `thread.message`       | `{t}:thread.{thread_id}`                               | **body**|

Routing keys come from the envelope (`tenant_id`, `location_id`) plus a few ids
in `data` (`station_id`, `session_id`, `thread_id`). A hint payload names what
changed (`type` + ids); the client refetches the authoritative snapshot.

> Known gap: ticket events carry no `session_id`, so `ticket.item_ready` reaches
> the station/runner boards but not a `session.{id}` channel. When KDS adds
> `session_id` to ticket payloads, add the session mapping in `EventMapper`.

### The marketplace board

`marketplace.{loc}` carries the inbound third-party delivery ledger and its
courier updates. Three things about it are deliberate:

- **The hint carries no `status`.** Marketplace publishes these from inside one
  transaction — `received` → `injected` → (auto-accept) `accepted` — so every
  event but the last one names a status the committed row has already moved past.
  On the auto-accept path `InboundOrderService.receiveAndInject` sets
  `status = accepted` and publishes *nothing* after `marketplace.order_injected`,
  so a board that rendered the hint's status would show "awaiting accept" for an
  accepted order until the next interval poll. The event type names the
  transition; the ledger status comes from the refetch the hint triggers — which
  returns `accepted` and needs no extra event.
- **`courier_name` and `courier_phone` never ride the hint** — the same rule as
  `payment.captured`. The board fans out to every `marketplace:read` staffer at
  the location, so a named driver would land in every open devtools console.
  `courier_eta` alone drives the "arriving in Xm" countdown; the name comes back
  on the refetch.
- **`location_id` resolves envelope-first**, like every other location-keyed arm
  in `EventMapper` (`register.updated` is the one that reads `data` first, and it
  falls back to the envelope). It matters here because
  `marketplace.order_accepted` / `..._rejected` carry no `location_id` in `data`
  at all — the envelope is their only routing key.

A client must gate this channel on the `marketplace:read` entitlement it already
checks: ticket minting is all-or-nothing, so appending it for a user who lacks
the entitlement breaks that user's *whole* stream, not just the board.

## How it consumes the bus

Depends on `commons-messaging`; with `RESTOPANDA_MESSAGING_BUS=pg` the poller
re-raises each foreign `bus.events` row as an in-process `EventEnvelope`, which
`BusEventConsumer` maps and dispatches. Not an `IdempotentConsumer` — a push is
best-effort and the snapshot contract self-heals duplicates, so no dedupe write
per event. Consumer watermark = `spring.application.name` (`realtime-gateway`).
The gateway owns no domain schema; the `realtime` schema hosts only the commons
authz/processed-events fragments.

## Scale-later (M6, adapter swap — not built)

`ChannelRegistry` sits behind the `ChannelDispatcher` seam. When volume demands
more than one replica, a `RealtimeBroker` SPI (Redis pub/sub or a Kafka
consumer-group) shares fan-out across replicas with no change to callers, and a
replica restart re-hydrates via client reconnect (snapshot) — losing nothing.

## Build & run

```bash
# from repo root — build the jar, then the compose stack picks it up
mvn -pl realtime-gateway -am -DskipTests install
docker compose -f docker-compose-local.yaml up -d realtime-gateway

curl -fsS http://localhost:8095/actuator/health      # {"status":"UP"}
```

Tests: `mvn -pl realtime-gateway test` (the Testcontainers `BusSubscriptionIT`
needs Docker). Config lives under `restopanda.realtime.*` (ticket TTL, heartbeat,
stream timeout, guest signing key).
