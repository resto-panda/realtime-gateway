/**
 * The SSE surface: the {@link com.restopanda.realtime.stream.StreamController}
 * two-step handshake ({@code POST /v1/stream/ticket} then {@code GET /v1/stream?ticket=})
 * and the one-time, short-lived {@link com.restopanda.realtime.stream.TicketStore}
 * that binds a ticket to the exact channels a caller was authorized for.
 */
package com.restopanda.realtime.stream;
