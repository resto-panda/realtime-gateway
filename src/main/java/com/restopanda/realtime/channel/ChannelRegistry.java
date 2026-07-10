package com.restopanda.realtime.channel;

import com.restopanda.realtime.bus.ChannelDispatcher;
import com.restopanda.realtime.bus.ChannelPush;
import com.restopanda.realtime.config.RealtimeProperties;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The in-memory fan-out: it maps each channel to the set of live
 * {@link SseEmitter}s subscribed to it, and — as the production
 * {@link ChannelDispatcher} — writes each {@link ChannelPush} to those emitters.
 *
 * <p>Everything here is process-local; the gateway holds no durable state. A
 * dropped connection self-heals because every screen re-snapshots from REST on
 * reconnect. When volume demands more than one replica, the {@code RealtimeBroker}
 * SPI (M6) sits behind this same {@code dispatch} seam and shares fan-out across
 * replicas — no change to callers.
 *
 * <p>Thread-safety: the poller thread calls {@link #dispatch}, request threads
 * call {@link #subscribe}, and the scheduler calls {@link #heartbeat} — all
 * concurrently. Emitter sets are concurrent, and a send failure detaches the
 * emitter rather than propagating.
 */
@Component
public class ChannelRegistry implements ChannelDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ChannelRegistry.class);

    /** channel value → live emitters. */
    private final ConcurrentHashMap<String, Set<SseEmitter>> byChannel = new ConcurrentHashMap<>();

    private final RealtimeProperties properties;

    public ChannelRegistry(RealtimeProperties properties) {
        this.properties = properties;
    }

    /**
     * Opens an SSE connection subscribed to the given channels. The returned
     * emitter is registered on every channel and automatically detached from all
     * of them on completion, timeout, or error — so a disconnect never leaks.
     *
     * @param channels the channels the (already-authorized) caller may receive
     * @return the emitter to return from the SSE controller
     */
    public SseEmitter subscribe(Collection<Channel> channels) {
        SseEmitter emitter = new SseEmitter(properties.streamTimeout().toMillis());
        for (Channel channel : channels) {
            byChannel
                    .computeIfAbsent(channel.value(), k -> ConcurrentHashMap.newKeySet())
                    .add(emitter);
        }
        Runnable detach = () -> remove(emitter, channels);
        emitter.onCompletion(detach);
        emitter.onTimeout(() -> {
            emitter.complete();
            detach.run();
        });
        emitter.onError(e -> detach.run());
        // An initial comment flushes headers so the client's `open` fires promptly.
        try {
            emitter.send(SseEmitter.event().comment("subscribed"));
        } catch (IOException e) {
            remove(emitter, channels);
        }
        log.debug("subscribed emitter to {} channel(s)", channels.size());
        return emitter;
    }

    @Override
    public void dispatch(ChannelPush push) {
        Set<SseEmitter> emitters = byChannel.get(push.channel().value());
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .id(push.eventId())
                .name("message")
                .data(push.payload(), org.springframework.http.MediaType.APPLICATION_JSON);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(event);
            } catch (IOException | IllegalStateException e) {
                // Client went away (or emitter already completed): detach it from
                // this channel. Best-effort — never fail the fan-out.
                emitters.remove(emitter);
                log.trace("detached dead emitter from {}", push.channel().value());
            }
        }
        // Drop the channel entry if it drained, so the map does not accumulate
        // empty sets for churned channels.
        byChannel.computeIfPresent(push.channel().value(), (k, set) -> set.isEmpty() ? null : set);
    }

    /** Sends a keepalive comment to every live emitter, holding connections open. */
    @Scheduled(fixedDelayString = "${restopanda.realtime.heartbeat-interval:PT15S}")
    public void heartbeat() {
        byChannel.forEach((channel, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("hb"));
                } catch (IOException | IllegalStateException e) {
                    emitters.remove(emitter);
                }
            }
            byChannel.computeIfPresent(channel, (k, set) -> set.isEmpty() ? null : set);
        });
    }

    private void remove(SseEmitter emitter, Collection<Channel> channels) {
        for (Channel channel : channels) {
            Set<SseEmitter> emitters = byChannel.get(channel.value());
            if (emitters != null) {
                emitters.remove(emitter);
                // Drop the empty set so the map does not grow unbounded with
                // idle channels.
                byChannel.compute(channel.value(), (k, set) -> (set == null || set.isEmpty()) ? null : set);
            }
        }
    }

    /** Test/observability helper: number of live emitters on a channel. */
    public int subscriberCount(Channel channel) {
        Set<SseEmitter> emitters = byChannel.get(channel.value());
        return emitters == null ? 0 : emitters.size();
    }

    /** Test/observability helper: a snapshot of channel → subscriber counts. */
    public Map<String, Integer> counts() {
        return byChannel.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, e -> e.getValue().size()));
    }
}
