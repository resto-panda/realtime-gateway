/**
 * Guest device-session token verification for the stream handshake. The gateway
 * only verifies (never mints) tokens: it checks the shared-key HMAC binding and
 * resolves the token to an active session in the shared {@code service} store,
 * so a guest's stream can be scoped to its own session.
 */
package com.restopanda.realtime.authz.guest;
