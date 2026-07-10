package com.restopanda.realtime.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Realtime-gateway security wiring.
 *
 * <p>In production the OAuth2 resource-server chain is provided by
 * {@code commons-security}'s {@code ResourceServerConfig} (active when an issuer
 * or JWKS URL is configured): it validates the platform's JWT locally and derives
 * identity + tenant only. The gateway layers two of its own rules on top of that
 * chain (see {@link StreamSecurityConfig}):
 * <ul>
 *   <li>{@code POST /v1/stream/ticket} — reachable by an authenticated staff JWT
 *       <em>or</em> an {@code X-Guest-Token}; identity is enforced in the
 *       controller, so the path is permitted at the transport layer.</li>
 *   <li>{@code GET /v1/stream} — an {@code EventSource} cannot send an
 *       {@code Authorization} header, so the stream is authorized by a one-time
 *       ticket redeemed in the controller, not by the security chain.</li>
 * </ul>
 *
 * <p>When no issuer is configured (local/dev/test) this supplies a deny-by-default
 * fallback chain so the gateway never serves unauthenticated traffic. (The stream
 * paths need a validated JWT / guest store, which only exist once the issuer is
 * wired, so they are intentionally not opened in the no-issuer fallback.)
 */
@Configuration
public class RealtimeSecurityConfig {

    @Bean
    @Conditional(OnMissingIssuerCondition.class)
    public SecurityFilterChain fallbackFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .anyRequest()
                        .denyAll());
        return http.build();
    }
}
