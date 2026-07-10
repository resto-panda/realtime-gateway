package com.restopanda.realtime.authz;

import com.restopanda.commons.core.TenantContext;
import com.restopanda.commons.core.error.UnauthorizedException;
import com.restopanda.commons.security.authz.EntitlementEvaluator;
import com.restopanda.commons.security.guest.GuestSession;
import com.restopanda.commons.security.guest.GuestSessionAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@link Caller} for a ticket-mint request from what the security
 * layer has already established — never from client-supplied identity fields:
 * <ul>
 *   <li><b>Staff</b>: the {@code TenantContextFilter} bound a {@link TenantContext}
 *       from the verified JWT. Entitlements are checked against the authz replica
 *       via {@link EntitlementEvaluator}.</li>
 *   <li><b>Guest</b>: the {@code GuestSessionAuthFilter} validated an
 *       {@code X-Guest-Token} and exposed a {@link GuestSession} request attribute.</li>
 * </ul>
 * A request with neither is unauthenticated.
 */
@Component
public class CallerResolver {

    private final EntitlementEvaluator entitlements;

    public CallerResolver(EntitlementEvaluator entitlements) {
        this.entitlements = entitlements;
    }

    /**
     * @param request the current request
     * @return the resolved caller
     * @throws UnauthorizedException if the request carries neither a verified
     *                               staff identity nor an active guest session
     */
    public Caller resolve(HttpServletRequest request) {
        TenantContext ctx = TenantContext.current().orElse(null);
        if (ctx != null && ctx.tenantId() != null && ctx.userId() != null) {
            return Caller.staff(ctx.tenantId(), ctx.locationIds(), entitlements::has);
        }

        Object attr = request.getAttribute(GuestSessionAuthFilter.GUEST_SESSION_ATTRIBUTE);
        if (attr instanceof GuestSession session && session.isActive()) {
            return new Caller.Guest(session);
        }

        throw new UnauthorizedException(
                "realtime.unauthenticated", "a staff token or an active guest session is required");
    }
}
