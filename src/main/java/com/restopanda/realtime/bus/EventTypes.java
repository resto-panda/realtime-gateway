package com.restopanda.realtime.bus;

/**
 * The dotted event-type strings the gateway maps to channels. These mirror the
 * constants the emitting services already publish to the bus
 * ({@code kds-service}, {@code order-service}, {@code service}, {@code platform},
 * {@code messaging-service}). They are duplicated here (rather than depending on
 * every domain module) because the gateway only needs the string, not the
 * emitting code — the contract is the wire type, not the Java constant.
 */
public final class EventTypes {

    private EventTypes() {}

    // --- kds-service: ticket lifecycle (envelope carries tenant_id + location_id;
    //     data carries station_id, ticket_id, order_id) ------------------------
    public static final String TICKET_ITEM_STARTED = "ticket.item_started";
    public static final String TICKET_ITEM_READY = "ticket.item_ready";
    public static final String TICKET_BUMPED = "ticket.bumped";
    public static final String TICKET_DELAY_NOTED = "ticket.delay_noted";

    // --- kds-service: fine-grained realtime nudges the gateway bridges straight to
    //     a station / runner channel (covers recall/override, which emit no coarser
    //     domain event). data carries station_id (station) / location_id (runner) --
    public static final String KDS_STATION_UPDATED = "kds.station.updated";
    public static final String KDS_RUNNER_UPDATED = "kds.runner.updated";

    // --- order-service: a course was fired to the kitchen -----------------------
    public static final String ORDER_COURSE_FIRED = "order.course_fired";

    /** Order lifecycle changes staff order screens must reflect live (hints only). */
    public static final String ORDER_VOIDED = "order.voided";

    public static final String ORDER_ITEM_VOIDED = "order.item_voided";

    public static final String ORDER_ITEM_REFIRED = "order.item_refired";

    public static final String ORDER_ITEM_RECALLED = "order.item_recalled";

    /** A manager/admin force-resolved a stuck/orphaned order — floor + order screens refetch. */
    public static final String ORDER_FORCE_RESOLVED = "order.force_resolved";

    // --- platform: table status transitions (data carries location_id, table_id,
    //     status) -----------------------------------------------------------------
    public static final String TABLE_STATUS_CHANGED = "table.status_changed";

    // --- service: dining-session lifecycle (data carries session_id, table_id) --
    public static final String SESSION_OPENED = "session.opened";
    public static final String SESSION_CHECK_REQUESTED = "session.check_requested";
    public static final String SESSION_CLOSED = "session.closed";

    // --- messaging-service: chat. `thread.message` is the ready-made relay
    //     payload (carries `channel` + `body`); `message.sent` is the domain
    //     event (also carries `body`) --------------------------------------------
    public static final String MESSAGE_SENT = "message.sent";
    public static final String THREAD_MESSAGE = "thread.message";
}
