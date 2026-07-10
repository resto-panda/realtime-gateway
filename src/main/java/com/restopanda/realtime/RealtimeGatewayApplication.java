package com.restopanda.realtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * RestoPanda Realtime Gateway.
 *
 * <p>A stateless bus→SSE bridge. It subscribes to the platform event bus (the
 * {@code commons-messaging} pg-bus poller re-raises each foreign event as an
 * in-process {@code EventEnvelope}), maps each domain event to one or more
 * per-tenant channel strings, and fans it out to connected browsers over
 * Server-Sent Events. It holds no business state.
 *
 * <p>Realtime is best-effort — correctness always comes from REST. Every screen
 * fetches a snapshot from the existing endpoints, then applies stream deltas,
 * and re-snapshots on reconnect. The gateway therefore mostly ships refetch
 * <em>hints</em>; only chat ({@code thread.*}) carries a body.
 *
 * <p>Cross-cutting concerns (OAuth2 resource server + JWKS validation, the
 * event-fed authz replica used for channel entitlement checks, the pg-bus
 * poller, the standard web envelope, observability) are auto-configured from the
 * {@code commons-*} libraries. {@link EnableScheduling} drives the bus poller.
 * The gateway owns no domain schema: it only relies on the shared {@code bus}
 * tables (auto-created) and the commons authz/processed-event fragments.
 */
@SpringBootApplication
@EnableScheduling
// commons-* auto-config narrows @EnableJpaRepositories/@EntityScan to its own
// packages, disabling Boot's default scan of this service; re-declare them here.
@EnableJpaRepositories(basePackages = "com.restopanda.realtime")
@EntityScan(basePackages = "com.restopanda.realtime")
public class RealtimeGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(RealtimeGatewayApplication.class, args);
    }
}
