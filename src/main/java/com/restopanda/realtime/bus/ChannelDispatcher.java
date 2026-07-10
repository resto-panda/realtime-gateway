package com.restopanda.realtime.bus;

/**
 * Delivers a resolved {@link ChannelPush} to whoever is listening on its channel.
 *
 * <p>This is the seam between "an event was mapped to a channel" and "the bytes
 * reach a browser". M1 ships a {@link LoggingChannelDispatcher log-only} impl;
 * M2 replaces it with the in-memory {@code ChannelRegistry} SSE fan-out; the
 * {@code RealtimeBroker} SPI (M6, scale-later) swaps it for cross-replica pub/sub.
 */
public interface ChannelDispatcher {

    /**
     * Fans a push out to the channel's subscribers. Must be non-blocking and
     * never throw into the bus consumer — a delivery failure to one screen must
     * not roll back the poll batch.
     *
     * @param push the resolved channel push
     */
    void dispatch(ChannelPush push);
}
