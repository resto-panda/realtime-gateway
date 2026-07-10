package com.restopanda.realtime.authz.guest;

import com.restopanda.commons.security.guest.GuestSession;
import com.restopanda.commons.security.guest.GuestTokenValidator;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The gateway's {@link GuestTokenValidator} — a verifier, not an issuer (Service
 * &amp; Floor mints the tokens). Two steps, mirroring messaging-service: verify
 * the token's HMAC binding with the shared key (cheap, no DB), then resolve its
 * self-contained claims to a {@link GuestSession}. The result grants only the
 * narrow guest scope (its own session) and never staff entitlements.
 */
@Component
public class GatewayGuestTokenValidator implements GuestTokenValidator {

    private static final Logger log = LoggerFactory.getLogger(GatewayGuestTokenValidator.class);

    private final GuestTokenCodec codec;
    private final SelfContainedGuestSessionResolver resolver;

    public GatewayGuestTokenValidator(GuestTokenCodec codec, SelfContainedGuestSessionResolver resolver) {
        this.codec = codec;
        this.resolver = resolver;
    }

    @Override
    public Optional<GuestSession> validate(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || !codec.isSignatureValid(rawToken)) {
            log.debug("Rejected guest token: missing or bad signature");
            return Optional.empty();
        }
        return resolver.resolve(rawToken);
    }
}
