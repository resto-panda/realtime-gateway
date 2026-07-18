package com.restopanda.realtime.bus;

import static org.assertj.core.api.Assertions.assertThat;

import com.restopanda.commons.core.EventEnvelope;
import com.restopanda.realtime.channel.ChannelFamily;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the event→channel policy. Drives the real emitted-event
 * shapes (from the domain services) through the mapper and asserts the resolved
 * channels + payload shape — one case per channel family (the M1 + M4 verify).
 */
class EventMapperTest {

    private final EventMapper mapper = new EventMapper();

    private static EventEnvelope event(String type, String tenant, String location, Map<String, Object> data) {
        return EventEnvelope.of(type)
                .tenantId(tenant)
                .locationId(location)
                .data(data)
                .build();
    }

    @Test
    void ticketBumpedResolvesStationAndRunner() {
        var pushes = mapper.map(event(
                "ticket.bumped",
                "ten_x",
                "loc_1",
                Map.of("ticket_id", "tkt_1", "order_id", "ord_1", "station_id", "stn_1")));

        assertThat(pushes).hasSize(2);
        var station = pushes.get(0);
        assertThat(station.channel().value()).isEqualTo("ten_x:kds.station.stn_1");
        assertThat(station.channel().family()).isEqualTo(ChannelFamily.KDS_STATION);
        assertThat(station.hint()).isTrue();
        assertThat(station.payload())
                .containsEntry("type", "ticket.bumped")
                .containsEntry("ticket_id", "tkt_1")
                .containsEntry("station_id", "stn_1");
        assertThat(pushes.get(1).channel().value()).isEqualTo("ten_x:kds.runner.loc_1");
    }

    @Test
    void ticketWithoutStationStillHitsRunner() {
        var pushes = mapper.map(
                event("ticket.item_ready", "ten_x", "loc_1", Map.of("ticket_id", "tkt_1", "order_id", "ord_1")));
        assertThat(pushes).extracting(p -> p.channel().value()).containsExactly("ten_x:kds.runner.loc_1");
    }

    @Test
    void ticketItemReadyCarriesTableAndItemForTheAlert() {
        var pushes = mapper.map(event(
                "ticket.item_ready",
                "ten_x",
                "loc_1",
                Map.of(
                        "ticket_id", "tkt_1",
                        "station_id", "stn_1",
                        "order_id", "ord_1",
                        "table_label", "12",
                        "item_name", "Ribeye",
                        "qty", 2)));

        // Both the station and its runner board get the alert, and — unlike other
        // ticket hints — it carries the table + item so the screen can render
        // "Table 12 · Ribeye ready" and sound a cue without a refetch.
        assertThat(pushes)
                .extracting(p -> p.channel().value())
                .containsExactlyInAnyOrder("ten_x:kds.station.stn_1", "ten_x:kds.runner.loc_1");
        assertThat(pushes).allSatisfy(p -> assertThat(p.payload())
                .containsEntry("type", "ticket.item_ready")
                .containsEntry("table_label", "12")
                .containsEntry("item_name", "Ribeye")
                .containsEntry("qty", 2));
    }

    @Test
    void courseFiredHitsRunnerBoard() {
        var pushes =
                mapper.map(event("order.course_fired", "ten_x", "loc_1", Map.of("order_id", "ord_1", "course_no", 2)));
        assertThat(pushes).singleElement().satisfies(p -> assertThat(p.channel().value())
                .isEqualTo("ten_x:kds.runner.loc_1"));
    }

    @Test
    void tableStatusChangedUsesPayloadLocationWhenEnvelopeHasNone() {
        var pushes = mapper.map(event(
                "table.status_changed",
                "ten_x",
                null,
                Map.of("table_id", "tbl_1", "location_id", "loc_9", "status", "dirty")));
        assertThat(pushes).singleElement().satisfies(p -> {
            assertThat(p.channel().value()).isEqualTo("ten_x:floor.loc_9");
            assertThat(p.payload()).containsEntry("status", "dirty").containsEntry("table_id", "tbl_1");
        });
    }

    @Test
    void sessionOpenedResolvesFloorAndSession() {
        var pushes = mapper.map(event(
                "session.opened",
                "ten_x",
                "loc_1",
                Map.of("session_id", "ses_1", "table_id", "tbl_1", "guest_count", 4)));
        assertThat(pushes)
                .extracting(p -> p.channel().value())
                .containsExactlyInAnyOrder("ten_x:floor.loc_1", "ten_x:session.ses_1");
    }

