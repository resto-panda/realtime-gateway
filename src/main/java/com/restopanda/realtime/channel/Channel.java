package com.restopanda.realtime.channel;

import java.util.Objects;

/**
 * A tenant-scoped fan-out channel: {@code {tenantId}:{family}.{entityId}}, e.g.
 * {@code ten_x:kds.station.stn_1}.
 *
 * <p>The tenant scope is always derived from a verified token, never from client
 * input — two tenants can never collide on a channel, and a subscriber is only
 * ever bound to channels in its own tenant.
 *
 * @param tenantId the owning tenant ({@code ten_…})
 * @param family   the channel family
 * @param entityId the family-specific entity id (station/location/session/thread)
 */
public record Channel(String tenantId, ChannelFamily family, String entityId) {

    public Channel {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(entityId, "entityId");
    }

    /** The wire form: {@code {tenantId}:{family.prefix}.{entityId}}. */
    public String value() {
        return tenantId + ":" + family.prefix() + "." + entityId;
    }

    @Override
    public String toString() {
        return value();
    }

    public static Channel kdsStation(String tenantId, String stationId) {
        return new Channel(tenantId, ChannelFamily.KDS_STATION, stationId);
    }

    public static Channel kdsRunner(String tenantId, String locationId) {
        return new Channel(tenantId, ChannelFamily.KDS_RUNNER, locationId);
    }

    public static Channel floor(String tenantId, String locationId) {
        return new Channel(tenantId, ChannelFamily.FLOOR, locationId);
    }

    public static Channel session(String tenantId, String sessionId) {
        return new Channel(tenantId, ChannelFamily.SESSION, sessionId);
    }

    /** A staff user's personal alert inbox — only that user may subscribe to it. */
    public static Channel user(String tenantId, String userId) {
        return new Channel(tenantId, ChannelFamily.USER, userId);
    }

    public static Channel thread(String tenantId, String threadId) {
        return new Channel(tenantId, ChannelFamily.THREAD, threadId);
    }

    /** A location's cash-register/drawer board — payment-entitled staff only. */
    public static Channel register(String tenantId, String locationId) {
        return new Channel(tenantId, ChannelFamily.REGISTER, locationId);
    }

    /**
     * Parses a wire channel string back into its parts, validating the shape.
     *
     * @param value the {@code {tenant}:{family}.{id}} string
     * @return the parsed channel
     * @throws IllegalArgumentException if the string is malformed or the family
     *                                  is unknown
     */
    public static Channel parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("channel is null");
        }
        int colon = value.indexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            throw new IllegalArgumentException("malformed channel: " + value);
        }
        String tenantId = value.substring(0, colon);
        String body = value.substring(colon + 1);
        ChannelFamily family = ChannelFamily.matching(body);
        if (family == null) {
            throw new IllegalArgumentException("unknown channel family: " + value);
        }
        String entityId = body.substring(family.prefix().length() + 1);
        return new Channel(tenantId, family, entityId);
    }
}
