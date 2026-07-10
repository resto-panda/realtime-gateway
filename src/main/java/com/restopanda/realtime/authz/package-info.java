/**
 * Channel authorization: who may subscribe to which channel. The {@link
 * com.restopanda.realtime.authz.CallerResolver} derives a {@link
 * com.restopanda.realtime.authz.Caller} (staff or guest) from the verified
 * identity, and the {@link com.restopanda.realtime.authz.ChannelAuthorizer}
 * enforces per-family rules (staff entitlement + location membership; guest
 * scoped to its own session) — the RLS-equivalent for the realtime layer.
 */
package com.restopanda.realtime.authz;
