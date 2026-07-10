package com.restopanda.realtime.authz.guest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.restopanda.commons.security.guest.GuestSession;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves a signature-verified guest token to a {@link GuestSession} by reading
 * the claims that travel inside the (already verified) signed part — no network
 * hop, no session store. The signed part is {@code gst.<base64url(claims-json)>};
 * we decode the claims, and build the session (the {@code GuestSessionAuthFilter}
 * then checks it is active). Mirrors messaging-service's resolver so the gateway
 * accepts exactly the tokens Service &amp; Floor mints.
 */
@Component
public class SelfContainedGuestSessionResolver {

    private static final Logger log = LoggerFactory.getLogger(SelfContainedGuestSessionResolver.class);

    private final GuestTokenCodec codec;
    private final ObjectMapper objectMapper;

    public SelfContainedGuestSessionResolver(GuestTokenCodec codec, ObjectMapper objectMapper) {
        this.codec = codec;
        this.objectMapper = objectMapper;
    }

    /**
     * @param rawToken the raw token (signature already verified by the caller)
     * @return the resolved session, or empty if the claims are malformed
     */
    public Optional<GuestSession> resolve(String rawToken) {
        try {
            String signed = codec.signedPart(rawToken);
            int dot = signed.indexOf('.');
            if (dot <= 0 || dot == signed.length() - 1) {
                return Optional.empty();
            }
            String claimsB64 = signed.substring(dot + 1);
            byte[] json = Base64.getUrlDecoder().decode(claimsB64);
            Claims claims = objectMapper.readValue(json, Claims.class);
            if (claims.sessionId() == null || claims.tenantId() == null || claims.exp() <= 0) {
                return Optional.empty();
            }
            Instant expiresAt = Instant.ofEpochSecond(claims.exp());
            return Optional.of(new GuestSession(
                    claims.sessionId(), claims.orderId(), claims.tenantId(), claims.locationId(), expiresAt));
        } catch (RuntimeException e) {
            log.debug(
                    "Self-contained guest token claims unreadable: {}",
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Compact self-contained claims; {@code exp} is epoch-seconds. */
    record Claims(
            @JsonProperty("sid") String sessionId,
            @JsonProperty("oid") String orderId,
            @JsonProperty("tid") String tenantId,
            @JsonProperty("lid") String locationId,
            @JsonProperty("exp") long exp) {}
}