    @Test
    void sessionReleasedResolvesFloorAndSession() {
        // Pre-order un-seat must refresh the floor exactly like a close.
        var pushes = mapper.map(event(
                "session.released",
                "ten_x",
                "loc_1",
                Map.of("session_id", "ses_1", "table_id", "tbl_1", "reason_code", "changed_mind")));
        assertThat(pushes)
                .extracting(p -> p.channel().value())
                .containsExactlyInAnyOrder("ten_x:floor.loc_1", "ten_x:session.ses_1");
    }

    @Test
    void orderLifecycleEventsHitTheFloorChannel() {
        for (String type : new String[] {
            "order.voided",
            "order.item_voided",
            "order.item_comped",
            "order.item_refired",
            "order.item_recalled",
            "order.force_resolved"
        }) {
            var pushes = mapper.map(event(type, "ten_x", "loc_1", Map.of("order_id", "ord_1", "line_item_id", "li_1")));
            assertThat(pushes).hasSize(1);
            assertThat(pushes.get(0).channel().value()).isEqualTo("ten_x:floor.loc_1");
            assertThat(pushes.get(0).hint()).isTrue();
            assertThat(pushes.get(0).payload()).containsEntry("order_id", "ord_1");
        }
    }

    @Test
    void approvalRequestedHitsTheLocationApprovalsChannel() {
        var pushes = mapper.map(event(
                "order.approval_requested",
                "ten_x",
                "loc_1",
                Map.of(
                        "approval_id", "apr_1",
                        "order_id", "ord_1",
                        "kind", "item_void",
                        "line_item_id", "li_1",
                        "item_name", "Ribeye",
                        "amount_minor", 4200L,
                        "reason", "guest changed mind",
                        "kitchen_started", true,
                        "table_label", "12",
                        "requested_by", "usr_server")));

        assertThat(pushes).singleElement().satisfies(p -> {
            assertThat(p.channel().value()).isEqualTo("ten_x:approvals.loc_1");
            assertThat(p.channel().family()).isEqualTo(ChannelFamily.APPROVALS);
            assertThat(p.hint()).isTrue();
            // The request fields pass through so the queue/badge renders without
            // waiting on the refetch.
            assertThat(p.payload())
                    .containsEntry("type", "order.approval_requested")
                    .containsEntry("approval_id", "apr_1")
                    .containsEntry("order_id", "ord_1")
                    .containsEntry("kind", "item_void")
                    .containsEntry("amount_minor", 4200L)
                    .containsEntry("reason", "guest changed mind")
                    .containsEntry("kitchen_started", true)
                    .containsEntry("table_label", "12")
                    .containsEntry("requested_by", "usr_server");
        });
    }

    @Test
    void approvalResolvedHitsTheLocationApprovalsChannel() {
        var pushes = mapper.map(event(
                "order.approval_resolved",
                "ten_x",
                "loc_1",
                Map.of(
                        "approval_id", "apr_1",
                        "order_id", "ord_1",
                        "status", "approved",
                        "resolved_by", "usr_manager",
                        "note", "ok")));

        assertThat(pushes).singleElement().satisfies(p -> {
            assertThat(p.channel().value()).isEqualTo("ten_x:approvals.loc_1");
            assertThat(p.payload())
                    .containsEntry("status", "approved")
                    .containsEntry("resolved_by", "usr_manager")
                    .containsEntry("note", "ok");
        });
    }

    @Test
    void approvalEventUsesPayloadLocationWhenEnvelopeHasNone() {
        var pushes = mapper.map(event(
                "order.approval_requested",
                "ten_x",
                null,
                Map.of("approval_id", "apr_1", "order_id", "ord_1", "location_id", "loc_9")));
        assertThat(pushes).singleElement().satisfies(p -> assertThat(p.channel().value())
                .isEqualTo("ten_x:approvals.loc_9"));
    }

    @Test
    void approvalEventWithNoLocationAnywhereResolvesNoChannels() {
        assertThat(mapper.map(event(
                        "order.approval_resolved",
                        "ten_x",
                        null,
                        Map.of("approval_id", "apr_1", "order_id", "ord_1", "status", "rejected"))))
                .isEmpty();
    }

