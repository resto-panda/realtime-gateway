package com.restopanda.realtime.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.restopanda.commons.core.error.ForbiddenException;
import com.restopanda.commons.core.error.ValidationException;
import com.restopanda.commons.security.guest.GuestSession;
import com.restopanda.realtime.channel.Channel;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * M3 isolation verify — the RLS-equivalent for realtime. Proves cross-tenant,
 * cross-location, missing-entitlement, and guest-out-of-session subscriptions are
 * all rejected, and that a caller only ever gets channels in its own tenant.
 */
class ChannelAuthorizerTest {

    private final ChannelAuthorizer authorizer = new ChannelAuthorizer();

    /** The user id of the {@link #staff} caller — its own personal channel. */
    private static final String SELF = "usr_self";

    /** A staff caller in tenant/locations (user id {@link #SELF}), holding the given entitlements. */
    private static Caller staff(String tenant, Set<String> locations, String... entitlements) {
        Set<String> held = Set.of(entitlements);
        return Caller.staff(tenant, SELF, List.copyOf(locations), held::contains);
    }

    // ---- staff: happy paths ----------------------------------------------------

    @Test
    void staffWithKdsReadGetsStationAndInScopeRunner() {
        Caller caller = staff("ten_A", Set.of("loc_1"), "kds:read");
        List<Channel> ok = authorizer.authorize(caller, List.of("ten_A:kds.station.stn_1", "ten_A:kds.runner.loc_1"));
        assertThat(ok).extracting(Channel::value).containsExactly("ten_A:kds.station.stn_1", "ten_A:kds.runner.loc_1");
    }

    @Test
    void staffFloorReadGetsFloorAndSession() {
        Caller caller = staff("ten_A", Set.of("loc_1"), "floor:read");
        assertThat(authorizer.authorize(caller, List.of("ten_A:floor.loc_1", "ten_A:session.ses_1")))
                .hasSize(2);
    }

    @Test
    void staffMessagingReadGetsThread() {
        Caller caller = staff("ten_A", Set.of(), "messaging:read");
        assertThat(authorizer.authorize(caller, List.of("ten_A:thread.thr_1"))).hasSize(1);
    }

    @Test
    void staffPaymentReadGetsInScopeRegister() {
        Caller caller = staff("ten_A", Set.of("loc_1"), "payment:read");
        assertThat(authorizer.authorize(caller, List.of("ten_A:register.loc_1")))
                .singleElement()
                .extracting(Channel::value)
                .isEqualTo("ten_A:register.loc_1");
    }

    @Test
    void staffPaymentDrawerAloneAlsoGetsInScopeRegister() {
        Caller caller = staff("ten_A", Set.of("loc_1"), "payment:drawer");
        assertThat(authorizer.authorize(caller, List.of("ten_A:register.loc_1")))
                .hasSize(1);
    }

    // ---- staff: the rejections -------------------------------------------------

