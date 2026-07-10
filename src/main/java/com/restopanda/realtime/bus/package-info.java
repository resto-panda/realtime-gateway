/**
 * The bus-facing half of the gateway: the {@link com.restopanda.realtime.bus.BusEventConsumer}
 * that subscribes to the platform event bus, the {@link com.restopanda.realtime.bus.EventMapper}
 * that turns a domain {@code EventEnvelope} into {@link com.restopanda.realtime.bus.ChannelPush}es,
 * and the {@link com.restopanda.realtime.bus.ChannelDispatcher} seam that delivers
 * them (log-only in M1, SSE fan-out from M2).
 */
package com.restopanda.realtime.bus;
