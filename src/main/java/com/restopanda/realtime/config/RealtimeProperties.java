package com.restopanda.realtime.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway tunables under {@code restopanda.realtime}.
 *
 * @param ticketTtl          how long a minted stream ticket stays redeemable
 *                           (one-time; kept short so a leaked ticket is useless)
 * @param heartbeatInterval  cadence of SSE keepalive comments that hold the
 *                           connection open through idle periods and proxies
 * @param streamTimeout      max lifetime of a single SSE connection before the
 *                           client is asked to reconnect (and re-snapshot)
 * @param guestSigningKey    shared HMAC key used to verify a guest device-session
 *                           token's binding (the same key {@code service} mints
 *                           with); blank disables guest streaming
 */
@ConfigurationProperties(prefix = "restopanda.realtime")
public record RealtimeProperties(
        Duration ticketTtl, Duration heartbeatInterval, Duration streamTimeout, String guestSigningKey) {

    public RealtimeProperties {
        if (ticketTtl == null) {
            ticketTtl = Duration.ofSeconds(30);
        }
        if (heartbeatInterval == null) {
            heartbeatInterval = Duration.ofSeconds(15);
        }
        if (streamTimeout == null) {
            streamTimeout = Duration.ofMinutes(30);
        }
    }

    /** Whether guest device-session streaming is enabled (a key is configured). */
    public boolean guestStreamingEnabled() {
        return guestSigningKey != null && !guestSigningKey.isBlank();
    }
}