    @Test
    void crossTenantIsRejected() {
        Caller caller = staff("ten_A", Set.of("loc_1"), "kds:read", "floor:read");
        assertThatThrownBy(() -> authorizer.authorize(caller, List.of("ten_B:kds.station.stn_1")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void crossLocationRunnerIsRejected() {
        Caller caller = staff("ten_A", Set.of("loc_1"), "kds:read");
        assertThatThrownBy(() -> authorizer.authorize(caller, List.of("ten_A:kds.runner.loc_2")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void crossLocationFloorIsRejected() {
        Caller caller = staff("ten_A", Set.of("loc_1"), "floor:read");
        assertThatThrownBy(() -> authorizer.authorize(caller, List.of("ten_A:floor.loc_2")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void missingEntitlementIsRejected() {
        Caller caller = staff("ten_A", Set.of("loc_1")); // holds nothing
        assertThatThrownBy(() -> authorizer.authorize(caller, List.of("ten_A:kds.station.stn_1")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void registerWithoutPaymentEntitlementIsRejected() {
        // floor:read/kds:read do NOT open the register board — payment:* gates it.
        Caller caller = staff("ten_A", Set.of("loc_1"), "floor:read", "kds:read");
        assertThatThrownBy(() -> authorizer.authorize(caller, List.of("ten_A:register.loc_1")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void crossLocationRegisterIsRejected() {
        Caller caller = staff("ten_A", Set.of("loc_1"), "payment:read", "payment:drawer");
        assertThatThrownBy(() -> authorizer.authorize(caller, List.of("ten_A:register.loc_2")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void crossTenantRegisterIsRejected() {
        Caller caller = staff("ten_A", Set.of("loc_1"), "payment:read");
        assertThatThrownBy(() -> authorizer.authorize(caller, List.of("ten_B:register.loc_1")))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- staff: personal user channel is self-only ----------------------------

    @Test
    void staffGetsOwnUserChannelWithNoEntitlement() {
        Caller caller = staff("ten_A", Set.of()); // holds nothing — a user always gets their own inbox
        assertThat(authorizer.authorize(caller, List.of("ten_A:user." + SELF)))
                .singleElement()
                .extracting(Channel::value)
                .isEqualTo("ten_A:user." + SELF);
    }

    @Test
    void staffCannotSubscribeToAnotherUsersChannel() {
        Caller caller = staff("ten_A", Set.of("loc_1"), "floor:read", "kds:read");
        assertThatThrownBy(() -> authorizer.authorize(caller, List.of("ten_A:user.usr_other")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void userChannelCrossTenantIsRejectedEvenForSameUserId() {
        Caller caller = staff("ten_A", Set.of());
        assertThatThrownBy(() -> authorizer.authorize(caller, List.of("ten_B:user." + SELF)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void wholeRequestRejectedIfAnySingleChannelIsOutOfScope() {
        Caller caller = staff("ten_A", Set.of("loc_1"), "kds:read");
        assertThatThrownBy(() -> authorizer.authorize(
                        caller, List.of("ten_A:kds.station.stn_1", "ten_A:floor.loc_1"))) // floor needs floor:read
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- guest: scoped to its own session only ---------------------------------

    private static Caller guest(String tenant, String sessionId) {
        return new Caller.Guest(
                new GuestSession(sessionId, null, tenant, "loc_1", Instant.now().plusSeconds(600)));
    }

    @Test
    void guestGetsOwnSessionOnly() {
        assertThat(authorizer.authorize(guest("ten_A", "ses_1"), List.of("ten_A:session.ses_1")))
                .singleElement()
                .extracting(Channel::value)
                .isEqualTo("ten_A:session.ses_1");
    }

    @Test
    void guestOutOfSessionIsRejected() {
        assertThatThrownBy(() -> authorizer.authorize(guest("ten_A", "ses_1"), List.of("ten_A:session.ses_2")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void guestCannotReachFloorKdsOrThread() {
        Caller g = guest("ten_A", "ses_1");
        for (String c : List.of(
                "ten_A:floor.loc_1",
                "ten_A:kds.station.stn_1",
                "ten_A:thread.thr_1",
                "ten_A:user.usr_1",
                "ten_A:register.loc_1")) {
            assertThatThrownBy(() -> authorizer.authorize(g, List.of(c)))
                    .as("guest must not reach %s", c)
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Test
    void guestCrossTenantIsRejected() {
        assertThatThrownBy(() -> authorizer.authorize(guest("ten_A", "ses_1"), List.of("ten_B:session.ses_1")))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- request validation ----------------------------------------------------

    @Test
    void emptyChannelsIsRejected() {
        assertThatThrownBy(() -> authorizer.authorize(staff("ten_A", Set.of()), List.of()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void malformedChannelIsRejected() {
        assertThatThrownBy(() -> authorizer.authorize(staff("ten_A", Set.of(), "kds:read"), List.of("not-a-channel")))
                .isInstanceOf(ValidationException.class);
    }
}
