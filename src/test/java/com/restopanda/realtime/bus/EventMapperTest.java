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
    void orderLifecycleEventsHitTheFloorChannel() {
        for (String type : new String[] {
            "order.voided",
            "order.item_voided",
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
