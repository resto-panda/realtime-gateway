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

    public static final String ORDER_ITEM_COMPED = "order.item_comped";

    public static final String ORDER_ITEM_REFIRED = "order.item_refired";

    public static final String ORDER_ITEM_RECALLED = "order.item_recalled";

    /** A manager/admin force-resolved a stuck/orphaned order — floor + order screens refetch. */
    public static final String ORDER_FORCE_RESOLVED = "order.force_resolved";

    /**
     * The order's roll-up {@code kitchen_status} moved ({@code cooking → ready →
     * served}) off a KDS ticket event — floor/order screens refetch so a runner's
     * delivered→served shows live instead of after a manual refresh. data carries
     * {@code order_id, kitchen_status}.
     */
    public static final String ORDER_KITCHEN_STATUS_CHANGED = "order.kitchen_status_changed";

    /**
     * A plate advanced along the fulfillment axis. Carries the session's remaining
     * plate count, which is exactly what the floor's paid-but-waiting badge counts
     * down — so every delivery must refresh the map, not just the last one.
     */
    public static final String ORDER_FULFILLMENT_ADVANCED = "order.fulfillment_advanced";

    // --- payment-service: settlement outcomes. The envelope carries location_id
    //     (payment copies it from the inbound intent request); data carries
    //     order_id + payment_id. Terminal-only since the reliability rework:
    //     payment.failed fires once per intent, so a floor hint per event is
    //     cheap. Staff order screens refetch to show paid / failed states live
    //     (the PaySheet closes ~900ms after tender — outcome visibility lives on
    //     OrderScreen, fed by exactly these hints). --------------------------------
    public static final String PAYMENT_CAPTURED = "payment.captured";
    public static final String PAYMENT_FAILED = "payment.failed";

    // --- order-service: money-off approval workflow (staff comp/discount/void
    //     over threshold → manager approval queue). data carries approval_id,
    //     order_id, kind, amount_minor, requested_by (+ status/resolved_by on
    //     resolve); location_id may live in the payload -------------------------
    public static final String ORDER_APPROVAL_REQUESTED = "order.approval_requested";
    public static final String ORDER_APPROVAL_RESOLVED = "order.approval_resolved";

    /**
     * A table/check was (re)assigned to a server — alert the new server on their
     * own {@code user.{userId}} channel ("table 12 is now yours"). data carries
     * {@code order_id, table_label, session_id, new_server_id, old_server_id}.
     */
    public static final String ORDER_SERVER_REASSIGNED = "order.server_reassigned";

    // --- platform: table status transitions (data carries location_id, table_id,
    //     status) -----------------------------------------------------------------
    public static final String TABLE_STATUS_CHANGED = "table.status_changed";

    // --- service: dining-session lifecycle (data carries session_id, table_id) --
    public static final String SESSION_OPENED = "session.opened";
    public static final String SESSION_CHECK_REQUESTED = "session.check_requested";
    public static final String SESSION_CLOSED = "session.closed";
    /** Pre-order release (un-seat) — the floor must refresh just like a close. */
    public static final String SESSION_RELEASED = "session.released";
    /**
     * A paid table has been fully served and can be closed out. The floor card flips
     * from "Paid · N pending" to "ready to close", so the map must refresh — without
     * this hint the badge would sit stale until something else happened to the table.
     */
    public static final String SESSION_READY_TO_CLOSE = "session.ready_to_close";

    // --- payment-service: drawer/register state changed (session opened/closed,
    //     cash event, cash sale). data carries location_id, drawer_id, session_id?,
    //     kind, drawer_status, expected_in_drawer -------------------------------
    public static final String REGISTER_UPDATED = "register.updated";

    // --- messaging-service: chat. `thread.message` is the ready-made relay
    //     payload (carries `channel` + `body`); `message.sent` is the domain
    //     event (also carries `body`) --------------------------------------------
    public static final String MESSAGE_SENT = "message.sent";
    public static final String THREAD_MESSAGE = "thread.message";
}
