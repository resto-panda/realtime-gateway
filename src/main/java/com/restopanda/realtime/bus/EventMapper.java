package com.restopanda.realtime.bus;

import com.restopanda.commons.core.EventEnvelope;
import com.restopanda.realtime.channel.Channel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Maps a domain {@link EventEnvelope} to zero or more {@link ChannelPush}es.
 *
 * <p>This is the whole "which screens care about this event" policy, in one
 * place. Most services need no change: the gateway reads the events they already
 * emit. Routing keys come from the envelope ({@code tenant_id}, {@code location_id})
 * and a few ids inside {@code data} ({@code station_id}, {@code session_id},
 * {@code thread_id}). Everything but {@code thread.*} is a refetch hint.
 *
 * <p>Tenant is always taken from {@code envelope.tenantId()} — never from the
 * payload — so a channel can only ever address the event's own tenant.
 */
@Component
public class EventMapper {

    private static final Logger log = LoggerFactory.getLogger(EventMapper.class);

    /**
     * Resolves the channel push(es) for an event. Returns an empty list for
     * event types the gateway does not fan out, or when a required routing id is
     * missing (logged at debug — a missing id is a data gap, not an error).
     *
     * @param e the delivered event
     * @return the pushes to fan out (possibly empty)
     */
    public List<ChannelPush> map(EventEnvelope e) {
        String tenant = e.tenantId();
        String location = e.locationId();
        Map<String, Object> data = e.data();
        List<ChannelPush> pushes = new ArrayList<>();

        switch (e.type()) {
            // ---- KDS ticket lifecycle → the affected station + its runner board.
            case EventTypes.TICKET_ITEM_STARTED, EventTypes.TICKET_BUMPED, EventTypes.TICKET_DELAY_NOTED -> {
                String stationId = str(data, "station_id");
                if (stationId != null) {
                    pushes.add(hint(
                            Channel.kdsStation(tenant, stationId),
                            e,
                            ids(data, "ticket_id", "station_id", "order_id")));
                }
                if (location != null) {
                    pushes.add(hint(
                            Channel.kdsRunner(tenant, location), e, ids(data, "ticket_id", "station_id", "order_id")));
                }
            }

            // ---- "Food up" alert: unlike the other ticket hints, carry the table
            //      label + item name so the station/runner screen can render a
            //      labeled, audible cue ("Table 12 · Ribeye ready") directly. Still a
            //      hint (the board also re-pulls for full state); the extra fields
            //      just let the client alert immediately without waiting on the fetch.
            case EventTypes.TICKET_ITEM_READY -> {
                Map<String, Object> ready =
                        ids(data, "ticket_id", "station_id", "order_id", "table_label", "item_name", "qty");
                String stationId = str(data, "station_id");
                if (stationId != null) {
                    pushes.add(hint(Channel.kdsStation(tenant, stationId), e, ready));
                }
                if (location != null) {
                    pushes.add(hint(Channel.kdsRunner(tenant, location), e, ready));
                }
            }

            // ---- Fine-grained KDS realtime nudges: a station queue changed (any
            //      ticket mutation, incl. recall/override) or a runner board changed.
            case EventTypes.KDS_STATION_UPDATED -> {
                String stationId = str(data, "station_id");
                if (stationId != null) {
                    pushes.add(hint(Channel.kdsStation(tenant, stationId), e, ids(data, "ticket_id", "station_id")));
                }
            }
            case EventTypes.KDS_RUNNER_UPDATED -> {
                String loc = location != null ? location : str(data, "location_id");
                if (loc != null) {
                    pushes.add(hint(Channel.kdsRunner(tenant, loc), e, ids(data, "ticket_id", "station_id")));
                }
            }

            // ---- A fired course changes the whole board for that location. There
            //      is no station_id on this event (KDS derives station routing), so
            //      it lands on the location's runner board as a refetch hint.
            case EventTypes.ORDER_COURSE_FIRED -> {
                if (location != null) {
                    pushes.add(hint(Channel.kdsRunner(tenant, location), e, ids(data, "order_id", "course_no")));
                }
            }

            // ---- Order lifecycle staff order screens must reflect live (an ops
            //      Support void/refire, another device's edit). No dedicated
            //      order channel exists, so they land on the location's floor
            //      channel as refetch hints — staff order screens subscribe to
            //      floor (they already hold floor:read) and re-pull the order.
            case EventTypes.ORDER_VOIDED,
                    EventTypes.ORDER_ITEM_VOIDED,
                    EventTypes.ORDER_ITEM_REFIRED,
                    EventTypes.ORDER_ITEM_RECALLED,
                    EventTypes.ORDER_FORCE_RESOLVED -> {
                String loc = location != null ? location : str(data, "location_id");
                if (loc != null) {
                    pushes.add(hint(Channel.floor(tenant, loc), e, ids(data, "order_id", "line_item_id")));
                }
            }

            // ---- Table status is owned/emitted by platform; location_id lives in
            //      the payload (this event may carry no envelope location).
            case EventTypes.TABLE_STATUS_CHANGED -> {
                String loc = location != null ? location : str(data, "location_id");
                if (loc != null) {
                    pushes.add(hint(Channel.floor(tenant, loc), e, ids(data, "table_id", "status")));
                }
            }

            // ---- Session lifecycle → the floor map for the location and the
            //      session's own channel.
            case EventTypes.SESSION_OPENED, EventTypes.SESSION_CHECK_REQUESTED, EventTypes.SESSION_CLOSED -> {
                String sessionId = str(data, "session_id");
                if (location != null) {
                    pushes.add(hint(Channel.floor(tenant, location), e, ids(data, "session_id", "table_id")));
                }
                if (sessionId != null) {
                    pushes.add(hint(Channel.session(tenant, sessionId), e, ids(data, "session_id", "table_id")));
                }
            }

            // ---- Chat: the only family that carries a body, so the client
            //      renders directly without a refetch.
            case EventTypes.MESSAGE_SENT, EventTypes.THREAD_MESSAGE -> {
                String threadId = str(data, "thread_id");
                if (threadId != null) {
                    pushes.add(body(Channel.thread(tenant, threadId), e, threadBody(data)));
                }
            }

            default -> {
                // Not a fanned-out type — ignore quietly.
            }
        }

        if (pushes.isEmpty()) {
            log.trace("event {} ({}) mapped to no channels", e.eventId(), e.type());
        }
        return pushes;
    }

    private static ChannelPush hint(Channel channel, EventEnvelope e, Map<String, Object> ids) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", e.type());
        payload.put("channel", channel.value());
        payload.put("hint", true);
        payload.putAll(ids);
        return new ChannelPush(channel, e.type(), e.eventId(), true, payload);
    }

    private static ChannelPush body(Channel channel, EventEnvelope e, Map<String, Object> content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", e.type());
        payload.put("channel", channel.value());
        payload.put("hint", false);
        payload.putAll(content);
        return new ChannelPush(channel, e.type(), e.eventId(), false, payload);
    }

    /** The chat fields the client renders directly (never a hint). */
    private static Map<String, Object> threadBody(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        copy(data, body, "thread_id", "message_id", "sender_ref", "sender_role", "locale", "body");
        return body;
    }

    private static Map<String, Object> ids(Map<String, Object> data, String... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        copy(data, out, keys);
        return out;
    }

    private static void copy(Map<String, Object> from, Map<String, Object> to, String... keys) {
        for (String key : keys) {
            Object v = from.get(key);
            if (v != null) {
                to.put(key, v);
            }
        }
    }

    private static String str(Map<String, Object> data, String key) {
        Object v = data.get(key);
        return v == null ? null : v.toString();
    }
}
