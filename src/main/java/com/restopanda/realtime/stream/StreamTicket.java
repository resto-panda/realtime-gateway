package com.restopanda.realtime.stream;

import com.restopanda.realtime.channel.Channel;
import java.time.Instant;
import java.util.List;

/**
 * A minted, short-lived, one-time credential that lets an {@code EventSource}
 * open the stream without putting a JWT in the URL. It is bound to the tenant and
 * the exact channels the caller was allowed to see at mint time.
 *
 * @param value     the opaque ticket string (passed as {@code ?ticket=})
 * @param tenantId  the tenant the ticket is scoped to
 * @param channels  the channels the ticket grants
 * @param expiresAt when the ticket stops being redeemable
 */
public record StreamTicket(String value, String tenantId, List<Channel> channels, Instant expiresAt) {

    public StreamTicket {
        channels = List.copyOf(channels);
    }

    boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
