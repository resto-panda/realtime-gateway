package com.restopanda.realtime.stream;

import com.restopanda.commons.core.Ids;
import com.restopanda.realtime.channel.Channel;
import com.restopanda.realtime.config.RealtimeProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * An in-memory store of live stream tickets. Tickets are one-time (redeeming
 * removes them) and short-lived (expired on a timer), so a leaked ticket is
 * useless within seconds and cannot be replayed.
 *
 * <p>Process-local by design: a ticket minted on one gateway replica is redeemed
 * on the same replica (the browser opens the stream immediately, to the same
 * origin). When multiple replicas exist behind a load balancer, sticky routing
 * or a shared ticket store (the M6 broker window) covers cross-replica redeem.
 */
@Component
public class TicketStore {

    private final ConcurrentHashMap<String, StreamTicket> tickets = new ConcurrentHashMap<>();
    private final RealtimeProperties properties;
    private final Clock clock;

    public TicketStore(RealtimeProperties properties) {
        this(properties, Clock.systemUTC());
    }

    TicketStore(RealtimeProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Mints a ticket for the given tenant + authorized channels, valid for the
     * configured TTL.
     *
     * @param tenantId the caller's tenant
     * @param channels the authorized channels
     * @return the minted ticket
     */
    public StreamTicket mint(String tenantId, List<Channel> channels) {
        String value = Ids.newId("rtk");
        Instant expiresAt = Instant.now(clock).plus(properties.ticketTtl());
        StreamTicket ticket = new StreamTicket(value, tenantId, channels, expiresAt);
        tickets.put(value, ticket);
        return ticket;
    }

    /**
     * Redeems a ticket exactly once. Removing atomically guarantees a ticket
     * cannot be reused even under a race; an expired ticket is rejected.
     *
     * @param value the ticket string from the query param
     * @return the ticket if valid and unexpired, otherwise empty
     */
    public Optional<StreamTicket> redeem(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        StreamTicket ticket = tickets.remove(value);
        if (ticket == null || ticket.isExpired(Instant.now(clock))) {
            return Optional.empty();
        }
        return Optional.of(ticket);
    }

    /** Periodically evicts expired tickets that were never redeemed. */
    @Scheduled(fixedDelayString = "PT60S")
    public void purgeExpired() {
        Instant now = Instant.now(clock);
        tickets.values().removeIf(t -> t.isExpired(now));
    }

    /** Test helper: number of live tickets. */
    int size() {
        return tickets.size();
    }
}
