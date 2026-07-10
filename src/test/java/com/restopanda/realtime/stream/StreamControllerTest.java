package com.restopanda.realtime.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.restopanda.commons.core.error.UnauthorizedException;
import com.restopanda.realtime.authz.Caller;
import com.restopanda.realtime.authz.CallerResolver;
import com.restopanda.realtime.authz.ChannelAuthorizer;
import com.restopanda.realtime.channel.ChannelRegistry;
import com.restopanda.realtime.config.RealtimeProperties;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M3 HTTP wiring: {@code POST /ticket} authorizes + mints, and {@code GET /stream}
 * redeems once to open an async SSE connection; a reused ticket is rejected.
 * Uses a stub {@link CallerResolver} (auth resolution is covered by its own
 * tests) with the real authorizer, ticket store, and registry.
 */
class StreamControllerTest {

    private final ObjectMapper json = new ObjectMapper();
    private final RealtimeProperties props = new RealtimeProperties(null, null, null, null);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        CallerResolver resolver = mock(CallerResolver.class);
        Caller staff = Caller.staff("ten_A", List.of("loc_1"), Set.of("kds:read")::contains);
        when(resolver.resolve(any())).thenReturn(staff);

        StreamController controller = new StreamController(
                resolver, new ChannelAuthorizer(), new TicketStore(props), new ChannelRegistry(props), props);
        mvc = standaloneSetup(controller).build();
    }

    private String mintTicket() throws Exception {
        MvcResult res = mvc.perform(post("/v1/stream/ticket")
                        .contentType("application/json")
                        .content("{\"channels\":[\"ten_A:kds.station.stn_1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expires_in").value(30))
                .andExpect(jsonPath("$.channels[0]").value("ten_A:kds.station.stn_1"))
                .andReturn();
        JsonNode body = json.readTree(res.getResponse().getContentAsString());
        return body.get("ticket").asText();
    }

    @Test
    void mintThenOpenStartsAnSseStream() throws Exception {
        String ticket = mintTicket();

        MvcResult stream =
                mvc.perform(get("/v1/stream").param("ticket", ticket)).andReturn();

        // An SseEmitter return value puts the request into async mode.
        assertThat(stream.getRequest().isAsyncStarted()).isTrue();
    }

    @Test
    void reusedTicketIsRejected() throws Exception {
        String ticket = mintTicket();
        mvc.perform(get("/v1/stream").param("ticket", ticket)).andReturn(); // consumes it

        // A second open with the same ticket must be unauthorized (one-time).
        assertThatThrownBy(() -> mvc.perform(get("/v1/stream").param("ticket", ticket)))
                .hasCauseInstanceOf(UnauthorizedException.class);
    }
}
