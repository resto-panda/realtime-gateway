package com.restopanda.realtime.authz.guest;

import com.restopanda.realtime.config.RealtimeProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * <strong>Verify-only</strong> HMAC codec for guest device-session tokens,
 * mirroring the one {@code messaging-service} uses. The realtime-gateway is a
 * <em>validator</em> of guest tokens, not the issuer (Service &amp; Floor mints
 * them), so it only recomputes the HMAC over the signed part and
 * constant-time-compares it with the shared {@code guest-signing-key}.
 *
 * <p>Token format: a self-contained signed token {@code gst.<base64url(claims)>.<base64url(hmac)>}
 * where {@code claims} is {@code {"sid","oid","tid","lid","exp"}}. Verifying the
 * signature both proves authenticity and yields the claims — no DB read, no hop.
 */
@Component
public class GuestTokenCodec {

    private static final Logger log = LoggerFactory.getLogger(GuestTokenCodec.class);

    private static final String HMAC_ALG = "HmacSHA256";
    /** Deterministic dev/test key, used ONLY in a dev-like environment (never in prod). */
    private static final String DEV_KEY = "dev-guest-session-signing-key-not-for-production";

    /**
     * The verification key, or {@code null} when guest streaming is disabled (fail
     * closed): a real deployment with no {@code guest-signing-key} configured never
     * falls back to {@link #DEV_KEY}. A null key makes {@link #isSignatureValid}
     * reject every token.
     */
    private final byte[] signingKey;

    public GuestTokenCodec(RealtimeProperties properties, Environment environment) {
        if (properties.guestStreamingEnabled()) {
            // A real guest signing key is configured — verify tokens against it.
            this.signingKey = properties.guestSigningKey().getBytes(StandardCharsets.UTF_8);
        } else if (isDevLike(environment)) {
            // Blank key, but no central OAuth issuer is set either, so this is a
            // local/dev/test environment: fall back to the deterministic dev key for
            // convenience (mirrors OnMissingIssuerCondition's dev/prod gate).
            log.warn("No guest signing key configured; using the built-in dev key (never for production).");
            this.signingKey = DEV_KEY.getBytes(StandardCharsets.UTF_8);
        } else {
            // Real deployment (OAuth issuer set) with a blank guest signing key: FAIL
            // CLOSED. Never trust the committed DEV_KEY here — leave the key unset so no
            // guest token can verify and guest streaming stays disabled until
            // GUEST_SIGNING_KEY is configured.
            log.warn("Guest signing key is not configured but a central OAuth issuer is set; "
                    + "guest streaming is DISABLED (fail closed). Set GUEST_SIGNING_KEY to enable it.");
            this.signingKey = null;
        }
    }

    /** Whether the token is well-formed and signed by the shared key. */
    public boolean isSignatureValid(String token) {
        if (signingKey == null || token == null) {
            return false;
        }
        int dot = token.lastIndexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return false;
        }
        String signed = token.substring(0, dot);
        String mac = token.substring(dot + 1);
        return constantTimeEquals(mac, sign(signed));
    }

    /** The signed part of the token (everything before the final {@code .}). */
    public String signedPart(String token) {
        int dot = token.lastIndexOf('.');
        return dot <= 0 ? token : token.substring(0, dot);
    }

    private String sign(String value) {
        if (signingKey == null) {
            // Unreachable via isSignatureValid (which short-circuits), but guards any
            // future signing path against silently using a missing key.
            throw new IllegalStateException("guest signing key is not configured");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALG));
            byte[] sig = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign guest token", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Dev-like == no central OAuth2 issuer/JWKS configured (local/dev/test),
     * mirroring {@link com.restopanda.realtime.config.OnMissingIssuerCondition}. In a
     * real deployment the issuer is set and we must never fall back to {@link #DEV_KEY}.
     */
    private static boolean isDevLike(Environment environment) {
        String issuer = environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri");
        String jwkSetUri = environment.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri");
        boolean issuerConfigured =
                (issuer != null && !issuer.isBlank()) || (jwkSetUri != null && !jwkSetUri.isBlank());
        return !issuerConfigured;
    }
}