    @Test
    void serverReassignedAlertsNewAndPreviousServerOnTheirOwnChannels() {
        var pushes = mapper.map(event(
                "order.server_reassigned",
                "ten_x",
                "loc_1",
                Map.of(
                        "order_id", "ord_1",
                        "table_label", "12",
                        "session_id", "ses_1",
                        "new_server_id", "usr_new",
                        "old_server_id", "usr_old")));

        // The new server (and the previous one, whose list changed) get it on their
        // own personal channels, carrying the table so the client can say whose it is.
        assertThat(pushes)
                .extracting(p -> p.channel().value())
                .containsExactlyInAnyOrder("ten_x:user.usr_new", "ten_x:user.usr_old");
        assertThat(pushes).allSatisfy(p -> assertThat(p.payload())
                .containsEntry("table_label", "12")
                .containsEntry("new_server_id", "usr_new"));
    }

    @Test
    void serverReassignedWithNoPriorServerAlertsOnlyTheNewServer() {
        var pushes = mapper.map(event(
                "order.server_reassigned",
                "ten_x",
                "loc_1",
                Map.of("order_id", "ord_1", "table_label", "5", "new_server_id", "usr_new")));

        assertThat(pushes)
                .singleElement()
                .satisfies(p -> assertThat(p.channel().value()).isEqualTo("ten_x:user.usr_new"));
    }

    @Test
    void registerUpdatedHitsTheLocationRegisterChannel() {
        var pushes = mapper.map(event(
                "register.updated",
                "ten_x",
                "loc_1",
                Map.of(
                        "location_id", "loc_1",
                        "drawer_id", "drw_1",
                        "session_id", "dse_1",
                        "kind", "cash_event",
                        "drawer_status", "open",
                        "expected_in_drawer", 12500L)));

        assertThat(pushes).singleElement().satisfies(p -> {
            assertThat(p.channel().value()).isEqualTo("ten_x:register.loc_1");
            assertThat(p.channel().family()).isEqualTo(ChannelFamily.REGISTER);
            assertThat(p.hint()).isTrue();
            assertThat(p.payload())
                    .containsEntry("type", "register.updated")
                    .containsEntry("drawer_id", "drw_1")
                    .containsEntry("kind", "cash_event")
                    .containsEntry("drawer_status", "open")
                    .containsEntry("expected_in_drawer", 12500L);
        });
    }

    @Test
    void registerUpdatedReadsPayloadLocationFirstThenEnvelopeFallback() {
        // data location_id wins over the envelope's...
        var payloadWins = mapper.map(event(
                "register.updated",
                "ten_x",
                "loc_env",
                Map.of("location_id", "loc_data", "drawer_id", "drw_1", "kind", "session_opened")));
        assertThat(payloadWins).singleElement().satisfies(p -> assertThat(p.channel().value())
                .isEqualTo("ten_x:register.loc_data"));

        // ...and the envelope location is the fallback when data has none.
        var envelopeFallback = mapper.map(
                event("register.updated", "ten_x", "loc_env", Map.of("drawer_id", "drw_1", "kind", "session_closed")));
        assertThat(envelopeFallback).singleElement().satisfies(p -> assertThat(p.channel().value())
                .isEqualTo("ten_x:register.loc_env"));
    }

    @Test
    void registerUpdatedWithNoLocationAnywhereResolvesNoChannels() {
        assertThat(mapper.map(event("register.updated", "ten_x", null, Map.of("drawer_id", "drw_1"))))
                .isEmpty();
    }

    @Test
    void threadMessageCarriesBodyNotHint() {
        var pushes = mapper.map(event(
                "message.sent",
                "ten_x",
                "loc_1",
                Map.of(
                        "thread_id",
                        "thr_1",
                        "message_id",
                        "msg_1",
                        "sender_role",
                        "staff",
                        "body",
                        "table 4 allergy: nuts")));
        assertThat(pushes).singleElement().satisfies(p -> {
            assertThat(p.channel().value()).isEqualTo("ten_x:thread.thr_1");
            assertThat(p.hint()).isFalse();
            assertThat(p.payload())
                    .containsEntry("body", "table 4 allergy: nuts")
                    .containsEntry("message_id", "msg_1");
        });
    }

    @Test
    void unmappedTypeResolvesNoChannels() {
        assertThat(mapper.map(event("order.completed", "ten_x", "loc_1", Map.of("order_id", "ord_1"))))
                .isEmpty();
    }

    @Test
    void tenantAlwaysComesFromEnvelopeNeverPayload() {
        // A payload attempting to name a different tenant must not leak across.
        var pushes = mapper.map(event(
                "session.opened", "ten_real", "loc_1", Map.of("session_id", "ses_1", "tenant_id", "ten_attacker")));
        assertThat(pushes).allSatisfy(p -> assertThat(p.channel().tenantId()).isEqualTo("ten_real"));
    }
}
