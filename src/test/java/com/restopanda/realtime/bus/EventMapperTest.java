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
    void fixtureUpsertedHintsTheFloor() {
        var pushes = mapper.map(event(
                "floor.fixture_upserted",
                "ten_x",
                "loc_1",
                Map.of("fixture_id", "fix_1", "area_id", "sar_2", "label", "Bar", "shape", "rect")));
        assertThat(pushes).singleElement().satisfies(p -> {
            assertThat(p.channel().value()).isEqualTo("ten_x:floor.loc_1");
            assertThat(p.payload()).containsEntry("fixture_id", "fix_1").containsEntry("area_id", "sar_2");
        });
    }

    @Test
    void fixtureDeletedFallsBackToPayloadLocationAndOmitsAbsentArea() {
        // A delete carries no area_id; ids() drops what is absent rather than
        // emitting a null the client would have to special-case.
        var pushes = mapper.map(
                event("floor.fixture_deleted", "ten_x", null, Map.of("fixture_id", "fix_1", "location_id", "loc_9")));
        assertThat(pushes).singleElement().satisfies(p -> {
            assertThat(p.channel().value()).isEqualTo("ten_x:floor.loc_9");
            assertThat(p.payload()).containsEntry("fixture_id", "fix_1").doesNotContainKey("area_id");
        });
    }

    @Test
    void aResolvedApprovalHintsBothTheManagerQueueAndTheFloor() {
        // The server who raised the request is watching their order screen, which
        // refetches on floor hints and nothing else. A REJECTED request changes
        // nothing on the order but its approvals block, so without the floor hint
        // their screen shows it pending forever while the manager sees it closed.
        var pushes = mapper.map(event(
                "order.approval_resolved",
                "ten_x",
                "loc_1",
                Map.of("approval_id", "apr_1", "order_id", "ord_1", "line_item_id", "li_1", "status", "rejected")));
        assertThat(pushes)
                .extracting(p -> p.channel().value())
                .containsExactlyInAnyOrder("ten_x:approvals.loc_1", "ten_x:floor.loc_1");
    }

    @Test
    void araisedApprovalStaysOnTheManagerQueueOnly() {
        // Deliberately not hinted to the floor: every server refetching whenever
        // anyone raises a request is noise, and nothing on their order changed yet.
        var pushes = mapper.map(event(
                "order.approval_requested",
                "ten_x",
                "loc_1",
                Map.of("approval_id", "apr_2", "order_id", "ord_1", "kind", "item_remove")));
        assertThat(pushes).singleElement().satisfies(p -> assertThat(p.channel().value())
                .isEqualTo("ten_x:approvals.loc_1"));
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
    void sessionReadyToCloseResolvesFloorAndSession() {
        // A paid table has been fully served: the floor card flips from
        // "Paid · N pending" to "ready to close". Without this hint the badge sits
        // stale until something else happens to the table — the whole point of the
        // prompt is that nobody is watching it.
        var pushes = mapper.map(event(
                "session.ready_to_close",
                "ten_x",
                "loc_1",
                Map.of("session_id", "ses_1", "table_id", "tbl_1", "opened_by", "usr_1")));
        assertThat(pushes)
                .extracting(p -> p.channel().value())
                .containsExactlyInAnyOrder("ten_x:floor.loc_1", "ten_x:session.ses_1");
    }

    @Test
    void fulfillmentAdvancedHitsTheFloorChannel() {
        // Every plate landing ticks the paid-but-waiting badge down, so every one
        // must refresh the map — not just the delivery that empties it.
        var pushes = mapper.map(event(
                "order.fulfillment_advanced",
                "ten_x",
                "loc_1",
                Map.of("order_id", "ord_1", "line_item_id", "li_1", "to_state", "delivered")));
        assertThat(pushes)
                .extracting(p -> p.channel().value())
                .containsExactly("ten_x:floor.loc_1");
    }

    @Test
    void orderLifecycleEventsHitTheFloorChannel() {
        for (String type : new String[] {
            "order.voided",
            "order.item_voided",
            "order.item_comped",
            "order.item_refired",
            "order.item_recalled",
            "order.kitchen_status_changed",
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
    void paymentOutcomesHitTheFloorChannelWithIdsOnly() {
        // Paid / failed states must show on staff order screens live (the
        // PaySheet auto-closes before the outcome lands). Ids only — amounts and
        // failure reasons never ride a hint every floor:read staffer can see.
        for (String type : new String[] {"payment.captured", "payment.failed"}) {
            var pushes = mapper.map(event(
                    type,
                    "ten_x",
                    "loc_1",
                    Map.of("order_id", "ord_1", "payment_id", "pay_1", "amount", 1234, "reason", "declined")));
            assertThat(pushes).singleElement().satisfies(p -> {
                assertThat(p.channel().value()).isEqualTo("ten_x:floor.loc_1");
                assertThat(p.hint()).isTrue();
                assertThat(p.payload())
                        .containsEntry("order_id", "ord_1")
                        .containsEntry("payment_id", "pay_1")
                        .doesNotContainKeys("amount", "reason");
            });
        }
    }

    @Test
    void paymentOutcomeWithoutALocationResolvesNoChannels() {
        // No location on the envelope or payload → nowhere to route; never throw.
        var pushes = mapper.map(event("payment.captured", "ten_x", null, Map.of("order_id", "ord_1")));
        assertThat(pushes).isEmpty();
    }

    @Test
    void kitchenStatusChangedCarriesTheNewStatusOnTheFloorHint() {
        // The delivered→served roll-up: floor/order screens refetch, and the new
        // status rides along so a client can update the pill straight off the hint.
        var pushes = mapper.map(
                event("order.kitchen_status_changed", "ten_x", "loc_1", Map.of("order_id", "ord_1", "kitchen_status", "served")));
        assertThat(pushes).singleElement().satisfies(p -> {
            assertThat(p.channel().value()).isEqualTo("ten_x:floor.loc_1");
            assertThat(p.hint()).isTrue();
            assertThat(p.payload())
                    .containsEntry("type", "order.kitchen_status_changed")
                    .containsEntry("order_id", "ord_1")
                    .containsEntry("kitchen_status", "served");
        });
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

        // Two pushes now: the manager queue keeps the full adjudication detail, and
        // the floor gets an ids-only nudge so the server who raised it sees the
        // outcome. The approvals payload below is the contract that matters here.
        assertThat(pushes)
                .filteredOn(p -> p.channel().value().equals("ten_x:approvals.loc_1"))
                .singleElement()
                .satisfies(p -> assertThat(p.payload())
                        .containsEntry("status", "approved")
                        .containsEntry("resolved_by", "usr_manager")
                        .containsEntry("note", "ok"));
        // The floor hint stays ids-only: that channel is visible to every floor:read
        // staffer, so the manager's note must not ride along on it.
        assertThat(pushes)
                .filteredOn(p -> p.channel().value().equals("ten_x:floor.loc_1"))
                .singleElement()
                .satisfies(p -> assertThat(p.payload()).doesNotContainKey("note").doesNotContainKey("resolved_by"));
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
    void transferredHintsTheFloorBothSeatingsAndTheOwningServer() {
        // A party moved from table 4 to table 12. ONE floor hint repaints BOTH
        // tables — the floor channel is location-scoped and the floor screen
        // refreshes wholesale on any hint — and both seatings get it so a guest
        // device bound to the old one stops rendering a check that left.
        var pushes = mapper.map(event(
                "order.transferred",
                "ten_x",
                "loc_1",
                Map.of(
                        "order_id", "ord_1",
                        "from_table_label", "4",
                        "to_table_label", "12",
                        "from_session_id", "dsn_FROM",
                        "to_session_id", "dsn_TO",
                        "server_id", "usr_server")));

        assertThat(pushes).hasSize(4);
        assertThat(pushes)
                .extracting(p -> p.channel().value())
                .containsExactly(
                        "ten_x:floor.loc_1", "ten_x:session.dsn_FROM", "ten_x:session.dsn_TO", "ten_x:user.usr_server");
        assertThat(pushes).extracting(p -> p.channel().value()).doesNotHaveDuplicates();
        assertThat(pushes).allSatisfy(p -> {
            assertThat(p.hint()).isTrue();
            assertThat(p.payload())
                    .containsEntry("type", "order.transferred")
                    .containsEntry("order_id", "ord_1")
                    .containsEntry("from_table_label", "4")
                    .containsEntry("to_table_label", "12")
                    .containsEntry("from_session_id", "dsn_FROM")
                    .containsEntry("to_session_id", "dsn_TO")
                    .containsEntry("server_id", "usr_server");
        });
    }

    @Test
    void transferredCarriesNoMoneyOnAnyChannel() {
        // The floor channel is visible to every floor:read staffer at the
        // location — the payment.captured rule. A transfer may run beside a live
        // tender, so nothing about what the check owes rides the hint.
        var pushes = mapper.map(event(
                "order.transferred",
                "ten_x",
                "loc_1",
                Map.of(
                        "order_id", "ord_1",
                        "to_table_label", "12",
                        "to_session_id", "dsn_TO",
                        "server_id", "usr_server",
                        "amount", 4200L,
                        "total", 9900L,
                        "tip_total", 800L)));

        assertThat(pushes).isNotEmpty();
        assertThat(pushes).allSatisfy(p ->
                assertThat(p.payload()).doesNotContainKeys("amount", "total", "tip_total"));
    }

    @Test
    void aSeatlessBarTabBeingSeatedHasNoSourceSeatingToHint() {
        // from_session_id is null for a bar tab: it never had a seating. Three
        // pushes, not four, and no null-keyed session channel.
        var pushes = mapper.map(event(
                "order.transferred",
                "ten_x",
                "loc_1",
                Map.of(
                        "order_id", "ord_1",
                        "to_table_label", "12",
                        "to_session_id", "dsn_TO",
                        "server_id", "usr_server")));

        assertThat(pushes).hasSize(3);
        assertThat(pushes)
                .extracting(p -> p.channel().value())
                .containsExactly("ten_x:floor.loc_1", "ten_x:session.dsn_TO", "ten_x:user.usr_server");
    }

    @Test
    void aMoveWithinOneSeatingHintsThatSessionOnlyOnce() {
        // Two checks joining at one table share a seating, so from == to. The
        // session must not be pushed twice — the same guard order.server_reassigned
        // uses for old == new.
        var pushes = mapper.map(event(
                "order.transferred",
                "ten_x",
                "loc_1",
                Map.of(
                        "order_id", "ord_1",
                        "from_table_label", "12",
                        "to_table_label", "12",
                        "from_session_id", "dsn_SAME",
                        "to_session_id", "dsn_SAME",
                        "server_id", "usr_server")));

        assertThat(pushes).hasSize(3);
        assertThat(pushes)
                .extracting(p -> p.channel().value())
                .containsExactly("ten_x:floor.loc_1", "ten_x:session.dsn_SAME", "ten_x:user.usr_server");
    }

    @Test
    void anUnclaimedCheckSkipsTheServerHint() {
        // A bar tab opened on a shared terminal has no server_id; one fewer push
        // and no user.null channel.
        var pushes = mapper.map(event(
                "order.transferred",
                "ten_x",
                "loc_1",
                Map.of(
                        "order_id", "ord_1",
                        "from_session_id", "dsn_FROM",
                        "to_session_id", "dsn_TO")));

        assertThat(pushes).hasSize(3);
        assertThat(pushes)
                .extracting(p -> p.channel().family())
                .containsExactly(ChannelFamily.FLOOR, ChannelFamily.SESSION, ChannelFamily.SESSION);
    }

    @Test
    void transferredWithNoResolvableLocationStillReachesTheSeatings() {
        // No location on the envelope or in the payload: the floor hint is
        // unroutable, but the seatings and the server are addressed by id and
        // must still get it. Never throw over a missing routing key.
        var pushes = mapper.map(event(
                "order.transferred",
                "ten_x",
                null,
                Map.of(
                        "order_id", "ord_1",
                        "from_session_id", "dsn_FROM",
                        "to_session_id", "dsn_TO",
                        "server_id", "usr_server")));

        assertThat(pushes)
                .extracting(p -> p.channel().value())
                .containsExactly("ten_x:session.dsn_FROM", "ten_x:session.dsn_TO", "ten_x:user.usr_server");
    }

    @Test
    void transferredFallsBackToThePayloadLocation() {
        var pushes = mapper.map(event(
                "order.transferred",
                "ten_x",
                null,
                Map.of("order_id", "ord_1", "location_id", "loc_9", "to_table_label", "12")));

        assertThat(pushes).singleElement().satisfies(p -> {
            assertThat(p.channel().value()).isEqualTo("ten_x:floor.loc_9");
            assertThat(p.payload()).containsEntry("to_table_label", "12").doesNotContainKey("location_id");
        });
    }

    @Test
    void orderOpenedNowHintsTheFloor() {
        // The regression that proves the second half landed. order.opened was
        // absent from the allowlist since the gateway was written, so a new tab —
        // bar, takeaway or dine-in — pushed nothing to any second device and the
        // floor sat stale until an unrelated later event happened to hint it.
        var pushes = mapper.map(event(
                "order.opened",
                "ten_x",
                "loc_1",
                Map.of("order_id", "ord_1", "table_label", "12", "session_id", "dsn_1", "channel", "bar_tab")));

        assertThat(pushes).singleElement().satisfies(p -> {
            assertThat(p.channel().value()).isEqualTo("ten_x:floor.loc_1");
            assertThat(p.channel().family()).isEqualTo(ChannelFamily.FLOOR);
            assertThat(p.hint()).isTrue();
            assertThat(p.payload())
                    .containsEntry("type", "order.opened")
                    .containsEntry("order_id", "ord_1");
        });
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
    void marketplaceOrderReceivedHitsTheLocationMarketplaceBoard() {
        var pushes = mapper.map(event(
                "marketplace.order_received",
                "ten_x",
                "loc_1",
                Map.of(
                        "external_order_id", "DD-4821",
                        "provider", "doordash",
                        "connection_id", "mkc_1",
                        "location_id", "loc_1",
                        "status", "received")));

        assertThat(pushes).singleElement().satisfies(p -> {
            assertThat(p.channel().value()).isEqualTo("ten_x:marketplace.loc_1");
            assertThat(p.channel().family()).isEqualTo(ChannelFamily.MARKETPLACE);
            assertThat(p.hint()).isTrue();
            assertThat(p.payload())
                    .containsEntry("type", "marketplace.order_received")
                    .containsEntry("external_order_id", "DD-4821")
                    .containsEntry("provider", "doordash")
                    .doesNotContainKey("status");
        });
    }

    @Test
    void marketplaceInjectedHintOmitsTheMidTransactionStatus() {
        // Auto-accept: receiveAndInject publishes order_injected with
        // status="injected" and then, in the same transaction, commits the row as
        // accepted without publishing anything else. Forwarding that status would
        // leave the board on "awaiting accept" for an accepted order, with no
        // corrective event — so the hint carries ids only and the client refetches.
        var pushes = mapper.map(event(
                "marketplace.order_injected",
                "ten_x",
                "loc_1",
                Map.of(
                        "external_order_id", "DD-4821",
                        "provider", "doordash",
                        "connection_id", "mkc_1",
                        "location_id", "loc_1",
                        "order_id", "ord_1",
                        "status", "injected")));

        assertThat(pushes).singleElement().satisfies(p -> {
            assertThat(p.channel().value()).isEqualTo("ten_x:marketplace.loc_1");
            assertThat(p.payload())
                    .containsEntry("type", "marketplace.order_injected")
                    .containsEntry("external_order_id", "DD-4821")
                    .containsEntry("order_id", "ord_1")
                    .doesNotContainKey("status");
        });
    }

    @Test
    void marketplaceAcceptUsesEnvelopeLocationWhenPayloadHasNone() {
        // ExternalOrderService.statusData() omits location_id on accept/reject —
        // the envelope is the only routing key those two carry.
        var pushes = mapper.map(event(
                "marketplace.order_accepted",
                "ten_x",
                "loc_1",
                Map.of(
                        "external_order_id", "DD-4821",
                        "provider", "doordash",
                        "connection_id", "mkc_1",
                        "order_id", "ord_1",
                        "status", "accepted")));

        assertThat(pushes).singleElement().satisfies(p -> {
            assertThat(p.channel().value()).isEqualTo("ten_x:marketplace.loc_1");
            assertThat(p.payload()).containsEntry("order_id", "ord_1").doesNotContainKey("status");
        });
    }

    @Test
    void marketplaceCancelledCarriesTheReason() {
        var pushes = mapper.map(event(
                "marketplace.order_cancelled",
                "ten_x",
                "loc_1",
                Map.of(
                        "external_order_id", "DD-4821",
                        "provider", "doordash",
                        "order_id", "ord_1",
                        "status", "cancelled",
                        "reason", "customer_cancelled")));

        assertThat(pushes).singleElement().satisfies(p -> {
            assertThat(p.channel().value()).isEqualTo("ten_x:marketplace.loc_1");
            assertThat(p.payload())
                    .containsEntry("reason", "customer_cancelled")
                    .doesNotContainKey("status");
        });
    }

    @Test
    void marketplaceOrderWithNoLocationAnywhereResolvesNoChannels() {
        assertThat(mapper.map(event(
                        "marketplace.order_rejected",
                        "ten_x",
                        null,
                        Map.of("external_order_id", "DD-4821", "provider", "doordash", "status", "rejected"))))
                .isEmpty();
    }

    @Test
    void deliveryEtaUpdatedHitsTheMarketplaceBoardWithoutCourierName() {
        // The driver's name (and phone) must never ride a hint every
        // marketplace:read staffer at the location receives — only the ETA the
        // board counts down from.
        var pushes = mapper.map(event(
                "order.delivery_eta_updated",
                "ten_x",
                "loc_1",
                Map.of(
                        "order_id", "ord_1",
                        "provider", "doordash",
                        "courier_name", "Jamie R.",
                        "courier_eta", "2026-08-03T18:42:00Z")));

        assertThat(pushes).singleElement().satisfies(p -> {
            assertThat(p.channel().value()).isEqualTo("ten_x:marketplace.loc_1");
            assertThat(p.channel().family()).isEqualTo(ChannelFamily.MARKETPLACE);
            assertThat(p.hint()).isTrue();
            assertThat(p.payload())
                    .containsEntry("order_id", "ord_1")
                    .containsEntry("provider", "doordash")
                    .containsEntry("courier_eta", "2026-08-03T18:42:00Z")
                    .doesNotContainKeys("courier_name", "courier_phone");
        });
    }

    @Test
    void marketplaceTenantAndLocationAlwaysComeFromTheEnvelope() {
        // The inbound provider webhook body is attacker-influenced and persists on
        // the ledger row, so neither a payload-named tenant nor a payload-named
        // location may address someone else's board.
        var pushes = mapper.map(event(
                "marketplace.order_injected",
                "ten_real",
                "loc_1",
                Map.of(
                        "external_order_id", "DD-4821",
                        "tenant_id", "ten_attacker",
                        "location_id", "loc_attacker",
                        "status", "injected")));

        assertThat(pushes).singleElement().satisfies(p -> {
            assertThat(p.channel().tenantId()).isEqualTo("ten_real");
            assertThat(p.channel().value()).isEqualTo("ten_real:marketplace.loc_1");
        });
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
