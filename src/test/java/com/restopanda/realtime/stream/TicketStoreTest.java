package com.restopanda.realtime.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.restopanda.realtime.channel.Channel;
import com.restopanda.realtime.config.RealtimeProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * M3 ticket verify: a ticket redeems exactly once (reuse rejected) and only
 * within its TTL (expiry rejected).
 */
class TicketStoreTest {

    /** A clock whose "now" the test advances. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-07-10T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private static final List<Channel> CHANNELS = List.of(Channel.kdsStation("ten_A", "stn_1"));

    private TicketStore store(MutableClock clock) {
        return new TicketStore(new RealtimeProperties(Duration.ofSeconds(30), null, null, null), clock);
    }

    @Test
    void ticketRedeemsExactlyOnce() {
        MutableClock clock = new MutableClock();
        TicketStore store = store(clock);
        StreamTicket ticket = store.mint("ten_A", CHANNELS);

        assertThat(store.redeem(ticket.value())).isPresent();
        // Second redeem of the same ticket must fail — one-time.
        assertThat(store.redeem(ticket.value())).isEmpty();
    }

    @Test
    void expiredTicketIsRejected() {
        MutableClock clock = new MutableClock();
        TicketStore store = store(clock);
        StreamTicket ticket = store.mint("ten_A", CHANNELS);

        clock.advance(Duration.ofSeconds(31)); // past the 30s TTL
        assertThat(store.redeem(ticket.value())).isEmpty();
    }

    @Test
    void unknownTicketIsRejected() {
        assertThat(store(new MutableClock()).redeem("rtk_nope")).isEmpty();
        assertThat(store(new MutableClock()).redeem(null)).isEmpty();
    }

    @Test
    void purgeEvictsExpiredTickets() {
        MutableClock clock = new MutableClock();
        TicketStore store = store(clock);
        store.mint("ten_A", CHANNELS);
        assertThat(store.size()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(31));
        store.purgeExpired();
        assertThat(store.size()).isZero();
    }
}
