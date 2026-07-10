package com.restopanda.realtime.config;

import com.restopanda.commons.security.guest.GuestSessionAuthFilter;
import com.restopanda.commons.security.guest.GuestTokenValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Transport-layer rules for the stream handshake, layered ahead of
 * commons-security's default resource-server chain.
 *
 * <p>A single high-precedence chain matches {@code /v1/stream/**} and
 * <em>permits</em> it: the two endpoints authorize themselves in the controller
 * (a staff JWT or {@code X-Guest-Token} on the mint, a one-time ticket on the
 * open). It still enables {@code oauth2ResourceServer().jwt()} so that when a
 * staff {@code Authorization: Bearer} <em>is</em> present it gets validated and
 * the {@code TenantContextFilter} can bind the tenant/entitlements the mint needs.
 *
 * <p>Active only when a JWKS/issuer is configured (so the shared {@code JwtDecoder}
 * exists). In the no-issuer local/test profile the deny-by-default fallback in
 * {@link RealtimeSecurityConfig} applies and the stream is closed.
 */
@Configuration
@ConditionalOnExpression("'${spring.security.oauth2.resourceserver.jwt.issuer-uri:}' != '' "
        + "or '${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}' != ''")
public class StreamSecurityConfig {

    static final String[] STREAM_PATHS = {"/v1/stream", "/v1/stream/**"};

    @Bean
    @Order(1)
    public SecurityFilterChain streamFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(STREAM_PATHS)
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // Validate a staff Bearer token when present (populates the
                // SecurityContext → TenantContext); absent/guest requests pass through
                // and are handled by the guest filter + controller.
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /**
     * Registers the commons guest filter for the stream paths so an
     * {@code X-Guest-Token} on the ticket mint is validated and exposed as a
     * {@code GuestSession} request attribute. The verifier uses the shared
     * signing key (a deterministic dev key when unconfigured), mirroring
     * messaging-service.
     */
    @Bean
    public FilterRegistrationBean<GuestSessionAuthFilter> guestSessionFilter(GuestTokenValidator validator) {
        FilterRegistrationBean<GuestSessionAuthFilter> reg =
                new FilterRegistrationBean<>(new GuestSessionAuthFilter(validator));
        reg.addUrlPatterns("/v1/stream/*");
        reg.setOrder(Ordered.LOWEST_PRECEDENCE - 150);
        return reg;
    }
}
