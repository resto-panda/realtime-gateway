package com.restopanda.realtime.stream;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The response to a successful ticket mint. The client redeems {@code ticket} at
 * {@code GET /v1/stream?ticket=} within {@code expires_in} seconds; the echoed
 * {@code channels} are exactly the ones it was authorized for (a subset of what
 * it requested is never returned — authorization is all-or-nothing).
 *
 * @param ticket    the one-time ticket string
 * @param expiresIn seconds until the ticket expires
 * @param channels  the granted channels
 */
public record TicketResponse(
        @JsonProperty("ticket") String ticket,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("channels") List<String> channels) {}
