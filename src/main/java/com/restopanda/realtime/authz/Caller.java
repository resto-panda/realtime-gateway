package com.restopanda.realtime.authz;

import com.restopanda.commons.security.guest.GuestSession;
import com.restopanda.realtime.channel.Channel;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Who is asking to subscribe, and what they may see. Two callers, two schemes —
 * a staff member (verified platform JWT) or an anonymous guest device-session.
 * The rule "may this caller receive this channel" lives here so it is pure and
 * exhaustively unit-testable, independent of HTTP/Spring.
 *
 * <p><strong>Tenant is always the caller's own verified tenant.</strong> Every
 * rule first requires the channel's tenant to equal the caller's tenant, so a
 * caller can never be granted a channel in another tenant regardless of what
 * they request.
 */
public sealed interface Caller permits Caller.Staff, Caller.Guest {

    /** Whether this caller is allowed to receive events on the given channel. */
    boolean mayAccess(Channel channel);

    /** The caller's verified tenant — the only tenant any minted ticket may scope to. */
    String tenantId();

    /**
     * A staff member. Authority comes only from the local authz replica (the
     * {@code hasEntitlement} predicate is backed by {@code EntitlementEvaluator},
     * which reads the verified identity — never client input) plus the token's
     * {@code location_ids}.
     *
     * @param tenantId       the caller's verified tenant
     * @param userId         the caller's own verified user id (token {@code sub})
     * @param locationIds    the caller's in-scope locations (from the token)
     * @param hasEntitlement resolves an entitlement against the authz replica
     */
    record Staff(String tenantId, String userId, Set<String> locationIds, Predicate<String> hasEntitlement)
            implements Caller {

        public Staff {
            locationIds = locationIds == null ? Set.of() : Set.copyOf(locationIds);
        }

        @Override
        public boolean mayAccess(Channel channel) {
            if (!tenantId.equals(channel.tenantId())) {
                return false;
            }
            return switch (channel.family()) {
                // A station's queue: needs kitchen read. Station id is not a
                // location, so membership can't be checked here — kds:read gates it.
                case KDS_STATION -> hasEntitlement.test("kds:read");
                // A runner board is keyed by location: needs kitchen read AND that
                // the location is in the caller's scope.
                case KDS_RUNNER -> hasEntitlement.test("kds:read") && locationIds.contains(channel.entityId());
                // The floor map is keyed by location: floor read + location scope.
                case FLOOR -> hasEntitlement.test("floor:read") && locationIds.contains(channel.entityId());
                // A single session is a floor/service surface, tenant-scoped.
                case SESSION -> hasEntitlement.test("floor:read");
                // A personal user inbox: SELF-ONLY. No entitlement — a user may
                // always receive their own alerts, and never anyone else's.
                case USER -> userId != null && userId.equals(channel.entityId());
                // A chat thread carries message bodies: needs messaging read.
                case THREAD -> hasEntitlement.test("messaging:read");
            };
        }
    }

    /**
     * A guest device-session. Scoped to <em>only</em> its own session channel —
     * every other channel (another session, any floor/kds/runner board, and any
     * chat thread, whose body it cannot be proven to own at the gateway) is
     * rejected. This is the RLS-equivalent isolation for anonymous callers.
     *
     * @param session the validated guest session
     */
    record Guest(GuestSession session) implements Caller {

        @Override
        public String tenantId() {
            return session.tenantId();
        }

        @Override
        public boolean mayAccess(Channel channel) {
            if (session.tenantId() == null || !session.tenantId().equals(channel.tenantId())) {
                return false;
            }
            return channel.family() == com.restopanda.realtime.channel.ChannelFamily.SESSION
                    && channel.entityId().equals(session.sessionId());
        }
    }

    /** Convenience for callers building a {@link Staff} from list location ids. */
    static Staff staff(
            String tenantId, String userId, List<String> locationIds, Predicate<String> hasEntitlement) {
        return new Staff(
                tenantId, userId, locationIds == null ? Set.of() : Set.copyOf(locationIds), hasEntitlement);
    }
}
