package com.restopanda.realtime.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.restopanda.commons.core.EventEnvelope;
import com.restopanda.commons.test.PostgresTestBase;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end M1 verify: insert a real {@code ticket.bumped} envelope into
 * {@code bus.events}, and assert the pg-bus poller delivers it, the mapper
 * resolves the station channel, and the dispatcher receives the push. Proves the
 * whole subscribe→map→dispatch wiring, not just the mapper in isolation.
 */
@SpringBootTest
@Import(BusSubscriptionIT.Config.class)
class BusSubscriptionIT extends PostgresTestBase {

    /** Captures dispatched pushes; suppresses the default logging dispatcher. */
    static final class CapturingDispatcher implements ChannelDispatcher {
        final List<ChannelPush> received = new CopyOnWriteArrayList<>();

        @Override
        public void dispatch(ChannelPush push) {
            received.add(push);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        CapturingDispatcher capturingDispatcher() {
            return new CapturingDispatcher();
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    CapturingDispatcher dispatcher;

    @Test
    void ticketBumpedRowIsDeliveredAndMappedToStationChannel() {
        EventEnvelope envelope = EventEnvelope.of("ticket.bumped")
                .tenantId("ten_x")
                .locationId("loc_1")
                .data(Map.of("ticket_id", "tkt_1", "order_id", "ord_1", "station_id", "stn_1"))
                .build();

        // A foreign source_service so the poller (consumer = realtime-gateway)
        // does not filter it out as its own event.
        jdbc.update(
                "INSERT INTO bus.events (event_id, event_type, source_service, payload)"
                        + " VALUES (?, ?, ?, ?::jsonb)",
                envelope.eventId(),
                envelope.type(),
                "kds-service",
                objectMapper.writeValueAsString(envelope));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(dispatcher.received)
                .anySatisfy(p -> assertThat(p.channel().value()).isEqualTo("ten_x:kds.station.stn_1")));
    }
}
