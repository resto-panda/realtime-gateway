package com.restopanda.realtime.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when no OAuth2 issuer/JWKS is configured. Used to install a
 * locked-down fallback security chain in environments (local/dev/test) where the
 * central issuer is not set, so the gateway never serves unauthenticated
 * traffic. In production (issuer/jwk-set-uri set) commons-security's
 * resource-server chain is used and this fallback is inactive.
 */
public class OnMissingIssuerCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var env = context.getEnvironment();
        String issuer = env.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri");
        String jwkSetUri = env.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri");
        boolean configured = (issuer != null && !issuer.isBlank()) || (jwkSetUri != null && !jwkSetUri.isBlank());
        return !configured;
    }
}
