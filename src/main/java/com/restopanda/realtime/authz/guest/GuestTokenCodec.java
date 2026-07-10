package com.restopanda.realtime.authz.guest;

import com.restopanda.realtime.config.RealtimeProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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

    private static final String HMAC_ALG = "HmacSHA256";
    /** Deterministic dev/test key when none is configured (never used in prod). */
    private static final String DEV_KEY = "dev-guest-session-signing-key-not-for-production";

    private final byte[] signingKey;

    public GuestTokenCodec(RealtimeProperties properties) {
        String configured = properties.guestSigningKey();
        String effective = (configured == null || configured.isBlank()) ? DEV_KEY : configured;
        this.signingKey = effective.getBytes(StandardCharsets.UTF_8);
    }

    /** Whether the token is well-formed and signed by the shared key. */
    public boolean isSignatureValid(String token) {
        if (token == null) {
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
}
