package com.restopanda.realtime.stream;

import java.util.List;

/**
 * The body of {@code POST /v1/stream/ticket}: the channels the caller wants to
 * subscribe to. Tenant is never accepted here — it is derived from the caller's
 * verified identity.
 *
 * @param channels the requested channel strings, e.g.
 *                 {@code ["ten_x:kds.station.stn_1"]}
 */
public record TicketRequest(List<String> channels) {}
