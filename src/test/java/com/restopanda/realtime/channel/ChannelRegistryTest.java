package com.restopanda.realtime.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.restopanda.realtime.bus.ChannelPush;
import com.restopanda.realtime.config.RealtimeProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * M2 verify (bookkeeping half): a subscribed emitter is registered on its
 * channel, a push targets only that channel's emitters, and a dead emitter is
 * detached on the next dispatch so nothing leaks. End-to-end byte delivery over a
 * live SSE connection (and normal-disconnect teardown) is proven by the MockMvc
 * {@code StreamController} test in M3.
 */
class ChannelRegistryTest {

    private final ChannelRegistry registry = new ChannelRegistry(new RealtimeProperties(null, null, null, null));

    private static ChannelPush hint(Channel channel) {
        return new ChannelPush(
                channel, "ticket.bumped", "evt_1", true, Map.of("type", "ticket.bumped", "channel", channel.value()));
    }

    @Test
    void subscribeRegistersEmitterOnEachChannel() {
        Channel station = Channel.kdsStation("ten_x", "stn_1");
        Channel runner = Channel.kdsRunner("ten_x", "loc_1");

        registry.subscribe(List.of(station, runner));

        assertThat(registry.subscriberCount(station)).isEqualTo(1);
        assertThat(registry.subscriberCount(runner)).isEqualTo(1);
        assertThat(registry.counts()).containsKeys("ten_x:kds.station.stn_1", "ten_x:kds.runner.loc_1");
    }

    @Test
    void dispatchToChannelWithNoSubscribersIsANoOp() {
        // Nothing subscribed → dispatch must not throw or create state.
        registry.dispatch(hint(Channel.floor("ten_x", "loc_9")));
        assertThat(registry.counts()).isEmpty();
    }

    @Test
    void deadEmitterIsDetachedOnDispatchSoNothingLeaks() {
        Channel station = Channel.kdsStation("ten_x", "stn_1");
        SseEmitter emitter = registry.subscribe(List.of(station));
        assertThat(registry.subscriberCount(station)).isEqualTo(1);

        // Simulate the client going away: once completed, any send throws, and
        // the registry must drop the emitter rather than retain a dead entry.
        emitter.complete();
        registry.dispatch(hint(station));

        assertThat(registry.subscriberCount(station)).isZero();
        assertThat(registry.counts()).doesNotContainKey("ten_x:kds.station.stn_1");
    }

    @Test
    void heartbeatDropsDeadEmitters() {
        Channel station = Channel.kdsStation("ten_x", "stn_1");
        SseEmitter emitter = registry.subscribe(List.of(station));
        emitter.complete();

        registry.heartbeat();

        assertThat(registry.subscriberCount(station)).isZero();
    }
}
