package com.restopanda.realtime.channel;

/**
 * The channel families the gateway fans out (Realtime design §C). A channel
 * is scoped to a tenant and one of these families plus an entity id, e.g.
 * {@code kds.station.stn_1}. The family determines the authorization rule a
 * subscriber must satisfy (staff entitlement + location membership, or guest
 * session scope).
 */
public enum ChannelFamily {

    /** A single kitchen station's live queue: {@code kds.station.{stationId}}. */
    KDS_STATION("kds.station"),
    /** A location's food-runner board: {@code kds.runner.{locationId}}. */
    KDS_RUNNER("kds.runner"),
    /** A location's floor/table map: {@code floor.{locationId}}. */
    FLOOR("floor"),
    /** A single dining/online session: {@code session.{sessionId}}. */
    SESSION("session"),
    /**
     * A single staff user's personal alert inbox: {@code user.{userId}}. A staff
     * caller may only ever subscribe to their <em>own</em> user channel (self-only),
     * so it is the one addressable-to-a-person surface (e.g. "table 12 is now yours").
     */
    USER("user"),
    /** A single chat thread (carries message bodies): {@code thread.{threadId}}. */
    THREAD("thread"),
    /** A location's cash-register/drawer board: {@code register.{locationId}}. */
    REGISTER("register"),
    /** A location's manager approval queue: {@code approvals.{locationId}}. */
    APPROVALS("approvals");

    private final String prefix;

    ChannelFamily(String prefix) {
        this.prefix = prefix;
    }

    /** The dotted prefix used in the channel string (e.g. {@code "kds.station"}). */
    public String prefix() {
        return prefix;
    }

    /**
     * Resolves the family whose prefix matches the given "family.id" body,
     * choosing the longest matching prefix so {@code kds.station} wins over any
     * shorter overlap.
     *
     * @param body the channel body after the {@code tenant:} scope
     * @return the matching family, or {@code null} if none matches
     */
    static ChannelFamily matching(String body) {
        ChannelFamily best = null;
        for (ChannelFamily f : values()) {
            String p = f.prefix + ".";
            if (body.startsWith(p)
                    && body.length() > p.length()
                    && (best == null || f.prefix.length() > best.prefix.length())) {
                best = f;
            }
        }
        return best;
    }
}
