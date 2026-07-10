package com.restopanda.realtime.bus;

import com.restopanda.realtime.channel.Channel;
import java.util.Map;

/**
 * One resolved fan-out: an {@link EventTypes event} routed to a {@link Channel},
 * with the small JSON payload the browser receives as an SSE {@code data} frame.
 *
 * <p>Two shapes exist, distinguished by {@link #hint()}:
 * <ul>
 *   <li><b>hint</b> ({@code hint=true}) — the default. The payload only names
 *       what changed ({@code type} + a few ids); the client re-fetches the
 *       authoritative snapshot from REST. A dropped hint self-heals on the next
 *       snapshot, which is why polling stays a safe permanent fallback.</li>
 *   <li><b>body</b> ({@code hint=false}) — chat only. {@code thread.*} pushes
 *       carry the message body so the client can render it directly.</li>
 * </ul>
 *
 * @param channel   the channel to deliver on
 * @param eventType the originating event type (echoed to the client)
 * @param eventId   the bus {@code evt_…} id (used as the SSE event id for
 *                  best-effort {@code Last-Event-ID} resume)
 * @param hint      whether this is a refetch hint (true) or a body payload (false)
 * @param payload   the SSE {@code data} body (already PII-considered)
 */
public record ChannelPush(
        Channel channel, String eventType, String eventId, boolean hint, Map<String, Object> payload) {}
