package com.restopanda.realtime.bus;

import com.restopanda.commons.core.EventEnvelope;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The gateway's bus subscriber. The {@code commons-messaging} pg-bus poller
 * reads {@code bus.events} and re-raises every foreign event as an in-process
 * {@link EventEnvelope} application event; this listener maps each one to its
 * channels and hands the pushes to the {@link ChannelDispatcher}.
 *
 * <p>Deliberately <em>not</em> an {@code IdempotentConsumer}: a realtime push is
 * best-effort and stateless, and the snapshot-on-reconnect contract self-heals
 * any duplicate or dropped hint — so paying for a dedupe ledger write per event
 * would be waste. Delivery never throws back into the poller (the dispatcher
 * swallows per-emitter failures), so one dead browser can't stall the batch.
 */
@Component
public class BusEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(BusEventConsumer.class);

    private final EventMapper mapper;
    private final ChannelDispatcher dispatcher;

    public BusEventConsumer(EventMapper mapper, ChannelDispatcher dispatcher) {
        this.mapper = mapper;
        this.dispatcher = dispatcher;
    }

    @EventListener
    public void onEvent(EventEnvelope envelope) {
        List<ChannelPush> pushes = mapper.map(envelope);
        for (ChannelPush push : pushes) {
            try {
                dispatcher.dispatch(push);
            } catch (RuntimeException ex) {
                // Never propagate: a fan-out failure must not roll back the poll
                // batch (which would replay every event in it).
                log.warn("dispatch failed for {} ({}): {}", push.channel().value(), push.eventType(), ex.toString());
            }
        }
    }
}
