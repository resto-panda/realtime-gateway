package com.restopanda.realtime.authz.guest;

import static org.assertj.core.api.Assertions.assertThat;

import com.restopanda.commons.security.guest.GuestSession;
import com.restopanda.realtime.config.RealtimeProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

/**
 * Verify-only guest path: a genuine self-contained token (minted the way Service
 * &amp; Floor would) resolves to its session, a tampered token is rejected at the
 * signature step, and an expired token resolves but is not active.
 */
class GatewayGuestTokenValidatorTest {

    /** The deterministic dev key the codec falls back to when none is configured. */
    private static final String KEY = "dev-guest-session-signing-key-not-for-production";

    private final ObjectMapper mapper = new ObjectMapper();
    private GatewayGuestTokenValidator validator;

    @BeforeEach
    void setUp() {
        // Blank key + no OAuth issuer (dev-like) → codec falls back to the dev key.
        GuestTokenCodec codec =
                new GuestTokenCodec(new RealtimeProperties(null, null, null, null), new MockEnvironment());
        validator = new GatewayGuestTokenValidator(codec, new SelfContainedGuestSessionResolver(codec, mapper));
    }

    private String mint(String sid, String tid, String lid, long expEpoch) {
        String claimsJson = mapper.writeValueAsString(Map.of("sid", sid, "tid", tid, "lid", lid, "exp", expEpoch));
        String claimsB64 =
                Base64.getUrlEncoder().withoutPadding().encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8));
        String signed = "gst." + claimsB64;
        return signed + "." + hmac(signed);
    }

    private static String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void validTokenResolvesActiveSession() {
        long exp = Instant.now().plusSeconds(3600).getEpochSecond();
        Optional<GuestSession> session = validator.validate(mint("ses_A", "ten_A", "loc_1", exp));

        assertThat(session).isPresent();
        assertThat(session.get().sessionId()).isEqualTo("ses_A");
        assertThat(session.get().tenantId()).isEqualTo("ten_A");
        assertThat(session.get().isActive()).isTrue();
    }

    @Test
    void tamperedTokenIsRejected() {
        String token =
                mint("ses_A", "ten_A", "loc_1", Instant.now().plusSeconds(3600).getEpochSecond());
        char[] chars = token.toCharArray();
        chars[chars.length - 1] = chars[chars.length - 1] == 'A' ? 'B' : 'A';
        assertThat(validator.validate(new String(chars))).isEmpty();
    }

    @Test
    void expiredTokenResolvesButIsNotActive() {
        long exp = Instant.now().minusSeconds(60).getEpochSecond();
        Optional<GuestSession> session = validator.validate(mint("ses_A", "ten_A", "loc_1", exp));
        assertThat(session).isPresent();
        assertThat(session.get().isActive()).isFalse();
    }

    @Test
    void garbageTokenRejected() {
        assertThat(validator.validate(null)).isEmpty();
        assertThat(validator.validate("")).isEmpty();
        assertThat(validator.validate("not-a-token")).isEmpty();
    }

    /**
     * Fail-closed: with no guest signing key configured but a central OAuth issuer set
     * (a real deployment), the codec must NOT fall back to the committed dev key. A
     * token that a dev-key codec would happily accept is rejected here.
     */
    @Test
    void blankKeyInRealDeploymentFailsClosed() {
        MockEnvironment realDeployment = new MockEnvironment()
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://issuer.example");
        GuestTokenCodec codec =
                new GuestTokenCodec(new RealtimeProperties(null, null, null, null), realDeployment); // blank key
        GatewayGuestTokenValidator failClosed =
                new GatewayGuestTokenValidator(codec, new SelfContainedGuestSessionResolver(codec, mapper));

        long exp = Instant.now().plusSeconds(3600).getEpochSecond();
        String devKeyToken = mint("ses_A", "ten_A", "loc_1", exp); // signed with the dev key

        // The dev-like validator (setUp) would accept it; the real deployment must not.
        assertThat(validator.validate(devKeyToken)).isPresent();
        assertThat(failClosed.validate(devKeyToken)).isEmpty();
    }
}
