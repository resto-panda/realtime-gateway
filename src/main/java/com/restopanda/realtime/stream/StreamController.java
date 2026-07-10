package com.restopanda.realtime.stream;

import com.restopanda.commons.core.error.UnauthorizedException;
import com.restopanda.realtime.authz.Caller;
import com.restopanda.realtime.authz.CallerResolver;
import com.restopanda.realtime.authz.ChannelAuthorizer;
import com.restopanda.realtime.channel.Channel;
import com.restopanda.realtime.channel.ChannelRegistry;
import com.restopanda.realtime.config.RealtimeProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The two-step stream handshake.
 *
 * <ol>
 *   <li>{@code POST /v1/stream/ticket} — an authenticated staff member or an
 *       {@code X-Guest-Token} guest asks for a set of channels. The gateway
 *       authorizes them against the caller's tenant/entitlements/location (or
 *       guest session scope) and mints a short-lived, one-time ticket bound to
 *       exactly those channels.</li>
 *   <li>{@code GET /v1/stream?ticket=} — the browser's {@code EventSource}
 *       redeems the ticket (it cannot send an {@code Authorization} header) and
 *       the gateway opens the SSE stream on the ticket's channels.</li>
 * </ol>
 *
 * The stream endpoint itself trusts only the ticket; identity/authorization was
 * settled at mint time, so no JWT/guest header is needed to open the stream.
 */
@RestController
@RequestMapping("/v1/stream")
public class StreamController {

    private final CallerResolver callerResolver;
    private final ChannelAuthorizer authorizer;
    private final TicketStore ticketStore;
    private final ChannelRegistry registry;
    private final RealtimeProperties properties;

    public StreamController(
            CallerResolver callerResolver,
            ChannelAuthorizer authorizer,
            TicketStore ticketStore,
            ChannelRegistry registry,
            RealtimeProperties properties) {
        this.callerResolver = callerResolver;
        this.authorizer = authorizer;
        this.ticketStore = ticketStore;
        this.registry = registry;
        this.properties = properties;
    }

    @PostMapping("/ticket")
    public TicketResponse mintTicket(@RequestBody TicketRequest request, HttpServletRequest http) {
        Caller caller = callerResolver.resolve(http);
        List<Channel> channels = authorizer.authorize(caller, request.channels());
        StreamTicket ticket = ticketStore.mint(caller.tenantId(), channels);
        return new TicketResponse(
                ticket.value(),
                properties.ticketTtl().toSeconds(),
                channels.stream().map(Channel::value).toList());
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter openStream(@RequestParam("ticket") String ticket) {
        StreamTicket redeemed = ticketStore
                .redeem(ticket)
                .orElseThrow(() -> new UnauthorizedException(
                        "realtime.ticket.invalid", "the stream ticket is missing, expired, or already used"));
        return registry.subscribe(redeemed.channels());
    }
}
