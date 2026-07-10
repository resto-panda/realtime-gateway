/**
 * RestoPanda Realtime Gateway — a stateless bus→SSE bridge.
 *
 * <p>Package layout:
 * <ul>
 *   <li>{@code config} — Spring wiring: security chains, gateway properties.</li>
 *   <li>{@code bus} — the pg-bus consumer and the {@code EventMapper} that turns
 *       a domain {@code EventEnvelope} into channel push(es).</li>
 *   <li>{@code channel} — the in-memory {@code ChannelRegistry} (channel →
 *       {@code SseEmitter}s) and channel-name helpers.</li>
 *   <li>{@code stream} — the SSE endpoint and the ticket handshake
 *       ({@code POST /v1/stream/ticket} → {@code GET /v1/stream?ticket=}).</li>
 *   <li>{@code authz} — who may subscribe to which channel (staff entitlement +
 *       location membership; guest scoped to its own session).</li>
 * </ul>
 */
package com.restopanda.realtime;
